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
import android.content.res.ColorStateList
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

/**
 * v10 paper-theme full-screen tab switcher.
 *
 * Lives as an inline `LinearLayout` inside `activity_main.xml`
 * (`tab_switcher_overlay`) rather than a BottomSheetDialog so the
 * activity's own bottom chrome stays visible behind it — matching the
 * prototype where the user can see (and tap) the bottom nav while the
 * switcher is up.
 *
 * Responsibilities:
 *
 *  - Render the "N open tabs" title + segmented Regular/Private filter
 *    over a 2-column card grid populated from the host's snapshot.
 *  - Route taps (switch / close / new) back through the [Listener]
 *    interface so tab-management invariants — active-tab tracking,
 *    pause/resume lifecycle, address-bar refresh — stay centralised
 *    in the activity.
 *  - Auto-pick the segment containing the active tab when first shown,
 *    so a private user opening the switcher lands on the private list
 *    without an extra tap.
 *
 *  Snapshots arrive via [show] / [refresh]. Diffing happens at the
 *  adapter level (the [TabCardAdapter] uses a stable-id DiffUtil
 *  callback) so rapid open/close inside the switcher only rebinds the
 *  rows that actually changed.
 */
class TabSwitcherView(
    private val context: Context,
    private val overlay: View,
    private val listener: Listener,
) {

    /** Stable snapshot of one open tab — identical fields as the
     *  previous TabSwitcherSheet.TabSnapshot for source compatibility
     *  with [MainActivity.buildTabSnapshots]. */
    data class TabSnapshot(
        val id: String,
        val title: String,
        val url: String,
        val isPrivate: Boolean = false,
        /** v10.1 live tab thumbnail. Captured by Tab.captureThumbnail()
         *  in MainActivity.switchToTab / showTabSwitcher; null until a
         *  capture has run for this tab. Private tabs always carry null
         *  here — the card paints the incognito placeholder instead. */
        val thumbnail: android.graphics.Bitmap? = null,
    )

    interface Listener {
        fun onSwitchToTab(id: String)
        fun onCloseTab(id: String)
        fun onNewTab(isPrivate: Boolean)
        /** Called whenever the overlay flips from visible to gone, so
         *  the host can restore top chrome + clear the active-state
         *  indicator. */
        fun onSwitcherClosed()
    }

    private val titleView: TextView = overlay.findViewById(R.id.tab_switcher_title)
    private val newTabButton: ImageButton = overlay.findViewById(R.id.tab_switcher_new)
    private val segmentRegular: View = overlay.findViewById(R.id.tab_segment_regular)
    private val segmentPrivate: View = overlay.findViewById(R.id.tab_segment_private)
    private val segmentRegularLabel: TextView =
        overlay.findViewById(R.id.tab_segment_regular_label)
    private val segmentRegularCount: TextView =
        overlay.findViewById(R.id.tab_segment_regular_count)
    private val segmentPrivateLabel: TextView =
        overlay.findViewById(R.id.tab_segment_private_label)
    private val segmentPrivateCount: TextView =
        overlay.findViewById(R.id.tab_segment_private_count)
    private val segmentPrivateIcon: ImageView =
        overlay.findViewById(R.id.tab_segment_private_icon)
    private val list: RecyclerView = overlay.findViewById(R.id.tab_switcher_list)
    private val emptyView: TextView = overlay.findViewById(R.id.tab_switcher_empty)

    private val adapter = TabCardAdapter(
        onCardClicked = { id ->
            listener.onSwitchToTab(id)
            dismiss()
        },
        onCloseClicked = { id ->
            listener.onCloseTab(id)
            // Stay open so the user can keep closing tabs; the host
            // calls refresh() afterwards with the new snapshot.
        },
    )

    private var snapshots: List<TabSnapshot> = emptyList()
    private var showPrivate: Boolean = false

    init {
        list.layoutManager = GridLayoutManager(context, GRID_COLUMNS)
        list.adapter = adapter
        list.addItemDecoration(TabGridSpacing(context, GRID_COLUMNS))

        newTabButton.setOnClickListener {
            // Match whichever segment the user is currently on. Lets
            // them open another private tab without leaving the
            // private segment first.
            listener.onNewTab(showPrivate)
            dismiss()
        }

        segmentRegular.setOnClickListener {
            if (showPrivate) {
                showPrivate = false
                paintSegments()
                rebind()
            }
        }
        segmentPrivate.setOnClickListener {
            if (!showPrivate) {
                showPrivate = true
                paintSegments()
                rebind()
            }
        }
    }

    fun show(snapshots: List<TabSnapshot>, activeIndex: Int) {
        this.snapshots = snapshots
        // Pick the segment containing the active tab so a private
        // user lands on the private list without an extra tap.
        showPrivate = snapshots.getOrNull(activeIndex)?.isPrivate == true &&
            snapshots.any { it.isPrivate }
        overlay.isVisible = true
        paintAll()
    }

    fun refresh(snapshots: List<TabSnapshot>, activeIndex: Int) {
        this.snapshots = snapshots
        // Don't move the user off whichever segment they're on just
        // because the active tab is in the other segment; only honour
        // the active-tab segment on the *initial* show.
        if (snapshots.none { it.isPrivate == showPrivate }) {
            // Fall back to the segment that still has tabs, otherwise
            // dismiss because the entire side just emptied.
            val hasOther = snapshots.any { it.isPrivate != showPrivate }
            if (hasOther) {
                showPrivate = !showPrivate
            } else {
                dismiss()
                return
            }
        }
        paintAll()
    }

    fun dismiss() {
        if (!overlay.isVisible) return
        overlay.isVisible = false
        listener.onSwitcherClosed()
    }

    fun isShowing(): Boolean = overlay.isVisible

    /** Programmatic bottom padding for the grid so the last row never
     *  hides under the bottom chrome. Called from MainActivity once
     *  the bottom chrome has measured. */
    fun setBottomInset(px: Int) {
        list.setPadding(
            list.paddingLeft,
            list.paddingTop,
            list.paddingRight,
            px + context.resources.getDimensionPixelSize(
                R.dimen.tab_switcher_list_bottom_extra,
            ),
        )
    }

    private fun paintAll() {
        paintTitle()
        paintSegments()
        rebind()
    }

    private fun paintTitle() {
        // Match the prototype's mixed-style heading ("N open tabs"
        // where "open tabs" is italic). The format string carries the
        // <i> tags so a translator can decide which words to italicise.
        val count = if (showPrivate) {
            snapshots.count { it.isPrivate }
        } else {
            snapshots.count { !it.isPrivate }
        }
        val raw = context.getString(R.string.tab_switcher_open_tabs_title, count)
        titleView.text = HtmlCompat.fromHtml(raw, HtmlCompat.FROM_HTML_MODE_LEGACY)
    }

    private fun paintSegments() {
        val regularCount = snapshots.count { !it.isPrivate }
        val privateCount = snapshots.count { it.isPrivate }
        segmentRegularCount.text = regularCount.toString()
        segmentPrivateCount.text = privateCount.toString()

        // Active segment gets the surface-paper pill background +
        // bold label + accent-strength icon tint; inactive segment
        // sits flat on the muted container.
        if (showPrivate) {
            segmentRegular.setBackgroundResource(android.R.color.transparent)
            segmentRegularLabel.setTextColor(
                ContextCompat.getColor(context, R.color.browser_text_2),
            )
            segmentRegularLabel.typeface = android.graphics.Typeface.DEFAULT

            segmentPrivate.setBackgroundResource(R.drawable.bg_segment_active)
            segmentPrivateLabel.setTextColor(
                ContextCompat.getColor(context, R.color.browser_text),
            )
            segmentPrivateLabel.typeface = android.graphics.Typeface.DEFAULT_BOLD
            segmentPrivateIcon.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.browser_text),
            )
        } else {
            segmentRegular.setBackgroundResource(R.drawable.bg_segment_active)
            segmentRegularLabel.setTextColor(
                ContextCompat.getColor(context, R.color.browser_text),
            )
            segmentRegularLabel.typeface = android.graphics.Typeface.DEFAULT_BOLD

            segmentPrivate.setBackgroundResource(android.R.color.transparent)
            segmentPrivateLabel.setTextColor(
                ContextCompat.getColor(context, R.color.browser_text_2),
            )
            segmentPrivateLabel.typeface = android.graphics.Typeface.DEFAULT
            segmentPrivateIcon.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.browser_text_2),
            )
        }
    }

    private fun rebind() {
        val visible = snapshots.filter { it.isPrivate == showPrivate }
        adapter.submitList(visible)
        val empty = visible.isEmpty()
        emptyView.isVisible = empty
        list.isVisible = !empty
        if (empty) {
            emptyView.setText(
                if (showPrivate) R.string.tab_switcher_empty_private
                else R.string.tab_switcher_empty_regular,
            )
        }
    }

    /** RecyclerView decoration that adds an even gap between grid
     *  items. Pulled out so the spacing constant lives in one place
     *  and survives configuration changes. */
    private class TabGridSpacing(
        context: Context,
        private val columns: Int,
    ) : RecyclerView.ItemDecoration() {
        private val spacing = context.resources.getDimensionPixelSize(
            R.dimen.tab_switcher_grid_spacing,
        )

        override fun getItemOffsets(
            outRect: android.graphics.Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State,
        ) {
            val position = parent.getChildAdapterPosition(view)
            if (position == RecyclerView.NO_POSITION) return
            val column = position % columns
            outRect.left = if (column == 0) 0 else spacing / 2
            outRect.right = if (column == columns - 1) 0 else spacing / 2
            outRect.bottom = spacing
        }
    }

    private class TabCardAdapter(
        private val onCardClicked: (String) -> Unit,
        private val onCloseClicked: (String) -> Unit,
    ) : ListAdapter<TabSnapshot, TabCardAdapter.VH>(DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_tab_card, parent, false)
            return VH(view, onCardClicked, onCloseClicked)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(getItem(position))
        }

        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<TabSnapshot>() {
                override fun areItemsTheSame(
                    oldItem: TabSnapshot,
                    newItem: TabSnapshot,
                ): Boolean = oldItem.id == newItem.id

                override fun areContentsTheSame(
                    oldItem: TabSnapshot,
                    newItem: TabSnapshot,
                ): Boolean = oldItem == newItem
            }
        }

        class VH(
            itemView: View,
            private val onCardClicked: (String) -> Unit,
            private val onCloseClicked: (String) -> Unit,
        ) : RecyclerView.ViewHolder(itemView) {
            private val root: View = itemView.findViewById(R.id.tab_card_root)
            private val avatar: TextView = itemView.findViewById(R.id.tab_avatar)
            private val titleView: TextView = itemView.findViewById(R.id.tab_title)
            private val closeButton: ImageButton = itemView.findViewById(R.id.tab_close)
            private val thumbnail: View = itemView.findViewById(R.id.tab_thumbnail)
            private val thumbnailImage: ImageView = itemView.findViewById(R.id.tab_thumbnail_image)
            private val skeletonGroup: View = itemView.findViewById(R.id.tab_skeleton_group)
            private val privateGroup: View = itemView.findViewById(R.id.tab_private_group)
            private val hostView: TextView = itemView.findViewById(R.id.tab_host)

            fun bind(snapshot: TabSnapshot) {
                val ctx = itemView.context
                val host = extractHost(snapshot.url)
                val title = snapshot.title.ifBlank {
                    host.ifBlank { ctx.getString(R.string.tab_blank_url_placeholder) }
                }

                // Card body: regular or private treatment. Both
                // variants of every relevant drawable / colour exist
                // so we can switch wholesale here without re-laying
                // out the view holder.
                //
                // Three thumbnail states (exactly one painted):
                //   • Live screenshot (regular tab, captureThumbnail
                //     has run) → tab_thumbnail_image visible, centre-
                //     cropped into the square so the page middle shows
                //   • Skeleton bars (regular tab, never captured) →
                //     tab_skeleton_group visible (boot / first-load
                //     placeholder)
                //   • Incognito glyph (private tab) → tab_private_group
                //     visible, ink-coloured chrome
                val liveBitmap = snapshot.thumbnail?.takeIf { !it.isRecycled }
                if (snapshot.isPrivate) {
                    root.setBackgroundResource(R.drawable.bg_tab_card_private)
                    thumbnail.setBackgroundResource(R.drawable.bg_tab_thumbnail_private)
                    thumbnailImage.setImageDrawable(null)
                    thumbnailImage.isVisible = false
                    skeletonGroup.isVisible = false
                    privateGroup.isVisible = true
                    titleView.setTextColor(
                        ContextCompat.getColor(ctx, R.color.tab_private_text),
                    )
                    hostView.setTextColor(
                        ContextCompat.getColor(ctx, R.color.tab_private_hint),
                    )
                    closeButton.imageTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(ctx, R.color.tab_private_hint),
                    )
                } else {
                    root.setBackgroundResource(R.drawable.bg_tab_card)
                    thumbnail.setBackgroundResource(R.drawable.bg_tab_thumbnail)
                    privateGroup.isVisible = false
                    if (liveBitmap != null) {
                        skeletonGroup.isVisible = false
                        thumbnailImage.isVisible = true
                        thumbnailImage.setImageBitmap(liveBitmap)
                    } else {
                        thumbnailImage.setImageDrawable(null)
                        thumbnailImage.isVisible = false
                        skeletonGroup.isVisible = true
                    }
                    titleView.setTextColor(
                        ContextCompat.getColor(ctx, R.color.browser_text),
                    )
                    hostView.setTextColor(
                        ContextCompat.getColor(ctx, R.color.browser_text),
                    )
                    closeButton.imageTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(ctx, R.color.browser_hint),
                    )
                }

                titleView.text = title
                hostView.text = host.ifBlank {
                    ctx.getString(R.string.tab_blank_url_placeholder)
                }

                // Avatar letter + colour: derived from host so the same
                // site always lands on the same colour without any
                // persisted state.
                avatar.text = avatarLetterFor(title, host)
                avatar.backgroundTintList = ColorStateList.valueOf(
                    AvatarPalette.colourFor(ctx, host),
                )

                root.setOnClickListener { onCardClicked(snapshot.id) }
                closeButton.setOnClickListener { onCloseClicked(snapshot.id) }
            }

            private fun extractHost(url: String): String {
                if (url.isBlank()) return ""
                return runCatching {
                    Uri.parse(url).host?.removePrefix("www.").orEmpty()
                }.getOrDefault("")
            }

            private fun avatarLetterFor(title: String, host: String): String {
                val source = title.ifBlank { host }.ifBlank { "?" }
                val firstLetter = source.firstOrNull { it.isLetterOrDigit() } ?: '?'
                return firstLetter.uppercase()
            }
        }
    }

    /**
     * Picks a stable avatar colour for a hostname by hashing into a
     * fixed palette. Pure: no state, no Android-context handling
     * beyond `Context.getColor` to honour day/night overrides.
     */
    private object AvatarPalette {
        private val palette = intArrayOf(
            R.color.avatar_terracotta,
            R.color.avatar_purple,
            R.color.avatar_green,
            R.color.avatar_blue,
            R.color.avatar_amber,
            R.color.avatar_ink,
        )

        fun colourFor(context: Context, host: String): Int {
            val key = host.lowercase(Locale.US).ifBlank { "?" }
            // FNV-1a 32-bit hash — fast, decent spread for short ASCII
            // strings, and stable across processes (Java String.hashCode
            // isn't guaranteed stable across VM versions, though in
            // practice it is on Dalvik/ART).
            var hash = 0x811c9dc5.toInt()
            for (c in key) {
                hash = hash xor c.code
                hash = (hash * 0x01000193).toInt()
            }
            val idx = (hash and 0x7fffffff) % palette.size
            return ContextCompat.getColor(context, palette[idx])
        }
    }

    companion object {
        private const val GRID_COLUMNS = 2
    }
}
