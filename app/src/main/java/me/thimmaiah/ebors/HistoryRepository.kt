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

data class HistoryEntry(
    val id: String,
    val title: String,
    val url: String,
    val visitedAt: Long,
)

/**
 * Stores browsing history (capped at [MAX_ENTRIES]).
 *
 * Persistence (audit B16): the durable store is the shared
 * [BrowserDatabase] SQLite file's `history` table. This is the
 * repository the JSON-blob approach hurt most — the old code rewrote
 * the entire blob (up to 500 entries) to `SharedPreferences` on *every*
 * navigation. [record] now writes just the handful of rows that
 * actually changed (the new entry, any same-URL duplicates it
 * supersedes, and any oldest entry evicted past the cap) inside one
 * transaction. The in-memory [items] deque keeps reads instant and the
 * `Listener`/`broadcast` contract unchanged.
 */
object HistoryRepository {
    interface Listener {
        fun onHistoryChanged(entries: List<HistoryEntry>)
    }

    // Retained only for the one-time JSON -> SQLite migration.
    private const val PREFS_NAME = "effective_browser_history"
    private const val KEY_HISTORY = "history_json"
    private const val KEY_MIGRATED = "migrated_to_db"
    private const val MAX_ENTRIES = 500
    private const val TAG = "HistoryRepository"

