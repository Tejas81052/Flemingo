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
import android.graphics.Typeface
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar

/**
 * Paints the browser chrome — the top address bar and the bottom navigation
 * card — from the current navigation state: the styled address text, the
 * security/private indicator, the per-button enabled state and tab count, and
 * the search-engine caption.
 *
 * Extracted from MainActivity, which keeps the chrome's *wiring* (click
 * listeners, the dormant scroll/auto-hide plumbing) and calls these render
 * methods whenever a load progresses or a tab changes. Collaborators arrive as
 * the activity, the chrome views, prefs, and a few callbacks — the active tab,
 * the open-tab count, and the "leave insecure page" action — so the painter
 * never reaches back into the activity's tab list or navigation internals.
 */
class BrowserChromeController(
    private val activity: Activity,
    private val addressBar: AddressEditText,
    private val securityIndicator: ImageView,
    private val captionView: TextView,
    private val rootView: android.view.View,
    private val navigationCard: android.view.View,
    private val bookmarkButton: ImageButton,
    private val searchButton: ImageButton,
    private val homeButton: ImageButton,
    private val menuButton: ImageButton,
    private val refreshButton: ImageButton,
    private val tabsButton: FrameLayout,
    private val tabCountText: TextView,
    private val prefs: BrowserPreferences,
    private val activeTab: () -> Tab?,
    private val tabCount: () -> Int,
    private val onLeaveInsecurePage: () -> Unit,
) {

    fun updateAddressBar(url: String?) {
        if (!url.isNullOrBlank() && !addressBar.isFocused) {
            renderAddressBar(url)
        }
        updateSecurityIndicator(url)
    }

    fun renderAddressBar(url: String?) {
        if (addressBar.hasFocus()) return
        val display = url.orEmpty()
            .takeIf { it.isNotBlank() }
            ?.let(UrlInputUtils::prettifyUrl)
            .orEmpty()
            .removePrefix("https://")
            .removePrefix("http://")

        if (display.isBlank()) {
            addressBar.setText("")
            return
        }

        val pathStart = display.indexOfFirst { it == '/' || it == '?' || it == '#' }
            .takeIf { it >= 0 }
            ?: display.length
        val host = display.substring(0, pathStart)
        val path = display.substring(pathStart)
        val span = SpannableString(host + path)
        span.setSpan(StyleSpan(Typeface.BOLD), 0, host.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        if (path.isNotEmpty()) {
            span.setSpan(
                ForegroundColorSpan(ContextCompat.getColor(activity, R.color.browser_faint)),
                host.length,
                span.length,
                Spanned.SPAN_INCLUSIVE_INCLUSIVE,
            )
        }
        addressBar.setText(span, TextView.BufferType.SPANNABLE)
        addressBar.setSelection(span.length)
    }

    fun updateSecurityIndicator(url: String?) {
        val isPrivate = activeTab()?.isPrivate == true
        val isHttps = url?.startsWith("https://", ignoreCase = true) == true
        val isBlank = url.isNullOrBlank()
        val isHttp = url?.startsWith("http://", ignoreCase = true) == true

        when {
            // Private tabs surface the incognito glyph so the user can tell
            // at a glance they're in a private session (paired with the
            // "Private ·" caption and the private start page).
            isPrivate -> {
                securityIndicator.setImageResource(R.drawable.ic_incognito_24)
                securityIndicator.imageTintList =
                    ContextCompat.getColorStateList(activity, R.color.browser_text_2)
                securityIndicator.contentDescription = activity.getString(R.string.private_tab_indicator)
            }
            isHttps || isBlank -> {
                securityIndicator.setImageResource(R.drawable.ic_lock_24)
                securityIndicator.imageTintList =
                    ContextCompat.getColorStateList(activity, R.color.browser_hint)
                securityIndicator.contentDescription = activity.getString(R.string.security_indicator_secure)
            }
            else -> {
                securityIndicator.setImageResource(R.drawable.ic_lock_open_24)
                securityIndicator.imageTintList =
                    ContextCompat.getColorStateList(activity, R.color.browser_danger)
                securityIndicator.contentDescription = activity.getString(R.string.security_indicator_insecure)
            }
        }

        // Insecure-HTTP warning — once per page load per tab, regardless of
        // private state. The action lets the user bail to safety in one tap.
        if (isHttp && !isBlank) {
            val tab = activeTab()
            if (tab != null && !tab.insecurePageWarningShown) {
                tab.insecurePageWarningShown = true
                Snackbar.make(rootView, R.string.insecure_page_warning, Snackbar.LENGTH_LONG)
                    .setAnchorView(navigationCard)
                    .setAction(R.string.insecure_leave_action) { onLeaveInsecurePage() }
                    .show()
            }
        }
    }

    fun updateNavigationButtons() {
        updateButtonState(bookmarkButton, true)
        updateButtonState(searchButton, true)
        updateButtonState(homeButton, true)
        updateButtonState(menuButton, true)
        updateButtonState(refreshButton, activeTab() != null)

        tabsButton.isEnabled = true
        tabsButton.alpha = 1f
        val count = tabCount()
        tabCountText.text = if (count > 9) "9+" else count.toString()
    }

    private fun updateButtonState(button: ImageButton, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.35f
    }

    fun updateSearchEngineUi() {
        val name = SearchEngineResolver.displayName(prefs)
        addressBar.hint = activity.getString(R.string.address_hint_with_engine, name)
        renderBrowserCaption(activeTab()?.isPrivate == true)
    }

    fun renderBrowserCaption(isPrivate: Boolean) {
        val span = SpannableStringBuilder()
        span.append("● ")
        span.setSpan(
            ForegroundColorSpan(activity.resolveThemeColor(R.attr.browserAccent)),
            0,
            1,
            Spanned.SPAN_INCLUSIVE_INCLUSIVE,
        )
        span.append(if (isPrivate) "Private · " else "Search · ")
        span.append(SearchEngineResolver.displayName(prefs))
        span.setSpan(
            ForegroundColorSpan(ContextCompat.getColor(activity, R.color.browser_hint)),
            2,
            span.length,
            Spanned.SPAN_INCLUSIVE_INCLUSIVE,
        )
        captionView.text = span
    }
}
