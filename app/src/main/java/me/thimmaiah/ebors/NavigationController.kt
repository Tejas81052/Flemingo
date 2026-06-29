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
import android.view.View

/**
 * Owns navigation: loading a URL into the active tab ([loadAddress]) and the
 * BACK-press decision tree ([handleBackPressed]) with its swallow / back-to-exit
 * state.
 *
 * Each tab is a fully independent entity — BACK walks only the active tab's own
 * history, then steps it to home, then a confirmed double-press exits; it never
 * cascades into closing other tabs. Extracted from MainActivity, which keeps the
 * `onBackPressedDispatcher` registration (a one-line delegate to
 * [handleBackPressed]) and the toolbar-button wiring. The already-extracted
 * [BrowserChromeController] and [FullscreenVideoController] are passed in
 * directly; every other subsystem the BACK tree consults (address editing, tab
 * switcher, find bar, offline overlay, tabs) arrives as a callback, so this
 * controller holds no back-reference to the activity's internals.
 */
class NavigationController(
    private val activity: Activity,
    private val rootView: View,
    private val addressBar: AddressEditText,
    private val chrome: BrowserChromeController,
    private val fullscreenVideo: FullscreenVideoController,
    private val activeTab: () -> Tab?,
    private val openTab: (String) -> Unit,
    private val showStartPage: () -> Unit,
    private val hideStartPage: () -> Unit,
    private val isOfflineShowing: () -> Boolean,
    private val hideOfflineScreen: () -> Unit,
    private val isAddressEditing: () -> Boolean,
    private val cancelAddressEditing: () -> Unit,
    private val dismissTabSwitcherIfShowing: () -> Boolean,
    private val hideFindBarIfVisible: () -> Boolean,
    private val showToast: (String) -> Unit,
    private val onExit: () -> Unit,
) {

    private var swallowBackNav = false
    private val clearSwallowBackNav = Runnable { swallowBackNav = false }

    private var backToExitArmed = false
    private val clearBackToExit = Runnable { backToExitArmed = false }

    /** Navigate the active tab to [url] (opening a tab if none exists).
     *  about:home shows the start page instead of issuing a web load. */
    fun loadAddress(url: String) {
        hideOfflineScreen()
        val tab = activeTab()
        if (tab == null) {
            openTab(url)
            return
        }

        if (url == MainActivity.ABOUT_HOME_URL) {
            tab.displayUrl = MainActivity.ABOUT_HOME_URL
            showStartPage()
            addressBar.setText("")
            chrome.updateSecurityIndicator(null)
            chrome.updateNavigationButtons()
            return
        }
        hideStartPage()
        chrome.updateAddressBar(url)
        tab.displayUrl = url
        tab.webView.loadUrl(url)
    }

    /** The URL to show for the active tab: its live WebView URL, else its
     *  remembered display URL, else empty. */
    fun currentAddressUrl(): String {
        val tab = activeTab() ?: return ""
        return tab.webView.url ?: tab.displayUrl.takeIf { it.isNotBlank() }.orEmpty()
    }

    /** Arm a brief window that swallows one stray BACK riding along with a
     *  keyboard-hide, so dismissing the IME doesn't also navigate/exit. The
     *  window is short enough that a deliberate second press still navigates. */
    fun armBackSwallow() {
        swallowBackNav = true
        rootView.removeCallbacks(clearSwallowBackNav)
        rootView.postDelayed(clearSwallowBackNav, BACK_NAV_SWALLOW_WINDOW_MS)
    }

    fun handleBackPressed() {
        // Address-bar editing (top bar or either home-page search): cancel it
        // rather than navigating. Catches the case where the dispatcher fires
        // while focus is still on the field.
        if (isAddressEditing()) {
            cancelAddressEditing()
            return
        }
        // The keyboard just hid from an edit and this BACK rode along with it —
        // swallow it so we don't also exit.
        if (swallowBackNav) {
            swallowBackNav = false
            rootView.removeCallbacks(clearSwallowBackNav)
            return
        }
        if (dismissTabSwitcherIfShowing()) return
        if (fullscreenVideo.isInFullscreen) {
            fullscreenVideo.exit()
            return
        }
        if (hideFindBarIfVisible()) return
        // Offline screen up: dismiss it and reveal the last good page (or home)
        // instead of leaving the overlay stranded over the failed page.
        if (isOfflineShowing()) {
            hideOfflineScreen()
            val offlineTab = activeTab()
            if (offlineTab != null && offlineTab.webView.canGoBack()) {
                offlineTab.webView.goBack()
            } else {
                loadAddress(MainActivity.ABOUT_HOME_URL)
            }
            backToExitArmed = false
            chrome.updateNavigationButtons()
            return
        }
        val tab = activeTab()
        if (tab != null && tab.webView.canGoBack()) {
            tab.webView.goBack()
            backToExitArmed = false
            chrome.updateNavigationButtons()
            return
        }
        // No page history left in this tab. Each tab is an independent entity:
        // BACK never cascades into closing other tabs. Step the tab to its home
        // page, then require a confirmed double-press to exit the app.
        if (tab != null && tab.displayUrl != MainActivity.ABOUT_HOME_URL) {
            loadAddress(MainActivity.ABOUT_HOME_URL)
            backToExitArmed = false
            return
        }
        if (backToExitArmed) {
            rootView.removeCallbacks(clearBackToExit)
            onExit()
            return
        }
        backToExitArmed = true
        showToast(activity.getString(R.string.press_back_again_to_exit))
        rootView.removeCallbacks(clearBackToExit)
        rootView.postDelayed(clearBackToExit, BACK_TO_EXIT_WINDOW_MS)
    }

    companion object {
        /** Window after a keyboard-hide during which one BACK is swallowed. */
        private const val BACK_NAV_SWALLOW_WINDOW_MS = 150L

        /** Confirm window for the double-press-to-exit gesture. */
        private const val BACK_TO_EXIT_WINDOW_MS = 2000L
    }
}
