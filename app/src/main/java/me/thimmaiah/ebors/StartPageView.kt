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
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.core.view.isVisible
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * v10 paper-theme start page (about:home) controller.
 *
 * Lives as a fragment-free view controller bound to the inflated
 * `view_start_page.xml` layout inside `activity_main.xml`. Repopulates
 * everything on every [refresh] so the page stays accurate after
 * background events (tracker counter advanced, a new bookmark was
 * added) without a Lifecycle/listener wiring overhead.
 *
 * Repository reads happen synchronously off the in-memory caches the
 * repositories already maintain — none of the data shown is heavy
 * enough to need a coroutine.
 *
 * Click dispatch goes through a [Listener] interface so the activity
 * stays the single source of truth for "what does it mean to open a
 * URL / focus the search bar / edit the pinned set". The view itself
 * never calls `loadAddress` or starts activities directly.
 */
class StartPageView(
    private val context: Context,
    private val overlay: View,
    private val listener: Listener,
    private val prefs: BrowserPreferences,
) {

    interface Listener {
        /** User tapped a pinned tile or a Continue-reading row. Loads
         *  the URL in the active tab. */
        fun onStartPageUrlTapped(url: String)
        /** User pressed Enter / Go in the inline search input. The
         *  activity resolves URL vs. search-query and loads in the
         *  active tab. */
        fun onStartPageQuerySubmitted(query: String)
        /** User tapped the "EDIT" link beside Pinned. Opens Bookmarks. */
        fun onStartPageEditPinned()
        /** User tapped the "HISTORY ↗" link. Opens Bookmarks on the
         *  history segment. */
        fun onStartPageOpenHistory()
    }

    private val dateView: TextView = overlay.findViewById(R.id.start_page_date)
    private val greetingTopView: TextView = overlay.findViewById(R.id.start_page_greeting_top)
    private val greetingBottomView: TextView = overlay.findViewById(R.id.start_page_greeting_bottom)
    private val searchInput: AddressEditText =
        overlay.findViewById(R.id.start_page_search_input)
    private val engineBadge: TextView = overlay.findViewById(R.id.start_page_engine_badge)
    private val suggestionsContainer: LinearLayout =
        overlay.findViewById(R.id.start_page_suggestions)
    private val pinnedRow1: LinearLayout = overlay.findViewById(R.id.start_page_pinned_row_1)
    private val pinnedRow2: LinearLayout = overlay.findViewById(R.id.start_page_pinned_row_2)
    private val pinnedEdit: View = overlay.findViewById(R.id.start_page_pinned_edit)
    private val continueList: LinearLayout = overlay.findViewById(R.id.start_page_continue_list)
    private val continueEmpty: View = overlay.findViewById(R.id.start_page_continue_empty)
    private val historyLink: View = overlay.findViewById(R.id.start_page_history_link)
    private val trackersCard: View = overlay.findViewById(R.id.start_page_trackers_card)
    private val trackersSummary: TextView = overlay.findViewById(R.id.start_page_trackers_summary)
    private val trackersMeta: TextView = overlay.findViewById(R.id.start_page_trackers_meta)

    init {
        pinnedEdit.setOnClickListener { listener.onStartPageEditPinned() }
        historyLink.setOnClickListener { listener.onStartPageOpenHistory() }
        // Task 6: trackers card is informational, not navigational.
        // Strip the click target and the surrounding ripple so the
        // card reads as a status badge rather than a link.
        trackersCard.isClickable = false
        trackersCard.isFocusable = false
        trackersCard.foreground = null

        wireSearchInput()
    }

    /** Wire the inline home-page search experience. Same flow as the
     *  top address bar's autocomplete (TextWatcher → filter
     *  HistoryRepository → render rows) but the suggestion list lives
     *  directly under the pill instead of in a popup window. */
    private fun wireSearchInput() {
        searchInput.setOnEditorActionListener { _, actionId, event ->
            val pressedEnter = event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                event.action == android.view.KeyEvent.ACTION_DOWN
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO || pressedEnter) {
                val query = searchInput.text?.toString().orEmpty()
                collapseSuggestions()
                // The host hides the keyboard (window-level) and navigates.
                listener.onStartPageQuerySubmitted(query)
                true
            } else {
                false
            }
        }
        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                if (!searchInput.isFocused) return
                refreshSearchSuggestions(s?.toString().orEmpty())
            }
        })
        searchInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) refreshSearchSuggestions(searchInput.text?.toString().orEmpty())
            else hideSearchSuggestions()
        }
        // Dismiss the dropdown on BACK (older devices); the host's
        // IME-inset listener handles focus + navigation.
        searchInput.onBackPreIme = { collapseSuggestions() }
    }

    /** True while the user is actively editing the home-page search. */
    fun isEditing(): Boolean = searchInput.isFocused

    /** Collapse the suggestion list + drop focus. Called by the host when
     *  the keyboard is dismissed so the dropdown never lingers. */
    fun onKeyboardHidden() {
        collapseSuggestions()
        if (searchInput.isFocused) searchInput.clearFocus()
    }

    private fun collapseSuggestions() {
        suggestionsContainer.isVisible = false
        suggestionsContainer.removeAllViews()
    }

    private fun refreshSearchSuggestions(query: String) {
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
            url.text = UrlInputUtils.prettifyUrl(entry.url)
                .removePrefix("https://")
                .removePrefix("http://")
            row.setOnClickListener { listener.onStartPageUrlTapped(entry.url) }
            suggestionsContainer.addView(row)
        }
        suggestionsContainer.isVisible = true
    }

    private fun hideSearchSuggestions() {
        // Defer so a tap inside a suggestion row still fires before
        // the focus-change tears the list down.
        suggestionsContainer.postDelayed({
            if (!searchInput.isFocused) {
                suggestionsContainer.isVisible = false
                suggestionsContainer.removeAllViews()
            }
        }, FOCUS_DISMISS_DELAY_MS)
    }

    /** Toggle visibility of the start-page overlay. Repopulates state
     *  on every show so a tab switch back to about:home picks up any
     *  bookmark / history / tracker count that changed while the
     *  user was on a different surface. */
    fun show() {
        refresh()
        // Clear any previous typed text & dropdown so re-entering the
        // start page from a navigation doesn't surface stale state.
        searchInput.setText("")
        suggestionsContainer.removeAllViews()
        suggestionsContainer.isVisible = false
        overlay.isVisible = true
    }

    fun hide() {
        overlay.isVisible = false
    }

    fun isShowing(): Boolean = overlay.isVisible

    /** Rebuild every section against the current repository + stats
     *  state. Safe to call while hidden — the next show() will see
     *  the freshly bound views. */
    fun refresh() {
        renderHeader()
        renderEngineBadge()
        renderPinned()
        renderContinueReading()
        renderTrackersCard()
    }

    // ---------------------------------------------------------------------
    // Header (date + time-of-day greeting)
    // ---------------------------------------------------------------------

    private fun renderHeader() {
        val format = SimpleDateFormat(
            context.getString(R.string.start_page_date_format),
            Locale.getDefault(),
        )
        dateView.text = format.format(Date())

        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val greetingRes = when (hour) {
            in 5..11 -> R.string.start_page_greeting_morning
            in 12..16 -> R.string.start_page_greeting_afternoon
            in 17..21 -> R.string.start_page_greeting_evening
            else -> R.string.start_page_greeting_night
        }
        greetingTopView.setText(greetingRes)
        // Pick a fresh "where would you like to read today?" prompt
        // from the rotating array. Seed the picker with the calendar
        // day so we don't reshuffle on every back-press refresh — the
        // same day-of-year always lands on the same prompt, but
        // tomorrow is a fresh one.
        greetingBottomView.text = pickGreetingPrompt()
    }

    /** Pick today's bottom greeting line from the rotating array.
     *  Seeded by the day-of-year so the prompt stays stable across a
     *  single calendar day (every back-press to home shows the same
     *  one) but flips to a new prompt at midnight. */
    private fun pickGreetingPrompt(): String {
        val prompts = context.resources.getStringArray(R.array.start_page_greeting_prompts)
        if (prompts.isEmpty()) {
            return context.getString(R.string.start_page_greeting_bottom)
        }
        val cal = Calendar.getInstance()
        val seed = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
        return prompts[(seed.toLong() and 0x7fffffff).rem(prompts.size).toInt()]
    }

    // ---------------------------------------------------------------------
    // Engine badge inside the search pill (e.g. "DDG", "GGL", "BNG")
    // ---------------------------------------------------------------------

    private fun renderEngineBadge() {
        // Short three-letter monogram for the configured engine. Kept
        // local so we don't need to add a method to
        // SearchEngineResolver just for this badge.
        val name = SearchEngineResolver.displayName(prefs).lowercase(Locale.US)
        engineBadge.text = when {
            name.contains("duck") -> "DDG"
            name.contains("google") -> "GGL"
            name.contains("bing") -> "BNG"
            name.contains("brave") -> "BRV"
            name.contains("ecosia") -> "ECO"
            name.contains("startpage") -> "SPG"
            name.contains("qwant") -> "QWT"
            else -> name.take(3).uppercase(Locale.US)
        }
    }

    // ---------------------------------------------------------------------
    // Pinned grid — bookmarks (with fallback defaults)
    // ---------------------------------------------------------------------

    private fun renderPinned() {
        val tiles = collectPinnedTiles()
        pinnedRow1.removeAllViews()
        pinnedRow2.removeAllViews()
        val inflater = LayoutInflater.from(context)

        tiles.forEachIndexed { index, tile ->
            val target = if (index < TILES_PER_ROW) pinnedRow1 else pinnedRow2
            val view = inflater.inflate(R.layout.item_pinned_tile, target, false) as LinearLayout
            // Even spacing between the four tiles in a row — symmetric
            // 4dp gutters give a 4dp gap inside row, 0 padding outside.
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            val side = (context.resources.displayMetrics.density * 4).toInt()
            val isFirstInRow = index % TILES_PER_ROW == 0
            val isLastInRow = index % TILES_PER_ROW == TILES_PER_ROW - 1
            lp.marginStart = if (isFirstInRow) 0 else side
            lp.marginEnd = if (isLastInRow) 0 else side
            view.layoutParams = lp

            val avatar = view.findViewById<TextView>(R.id.pinned_tile_avatar)
            val label = view.findViewById<TextView>(R.id.pinned_tile_label)

            avatar.text = tile.letter
            avatar.backgroundTintList = ColorStateList.valueOf(
                AvatarPalette.colourFor(context, tile.colourKey),
            )
            label.text = tile.label

            view.setOnClickListener { listener.onStartPageUrlTapped(tile.url) }
            target.addView(view)
        }
    }

    /**
     * Pull the Pinned tiles straight from [BookmarkRepository]. The
     * v10 defaults are now seeded into the bookmark store on first
     * install (see [BookmarkRepository.seedDefaultBookmarksIfNeeded]),
     * so we no longer need a separate fallback list — what the user
     * sees here is exactly what's in their bookmark set. Removing a
     * default bookmark from Bookmarks makes the corresponding tile
     * disappear; removing the lot leaves the section empty (the
     * row containers stay laid out with their margins, just no tiles).
     */
    private fun collectPinnedTiles(): List<PinnedTile> {
        return BookmarkRepository.snapshot()
            .take(TILES_TOTAL)
            .map { entry ->
                val host = extractHost(entry.url)
                PinnedTile(
                    label = entry.title.ifBlank { host.ifBlank { entry.url } }.shortLabel(),
                    url = entry.url,
                    letter = avatarLetter(entry.title.ifBlank { host }),
                    colourKey = host.ifBlank { entry.url },
                )
            }
    }

    // ---------------------------------------------------------------------
    // Continue reading — recent history (top 3)
    // ---------------------------------------------------------------------

    private fun renderContinueReading() {
        val entries = HistoryRepository.snapshot().take(CONTINUE_LIMIT)
        continueList.removeAllViews()
        continueEmpty.isVisible = entries.isEmpty()
        continueList.isVisible = entries.isNotEmpty()
        if (entries.isEmpty()) return

        val inflater = LayoutInflater.from(context)
        entries.forEachIndexed { index, entry ->
            val row = inflater.inflate(R.layout.item_continue_reading, continueList, false) as LinearLayout
            val avatar = row.findViewById<TextView>(R.id.continue_row_avatar)
            val title = row.findViewById<TextView>(R.id.continue_row_title)
            val meta = row.findViewById<TextView>(R.id.continue_row_meta)
            val divider = row.findViewById<View>(R.id.continue_row_divider)

            val host = extractHost(entry.url)
            val displayTitle = entry.title.ifBlank { host.ifBlank { entry.url } }
            title.text = displayTitle
            avatar.text = avatarLetter(displayTitle)
            avatar.backgroundTintList = ColorStateList.valueOf(
                AvatarPalette.colourFor(context, host.ifBlank { entry.url }),
            )

            meta.text = buildContinueMeta(host, entry)
            // Hide the divider on the last visible row so the section
            // ends with a clean edge against the trackers card below.
            divider.isVisible = index < entries.size - 1

            row.setOnClickListener { listener.onStartPageUrlTapped(entry.url) }
            continueList.addView(row)
        }
    }

    /** Compose the "host · age · reader ready" meta line, tinting the
     *  "reader ready" portion in accent so it reads as a status tag. */
    private fun buildContinueMeta(host: String, entry: HistoryEntry): CharSequence {
        val safeHost = host.ifBlank { context.getString(R.string.tab_blank_url_placeholder) }
        val age = relativeTimeSpan(entry.visitedAt)
        val readerReady = looksReaderReady(entry.url)
        return if (readerReady) {
            val prefix = context.getString(
                R.string.start_page_continue_meta_reader,
                safeHost,
                age,
            )
            // Pull accent from the current activity's theme so the
            // chip retints automatically when the user picks a
            // different accent in Settings.
            val accent = context.resolveThemeColor(R.attr.browserAccent)
            SpannableStringBuilder().apply {
                append(prefix)
                val start = length
                append(context.getString(R.string.start_page_continue_reader_ready))
                setSpan(
                    ForegroundColorSpan(accent),
                    start,
                    length,
                    Spanned.SPAN_INCLUSIVE_INCLUSIVE,
                )
            }
        } else {
            context.getString(R.string.start_page_continue_meta, safeHost, age)
        }
    }

    /** Heuristic for the "reader ready" badge. We don't run the reader
     *  extractor here (it's WebView-bound and async); instead we look
     *  at the URL shape — an article URL usually has a path with
     *  multiple segments or hyphenated slug-like content, whereas
     *  search / homepage URLs don't. False positives are cheap (the
     *  badge is just a hint), false negatives merely hide it. */
    private fun looksReaderReady(url: String): Boolean {
        val parsed = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val scheme = parsed.scheme?.lowercase(Locale.US)
        if (scheme != "http" && scheme != "https") return false
        val path = parsed.path ?: return false
        if (path.length < 6) return false
        if (parsed.query?.contains("q=") == true) return false
        // Slug-like: at least one path segment with a hyphen or 10+
        // characters. Filters out homepages while still flagging
        // most article-style URLs.
        return path.split('/').any { seg ->
            seg.contains('-') || seg.length >= 10
        }
    }

    // ---------------------------------------------------------------------
    // Trackers blocked today card
    // ---------------------------------------------------------------------

    private fun renderTrackersCard() {
        val count = TrackerStats.today()
        val summary = HtmlCompat.fromHtml(
            context.getString(
                R.string.start_page_trackers_summary,
                String.format(Locale.getDefault(), "%,d", count),
            ),
            HtmlCompat.FROM_HTML_MODE_LEGACY,
        )
        trackersSummary.text = summary

        val version = BrowserBlocker.currentBlocklistVersion
        val lastChecked = prefs.blocklistLastCheckedAt
        trackersMeta.text = if (lastChecked <= 0) {
            context.getString(R.string.start_page_trackers_meta_never, version)
        } else {
            context.getString(
                R.string.start_page_trackers_meta,
                version,
                relativeTimeSpan(lastChecked),
            )
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun extractHost(url: String): String {
        if (url.isBlank()) return ""
        return runCatching {
            Uri.parse(url).host?.removePrefix("www.").orEmpty()
        }.getOrDefault("")
    }

    private fun avatarLetter(source: String): String {
        val first = source.firstOrNull { it.isLetterOrDigit() } ?: '?'
        return first.uppercase()
    }

    private fun String.shortLabel(): String {
        // Trim the bookmark label to a reasonable display width while
        // preserving the leading word — usually the brand.
        val trimmed = trim()
        if (trimmed.length <= 10) return trimmed
        val firstSpace = trimmed.indexOf(' ')
        return if (firstSpace in 1..10) trimmed.substring(0, firstSpace) else trimmed.take(10)
    }

    /** Casual "12 min ago" / "yesterday" formatting that mirrors the
     *  prototype's tone. Falls through to an absolute date once we're
     *  past a week, because "47 days ago" is more confusing than
     *  helpful. */
    private fun relativeTimeSpan(epochMs: Long): String {
        if (epochMs <= 0L) return context.getString(R.string.start_page_continue_age_just_now)
        val now = System.currentTimeMillis()
        val deltaMs = now - epochMs
        val deltaMin = deltaMs / 60_000L
        val deltaHr = deltaMs / 3_600_000L
        val deltaDay = deltaMs / 86_400_000L
        return when {
            deltaMin < 1 -> context.getString(R.string.start_page_continue_age_just_now)
            deltaMin < 60 -> context.getString(
                R.string.start_page_continue_age_minutes,
                deltaMin.toInt(),
            )
            deltaHr < 2 -> context.getString(R.string.start_page_continue_age_hour)
            deltaHr < 24 -> context.getString(
                R.string.start_page_continue_age_hours,
                deltaHr.toInt(),
            )
            deltaDay < 2 -> context.getString(R.string.start_page_continue_age_yesterday)
            deltaDay < 7 -> context.getString(
                R.string.start_page_continue_age_days,
                deltaDay.toInt(),
            )
            else -> SimpleDateFormat(
                context.getString(R.string.start_page_date_format),
                Locale.getDefault(),
            ).format(Date(epochMs))
        }
    }

    /**
     * Hashed avatar palette — shared with [TabSwitcherView] so the
     * same hostname lands on the same colour across both surfaces.
     * Duplicated here as a private object rather than refactored into
     * a top-level util because the two callers are independent and a
     * top-level singleton would have to live somewhere arbitrary.
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

        fun colourFor(context: Context, key: String): Int {
            val needle = key.lowercase(Locale.US).ifBlank { "?" }
            var hash = 0x811c9dc5.toInt()
            for (c in needle) {
                hash = hash xor c.code
                hash = (hash * 0x01000193).toInt()
            }
            val idx = (hash and 0x7fffffff) % palette.size
            return ContextCompat.getColor(context, palette[idx])
        }
    }

    private data class PinnedTile(
        val label: String,
        val url: String,
        val letter: String,
        val colourKey: String,
    )

    companion object {
        private const val TILES_TOTAL = 8
        private const val TILES_PER_ROW = 4
        private const val CONTINUE_LIMIT = 3
        private const val SUGGESTION_LIMIT = 6
        private const val FOCUS_DISMISS_DELAY_MS = 120L

        // The Pinned grid no longer has any fallback defaults — the
        // v10 default tiles are seeded into the bookmark store on
        // first install by BookmarkRepository, so the grid always
        // reflects exactly what's bookmarked. Removing all of them
        // leaves the section empty (matching Task 5's spec).
    }
}
