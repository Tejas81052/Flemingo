package me.thimmaiah.effectivebrowser

import android.content.Context
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView

/**
 * Bottom-sheet UI for switching between, closing, and creating tabs.
 *
 * Implementation notes:
 *
 *  - The sheet receives a snapshot via [show] and re-renders in place when
 *    [refresh] is called by the host activity. We deliberately don't store a
 *    live reference to the [MainActivity.tabs] list so the adapter can never
 *    observe a mutation mid-bind.
 *
 *  - All UI mutations are routed back through callbacks the host implements
 *    (switch / close / new). This keeps the tab-management invariants —
 *    active-tab tracking, lifecycle pause/resume, address-bar refresh —
 *    centralised in the activity rather than scattered into the dialog.
 *
 *  - Close buttons are guarded by a single-shot guard: if the user
 *    spam-taps close on multiple rows the host still receives one event
 *    per tap, but adapter rebinds clear the cached index → button mapping
 *    so we don't act on stale data.
 */
class TabSwitcherSheet(
    private val context: Context,
    private val onSwitchToTab: (String) -> Unit,
    private val onCloseTab: (String) -> Unit,
    private val onNewTab: () -> Unit,
) {
    data class TabSnapshot(
        /** Stable id of the underlying tab (the host's [Tab.id]). The
         *  switcher uses this to dispatch callbacks instead of the
         *  list-position index, so a rapid double-tap on a close button
         *  can't close the wrong tab after the list shifts under us
         *  (the `bindingAdapterPosition` becomes NO_POSITION during
         *  rebind; the previous fallback to the captured `position`
         *  parameter was the *original* bind index — stale once any
         *  earlier row was removed). Ids never alias, so the dispatch
         *  is always to the tab the user actually tapped. */
        val id: String,
        val title: String,
        val url: String,
        /** True for private (incognito) tabs. The card prefixes the URL
         *  line with a "Private · " marker so the user can tell at a
         *  glance which tabs are isolated from the default session. */
        val isPrivate: Boolean = false,
    )

    private var dialog: BottomSheetDialog? = null
    private var adapter: TabAdapter? = null
    private var titleView: TextView? = null

    fun show(snapshots: List<TabSnapshot>, activeIndex: Int) {
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.dialog_tab_switcher, null)

        val recyclerView: RecyclerView = view.findViewById(R.id.tab_switcher_list)
        val newTabButton: ImageButton = view.findViewById(R.id.tab_switcher_new)
        titleView = view.findViewById(R.id.tab_switcher_title)

        recyclerView.layoutManager = LinearLayoutManager(context)
        val ad = TabAdapter(
            onTabSelected = { id ->
                onSwitchToTab(id)
                dismiss()
            },
            onTabClosed = { id ->
                // Defer closing to the host so it can pause/destroy in the
                // right order; the host calls back into [refresh] afterwards.
                onCloseTab(id)
            },
        )
        adapter = ad
        recyclerView.adapter = ad
        ad.submit(snapshots, activeIndex)
        renderTitle(snapshots.size)

        newTabButton.setOnClickListener {
            onNewTab()
            dismiss()
        }

        val sheet = BottomSheetDialog(context).also { d ->
            d.setContentView(view)
            d.setOnDismissListener {
                dialog = null
                adapter = null
                titleView = null
            }
        }
        dialog = sheet
        sheet.show()
    }

    /**
     * Re-renders the sheet with a new snapshot — used by the host after the
     * user closes a tab from inside the sheet, so the list updates in place
     * instead of forcing them to reopen the menu.
     */
    fun refresh(snapshots: List<TabSnapshot>, activeIndex: Int) {
        adapter?.submit(snapshots, activeIndex)
        renderTitle(snapshots.size)
    }

    fun dismiss() {
        dialog?.dismiss()
    }

    fun isShowing(): Boolean = dialog?.isShowing == true

    private fun renderTitle(count: Int) {
        titleView?.text = context.getString(R.string.tab_switcher_title, count)
    }

    private class TabAdapter(
        private val onTabSelected: (String) -> Unit,
        private val onTabClosed: (String) -> Unit,
    ) : RecyclerView.Adapter<TabAdapter.VH>() {

        private var items: List<TabSnapshot> = emptyList()
        private var activeIndex: Int = -1

        fun submit(newItems: List<TabSnapshot>, newActiveIndex: Int) {
            items = newItems
            activeIndex = newActiveIndex
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_tab, parent, false)
            return VH(view, onTabSelected, onTabClosed)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position], isActive = position == activeIndex)
        }

        override fun getItemCount(): Int = items.size

        class VH(
            itemView: View,
            private val onTabSelected: (String) -> Unit,
            private val onTabClosed: (String) -> Unit,
        ) : RecyclerView.ViewHolder(itemView) {

            private val card: MaterialCardView = itemView as MaterialCardView
            private val titleView: TextView = itemView.findViewById(R.id.tab_title)
            private val urlView: TextView = itemView.findViewById(R.id.tab_url)
            private val closeButton: ImageButton = itemView.findViewById(R.id.tab_close)

            /**
             * Stable id of the tab currently bound to this view holder.
             * Click handlers route through this rather than a captured
             * position — `bindingAdapterPosition` goes to NO_POSITION
             * during a `notifyDataSetChanged` rebind, and the old
             * fallback `?: position` was the *original* bind index,
             * which is stale after the list shifts. Routing on id means
             * rapid taps on adjacent rows always close the tab the user
             * actually tapped.
             */
            private var boundId: String? = null

            fun bind(snapshot: TabSnapshot, isActive: Boolean) {
                boundId = snapshot.id
                val displayTitle = snapshot.title.ifBlank {
                    snapshot.url.ifBlank {
                        itemView.context.getString(R.string.new_tab)
                    }
                }
                titleView.text = displayTitle
                // Private tabs prefix the URL line with a textual
                // marker. We deliberately don't introduce a new colour
                // or drawable resource here — keeps the diff small and
                // works on any system theme. The marker is sourced from
                // a string resource so future translations cover it.
                val rendered = prettyHost(snapshot.url)
                urlView.text = if (snapshot.isPrivate) {
                    itemView.context.getString(R.string.tab_private_url_prefix, rendered)
                } else {
                    rendered
                }

                // Visual marker for the active tab. We only mutate stroke
                // width so the row keeps its normal layout metrics — adding
                // a stroke after the fact can shift content by 1 dp on some
                // densities, which looks jittery during list refresh.
                card.strokeWidth = if (isActive) {
                    (itemView.resources.displayMetrics.density * 2).toInt()
                } else {
                    0
                }

                itemView.setOnClickListener { boundId?.let(onTabSelected) }
                closeButton.setOnClickListener { boundId?.let(onTabClosed) }
            }

            private fun prettyHost(url: String): String {
                if (url.isBlank()) return itemView.context.getString(R.string.tab_blank_url_placeholder)
                return runCatching {
                    Uri.parse(url).host ?: url
                }.getOrDefault(url)
            }
        }
    }
}

/**
 * Convenience for activities that don't want to retain a [TabSwitcherSheet]
 * instance just to call [TabSwitcherSheet.refresh]. Currently unused but
 * kept here as a hook in case future code needs to drive sheet refresh from
 * a service or observer.
 */
@Suppress("unused")
internal fun AppCompatActivity.dummyTabSwitcherAnchor(): Unit = Unit
