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

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

class DownloadsActivity : AppCompatActivity(), DownloadRepository.Listener, DownloadAdapter.Listener {
    private lateinit var rootView: View
    private lateinit var toolbar: MaterialToolbar
    private lateinit var summaryView: TextView
    private lateinit var emptyView: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var filterGroup: ChipGroup
    private val adapter = DownloadAdapter(this)

    private var allDownloads: List<DownloadItem> = emptyList()
    private var currentFilter: Filter = Filter.ALL
    private var pendingInstallItem: DownloadItem? = null

    /**
     * The download rows show "5 minutes ago" relative-time labels.
     * Since [DownloadItem] equality wouldn't change just from wall-clock
     * advancing, DiffUtil never rebinds the row — so a row created two
     * minutes ago would forever read "two minutes ago" until something
     * else mutated the item. We tick the visible rows roughly once a
     * minute while the activity is in the foreground so the user sees
     * the labels advance. notifyItemRangeChanged-with-empty-payload is
     * cheap (no full rebind animation) and only affects laid-out rows.
     */
    private val timeRefreshHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val timeRefreshTickMs = 60_000L
    private val timeRefreshRunnable = object : Runnable {
        override fun run() {
            val count = adapter.itemCount
            if (count > 0) adapter.notifyItemRangeChanged(0, count)
            timeRefreshHandler.postDelayed(this, timeRefreshTickMs)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyAccentTheme(BrowserPreferences.from(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)

        DownloadRepository.initialize(applicationContext)

        rootView = findViewById(R.id.downloads_root)
        toolbar = findViewById(R.id.downloads_toolbar)
        summaryView = findViewById(R.id.downloads_summary)
        emptyView = findViewById(R.id.downloads_empty)
        recyclerView = findViewById(R.id.downloads_list)
        filterGroup = findViewById(R.id.downloads_filter)

        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_clear_completed -> {
                    confirmClearCompleted()
                    true
                }
                else -> false
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        filterGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            currentFilter = when (checkedIds.firstOrNull()) {
                R.id.filter_active -> Filter.ACTIVE
                R.id.filter_completed -> Filter.COMPLETED
                else -> Filter.ALL
            }
            refreshList()
        }
    }

    override fun onStart() {
        super.onStart()
        DownloadRepository.addListener(this)
        timeRefreshHandler.postDelayed(timeRefreshRunnable, timeRefreshTickMs)
    }

    override fun onStop() {
        DownloadRepository.removeListener(this)
        timeRefreshHandler.removeCallbacks(timeRefreshRunnable)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // The user may have just returned from the "Install unknown apps"
        // settings page. Continue the install flow if it's now permitted.
        val pending = pendingInstallItem
        if (pending != null && packageManager.canRequestPackageInstalls()) {
            pendingInstallItem = null
            launchApkInstall(pending)
        }
    }

    override fun onDownloadsChanged(downloads: List<DownloadItem>) {
        allDownloads = downloads
        refreshList()
    }

    private fun refreshList() {
        val filtered = when (currentFilter) {
            Filter.ALL -> allDownloads
            Filter.ACTIVE -> allDownloads.filter {
                it.status == DownloadStatus.QUEUED ||
                    it.status == DownloadStatus.DOWNLOADING ||
                    it.status == DownloadStatus.PAUSED
            }
            Filter.COMPLETED -> allDownloads.filter { it.status == DownloadStatus.COMPLETED }
        }

        adapter.submitList(filtered)
        emptyView.isVisible = filtered.isEmpty()
        recyclerView.isVisible = filtered.isNotEmpty()

        val activeCount = allDownloads.count {
            it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING
        }
        val completedCount = allDownloads.count { it.status == DownloadStatus.COMPLETED }
        val totalBytes = allDownloads
            .filter { it.status == DownloadStatus.COMPLETED }
            .sumOf { it.totalBytes.coerceAtLeast(0L) }

        summaryView.text = if (totalBytes > 0L) {
            getString(
                R.string.downloads_summary_with_total,
                activeCount,
                completedCount,
                Formatter.formatShortFileSize(this, totalBytes),
            )
        } else {
            getString(R.string.downloads_summary, activeCount, completedCount)
        }

        toolbar.menu.findItem(R.id.action_clear_completed)?.isVisible = completedCount > 0
    }

    private fun confirmClearCompleted() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_completed_title)
            .setMessage(R.string.clear_completed_message)
            .setPositiveButton(R.string.clear_completed) { _, _ ->
                DownloadRepository.clearCompleted(deleteFiles = false)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onPrimaryAction(item: DownloadItem) {
        when (item.status) {
            DownloadStatus.QUEUED,
            DownloadStatus.DOWNLOADING,
            -> DownloadRepository.pause(item.id)

            DownloadStatus.PAUSED,
            DownloadStatus.FAILED,
            DownloadStatus.CANCELLED,
            -> {
                DownloadRepository.retry(item.id)
                DownloadService.start(this)
            }

            DownloadStatus.COMPLETED -> openDownload(item)
        }
    }

    override fun onSecondaryAction(item: DownloadItem) {
        DownloadRepository.remove(item.id)
    }

    override fun onShare(item: DownloadItem) {
        val uri = resolveDownloadUri(item) ?: return
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = effectiveMimeType(item)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_download)))
    }

    override fun onOpen(item: DownloadItem) {
        openDownload(item)
    }

    private fun openDownload(item: DownloadItem) {
        if (isApk(item)) {
            handleApkOpen(item)
            return
        }

        val uri = resolveDownloadUri(item) ?: return
        val openIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, effectiveMimeType(item))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            startActivity(openIntent)
        } catch (_: ActivityNotFoundException) {
            showToast(getString(R.string.no_app_to_open_download))
        }
    }

    /**
     * Resolve a [DownloadItem] to a URI suitable for ACTION_VIEW /
     * ACTION_SEND. After B17 a download's `filePath` is either a
     * `content://` URI (new MediaStore-backed downloads) or a legacy
     * absolute file path (downloads created by an older build of this
     * app). Both are surfaced as `content://` URIs to the OS — MediaStore
     * URIs straight through, legacy paths via FileProvider — and both
     * paths show the "file is missing" toast when the target has been
     * deleted out from under us.
     */
    private fun resolveDownloadUri(item: DownloadItem): Uri? {
        if (!DownloadStorage.targetExists(this, item.filePath)) {
            showToast(getString(R.string.download_file_missing))
            return null
        }
        return if (DownloadStorage.isContentUri(item.filePath)) {
            Uri.parse(item.filePath)
        } else {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", File(item.filePath))
        }
    }

    private fun handleApkOpen(item: DownloadItem) {
        if (!packageManager.canRequestPackageInstalls()) {
            pendingInstallItem = item
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.install_apk_title)
                .setMessage(R.string.install_apk_permission_message)
                .setPositiveButton(R.string.install_apk_open_settings) { _, _ ->
                    try {
                        startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                                .setData(Uri.parse("package:$packageName")),
                        )
                    } catch (_: ActivityNotFoundException) {
                        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
                    }
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    pendingInstallItem = null
                }
                .setOnCancelListener { pendingInstallItem = null }
                .show()
            return
        }
        launchApkInstall(item)
    }

    private fun launchApkInstall(item: DownloadItem) {
        // resolveDownloadUri handles the "file was deleted" toast and
        // covers both MediaStore URIs and legacy FileProvider paths.
        // Either kind grants the package installer read access via
        // FLAG_GRANT_READ_URI_PERMISSION.
        val uri = resolveDownloadUri(item) ?: return
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(installIntent)
        } catch (_: ActivityNotFoundException) {
            showToast(getString(R.string.no_app_to_open_download))
        }
    }

    private fun isApk(item: DownloadItem): Boolean {
        val nameMatches = item.fileName.endsWith(".apk", ignoreCase = true)
        val mimeMatches = item.mimeType?.equals(
            "application/vnd.android.package-archive",
            ignoreCase = true,
        ) == true
        return nameMatches || mimeMatches
    }

    private fun effectiveMimeType(item: DownloadItem): String {
        val explicit = item.mimeType
            ?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
        if (explicit != null) {
            return explicit
        }
        val ext = item.fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "apk" -> "application/vnd.android.package-archive"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "txt" -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private enum class Filter { ALL, ACTIVE, COMPLETED }
}
