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

import org.json.JSONObject

/**
 * One download row. Persisted as JSON inside SharedPreferences.
 *
 * Notable schema change: there is **no** cookie field. Previously the
 * caller's CookieManager snapshot was captured at enqueue time and stored
 * here so it could be replayed on resume, but that meant:
 *
 *  - Cookies (often session tokens) survived "Clear browsing data" because
 *    SharedPreferences-backed JSON isn't part of the WebView cookie store.
 *  - Stale cookies were re-sent on resume long after the user had logged out
 *    elsewhere, occasionally letting a downloaded artifact bypass a fresh
 *    auth check the server thought it had imposed.
 *  - The cookie sat on disk indefinitely as long as the download row stayed
 *    on the list.
 *
 * Cookies are now fetched fresh from [android.webkit.CookieManager] inside
 * `DownloadTask.run` for every attempt — see V4 in the audit. The download
 * proceeds with whatever cookies the user has *right now*; if they've
 * logged out the server gets to enforce that.
 */
data class DownloadItem(
    val id: String,
    val url: String,
    val fileName: String,
    val mimeType: String?,
    val host: String,
    val filePath: String,
    val tempFilePath: String,
    val userAgent: String?,
    val referer: String?,
    val contentLengthHint: Long,
    val status: DownloadStatus,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val errorMessage: String?,
    val userPaused: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val bytesPerSecond: Long = 0L,
    /**
     * AndroidX webkit [androidx.webkit.Profile] name the download was
     * initiated under. `null` means the default profile (used for every
     * download created before this column existed, and for downloads
     * from non-private tabs). A non-null value — currently always
     * `"incognito"` — routes the cookie lookup in `DownloadTask.run`
     * through that profile's `CookieManager` rather than the default
     * one, so a private-tab download uses the private session's auth.
     *
     * Not surfaced in the JSON migration path either; legacy entries
     * stay null after import.
     */
    val profileName: String? = null,
) {
    companion object {
        /**
         * Parse a legacy SharedPreferences JSON entry.
         *
         * Only used by [DownloadRepository]'s one-time SQLite migration
         * — the durable store is now the `downloads` SQLite table, not a
         * JSON blob, so there is no `toJson` counterpart anymore. A
         * `cookie` field from an even-older schema is intentionally
         * ignored (audit V4: cookies are fetched live at request time,
         * never persisted).
         */
        fun fromJson(json: JSONObject): DownloadItem {
            return DownloadItem(
                id = json.getString("id"),
                url = json.getString("url"),
                fileName = json.getString("fileName"),
                mimeType = json.optString("mimeType").takeIf { it.isNotBlank() && it != "null" },
                host = json.optString("host"),
                filePath = json.getString("filePath"),
                tempFilePath = json.getString("tempFilePath"),
                userAgent = json.optString("userAgent").takeIf { it.isNotBlank() && it != "null" },
                referer = json.optString("referer").takeIf { it.isNotBlank() && it != "null" },
                contentLengthHint = json.optLong("contentLengthHint", -1L),
                status = DownloadStatus.valueOf(json.optString("status", DownloadStatus.QUEUED.name)),
                downloadedBytes = json.optLong("downloadedBytes", 0L),
                totalBytes = json.optLong("totalBytes", -1L),
                errorMessage = json.optString("errorMessage").takeIf { it.isNotBlank() && it != "null" },
                userPaused = json.optBoolean("userPaused", false),
                createdAt = json.optLong("createdAt"),
                updatedAt = json.optLong("updatedAt"),
                bytesPerSecond = 0L,
            )
        }
    }
}

enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED,
}
