/*
 * Copyright 2026 Tejas Thimmaiah
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package me.thimmaiah.ebors

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.edit
import org.json.JSONArray
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

data class Bookmark(
    val id: String,
    val title: String,
    val url: String,
    val createdAt: Long,
)

/**
 * Stores the user's bookmarks.
 *
 * Persistence (audit B16): the durable store is now the shared
 * [BrowserDatabase] SQLite file's `bookmarks` table rather than a JSON
 * blob in `SharedPreferences`. The repository keeps an in-memory cache
 * ([items]) so reads — including the synchronous [isBookmarked] call the
 * browser menu makes on every open — stay instant and the
 * `Listener`/`broadcast` model and all callers are untouched. Each
 * mutation updates the cache synchronously and writes just the affected
 * row(s) on [BrowserDatabase]'s background writer.
 */
object BookmarkRepository {
    interface Listener {
        fun onBookmarksChanged(bookmarks: List<Bookmark>)
    }

    // Retained only for the one-time JSON -> SQLite migration and the
    // `migrated` flag that gates it.
    private const val PREFS_NAME = "effective_browser_bookmarks"
    private const val KEY_BOOKMARKS = "bookmarks_json"
    private const val KEY_MIGRATED = "migrated_to_db"
    /** Once-per-install flag so the v10 default Pinned set isn't
     *  re-seeded if the user deletes them. The seed runs exactly
     *  once; after that the Pinned grid mirrors the user's real
     *  bookmarks (and goes empty when they remove the last one). */
    private const val KEY_DEFAULTS_SEEDED = "defaults_seeded"
    private const val TAG = "BookmarkRepository"

    /**
     * v10 Pinned-grid defaults. Seeded into the database on first
     * install via [seedDefaultBookmarksIfNeeded] so the start page's
     * Pinned section shows a populated grid out of the box. Once the
     * user deletes any of these they stay deleted — the seed never
     * re-runs because [KEY_DEFAULTS_SEEDED] flips on first run.
     */
    private val DEFAULT_PINNED = listOf(
        "Coffee" to "https://www.craftcoffee.dev/",
        "Are.na" to "https://www.are.na/",
        "HN" to "https://news.ycombinator.com/",
        "Docs" to "https://docs.google.com/",
        "Times" to "https://www.nytimes.com/",
        "GitHub" to "https://github.com/",
        "Maps" to "https://maps.google.com/",
        "DDG" to "https://duckduckgo.com/",
    )