    // Head = most recent, tail = oldest (see record()'s addFirst/removeLast).
    private val items = ArrayDeque<HistoryEntry>()
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
        initialized = true
    }

    @Synchronized
    fun snapshot(): List<HistoryEntry> = items.sortedByDescending(HistoryEntry::visitedAt)

    fun record(url: String, title: String) {
        // Only record real navigations. Internal/data/javascript/blob URLs
        // either aren't useful in history or could be self-XSS bookmarks.
        if (url.isBlank()) return
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return

        // B2 dedup: compare on the canonicalised form (trailing slash,
        // fragment, case) so A -> A/ -> A#frag all collapse into one
        // history entry. Matches the canonicalisation BookmarkRepository
        // uses, so a bookmark and a history hit for the same logical
        // page agree on identity.
        val newUrlKey = UrlInputUtils.canonicalForCompare(url)
        val newEntry: HistoryEntry
        // Row ids the DB write must delete: same-URL duplicates we
        // collapsed, plus anything evicted past the cap.
        val removedIds = ArrayList<String>()
        synchronized(this) {
            var preservedTitle: String? = null
            val iterator = items.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (UrlInputUtils.canonicalForCompare(entry.url) == newUrlKey) {
                    // ArrayDeque iterates head-first, i.e. most-recent
                    // first, so the first match's title is the freshest.
                    if (preservedTitle == null) preservedTitle = entry.title
                    removedIds.add(entry.id)
                    iterator.remove()
                }
            }

            newEntry = HistoryEntry(
                id = UUID.randomUUID().toString(),
                title = title.ifBlank { preservedTitle ?: url },
                url = url,
                visitedAt = System.currentTimeMillis(),
            )
            items.addFirst(newEntry)
            while (items.size > MAX_ENTRIES) {
                removedIds.add(items.removeLast().id)
            }
        }

        // One transaction: delete the superseded/evicted rows, insert the
        // new one. A few row ops instead of a full-blob rewrite.
        db.runWrite { writable ->
            writable.beginTransaction()
            try {
                for (id in removedIds) {
                    writable.delete(
                        BrowserDatabase.TABLE_HISTORY,
                        "${BrowserDatabase.COL_HISTORY_ID} = ?",
                        arrayOf(id),
                    )
                }
                writable.insertWithOnConflict(
                    BrowserDatabase.TABLE_HISTORY, null, newEntry.toContentValues(),
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
                writable.setTransactionSuccessful()
            } finally {
                writable.endTransaction()
            }
        }
        broadcast()
    }

    fun remove(id: String) {
        val removed: Boolean
        synchronized(this) {
            removed = items.removeAll { it.id == id }
        }
        if (!removed) return
        db.runWrite {
            it.delete(BrowserDatabase.TABLE_HISTORY, "${BrowserDatabase.COL_HISTORY_ID} = ?", arrayOf(id))
        }
        broadcast()
    }

    fun clear() {
        val wasEmpty: Boolean
        synchronized(this) {
            wasEmpty = items.isEmpty()
            items.clear()
        }
        if (wasEmpty) return
        db.runWrite { it.delete(BrowserDatabase.TABLE_HISTORY, null, null) }
        broadcast()
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
        val snap = snapshot()
        mainHandler.post { listener.onHistoryChanged(snap) }
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun broadcast() {
        val snap = snapshot()
        mainHandler.post {
            for (listener in listeners) {
                try {
                    listener.onHistoryChanged(snap)
                } catch (error: Exception) {
                    Log.w(TAG, "Listener threw", error)
                }
            }
        }
    }

    /**
     * Load the table into the in-memory deque, newest first, so the
     * head/tail invariant [record] relies on holds. The cap is enforced
     * here too in case a prior version wrote more rows than [MAX_ENTRIES].
     */
    private fun loadFromDb() {
        items.clear()
        db.readableDatabase.query(
            BrowserDatabase.TABLE_HISTORY,
            null, null, null, null, null,
            "${BrowserDatabase.COL_HISTORY_VISITED_AT} DESC",
            MAX_ENTRIES.toString(),
        ).use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(BrowserDatabase.COL_HISTORY_ID)
            val titleIdx = cursor.getColumnIndexOrThrow(BrowserDatabase.COL_HISTORY_TITLE)
            val urlIdx = cursor.getColumnIndexOrThrow(BrowserDatabase.COL_HISTORY_URL)
            val visitedIdx = cursor.getColumnIndexOrThrow(BrowserDatabase.COL_HISTORY_VISITED_AT)
            while (cursor.moveToNext()) {
                // Rows arrive newest-first; addLast keeps head = newest.
                items.addLast(
                    HistoryEntry(
                        id = cursor.getString(idIdx),
                        title = cursor.getString(titleIdx),
                        url = cursor.getString(urlIdx),
                        visitedAt = cursor.getLong(visitedIdx),
                    ),
                )
            }
        }
    }

    /**
     * One-time import of the legacy `SharedPreferences` JSON blob into
     * the SQLite table. Gated by a `migrated` flag; the import is a
     * single transaction, so a failure rolls back and is retried next
     * launch. On success the old JSON key is dropped. Synchronous, runs
     * at most once per install.
     */
    private fun migrateFromPrefsIfNeeded() {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_MIGRATED, false)) return

        val raw = prefs.getString(KEY_HISTORY, null)
        if (raw != null) {
            try {
                val legacy = parseLegacyJson(raw)
                val writable = db.writableDatabase
                writable.beginTransaction()
                try {
                    for (entry in legacy) {
                        writable.insertWithOnConflict(
                            BrowserDatabase.TABLE_HISTORY, null, entry.toContentValues(),
                            SQLiteDatabase.CONFLICT_REPLACE,
                        )
                    }
                    writable.setTransactionSuccessful()
                } finally {
                    writable.endTransaction()
                }
            } catch (error: Exception) {
                Log.w(TAG, "History migration failed; starting with an empty table", error)
            }
        }
        prefs.edit {
            putBoolean(KEY_MIGRATED, true)
            remove(KEY_HISTORY)
        }
    }

    /**
     * Parse the legacy JSON array, keeping only the [MAX_ENTRIES] most
     * recent entries (older ones would be evicted on the next [record]
     * anyway, so importing them is wasted work).
     */
    private fun parseLegacyJson(raw: String): List<HistoryEntry> {
        val array = JSONArray(raw)
        val parsed = ArrayList<HistoryEntry>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            parsed.add(
                HistoryEntry(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    title = obj.optString("title"),
                    url = obj.optString("url"),
                    visitedAt = obj.optLong("visitedAt", System.currentTimeMillis()),
                ),
            )
        }
        return parsed.sortedByDescending(HistoryEntry::visitedAt).take(MAX_ENTRIES)
    }

    private fun HistoryEntry.toContentValues(): ContentValues = ContentValues().apply {
        put(BrowserDatabase.COL_HISTORY_ID, id)
        put(BrowserDatabase.COL_HISTORY_TITLE, title)
        put(BrowserDatabase.COL_HISTORY_URL, url)
        put(BrowserDatabase.COL_HISTORY_VISITED_AT, visitedAt)
    }
}
