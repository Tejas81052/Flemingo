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

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.util.concurrent.Executors

/**
 * Shared on-device SQLite store for the three "list" repositories:
 * [BookmarkRepository], [HistoryRepository] and [BlockedSitesRepository].
 *
 * # Why this exists (audit item B16)
 *
 * Those three repositories previously serialised their entire contents
 * to a JSON blob in `SharedPreferences` on **every** mutation. For
 * history that meant rewriting up to ~500 entries (~tens of KB) on every
 * single navigation, and a single corrupt character anywhere in the blob
 * tripped the "reset the whole store" catch clause. A real table store
 * fixes both: writes touch only the changed rows, and a problem with one
 * row can't take out the others.
 *
 * # Why hand-rolled SQLite and not Room
 *
 * This module's build (AGP 9.1.0) keeps Kotlin *inside* AGP — there is
 * no Kotlin version declared in `libs.versions.toml` and no Kotlin
 * Gradle plugin applied. Room needs an annotation processor (KSP) whose
 * version must line up with the Kotlin compiler version; with the
 * compiler version not surfaced anywhere, wiring KSP in is a guess that
 * breaks the build when wrong. The framework's [SQLiteOpenHelper] needs
 * **zero** new dependencies or plugins and delivers the same B16 value
 * (incremental row writes, integrity, real schema). The audit explicitly
 * offered "Room *or a tiny SQLite schema*" — this is the latter.
 *
 * # Threading
 *
 *  - **Reads** happen once per repository, synchronously, inside
 *    `initialize(...)` — the same place the old code read the JSON blob.
 *    For ≤500 small rows this is a few milliseconds; keeping it
 *    synchronous preserves the existing "initialize then immediately
 *    use" contract with zero Activity changes.
 *  - **Writes** go through [runWrite], which hops onto a single shared
 *    background thread. One writer thread across all three repositories
 *    means no SQLite write-lock contention and FIFO ordering, and keeps
 *    disk I/O off the caller's thread. (`SQLiteDatabase` is itself
 *    thread-safe, so a synchronous read on the main thread during
 *    `initialize` and a background write never corrupt each other — but
 *    in practice no write is ever enqueued before `initialize` returns.)
 *
 * Durability note: like the old `SharedPreferences.apply()`, a write
 * enqueued via [runWrite] is not yet on disk when the method returns. The
 * window is a single executor hop and matches the prior behaviour.
 */
