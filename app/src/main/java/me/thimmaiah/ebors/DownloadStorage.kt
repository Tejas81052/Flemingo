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
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Glue for writing completed downloads into the public Downloads/ folder
 * via MediaStore (API 29+), with a legacy app-private fallback for the
 * rare case where the MediaStore insert fails (disk full, weird OEM).
 *
 * # Why MediaStore
 *
 * The previous design kept finished downloads in
 * `getExternalFilesDir(DIRECTORY_DOWNLOADS)` — app-private external
 * storage. Those files don't show up in the system Files app on most
 * devices and **get erased when the app is uninstalled**, which is a
 * surprise. MediaStore.Downloads (API 29+) places the file in the
 * shared public Downloads/ tree, where it survives uninstall and shows
 * up alongside other browser/file-manager downloads.
 *
 * # File-path dual encoding
 *
 * [DownloadItem.filePath] is a String. Existing downloads (on a device
 * that's upgrading from before this pass) store an absolute file path;
 * new downloads store a `content://` URI string. Every helper here
 * checks [isContentUri] first and dispatches accordingly, so the same
 * [DownloadItem] type covers both eras without a schema change.
 *
 * # Resume semantics
 *
 * Resume still uses an app-private `.part` file written with
 * [java.io.RandomAccessFile] — `ContentResolver.openOutputStream`
 * doesn't expose `seek`. On completion we copy temp → MediaStore +
 * clear `IS_PENDING=0`, then delete temp. The extra full-file copy
 * costs a few seconds on multi-GiB downloads but keeps the existing
 * `DownloadTask` codepath intact (one copy of resume logic, not two).
 */
internal object DownloadStorage {
    private const val TAG = "DownloadStorage"

    /** True if [path] is a `content://` URI string rather than a file path. */
    fun isContentUri(path: String): Boolean =
        path.startsWith("content://", ignoreCase = true)

    /**
     * Insert a pending row into MediaStore.Downloads so the eventual
     * file is hidden from other apps until [finalizeMediaStoreEntry]
     * clears the flag. Returns the row's content URI as a string for
     * direct storage in [DownloadItem.filePath], or null if the insert
     * fails (the caller falls back to the legacy app-private path).
     */
    fun createPendingMediaStoreEntry(
        context: Context,
        fileName: String,
        mimeType: String?,
    ): String? {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                // MediaStore wants something — server-given MIME or our
                // generic-binary default. Either is fine for non-media
                // entries under MediaStore.Downloads.
                put(
                    MediaStore.Downloads.MIME_TYPE,
                    mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream",
                )
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            context.contentResolver
                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?.toString()
        } catch (e: Exception) {
            // Disk full, restricted profile, or some OEM quirk. Caller
            // falls back to app-private storage, which is a degraded
            // but functional outcome.
            Log.w(TAG, "MediaStore insert failed; falling back to legacy app-private storage", e)
            null
        }
    }

    /**
     * Flip `IS_PENDING` to 0 so other apps (Files, share targets) can
     * now see the completed download. Failure is logged but swallowed —
     * the file is on disk either way; worst case the user has to wait
     * for MediaStore's periodic re-index to surface it.
     */
    fun finalizeMediaStoreEntry(context: Context, uri: Uri) {
        try {
            val update = ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }
            context.contentResolver.update(uri, update, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to finalize MediaStore entry $uri", e)
        }
    }

    /**
     * Copy the streamed-to-disk temp file into its final destination.
     * For MediaStore-backed downloads that's a content-URI write +
     * `IS_PENDING=0`; for legacy app-private downloads it's the
     * original rename-or-copy fallback.
     *
     * The temp file is deleted on success. On failure (e.g. the
     * MediaStore write throws partway through) the temp is **kept** so
     * the user retains some recovery ground; the caller marks the
     * download FAILED.
     */
    @Throws(IOException::class)
    fun finalizeToTarget(context: Context, tempFile: File, filePath: String) {
        if (isContentUri(filePath)) {
            val uri = Uri.parse(filePath)
            // "wt" = write + truncate. Defensive against the MediaStore
            // row having a stray byte from a previous attempt.
            val stream = context.contentResolver.openOutputStream(uri, "wt")
                ?: throw IOException("MediaStore openOutputStream returned null for $uri")
            try {
                stream.use { out ->
                    tempFile.inputStream().use { input -> input.copyTo(out) }
                }
                finalizeMediaStoreEntry(context, uri)
                tempFile.delete()
            } catch (e: Exception) {
                // The MediaStore row stays IS_PENDING=1 forever if we
                // leave now, but DownloadItem.filePath points at this
                // exact URI. Retry reopens it with "wt", truncating any
                // partial copy and preserving the stored target. If the
                // user removes the failed download, deleteAtPath cleans
                // up the pending row.
                throw e
            }
        } else {
            val finalFile = File(filePath)
            if (finalFile.exists()) finalFile.delete()
            if (!tempFile.renameTo(finalFile)) {
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
            }
        }
    }

    /**
     * Delete the final-location target whose path may be either a
     * content URI or a legacy file path. Errors are swallowed: from the
     * user's perspective the row is already gone from the downloads
     * list, and a left-behind file is recoverable manually but a thrown
     * exception is not.
     */
    fun deleteAtPath(context: Context, filePath: String) {
        if (filePath.isBlank()) return
        if (isContentUri(filePath)) {
            try {
                context.contentResolver.delete(Uri.parse(filePath), null, null)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete MediaStore item $filePath", e)
            }
        } else {
            try {
                File(filePath).delete()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete file $filePath", e)
            }
        }
    }

    /**
     * Does the final-location target still exist? Used by the
     * Downloads UI to detect "file was deleted by the user via Files
     * app" before launching a share / open intent that would fail with
     * a less helpful error.
     */
    fun targetExists(context: Context, filePath: String): Boolean {
        if (filePath.isBlank()) return false
        if (!isContentUri(filePath)) return File(filePath).exists()
        return try {
            context.contentResolver.query(
                Uri.parse(filePath),
                arrayOf(MediaStore.Downloads._ID),
                null, null, null,
            )?.use { it.count > 0 } ?: false
        } catch (e: Exception) {
            false
        }
    }
}
