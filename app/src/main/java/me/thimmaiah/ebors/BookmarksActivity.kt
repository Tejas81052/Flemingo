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
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout

class BookmarksActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var tabs: TabLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private val adapter = BookmarkRowAdapter()
    private var mode: Mode = Mode.BOOKMARKS
    private var bookmarks: List<Bookmark> = emptyList()
    private var history: List<HistoryEntry> = emptyList()

    private val bookmarksListener = object : BookmarkRepository.Listener {
        override fun onBookmarksChanged(bookmarks: List<Bookmark>) {
            this@BookmarksActivity.bookmarks = bookmarks
            if (mode == Mode.BOOKMARKS) refresh()
        }
    }
    private val historyListener = object : HistoryRepository.Listener {
        override fun onHistoryChanged(entries: List<HistoryEntry>) {
            history = entries
            if (mode == Mode.HISTORY) refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyAccentTheme(BrowserPreferences.from(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bookmarks)

        BookmarkRepository.initialize(applicationContext)
        HistoryRepository.initialize(applicationContext)

        toolbar = findViewById(R.id.bookmarks_toolbar)
        tabs = findViewById(R.id.bookmarks_tabs)
        recyclerView = findViewById(R.id.bookmarks_list)
        emptyView = findViewById(R.id.bookmarks_empty)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.bookmarks_root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_clear_history && mode == Mode.HISTORY) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.history_clear_title)
                    .setMessage(R.string.history_clear_message)
                    .setPositiveButton(R.string.history_clear_confirm) { _, _ ->
                        HistoryRepository.clear()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            } else {
                false
            }
        }

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                mode = if (tab.position == 0) Mode.BOOKMARKS else Mode.HISTORY
                refreshToolbar()
                refresh()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) = Unit
            override fun onTabReselected(tab: TabLayout.Tab?) = Unit
        })
    }

    override fun onStart() {
        super.onStart()
        BookmarkRepository.addListener(bookmarksListener)
        HistoryRepository.addListener(historyListener)
        refreshToolbar()
        refresh()
    }

    override fun onStop() {
        BookmarkRepository.removeListener(bookmarksListener)
        HistoryRepository.removeListener(historyListener)
        super.onStop()
    }

    private fun refreshToolbar() {
        toolbar.menu.findItem(R.id.action_clear_history)?.isVisible = mode == Mode.HISTORY
    }

    private fun refresh() {
        val rows: List<Row> = when (mode) {
            Mode.BOOKMARKS -> bookmarks.map { Row.Bookmark(it) }
            Mode.HISTORY -> history.map { Row.History(it) }
        }
        adapter.submitList(rows)
        emptyView.isVisible = rows.isEmpty()
        recyclerView.isVisible = rows.isNotEmpty()
        emptyView.setText(
            if (mode == Mode.BOOKMARKS) R.string.bookmarks_empty else R.string.history_empty,
        )
    }

    private fun openUrl(url: String) {
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_URL, url))
        finish()
    }

    /**
     * `ListAdapter`-backed bookmark / history rows. `Row` is a sealed
     * class so the diff callback has to switch on variant before doing
     * an id compare; per-variant equality lets bookmark renames /
     * history visited-at updates animate cleanly rather than triggering
     * a full rebind.
     */
    private inner class BookmarkRowAdapter : ListAdapter<Row, BookmarkRowAdapter.VH>(DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_bookmark, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val title: TextView = itemView.findViewById(R.id.bookmark_title)
            private val url: TextView = itemView.findViewById(R.id.bookmark_url)
            private val meta: TextView = itemView.findViewById(R.id.bookmark_meta)
            private val deleteButton: ImageButton = itemView.findViewById(R.id.bookmark_delete)
            private val openButton: ImageButton = itemView.findViewById(R.id.bookmark_open)

            fun bind(row: Row) {
                when (row) {
                    is Row.Bookmark -> {
                        title.text = row.value.title.ifBlank { row.value.url }
                        url.text = displayHost(row.value.url)
                        meta.text = DateUtils.getRelativeTimeSpanString(
                            row.value.createdAt,
                            System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS,
                        )
                        itemView.setOnClickListener { openUrl(row.value.url) }
                        openButton.setOnClickListener { openUrl(row.value.url) }
                        deleteButton.setOnClickListener {
                            BookmarkRepository.remove(row.value.id)
                            Toast.makeText(this@BookmarksActivity, R.string.bookmark_removed, Toast.LENGTH_SHORT).show()
                        }
                    }
                    is Row.History -> {
                        title.text = row.value.title.ifBlank { row.value.url }
                        url.text = displayHost(row.value.url)
                        meta.text = DateUtils.getRelativeTimeSpanString(
                            row.value.visitedAt,
                            System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS,
                        )
                        itemView.setOnClickListener { openUrl(row.value.url) }
                        openButton.setOnClickListener { openUrl(row.value.url) }
                        deleteButton.setOnClickListener {
                            HistoryRepository.remove(row.value.id)
                        }
                    }
                }
            }

            private fun displayHost(raw: String): String {
                return runCatching { Uri.parse(raw).host ?: raw }.getOrDefault(raw)
            }
        }
    }

    private sealed class Row {
        data class Bookmark(val value: me.thimmaiah.ebors.Bookmark) : Row()
        data class History(val value: HistoryEntry) : Row()
    }

    private enum class Mode { BOOKMARKS, HISTORY }

    companion object {
        const val EXTRA_URL = "extra_url"

        /**
         * DiffUtil for the sealed `Row` type. Kotlin inner classes can't
         * have companion objects, so we keep this at file scope and let
         * the inner adapter reference it directly.
         */
        private val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(oldItem: Row, newItem: Row): Boolean = when {
                oldItem is Row.Bookmark && newItem is Row.Bookmark ->
                    oldItem.value.id == newItem.value.id
                oldItem is Row.History && newItem is Row.History ->
                    oldItem.value.id == newItem.value.id
                else -> false
            }

            override fun areContentsTheSame(oldItem: Row, newItem: Row): Boolean = when {
                oldItem is Row.Bookmark && newItem is Row.Bookmark ->
                    oldItem.value == newItem.value
                oldItem is Row.History && newItem is Row.History ->
                    oldItem.value == newItem.value
                else -> false
            }
        }
    }
}