internal class BrowserDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_BOOKMARKS)
        db.execSQL(CREATE_HISTORY)
        db.execSQL(CREATE_HISTORY_VISITED_INDEX)
        db.execSQL(CREATE_HISTORY_URL_INDEX)
        db.execSQL(CREATE_BLOCKED_SITES)
        db.execSQL(CREATE_DOWNLOADS)
        db.execSQL(CREATE_DOWNLOADS_CREATED_INDEX)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // The one-time SharedPreferences -> SQLite *import* is NOT done
        // here — it's per-repository, gated by a `migrated` pref flag, so
        // each repository owns its own legacy-format knowledge. onUpgrade
        // only evolves the SQLite schema itself.
        //
        // Each `if (oldVersion < N)` step is independent and additive,
        // so an installation skipping intermediate versions (e.g.,
        // v1 -> v3) replays every step in order. Critically, each step
        // applies the *v(N-1) -> v(N)* delta — not the latest schema —
        // so the next step's ALTER doesn't blow up with a
        // "duplicate column" error.

        // v1 -> v2: the three "list" repositories shipped first (v1,
        // three tables); the downloads table was added in the follow-up
        // pass. A device that already created the v1 file needs the
        // table added without touching the existing three. We create it
        // here at the *v2* schema (no profile_name), and let the v2->v3
        // step below add that column.
        if (oldVersion < 2) {
            db.execSQL(CREATE_DOWNLOADS_V2)
            db.execSQL(CREATE_DOWNLOADS_CREATED_INDEX)
        }
        // v2 -> v3: add downloads.profile_name (incognito-aware
        // downloads). Existing rows get NULL, which DownloadTask
        // interprets as "use the default profile's CookieManager".
        if (oldVersion < 3) {
            db.execSQL(ADD_DOWNLOADS_PROFILE_NAME)
        }
    }

    /**
     * Run [block] against the writable database on the shared background
     * writer thread. Exceptions are logged and swallowed: a failed write
     * must not kill the writer thread (that would silently drop every
     * later write) and the in-memory cache in the calling repository
     * already reflects the change, so the UI stays consistent — only
     * durability of that one write is lost, exactly as a failed
     * `SharedPreferences.apply()` would behave.
     */
    fun runWrite(block: (SQLiteDatabase) -> Unit) {
        writeExecutor.execute {
            try {
                block(writableDatabase)
            } catch (error: Exception) {
                Log.w(TAG, "Database write failed", error)
            }
        }
    }

    companion object {
        private const val TAG = "BrowserDatabase"
        private const val DB_NAME = "effective_browser.db"

        // v1: bookmarks + history + blocked_sites (first list-repo pass).
        // v2: + downloads table.
        // v3: + downloads.profile_name (incognito-aware downloads).
        // See onUpgrade for the staged migration.
        private const val DB_VERSION = 3

        // ---- bookmarks table ------------------------------------------
        const val TABLE_BOOKMARKS = "bookmarks"
        const val COL_BOOKMARK_ID = "id"
        const val COL_BOOKMARK_TITLE = "title"
        const val COL_BOOKMARK_URL = "url"
        const val COL_BOOKMARK_CREATED_AT = "created_at"

        // ---- history table --------------------------------------------
        const val TABLE_HISTORY = "history"
        const val COL_HISTORY_ID = "id"
        const val COL_HISTORY_TITLE = "title"
        const val COL_HISTORY_URL = "url"
        const val COL_HISTORY_VISITED_AT = "visited_at"

        // ---- blocked sites table --------------------------------------
        const val TABLE_BLOCKED_SITES = "blocked_sites"
        const val COL_BLOCKED_DOMAIN = "domain"

        // ---- downloads table ------------------------------------------
        // One row per download. `bytesPerSecond` from DownloadItem is
        // deliberately NOT a column: it's a transient speed estimate
        // recomputed each run, never persisted (the old toJson() didn't
        // persist it either). `user_paused` is stored as 0/1.
        const val TABLE_DOWNLOADS = "downloads"
        const val COL_DOWNLOAD_ID = "id"
        const val COL_DOWNLOAD_URL = "url"
        const val COL_DOWNLOAD_FILE_NAME = "file_name"
        const val COL_DOWNLOAD_MIME_TYPE = "mime_type"
        const val COL_DOWNLOAD_HOST = "host"
        const val COL_DOWNLOAD_FILE_PATH = "file_path"
        const val COL_DOWNLOAD_TEMP_FILE_PATH = "temp_file_path"
        const val COL_DOWNLOAD_USER_AGENT = "user_agent"
        const val COL_DOWNLOAD_REFERER = "referer"
        const val COL_DOWNLOAD_CONTENT_LENGTH_HINT = "content_length_hint"
        const val COL_DOWNLOAD_STATUS = "status"
        const val COL_DOWNLOAD_DOWNLOADED_BYTES = "downloaded_bytes"
        const val COL_DOWNLOAD_TOTAL_BYTES = "total_bytes"
        const val COL_DOWNLOAD_ERROR_MESSAGE = "error_message"
        const val COL_DOWNLOAD_USER_PAUSED = "user_paused"
        const val COL_DOWNLOAD_CREATED_AT = "created_at"
        const val COL_DOWNLOAD_UPDATED_AT = "updated_at"
        // Added in DB v3 (nullable). NULL = default WebView profile;
        // a value like "incognito" routes the cookie lookup in
        // DownloadTask through the matching AndroidX webkit Profile.
        const val COL_DOWNLOAD_PROFILE_NAME = "profile_name"

        // DDL kept as plain `val` (not `const val`) so the string-template
        // references to the name constants above are unambiguous.
        private val CREATE_BOOKMARKS =
            "CREATE TABLE $TABLE_BOOKMARKS (" +
                "$COL_BOOKMARK_ID TEXT PRIMARY KEY NOT NULL, " +
                "$COL_BOOKMARK_TITLE TEXT NOT NULL, " +
                "$COL_BOOKMARK_URL TEXT NOT NULL, " +
                "$COL_BOOKMARK_CREATED_AT INTEGER NOT NULL)"

        private val CREATE_HISTORY =
            "CREATE TABLE $TABLE_HISTORY (" +
                "$COL_HISTORY_ID TEXT PRIMARY KEY NOT NULL, " +
                "$COL_HISTORY_TITLE TEXT NOT NULL, " +
                "$COL_HISTORY_URL TEXT NOT NULL, " +
                "$COL_HISTORY_VISITED_AT INTEGER NOT NULL)"

        // Indexes the in-memory cache doesn't strictly need today, but
        // they cost nothing to maintain at these row counts and set up
        // the indexed time-range / URL lookups the audit called for.
        private val CREATE_HISTORY_VISITED_INDEX =
            "CREATE INDEX idx_history_visited_at ON $TABLE_HISTORY($COL_HISTORY_VISITED_AT)"
        private val CREATE_HISTORY_URL_INDEX =
            "CREATE INDEX idx_history_url ON $TABLE_HISTORY($COL_HISTORY_URL)"

        private val CREATE_BLOCKED_SITES =
            "CREATE TABLE $TABLE_BLOCKED_SITES (" +
                "$COL_BLOCKED_DOMAIN TEXT PRIMARY KEY NOT NULL)"

        // Current (v3) downloads schema. `onCreate` uses this for
        // fresh installs; the staged upgrade in `onUpgrade` from v1
        // creates the table at v2's schema and then ALTERs to add
        // `profile_name`, mirroring the migration history exactly so
        // future schema bumps stay independent.
        private val CREATE_DOWNLOADS =
            "CREATE TABLE $TABLE_DOWNLOADS (" +
                "$COL_DOWNLOAD_ID TEXT PRIMARY KEY NOT NULL, " +
                "$COL_DOWNLOAD_URL TEXT NOT NULL, " +
                "$COL_DOWNLOAD_FILE_NAME TEXT NOT NULL, " +
                "$COL_DOWNLOAD_MIME_TYPE TEXT, " +
                "$COL_DOWNLOAD_HOST TEXT NOT NULL, " +
                "$COL_DOWNLOAD_FILE_PATH TEXT NOT NULL, " +
                "$COL_DOWNLOAD_TEMP_FILE_PATH TEXT NOT NULL, " +
                "$COL_DOWNLOAD_USER_AGENT TEXT, " +
                "$COL_DOWNLOAD_REFERER TEXT, " +
                "$COL_DOWNLOAD_CONTENT_LENGTH_HINT INTEGER NOT NULL, " +
                "$COL_DOWNLOAD_STATUS TEXT NOT NULL, " +
                "$COL_DOWNLOAD_DOWNLOADED_BYTES INTEGER NOT NULL, " +
                "$COL_DOWNLOAD_TOTAL_BYTES INTEGER NOT NULL, " +
                "$COL_DOWNLOAD_ERROR_MESSAGE TEXT, " +
                "$COL_DOWNLOAD_USER_PAUSED INTEGER NOT NULL, " +
                "$COL_DOWNLOAD_CREATED_AT INTEGER NOT NULL, " +
                "$COL_DOWNLOAD_UPDATED_AT INTEGER NOT NULL, " +
                "$COL_DOWNLOAD_PROFILE_NAME TEXT)"

        // The shape of the downloads table in DB v2 — same as v3 minus
        // the trailing `profile_name` column. Only used by the
        // v1->v3 onUpgrade path, which deliberately creates at the v2
        // schema first so the v2->v3 ALTER step then succeeds without
        // a duplicate-column error.
        private val CREATE_DOWNLOADS_V2 =
            "CREATE TABLE $TABLE_DOWNLOADS (" +
                "$COL_DOWNLOAD_ID TEXT PRIMARY KEY NOT NULL, " +
                "$COL_DOWNLOAD_URL TEXT NOT NULL, " +
                "$COL_DOWNLOAD_FILE_NAME TEXT NOT NULL, " +
                "$COL_DOWNLOAD_MIME_TYPE TEXT, " +
                "$COL_DOWNLOAD_HOST TEXT NOT NULL, " +
                "$COL_DOWNLOAD_FILE_PATH TEXT NOT NULL, " +
                "$COL_DOWNLOAD_TEMP_FILE_PATH TEXT NOT NULL, " +
                "$COL_DOWNLOAD_USER_AGENT TEXT, " +
                "$COL_DOWNLOAD_REFERER TEXT, " +
                "$COL_DOWNLOAD_CONTENT_LENGTH_HINT INTEGER NOT NULL, " +
                "$COL_DOWNLOAD_STATUS TEXT NOT NULL, " +
                "$COL_DOWNLOAD_DOWNLOADED_BYTES INTEGER NOT NULL, " +
                "$COL_DOWNLOAD_TOTAL_BYTES INTEGER NOT NULL, " +
                "$COL_DOWNLOAD_ERROR_MESSAGE TEXT, " +
                "$COL_DOWNLOAD_USER_PAUSED INTEGER NOT NULL, " +
                "$COL_DOWNLOAD_CREATED_AT INTEGER NOT NULL, " +
                "$COL_DOWNLOAD_UPDATED_AT INTEGER NOT NULL)"

        private val ADD_DOWNLOADS_PROFILE_NAME =
            "ALTER TABLE $TABLE_DOWNLOADS ADD COLUMN $COL_DOWNLOAD_PROFILE_NAME TEXT"

        // Snapshots are ordered newest-first by created_at.
        private val CREATE_DOWNLOADS_CREATED_INDEX =
            "CREATE INDEX idx_downloads_created_at ON $TABLE_DOWNLOADS($COL_DOWNLOAD_CREATED_AT)"

        /** One writer thread shared by every repository — see the
         *  Threading note in the class KDoc. Daemon so it never blocks
         *  process exit. */
        private val writeExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "browser-db-writer").apply { isDaemon = true }
        }

        @Volatile
        private var instance: BrowserDatabase? = null

        /** Process-wide singleton. `SQLiteOpenHelper` is designed to be
         *  long-lived and shared; opening multiple helpers on one DB file
         *  invites lock contention. */
        fun get(context: Context): BrowserDatabase =
            instance ?: synchronized(this) {
                instance ?: BrowserDatabase(context).also { instance = it }
            }
    }
}
