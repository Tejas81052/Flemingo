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

import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt

/**
 * Downloads RecyclerView adapter.
 *
 * Backed by a [ListAdapter] so updates are diffed in the background (B14):
 * with up to ~2 Hz progress ticks per active download, the previous
 * `notifyDataSetChanged()` was rebinding every visible row on every tick
 * and dropping any in-progress focus / scroll position. DiffUtil only
 * rebinds the rows that actually changed.
 *
 * `DownloadItem` is a data class so structural equality is enough for the
 * contents diff — every progress update produces a new instance with a new
 * `downloadedBytes`/`bytesPerSecond` and `areContentsTheSame` correctly
 * reports the row as changed.
 */
class DownloadAdapter(
    private val listener: Listener,
) : ListAdapter<DownloadItem, DownloadAdapter.DownloadViewHolder>(DIFF) {
    interface Listener {
        fun onPrimaryAction(item: DownloadItem)
        fun onSecondaryAction(item: DownloadItem)
        fun onShare(item: DownloadItem)
        fun onOpen(item: DownloadItem)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DownloadViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_download, parent, false)
        return DownloadViewHolder(view)
    }

    override fun onBindViewHolder(holder: DownloadViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<DownloadItem>() {
            override fun areItemsTheSame(oldItem: DownloadItem, newItem: DownloadItem): Boolean {
                return oldItem.id == newItem.id
            }

            // Data-class equality covers all visible fields (progress,
            // status, error message, file name). The `bytesPerSecond`
            // field changes on every persist tick so even a small
            // progress delta will mark the row as changed.
            override fun areContentsTheSame(oldItem: DownloadItem, newItem: DownloadItem): Boolean {
                return oldItem == newItem
            }
        }
    }

    inner class DownloadViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val iconView: ImageView = itemView.findViewById(R.id.download_icon)
        private val fileNameView: TextView = itemView.findViewById(R.id.download_file_name)
        private val metaView: TextView = itemView.findViewById(R.id.download_meta)
        private val statusView: TextView = itemView.findViewById(R.id.download_status)
        private val progressBar: ProgressBar = itemView.findViewById(R.id.download_progress)
        private val primaryAction: ImageButton = itemView.findViewById(R.id.download_primary_action)
        private val secondaryAction: ImageButton = itemView.findViewById(R.id.download_secondary_action)
        private val shareAction: ImageButton = itemView.findViewById(R.id.download_share_action)

        fun bind(item: DownloadItem) {
            val context = itemView.context
            fileNameView.text = item.fileName

            iconView.setImageResource(iconForMime(item.mimeType, item.fileName))

            val sourceLabel = item.host.ifBlank { context.getString(R.string.unknown_host) }
            val sizeLabel = if (item.totalBytes > 0L) {
                Formatter.formatShortFileSize(context, item.totalBytes)
            } else {
                null
            }
            val timeLabel = DateUtils.getRelativeTimeSpanString(
                item.createdAt,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
            ).toString()

            metaView.text = listOfNotNull(sourceLabel, sizeLabel, timeLabel)
                .joinToString(separator = "  ·  ")

            val statusText = when (item.status) {
                DownloadStatus.QUEUED -> context.getString(R.string.download_status_queued)
                DownloadStatus.DOWNLOADING -> {
                    val speedSuffix = if (item.bytesPerSecond > 0L) {
                        "  ·  " + Formatter.formatShortFileSize(context, item.bytesPerSecond) + "/s"
                    } else {
                        ""
                    }
                    if (item.totalBytes > 0L) {
                        context.getString(
                            R.string.download_status_downloading,
                            Formatter.formatShortFileSize(context, item.downloadedBytes),
                            Formatter.formatShortFileSize(context, item.totalBytes),
                        ) + speedSuffix
                    } else {
                        context.getString(
                            R.string.download_status_downloading_unknown,
                            Formatter.formatShortFileSize(context, item.downloadedBytes),
                        ) + speedSuffix
                    }
                }

                DownloadStatus.PAUSED -> context.getString(
                    R.string.download_status_paused,
                    Formatter.formatShortFileSize(context, item.downloadedBytes),
                )

                DownloadStatus.COMPLETED -> context.getString(
                    R.string.download_status_completed,
                    Formatter.formatShortFileSize(context, item.downloadedBytes),
                )

                DownloadStatus.FAILED -> item.errorMessage ?: context.getString(R.string.download_failed_generic)
                DownloadStatus.CANCELLED -> context.getString(R.string.download_status_cancelled)
            }
            statusView.text = statusText

            val showProgress = item.status != DownloadStatus.COMPLETED &&
                item.status != DownloadStatus.CANCELLED &&
                item.status != DownloadStatus.FAILED
            progressBar.isVisible = showProgress
            if (showProgress) {
                if (item.totalBytes > 0L) {
                    progressBar.isIndeterminate = false
                    progressBar.max = 1000
                    progressBar.progress = ((item.downloadedBytes.toDouble() / item.totalBytes.toDouble()) * 1000.0)
                        .roundToInt()
                        .coerceIn(0, 1000)
                } else {
                    progressBar.isIndeterminate = item.status == DownloadStatus.DOWNLOADING || item.status == DownloadStatus.QUEUED
                    progressBar.progress = 0
                }
            }

            val primaryIcon = when (item.status) {
                DownloadStatus.QUEUED,
                DownloadStatus.DOWNLOADING,
                -> R.drawable.ic_pause_24

                DownloadStatus.PAUSED,
                DownloadStatus.FAILED,
                -> R.drawable.ic_play_arrow_24

                DownloadStatus.COMPLETED -> R.drawable.ic_open_in_new_24
                DownloadStatus.CANCELLED -> R.drawable.ic_retry_24
            }
            val primaryDescription = when (item.status) {
                DownloadStatus.QUEUED,
                DownloadStatus.DOWNLOADING,
                -> R.string.pause_download

                DownloadStatus.PAUSED -> R.string.resume_download
                DownloadStatus.FAILED,
                DownloadStatus.CANCELLED,
                -> R.string.retry_download

                DownloadStatus.COMPLETED -> R.string.open_download
            }
            primaryAction.setImageResource(primaryIcon)
            primaryAction.contentDescription = context.getString(primaryDescription)

            val secondaryIcon = if (item.status == DownloadStatus.COMPLETED) {
                R.drawable.ic_delete_24
            } else {
                R.drawable.ic_close_24
            }
            val secondaryDescription = if (item.status == DownloadStatus.COMPLETED) {
                R.string.delete_download
            } else {
                R.string.cancel_download
            }
            secondaryAction.setImageResource(secondaryIcon)
            secondaryAction.contentDescription = context.getString(secondaryDescription)

            shareAction.isVisible = item.status == DownloadStatus.COMPLETED

            itemView.setOnClickListener {
                if (item.status == DownloadStatus.COMPLETED) {
                    listener.onOpen(item)
                }
            }
            primaryAction.setOnClickListener { listener.onPrimaryAction(item) }
            secondaryAction.setOnClickListener { listener.onSecondaryAction(item) }
            shareAction.setOnClickListener { listener.onShare(item) }
        }

        private fun iconForMime(mimeType: String?, fileName: String): Int {
            val type = mimeType?.lowercase() ?: guessMime(fileName)
            return when {
                type == null -> R.drawable.ic_file_24
                type.startsWith("image/") -> R.drawable.ic_file_image_24
                type.startsWith("video/") -> R.drawable.ic_file_video_24
                type.startsWith("audio/") -> R.drawable.ic_file_audio_24
                type == "application/pdf" -> R.drawable.ic_file_pdf_24
                type.startsWith("text/") -> R.drawable.ic_file_text_24
                type.contains("zip") || type.contains("compressed") || type.contains("archive") ->
                    R.drawable.ic_file_archive_24
                else -> R.drawable.ic_file_24
            }
        }

        private fun guessMime(fileName: String): String? {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp" -> "image/$ext"
                "mp4", "mkv", "webm", "mov", "avi", "m4v" -> "video/$ext"
                "mp3", "ogg", "wav", "flac", "m4a", "aac" -> "audio/$ext"
                "pdf" -> "application/pdf"
                "txt", "md", "log", "csv" -> "text/plain"
                "zip", "rar", "7z", "tar", "gz" -> "application/zip"
                else -> null
            }
        }
    }
}
