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
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.util.Log
import android.webkit.CookieManager
import android.webkit.URLUtil
import androidx.core.content.edit
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max

object DownloadRepository {
    interface Listener {
        fun onDownloadsChanged(downloads: List<DownloadItem>)
    }

    // Retained only for the one-time JSON -> SQLite migration and the
    // `migrated` flag that gates it. The durable store is now the
    // `downloads` table in the shared [BrowserDatabase].
    private const val PREFS_NAME = "effective_browser_downloads"
    private const val KEY_DOWNLOADS = "downloads_json"
    private const val KEY_MIGRATED = "migrated_to_db"
    private const val TAG = "DownloadRepository"

    /** B5: ceiling for the `foo (N).ext` linear collision search. After
     *  this many failed candidates we switch to a millisecond-suffixed
     *  name that won't collide. */
    private const val MAX_NAME_COLLISION_ATTEMPTS = 1024

    /** B18: hard cap on a single download. Anything larger is refused so
     *  a misbehaving server can't fill the user's disk. ~8 GiB is a
     *  generous upper bound for legitimate browser downloads. */
    private const val MAX_DOWNLOAD_BYTES = 8L * 1024L * 1024L * 1024L

    private val listeners = CopyOnWriteArraySet<Listener>()
    // Bounded thread pool so a flood of enqueueDownload calls (a buggy or
    // hostile page firing many download intents, or the user picking
    // "Save all images" on a long article) can't spawn an unbounded number
    // of worker threads. Four concurrent transfers is plenty for a mobile
    // browser: more than that saturates a typical home uplink and starts
    // hurting per-stream throughput. Excess work queues on the executor
    // and starts as soon as a slot frees.
    private const val MAX_CONCURRENT_DOWNLOADS = 4
    private val executor: ExecutorService = Executors.newFixedThreadPool(
        MAX_CONCURRENT_DOWNLOADS,
    ) { runnable ->
        Thread(runnable, "ebors-download").apply { isDaemon = true }
    }
    private val tasks = ConcurrentHashMap<String, DownloadTask>()
    private val items = LinkedHashMap<String, DownloadItem>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS) // no overall cap — downloads can be huge
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            // Reuse connections aggressively — TLS handshake is the main
            // hidden cost on a "fast internet" link with small files.
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .build()
    }

    @Volatile
    private var initialized = false
    private lateinit var appContext: Context
    private lateinit var db: BrowserDatabase

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) {
            return
        }

        appContext = context.applicationContext
        db = BrowserDatabase.get(appContext)
        migrateFromPrefsIfNeeded()
        loadFromDb()
        initialized = true
        if (getActiveDownloadsLocked().isNotEmpty()) {
            ensureServiceRunning()
        }
        resumePendingDownloads()
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
        // Push the current snapshot off-lock to the main thread so the listener
        // doesn't have to worry about threading or re-entrant locks.
        val snap = synchronized(this) { snapshotLocked() }
        mainHandler.post { listener.onDownloadsChanged(snap) }
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /**
     * Enqueue a new download. The `cookie` parameter from the previous
     * API is intentionally absent — see V4 in the audit. Cookies are
     * fetched live from [CookieManager] inside [DownloadTask.run] each
     * time a request is issued, so the download always uses whatever
     * session the user has *now* rather than a frozen snapshot from
     * enqueue time.
     *
     * @param profileName the AndroidX webkit Profile the source tab is
     *   bound to, or null for the default profile. A non-null value
     *   (currently always `"incognito"`) routes the live cookie lookup
     *   through that profile's CookieManager so a download from a
     *   private tab uses the private session's auth. If the named
     *   profile no longer exists at request time (the user closed the
     *   last private tab, wiping it), the request goes out with no
     *   Cookie header — we deliberately don't fall back to the
     *   default profile's cookies, because that would silently leak a
     *   different session into a "private" download.
     */
    fun enqueueDownload(
        url: String,
        contentDisposition: String?,
        mimeType: String?,
        contentLengthHint: Long,
        userAgent: String?,
        referer: String?,
        profileName: String? = null,
    ): DownloadItem {
        checkInitialized()

        val item: DownloadItem
        synchronized(this) {
            val guessedName = sanitizeFileName(
                URLUtil.guessFileName(url, contentDisposition, mimeType),
            )
            // `createUniqueFileLocked` reserves a unique name in the
            // app-private downloads dir. That's used as the parent for
            // the .part temp file regardless of whether the final
            // destination is MediaStore or app-private. (For new
            // MediaStore-backed downloads, the legacy file path itself
            // never gets written to — only the temp.)
            val reservation = createUniqueFileLocked(guessedName)
            val tempFile = File(reservation.parentFile, "${reservation.name}.part")

            // Public Downloads/ via MediaStore when possible; that's
            // what makes downloads survive uninstall (B17). On any
            // failure to create the MediaStore row, fall back to the
            // pre-existing app-private path so the download still
            // works — degraded, not broken.
            val mediaStoreUri = DownloadStorage.createPendingMediaStoreEntry(
                appContext, reservation.name, mimeType,
            )
            val effectiveFilePath = mediaStoreUri ?: reservation.absolutePath

            val host = Uri.parse(url).host.orEmpty()
            val now = System.currentTimeMillis()
            item = DownloadItem(
                id = UUID.randomUUID().toString(),
                url = url,
                fileName = reservation.name,
                mimeType = mimeType,
                host = host,
                filePath = effectiveFilePath,
                tempFilePath = tempFile.absolutePath,
                userAgent = userAgent,
                referer = referer,
                contentLengthHint = contentLengthHint,
                status = DownloadStatus.QUEUED,
                downloadedBytes = 0L,
                totalBytes = contentLengthHint,
                errorMessage = null,
                userPaused = false,
                createdAt = now,
                updatedAt = now,
                profileName = profileName?.takeIf { it.isNotBlank() },
            )
            items[item.id] = item
            dbUpsert(item)
        }
        notifyListenersAsync()
        ensureServiceRunning()
        startOrResume(item.id)
        return item
    }

    /**
     * Re-arm any download that was in QUEUED or DOWNLOADING state at
     * persist time. Note that PAUSED items are intentionally not picked
     * up here (B11): the task only writes PAUSED in response to an
     * explicit `pause()` call, so a PAUSED item on disk means the user
     * deliberately paused it. Items that were actively downloading when
     * the process died were normalised to QUEUED by [loadDownloads], so
     * they auto-resume via this method.
     */
    fun resumePendingDownloads() {
        checkInitialized()
        val resumableIds = synchronized(this) {
            items.values
                .filter { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING }
                .map(DownloadItem::id)
        }
        if (resumableIds.isEmpty()) {
            return
        }
        resumableIds.forEach { startOrResume(it) }
    }

    fun startOrResume(downloadId: String) {
        checkInitialized()
        // B6: only persist + broadcast if the state actually changes. retry()
        // already writes QUEUED + clears errorMessage; without this guard,
        // startOrResume would re-write the same state, fire a second
        // persist, broadcast, and rebind the list — visible as a flicker.
        // B13 note: tasks is a ConcurrentHashMap, but the compound
        // containsKey-then-put sequence below depends on the surrounding
        // synchronized(this) for atomicity. Don't lift those two lines
        // out without re-establishing the invariant.
        var shouldNotify = false
        var task: DownloadTask? = null
        synchronized(this) {
            val item = items[downloadId] ?: return
            if (tasks.containsKey(downloadId)) {
                return
            }
            if (item.status == DownloadStatus.COMPLETED || item.status == DownloadStatus.CANCELLED) {
                return
            }
            val newTask = DownloadTask(downloadId)
            tasks[downloadId] = newTask
            task = newTask
            if (item.status != DownloadStatus.QUEUED ||
                item.errorMessage != null ||
                item.userPaused
            ) {
                val updated = item.copy(
                    status = DownloadStatus.QUEUED,
                    errorMessage = null,
                    userPaused = false,
                    updatedAt = System.currentTimeMillis(),
                )
                items[downloadId] = updated
                dbUpsert(updated)
                shouldNotify = true
            }
        }
        if (shouldNotify) notifyListenersAsync()
        task?.let(executor::execute)
    }

    fun pause(downloadId: String) {
        // Ask the live task to stop. If the task IS running, it'll write
        // status=PAUSED itself from inside the read loop (see DownloadTask
        // StopReason.PAUSE branch) — and we MUST NOT also write PAUSED
        // here, or we'd race with the task's own write of
        // downloadedBytes / userPaused.
        val hadTask = tasks[downloadId] != null
        tasks[downloadId]?.pause()
        synchronized(this) {
            val item = items[downloadId] ?: return
            // If no task is running (service restarted, or task already
            // completed its loop), the in-loop pause write never happens.
            // We owe the DB write ourselves — otherwise the pause button
            // is a silent no-op for any non-terminal item with no live
            // task, leaving the UI showing QUEUED/DOWNLOADING forever.
            if (!hadTask &&
                item.status != DownloadStatus.COMPLETED &&
                item.status != DownloadStatus.CANCELLED &&
                item.status != DownloadStatus.FAILED &&
                item.status != DownloadStatus.PAUSED
            ) {
                val updated = item.copy(
                    status = DownloadStatus.PAUSED,
                    userPaused = true,
                    updatedAt = System.currentTimeMillis(),
                )
                items[downloadId] = updated
                dbUpsert(updated)
            }
        }
        notifyListenersAsync()
    }

    fun retry(downloadId: String) {
        synchronized(this) {
            val item = items[downloadId] ?: return
            val updated = item.copy(
                status = DownloadStatus.QUEUED,
                errorMessage = null,
                userPaused = false,
                updatedAt = System.currentTimeMillis(),
            )
            items[downloadId] = updated
            dbUpsert(updated)
        }
        notifyListenersAsync()
        ensureServiceRunning()
        startOrResume(downloadId)
    }

    fun remove(downloadId: String) {
        tasks[downloadId]?.cancel()
        tasks.remove(downloadId)
        synchronized(this) {
            val item = items.remove(downloadId) ?: return
            File(item.tempFilePath).delete()
            // The status check stays as-was: only delete the final file
            // for downloads that actually have one. For MediaStore-backed
            // items that's a ContentResolver.delete; for legacy items
            // it's a File.delete. DownloadStorage handles the
            // dispatch.
            //
            // Note: we ALSO clean up MediaStore rows for non-completed
            // statuses (FAILED/PAUSED/QUEUED) where MediaStore had
            // pre-created a pending row at enqueue time — otherwise the
            // pending row would leak and stay invisible-but-present in
            // the user's Downloads/ until the OS sweeps it.
            if (item.status == DownloadStatus.COMPLETED ||
                item.status == DownloadStatus.CANCELLED ||
                DownloadStorage.isContentUri(item.filePath)
            ) {
                DownloadStorage.deleteAtPath(appContext, item.filePath)
            }
            dbDelete(downloadId)
        }
        notifyListenersAsync()
    }

    fun clearCompleted(deleteFiles: Boolean) {
        synchronized(this) {
            val toRemove = items.values.filter { it.status == DownloadStatus.COMPLETED }
            if (toRemove.isEmpty()) {
                return
            }
            toRemove.forEach { item ->
                items.remove(item.id)
                if (deleteFiles) {
                    DownloadStorage.deleteAtPath(appContext, item.filePath)
                }
            }
            // Single statement instead of one DELETE per row — and one
            // less full-blob rewrite than the old persistLocked().
            dbDeleteCompleted()
        }
        notifyListenersAsync()
    }

    fun getSnapshot(): List<DownloadItem> = synchronized(this) { snapshotLocked() }

    fun getById(downloadId: String): DownloadItem? = synchronized(this) { items[downloadId] }

    fun getActiveDownloads(): List<DownloadItem> = synchronized(this) { getActiveDownloadsLocked() }

    fun formatBytes(bytes: Long): String {
        return if (bytes <= 0L) "0 B" else Formatter.formatShortFileSize(appContext, bytes)
    }

    private fun getActiveDownloadsLocked(): List<DownloadItem> {
        return items.values.filter {
            it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING
        }
    }

    private fun snapshotLocked(): List<DownloadItem> {
        return items.values.sortedByDescending(DownloadItem::createdAt)
    }

    private fun updateItem(downloadId: String, transform: (DownloadItem) -> DownloadItem) {
        synchronized(this) {
            val current = items[downloadId] ?: return
            val updated = transform(current)
            items[downloadId] = updated
            // This is the hot path: DownloadTask calls it ~2 Hz per
            // active download. It used to rewrite the entire downloads
            // JSON blob each time; now it's a single-row UPSERT on the
            // shared background writer.
            dbUpsert(updated)
        }
        notifyListenersAsync()
    }

    private fun completeTask(downloadId: String) {
        tasks.remove(downloadId)
    }

    /**
     * Load the `downloads` table into the in-memory [items] map, oldest
     * first (so the map's insertion order matches creation order;
     * `snapshotLocked` re-sorts for display anyway).
     *
     * Any row left in DOWNLOADING or QUEUED state from a previous run is
     * normalised to QUEUED — the process clearly died mid-download, so
     * the task needs re-arming. The normalisation is written back so the
     * DB stays honest (it's a handful of rows at most, usually zero).
     */
    private fun loadFromDb() {
        items.clear()
        val normalised = ArrayList<DownloadItem>()
        db.readableDatabase.query(
            BrowserDatabase.TABLE_DOWNLOADS,
            null, null, null, null, null,
            "${BrowserDatabase.COL_DOWNLOAD_CREATED_AT} ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val item = cursorToDownloadItem(cursor)
                val effective = when (item.status) {
                    DownloadStatus.DOWNLOADING,
                    DownloadStatus.QUEUED,
                    -> item.copy(
                        status = DownloadStatus.QUEUED,
                        errorMessage = null,
                        userPaused = false,
                        updatedAt = System.currentTimeMillis(),
                    ).also { normalised.add(it) }

                    else -> item
                }
                items[effective.id] = effective
            }
        }
        // Persist the interrupted-download normalisation. Idempotent —
        // if the process dies again before these land, the next load
        // normalises afresh.
        for (item in normalised) dbUpsert(item)
    }

    /**
     * One-time import of the legacy `SharedPreferences` JSON blob into
     * the SQLite `downloads` table. Gated by a `migrated` flag; the
     * import is a single transaction, so a failure rolls back and is
     * retried next launch. On success the old JSON key is dropped.
     *
     * Runs synchronously inside [initialize] (same thread the old
     * `loadDownloads` ran on); touches the DB at most once, ever.
     * `DownloadItem.fromJson` already drops the long-gone `cookie`
     * field (audit V4), and the new table has no cookie column, so the
     * cookie scrub happens for free here.
     */
    private fun migrateFromPrefsIfNeeded() {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_MIGRATED, false)) return

        val raw = prefs.getString(KEY_DOWNLOADS, null)
        if (raw != null) {
            try {
                val jsonArray = JSONArray(raw)
                val writable = db.writableDatabase
                writable.beginTransaction()
                try {
                    for (index in 0 until jsonArray.length()) {
                        val item = try {
                            DownloadItem.fromJson(jsonArray.getJSONObject(index))
                        } catch (error: Exception) {
                            Log.w(TAG, "Skipping malformed download entry during migration", error)
                            continue
                        }
                        writable.insertWithOnConflict(
                            BrowserDatabase.TABLE_DOWNLOADS, null, item.toContentValues(),
                            SQLiteDatabase.CONFLICT_REPLACE,
                        )
                    }
                    writable.setTransactionSuccessful()
                } finally {
                    writable.endTransaction()
                }
            } catch (error: Exception) {
                // Unreadable legacy blob — start fresh rather than retry
                // the failing parse on every launch. The user's
                // downloaded files on disk are unaffected.
                Log.w(TAG, "Download index migration failed; starting with an empty table", error)
            }
        }
        prefs.edit {
            putBoolean(KEY_MIGRATED, true)
            remove(KEY_DOWNLOADS)
        }
    }

    /** Insert-or-replace one row on the shared background writer. */
    private fun dbUpsert(item: DownloadItem) {
        db.runWrite {
            it.insertWithOnConflict(
                BrowserDatabase.TABLE_DOWNLOADS, null, item.toContentValues(),
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
    }

    private fun dbDelete(downloadId: String) {
        db.runWrite {
            it.delete(
                BrowserDatabase.TABLE_DOWNLOADS,
                "${BrowserDatabase.COL_DOWNLOAD_ID} = ?",
                arrayOf(downloadId),
            )
        }
    }

    private fun dbDeleteCompleted() {
        db.runWrite {
            it.delete(
                BrowserDatabase.TABLE_DOWNLOADS,
                "${BrowserDatabase.COL_DOWNLOAD_STATUS} = ?",
                arrayOf(DownloadStatus.COMPLETED.name),
            )
        }
    }

    /**
     * Resolve the right `CookieManager` for a download and return its
     * Cookie header for [url], or null if no cookie is available.
     *
     * Three cases:
     *
     *  - [profileName] is null/blank → default profile (most downloads,
     *    plus every pre-v3 row migrated from the old schema).
     *  - [profileName] is set + the device supports `MULTI_PROFILE` +
     *    the profile still exists → that profile's CookieManager.
     *  - [profileName] is set but the profile is gone (private session
     *    was wiped) → return **null**, deliberately *not* falling back
     *    to the default profile. Falling back would silently leak the
     *    default session into a download the user initiated under
     *    private mode.
     *
     * Called from the DownloadTask thread; all three CookieManager /
     * Profile / ProfileStore APIs are documented thread-safe.
     */
    private fun lookupCookieFor(profileName: String?, url: String): String? {
        return try {
            val manager = if (profileName.isNullOrBlank()) {
                CookieManager.getInstance()
            } else {
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                    return null
                }
                ProfileStore.getInstance().getProfile(profileName)?.cookieManager
                    ?: return null
            }
            manager.getCookie(url)
        } catch (e: Exception) {
            null
        }
    }

    private fun DownloadItem.toContentValues(): ContentValues = ContentValues().apply {
        put(BrowserDatabase.COL_DOWNLOAD_ID, id)
        put(BrowserDatabase.COL_DOWNLOAD_URL, url)
        put(BrowserDatabase.COL_DOWNLOAD_FILE_NAME, fileName)
        put(BrowserDatabase.COL_DOWNLOAD_MIME_TYPE, mimeType)
        put(BrowserDatabase.COL_DOWNLOAD_HOST, host)
        put(BrowserDatabase.COL_DOWNLOAD_FILE_PATH, filePath)
        put(BrowserDatabase.COL_DOWNLOAD_TEMP_FILE_PATH, tempFilePath)
        put(BrowserDatabase.COL_DOWNLOAD_USER_AGENT, userAgent)
        put(BrowserDatabase.COL_DOWNLOAD_REFERER, referer)
        put(BrowserDatabase.COL_DOWNLOAD_CONTENT_LENGTH_HINT, contentLengthHint)
        put(BrowserDatabase.COL_DOWNLOAD_STATUS, status.name)
        put(BrowserDatabase.COL_DOWNLOAD_DOWNLOADED_BYTES, downloadedBytes)
        put(BrowserDatabase.COL_DOWNLOAD_TOTAL_BYTES, totalBytes)
        put(BrowserDatabase.COL_DOWNLOAD_ERROR_MESSAGE, errorMessage)
        put(BrowserDatabase.COL_DOWNLOAD_USER_PAUSED, if (userPaused) 1 else 0)
        put(BrowserDatabase.COL_DOWNLOAD_CREATED_AT, createdAt)
        put(BrowserDatabase.COL_DOWNLOAD_UPDATED_AT, updatedAt)
        // bytesPerSecond is a transient speed estimate — not persisted,
        // exactly as the old toJson() didn't persist it.
        put(BrowserDatabase.COL_DOWNLOAD_PROFILE_NAME, profileName)
    }

    private fun cursorToDownloadItem(c: Cursor): DownloadItem {
        return DownloadItem(
            id = c.getString(c.getColumnIndexOrThrow(BrowserDatabase.COL_DOWNLOAD_ID)),
            url = c.getString(c.getColumnIndexOrThrow(BrowserDatabase.COL_DOWNLOAD_URL)),
            fileName = c.getString(c.getColumnIndexOrThrow(BrowserDatabase.COL_DOWNLOAD_FILE_NAME)),
            mimeType = c.getString(c.getColumnIndexOrThrow(BrowserDatabase.COL_DOWNLOAD_MIME_TYPE)),
            host = c.getString(c.getColumnIndexOrThrow(BrowserDatabase.COL_DOWNLOAD_HOST)),
            filePath = c.getString(c.getColumnIndexOrThrow(BrowserDatabase.COL_DOWNLOAD_FILE_PATH)),
            tempFilePath = c.getString(c.getColumnIndexOrThrow(BrowserDatabase.COL_DOWNLOAD_TEMP_FILE_PATH)),
            userAgent = c.getString(c.getColumnIndexOrThrow(BrowserDatabase.COL_DOWNLOAD_USER_AGENT)),
            referer = c.getString(c.getColumnIndexOrThrow(BrowserDatabase.COL_DOWNLOAD_REFERER)),
            contentLengthHint = c.getLong(c.getColumnIndexOrThrow(BrowserDatabase.COL_DOWNLOAD_CONTENT_LENGTH_HINT)),
            status = parseStatus(c.getString(c.getColumnIndexOrThrow(BrowserDatabase.COL_DOWNLOAD_STATUS))),
            downloadedBytes = c.getLong(c.getColumnIndexOrThrow(BrowserDatabase.COL_DOWNLOAD_DOWNLOADED_BYTES)),
            totalBytes = c.getLong(c.getColumnIndexOrThrow(BrowserDatabase.COL_DOWNLOAD_TOTAL_BYTES)),
            errorMessage = c.getString(c.getColumnIndexOrThrow(BrowserDatabase.COL_DOWNLOAD_ERROR_MESSAGE)),
            userPaused = c.getInt(c.getColumnIndexOrThrow(BrowserDatabase.COL_DOWNLOAD_USER_PAUSED)) != 0,
            createdAt = c.getLong(c.getColumnIndexOrThrow(BrowserDatabase.COL_DOWNLOAD_CREATED_AT)),
            updatedAt = c.getLong(c.getColumnIndexOrThrow(BrowserDatabase.COL_DOWNLOAD_UPDATED_AT)),
            bytesPerSecond = 0L,
            // Pre-v3 rows (legacy, before the column existed) come
            // back as NULL — interpreted by the cookie lookup in
            // DownloadTask as "use the default profile", which is what
            // those downloads originally did.
            profileName = c.getString(c.getColumnIndexOrThrow(BrowserDatabase.COL_DOWNLOAD_PROFILE_NAME)),
        )
    }

    /** Map a stored status string back to the enum, tolerating an
     *  unrecognised value (e.g. a downgrade that wrote a status this
     *  build doesn't know) by treating it as QUEUED. */
    private fun parseStatus(raw: String?): DownloadStatus =
        runCatching { DownloadStatus.valueOf(raw.orEmpty()) }
            .getOrDefault(DownloadStatus.QUEUED)

    private fun ensureServiceRunning() {
        try {
            DownloadService.start(appContext)
        } catch (error: Exception) {
            Log.w(TAG, "Unable to start download foreground service", error)
        }
    }

    /**
     * Notifies listeners on the main thread, without holding the repository
     * lock. This is the critical change that keeps the download thread from
     * blocking on UI/notification updates.
     */
    private fun notifyListenersAsync() {
        val snap = synchronized(this) { snapshotLocked() }
        mainHandler.post {
            for (listener in listeners) {
                try {
                    listener.onDownloadsChanged(snap)
                } catch (error: Exception) {
                    Log.w(TAG, "Listener threw", error)
                }
            }
        }
    }

    /**
     * Strip path separators, parent-dir markers, and reserved characters
     * from a filename returned by URLUtil.guessFileName. Delegates to
     * [DownloadFileNames] so unit tests can exercise the sanitiser
     * without forcing [DownloadRepository]'s static init to run (the
     * main-thread [android.os.Handler] field NPEs in pure-JVM tests).
     */
    internal fun sanitizeFileName(fileName: String): String =
        DownloadFileNames.sanitize(fileName)

    private fun createUniqueFileLocked(fileName: String): File {
        // `getExternalFilesDir` returns null when no external storage is
        // available (rare — emulator without SD, or storage being
        // unmounted for media transfer). We fall back to internal
        // storage so the download still has somewhere to go, but the
        // user can't browse to it via the Files app and it counts
        // against the app's data quota. Log once so the path swap is
        // visible in `adb logcat` for diagnosing "where did my file
        // go?" reports.
        val externalDir = appContext.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
        val directory = externalDir ?: run {
            Log.w(
                TAG,
                "External storage unavailable; downloads will write to internal app storage." +
                    " Files won't appear in the system Files app and will be removed on uninstall.",
            )
            File(appContext.filesDir, "downloads")
        }
        directory.mkdirs()

        val baseName = fileName.substringBeforeLast('.', fileName)
        val extension = fileName.substringAfterLast('.', "")

        // B5: cap the linear search. The previous unbounded `while(true)`
        // would block this method's caller (and hold the repository lock)
        // for pathological collision counts. After [MAX_NAME_COLLISION_ATTEMPTS]
        // failed candidates we fall back to a millisecond-suffixed name
        // that won't collide in any realistic scenario.
        for (attempt in 0 until MAX_NAME_COLLISION_ATTEMPTS) {
            val candidateName = when {
                attempt == 0 -> fileName
                extension.isNotEmpty() -> "$baseName ($attempt).$extension"
                else -> "$baseName ($attempt)"
            }
            val candidate = File(directory, candidateName)
            val reserved = items.values.any {
                it.filePath == candidate.absolutePath ||
                    it.tempFilePath == "${candidate.absolutePath}.part"
            }
            if (!candidate.exists() && !reserved) {
                return candidate
            }
        }

        val timestamp = System.currentTimeMillis()
        val fallback = if (extension.isNotEmpty()) {
            "$baseName-$timestamp.$extension"
        } else {
            "$baseName-$timestamp"
        }
        return File(directory, fallback)
    }

    private fun checkInitialized() {
        check(initialized) { "DownloadRepository.initialize(context) must be called first." }
    }

    private enum class StopReason {
        PAUSE,
        CANCEL,
    }

    private class DownloadTask(
        private val downloadId: String,
    ) : Runnable {
        @Volatile
        private var stopReason: StopReason? = null
        @Volatile
        private var activeCall: okhttp3.Call? = null

        fun pause() {
            stopReason = StopReason.PAUSE
            activeCall?.cancel()
        }

        fun cancel() {
            stopReason = StopReason.CANCEL
            activeCall?.cancel()
        }

        override fun run() {
            // B7: if pause() or cancel() landed between executor.execute(...)
            // and the task's first instruction, bail out *before* writing
            // status = DOWNLOADING. Without this guard the task would
            // overwrite a freshly-written PAUSED state, then loop back
            // around and immediately write PAUSED again — visible in the
            // UI as a one-frame flicker through "downloading".
            //
            // The read is into a local so Kotlin's smart-cast doesn't
            // propagate the "stopReason is null" conclusion to the
            // `when (stopReason)` further down — that field is @Volatile
            // and other threads can flip it at any point.
            val initialStop: StopReason? = stopReason
            if (initialStop != null) {
                DownloadRepository.completeTask(downloadId)
                return
            }
            val item = DownloadRepository.getById(downloadId) ?: return
            var response: Response? = null

            try {
                val tempFile = File(item.tempFilePath).apply {
                    parentFile?.mkdirs()
                }
                // item.filePath used to be unconditionally a File path
                // (so we built a `finalFile = File(item.filePath)` here
                // and renamed temp→finalFile on completion). With
                // MediaStore-backed downloads it can now be either a
                // content:// URI or a legacy file path, so the
                // finalisation dispatches via DownloadStorage.finalizeToTarget
                // further down — no local File needed up here.
                var downloaded = if (tempFile.exists()) tempFile.length() else 0L
                var totalBytes = max(item.totalBytes, item.contentLengthHint)

                DownloadRepository.updateItem(downloadId) {
                    it.copy(
                        status = DownloadStatus.DOWNLOADING,
                        downloadedBytes = downloaded,
                        totalBytes = totalBytes,
                        errorMessage = null,
                        updatedAt = System.currentTimeMillis(),
                    )
                }

                val requestBuilder = Request.Builder()
                    .url(item.url)
                    .header("Accept", "*/*")
                    // Identity encoding so resumed downloads stay byte-aligned
                    // with the on-disk partial file.
                    .header("Accept-Encoding", "identity")
                    .header("Connection", "keep-alive")
                item.userAgent?.takeIf { it.isNotBlank() }?.let { requestBuilder.header("User-Agent", it) }
                // V4 + incognito-aware downloads: fetch a *live* cookie
                // from the right CookieManager (default vs the per-tab
                // private profile) instead of replaying a snapshot
                // persisted at enqueue time. If the user has logged out
                // since the download was queued, or — for private-tab
                // downloads — the private profile was wiped (last
                // private tab closed), the lookup returns null and the
                // request goes out with no Cookie header. The server
                // then enforces its own auth, which is exactly the
                // intended behaviour.
                DownloadRepository.lookupCookieFor(item.profileName, item.url)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { requestBuilder.header("Cookie", it) }
                item.referer?.takeIf { it.isNotBlank() }?.let { requestBuilder.header("Referer", it) }
                if (downloaded > 0L) {
                    requestBuilder.header("Range", "bytes=$downloaded-")
                }

                val call = DownloadRepository.httpClient.newCall(requestBuilder.build())
                activeCall = call
                response = call.execute()

                val code = response.code
                if (code == 416 && downloaded > 0L) {
                    // Partial state out of sync. Drop temp; user retry will
                    // start fresh.
                    response.close()
                    tempFile.delete()
                    error("Range not satisfiable. Tap retry to download from the start.")
                }
                if (!response.isSuccessful) {
                    error("Server returned $code")
                }

                val body = response.body ?: error("Empty response.")
                val contentLength = body.contentLength()
                val supportsResume = code == 206

                if (downloaded > 0L && !supportsResume) {
                    tempFile.delete()
                    downloaded = 0L
                }

                totalBytes = when {
                    supportsResume && contentLength > 0 -> downloaded + contentLength
                    contentLength > 0 -> contentLength
                    else -> max(item.contentLengthHint, downloaded)
                }

                // B18: pre-flight size check. If the server has told us up
                // front that the response is larger than our hard cap,
                // refuse before opening the file so we don't tie up the
                // network connection or the temp file. The in-loop check
                // further down catches servers that don't send a
                // Content-Length but stream forever.
                if (totalBytes > MAX_DOWNLOAD_BYTES) {
                    error(
                        "Download exceeds maximum size of " +
                            "${formatBytes(MAX_DOWNLOAD_BYTES)}.",
                    )
                }

                // Persist + notify cadence is gated by both byte threshold
                // and wall-clock so the download thread is never blocked on
                // listener callbacks for more than a fraction of a second.
                val persistByteInterval = 1L * 1024L * 1024L // 1 MiB
                val persistTimeIntervalMs = 500L
                var lastPersistedBytes = downloaded
                var lastPersistedAt = System.currentTimeMillis()
                var lastSpeedSampleAt = lastPersistedAt
                var lastSpeedSampleBytes = downloaded
                var lastSpeed = 0L

                RandomAccessFile(tempFile, "rw").use { output ->
                    if (downloaded == 0L) {
                        output.setLength(0L)
                    } else {
                        output.seek(downloaded)
                    }

                    BufferedInputStream(body.byteStream(), 256 * 1024).use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            when (stopReason) {
                                StopReason.PAUSE -> {
                                    DownloadRepository.updateItem(downloadId) {
                                        it.copy(
                                            status = DownloadStatus.PAUSED,
                                            downloadedBytes = downloaded,
                                            totalBytes = totalBytes,
                                            userPaused = true,
                                            bytesPerSecond = 0L,
                                            updatedAt = System.currentTimeMillis(),
                                        )
                                    }
                                    return
                                }

                                StopReason.CANCEL -> {
                                    tempFile.delete()
                                    DownloadRepository.updateItem(downloadId) {
                                        it.copy(
                                            status = DownloadStatus.CANCELLED,
                                            downloadedBytes = 0L,
                                            totalBytes = totalBytes,
                                            bytesPerSecond = 0L,
                                            updatedAt = System.currentTimeMillis(),
                                        )
                                    }
                                    return
                                }

                                null -> Unit
                            }

                            val count = try {
                                input.read(buffer)
                            } catch (error: IOException) {
                                if (stopReason != null) {
                                    return
                                }
                                throw error
                            }
                            if (count <= 0) {
                                break
                            }

                            output.write(buffer, 0, count)
                            downloaded += count

                            // B18: in-loop size enforcement. Catches the
                            // case where the server reports no
                            // Content-Length but keeps streaming past our
                            // configured cap. The temp file is left in
                            // place for inspection; remove() will clean
                            // it up.
                            if (downloaded > MAX_DOWNLOAD_BYTES) {
                                error(
                                    "Download exceeds maximum size of " +
                                        "${formatBytes(MAX_DOWNLOAD_BYTES)}.",
                                )
                            }

                            val now = System.currentTimeMillis()
                            val byteDelta = downloaded - lastPersistedBytes
                            val timeDelta = now - lastPersistedAt
                            if (byteDelta >= persistByteInterval || timeDelta >= persistTimeIntervalMs) {
                                val sampleElapsed = (now - lastSpeedSampleAt).coerceAtLeast(1L)
                                val sampleBytes = downloaded - lastSpeedSampleBytes
                                val instantaneous = sampleBytes * 1000L / sampleElapsed
                                lastSpeed = if (lastSpeed == 0L) {
                                    instantaneous
                                } else {
                                    (lastSpeed * 3 + instantaneous) / 4
                                }
                                lastSpeedSampleAt = now
                                lastSpeedSampleBytes = downloaded
                                val speedSnapshot = lastSpeed
                                val bytesSnapshot = downloaded
                                val totalSnapshot = totalBytes
                                lastPersistedBytes = downloaded
                                lastPersistedAt = now
                                DownloadRepository.updateItem(downloadId) {
                                    it.copy(
                                        status = DownloadStatus.DOWNLOADING,
                                        downloadedBytes = bytesSnapshot,
                                        totalBytes = totalSnapshot,
                                        bytesPerSecond = speedSnapshot,
                                        updatedAt = now,
                                    )
                                }
                            }
                        }
                    }
                }

                // Move the temp file to its final destination. For
                // MediaStore-backed downloads (B17, API 29+) this is a
                // copy-into-content-URI + IS_PENDING=0 + delete-temp;
                // for legacy downloads it's the original rename-or-copy
                // dance. The helper picks based on item.filePath's
                // form. A throw here lands in the catch below and
                // marks the download FAILED with the temp file intact
                // for inspection / manual recovery.
                DownloadStorage.finalizeToTarget(
                    DownloadRepository.appContext, tempFile, item.filePath,
                )

                DownloadRepository.updateItem(downloadId) {
                    it.copy(
                        status = DownloadStatus.COMPLETED,
                        downloadedBytes = downloaded,
                        totalBytes = max(totalBytes, downloaded),
                        errorMessage = null,
                        bytesPerSecond = 0L,
                        updatedAt = System.currentTimeMillis(),
                    )
                }
            } catch (error: Exception) {
                val stop = stopReason
                if (stop == StopReason.PAUSE || stop == StopReason.CANCEL) {
                    return
                }

                // Log the full exception with its cause chain before
                // swallowing it into a user-facing errorMessage. The
                // string we surface to the UI is intentionally terse;
                // the stack trace here is what lets us diagnose
                // "download X failed" reports without asking the user
                // to repro under a debugger.
                Log.w(
                    TAG,
                    "Download $downloadId failed (url=${item.url}, " +
                        "bytes=${runCatching { File(item.tempFilePath).length() }.getOrDefault(-1L)})",
                    error,
                )

                DownloadRepository.updateItem(downloadId) {
                    it.copy(
                        status = DownloadStatus.FAILED,
                        errorMessage = error.message
                            ?: DownloadRepository.appContext.getString(R.string.download_failed_generic),
                        bytesPerSecond = 0L,
                        updatedAt = System.currentTimeMillis(),
                    )
                }
            } finally {
                activeCall = null
                try {
                    response?.close()
                } catch (_: Exception) { /* ignore */ }
                DownloadRepository.completeTask(downloadId)
            }
        }
    }
}