    private val items = mutableListOf<Bookmark>()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var initialized = false
    private lateinit var appContext: Context
    private lateinit var db: BrowserDatabase

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        db = BrowserDatabase.get(appContext)
        migrateFromPrefsIfNeeded()
        loadFromDb()
        seedDefaultBookmarksIfNeeded()
        initialized = true
    }

    /**
     * Populate the bookmark store with the v10 Pinned defaults on
     * first run, so the start page's Pinned grid is non-empty out
     * of the box. Gated by [KEY_DEFAULTS_SEEDED] so a user who
     * deletes all the defaults doesn't see them reappear on the
     * next launch.
     *
     * Skipped entirely if the user already has any bookmarks
     * (covers the migration case where someone upgrades from v9
     * with an existing bookmark set we don't want to clutter).
     */
    private fun seedDefaultBookmarksIfNeeded() {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DEFAULTS_SEEDED, false)) return
        if (items.isNotEmpty()) {
            prefs.edit { putBoolean(KEY_DEFAULTS_SEEDED, true) }
            return
        }
        val now = System.currentTimeMillis()
        // Insert in reverse so that snapshot()'s desc-by-createdAt sort
        // lands the prototype's first tile (Coffee) at the top of the
        // grid. 1ms spacing keeps the ordering stable even on devices
        // whose currentTimeMillis ticks slowly.
        val seeded = DEFAULT_PINNED.mapIndexed { index, (title, url) ->
            Bookmark(
                id = UUID.randomUUID().toString(),
                title = title,
                url = normalizeUrl(url),
                createdAt = now - index,
            )
        }
        items.addAll(seeded)
        db.runWrite { writable ->
            writable.beginTransaction()
            try {
                for (bookmark in seeded) {
                    writable.insertWithOnConflict(
                        BrowserDatabase.TABLE_BOOKMARKS,
                        null,
                        bookmark.toContentValues(),
                        SQLiteDatabase.CONFLICT_REPLACE,
                    )
                }
                writable.setTransactionSuccessful()
            } finally {
                writable.endTransaction()
            }
        }
        prefs.edit { putBoolean(KEY_DEFAULTS_SEEDED, true) }
        broadcast()
    }

    @Synchronized
    fun snapshot(): List<Bookmark> = items.sortedByDescending(Bookmark::createdAt)

    @Synchronized
    fun isBookmarked(url: String): Boolean {
        val needle = normalizeUrl(url)
        return items.any { normalizeUrl(it.url) == needle }
    }

    fun addOrRemove(url: String, title: String): Boolean {
        // B3: dedupe on a normalised form so trailing slashes, fragments
        // and case-only differences in the host don't produce visually
        // identical duplicate bookmarks. The stored URL keeps the
        // canonical (normalised) form so subsequent lookups also match.
        val canonical = normalizeUrl(url)
        val nowBookmarked: Boolean
        var bookmarkToInsert: Bookmark? = null
        var idToDelete: String? = null
        synchronized(this) {
            val existing = items.indexOfFirst { normalizeUrl(it.url) == canonical }
            if (existing >= 0) {
                idToDelete = items.removeAt(existing).id
                nowBookmarked = false
            } else {
                val bookmark = Bookmark(
                    id = UUID.randomUUID().toString(),
                    title = title.ifBlank { canonical },
                    url = canonical,
                    createdAt = System.currentTimeMillis(),
                )
                items.add(bookmark)
                bookmarkToInsert = bookmark
                nowBookmarked = true
            }
        }
        // DB write off-lock, off the caller's thread.
        bookmarkToInsert?.let { inserted ->
            db.runWrite { it.insertWithOnConflict(
                BrowserDatabase.TABLE_BOOKMARKS, null, inserted.toContentValues(),
                SQLiteDatabase.CONFLICT_REPLACE,
            ) }
        }
        idToDelete?.let { id -> deleteRow(id) }
        broadcast()
        return nowBookmarked
    }

    fun remove(id: String) {
        val removed: Boolean
        synchronized(this) {
            removed = items.removeAll { it.id == id }
        }
        if (!removed) return
        deleteRow(id)
        broadcast()
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
        // Always dispatch to main thread so the listener can safely touch UI.
        val snap = snapshot()
        mainHandler.post { listener.onBookmarksChanged(snap) }
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun broadcast() {
        val snap = snapshot()
        mainHandler.post {
            for (listener in listeners) {
                try {
                    listener.onBookmarksChanged(snap)
                } catch (error: Exception) {
                    Log.w(TAG, "Listener threw", error)
                }
            }
        }
    }

    private fun deleteRow(id: String) {
        db.runWrite {
            it.delete(BrowserDatabase.TABLE_BOOKMARKS, "${BrowserDatabase.COL_BOOKMARK_ID} = ?", arrayOf(id))
        }
    }

    /** Load the whole table into the in-memory cache, newest first. */
    private fun loadFromDb() {
        items.clear()
        db.readableDatabase.query(
            BrowserDatabase.TABLE_BOOKMARKS,
            null, null, null, null, null,
            "${BrowserDatabase.COL_BOOKMARK_CREATED_AT} DESC",
        ).use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(BrowserDatabase.COL_BOOKMARK_ID)
            val titleIdx = cursor.getColumnIndexOrThrow(BrowserDatabase.COL_BOOKMARK_TITLE)
            val urlIdx = cursor.getColumnIndexOrThrow(BrowserDatabase.COL_BOOKMARK_URL)
            val createdIdx = cursor.getColumnIndexOrThrow(BrowserDatabase.COL_BOOKMARK_CREATED_AT)
            while (cursor.moveToNext()) {
                items.add(
                    Bookmark(
                        id = cursor.getString(idIdx),
                        title = cursor.getString(titleIdx),
                        url = cursor.getString(urlIdx),
                        createdAt = cursor.getLong(createdIdx),
                    ),
                )
            }
        }
    }

    /**
     * One-time import of the legacy `SharedPreferences` JSON blob into the
     * SQLite table. Gated by a `migrated` flag so it runs at most once per
     * install. The whole import is a single transaction — if anything
     * throws, it rolls back, the flag stays unset, and the next launch
     * retries. On success the old JSON key is dropped.
     *
     * Runs synchronously on the caller's thread (the same thread the old
     * `load()` ran on); it touches the DB at most once, ever.
     */
    private fun migrateFromPrefsIfNeeded() {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_MIGRATED, false)) return

        val raw = prefs.getString(KEY_BOOKMARKS, null)
        if (raw != null) {
            try {
                val legacy = parseLegacyJson(raw)
                val writable = db.writableDatabase
                writable.beginTransaction()
                try {
                    for (bookmark in legacy) {
                        writable.insertWithOnConflict(
                            BrowserDatabase.TABLE_BOOKMARKS, null, bookmark.toContentValues(),
                            SQLiteDatabase.CONFLICT_REPLACE,
                        )
                    }
                    writable.setTransactionSuccessful()
                } finally {
                    writable.endTransaction()
                }
            } catch (error: Exception) {
                // A corrupt legacy blob shouldn't block the migration
                // flag forever (that would retry the failing parse on
                // every launch). Log, start clean — exactly what the old
                // "reset corrupt store" path did.
                Log.w(TAG, "Bookmark migration failed; starting with an empty table", error)
            }
        }
        prefs.edit {
            putBoolean(KEY_MIGRATED, true)
            remove(KEY_BOOKMARKS)
        }
    }

    /**
     * Parse the legacy JSON array and apply the same canonicalisation +
     * de-duplication the old `load()` did, so the table starts in the
     * post-B3 normalised state regardless of which app version last
     * wrote the blob.
     */
    private fun parseLegacyJson(raw: String): List<Bookmark> {
        val array = JSONArray(raw)
        val result = ArrayList<Bookmark>(array.length())
        val seenCanonical = HashSet<String>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val canonicalUrl = normalizeUrl(obj.optString("url"))
            if (!seenCanonical.add(canonicalUrl)) continue // drop duplicates
            result.add(
                Bookmark(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    title = obj.optString("title"),
                    url = canonicalUrl,
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                ),
            )
        }
        return result
    }

    private fun Bookmark.toContentValues(): ContentValues = ContentValues().apply {
        put(BrowserDatabase.COL_BOOKMARK_ID, id)
        put(BrowserDatabase.COL_BOOKMARK_TITLE, title)
        put(BrowserDatabase.COL_BOOKMARK_URL, url)
        put(BrowserDatabase.COL_BOOKMARK_CREATED_AT, createdAt)
    }

    /**
     * Canonicalise [url] for equality comparisons. Delegates to the
     * shared [UrlInputUtils.canonicalForCompare] so a history entry and
     * a bookmark agree on what "same URL" means — `isBookmarked` was
     * matching only on raw strings, so a history-redirected URL with a
     * trailing slash failed to find the bookmarked form. Now both
     * repositories share one canonicalisation pass.
     */
    private fun normalizeUrl(url: String): String =
        UrlInputUtils.canonicalForCompare(url)
}
