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
import android.graphics.Typeface
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.StyleSpan
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible

/**
 * v10 private start page (about:home for private/incognito tabs).
 *
 * Lives as a separate overlay inside `activity_main.xml`
 * (`private_start_page_overlay`) and is rendered by this controller.
 * Distinct from [StartPageView] because:
 *
 *   - The visual palette is theme-invariant ink — even in day mode the
 *     private surface stays dark — which means tons of bound colours
 *     would have to be conditional inside one controller. A separate
 *     class keeps the binding code straight.
 *   - The content is different: a fixed 4-tile Quick to row + a
 *     "promises" card rather than Pinned + Continue reading.
 *
 * Inline search experience: the pill is a real EditText that owns its
 * own autocomplete dropdown drawn directly below it (no jump to the
 * top address bar — the design intent for the private home page).
 */
class PrivateStartPageView(
    private val context: Context,
    private val overlay: View,
    private val listener: StartPageListener,
    private val prefs: BrowserPreferences,
) {

    /** Listener shape shared with [StartPageView] so MainActivity can
     *  hand both views the same dispatcher. */
    interface StartPageListener {
        /** Load a URL in the active tab. Hides the start page overlay
         *  and resumes the WebView. */
        fun onStartPageUrlTapped(url: String)
        /** User submitted typed text in the home-page search pill.
         *  MainActivity resolves URL vs. search query and loads. */
        fun onStartPageQuerySubmitted(query: String)
    }

    private val searchPill: View = overlay.findViewById(R.id.private_start_search_pill)
    private val searchInput: AddressEditText = overlay.findViewById(R.id.private_start_search_input)
    private val engineBadge: TextView = overlay.findViewById(R.id.private_start_engine_badge)
    private val quickRow: LinearLayout = overlay.findViewById(R.id.private_start_quick_row)
    private val disclaimer: TextView = overlay.findViewById(R.id.private_start_disclaimer)
    private val suggestionsContainer: LinearLayout =
        overlay.findViewById(R.id.private_start_suggestions)

    init {
        renderQuickRow()
        renderEngineBadge()
        renderDisclaimer()
        wireSearchInput()
    }

    fun show() {
        // Refresh the engine badge in case the user changed the
        // default search engine while the private tab was alive.
        renderEngineBadge()
        searchInput.setText("")
        suggestionsContainer.removeAllViews()
        suggestionsContainer.isVisible = false
        overlay.isVisible = true
    }

    fun hide() {
        overlay.isVisible = false
    }

    fun isShowing(): Boolean = overlay.isVisible

    // ---------------------------------------------------------------------
    // Quick to row — four fixed shortcuts the prototype calls out
    // ---------------------------------------------------------------------

    private fun renderQuickRow() {
        quickRow.removeAllViews()
        val inflater = LayoutInflater.from(context)
        QUICK_TO_TILES.forEachIndexed { index, tile ->
            val view = inflater.inflate(R.layout.item_private_quick_tile, quickRow, false) as LinearLayout
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            val gutter = (context.resources.displayMetrics.density * 4).toInt()
            val isFirst = index == 0
            val isLast = index == QUICK_TO_TILES.lastIndex
            lp.marginStart = if (isFirst) 0 else gutter
            lp.marginEnd = if (isLast) 0 else gutter
            view.layoutParams = lp

            val avatar = view.findViewById<TextView>(R.id.private_quick_tile_avatar)
            val label = view.findViewById<TextView>(R.id.private_quick_tile_label)
            avatar.text = tile.letter
            avatar.backgroundTintList = ColorStateList.valueOf(
                AvatarPalette.colourFor(context, tile.colourKey),
            )
            label.text = tile.label

            view.setOnClickListener { listener.onStartPageUrlTapped(tile.url) }
            quickRow.addView(view)
        }
    }

    // ---------------------------------------------------------------------
    // Engine badge inside the pill (DDG / GGL / etc.)
    // ---------------------------------------------------------------------

    private fun renderEngineBadge() {
        val name = SearchEngineResolver.displayName(prefs).lowercase()
        engineBadge.text = when {
            name.contains("duck") -> "DDG"
            name.contains("google") -> "GGL"
            name.contains("bing") -> "BNG"
            name.contains("brave") -> "BRV"
            name.contains("ecosia") -> "ECO"
            name.contains("startpage") -> "SPG"
            name.contains("qwant") -> "QWT"
            else -> name.take(3).uppercase()
        }
    }

    // ---------------------------------------------------------------------
    // Disclaimer line (italic lead + plain body, matches prototype)
    // ---------------------------------------------------------------------

    private fun renderDisclaimer() {
        val lead = context.getString(R.string.private_start_disclaimer_lead)
        val body = context.getString(R.string.private_start_disclaimer_body)
        val span = SpannableStringBuilder().apply {
            append(lead)
            setSpan(
                StyleSpan(Typeface.ITALIC),
                0,
                length,
                Spanned.SPAN_INCLUSIVE_INCLUSIVE,
            )
            append(body)
        }
        disclaimer.text = span
    }

    // ---------------------------------------------------------------------
    // Inline search input — same UX as the top address bar but the
    // dropdown paints right under the pill instead of the address bar
    // ---------------------------------------------------------------------

    private fun wireSearchInput() {
        searchInput.setOnEditorActionListener { _, actionId, event ->
            val pressedEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || pressedEnter) {
                val text = searchInput.text?.toString().orEmpty()
                collapseSuggestions()
                // The host hides the keyboard (window-level) and navigates.
                listener.onStartPageQuerySubmitted(text)
                true
            } else {
                false
            }
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!searchInput.isFocused) return
                refreshSuggestions(s?.toString().orEmpty())
            }
        })

        searchInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) refreshSuggestions(searchInput.text?.toString().orEmpty())
            else hideSuggestions()
        }

        // Dismiss the dropdown on BACK (older devices); the host's
        // IME-inset listener handles focus + navigation.
        searchInput.onBackPreIme = { collapseSuggestions() }
    }

    /** True while the user is actively editing the private search. */
    fun isEditing(): Boolean = searchInput.isFocused

    /** Collapse the dropdown + drop focus. Called by the host when the
     *  keyboard is dismissed so the dropdown never lingers. */
    fun onKeyboardHidden() {
        collapseSuggestions()
        if (searchInput.isFocused) searchInput.clearFocus()
    }

    private fun collapseSuggestions() {
        suggestionsContainer.isVisible = false
        suggestionsContainer.removeAllViews()
    }

    /** Filter HistoryRepository — note: HistoryRepository only stores
     *  *non*-private history (the recorder skips private tabs), so
     *  the dropdown shows the user's regular browsing history as
     *  suggestions even while in private mode. That's the same
     *  behaviour Chrome incognito has: you can navigate to a site
     *  you've been to publicly by typing it, while the new visit
     *  itself doesn't get recorded. */
    private fun refreshSuggestions(query: String) {
        val trimmed = query.trim()
        val matches = if (trimmed.isEmpty()) {
            HistoryRepository.snapshot().take(SUGGESTION_LIMIT)
        } else {
            HistoryRepository.snapshot().asSequence()
                .filter {
                    it.url.contains(trimmed, ignoreCase = true) ||
                        it.title.contains(trimmed, ignoreCase = true)
                }
                .take(SUGGESTION_LIMIT)
                .toList()
        }
        suggestionsContainer.removeAllViews()
        if (matches.isEmpty()) {
            suggestionsContainer.isVisible = false
            return
        }
        val inflater = LayoutInflater.from(context)
        for (entry in matches) {
            val row = inflater.inflate(
                R.layout.item_address_suggestion,
                suggestionsContainer,
                false,
            )
            val title = row.findViewById<TextView>(R.id.suggestion_title)
            val url = row.findViewById<TextView>(R.id.suggestion_url)
            title.text = entry.title.ifBlank { entry.url }
            title.setTextColor(ContextCompat.getColor(context, R.color.tab_private_text))
            url.text = UrlInputUtils.prettifyUrl(entry.url)
                .removePrefix("https://")
                .removePrefix("http://")
            url.setTextColor(ContextCompat.getColor(context, R.color.tab_private_hint))
            row.setOnClickListener { listener.onStartPageUrlTapped(entry.url) }
            suggestionsContainer.addView(row)
        }
        suggestionsContainer.isVisible = true
    }

    private fun hideSuggestions() {
        // Defer slightly so a tap inside a suggestion row still fires
        // before the focus listener tears the container down.
        suggestionsContainer.postDelayed({
            if (!searchInput.isFocused) {
                suggestionsContainer.isVisible = false
                suggestionsContainer.removeAllViews()
            }
        }, FOCUS_DISMISS_DELAY_MS)
    }

    /** Avatar colour palette — duplicated locally rather than shared
     *  with [StartPageView] / [TabSwitcherView] because they're
     *  independent and a single top-level helper would have to live
     *  somewhere arbitrary. */
    private object AvatarPalette {
        private val palette = intArrayOf(
            R.color.avatar_terracotta,
            R.color.avatar_purple,
            R.color.avatar_green,
            R.color.avatar_blue,
            R.color.avatar_amber,
            R.color.avatar_ink,
        )

        fun colourFor(context: Context, key: String): Int {
            val needle = key.lowercase().ifBlank { "?" }
            var hash = 0x811c9dc5.toInt()
            for (c in needle) {
                hash = hash xor c.code
                hash = (hash * 0x01000193).toInt()
            }
            val idx = (hash and 0x7fffffff) % palette.size
            return ContextCompat.getColor(context, palette[idx])
        }
    }

    private data class QuickTile(
        val label: String,
        val url: String,
        val letter: String,
        val colourKey: String,
    )

    companion object {
        private const val SUGGESTION_LIMIT = 6
        private const val FOCUS_DISMISS_DELAY_MS = 120L

        /** Fixed shortcuts that prototype the v10 private home page
         *  shows. Privacy-respecting search + reference + archive —
         *  none of these are bookmarks (the user can't edit this
         *  row), they're hard-coded for incognito context. */
        private val QUICK_TO_TILES = listOf(
            QuickTile("DuckDuckGo", "https://duckduckgo.com/", "D", "duckduckgo"),
            QuickTile("Kagi", "https://kagi.com/", "K", "kagi"),
            QuickTile("Wikipedia", "https://www.wikipedia.org/", "W", "wikipedia"),
            QuickTile("Archive", "https://archive.org/", "A", "archive.org"),
        )
    }
}
