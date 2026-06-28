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
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Owns the tab list and everything that mutates it: opening, switching,
 * closing, memory-pressure discarding, proactive idle sleeping, and
 * save/restore across process death. The active WebView lives in [webHost];
 * this controller swaps it in and out as the active tab changes.
 *
 * Each tab is a fully independent entity — closing or switching never touches
 * another tab's history (see [[tabs-independent-back-exit]]). Extracted from
 * MainActivity, which keeps the chrome/overlay views and reads the active tab
 * back through thin accessors. The post-switch UI sync (address bar, start
 * page, offline overlay, find bar) is funnelled through the single
 * [onActiveTabChanged] callback so this controller never reaches into those
 * subsystems; the already-extracted [BrowserChromeController] is passed in for
 * the tab-count/button refresh. WebView wiring stays in MainActivity via
 * [configureWebView].
 */
class TabController(
    private val activity: Activity,
    private val webHost: FrameLayout,
    private val prefs: BrowserPreferences,
    private val chrome: BrowserChromeController,
    private val configureWebView: (Tab) -> Unit,
    private val onActiveTabChanged: (Tab) -> Unit,
    private val mediaPlayingTab: () -> Tab?,
    private val stopBackgroundMedia: () -> Unit,
    private val refreshTabSwitcher: () -> Unit,
    private val onAllTabsClosed: () -> Unit,
    private val showToast: (String) -> Unit,
) {

    private val _tabs = mutableListOf<Tab>()
    private var activeIndex: Int = -1

    /** Read-only view of the open tabs, in display order. */
    val tabs: List<Tab> get() = _tabs
    val activeTabIndex: Int get() = activeIndex
    val activeTabOrNull: Tab? get() = _tabs.getOrNull(activeIndex)
    val activeTab: Tab get() = _tabs[activeIndex]

    val multiProfileSupported: Boolean by lazy {
        WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
    }

    private val sleepHandler = Handler(Looper.getMainLooper())
    private val sleepChecker = object : Runnable {
        override fun run() {
            if (prefs.tabSleeping) sleepIdleTabs()
            sleepHandler.postDelayed(this, TAB_SLEEP_CHECK_INTERVAL_MS)
        }
    }

    fun openNewTab(
        url: String?,
        switchTo: Boolean = true,
        isPrivate: Boolean = false,
    ): Tab? {
        val newWebView = ScrollAwareWebView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        var privateProfileBound = false
        if (isPrivate && multiProfileSupported) {
            try {
                WebViewCompat.setProfile(newWebView, MainActivity.PRIVATE_PROFILE_NAME)
                privateProfileBound = true
            } catch (e: Exception) {
                Log.w("TabController", "Profile binding failed; private tab was not opened", e)
                showToast(activity.getString(R.string.private_mode_unsupported))
                newWebView.destroy()
                return null
            }
        } else if (isPrivate) {
            showToast(activity.getString(R.string.private_mode_unsupported))
            newWebView.destroy()
            return null
        }
        val tab = Tab(newWebView, isPrivate = privateProfileBound)
        _tabs.add(tab)
        configureWebView(tab)

        if (!url.isNullOrBlank()) {
            tab.displayUrl = url
        }

        val isHome = url == MainActivity.ABOUT_HOME_URL
        if (switchTo) {
            // Queue the first navigation through switchToTab so the WebView is
            // attached to the window and resumed before Chromium starts the
            // document. Loading first and attaching second leaves a short
            // lifecycle race on cold start/new tabs where media can receive a
            // synthetic pause immediately after its first play.
            tab.pendingLoadUrl = url?.takeUnless { it.isBlank() || isHome }
            switchToTab(_tabs.size - 1)
        } else {
            tab.pendingLoadUrl = url?.takeUnless { it.isBlank() || isHome }
            newWebView.onPause()
            refreshTabSwitcher()
        }

        chrome.updateNavigationButtons()
        return tab
    }

    private fun switchToTab(index: Int) {
        val target = _tabs.getOrNull(index) ?: return

        if (index == activeIndex && webHost.indexOfChild(target.webView) >= 0) {
            return
        }
        val previousActive = if (activeIndex in _tabs.indices) _tabs[activeIndex] else null

        previousActive?.let {
            it.lastActiveAt = System.currentTimeMillis()
            it.captureThumbnail()
            webHost.removeView(it.webView)
            it.webView.onPause()
        }

        webHost.addView(target.webView)
        target.webView.onResume()
        activeIndex = index

        target.pendingLoadUrl?.let { deferredUrl ->
            target.pendingLoadUrl = null
            target.webView.loadUrl(deferredUrl)
        }

        if (target.pendingReloadOnActivate && target.pendingLoadUrl == null) {
            target.pendingReloadOnActivate = false
            if (target.webView.url != null) target.webView.reload()
        } else if (target.pendingReloadOnActivate) {
            target.pendingReloadOnActivate = false
        }

        onActiveTabChanged(target)
    }

    fun switchToTabById(id: String) {
        val index = _tabs.indexOfFirst { it.id == id }
        if (index >= 0) switchToTab(index)
    }

    private fun closeTab(index: Int) {
        val tab = _tabs.getOrNull(index) ?: return
        val wasActive = index == activeIndex
        val wasPrivate = tab.isPrivate

        if (tab === mediaPlayingTab()) stopBackgroundMedia()

        if (wasActive) {
            webHost.removeView(tab.webView)
        }
        _tabs.removeAt(index)
        tab.destroy()

        if (wasPrivate && _tabs.none { it.isPrivate }) {
            wipePrivateProfileIfAny()
        }

        if (_tabs.isEmpty()) {
            onAllTabsClosed()
            return
        }

        if (wasActive) {
            val newIndex = index.coerceAtMost(_tabs.size - 1)
            activeIndex = -1
            switchToTab(newIndex)
        } else if (index < activeIndex) {
            activeIndex--
        }

        refreshTabSwitcher()
        chrome.updateNavigationButtons()
    }

    fun closeTabById(id: String) {
        val index = _tabs.indexOfFirst { it.id == id }
        if (index >= 0) closeTab(index)
    }

    fun buildTabSnapshots(): List<TabSwitcherView.TabSnapshot> {
        return _tabs.map { tab ->
            val displayTitle = tab.displayTitle.ifBlank { tab.webView.title.orEmpty() }
            val displayUrl = tab.displayUrl.ifBlank { tab.webView.url.orEmpty() }
                .ifBlank { tab.pendingLoadUrl.orEmpty() }
            TabSwitcherView.TabSnapshot(
                id = tab.id,
                title = displayTitle,
                url = displayUrl,
                isPrivate = tab.isPrivate,
                thumbnail = tab.thumbnail,
            )
        }
    }

    fun wipePrivateProfileIfAny() {
        if (!multiProfileSupported) return
        try {
            val store = ProfileStore.getInstance()
            if (MainActivity.PRIVATE_PROFILE_NAME in store.allProfileNames) {
                store.deleteProfile(MainActivity.PRIVATE_PROFILE_NAME)
            }
        } catch (e: Exception) {
            Log.w("TabController", "Failed to delete private profile", e)
        }
    }

    // --- Save / restore -----------------------------------------------------

    fun saveState(outState: Bundle) {
        val urls = ArrayList<String>(_tabs.size)
        var restoredActiveIndex = 0
        for ((i, tab) in _tabs.withIndex()) {
            if (tab.isPrivate) continue
            if (i == activeIndex) restoredActiveIndex = urls.size
            urls.add(tab.webView.url ?: tab.displayUrl)
        }
        outState.putStringArrayList(STATE_TAB_URLS, urls)
        outState.putInt(STATE_ACTIVE_TAB_INDEX, restoredActiveIndex)

        val activeTabState = Bundle()
        val active = activeTabOrNull
        if (active != null && !active.isPrivate) {
            active.webView.saveState(activeTabState)
        }
        outState.putBundle(STATE_ACTIVE_TAB_WEBVIEW, activeTabState)
    }

    fun restoreTabsFrom(state: Bundle?): Boolean {
        val urls = state?.getStringArrayList(STATE_TAB_URLS) ?: return false
        if (urls.isEmpty()) return false
        val savedActiveIndex = state.getInt(STATE_ACTIVE_TAB_INDEX, 0)
            .coerceIn(0, urls.size - 1)
        val activeStateBundle = state.getBundle(STATE_ACTIVE_TAB_WEBVIEW)

        for ((i, url) in urls.withIndex()) {
            val tab = openNewTab(url = null, switchTo = false) ?: return false
            tab.displayUrl = url
            if (i == savedActiveIndex && activeStateBundle != null) {
                tab.webView.restoreState(activeStateBundle)
                tab.pendingLoadUrl = null
            } else if (url.isNotBlank()) {
                tab.pendingLoadUrl = url
            }
        }
        switchToTab(savedActiveIndex)
        return true
    }

    // --- Memory: discard + proactive sleep ----------------------------------

    /**
     * Navigate every non-active, non-media tab to about:blank so its heavy page
     * releases renderer memory, queuing a reload on next activation via
     * [Tab.pendingLoadUrl]. The foreground tab and any tab playing media are
     * always kept intact.
     */
    fun discardBackgroundTabs() {
        val active = activeTabOrNull
        for (tab in _tabs) if (isDiscardable(tab, active)) discardTabPage(tab)
    }

    private fun sleepIdleTabs() {
        val active = activeTabOrNull
        val now = System.currentTimeMillis()
        val threshold = sleepThresholdMs()
        for (tab in _tabs) {
            if (isDiscardable(tab, active) && now - tab.lastActiveAt >= threshold) {
                discardTabPage(tab)
            }
        }
    }

    /**
     * Adaptive idle window before a background tab is slept. The more tabs are
     * open, the sooner idle ones are dropped — many live renderers is the main
     * driver of memory/battery pressure.
     */
    private fun sleepThresholdMs(): Long = when {
        _tabs.size >= 8 -> TAB_SLEEP_AFTER_MS / 3
        _tabs.size >= 4 -> TAB_SLEEP_AFTER_MS * 2 / 3
        else -> TAB_SLEEP_AFTER_MS
    }

    private fun isDiscardable(tab: Tab, active: Tab?): Boolean {
        if (tab === active || tab === mediaPlayingTab()) return false
        if (tab.pendingLoadUrl != null) return false
        val url = tab.displayUrl
        return url.isNotBlank() && url != MainActivity.ABOUT_HOME_URL && url != MainActivity.ABOUT_BLANK_URL
    }

    private fun discardTabPage(tab: Tab) {
        Log.d("TabController", "Sleeping tab (${_tabs.size} open): ${tab.displayUrl}")
        tab.pendingLoadUrl = tab.displayUrl
        tab.webView.loadUrl(MainActivity.ABOUT_BLANK_URL)
    }

    fun startSleepTimer() {
        sleepHandler.removeCallbacks(sleepChecker)
        sleepHandler.postDelayed(sleepChecker, TAB_SLEEP_CHECK_INTERVAL_MS)
    }

    fun stopSleepTimer() {
        sleepHandler.removeCallbacks(sleepChecker)
    }

    // --- Activity lifecycle hooks ------------------------------------------

    /** Pause every WebView and its JS timers (call when not keeping audio alive). */
    fun pauseWebViews() {
        if (_tabs.isNotEmpty()) _tabs[0].webView.pauseTimers()
        for (tab in _tabs) tab.webView.onPause()
    }

    fun resumeWebViews() {
        if (_tabs.isNotEmpty()) _tabs[0].webView.resumeTimers()
        activeTabOrNull?.webView?.onResume()
    }

    /** Tear down every tab (final activity destroy), wiping the private profile
     *  if any private tab was open. */
    fun destroyAll() {
        sleepHandler.removeCallbacksAndMessages(null)
        val hadPrivateTabs = _tabs.any { it.isPrivate }
        activeTabOrNull?.let { webHost.removeView(it.webView) }
        for (tab in _tabs.reversed()) {
            tab.destroy()
        }
        _tabs.clear()
        activeIndex = -1
        if (hadPrivateTabs) {
            wipePrivateProfileIfAny()
        }
    }

    companion object {
        private const val STATE_TAB_URLS = "state_tab_urls"
        private const val STATE_ACTIVE_TAB_INDEX = "state_active_tab_index"
        private const val STATE_ACTIVE_TAB_WEBVIEW = "state_active_tab_webview"

        private const val TAB_SLEEP_AFTER_MS = 3L * 60L * 1000L // 3 minutes
        private const val TAB_SLEEP_CHECK_INTERVAL_MS = 60L * 1000L
    }
}
