/*
 * Copyright 2025 Tejas Thimmaiah
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

import android.Manifest
import android.Manifest.permission.POST_NOTIFICATIONS
import android.annotation.SuppressLint
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Message
import android.provider.Settings
import android.text.Editable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.ContextMenu
import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.webkit.ProfileStore
import androidx.webkit.ScriptHandler
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity() {
    private lateinit var rootView: View
    private lateinit var addressBar: EditText
    private lateinit var addressSuggestionAdapter: AddressSuggestionAdapter
    private var addressSuggestionPopup: ListPopupWindow? = null
    private lateinit var captionView: TextView
    private lateinit var securityIndicator: ImageView
    private lateinit var webContainer: androidx.swiperefreshlayout.widget.SwipeRefreshLayout

    private lateinit var webHost: FrameLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var goButton: ImageButton
    private lateinit var menuButton: ImageButton

    private lateinit var bookmarkButton: ImageButton
    private lateinit var tabsButton: FrameLayout
    private lateinit var tabCountText: TextView

    private lateinit var tabsButtonActiveDot: View
    private lateinit var searchButton: ImageButton
    private lateinit var homeButton: ImageButton
    private lateinit var refreshButton: ImageButton
    private lateinit var navigationCard: View
    private lateinit var topChrome: AppBarLayout
    private lateinit var bottomChrome: View

    private var chromeHidden = false
    private var scrollDirAccumPx = 0
    private var chromeAnimGateUntilMs = 0L

    private val inactivityHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val inactivityHider = Runnable {

        if (!chromeHidden && shouldAutoHide()) {
            setChromeHidden(true)
        }
    }

    @Volatile
    private var refreshSuppressedByJs: Boolean = false

    private var shortsLayoutActive: Boolean = false

    private lateinit var findBar: View
    private lateinit var findInput: EditText
    private lateinit var findCount: TextView
    private lateinit var findPrev: ImageButton
    private lateinit var findNext: ImageButton
    private lateinit var findClose: ImageButton

    private val findDebounceHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var findDebounceRunnable: Runnable? = null

    private val tabs = mutableListOf<Tab>()
    private var activeTabIndex: Int = -1
    private var tabSwitcherView: TabSwitcherView? = null

    private var startPageView: StartPageView? = null

    private var privateStartPageView: PrivateStartPageView? = null

    private var permissionInFlightTabId: String? = null
    private var geolocationInFlightTabId: String? = null

    private var inFlightPermissionDialog: android.app.Dialog? = null

    private var skipWebViewPauseForPermission = false

    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var fullscreenSavedOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    private lateinit var prefs: BrowserPreferences
    private var lastDesktopMode: Boolean = false
    private var lastForceDark: Boolean = false
    private var lastBlockWebRtc: Boolean = false
    private var lastTrimReferrer: Boolean = false

    private val activeTabOrNull: Tab? get() = tabs.getOrNull(activeTabIndex)

    private val multiProfileSupported: Boolean by lazy {
        WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
    }

    private val activeTab: Tab get() = tabs[activeTabIndex]

    private val openLibraryLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val url = result.data?.getStringExtra(BookmarksActivity.EXTRA_URL)
                if (!url.isNullOrBlank()) {
                    loadAddress(url)
                }
            }
        }

    private val openSettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == SettingsActivity.RESULT_CLEAR_BROWSING_DATA) {
                clearBrowsingData()
            }
            applyPreferences()
        }

    private val defaultBrowserRoleLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (isDefaultBrowser()) {
                showToast(getString(R.string.default_browser_success))
            } else {
                showToast(getString(R.string.default_browser_not_changed))
            }
        }

    private val websitePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->

            val tabId = permissionInFlightTabId
            permissionInFlightTabId = null
            val tab = tabs.firstOrNull { it.id == tabId } ?: return@registerForActivityResult
            val pending = tab.pendingWebsitePermission ?: return@registerForActivityResult
            tab.pendingWebsitePermission = null

            val grantableResources = pending.resources.filter { resource ->
                requiredAndroidPermissions(resource).all { permission ->
                    grants[permission] == true || hasPermission(permission)
                }
            }

            if (grantableResources.isNotEmpty()) {
                pending.request.grant(grantableResources.toTypedArray())
            } else {
                pending.request.deny()

                val deniedPermissions = pending.resources
                    .flatMap(::requiredAndroidPermissions)
                    .distinct()
                    .filterNot(::hasPermission)
                offerAppSettingsIfPermanentlyDenied(tab, deniedPermissions)
            }
        }

    private val geolocationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val tabId = geolocationInFlightTabId
            geolocationInFlightTabId = null
            val tab = tabs.firstOrNull { it.id == tabId } ?: return@registerForActivityResult
            val pending = tab.pendingGeolocation ?: return@registerForActivityResult
            tab.pendingGeolocation = null

            val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
                hasAnyLocationPermission()
            pending.callback.invoke(pending.origin, granted, false)
            if (!granted) {
                val deniedPermissions = listOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ).filterNot(::hasPermission)
                offerAppSettingsIfPermanentlyDenied(tab, deniedPermissions)
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                showToast(getString(R.string.download_notification_permission_denied))
            }
        }

    private var pendingFileChooserCallback: android.webkit.ValueCallback<Array<android.net.Uri>>? = null

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = pendingFileChooserCallback ?: return@registerForActivityResult
            pendingFileChooserCallback = null
            val uris: Array<android.net.Uri>? = if (result.resultCode == RESULT_OK) {
                val data = result.data
                val clip = data?.clipData
                when {
                    clip != null && clip.itemCount > 0 -> Array(clip.itemCount) { clip.getItemAt(it).uri }
                    data?.data != null -> arrayOf(data.data!!)
                    else -> null
                }
            } else {
                null
            }
            callback.onReceiveValue(uris)
        }

    private var boundAccentKey: String = AccentTheme.DEFAULT.key

    override fun onCreate(savedInstanceState: Bundle?) {

        val prefs = BrowserPreferences.from(this)

        if (!prefs.onboardingCompleted) {
            super.onCreate(savedInstanceState)
            startActivity(
                Intent(this, WelcomeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
            finish()
            return
        }

        prefs.migrateDefaults()

        prefs.bootstrapDefaults()

        applyAccentTheme(prefs)
        boundAccentKey = prefs.accentKey

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        this.prefs = prefs

        lastDesktopMode = prefs.desktopMode
        lastForceDark = prefs.forceDark
        lastBlockWebRtc = prefs.blockWebRtc
        lastTrimReferrer = prefs.trimReferrer

        DownloadRepository.initialize(applicationContext)
        BookmarkRepository.initialize(applicationContext)
        HistoryRepository.initialize(applicationContext)
        TrackerStats.initialize(applicationContext)
        BrowserBlocker.adBlockEnabled = prefs.adBlockEnabled
        BrowserBlocker.siteBlockEnabled = prefs.siteBlockEnabled

        BrowserBlocker.initialize(applicationContext)

        if (prefs.blocklistAutoUpdate) {
            BlocklistUpdater.checkForUpdate(applicationContext, force = false)
        }
        BlockedSitesRepository.initialize(applicationContext)
        SitePermissionStore.initialize(applicationContext)

        if (prefs.needsSitePermissionReset) {
            SitePermissionStore.clearAll()
            prefs.markSitePermissionsReset()
        }

        wipePrivateProfileIfAny()

        bindViews()
        applyInsets()
        configureNavigation()
        configureFindBar()
        configureSwipeRefresh()
        configureServiceWorkerBlocker()
        configureBackHandling()
        updateSearchEngineUi()

        val restored = restoreTabsFrom(savedInstanceState)
        if (!restored) {
            val intentUrl = extractIntentUrl(intent)
            openNewTab(url = intentUrl ?: homeUrl(), switchTo = true)
        }
        applyPreferences()

        maybeShowDefaultBrowserPrompt()
        maybeRequestNotificationPermission()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val url = extractIntentUrl(intent) ?: return

        openNewTab(url = url, switchTo = true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        val urls = ArrayList<String>(tabs.size)
        var restoredActiveIndex = 0
        for ((i, tab) in tabs.withIndex()) {
            if (tab.isPrivate) continue
            if (i == activeTabIndex) restoredActiveIndex = urls.size
            urls.add(tab.webView.url ?: tab.displayUrl)
        }
        outState.putStringArrayList(STATE_TAB_URLS, urls)
        outState.putInt(STATE_ACTIVE_TAB_INDEX, restoredActiveIndex)

        val activeTabState = Bundle()
        val activeTab = activeTabOrNull
        if (activeTab != null && !activeTab.isPrivate) {
            activeTab.webView.saveState(activeTabState)
        }
        outState.putBundle(STATE_ACTIVE_TAB_WEBVIEW, activeTabState)
    }

    private fun restoreTabsFrom(state: Bundle?): Boolean {
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

    override fun onPause() {

        if (!skipWebViewPauseForPermission) {

            if (tabs.isNotEmpty()) tabs[0].webView.pauseTimers()
            for (tab in tabs) tab.webView.onPause()
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()

        skipWebViewPauseForPermission = false

        if (prefs.accentKey != boundAccentKey) {
            recreate()
            return
        }
        if (tabs.isNotEmpty()) tabs[0].webView.resumeTimers()

        activeTabOrNull?.webView?.onResume()
        applyPreferences()

        if (startPageView?.isShowing() == true) startPageView?.refresh()
    }

    override fun onDestroy() {
        exitFullscreen()
        permissionInFlightTabId = null
        geolocationInFlightTabId = null
        inFlightPermissionDialog = null

        findDebounceHandler.removeCallbacksAndMessages(null)
        findDebounceRunnable = null

        inactivityHandler.removeCallbacksAndMessages(null)

        speechActive = false
        destroySpeechRecognizer()
        val hadPrivateTabs = tabs.any { it.isPrivate }

        val activeTab = activeTabOrNull
        if (activeTab != null) {
            webHost.removeView(activeTab.webView)
        }

        for (tab in tabs.reversed()) {
            tab.destroy()
        }
        tabs.clear()
        activeTabIndex = -1
        tabSwitcherView?.dismiss()
        tabSwitcherView = null

        if (hadPrivateTabs) {
            wipePrivateProfileIfAny()
        }
        super.onDestroy()
    }

    private fun openNewTab(
        url: String?,
        switchTo: Boolean = true,
        isPrivate: Boolean = false,
    ): Tab? {
        val newWebView = ScrollAwareWebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        var privateProfileBound = false
        if (isPrivate && multiProfileSupported) {
            try {
                WebViewCompat.setProfile(newWebView, PRIVATE_PROFILE_NAME)
                privateProfileBound = true
            } catch (e: Exception) {
                Log.w(
                    "MainActivity",
                    "Profile binding failed; private tab was not opened",
                    e,
                )
                showToast(getString(R.string.private_mode_unsupported))
                newWebView.destroy()
                return null
            }
        } else if (isPrivate) {
            showToast(getString(R.string.private_mode_unsupported))
            newWebView.destroy()
            return null
        }
        val tab = Tab(newWebView, isPrivate = privateProfileBound)
        tabs.add(tab)
        configureWebViewForTab(tab)

        if (!url.isNullOrBlank()) {
            tab.displayUrl = url
        }

        val isHome = url == ABOUT_HOME_URL
        if (switchTo) {
            if (!url.isNullOrBlank() && !isHome) {
                newWebView.loadUrl(url)
            }
            switchToTab(tabs.size - 1)
        } else {
            tab.pendingLoadUrl = url?.takeUnless { it.isBlank() || isHome }
            newWebView.onPause()
            if (tabSwitcherView?.isShowing() == true) {
                tabSwitcherView?.refresh(buildTabSnapshots(), activeTabIndex)
            }
        }

        updateNavigationButtons()
        return tab
    }

    private fun switchToTab(index: Int) {
        val target = tabs.getOrNull(index) ?: return

        if (index == activeTabIndex && webHost.indexOfChild(target.webView) >= 0) {
            return
        }
        val previousActive = if (activeTabIndex in tabs.indices) tabs[activeTabIndex] else null

        previousActive?.let {

            it.captureThumbnail()
            webHost.removeView(it.webView)
            it.webView.onPause()
        }

        webHost.addView(target.webView)
        target.webView.onResume()
        activeTabIndex = index

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

        val targetIsHome = target.displayUrl == ABOUT_HOME_URL
        if (targetIsHome) {
            showStartPage()
        } else {
            hideStartPage()
        }

        if (targetIsHome) {
            addressBar.setText("")
            updateSecurityIndicator(null)
        } else {
            updateAddressBar(target.webView.url ?: target.displayUrl.takeIf { it.isNotBlank() })
        }
        renderBrowserCaption(SearchEngineResolver.displayName(prefs), target.isPrivate)
        updateNavigationButtons()

        hideFindBar()
        scrollDirAccumPx = 0
        setChromeHidden(false)

        scheduleAutoHide()

        refreshSuppressedByJs = false

        val newUrl = target.webView.url.orEmpty()
        applyShortsLayout(isYouTubeHost(newUrl) && newUrl.contains("/shorts/"))
    }

    private fun closeTab(index: Int) {
        val tab = tabs.getOrNull(index) ?: return
        val wasActive = index == activeTabIndex
        val wasPrivate = tab.isPrivate

        if (wasActive) {
            webHost.removeView(tab.webView)
        }
        tabs.removeAt(index)
        tab.destroy()

        if (wasPrivate && tabs.none { it.isPrivate }) {
            wipePrivateProfileIfAny()
        }

        if (tabs.isEmpty()) {

            finish()
            return
        }

        if (wasActive) {
            val newIndex = index.coerceAtMost(tabs.size - 1)

            activeTabIndex = -1
            switchToTab(newIndex)
        } else if (index < activeTabIndex) {
            activeTabIndex--
        }

        if (tabSwitcherView?.isShowing() == true) {
            tabSwitcherView?.refresh(buildTabSnapshots(), activeTabIndex)
        }

        updateNavigationButtons()
    }

    private fun wipePrivateProfileIfAny() {
        if (!multiProfileSupported) return
        try {
            val store = ProfileStore.getInstance()

            if (PRIVATE_PROFILE_NAME in store.allProfileNames) {
                store.deleteProfile(PRIVATE_PROFILE_NAME)
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to delete private profile", e)
        }
    }

    private fun showTabSwitcher() {
        val view = tabSwitcherView ?: return
        if (view.isShowing()) {
            view.dismiss()
            return
        }

        activeTabOrNull?.captureThumbnail()

        bottomChrome.post {
            view.setBottomInset(bottomChrome.height)
        }

        topChrome.isVisible = false

        setTabSwitcherActiveIndicator(active = true)

        cancelAutoHide()
        view.show(buildTabSnapshots(), activeTabIndex)
    }

    private fun setTabSwitcherActiveIndicator(active: Boolean) {
        if (active) {
            tabCountText.setBackgroundResource(R.drawable.bg_tab_badge_active)
            tabCountText.setTextColor(ContextCompat.getColor(this, R.color.browser_on_accent))
            tabsButtonActiveDot.isVisible = true
        } else {
            tabCountText.setBackgroundResource(R.drawable.bg_tab_badge)
            tabCountText.setTextColor(ContextCompat.getColor(this, R.color.browser_text))
            tabsButtonActiveDot.isVisible = false
        }
    }

    private val startPageListener = object : StartPageView.Listener {
        override fun onStartPageUrlTapped(url: String) {
            loadAddress(url)
        }

        override fun onStartPageQuerySubmitted(query: String) {

            val text = query.trim()
            if (text.isEmpty()) return
            hideKeyboard(addressBar)
            loadAddress(resolveUserInput(text))
        }

        override fun onStartPageEditPinned() {
            openLibraryLauncher.launch(Intent(this@MainActivity, BookmarksActivity::class.java))
        }

        override fun onStartPageOpenHistory() {
            openLibraryLauncher.launch(Intent(this@MainActivity, BookmarksActivity::class.java))
        }
    }

    private val privateStartPageListener = object : PrivateStartPageView.StartPageListener {
        override fun onStartPageUrlTapped(url: String) {
            loadAddress(url)
        }

        override fun onStartPageQuerySubmitted(query: String) {
            val text = query.trim()
            if (text.isEmpty()) return
            hideKeyboard(addressBar)
            loadAddress(resolveUserInput(text))
        }
    }

    private val tabSwitcherListener = object : TabSwitcherView.Listener {
        override fun onSwitchToTab(id: String) = switchToTabById(id)
        override fun onCloseTab(id: String) = closeTabById(id)
        override fun onNewTab(isPrivate: Boolean) {
            openNewTab(url = homeUrl(), switchTo = true, isPrivate = isPrivate)
        }
        override fun onSwitcherClosed() {

            topChrome.isVisible = true
            setTabSwitcherActiveIndicator(active = false)

            scheduleAutoHide()
        }
    }

    private fun switchToTabById(id: String) {
        val index = tabs.indexOfFirst { it.id == id }
        if (index >= 0) switchToTab(index)
    }

    private fun closeTabById(id: String) {
        val index = tabs.indexOfFirst { it.id == id }
        if (index >= 0) closeTab(index)
    }

    private fun buildTabSnapshots(): List<TabSwitcherView.TabSnapshot> {
        return tabs.map { tab ->

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

    private fun bindViews() {
        rootView = findViewById(R.id.browser_root)
        addressBar = findViewById(R.id.address_bar)
        captionView = findViewById(R.id.browser_caption)
        securityIndicator = findViewById(R.id.security_indicator)
        webContainer = findViewById(R.id.web_container)
        webHost = findViewById(R.id.web_host)
        progressBar = findViewById(R.id.loading_progress)
        goButton = findViewById(R.id.go_button)
        menuButton = findViewById(R.id.menu_button)
        bookmarkButton = findViewById(R.id.bookmark_button)
        tabsButton = findViewById(R.id.tabs_button)
        tabCountText = findViewById(R.id.tab_count_text)
        tabsButtonActiveDot = findViewById(R.id.tabs_button_active_dot)
        searchButton = findViewById(R.id.search_button)
        homeButton = findViewById(R.id.home_button)
        refreshButton = findViewById(R.id.refresh_button)
        navigationCard = findViewById(R.id.navigation_card)
        topChrome = findViewById(R.id.top_chrome)
        bottomChrome = findViewById(R.id.bottom_chrome)

        findBar = findViewById(R.id.find_bar)
        findInput = findViewById(R.id.find_input)
        findCount = findViewById(R.id.find_count)
        findPrev = findViewById(R.id.find_prev)
        findNext = findViewById(R.id.find_next)
        findClose = findViewById(R.id.find_close)

        tabSwitcherView = TabSwitcherView(
            context = this,
            overlay = findViewById(R.id.tab_switcher_overlay),
            listener = tabSwitcherListener,
        )

        startPageView = StartPageView(
            context = this,
            overlay = findViewById(R.id.start_page_overlay),
            listener = startPageListener,
            prefs = prefs,
        )

        privateStartPageView = PrivateStartPageView(
            context = this,
            overlay = findViewById(R.id.private_start_page_overlay),
            listener = privateStartPageListener,
            prefs = prefs,
        )
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        ViewCompat.requestApplyInsets(rootView)
    }

    private fun configureNavigation() {
        configureAddressSuggestions()

        goButton.setOnClickListener {
            submitAddressBarInput()
        }

        menuButton.setOnClickListener {
            tabSwitcherView?.dismiss()
            showBrowserMenu()
        }

        addressBar.setOnEditorActionListener { _, actionId, event ->
            val pressedEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN

            if (actionId == EditorInfo.IME_ACTION_GO || pressedEnter) {
                submitAddressBarInput()
                true
            } else {
                false
            }
        }

        addressBar.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (addressBar.hasFocus()) {
                    refreshAddressSuggestions(s?.toString().orEmpty())
                }
            }
        })

        addressBar.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {

                cancelAutoHide()
                addressBar.setText(currentAddressUrl())
                addressBar.post {
                    addressBar.selectAll()
                    refreshAddressSuggestions("")
                }
            } else {
                addressBar.postDelayed({
                    if (!addressBar.hasFocus()) {
                        dismissAddressSuggestions()
                    }
                }, ADDRESS_SUGGESTION_DISMISS_DELAY_MS)
                renderAddressBar(currentAddressUrl())

                scheduleAutoHide()
            }
        }

        bookmarkButton.setOnClickListener {
            tabSwitcherView?.dismiss()
            openLibraryLauncher.launch(Intent(this, BookmarksActivity::class.java))
        }

        tabsButton.setOnClickListener {
            showTabSwitcher()
        }

        searchButton.setOnClickListener {
            tabSwitcherView?.dismiss()
            addressBar.requestFocus()
            addressBar.selectAll()
            getSystemService<InputMethodManager>()
                ?.showSoftInput(addressBar, InputMethodManager.SHOW_IMPLICIT)
        }

        homeButton.setOnClickListener {
            tabSwitcherView?.dismiss()
            loadAddress(homeUrl())
        }

        refreshButton.setOnClickListener {
            activeTabOrNull?.webView?.reload()
        }

        updateNavigationButtons()
    }

    private fun configureAddressSuggestions() {
        addressSuggestionAdapter = AddressSuggestionAdapter(this)
        addressSuggestionPopup = ListPopupWindow(this).apply {
            setAdapter(addressSuggestionAdapter)
            setBackgroundDrawable(ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_address_suggestions))
            setOnItemClickListener { _, _, position, _ ->
                val entry = addressSuggestionAdapter.getItem(position) ?: return@setOnItemClickListener
                dismissAddressSuggestions()
                addressBar.clearFocus()
                hideKeyboard(addressBar)
                loadAddress(entry.url)
            }
        }
    }

    private fun submitAddressBarInput() {
        val resolved = resolveUserInput(addressBar.text?.toString().orEmpty())
        dismissAddressSuggestions()
        addressBar.clearFocus()
        hideKeyboard(addressBar)
        loadAddress(resolved)
    }

    private fun refreshAddressSuggestions(query: String) {
        val suggestions = buildAddressSuggestions(query)
        addressSuggestionAdapter.submit(suggestions)
        if (suggestions.isEmpty()) {
            dismissAddressSuggestions()
        } else {
            showAddressSuggestions()
        }
    }

    private fun showAddressSuggestions() {
        val popup = addressSuggestionPopup ?: return
        if (!addressBar.hasFocus() || addressBar.windowToken == null) return

        val anchor = addressSuggestionAnchor()
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

    private fun dismissAddressSuggestions() {
        addressSuggestionPopup?.dismiss()
    }

    private fun buildAddressSuggestions(query: String): List<HistoryEntry> {
        val needle = query.trim().lowercase()
        val seen = HashSet<String>()
        return HistoryRepository.snapshot().asSequence()
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
            .take(ADDRESS_SUGGESTION_LIMIT)
            .toList()
    }

    private fun addressSuggestionAnchor(): View {
        return (addressBar.parent as? View) ?: addressBar
    }

    private fun currentAddressUrl(): String {
        val tab = activeTabOrNull ?: return ""
        return tab.webView.url ?: tab.displayUrl.takeIf { it.isNotBlank() }.orEmpty()
    }

    private fun hideKeyboard(view: View) {
        getSystemService<InputMethodManager>()
            ?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun configureFindBar() {
        findInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val tab = activeTabOrNull ?: return
                val query = s?.toString().orEmpty()

                findDebounceRunnable?.let(findDebounceHandler::removeCallbacks)
                if (query.isEmpty()) {
                    tab.webView.clearMatches()
                    findCount.text = ""
                    return
                }

                findCount.text = ""
                val runnable = Runnable {
                    if (findBar.isVisible) {
                        activeTabOrNull?.webView?.findAllAsync(query)
                    }
                }
                findDebounceRunnable = runnable
                findDebounceHandler.postDelayed(runnable, FIND_DEBOUNCE_MS)
            }
        })
        findInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                activeTabOrNull?.webView?.findNext(true)
                true
            } else {
                false
            }
        }

        findPrev.setOnClickListener { activeTabOrNull?.webView?.findNext(false) }
        findNext.setOnClickListener { activeTabOrNull?.webView?.findNext(true) }
        findClose.setOnClickListener { hideFindBar() }
    }

    private fun showFindBar() {
        findBar.isVisible = true
        findInput.requestFocus()
        findInput.setText("")
        findCount.text = ""
        getSystemService<InputMethodManager>()
            ?.showSoftInput(findInput, InputMethodManager.SHOW_IMPLICIT)

        cancelAutoHide()
    }

    private fun hideFindBar() {
        if (!findBar.isVisible) return
        findBar.isVisible = false
        activeTabOrNull?.webView?.clearMatches()
        findCount.text = ""
        getSystemService<InputMethodManager>()
            ?.hideSoftInputFromWindow(findInput.windowToken, 0)

        scheduleAutoHide()
    }

    private fun configureSwipeRefresh() {
        webContainer.setColorSchemeColors(resolveThemeColor(R.attr.browserAccent))
        webContainer.setOnRefreshListener {
            val tab = activeTabOrNull
            if (tab == null) {
                webContainer.isRefreshing = false
                return@setOnRefreshListener
            }
            tab.webView.reload()
        }

        webContainer.setOnChildScrollUpCallback { _, _ ->
            val webView = activeTabOrNull?.webView

            if (webView == null) return@setOnChildScrollUpCallback false

            webView.scrollY > 0 || refreshSuppressedByJs
        }
    }

    private inner class RefreshGuardBridge {
        @JavascriptInterface
        fun setSuppressed(suppressed: Boolean) {
            refreshSuppressedByJs = suppressed
        }
    }

    private inner class ShortsLayoutBridge(private val tab: Tab) {
        @JavascriptInterface
        fun setShortsLayoutActive(active: Boolean) {

            runOnUiThread {
                if (tab === activeTabOrNull) {
                    applyShortsLayout(active)
                }
            }
        }
    }

    private var speechRecognizer: android.speech.SpeechRecognizer? = null
    private var speechActive = false
    private var speechContinuous = false
    private var speechInterim = false
    private var speechLang = ""

    private var pendingSpeechStart: Triple<String, Boolean, Boolean>? = null

    private val speechAudioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val pending = pendingSpeechStart
            pendingSpeechStart = null
            if (granted && pending != null) {
                beginSpeechRecognition(pending.first, pending.second, pending.third)
            } else {

                emitSpeechError("not-allowed")
                emitSpeechEnd()
            }
        }

    private inner class SpeechRecognitionBridge(private val tab: Tab) {

        @JavascriptInterface
        fun isAvailable(): Boolean = runCatching {
            android.speech.SpeechRecognizer.isRecognitionAvailable(this@MainActivity)
        }.getOrDefault(false)

        @JavascriptInterface
        fun start(lang: String?, continuous: Boolean, interim: Boolean) {
            runOnUiThread {

                if (tab !== activeTabOrNull) {
                    emitSpeechError("aborted")
                    emitSpeechEnd()
                    return@runOnUiThread
                }
                if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
                    beginSpeechRecognition(lang.orEmpty(), continuous, interim)
                } else {
                    pendingSpeechStart = Triple(lang.orEmpty(), continuous, interim)

                    skipWebViewPauseForPermission = true
                    speechAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }

        @JavascriptInterface
        fun stop() {
            runOnUiThread { runCatching { speechRecognizer?.stopListening() } }
        }

        @JavascriptInterface
        fun abort() {
            runOnUiThread {
                speechActive = false
                destroySpeechRecognizer()
                emitSpeechEnd()
            }
        }
    }

    private fun beginSpeechRecognition(lang: String, continuous: Boolean, interim: Boolean) {
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {

            emitSpeechError("service-not-allowed")
            emitSpeechEnd()
            return
        }
        destroySpeechRecognizer()
        speechLang = lang
        speechContinuous = continuous
        speechInterim = interim
        speechActive = true

        val recognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer = recognizer
        recognizer.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = emitSpeechStart()
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                if (!speechInterim) return
                val text = partialResults
                    ?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isNotBlank()) emitSpeechResult(text, isFinal = false)
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isNotBlank()) emitSpeechResult(text, isFinal = true)
                if (speechContinuous && speechActive) {

                    restartSpeechListening()
                } else {
                    speechActive = false
                    destroySpeechRecognizer()
                    emitSpeechEnd()
                }
            }

            override fun onError(error: Int) {
                val code = mapSpeechError(error)

                val recoverable = speechContinuous && speechActive &&
                    (error == android.speech.SpeechRecognizer.ERROR_NO_MATCH ||
                        error == android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                if (recoverable) {
                    restartSpeechListening()
                    return
                }
                emitSpeechError(code)
                speechActive = false
                destroySpeechRecognizer()
                emitSpeechEnd()
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        runCatching { recognizer.startListening(buildSpeechIntent()) }
            .onFailure {
                emitSpeechError("audio-capture")
                speechActive = false
                destroySpeechRecognizer()
                emitSpeechEnd()
            }
    }

    private fun restartSpeechListening() {
        val recognizer = speechRecognizer ?: return
        runCatching {
            recognizer.cancel()
            recognizer.startListening(buildSpeechIntent())
        }.onFailure {
            speechActive = false
            destroySpeechRecognizer()
            emitSpeechEnd()
        }
    }

    private fun buildSpeechIntent(): Intent =
        Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            if (speechLang.isNotBlank()) {
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, speechLang)
            }
            putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, speechInterim)

            putExtra(android.speech.RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }

    private fun destroySpeechRecognizer() {
        val recognizer = speechRecognizer ?: return
        speechRecognizer = null
        runCatching { recognizer.destroy() }
    }

    private fun mapSpeechError(error: Int): String = when (error) {
        android.speech.SpeechRecognizer.ERROR_AUDIO -> "audio-capture"
        android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "not-allowed"
        android.speech.SpeechRecognizer.ERROR_NETWORK,
        android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network"
        android.speech.SpeechRecognizer.ERROR_NO_MATCH -> "no-speech"
        android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no-speech"
        android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "aborted"
        android.speech.SpeechRecognizer.ERROR_CLIENT -> "aborted"
        else -> "service-not-allowed"
    }

    private fun dispatchSpeechJs(js: String) {
        activeTabOrNull?.webView?.evaluateJavascript(
            "if(window.__eborsSpeech){try{window.__eborsSpeech.$js}catch(e){}}",
            null,
        )
    }

    private fun emitSpeechStart() = dispatchSpeechJs("_fire('start');")
    private fun emitSpeechEnd() = dispatchSpeechJs("_end();")

    private fun emitSpeechError(code: String) =
        dispatchSpeechJs("_error(${org.json.JSONObject.quote(code)});")

    private fun emitSpeechResult(transcript: String, isFinal: Boolean) =
        dispatchSpeechJs("_result(${org.json.JSONObject.quote(transcript)}, $isFinal);")

    private fun isYouTubeHost(url: String): Boolean {
        return try {
            val host = Uri.parse(url).host?.lowercase() ?: return false
            host == "youtube.com" || host == "m.youtube.com" ||
                host.endsWith(".youtube.com")
        } catch (e: Exception) {
            false
        }
    }

    private fun configureServiceWorkerBlocker() {
        val controller = ServiceWorkerController.getInstance()
        with(controller.serviceWorkerWebSettings) {
            allowContentAccess = false
            allowFileAccess = false
            blockNetworkLoads = false
        }
        controller.setServiceWorkerClient(object : ServiceWorkerClient() {
            override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                return BrowserBlocker.createBlockingResponse(
                    url = request.url.toString(),
                    isMainFrame = request.isForMainFrame,
                )
            }
        })
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebViewForTab(tab: Tab) {
        val target = tab.webView
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(target, prefs.thirdPartyCookies)

        target.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->

            if (tab !== activeTabOrNull) return@setDownloadListener
            handleDownloadRequest(
                tab = tab,
                url = url,
                userAgent = userAgent,
                contentDisposition = contentDisposition,
                mimeType = mimeType,
                contentLength = contentLength,
            )
        }

        target.onScrollChangedListener = { scrollY, dy ->
            if (tab === activeTabOrNull) {
                onWebScroll(scrollY, dy)
            }
        }

        target.addJavascriptInterface(RefreshGuardBridge(), REFRESH_GUARD_BRIDGE_NAME)

        target.addJavascriptInterface(
            ShortsLayoutBridge(tab),
            SHORTS_LAYOUT_BRIDGE_NAME,
        )

        target.addJavascriptInterface(
            SpeechRecognitionBridge(tab),
            SPEECH_BRIDGE_NAME,
        )

        target.setOnCreateContextMenuListener { menu, _, _ ->
            val result = target.hitTestResult
            val url = result.extra
            when (result.type) {
                WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                    if (!url.isNullOrBlank()) addLinkContextMenuItems(menu, url)
                }

                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {

                    if (!url.isNullOrBlank()) {
                        addLinkContextMenuItems(menu, url)
                        addSaveImageItem(menu, tab)
                    }
                }

                WebView.HitTestResult.IMAGE_TYPE -> {
                    if (!url.isNullOrBlank()) {
                        addImageContextMenuItems(menu, url)
                        addSaveImageItem(menu, tab)
                    }
                }

                else -> Unit
            }
        }

        with(target.settings) {
            javaScriptEnabled = prefs.javaScriptEnabled
            domStorageEnabled = true
            setSupportMultipleWindows(!prefs.blockPopups)
            javaScriptCanOpenWindowsAutomatically = !prefs.blockPopups
            allowFileAccess = false
            allowContentAccess = false

            mixedContentMode = if (prefs.allowMixedContent) {
                WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            } else {
                WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            safeBrowsingEnabled = true
            loadsImagesAutomatically = true

            mediaPlaybackRequiresUserGesture = false
            displayZoomControls = false
            builtInZoomControls = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setGeolocationEnabled(true)
            userAgentString = effectiveUserAgent()

            if (tab.isPrivate) {
                @Suppress("DEPRECATION")
                saveFormData = false
            }
        }

        target.importantForAutofill = if (tab.isPrivate) {
            View.IMPORTANT_FOR_AUTOFILL_NO
        } else {
            View.IMPORTANT_FOR_AUTOFILL_YES_EXCLUDE_DESCENDANTS
        }

        applyForceDark(target)
        syncDocumentStartScripts(tab)

        target.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (tab !== activeTabOrNull) return
                progressBar.progress = newProgress
                progressBar.isVisible = newProgress in 0..99
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                tab.displayTitle = title.orEmpty()
                if (tab === activeTabOrNull && tabSwitcherView?.isShowing() == true) {
                    tabSwitcherView?.refresh(buildTabSnapshots(), activeTabIndex)
                }
            }

            override fun onConsoleMessage(message: android.webkit.ConsoleMessage?): Boolean {
                message?.let {
                    android.util.Log.i(
                        "EborsWeb",
                        "[${it.messageLevel()}] ${it.message()} @${it.sourceId()}:${it.lineNumber()}",
                    )
                }
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                runOnUiThread { handleWebsitePermissionRequest(tab, request) }
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest?) {

                if (tab.pendingWebsitePermission?.request == request) {
                    tab.pendingWebsitePermission = null
                    if (permissionInFlightTabId == tab.id) permissionInFlightTabId = null
                } else if (permissionInFlightTabId == tab.id && tab.pendingWebsitePermission == null) {

                    permissionInFlightTabId = null
                    inFlightPermissionDialog?.dismiss()
                    inFlightPermissionDialog = null
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?,
            ) {
                runOnUiThread { handleGeolocationPermissionRequest(tab, origin, callback) }
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (tab !== activeTabOrNull) {

                    callback?.onCustomViewHidden()
                    return
                }
                enterFullscreen(view, callback)
            }

            override fun onHideCustomView() {
                exitFullscreen()
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>?,
                fileChooserParams: WebChromeClient.FileChooserParams?,
            ): Boolean {
                if (filePathCallback == null) return false

                if (tab !== activeTabOrNull) {
                    filePathCallback.onReceiveValue(null)
                    return true
                }

                pendingFileChooserCallback?.onReceiveValue(null)
                pendingFileChooserCallback = filePathCallback

                val pageIntent = fileChooserParams?.createIntent()
                val intent: Intent = if (pageIntent != null) {
                    pageIntent
                } else {
                    val fallback = Intent(Intent.ACTION_GET_CONTENT)
                    fallback.addCategory(Intent.CATEGORY_OPENABLE)
                    fallback.type = "*/*"
                    fallback
                }

                if (fileChooserParams?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                return try {
                    fileChooserLauncher.launch(
                        Intent.createChooser(intent, getString(R.string.file_chooser_title)),
                    )
                    true
                } catch (_: ActivityNotFoundException) {
                    pendingFileChooserCallback = null
                    filePathCallback.onReceiveValue(null)
                    showToast(getString(R.string.file_chooser_unavailable))
                    false
                }
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?,
            ): Boolean {
                if (resultMsg == null) return false
                if (prefs.blockPopups) return false

                if (!isUserGesture) return false

                val newTab = openNewTab(url = null, switchTo = true) ?: return false
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                transport.webView = newTab.webView
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView?) {
                if (window == null) return
                val idx = tabs.indexOfFirst { it.webView === window }
                if (idx >= 0) closeTab(idx)
            }
        }

        target.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val targetUrl = request?.url?.toString().orEmpty()
                if (targetUrl.isBlank()) return false
                if (targetUrl.startsWith(BrowserBlocker.ACTION_URL_PREFIX, ignoreCase = true)) {
                    return handleBlockPageAction(targetUrl)
                }
                if (!BrowserBlocker.isSupportedBrowserScheme(targetUrl)) {
                    return handleExternalScheme(targetUrl)
                }

                if (prefs.alwaysHttps && UrlInputUtils.shouldUpgradeToHttps(targetUrl)) {
                    view?.loadUrl(UrlInputUtils.upgradeToHttps(targetUrl))
                    return true
                }
                return false
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? {

                return BrowserBlocker.createBlockingResponse(
                    url = request?.url?.toString(),
                    isMainFrame = request?.isForMainFrame == true,
                )
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)

                if (tab.displayUrl == ABOUT_HOME_URL &&
                    !url.isNullOrBlank() &&
                    url != ABOUT_HOME_URL &&
                    tab === activeTabOrNull
                ) {
                    hideStartPage()
                }
                url?.let { tab.displayUrl = it }
                tab.insecurePageWarningShown = false

                refreshSuppressedByJs = false
                if (tab === activeTabOrNull) {
                    updateAddressBar(url)
                    updateNavigationButtons()
                    scrollDirAccumPx = 0
                    setChromeHidden(false)

                    val isShortsUrl = url != null &&
                        isYouTubeHost(url) &&
                        url.contains("/shorts/")
                    applyShortsLayout(isShortsUrl)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectPageScripts(view, url)
                url?.let { tab.displayUrl = it }
                view?.title?.let { tab.displayTitle = it }
                if (prefs.historyEnabled && !tab.isPrivate && !url.isNullOrBlank()) {

                    HistoryRepository.record(url, view?.title.orEmpty())
                }
                if (tab === activeTabOrNull) {
                    progressBar.isVisible = false

                    webContainer.isRefreshing = false
                    updateAddressBar(url)
                    updateNavigationButtons()
                }
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?,
            ) {
                if (handler == null) return

                if (tab !== activeTabOrNull) {
                    handler.cancel()
                    return
                }
                runOnUiThread { showSslErrorDialog(error, handler) }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                super.onReceivedError(view, request, error)
                if (tab === activeTabOrNull && request?.isForMainFrame == true) {
                    progressBar.isVisible = false
                    webContainer.isRefreshing = false
                    updateNavigationButtons()
                }
            }
        }

        target.setFindListener { activeMatch, numberOfMatches, _ ->
            if (tab !== activeTabOrNull) return@setFindListener
            findCount.text = if (numberOfMatches == 0) {
                ""
            } else {
                getString(R.string.find_count_format, activeMatch + 1, numberOfMatches)
            }
        }
    }

    private fun applyPreferences() {

        BrowserBlocker.adBlockEnabled = prefs.adBlockEnabled
        BrowserBlocker.siteBlockEnabled = prefs.siteBlockEnabled

        updateSearchEngineUi()

        for (tab in tabs) {
            applyToWebView(tab)
        }

        val shouldReloadTabs =
            prefs.desktopMode != lastDesktopMode ||
                prefs.forceDark != lastForceDark ||
                prefs.blockWebRtc != lastBlockWebRtc ||
                prefs.trimReferrer != lastTrimReferrer
        if (shouldReloadTabs) {
            lastDesktopMode = prefs.desktopMode
            lastForceDark = prefs.forceDark
            lastBlockWebRtc = prefs.blockWebRtc
            lastTrimReferrer = prefs.trimReferrer

            val activeTab = activeTabOrNull
            for (tab in tabs) {
                if (tab === activeTab) {
                    if (tab.webView.url != null) tab.webView.reload()
                } else {
                    tab.pendingReloadOnActivate = true
                }
            }
        }
    }

    private fun applyToWebView(tab: Tab) {
        val target = tab.webView
        CookieManager.getInstance().setAcceptThirdPartyCookies(target, prefs.thirdPartyCookies)
        with(target.settings) {
            javaScriptEnabled = prefs.javaScriptEnabled
            setSupportMultipleWindows(!prefs.blockPopups)
            javaScriptCanOpenWindowsAutomatically = !prefs.blockPopups
            userAgentString = effectiveUserAgent()

            mixedContentMode = if (prefs.allowMixedContent) {
                WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            } else {
                WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
        }
        applyForceDark(target)
        syncDocumentStartScripts(tab)
    }

    private fun syncDocumentStartScripts(tab: Tab) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            tab.removeDocumentStartScripts()
            return
        }
        if (tab.youTubeDocumentStartScript == null) {
            tab.youTubeDocumentStartScript = addDocumentStartScript(
                tab.webView,
                BrowserBlocker.youTubeResponsePruningScript(),
                setOf(
                    "https://youtube.com",
                    "https://*.youtube.com",
                ),
            )
        }

        if (tab.speechDocumentStartScript == null) {
            tab.speechDocumentStartScript = addDocumentStartScript(
                tab.webView,
                SPEECH_POLYFILL_SCRIPT,
                setOf("https://*", "http://*"),
            )
        }

        val desiredPrivacyFlags = privacyDocumentStartFlags()
        if (desiredPrivacyFlags == 0) {
            removeDocumentStartScript(tab.privacyDocumentStartScript)
            tab.privacyDocumentStartScript = null
            tab.privacyDocumentStartFlags = desiredPrivacyFlags
            return
        }
        if (
            tab.privacyDocumentStartScript != null &&
            tab.privacyDocumentStartFlags == desiredPrivacyFlags
        ) {
            return
        }
        removeDocumentStartScript(tab.privacyDocumentStartScript)
        tab.privacyDocumentStartScript = addDocumentStartScript(
            tab.webView,
            BrowserBlocker.privacyDocumentStartScript(
                trimReferrer = prefs.trimReferrer,
                blockWebRtc = prefs.blockWebRtc,
            ),
            setOf("https://*", "http://*"),
        )
        tab.privacyDocumentStartFlags = if (tab.privacyDocumentStartScript == null) {
            Tab.DOCUMENT_START_FLAGS_UNSET
        } else {
            desiredPrivacyFlags
        }
    }

    private fun privacyDocumentStartFlags(): Int =
        (if (prefs.trimReferrer) PRIVACY_SCRIPT_TRIM_REFERRER else 0) or
            (if (prefs.blockWebRtc) PRIVACY_SCRIPT_BLOCK_WEBRTC else 0)

    private fun addDocumentStartScript(
        target: WebView,
        script: String,
        allowedOriginRules: Set<String>,
    ): ScriptHandler? {
        return try {
            WebViewCompat.addDocumentStartJavaScript(target, script, allowedOriginRules)
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: UnsupportedOperationException) {
            null
        }
    }

    private fun removeDocumentStartScript(handler: ScriptHandler?) {
        try {
            handler?.remove()
        } catch (_: RuntimeException) {

        }
    }

    @Suppress("DEPRECATION")
    private fun applyForceDark(target: WebView) {

        if (prefs.forceDark) {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                WebSettingsCompat.setForceDark(target.settings, WebSettingsCompat.FORCE_DARK_ON)
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(target.settings, true)
            }
        } else {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                WebSettingsCompat.setForceDark(target.settings, WebSettingsCompat.FORCE_DARK_OFF)
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(target.settings, false)
            }
        }
    }

    private fun effectiveUserAgent(): String =
        if (prefs.desktopMode) toDesktopUa(baseUserAgent) else baseUserAgent

    private val baseUserAgent: String by lazy {
        stripWebViewMarker(WebSettings.getDefaultUserAgent(this))
    }

    private fun stripWebViewMarker(ua: String): String {

        return ua
            .replace("; wv)", ")")
            .replace("; wv;", ";")
    }

    private fun toDesktopUa(mobileUa: String): String {

        return mobileUa
            .replace(ANDROID_PLATFORM_REGEX, "(X11; Linux x86_64)")
            .replace(" Mobile", "")
    }

    private fun enterFullscreen(view: View?, callback: WebChromeClient.CustomViewCallback?) {
        if (view == null) {
            callback?.onCustomViewHidden()
            return
        }
        if (fullscreenView != null) {
            callback?.onCustomViewHidden()
            return
        }
        fullscreenView = view
        fullscreenCallback = callback
        fullscreenSavedOrientation = requestedOrientation

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val decor = window.decorView as ViewGroup
        decor.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        WindowCompat.getInsetsController(window, view).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun exitFullscreen() {
        val view = fullscreenView ?: return

        val callback = fullscreenCallback
        val savedOrientation = fullscreenSavedOrientation
        fullscreenView = null
        fullscreenCallback = null
        fullscreenSavedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        try {
            val decor = window.decorView as ViewGroup
            decor.removeView(view)
        } catch (_: Exception) {

        }
        try {
            WindowCompat.getInsetsController(window, window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        } catch (_: Exception) {

        }
        callback?.onCustomViewHidden()
        requestedOrientation = savedOrientation
    }

    private fun isInFullscreen(): Boolean = fullscreenView != null

    private fun onWebScroll(scrollY: Int, dy: Int) {

        scheduleAutoHide()

        if (android.os.SystemClock.uptimeMillis() < chromeAnimGateUntilMs) return

        val density = resources.displayMetrics.density
        val topZonePx = (24 * density).toInt()
        val hideTriggerPx = (64 * density).toInt()
        val showTriggerPx = (32 * density).toInt()

        if (scrollY <= topZonePx) {
            scrollDirAccumPx = 0
            if (chromeHidden) setChromeHidden(false)
            return
        }

        if ((dy > 0 && scrollDirAccumPx < 0) || (dy < 0 && scrollDirAccumPx > 0)) {
            scrollDirAccumPx = 0
        }
        scrollDirAccumPx += dy

        when {
            !chromeHidden && scrollDirAccumPx >= hideTriggerPx -> setChromeHidden(true)
            chromeHidden && scrollDirAccumPx <= -showTriggerPx -> setChromeHidden(false)
        }
    }

    private fun setChromeHidden(hidden: Boolean) {

    }

    private fun shouldAutoHide(): Boolean {
        if (!::addressBar.isInitialized) return false
        if (addressBar.hasFocus()) return false
        if (::findBar.isInitialized && findBar.isVisible) return false
        if (tabSwitcherView?.isShowing() == true) return false
        if (isInFullscreen()) return false
        if (activeTabOrNull == null) return false
        return true
    }

    private fun scheduleAutoHide() {

        inactivityHandler.removeCallbacks(inactivityHider)
    }

    private fun cancelAutoHide() {
        inactivityHandler.removeCallbacks(inactivityHider)
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        if (ev?.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            scheduleAutoHide()
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun applyShortsLayout(@Suppress("UNUSED_PARAMETER") active: Boolean) {

        shortsLayoutActive = false
    }

    private fun injectPageScripts(view: WebView?, url: String?) {
        if (view == null) return

        view.evaluateJavascript(REFRESH_GUARD_SCRIPT, null)

        if (url != null && isYouTubeHost(url)) {
            view.evaluateJavascript(YOUTUBE_SHORTS_LAYOUT_SCRIPT, null)
        }
        if (prefs.adBlockEnabled) {

            view.evaluateJavascript(
                BrowserBlocker.cosmeticHidingScript(aggressive = prefs.aggressiveAntiAdblock),
                null,
            )
        }
        if (prefs.desktopMode) {

            view.evaluateJavascript(DESKTOP_VIEWPORT_SCRIPT, null)
        }
    }

    private fun configureBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {

                if (tabSwitcherView?.isShowing() == true) {
                    tabSwitcherView?.dismiss()
                    return
                }
                if (isInFullscreen()) {
                    exitFullscreen()
                    return
                }
                if (findBar.isVisible) {
                    hideFindBar()
                    return
                }
                val tab = activeTabOrNull
                if (tab != null && tab.webView.canGoBack()) {
                    tab.webView.goBack()
                    updateNavigationButtons()
                    return
                }
                if (tabs.size > 1) {

                    closeTab(activeTabIndex)
                    return
                }
                finish()
            }
        })
    }

    private fun handleDownloadRequest(
        tab: Tab,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long,
    ) {
        if (url.startsWith("blob:", ignoreCase = true)) {
            showToast(getString(R.string.blob_download_not_supported))
            return
        }

        val blockingMatch = BrowserBlocker.findMatch(url)
        if (blockingMatch != null) {
            showToast(getString(R.string.download_blocked))
            return
        }

        val fileName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
        val sizeLabel = if (contentLength > 0L) {
            getString(
                R.string.download_prompt_message_with_size,
                fileName,
                DownloadRepository.formatBytes(contentLength),
            )
        } else {
            getString(R.string.download_prompt_message, fileName)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.download_prompt_title)
            .setMessage(sizeLabel)
            .setPositiveButton(R.string.allow_once) { _, _ ->

                val item = DownloadRepository.enqueueDownload(
                    url = url,
                    contentDisposition = contentDisposition,
                    mimeType = mimeType,
                    contentLengthHint = contentLength,
                    userAgent = userAgent,
                    referer = tab.webView.url,
                    profileName = if (tab.isPrivate) PRIVATE_PROFILE_NAME else null,
                )
                DownloadService.start(this)
                Snackbar.make(rootView, getString(R.string.download_started, item.fileName), Snackbar.LENGTH_LONG)
                    .setAnchorView(navigationCard)
                    .setAction(R.string.view_downloads) {
                        openDownloadsScreen()
                    }
                    .show()
            }
            .setNegativeButton(R.string.deny) { _, _ ->
                showToast(getString(R.string.download_denied))
            }
            .show()
    }

    private fun handleWebsitePermissionRequest(tab: Tab, request: PermissionRequest) {
        if (permissionInFlightTabId != null) {
            request.deny()
            if (tab === activeTabOrNull) {
                showToast(getString(R.string.permission_busy))
            }
            return
        }

        val supportedResources = request.resources.filter { resource ->
            resource == PermissionRequest.RESOURCE_AUDIO_CAPTURE ||
                resource == PermissionRequest.RESOURCE_VIDEO_CAPTURE
        }

        if (supportedResources.isEmpty()) {
            request.deny()
            if (tab === activeTabOrNull) {
                showToast(getString(R.string.website_permission_unsupported))
            }
            return
        }

        val rawOrigin = request.origin?.toString()
        val kinds = supportedResources.mapNotNull(::resourceToKind).distinct()

        if (!tab.isPrivate && kinds.isNotEmpty()) {
            val decisions = kinds.map { SitePermissionStore.decisionFor(rawOrigin, it) }
            if (decisions.all { it == SitePermissionStore.Decision.ALLOW }) {
                grantWebsitePermission(tab, request, supportedResources)
                return
            }
            if (decisions.any { it == SitePermissionStore.Decision.BLOCK }) {
                request.deny()
                return
            }
        }

        val origin = extractOriginLabel(rawOrigin)
        val requestedAccess = supportedResources.joinToString(separator = getString(R.string.permission_joiner)) {
            when (it) {
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> getString(R.string.microphone_permission_label)
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> getString(R.string.camera_permission_label)
                else -> it
            }
        }

        permissionInFlightTabId = tab.id

        inFlightPermissionDialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.website_permission_title, origin))
            .setMessage(getString(R.string.website_permission_message, origin, requestedAccess))
            .setPositiveButton(R.string.allow_always) { _, _ ->
                inFlightPermissionDialog = null
                if (!tab.isPrivate) {
                    kinds.forEach { SitePermissionStore.remember(rawOrigin, it, allow = true) }
                }
                grantWebsitePermission(tab, request, supportedResources)
            }
            .setNeutralButton(R.string.allow_once) { _, _ ->
                inFlightPermissionDialog = null
                grantWebsitePermission(tab, request, supportedResources)
            }
            .setNegativeButton(R.string.deny) { _, _ ->
                inFlightPermissionDialog = null
                request.deny()
                permissionInFlightTabId = null
            }
            .setOnCancelListener {
                inFlightPermissionDialog = null
                request.deny()
                permissionInFlightTabId = null
            }
            .show()
    }

    private fun grantWebsitePermission(
        tab: Tab,
        request: PermissionRequest,
        resources: List<String>,
    ) {
        val missingPermissions = resources
            .flatMap(::requiredAndroidPermissions)
            .distinct()
            .filterNot(::hasPermission)

        if (missingPermissions.isEmpty()) {
            request.grant(resources.toTypedArray())
            permissionInFlightTabId = null
        } else {

            permissionInFlightTabId = tab.id
            tab.pendingWebsitePermission = Tab.PendingWebsitePermission(
                request = request,
                resources = resources,
            )

            skipWebViewPauseForPermission = true
            websitePermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun resourceToKind(resource: String): SitePermissionStore.Kind? = when (resource) {
        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> SitePermissionStore.Kind.MICROPHONE
        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> SitePermissionStore.Kind.CAMERA
        else -> null
    }

    private fun offerAppSettingsIfPermanentlyDenied(tab: Tab, deniedPermissions: List<String>) {
        if (tab !== activeTabOrNull || deniedPermissions.isEmpty()) return
        val permanentlyDenied = deniedPermissions.any { !shouldShowRequestPermissionRationale(it) }
        if (!permanentlyDenied) {
            showToast(getString(R.string.website_permission_denied))
            return
        }
        val label = deniedPermissions.joinToString(getString(R.string.permission_joiner)) {
            when (it) {
                Manifest.permission.CAMERA -> getString(R.string.camera_permission_label)
                Manifest.permission.RECORD_AUDIO -> getString(R.string.microphone_permission_label)
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION ->
                    getString(R.string.location_permission_kind)
                else -> it
            }
        }.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.permission_blocked_in_android_title)
            .setMessage(getString(R.string.permission_blocked_in_android_message, label))
            .setPositiveButton(R.string.open_settings) { _, _ -> openAppDetailsSettings() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openAppDetailsSettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null),
                ),
            )
        } catch (_: ActivityNotFoundException) {

        }
    }

    private fun handleGeolocationPermissionRequest(
        tab: Tab,
        origin: String?,
        callback: GeolocationPermissions.Callback?,
    ) {
        if (origin.isNullOrBlank() || callback == null) {
            callback?.invoke(origin, false, false)
            return
        }
        if (geolocationInFlightTabId != null) {
            callback.invoke(origin, false, false)
            if (tab === activeTabOrNull) {
                showToast(getString(R.string.permission_busy))
            }
            return
        }

        if (!tab.isPrivate) {
            when (SitePermissionStore.decisionFor(origin, SitePermissionStore.Kind.LOCATION)) {
                SitePermissionStore.Decision.ALLOW -> {
                    grantGeolocation(tab, origin, callback)
                    return
                }
                SitePermissionStore.Decision.BLOCK -> {
                    callback.invoke(origin, false, false)
                    return
                }
                SitePermissionStore.Decision.ASK -> Unit
            }
        }

        geolocationInFlightTabId = tab.id

        val originLabel = extractOriginLabel(origin)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.location_permission_title, originLabel))
            .setMessage(getString(R.string.location_permission_message, originLabel))
            .setPositiveButton(R.string.allow_always) { _, _ ->
                if (!tab.isPrivate) {
                    SitePermissionStore.remember(origin, SitePermissionStore.Kind.LOCATION, allow = true)
                }
                grantGeolocation(tab, origin, callback)
            }
            .setNeutralButton(R.string.allow_once) { _, _ ->
                grantGeolocation(tab, origin, callback)
            }
            .setNegativeButton(R.string.deny) { _, _ ->

                callback.invoke(origin, false, false)
                geolocationInFlightTabId = null
                showToast(getString(R.string.location_denied))
            }
            .setOnCancelListener {
                callback.invoke(origin, false, false)
                geolocationInFlightTabId = null
            }
            .show()
    }

    private fun grantGeolocation(
        tab: Tab,
        origin: String,
        callback: GeolocationPermissions.Callback,
    ) {
        if (hasAnyLocationPermission()) {

            callback.invoke(origin, true, false)
            geolocationInFlightTabId = null
            if (!isLocationServiceEnabled()) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.location_services_off_title)
                    .setMessage(R.string.location_services_off_message)
                    .setPositiveButton(R.string.open_settings) { _, _ ->
                        try {
                            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                        } catch (_: ActivityNotFoundException) {

                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        } else {
            geolocationInFlightTabId = tab.id
            tab.pendingGeolocation = Tab.PendingGeolocation(origin, callback)
            geolocationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }

    private fun isLocationServiceEnabled(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
            ?: return true
        return try {
            androidx.core.location.LocationManagerCompat.isLocationEnabled(lm)
        } catch (_: Exception) {
            true
        }
    }

    private fun showSslErrorDialog(error: SslError?, handler: SslErrorHandler) {
        val originLabel = extractOriginLabel(error?.url)
        val reason = sslErrorReason(error)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ssl_error_title)
            .setMessage(getString(R.string.ssl_error_message, originLabel, reason))
            .setCancelable(false)
            .setNegativeButton(R.string.ssl_error_cancel) { _, _ -> handler.cancel() }
            .setPositiveButton(R.string.ssl_error_proceed) { _, _ -> handler.proceed() }
            .setOnCancelListener { handler.cancel() }
            .show()
    }

    private fun sslErrorReason(error: SslError?): String {
        if (error == null) return getString(R.string.ssl_error_reason_other)
        return when (error.primaryError) {
            SslError.SSL_DATE_INVALID -> getString(R.string.ssl_error_reason_invalid_date)
            SslError.SSL_EXPIRED -> getString(R.string.ssl_error_reason_expired)
            SslError.SSL_IDMISMATCH -> getString(R.string.ssl_error_reason_hostname_mismatch)
            SslError.SSL_NOTYETVALID -> getString(R.string.ssl_error_reason_not_yet_valid)
            SslError.SSL_UNTRUSTED -> getString(R.string.ssl_error_reason_untrusted)
            else -> getString(R.string.ssl_error_reason_other)
        }
    }

    private fun handleBlockPageAction(actionUrl: String): Boolean {
        val token = actionUrl.substring(BrowserBlocker.ACTION_URL_PREFIX.length)
            .substringBefore('?')
            .substringBefore('#')
        return when (BrowserBlocker.Action.fromToken(token)) {
            BrowserBlocker.Action.OPEN_BLOCKLIST -> {
                startActivity(Intent(this, BlockedSitesActivity::class.java))
                true
            }
            BrowserBlocker.Action.OPEN_SETTINGS -> {
                openSettingsLauncher.launch(Intent(this, SettingsActivity::class.java))
                true
            }
            null -> true
        }
    }

    private fun handleExternalScheme(targetUrl: String): Boolean {
        val intent = try {
            if (targetUrl.startsWith("intent:", ignoreCase = true)) {
                Intent.parseUri(targetUrl, Intent.URI_INTENT_SCHEME)
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl))
            }
        } catch (_: Exception) {
            showToast(getString(R.string.unsupported_link_message))
            return true
        }

        val fallbackUrl = intent.getStringExtra("browser_fallback_url")
            ?.takeIf {
                it.startsWith("http://", ignoreCase = true) ||
                    it.startsWith("https://", ignoreCase = true)
            }

        if (!sanitizeExternalIntent(intent)) {

            if (fallbackUrl != null) {
                loadAddress(fallbackUrl)
                return true
            }
            showToast(getString(R.string.unsupported_link_message))
            return true
        }

        return try {
            startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {

            if (fallbackUrl != null) loadAddress(fallbackUrl) else {
                showToast(getString(R.string.unsupported_link_message))
            }
            true
        } catch (_: SecurityException) {
            if (fallbackUrl != null) loadAddress(fallbackUrl) else {
                showToast(getString(R.string.unsupported_link_message))
            }
            true
        }
    }

    private fun sanitizeExternalIntent(intent: Intent): Boolean {
        intent.flags = 0
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        intent.setPackage(null)
        intent.selector = null
        intent.component = null

        intent.categories?.toList()?.forEach { intent.removeCategory(it) }
        intent.removeExtra(Intent.EXTRA_INTENT)

        val action = intent.action ?: return false
        return action in ALLOWED_EXTERNAL_INTENT_ACTIONS
    }

    private fun extractIntentUrl(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_VIEW) {
            return null
        }

        val rawUrl = intent.dataString ?: return null

        val lower = rawUrl.lowercase(java.util.Locale.US)
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return null
        }

        return UrlInputUtils.enforceSecureScheme(rawUrl)
    }

    private fun loadAddress(url: String) {
        val tab = activeTabOrNull
        if (tab == null) {

            openNewTab(url = url, switchTo = true)
            return
        }

        if (url == ABOUT_HOME_URL) {
            tab.displayUrl = ABOUT_HOME_URL
            showStartPage()
            addressBar.setText("")
            updateSecurityIndicator(null)
            updateNavigationButtons()
            return
        }
        hideStartPage()
        updateAddressBar(url)
        tab.displayUrl = url
        tab.webView.loadUrl(url)
    }

    private fun showStartPage() {

        activeTabOrNull?.webView?.stopLoading()
        val isPrivate = activeTabOrNull?.isPrivate == true
        if (isPrivate) {
            startPageView?.hide()
            privateStartPageView?.show()
        } else {
            privateStartPageView?.hide()
            val view = startPageView ?: return
            view.show()
        }
        webContainer.isVisible = false
    }

    private fun hideStartPage() {
        val regular = startPageView
        val incognito = privateStartPageView
        val wasShowing = regular?.isShowing() == true || incognito?.isShowing() == true
        regular?.hide()
        incognito?.hide()
        if (wasShowing) webContainer.isVisible = true
    }

    private fun homeUrl(): String {
        val custom = prefs.homePage
        if (custom.isNotBlank()) return custom
        return ABOUT_HOME_URL
    }

    private fun buildSearchUrl(query: String): String = SearchEngineResolver.buildSearchUrl(prefs, query)

    private fun updateAddressBar(url: String?) {
        if (!url.isNullOrBlank() && !addressBar.isFocused) {
            renderAddressBar(url)
        }
        updateSecurityIndicator(url)
    }

    private fun prettifyUrl(url: String): String = UrlInputUtils.prettifyUrl(url)

    private fun renderAddressBar(url: String?) {
        if (addressBar.hasFocus()) return
        val display = url.orEmpty()
            .takeIf { it.isNotBlank() }
            ?.let(::prettifyUrl)
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
                ForegroundColorSpan(ContextCompat.getColor(this, R.color.browser_faint)),
                host.length,
                span.length,
                Spanned.SPAN_INCLUSIVE_INCLUSIVE,
            )
        }
        addressBar.setText(span, TextView.BufferType.SPANNABLE)
        addressBar.setSelection(span.length)
    }

    private fun updateSecurityIndicator(url: String?) {

        val isHttps = url?.startsWith("https://", ignoreCase = true) == true
        val isBlank = url.isNullOrBlank()

        if (isHttps || isBlank) {
            securityIndicator.setImageResource(R.drawable.ic_lock_24)
            securityIndicator.imageTintList = ContextCompat.getColorStateList(this, R.color.browser_hint)
            securityIndicator.contentDescription = getString(R.string.security_indicator_secure)
        } else {
            securityIndicator.setImageResource(R.drawable.ic_lock_open_24)
            securityIndicator.imageTintList = ContextCompat.getColorStateList(this, R.color.browser_danger)
            securityIndicator.contentDescription = getString(R.string.security_indicator_insecure)
            val tab = activeTabOrNull

            val isHttp = url?.startsWith("http://", ignoreCase = true) == true
            if (isHttp && tab != null && !tab.insecurePageWarningShown) {
                tab.insecurePageWarningShown = true
                Snackbar.make(rootView, R.string.insecure_page_warning, Snackbar.LENGTH_LONG)
                    .setAnchorView(navigationCard)
                    .show()
            }
        }
    }

    private fun updateNavigationButtons() {

        updateButtonState(bookmarkButton, true)
        updateButtonState(searchButton, true)
        updateButtonState(homeButton, true)
        updateButtonState(menuButton, true)
        updateButtonState(refreshButton, activeTabOrNull != null)

        tabsButton.isEnabled = true
        tabsButton.alpha = 1f
        val count = tabs.size
        tabCountText.text = if (count > 9) "9+" else count.toString()
    }

    private fun updateButtonState(button: ImageButton, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.35f
    }

    private fun resolveUserInput(rawInput: String): String {
        val trimmedInput = rawInput.trim()
        if (trimmedInput.isBlank()) {
            return homeUrl()
        }

        val sanitized = UrlInputUtils.stripJavascriptScheme(trimmedInput)
        if (sanitized.isBlank()) {
            return homeUrl()
        }

        return if (UrlInputUtils.looksLikeUrl(sanitized)) {
            val withScheme = UrlInputUtils.enforceSecureScheme(sanitized)

            if (prefs.alwaysHttps && UrlInputUtils.shouldUpgradeToHttps(withScheme)) {
                UrlInputUtils.upgradeToHttps(withScheme)
            } else {
                withScheme
            }
        } else {
            buildSearchUrl(sanitized)
        }
    }

    private fun showBrowserMenu() {
        val view = layoutInflater.inflate(R.layout.sheet_browser_menu, null)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setOnShowListener {
            dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
                ?.background = ColorDrawable(Color.TRANSPARENT)
        }

        val tab = activeTabOrNull
        val currentUrl = tab?.webView?.url.orEmpty()
        val hasPage = currentUrl.isNotBlank()
        val isBookmarked = hasPage && BookmarkRepository.isBookmarked(currentUrl)

        val saveTile: LinearLayout = view.findViewById(R.id.menu_save)
        val saveLabel: TextView = view.findViewById(R.id.menu_save_label)
        val saveIcon: ImageView = view.findViewById(R.id.menu_save_icon)
        saveTile.setBackgroundResource(
            if (isBookmarked) R.drawable.bg_menu_quick_action_selected else R.drawable.bg_menu_quick_action,
        )
        saveLabel.setText(if (isBookmarked) R.string.saved_page else R.string.save_page)
        val accent = resolveThemeColor(R.attr.browserAccent)
        saveLabel.setTextColor(accent)
        saveIcon.imageTintList = android.content.res.ColorStateList.valueOf(accent)

        val desktopTile: LinearLayout = view.findViewById(R.id.menu_desktop)
        val desktopIcon: ImageView = view.findViewById(R.id.menu_desktop_icon)
        val desktopLabel: TextView = view.findViewById(R.id.menu_desktop_label)
        val desktopOn = prefs.desktopMode
        desktopTile.setBackgroundResource(
            if (desktopOn) R.drawable.bg_menu_quick_action_selected
            else R.drawable.bg_menu_quick_action,
        )
        if (desktopOn) {
            desktopIcon.imageTintList = android.content.res.ColorStateList.valueOf(accent)
            desktopLabel.setTextColor(accent)
        } else {
            desktopIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.browser_icon),
            )
            desktopLabel.setTextColor(ContextCompat.getColor(this, R.color.browser_text))
        }

        setMenuTileEnabled(view.findViewById(R.id.menu_share), hasPage)
        setMenuTileEnabled(saveTile, hasPage)
        setMenuTileEnabled(view.findViewById(R.id.menu_find), hasPage)
        setMenuTileEnabled(view.findViewById(R.id.menu_backward), tab?.webView?.canGoBack() == true)
        setMenuTileEnabled(view.findViewById(R.id.menu_forward), tab?.webView?.canGoForward() == true)
        setMenuTileEnabled(view.findViewById(R.id.menu_private), multiProfileSupported)
        setMenuTileEnabled(view.findViewById(R.id.menu_print), hasPage)

        val downloads = DownloadRepository.getSnapshot()
        val activeDownloads = downloads.count {
            it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING
        }
        val completedDownloads = downloads.count { it.status == DownloadStatus.COMPLETED }
        view.findViewById<TextView>(R.id.menu_downloads_summary).text =
            getString(R.string.downloads_summary, activeDownloads, completedDownloads)

        view.findViewById<TextView>(R.id.menu_blocked_summary).text =
            getString(R.string.blocked_sites_summary_domains, BlockedSitesRepository.snapshot().size)

        view.findViewById<ImageButton>(R.id.menu_close).setOnClickListener { dialog.dismiss() }
        view.findViewById<View>(R.id.menu_share).setOnClickListener {
            dialog.dismiss()
            shareCurrentPage()
        }
        saveTile.setOnClickListener {
            dialog.dismiss()
            toggleCurrentPageBookmark(tab)
        }
        view.findViewById<View>(R.id.menu_find).setOnClickListener {
            dialog.dismiss()
            showFindBar()
        }
        view.findViewById<View>(R.id.menu_backward).setOnClickListener {
            dialog.dismiss()
            val webView = activeTabOrNull?.webView ?: return@setOnClickListener
            if (webView.canGoBack()) webView.goBack()
        }
        view.findViewById<View>(R.id.menu_forward).setOnClickListener {
            dialog.dismiss()
            activeTabOrNull?.webView?.goForward()
        }
        view.findViewById<View>(R.id.menu_desktop).setOnClickListener {
            dialog.dismiss()
            toggleDesktopMode()
        }
        view.findViewById<View>(R.id.menu_print).setOnClickListener {
            dialog.dismiss()
            printCurrentPage()
        }
        view.findViewById<View>(R.id.menu_private).setOnClickListener {
            dialog.dismiss()
            openNewTab(url = homeUrl(), switchTo = true, isPrivate = true)
        }
        view.findViewById<View>(R.id.menu_downloads).setOnClickListener {
            dialog.dismiss()
            openDownloadsScreen()
        }
        view.findViewById<View>(R.id.menu_library).setOnClickListener {
            dialog.dismiss()
            openLibraryLauncher.launch(Intent(this, BookmarksActivity::class.java))
        }
        view.findViewById<View>(R.id.menu_blocked).setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, BlockedSitesActivity::class.java))
        }
        view.findViewById<View>(R.id.menu_settings).setOnClickListener {
            dialog.dismiss()
            openSettingsLauncher.launch(Intent(this, SettingsActivity::class.java))
        }

        dialog.show()
    }

    private fun setMenuTileEnabled(tile: View, enabled: Boolean) {
        tile.isEnabled = enabled
        tile.alpha = if (enabled) 1f else 0.38f
        if (tile is ViewGroup) {
            for (i in 0 until tile.childCount) {
                tile.getChildAt(i).isEnabled = enabled
            }
        }
    }

    private fun toggleCurrentPageBookmark(tab: Tab?) {
        val url = tab?.webView?.url
        if (url.isNullOrBlank()) {
            showToast(getString(R.string.no_page_loaded))
            return
        }
        val nowSaved = BookmarkRepository.addOrRemove(url, tab.webView.title.orEmpty())
        showToast(
            getString(
                if (nowSaved) R.string.bookmark_added else R.string.bookmark_removed,
            ),
        )
    }

    private fun toggleDesktopMode() {
        prefs.desktopMode = !prefs.desktopMode
        applyPreferences()
        showToast(
            getString(
                if (prefs.desktopMode) R.string.desktop_mode_on else R.string.desktop_mode_off,
            ),
        )
    }

    private fun canEnterReaderMode(): Boolean {
        val url = activeTabOrNull?.webView?.url ?: return false
        return url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true)
    }

    private fun enterReaderMode() {
        val tab = activeTabOrNull ?: return
        val originalUrl = tab.webView.url
        if (originalUrl.isNullOrBlank() || !canEnterReaderMode()) {
            showToast(getString(R.string.reader_mode_not_eligible))
            return
        }
        ReaderMode.extractInto(tab.webView) { html ->
            if (html == null) {
                showToast(getString(R.string.reader_mode_not_eligible))
                return@extractInto
            }
            tab.webView.loadDataWithBaseURL(
                originalUrl,
                html,
                "text/html",
                "utf-8",
                originalUrl,
            )
        }
    }

    private fun clearBrowsingData() {

        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        clearPrivateProfileDataIfAny()

        for (tab in tabs) {
            tab.webView.clearHistory()
            tab.webView.clearCache(true)
            tab.webView.clearFormData()
        }
        WebStorage.getInstance().deleteAllData()
        HistoryRepository.clear()

        SitePermissionStore.clearAll()
        showToast(getString(R.string.clear_browsing_data_done))
        loadAddress(homeUrl())
    }

    private fun clearPrivateProfileDataIfAny() {
        if (!multiProfileSupported) return
        try {
            val store = ProfileStore.getInstance()
            if (PRIVATE_PROFILE_NAME !in store.allProfileNames) return
            val profile = store.getProfile(PRIVATE_PROFILE_NAME) ?: return
            profile.cookieManager.removeAllCookies(null)
            profile.cookieManager.flush()
            profile.webStorage.deleteAllData()
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to clear private profile data", e)
        }
    }

    private fun updateSearchEngineUi() {
        val name = SearchEngineResolver.displayName(prefs)
        addressBar.hint = getString(R.string.address_hint_with_engine, name)
        renderBrowserCaption(name, activeTabOrNull?.isPrivate == true)
    }

    private fun renderBrowserCaption(searchEngineName: String, isPrivate: Boolean) {
        val span = SpannableStringBuilder()
        span.append("● ")
        span.setSpan(
            ForegroundColorSpan(resolveThemeColor(R.attr.browserAccent)),
            0,
            1,
            Spanned.SPAN_INCLUSIVE_INCLUSIVE,
        )
        span.append(if (isPrivate) "Private · " else "Search · ")
        span.append(searchEngineName)
        span.setSpan(
            ForegroundColorSpan(ContextCompat.getColor(this, R.color.browser_hint)),
            2,
            span.length,
            Spanned.SPAN_INCLUSIVE_INCLUSIVE,
        )
        captionView.text = span
    }

    private fun requestDefaultBrowserRole() {
        val roleManager = getSystemService<RoleManager>() ?: run {
            openDefaultAppsSettings()
            return
        }

        if (!roleManager.isRoleAvailable(RoleManager.ROLE_BROWSER)) {
            openDefaultAppsSettings()
            return
        }

        if (roleManager.isRoleHeld(RoleManager.ROLE_BROWSER)) {
            showToast(getString(R.string.default_browser_already_set))
            return
        }

        defaultBrowserRoleLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_BROWSER))
    }

    private fun isDefaultBrowser(): Boolean {
        val roleManager = getSystemService<RoleManager>() ?: return false
        return roleManager.isRoleAvailable(RoleManager.ROLE_BROWSER) &&
            roleManager.isRoleHeld(RoleManager.ROLE_BROWSER)
    }

    private fun maybeShowDefaultBrowserPrompt() {
        if (prefs.defaultBrowserPromptShown || isDefaultBrowser()) {
            return
        }
        prefs.defaultBrowserPromptShown = true
        Snackbar.make(rootView, R.string.default_browser_prompt, Snackbar.LENGTH_LONG)
            .setAnchorView(navigationCard)
            .setAction(R.string.set_default_browser_action) {
                requestDefaultBrowserRole()
            }
            .show()
    }

    private fun maybeRequestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            return
        }

        if (
            prefs.notificationPromptShown ||
            ContextCompat.checkSelfPermission(this, POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        prefs.notificationPromptShown = true
        notificationPermissionLauncher.launch(POST_NOTIFICATIONS)
    }

    private fun openDownloadsScreen() {
        startActivity(Intent(this, DownloadsActivity::class.java))
    }

    private fun shareCurrentPage() {
        val currentUrl = activeTabOrNull?.webView?.url ?: return showToast(getString(R.string.no_page_loaded))
        shareUrl(currentUrl, chooserTitleRes = R.string.share_page)
    }

    private fun copyCurrentPageLink() {
        val currentUrl = activeTabOrNull?.webView?.url ?: return showToast(getString(R.string.no_page_loaded))
        copyUrlToClipboard(currentUrl)
    }

    private fun copyUrlToClipboard(url: String) {
        val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), url))
        showToast(getString(R.string.link_copied))
    }

    private fun shareUrl(url: String, chooserTitleRes: Int = R.string.share_link) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        startActivity(Intent.createChooser(shareIntent, getString(chooserTitleRes)))
    }

    private fun addLinkContextMenuItems(menu: ContextMenu, url: String) {
        menu.add(getString(R.string.link_action_open_in_new_tab))
            .setOnMenuItemClickListener {
                openNewTab(url = url, switchTo = false)
                showToast(getString(R.string.tab_opened_in_background))
                true
            }
        if (multiProfileSupported) {
            menu.add(getString(R.string.link_action_open_in_private_tab))
                .setOnMenuItemClickListener {
                    openNewTab(url = url, switchTo = true, isPrivate = true)
                    true
                }
        }
        menu.add(getString(R.string.copy_link))
            .setOnMenuItemClickListener {
                copyUrlToClipboard(url)
                true
            }
        menu.add(getString(R.string.share_link))
            .setOnMenuItemClickListener {
                shareUrl(url)
                true
            }
    }

    private fun addImageContextMenuItems(menu: ContextMenu, url: String) {
        menu.add(getString(R.string.image_action_copy_url))
            .setOnMenuItemClickListener {
                copyUrlToClipboard(url)
                true
            }
        menu.add(getString(R.string.image_action_share_url))
            .setOnMenuItemClickListener {
                shareUrl(url)
                true
            }
    }

    private fun addSaveImageItem(menu: ContextMenu, sourceTab: Tab) {
        menu.add(getString(R.string.image_action_save_image))
            .setOnMenuItemClickListener {
                requestLastTouchedImageUrl(sourceTab) { imageUrl ->
                    saveImageFromUrl(imageUrl, sourceTab)
                }
                true
            }
    }

    private fun requestLastTouchedImageUrl(tab: Tab, onUrl: (String) -> Unit) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper()) { msg ->
            val url = msg.data?.getString("url")
            if (!url.isNullOrBlank()) onUrl(url)
            true
        }
        tab.webView.requestImageRef(handler.obtainMessage())
    }

    private fun saveImageFromUrl(imageUrl: String, sourceTab: Tab) {

        if (imageUrl.startsWith("blob:", ignoreCase = true) ||
            imageUrl.startsWith("data:", ignoreCase = true)
        ) {
            showToast(getString(R.string.blob_download_not_supported))
            return
        }

        if (BrowserBlocker.findMatch(imageUrl) != null) {
            showToast(getString(R.string.download_blocked))
            return
        }
        val item = DownloadRepository.enqueueDownload(
            url = imageUrl,
            contentDisposition = null,
            mimeType = guessImageMimeFromUrl(imageUrl),
            contentLengthHint = -1L,
            userAgent = baseUserAgent,
            referer = sourceTab.webView.url,
            profileName = if (sourceTab.isPrivate) PRIVATE_PROFILE_NAME else null,
        )
        DownloadService.start(this)
        Snackbar.make(
            rootView,
            getString(R.string.download_started, item.fileName),
            Snackbar.LENGTH_LONG,
        )
            .setAnchorView(navigationCard)
            .setAction(R.string.view_downloads) {
                openDownloadsScreen()
            }
            .show()
    }

    private fun guessImageMimeFromUrl(url: String): String? {
        val extension = try {
            val path = java.net.URI(url).path ?: return null
            path.substringAfterLast('.', "").lowercase()
        } catch (_: Exception) {
            return null
        }
        return when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "heic", "heif" -> "image/heif"
            "avif" -> "image/avif"
            "svg" -> "image/svg+xml"
            else -> null
        }
    }

    private fun printCurrentPage() {
        val tab = activeTabOrNull
        val webView = tab?.webView
        if (webView?.url.isNullOrBlank()) {
            showToast(getString(R.string.no_page_loaded))
            return
        }
        val printManager = getSystemService(Context.PRINT_SERVICE) as? android.print.PrintManager
        if (printManager == null) {

            showToast(getString(R.string.print_unavailable))
            return
        }

        val jobName = webView.title
            ?.takeIf { it.isNotBlank() }
            ?: getString(R.string.app_name)
        val adapter = webView.createPrintDocumentAdapter(jobName)
        printManager.print(
            jobName,
            adapter,
            android.print.PrintAttributes.Builder().build(),
        )
    }

    private fun openDefaultAppsSettings() {
        val settingsIntent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        try {
            startActivity(settingsIntent)
        } catch (_: ActivityNotFoundException) {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null),
                ),
            )
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasAnyLocationPermission(): Boolean {
        return hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    private fun requiredAndroidPermissions(resource: String): List<String> {
        return when (resource) {
            PermissionRequest.RESOURCE_AUDIO_CAPTURE -> listOf(Manifest.permission.RECORD_AUDIO)
            PermissionRequest.RESOURCE_VIDEO_CAPTURE -> listOf(Manifest.permission.CAMERA)
            else -> emptyList()
        }
    }

    private fun extractOriginLabel(origin: String?): String {
        val uri = origin?.let(Uri::parse)
        return uri?.host ?: origin ?: getString(R.string.this_site)
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
            val title = view.findViewById<TextView>(R.id.suggestion_title)
            val url = view.findViewById<TextView>(R.id.suggestion_url)
            title.text = entry.title.ifBlank { entry.url }
            url.text = UrlInputUtils.prettifyUrl(entry.url)
                .removePrefix("https://")
                .removePrefix("http://")
            return view
        }
    }

    companion object {

        private const val CHROME_ANIM_GATE_MS = 350L

        private const val INACTIVITY_HIDE_MS = 4_000L

        private const val FIND_DEBOUNCE_MS = 150L
        private const val ADDRESS_SUGGESTION_LIMIT = 8
        private const val ADDRESS_SUGGESTION_DISMISS_DELAY_MS = 120L

        private const val STATE_TAB_URLS = "state_tab_urls"
        private const val STATE_ACTIVE_TAB_INDEX = "state_active_tab_index"
        private const val STATE_ACTIVE_TAB_WEBVIEW = "state_active_tab_webview"
        private const val PRIVACY_SCRIPT_TRIM_REFERRER = 1
        private const val PRIVACY_SCRIPT_BLOCK_WEBRTC = 2

        private const val PRIVATE_PROFILE_NAME = "incognito"

        const val ABOUT_HOME_URL = "about:home"

        private val ALLOWED_EXTERNAL_INTENT_ACTIONS = setOf(
            Intent.ACTION_VIEW,
            Intent.ACTION_DIAL,
            Intent.ACTION_SENDTO,
            Intent.ACTION_SEND,
            Intent.ACTION_SEND_MULTIPLE,
            Intent.ACTION_WEB_SEARCH,
        )

        private val ANDROID_PLATFORM_REGEX = Regex("\\(Linux; Android[^)]*\\)")

        private const val DESKTOP_VIEWPORT_SCRIPT = """
            (function() {
                try {
                    var meta = document.querySelector('meta[name="viewport"]');
                    if (!meta) {
                        meta = document.createElement('meta');
                        meta.name = 'viewport';
                        (document.head || document.documentElement).appendChild(meta);
                    }
                    if (meta.getAttribute('content') !== 'width=1024, initial-scale=1') {
                        meta.setAttribute('content', 'width=1024, initial-scale=1');
                    }
                } catch (e) { /* ignore */ }
            })();
        """

        private const val REFRESH_GUARD_BRIDGE_NAME = "__eborsRefreshGuard"

        private const val SHORTS_LAYOUT_BRIDGE_NAME = "__eborsShortsLayout"

        private const val SPEECH_BRIDGE_NAME = "__eborsSpeechBridge"

        private const val SPEECH_POLYFILL_SCRIPT = """
            (function() {
                try {
                    if (window.__eborsSpeechInstalled) return;
                    var bridge = window.$SPEECH_BRIDGE_NAME;
                    if (!bridge || typeof bridge.start !== 'function') return;
                    // Only polyfill when the device actually has speech
                    // recognition. Otherwise stay invisible so the site
                    // falls back to its own (often working) path instead
                    // of trying our API and erroring.
                    try { if (typeof bridge.isAvailable === 'function' && !bridge.isAvailable()) return; }
                    catch (e) { return; }
                    window.__eborsSpeechInstalled = true;

                    var active = null;

                    function Recognition() {
                        this.lang = '';
                        this.continuous = false;
                        this.interimResults = false;
                        this.maxAlternatives = 1;
                        this.onstart = null;
                        this.onaudiostart = null;
                        this.onspeechstart = null;
                        this.onspeechend = null;
                        this.onresult = null;
                        this.onerror = null;
                        this.onend = null;
                        this._listeners = {};
                    }
                    Recognition.prototype.start = function() {
                        active = this;
                        try {
                            bridge.start(this.lang || '', !!this.continuous, !!this.interimResults);
                        } catch (e) {}
                    };
                    Recognition.prototype.stop = function() {
                        try { bridge.stop(); } catch (e) {}
                    };
                    Recognition.prototype.abort = function() {
                        try { bridge.abort(); } catch (e) {}
                    };
                    Recognition.prototype.addEventListener = function(type, fn) {
                        if (!this._listeners[type]) this._listeners[type] = [];
                        this._listeners[type].push(fn);
                    };
                    Recognition.prototype.removeEventListener = function(type, fn) {
                        var a = this._listeners[type];
                        if (!a) return;
                        var i = a.indexOf(fn);
                        if (i >= 0) a.splice(i, 1);
                    };
                    Recognition.prototype._dispatch = function(type, ev) {
                        var h = this['on' + type];
                        if (typeof h === 'function') { try { h.call(this, ev); } catch (e) {} }
                        var a = this._listeners[type];
                        if (a) for (var i = 0; i < a.length; i++) {
                            try { a[i].call(this, ev); } catch (e) {}
                        }
                    };

                    function makeEvent(type) {
                        try { return new Event(type); }
                        catch (e) { return { type: type }; }
                    }

                    window.__eborsSpeech = {
                        _fire: function(name) {
                            if (active) active._dispatch(name, makeEvent(name));
                        },
                        _result: function(transcript, isFinal) {
                            if (!active) return;
                            var ev = makeEvent('result');
                            var alt = { transcript: transcript, confidence: 0.9 };
                            var result = [alt];
                            result.isFinal = isFinal;
                            result.length = 1;
                            result.item = function(i) { return this[i]; };
                            var list = [result];
                            list.length = 1;
                            list.item = function(i) { return this[i]; };
                            ev.results = list;
                            ev.resultIndex = 0;
                            active._dispatch('result', ev);
                        },
                        _error: function(code) {
                            if (!active) return;
                            var ev = makeEvent('error');
                            ev.error = code;
                            active._dispatch('error', ev);
                        },
                        _end: function() {
                            if (active) active._dispatch('end', makeEvent('end'));
                        }
                    };

                    if (!window.SpeechRecognition) window.SpeechRecognition = Recognition;
                    if (!window.webkitSpeechRecognition) window.webkitSpeechRecognition = Recognition;
                } catch (e) { /* ignore */ }
            })();
        """

        private const val YOUTUBE_SHORTS_LAYOUT_SCRIPT = """
            (function() {
                try {
                    if (window.__eborsShortsLayoutInstalled) return;
                    window.__eborsShortsLayoutInstalled = true;

                    var bridge = window.$SHORTS_LAYOUT_BRIDGE_NAME;
                    if (!bridge || typeof bridge.setShortsLayoutActive !== 'function') return;

                    function isShorts() {
                        try {
                            return location.pathname.indexOf('/shorts/') !== -1;
                        } catch (e) { return false; }
                    }

                    function nudgeReflow() {
                        // YouTube's Shorts layout code does most of its
                        // viewport-dependent math once at mount, not on
                        // every render. After our bridge call resizes
                        // the WebView, we have to tell YT to recompute
                        // — otherwise the action rail / title / page
                        // indicator stay anchored to where the old
                        // viewport bottom *was*. The 100 ms delay gives
                        // native a vsync or two to actually apply the
                        // new layout params before we fire the event.
                        setTimeout(function() {
                            try {
                                window.dispatchEvent(new Event('resize'));
                            } catch (e) { /* ignore */ }
                        }, 100);
                        // Second dispatch ~350 ms later catches the
                        // (occasional) case where YT's first-mount
                        // measurement happens *after* our 100 ms fire.
                        setTimeout(function() {
                            try {
                                window.dispatchEvent(new Event('resize'));
                            } catch (e) { /* ignore */ }
                        }, 350);
                    }

                    var last = null;
                    function tick() {
                        try {
                            var now = isShorts();
                            if (now !== last) {
                                last = now;
                                bridge.setShortsLayoutActive(now);
                                nudgeReflow();
                            }
                        } catch (e) { /* ignore */ }
                    }

                    tick();
                    setInterval(tick, 500);
                } catch (e) { /* ignore */ }
            })();
        """

        private const val REFRESH_GUARD_SCRIPT = """
            (function() {
                try {
                    if (window.__eborsRefreshGuardInstalled) return;
                    window.__eborsRefreshGuardInstalled = true;
                    var bridge = window.$REFRESH_GUARD_BRIDGE_NAME;
                    if (!bridge || typeof bridge.setSuppressed !== 'function') return;

                    function hasInternalScroll(el) {
                        try {
                            var depth = 0;
                            while (el && el.nodeType === 1 && depth < 32) {
                                var s = window.getComputedStyle(el);
                                var ob = s.overscrollBehaviorY || s.overscrollBehavior;
                                if (ob === 'contain' || ob === 'none') return true;
                                var oy = s.overflowY;
                                if ((oy === 'scroll' || oy === 'auto') &&
                                    el.scrollHeight - el.clientHeight > 1) return true;
                                el = el.parentElement;
                                depth++;
                            }
                        } catch (e) { /* ignore */ }
                        return false;
                    }

                    var suppressed = false;
                    function set(v) {
                        if (v === suppressed) return;
                        suppressed = v;
                        try { bridge.setSuppressed(v); } catch (e) { /* ignore */ }
                    }

                    document.addEventListener('touchstart', function(e) {
                        try {
                            if (e.touches && e.touches.length === 1 &&
                                hasInternalScroll(e.target)) {
                                set(true);
                            }
                        } catch (err) { /* ignore */ }
                    }, { capture: true, passive: true });

                    function release() { set(false); }
                    document.addEventListener('touchend', release,
                        { capture: true, passive: true });
                    document.addEventListener('touchcancel', release,
                        { capture: true, passive: true });
                } catch (e) { /* ignore */ }
            })();
        """
    }
}
