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
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListPopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService

/**
 * Owns the address-bar suggestion dropdown: the popup window, its adapter, the
 * keystroke debounce, and the logic that blends ranked history matches with
 * live search-engine completions.
 *
 * Extracted from MainActivity, which keeps the address-bar wiring itself
 * (TextWatcher / focus / editor action) and calls [configure], [refresh],
 * [submit], and [dismiss] here. Collaborators are passed explicitly — the bar,
 * the root view (for popup sizing), prefs, and two callbacks — so the
 * suggestion logic carries no back-reference to the activity's internals.
 */
class SearchController(
    private val activity: Activity,
    private val addressBar: AddressEditText,
    private val rootView: View,
    private val prefs: BrowserPreferences,
    private val onLoadAddress: (String) -> Unit,
    private val resolveUserInput: (String) -> String,
) {

    private val adapter = AddressSuggestionAdapter(activity)
    private var suggestionPopup: ListPopupWindow? = null

    /** Most recent address-bar text we requested suggestions for; used to
     *  drop out-of-order async engine responses. */
    private var lastSuggestQuery: String = ""

    private val debounceHandler = Handler(Looper.getMainLooper())
    private var debounceRunnable: Runnable? = null

    val isShowing: Boolean get() = suggestionPopup?.isShowing == true

    /** One-time setup. Call once the address bar exists (after bindViews). */
    fun configure() {
        // Dismiss the dropdown on BACK (older devices). Focus + keyboard +
        // navigation are handled by the IME-inset listener / back
        // dispatcher so we don't race them.
        addressBar.onBackPreIme = { dismiss() }
        suggestionPopup = ListPopupWindow(activity).apply {
            setAdapter(adapter)
            setBackgroundDrawable(ContextCompat.getDrawable(activity, R.drawable.bg_address_suggestions))
            setOnItemClickListener { _, _, position, _ ->
                val entry = adapter.getItem(position) ?: return@setOnItemClickListener
                dismiss()
                addressBar.clearFocus()
                hideKeyboard()
                onLoadAddress(entry.url)
            }
        }
    }

    /** Resolve and load whatever the user has typed (Go / Enter). */
    fun submit() {
        val resolved = resolveUserInput(addressBar.text?.toString().orEmpty())
        dismiss()
        addressBar.clearFocus()
        hideKeyboard()
        onLoadAddress(resolved)
    }

    fun refresh(query: String) {
        lastSuggestQuery = query
        // Show local results (history + "search this") instantly, then fold
        // in live engine suggestions after a short debounce so we don't fire
        // a request on every keystroke.
        applySuggestions(buildSuggestions(query))

        debounceRunnable?.let { debounceHandler.removeCallbacks(it) }
        val trimmed = query.trim()
        if (prefs.searchSuggestionsEnabled &&
            trimmed.isNotEmpty() &&
            !UrlInputUtils.looksLikeUrl(trimmed)
        ) {
            val runnable = Runnable {
                SearchSuggestions.fetch(prefs, query) { forQuery, suggestions ->
                    // Drop stale responses: only apply if the user hasn't
                    // typed on, the bar is still focused, and we got results.
                    if (forQuery == lastSuggestQuery &&
                        addressBar.hasFocus() &&
                        suggestions.isNotEmpty()
                    ) {
                        applySuggestions(merge(forQuery, suggestions))
                    }
                }
            }
            debounceRunnable = runnable
            debounceHandler.postDelayed(runnable, SUGGESTION_DEBOUNCE_MS)
        }
    }

    fun dismiss() {
        suggestionPopup?.dismiss()
    }

    /** Drop any pending debounced engine request (call from onDestroy). */
    fun cancelPending() {
        debounceHandler.removeCallbacksAndMessages(null)
    }

    private fun applySuggestions(suggestions: List<HistoryEntry>) {
        adapter.submit(suggestions)
        if (suggestions.isEmpty()) {
            dismiss()
        } else {
            show()
        }
    }

    private fun show() {
        val popup = suggestionPopup ?: return
        if (!addressBar.hasFocus() || addressBar.windowToken == null) return

        val anchor = anchor()
        val gutter = dp(8)
        val screenWidth = rootView.width.takeIf { it > 0 } ?: anchor.width
        val targetWidth = (screenWidth - gutter * 2).coerceAtLeast(dp(260))

        val anchorLocation = IntArray(2)
        anchor.getLocationOnScreen(anchorLocation)
        val rootLocation = IntArray(2)
        rootView.getLocationOnScreen(rootLocation)
        val anchorScreenLeft = anchorLocation[0]
        val rootScreenLeft = rootLocation[0]
        val targetLeft = rootScreenLeft + gutter
        popup.setAnchorView(anchor)
        popup.width = targetWidth
        popup.height = ViewGroup.LayoutParams.WRAP_CONTENT
        popup.horizontalOffset = targetLeft - anchorScreenLeft
        popup.verticalOffset = dp(6)
        if (!popup.isShowing) {
            popup.show()
        }
    }

    /** Local-only suggestions: ranked history matches plus a "search this"
     *  row. The async network path layers engine suggestions on top via
     *  [merge]. */
    private fun buildSuggestions(query: String): List<HistoryEntry> =
        merge(query, emptyList())

    /**
     * Compose the final suggestion list: a few ranked history matches,
     * then live engine suggestions as search rows, then a guaranteed
     * "search the raw text" row — all de-duplicated, capped at
     * [ADDRESS_SUGGESTION_LIMIT].
     */
    private fun merge(
        query: String,
        networkSuggestions: List<String>,
    ): List<HistoryEntry> {
        val result = ArrayList<HistoryEntry>()
        val seenUrls = HashSet<String>()
        val seenQueries = HashSet<String>()

        for (h in rankedHistoryMatches(query)) {
            if (result.size >= HISTORY_SUGGESTION_LIMIT) break
            if (seenUrls.add(UrlInputUtils.canonicalForCompare(h.url))) result.add(h)
        }
        for (s in networkSuggestions) {
            if (result.size >= ADDRESS_SUGGESTION_LIMIT) break
            val text = s.trim()
            if (text.isNotEmpty() && seenQueries.add(text.lowercase())) {
                result.add(searchSuggestionEntry(text))
            }
        }
        val raw = query.trim()
        if (raw.isNotEmpty() &&
            result.size < ADDRESS_SUGGESTION_LIMIT &&
            seenQueries.add(raw.lowercase())
        ) {
            result.add(searchSuggestionEntry(raw))
        }
        return result
    }

    private fun rankedHistoryMatches(query: String): List<HistoryEntry> {
        val needle = query.trim().lowercase()
        val seen = HashSet<String>()
        val matches = HistoryRepository.snapshot().asSequence()
            .filter { entry ->
                if (needle.isBlank()) {
                    true
                } else {
                    entry.title.lowercase().contains(needle) ||
                        entry.url.lowercase().contains(needle) ||
                        (Uri.parse(entry.url).host?.lowercase()?.contains(needle) == true)
                }
            }
            .filter { seen.add(UrlInputUtils.canonicalForCompare(it.url)) }
            .toList()
        if (needle.isBlank()) return matches.take(ADDRESS_SUGGESTION_LIMIT)
        // Host/title prefix matches rank above mid-string matches so the
        // site you're clearly typing wins the top slot.
        return matches.sortedByDescending { entry ->
            val host = Uri.parse(entry.url).host?.lowercase().orEmpty()
            when {
                host.startsWith(needle) || host.removePrefix("www.").startsWith(needle) -> 2
                entry.title.lowercase().startsWith(needle) -> 1
                else -> 0
            }
        }.take(ADDRESS_SUGGESTION_LIMIT)
    }

    private fun searchSuggestionEntry(text: String): HistoryEntry =
        HistoryEntry(
            id = SUGGESTION_ID_PREFIX + text,
            title = text,
            url = SearchEngineResolver.buildSearchUrl(prefs, text),
            visitedAt = 0L,
        )

    private fun anchor(): View {
        return (addressBar.parent as? View) ?: addressBar
    }

    private fun hideKeyboard() {
        activity.getSystemService<InputMethodManager>()
            ?.hideSoftInputFromWindow(addressBar.windowToken, 0)
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    private class AddressSuggestionAdapter(
        private val context: Context,
    ) : BaseAdapter() {
        private var items: List<HistoryEntry> = emptyList()

        fun submit(newItems: List<HistoryEntry>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): HistoryEntry? = items.getOrNull(position)

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context)
                .inflate(R.layout.item_address_suggestion, parent, false)
            val entry = getItem(position) ?: return view
            val icon = view.findViewById<ImageView>(R.id.suggestion_icon)
            val title = view.findViewById<TextView>(R.id.suggestion_title)
            val url = view.findViewById<TextView>(R.id.suggestion_url)
            if (entry.id.startsWith(SUGGESTION_ID_PREFIX)) {
                icon.setImageResource(R.drawable.ic_search_24)
                title.text = entry.title
                url.text = context.getString(R.string.suggestion_search_label)
            } else {
                icon.setImageResource(R.drawable.ic_history_24)
                title.text = entry.title.ifBlank { entry.url }
                url.text = UrlInputUtils.prettifyUrl(entry.url)
                    .removePrefix("https://")
                    .removePrefix("http://")
            }
            return view
        }
    }

    companion object {
        private const val ADDRESS_SUGGESTION_LIMIT = 8

        /** Cap on history rows shown before engine/search suggestions, so a
         *  long history doesn't crowd out live search completions. */
        private const val HISTORY_SUGGESTION_LIMIT = 3

        /** Settle time before an engine suggestion request fires, so a burst
         *  of keystrokes makes at most one network call. */
        private const val SUGGESTION_DEBOUNCE_MS = 160L

        /** Marker prefix on a synthetic search-suggestion [HistoryEntry] id so
         *  the adapter can paint it with the search glyph. */
        private const val SUGGESTION_ID_PREFIX = "suggest:"
    }
}
