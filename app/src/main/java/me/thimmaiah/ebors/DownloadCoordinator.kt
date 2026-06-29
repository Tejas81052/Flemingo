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

import android.app.Activity
import android.content.Intent
import android.view.ContextMenu
import android.view.View
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

/**
 * Owns the download paths: the WebView download-listener prompt, the "Save
 * image" context action and its plumbing, and opening the downloads screen.
 *
 * Extracted from MainActivity. Collaborators are passed in — the root view and
 * the Snackbar anchor card, the private-profile name, a user-agent provider,
 * and a toast callback — so the coordinator carries no back-reference to the
 * activity's private state.
 */
class DownloadCoordinator(
    private val activity: Activity,
    private val rootView: View,
    private val navigationCard: View,
    private val privateProfileName: String,
    private val userAgentProvider: () -> String,
    private val showToast: (String) -> Unit,
) {

    /** WebView download-listener entry point: confirm, then enqueue. */
    fun handleDownloadRequest(
        tab: Tab,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long,
    ) {
        if (url.startsWith("blob:", ignoreCase = true)) {
            showToast(activity.getString(R.string.blob_download_not_supported))
            return
        }

        val blockingMatch = BrowserBlocker.findMatch(url)
        if (blockingMatch != null) {
            showToast(activity.getString(R.string.download_blocked))
            return
        }

        val fileName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
        val sizeLabel = if (contentLength > 0L) {
            activity.getString(
                R.string.download_prompt_message_with_size,
                fileName,
                DownloadRepository.formatBytes(contentLength),
            )
        } else {
            activity.getString(R.string.download_prompt_message, fileName)
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.download_prompt_title)
            .setMessage(sizeLabel)
            .setPositiveButton(R.string.allow_once) { _, _ ->
                val item = DownloadRepository.enqueueDownload(
                    url = url,
                    contentDisposition = contentDisposition,
                    mimeType = mimeType,
                    contentLengthHint = contentLength,
                    userAgent = userAgent,
                    referer = tab.webView.url,
                    profileName = if (tab.isPrivate) privateProfileName else null,
                )
                DownloadService.start(activity)
                Snackbar.make(rootView, activity.getString(R.string.download_started, item.fileName), Snackbar.LENGTH_LONG)
                    .setAnchorView(navigationCard)
                    .setAction(R.string.view_downloads) {
                        openDownloadsScreen()
                    }
                    .show()
            }
            .setNegativeButton(R.string.deny) { _, _ ->
                showToast(activity.getString(R.string.download_denied))
            }
            .show()
    }

    fun openDownloadsScreen() {
        activity.startActivity(Intent(activity, DownloadsActivity::class.java))
    }

    /** Append the "Save image" item to a context menu for [sourceTab]. */
    fun addSaveImageItem(menu: ContextMenu, sourceTab: Tab) {
        menu.add(activity.getString(R.string.image_action_save_image))
            .setOnMenuItemClickListener {
                requestLastTouchedImageUrl(sourceTab) { imageUrl ->
                    saveImageFromUrl(imageUrl, sourceTab)
                }
                true
            }
    }

    private fun requestLastTouchedImageUrl(tab: Tab, onUrl: (String) -> Unit) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper()) { msg ->
            val url = msg.data?.getString("url")
            if (!url.isNullOrBlank()) onUrl(url)
            true
        }
        tab.webView.requestImageRef(handler.obtainMessage())
    }

    private fun saveImageFromUrl(imageUrl: String, sourceTab: Tab) {
        if (imageUrl.startsWith("blob:", ignoreCase = true) ||
            imageUrl.startsWith("data:", ignoreCase = true)
        ) {
            showToast(activity.getString(R.string.blob_download_not_supported))
            return
        }

        if (BrowserBlocker.findMatch(imageUrl) != null) {
            showToast(activity.getString(R.string.download_blocked))
            return
        }
        val item = DownloadRepository.enqueueDownload(
            url = imageUrl,
            contentDisposition = null,
            mimeType = guessImageMimeFromUrl(imageUrl),
            contentLengthHint = -1L,
            userAgent = userAgentProvider(),
            referer = sourceTab.webView.url,
            profileName = if (sourceTab.isPrivate) privateProfileName else null,
        )
        DownloadService.start(activity)
        Snackbar.make(
            rootView,
            activity.getString(R.string.download_started, item.fileName),
            Snackbar.LENGTH_LONG,
        )
            .setAnchorView(navigationCard)
            .setAction(R.string.view_downloads) {
                openDownloadsScreen()
            }
            .show()
    }

    private fun guessImageMimeFromUrl(url: String): String? {
        val extension = try {
            val path = java.net.URI(url).path ?: return null
            path.substringAfterLast('.', "").lowercase()
        } catch (_: Exception) {
            return null
        }
        return when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "heic", "heif" -> "image/heif"
            "avif" -> "image/avif"
            "svg" -> "image/svg+xml"
            else -> null
        }
    }
}
