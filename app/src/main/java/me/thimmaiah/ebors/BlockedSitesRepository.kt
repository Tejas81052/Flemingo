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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * User-managed list of domains to block, persisted locally.
 *
 * Persistence (audit B16): the durable store is the shared
 * [BrowserDatabase] SQLite file's `blocked_sites` table rather than a
 * JSON blob in `SharedPreferences`. The repository keeps an in-memory
 * [items] set so [snapshot] and the per-change push to
 * [BrowserBlocker.blockedDomains] stay synchronous and instant, and the
 * `Listener`/`broadcast` model is unchanged. Each mutation updates the
 * set synchronously and writes just the affected row(s) on
 * [BrowserDatabase]'s background writer.
 */
object BlockedSitesRepository {
    interface Listener {
        fun onBlockedSitesChanged(domains: List<String>)
    }

    // Retained only for the one-time JSON -> SQLite migration.
    private const val PREFS_NAME = "effective_browser_blocked_sites"
    private const val KEY_DOMAINS = "domains_json"
    private const val KEY_MIGRATED = "migrated_to_db"
    private const val TAG = "BlockedSitesRepository"

    /** Defensive ceiling so a malicious paste can't blow up the table. */
    private const val MAX_ENTRIES = 500

    private val items = LinkedHashSet<String>()
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
        pushToBlocker()
    }

    @Synchronized
    fun snapshot(): List<String> = items.sorted()

    /**
     * Parse one or many space/newline/comma-separated entries from user input.
     * Returns the number of *new* entries actually added.
     */
    fun addFromText(rawInput: String): Int {
        if (rawInput.isBlank()) return 0
        val candidates = rawInput
            .split('\n', ',', ' ', ';')
            .mapNotNull(::normalizeEntry)
        if (candidates.isEmpty()) return 0

        // Only the genuinely-new domains (LinkedHashSet.add == true) — both
        // the return count and the rows we write.
        val actuallyAdded = ArrayList<String>()
        synchronized(this) {
            for (entry in candidates) {
                if (items.size >= MAX_ENTRIES) break
                if (items.add(entry)) actuallyAdded.add(entry)
            }
        }
        if (actuallyAdded.isEmpty()) return 0

        db.runWrite { writable ->
            writable.beginTransaction()
            try {
                for (domain in actuallyAdded) {
                    // CONFLICT_IGNORE: domain is the primary key; if a
                    // concurrent path already inserted it, skip silently.
                    writable.insertWithOnConflict(
                        BrowserDatabase.TABLE_BLOCKED_SITES, null, domain.toContentValues(),
                        SQLiteDatabase.CONFLICT_IGNORE,
                    )
                }
                writable.setTransactionSuccessful()
            } finally {
                writable.endTransaction()
            }
        }
        pushToBlocker()
        broadcast()
        return actuallyAdded.size
    }

    fun remove(domain: String) {
        val didRemove: Boolean
        synchronized(this) {
            didRemove = items.remove(domain)
        }
        if (!didRemove) return
        db.runWrite {
            it.delete(
                BrowserDatabase.TABLE_BLOCKED_SITES,
                "${BrowserDatabase.COL_BLOCKED_DOMAIN} = ?",
                arrayOf(domain),
            )
        }
        pushToBlocker()
        broadcast()
    }

    fun clear() {
        val wasEmpty: Boolean
        synchronized(this) {
            wasEmpty = items.isEmpty()
            items.clear()
        }
        if (wasEmpty) return
        db.runWrite { it.delete(BrowserDatabase.TABLE_BLOCKED_SITES, null, null) }
        pushToBlocker()
        broadcast()
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
        val snap = snapshot()
        mainHandler.post { listener.onBlockedSitesChanged(snap) }
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun broadcast() {
        val snap = snapshot()
        mainHandler.post {
            for (listener in listeners) {
                try {
                    listener.onBlockedSitesChanged(snap)
                } catch (error: Exception) {
                    Log.w(TAG, "Listener threw", error)
                }
            }
        }
    }

    private fun pushToBlocker() {
        BrowserBlocker.blockedDomains = synchronized(this) { items.toSet() }
    }

    /** Load the table into the in-memory set, sorted, capped at [MAX_ENTRIES]. */
    private fun loadFromDb() {
        items.clear()
        db.readableDatabase.query(
            BrowserDatabase.TABLE_BLOCKED_SITES,
            null, null, null, null, null,
            "${BrowserDatabase.COL_BLOCKED_DOMAIN} ASC",
        ).use { cursor ->
            val idx = cursor.getColumnIndexOrThrow(BrowserDatabase.COL_BLOCKED_DOMAIN)
            while (cursor.moveToNext() && items.size < MAX_ENTRIES) {
                items.add(cursor.getString(idx))
            }
        }
    }

    /**
     * One-time import of the legacy `SharedPreferences` JSON array into
     * the SQLite table. Gated by a `migrated` flag; the import is a
     * single transaction, so a failure rolls back and is retried next
     * launch. On success the old JSON key is dropped. Synchronous, runs
     * at most once per install. Entries are re-normalised on the way in
     * so the table starts in a consistent state regardless of which app
     * version last wrote the blob.
     */
    private fun migrateFromPrefsIfNeeded() {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_MIGRATED, false)) return

        val raw = prefs.getString(KEY_DOMAINS, null)
        if (raw != null) {
            try {
                val array = JSONArray(raw)
                val writable = db.writableDatabase
                writable.beginTransaction()
                try {
                    var count = 0
                    for (i in 0 until array.length()) {
                        if (count >= MAX_ENTRIES) break
                        val entry = normalizeEntry(array.optString(i)) ?: continue
                        writable.insertWithOnConflict(
                            BrowserDatabase.TABLE_BLOCKED_SITES, null, entry.toContentValues(),
                            SQLiteDatabase.CONFLICT_IGNORE,
                        )
                        count++
                    }
                    writable.setTransactionSuccessful()
                } finally {
                    writable.endTransaction()
                }
            } catch (error: Exception) {
                Log.w(TAG, "Blocked-sites migration failed; starting with an empty table", error)
            }
        }
        prefs.edit {
            putBoolean(KEY_MIGRATED, true)
            remove(KEY_DOMAINS)
        }
    }

    private fun String.toContentValues(): ContentValues = ContentValues().apply {
        put(BrowserDatabase.COL_BLOCKED_DOMAIN, this@toContentValues)
    }

    /**
     * Accept anything from "youtube.com" to "https://www.YouTube.com/feed/foo"
     * and reduce to a normalized host. Delegates to [BlockedSitesNormalizer]
     * so unit tests can exercise the rules without booting the Android
     * runtime that this singleton's static init needs.
     */
    internal fun normalizeEntry(rawValue: String?): String? =
        BlockedSitesNormalizer.normalize(rawValue)
}
