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
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Message
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
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
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
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
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var rootView: View
    private lateinit var addressBar: AddressEditText
    private lateinit var searchController: SearchController
    private lateinit var chrome: BrowserChromeController
    private lateinit var navigationController: NavigationController
    private var imeWasVisible = false
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
    private lateinit var qrScanButton: ImageButton
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

    private lateinit var tabController: TabController
    private val tabs: List<Tab> get() = tabController.tabs
    private val activeTabIndex: Int get() = tabController.activeTabIndex
    private var tabSwitcherView: TabSwitcherView? = null

    private var startPageView: StartPageView? = null

    private var privateStartPageView: PrivateStartPageView? = null

    /** Drives the animated offline screen; constructed on first show so
     *  its findViewById lands after the overlay include is in the tree. */
    private val noInternetController: NoInternetController by lazy { NoInternetController(this) }
    private var offlineShowing = false

    /** Drives the animated "site not found" screen (online host-lookup
     *  failure); constructed on first show so its findViewById lands after
     *  the overlay include is in the tree. */
    private val siteNotFoundController: SiteNotFoundController by lazy { SiteNotFoundController(this) }
    private var siteNotFoundShowing = false
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    /** Tab whose web media is currently driving the background-playback
     *  service, or null when nothing is playing. */
    private var mediaPlayingTab: Tab? = null

    /** True while web media is actively playing; gates the
     *  keep-WebView-alive path in [onPause] so audio survives backgrounding. */
    private var backgroundMediaPlaying = false

    private val fullscreenVideo = FullscreenVideoController(this)
    private val intentRouter = IntentRouter(this, { navigationController.loadAddress(it) }, this::showToast)
    private val downloadCoordinator by lazy {
        DownloadCoordinator(this, rootView, navigationCard, PRIVATE_PROFILE_NAME, { baseUserAgent }, this::showToast)
    }

    private lateinit var prefs: BrowserPreferences
    private var lastDesktopMode: Boolean = false
    private var lastBlockWebRtc: Boolean = false
    private var lastTrimReferrer: Boolean = false

    private val activeTabOrNull: Tab? get() = tabController.activeTabOrNull

    private val activeTab: Tab get() = tabController.activeTab

    private val openLibraryLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val url = result.data?.getStringExtra(BookmarksActivity.EXTRA_URL)
                if (!url.isNullOrBlank()) {
                    navigationController.loadAddress(url)
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

    private val permissionController = PermissionController(
        activity = this,
        findTabById = { id -> tabs.firstOrNull { it.id == id } },
        activeTab = { activeTabOrNull },
        showToast = this::showToast,
    )

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

    /**
     * QR / barcode scan launched from the address-bar scan button. A decoded
     * URL loads directly; anything else is routed through [resolveUserInput]
     * so plain text becomes a search. Cancelling (back/permission denied)
     * yields a null `contents` and is a silent no-op.
     */
    private val qrScanLauncher =
        registerForActivityResult(ScanContract()) { result ->
            val contents = result.contents?.trim()
            if (!contents.isNullOrBlank()) {
                cancelAddressEditing()
                navigationController.loadAddress(resolveUserInput(contents))
            }
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

        bindViews()
        chrome = BrowserChromeController(
            activity = this,
            addressBar = addressBar,
            securityIndicator = securityIndicator,
            captionView = captionView,
            rootView = rootView,
            navigationCard = navigationCard,
            bookmarkButton = bookmarkButton,
            searchButton = searchButton,
            homeButton = homeButton,
            menuButton = menuButton,
            refreshButton = refreshButton,
            tabsButton = tabsButton,
            tabCountText = tabCountText,
            prefs = prefs,
            activeTab = { activeTabOrNull },
            tabCount = { tabs.size },
            onLeaveInsecurePage = { navigationController.loadAddress(homeUrl()) },
        )
        navigationController = NavigationController(
            activity = this,
            rootView = rootView,
            addressBar = addressBar,
            chrome = chrome,
            fullscreenVideo = fullscreenVideo,
            activeTab = { activeTabOrNull },
            openTab = { tabController.openNewTab(url = it, switchTo = true) },
            showStartPage = ::showStartPage,
            hideStartPage = ::hideStartPage,
            isOfflineShowing = { offlineShowing },
            hideOfflineScreen = ::hideOfflineScreen,
            isAddressEditing = ::isAddressEditing,
            cancelAddressEditing = ::cancelAddressEditing,
            dismissTabSwitcherIfShowing = {
                if (tabSwitcherView?.isShowing() == true) {
                    tabSwitcherView?.dismiss()
                    true
                } else {
                    false
                }
            },
            hideFindBarIfVisible = {
                if (findBar.isVisible) {
                    hideFindBar()
                    true
                } else {
                    false
                }
            },
            showToast = ::showToast,
            onExit = ::finish,
        )
        tabController = TabController(
            activity = this,
            webHost = webHost,
            prefs = prefs,
            chrome = chrome,
            configureWebView = ::configureWebViewForTab,
            onActiveTabChanged = ::onActiveTabActivated,
            mediaPlayingTab = { mediaPlayingTab },
            stopBackgroundMedia = ::stopBackgroundMedia,
            refreshTabSwitcher = ::refreshTabSwitcher,
            onAllTabsClosed = ::finish,
            showToast = ::showToast,
        )
        tabController.wipePrivateProfileIfAny()
        applyInsets()
        configureNavigation()
        configureFindBar()
        configureSwipeRefresh()
        configureServiceWorkerBlocker()
        configureBackHandling()
        chrome.updateSearchEngineUi()

        val restored = tabController.restoreTabsFrom(savedInstanceState)
        if (!restored) {
            val intentUrl = intentRouter.extractIntentUrl(intent)
            tabController.openNewTab(url = intentUrl ?: homeUrl(), switchTo = true)
        }
        applyPreferences()

        MediaPlaybackService.transportCallback = object : MediaPlaybackService.Transport {
            override fun onPlay() = runOnUiThread { mediaAction("play") }
            override fun onPause() = runOnUiThread { mediaAction("pause") }
            override fun onStop() = runOnUiThread { mediaAction("pause") }
            override fun onNext() = runOnUiThread { mediaAction("nexttrack") }
            override fun onPrevious() = runOnUiThread { mediaAction("previoustrack") }
            override fun onSeekTo(positionMs: Long) =
                runOnUiThread { mediaAction("seekto", positionMs / 1000.0) }
        }

        maybeShowDefaultBrowserPrompt()
        permissionController.maybeRequestNotificationPermission(prefs)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val url = intentRouter.extractIntentUrl(intent) ?: return

        tabController.openNewTab(url = url, switchTo = true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!::prefs.isInitialized) return
        tabController.saveState(outState)
    }

    override fun onPause() {
        // The onboarding-redirect path in onCreate calls finish() before this
        // activity is fully initialized, so the lifecycle teardown below would
        // touch uninitialized lateinit fields (prefs / tabController). Bail out
        // early in that case.
        if (!::prefs.isInitialized) {
            super.onPause()
            return
        }
        // While audio is playing we deliberately keep every WebView (and JS
        // timers) alive so playback continues in the background; the page
        // masked its own visibility when playback started so it won't
        // auto-pause. skipWebViewPauseForPermission covers the runtime
        // permission round-trip.
        if (!backgroundMediaPlaying && !permissionController.skipWebViewPauseForPermission) {
            tabController.pauseWebViews()
        }
        if (offlineShowing) noInternetController.stopAnimations()
        if (siteNotFoundShowing) siteNotFoundController.stopAnimations()
        unregisterNetworkCallback()
        tabController.stopSleepTimer()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        // See onPause: skip when the activity finished during onboarding redirect.
        if (!::prefs.isInitialized) return

        permissionController.skipWebViewPauseForPermission = false

        if (prefs.accentKey != boundAccentKey) {
            recreate()
            return
        }
        tabController.resumeWebViews()
        applyPreferences()

        if (startPageView?.isShowing() == true) startPageView?.refresh()
        if (offlineShowing) noInternetController.startAnimations()
        if (siteNotFoundShowing) siteNotFoundController.startAnimations()
        registerNetworkCallback()
        tabController.startSleepTimer()
        // Recover from a reconnect that happened while backgrounded.
        if (offlineShowing && !isDeviceOffline()) onNetworkBackOnline()
    }

    /**
     * Respond to system memory pressure by discarding background tabs (the
     * app previously held every tab's full page in RAM forever). Only the
     * levels that signal genuine pressure act — foreground low/critical and
     * background moderate/complete — so normal backgrounding never triggers
     * a needless reload.
     */
    @Suppress("DEPRECATION") // TRIM_MEMORY_MODERATE/COMPLETE still fire on older OS
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (!::prefs.isInitialized) return
        when (level) {
            TRIM_MEMORY_RUNNING_LOW, TRIM_MEMORY_RUNNING_CRITICAL,
            TRIM_MEMORY_MODERATE, TRIM_MEMORY_COMPLETE,
            -> tabController.discardBackgroundTabs()
        }
    }

    override fun onDestroy() {
        // The onboarding-redirect path in onCreate calls finish() before this
        // activity is fully initialized; skip teardown that would touch
        // uninitialized lateinit fields (prefs / tabController / searchController).
        if (!::prefs.isInitialized) {
            super.onDestroy()
            return
        }
        fullscreenVideo.exit()
        // Auto-delete on exit: only when the task is genuinely finishing
        // (back-to-exit / removed from recents), never on a transient
        // config-change teardown. Runs while tabs are still alive so each
        // WebView's cache can be cleared before destroy().
        if (isFinishing && prefs.clearDataOnExit) wipeBrowsingDataOnExit()
        if (offlineShowing) noInternetController.stopAnimations()
        if (siteNotFoundShowing) siteNotFoundController.stopAnimations()
        // Tabs are torn down below, so any web media is gone — release the
        // session/notification and stop masking transport actions.
        MediaPlaybackService.transportCallback = null
        stopBackgroundMedia()
        permissionController.clearInFlight()

        findDebounceHandler.removeCallbacksAndMessages(null)
        findDebounceRunnable = null

        inactivityHandler.removeCallbacksAndMessages(null)
        searchController.cancelPending()

        speechActive = false
        destroySpeechRecognizer()
        tabController.destroyAll()
        tabSwitcherView?.dismiss()
        tabSwitcherView = null
        super.onDestroy()
    }

    /** UI sync after the active tab changes: start page vs. web, address bar,
     *  security indicator, caption, nav buttons, find bar, offline overlay,
     *  chrome reset and shorts layout. Funnelled here from [TabController]. */
    private fun onActiveTabActivated(target: Tab) {
        val targetIsHome = target.displayUrl == ABOUT_HOME_URL
        if (targetIsHome) {
            showStartPage()
        } else {
            hideStartPage()
        }

        if (targetIsHome) {
            addressBar.setText("")
            chrome.updateSecurityIndicator(null)
        } else {
            chrome.updateAddressBar(target.webView.url ?: target.displayUrl.takeIf { it.isNotBlank() })
        }
        chrome.renderBrowserCaption(target.isPrivate)
        chrome.updateNavigationButtons()

        hideFindBar()
        // The offline overlay is a shared surface: re-decide whether it
        // belongs over the now-active tab (persistent per offline tab,
        // recovers when back online) instead of blindly hiding it.
        evaluateOfflineForActiveTab()
        scrollDirAccumPx = 0
        setChromeHidden(false)
        scheduleAutoHide()
        refreshSuppressedByJs = false

        val newUrl = target.webView.url.orEmpty()
        applyShortsLayout(isYouTubeHost(newUrl) && newUrl.contains("/shorts/"))
    }

    private fun refreshTabSwitcher() {
        if (tabSwitcherView?.isShowing() == true) {
            tabSwitcherView?.refresh(tabController.buildTabSnapshots(), activeTabIndex)
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
        view.show(tabController.buildTabSnapshots(), activeTabIndex)
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
            navigationController.loadAddress(url)
        }

        override fun onStartPageQuerySubmitted(query: String) {

            val text = query.trim()
            if (text.isEmpty()) return
            hideImeForcefully()
            navigationController.loadAddress(resolveUserInput(text))
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
            navigationController.loadAddress(url)
        }

        override fun onStartPageQuerySubmitted(query: String) {
            val text = query.trim()
            if (text.isEmpty()) return
            hideImeForcefully()
            navigationController.loadAddress(resolveUserInput(text))
        }
    }

    private val tabSwitcherListener = object : TabSwitcherView.Listener {
        override fun onSwitchToTab(id: String) = tabController.switchToTabById(id)
        override fun onCloseTab(id: String) = tabController.closeTabById(id)
        override fun onNewTab(isPrivate: Boolean) {
            tabController.openNewTab(url = homeUrl(), switchTo = true, isPrivate = isPrivate)
        }
        override fun onSwitcherClosed() {

            topChrome.isVisible = true
            setTabSwitcherActiveIndicator(active = false)

            scheduleAutoHide()
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
        qrScanButton = findViewById(R.id.qr_scan_button)
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
            // Close the address-bar autocomplete when the keyboard hides
            // (gesture/3-button back), which the IME swallows before it
            // reaches the EditText. The IME inset transition is the one
            // signal that fires across all navigation modes.
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (imeWasVisible && !imeVisible) onKeyboardHidden()
            imeWasVisible = imeVisible
            insets
        }

        ViewCompat.requestApplyInsets(rootView)
    }

    private fun onKeyboardHidden() {
        val wasEditing = isAddressEditing()
        searchController.dismiss()
        if (addressBar.hasFocus()) addressBar.clearFocus()
        startPageView?.onKeyboardHidden()
        privateStartPageView?.onKeyboardHidden()
        // If we were editing, a predictive-back press may also fire the
        // back dispatcher right after this. Arm a brief swallow so that
        // stray back doesn't navigate/exit. The window is short enough
        // that a deliberate second press still navigates.
        if (wasEditing) {
            navigationController.armBackSwallow()
        }
    }

    private fun isAddressEditing(): Boolean =
        addressBar.hasFocus() ||
            searchController.isShowing ||
            startPageView?.isEditing() == true ||
            privateStartPageView?.isEditing() == true

    private fun cancelAddressEditing() {
        searchController.dismiss()
        addressBar.clearFocus()
        hideImeForcefully()
        startPageView?.onKeyboardHidden()
        privateStartPageView?.onKeyboardHidden()
    }

    /** Hide the soft keyboard at the window level, independent of which
     *  view holds focus. More reliable than hideSoftInputFromWindow when
     *  focus may move between the top bar and a home-page search field. */
    private fun hideImeForcefully() {
        WindowInsetsControllerCompat(window, rootView).hide(WindowInsetsCompat.Type.ime())
    }

    private fun configureNavigation() {
        searchController = SearchController(this, addressBar, rootView, prefs, { navigationController.loadAddress(it) }, this::resolveUserInput)
        searchController.configure()

        goButton.setOnClickListener {
            searchController.submit()
        }

        menuButton.setOnClickListener {
            tabSwitcherView?.dismiss()
            showBrowserMenu()
        }

        addressBar.setOnEditorActionListener { _, actionId, event ->
            val pressedEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN

            if (actionId == EditorInfo.IME_ACTION_GO || pressedEnter) {
                searchController.submit()
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
                    searchController.refresh(s?.toString().orEmpty())
                }
            }
        })

        addressBar.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {

                cancelAutoHide()
                addressBar.setText(navigationController.currentAddressUrl())
                addressBar.post {
                    addressBar.selectAll()
                    searchController.refresh("")
                }
            } else {
                addressBar.postDelayed({
                    if (!addressBar.hasFocus()) {
                        searchController.dismiss()
                    }
                }, ADDRESS_SUGGESTION_DISMISS_DELAY_MS)
                chrome.renderAddressBar(navigationController.currentAddressUrl())

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

        // Dedicated incognito shortcut: long-press the tabs button to jump
        // straight into a fresh private tab (mirrors the menu action but
        // one gesture from anywhere).
        tabsButton.setOnLongClickListener {
            if (tabController.multiProfileSupported) {
                tabSwitcherView?.dismiss()
                tabController.openNewTab(url = homeUrl(), switchTo = true, isPrivate = true)
                showToast(getString(R.string.new_private_tab_opened))
            } else {
                showToast(getString(R.string.private_mode_unsupported))
            }
            true
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
            navigationController.loadAddress(homeUrl())
        }

        refreshButton.setOnClickListener {
            activeTabOrNull?.webView?.reload()
        }

        qrScanButton.setOnClickListener {
            launchQrScanner()
        }

        chrome.updateNavigationButtons()
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

    private inner class BackgroundMediaBridge(private val tab: Tab) {
        @JavascriptInterface
        fun onState(
            playing: Boolean,
            title: String?,
            artist: String?,
            artwork: String?,
            durationSec: Double,
            positionSec: Double,
        ) {
            runOnUiThread {
                if (playing) {
                    // Only the active (attached) tab can be playing; ignore
                    // late events from a tab the user already switched away
                    // from (its WebView was paused on switch).
                    if (tab !== activeTabOrNull) return@runOnUiThread
                    mediaPlayingTab = tab
                    backgroundMediaPlaying = true
                    tab.webView.keepAliveWhenHidden = true
                } else if (tab === mediaPlayingTab) {
                    backgroundMediaPlaying = false
                } else {
                    return@runOnUiThread
                }
                MediaPlaybackService.update(
                    this@MainActivity,
                    title.orEmpty(),
                    artist.orEmpty(),
                    artwork.orEmpty(),
                    (durationSec * 1000).toLong().coerceAtLeast(0),
                    (positionSec * 1000).toLong().coerceAtLeast(0),
                    playing,
                )
            }
        }

        @JavascriptInterface
        fun onStopped() {
            runOnUiThread {
                if (tab === mediaPlayingTab) stopBackgroundMedia()
            }
        }
    }

    private fun stopBackgroundMedia() {
        backgroundMediaPlaying = false
        mediaPlayingTab?.webView?.keepAliveWhenHidden = false
        mediaPlayingTab = null
        MediaPlaybackService.stop(this)
    }

    private fun mediaAction(action: String, arg: Double? = null) {
        val tab = mediaPlayingTab ?: activeTabOrNull ?: return
        val js = if (arg != null) {
            "window.__eborsMediaAction && window.__eborsMediaAction('$action', $arg)"
        } else {
            "window.__eborsMediaAction && window.__eborsMediaAction('$action')"
        }
        tab.webView.evaluateJavascript(js, null)
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
                if (permissionController.hasPermission(Manifest.permission.RECORD_AUDIO)) {
                    beginSpeechRecognition(lang.orEmpty(), continuous, interim)
                } else {
                    pendingSpeechStart = Triple(lang.orEmpty(), continuous, interim)

                    permissionController.skipWebViewPauseForPermission = true
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
                    requestHeaders = request.requestHeaders,
                    documentHost = null,
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
            downloadCoordinator.handleDownloadRequest(
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

        target.addJavascriptInterface(
            BackgroundMediaBridge(tab),
            MEDIA_BRIDGE_NAME,
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
                        downloadCoordinator.addSaveImageItem(menu, tab)
                    }
                }

                WebView.HitTestResult.IMAGE_TYPE -> {
                    if (!url.isNullOrBlank()) {
                        addImageContextMenuItems(menu, url)
                        downloadCoordinator.addSaveImageItem(menu, tab)
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
            // Reuse the HTTP cache when entries are still fresh; only hit the
            // network for expired/absent resources. Speeds up repeat visits
            // and heavy sites without serving stale pages.
            cacheMode = WebSettings.LOAD_DEFAULT

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

        applyWebDarkening(target)
        syncDocumentStartScripts(tab)

        target.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                tab.loadProgress = newProgress
                if (tab !== activeTabOrNull) return
                progressBar.progress = newProgress
                progressBar.isVisible = newProgress in 0..99
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                tab.displayTitle = title.orEmpty()
                if (tab === activeTabOrNull && tabSwitcherView?.isShowing() == true) {
                    tabSwitcherView?.refresh(tabController.buildTabSnapshots(), activeTabIndex)
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
                runOnUiThread { permissionController.handleWebsitePermissionRequest(tab, request) }
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest?) {
                permissionController.handlePermissionRequestCanceled(tab, request)
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?,
            ) {
                runOnUiThread { permissionController.handleGeolocationPermissionRequest(tab, origin, callback) }
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (tab !== activeTabOrNull) {

                    callback?.onCustomViewHidden()
                    return
                }
                fullscreenVideo.enter(view, callback)
            }

            override fun onHideCustomView() {
                fullscreenVideo.exit()
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

                val newTab = tabController.openNewTab(url = null, switchTo = true) ?: return false
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                transport.webView = newTab.webView
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView?) {
                if (window == null) return
                val tab = tabs.firstOrNull { it.webView === window } ?: return
                tabController.closeTabById(tab.id)
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
                    return intentRouter.handleExternalScheme(targetUrl)
                }

                if (prefs.alwaysHttps && UrlInputUtils.shouldUpgradeToHttps(targetUrl)) {
                    view?.loadUrl(UrlInputUtils.upgradeToHttps(targetUrl))
                    return true
                }
                // A link tap is a renderer-initiated navigation, which
                // doesn't reliably reach onReceivedError the way a
                // programmatic reload does — so the offline screen wouldn't
                // appear on a tap, only on refresh. Surface it here when
                // we're offline. If the target turns out to be cached and
                // loads anyway, onPageFinished clears the overlay again.
                if (request?.isForMainFrame == true &&
                    tab === activeTabOrNull &&
                    isDeviceOffline()
                ) {
                    showOfflineScreen()
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
                    requestHeaders = request?.requestHeaders,
                    documentHost = tab.currentHost,
                )
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                tab.loadProgress = 0

                // A full navigation in the tab that was driving background
                // audio means the old media element is gone; clear the
                // session so we don't show stale controls.
                if (tab === mediaPlayingTab) stopBackgroundMedia()

                if (tab.displayUrl == ABOUT_HOME_URL &&
                    !url.isNullOrBlank() &&
                    url != ABOUT_HOME_URL &&
                    tab === activeTabOrNull
                ) {
                    hideStartPage()
                }
                url?.takeUnless { it == ABOUT_BLANK_URL }?.let { tab.displayUrl = it }
                tab.currentHost = BrowserBlocker.hostOf(url)
                if (tab.pendingReaderModeUrl == null || tab.pendingReaderModeUrl != url) {
                    tab.clearReaderModeState()
                }
                if (!PageTranslator.isTranslationUrl(url)) {
                    tab.translationSourceUrl = null
                    tab.translationTargetLanguage = null
                }
                tab.insecurePageWarningShown = false
                tab.mainFrameErrored = false
                tab.siteNotFoundUrl = null

                refreshSuppressedByJs = false
                if (tab === activeTabOrNull) {
                    chrome.updateAddressBar(url)
                    chrome.updateNavigationButtons()
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
                tab.loadProgress = 100
                if (tab.pendingReaderModeUrl == url) {
                    tab.pendingReaderModeUrl = null
                }
                injectPageScripts(view, url)
                url?.takeUnless { it == ABOUT_BLANK_URL }?.let { tab.displayUrl = it }
                if (url != ABOUT_BLANK_URL) view?.title?.let { tab.displayTitle = it }
                if (prefs.historyEnabled && !tab.isPrivate &&
                    !url.isNullOrBlank() && url != ABOUT_BLANK_URL
                ) {
                    HistoryRepository.record(url, view?.title.orEmpty())
                }
                if (tab === activeTabOrNull) {
                    progressBar.isVisible = false

                    webContainer.isRefreshing = false
                    chrome.updateAddressBar(url)
                    chrome.updateNavigationButtons()
                    // A finish without a main-frame error means the page
                    // actually loaded — clear any offline / site-not-found
                    // overlay left up from a previous failed attempt in this tab.
                    if (!tab.mainFrameErrored) {
                        hideOfflineScreen()
                        tab.siteNotFoundUrl = null
                        hideSiteNotFound()
                    }
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
                val failingUrl = request?.url?.toString().orEmpty()
                // Route the error through the pure decision function so the
                // offline-vs-site-not-found precedence lives in one tested place.
                // WebResourceError.errorCode requires API 23+; minSdk is 29.
                val surface = resolveErrorSurface(
                    isForMainFrame = request?.isForMainFrame == true,
                    isDeviceOffline = isDeviceOffline(),
                    errorCode = error?.errorCode ?: 0,
                )
                when (surface) {
                    // Sub-resource failure: leave the tab presentation unchanged
                    // (do not even record a main-frame error).
                    ErrorSurface.UNCHANGED -> {
                        // no-op
                    }
                    // Device genuinely offline — surface the branded offline screen.
                    ErrorSurface.OFFLINE -> {
                        tab.mainFrameErrored = true
                        if (tab === activeTabOrNull) {
                            progressBar.isVisible = false
                            webContainer.isRefreshing = false
                            chrome.updateNavigationButtons()
                            showOfflineScreen()
                        }
                    }
                    // Online host-lookup failure (DNS typo, dead host): show the
                    // branded site-not-found screen for the active tab and record
                    // the failing URL so a tab switch can restore it.
                    ErrorSurface.SITE_NOT_FOUND -> {
                        tab.mainFrameErrored = true
                        tab.siteNotFoundUrl = failingUrl
                        if (tab === activeTabOrNull) {
                            progressBar.isVisible = false
                            webContainer.isRefreshing = false
                            chrome.updateNavigationButtons()
                            showSiteNotFound(failingUrl)
                        }
                    }
                    // Any other online main-frame error (server down, timeout):
                    // keep the WebView's own error page — no branded overlay.
                    ErrorSurface.DEFAULT_WEBVIEW -> {
                        tab.mainFrameErrored = true
                        if (tab === activeTabOrNull) {
                            progressBar.isVisible = false
                            webContainer.isRefreshing = false
                            chrome.updateNavigationButtons()
                        }
                    }
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

        chrome.updateSearchEngineUi()

        for (tab in tabs) {
            applyToWebView(tab)
        }

        val shouldReloadTabs =
            prefs.desktopMode != lastDesktopMode ||
                prefs.blockWebRtc != lastBlockWebRtc ||
                prefs.trimReferrer != lastTrimReferrer
        if (shouldReloadTabs) {
            lastDesktopMode = prefs.desktopMode
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
        applyWebDarkening(target)
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

        if (tab.mediaDocumentStartScript == null) {
            tab.mediaDocumentStartScript = addDocumentStartScript(
                tab.webView,
                MEDIA_BACKGROUND_SCRIPT,
                setOf("https://*", "http://*"),
            )
        }

        // Cosmetic hiding + anti-adblock at document-start: the <style> and
        // detector stubs land before the page's own scripts run, so ad
        // containers never flash and "disable your adblocker" overlays can't
        // win the race. Re-install when the aggressive pref flips; remove
        // entirely when ad blocking is off.
        if (!prefs.adBlockEnabled) {
            removeDocumentStartScript(tab.cosmeticDocumentStartScript)
            tab.cosmeticDocumentStartScript = null
        } else if (
            tab.cosmeticDocumentStartScript == null ||
            tab.cosmeticDocumentStartAggressive != prefs.aggressiveAntiAdblock
        ) {
            removeDocumentStartScript(tab.cosmeticDocumentStartScript)
            tab.cosmeticDocumentStartScript = addDocumentStartScript(
                tab.webView,
                BrowserBlocker.cosmeticHidingScript(aggressive = prefs.aggressiveAntiAdblock),
                setOf("https://*", "http://*"),
            )
            tab.cosmeticDocumentStartAggressive = prefs.aggressiveAntiAdblock
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
    private fun applyWebDarkening(target: WebView) {
        // Web content darkening follows the system theme. Algorithmic
        // darkening only kicks in when the app is in night mode (which now
        // tracks the OS via MODE_NIGHT_FOLLOW_SYSTEM); FORCE_DARK_AUTO does
        // the same on older WebView builds.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(target.settings, true)
        } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(target.settings, WebSettingsCompat.FORCE_DARK_AUTO)
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
        if (fullscreenVideo.isInFullscreen) return false
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

        // Reliable install point for the background-media shim: the JS
        // interface is guaranteed present here, and this also covers
        // WebView builds without DOCUMENT_START_SCRIPT. The shim is
        // idempotent (guards on window.__eborsMediaInit).
        view.evaluateJavascript(MEDIA_BACKGROUND_SCRIPT, null)

        if (url != null && isYouTubeHost(url)) {
            view.evaluateJavascript(YOUTUBE_SHORTS_LAYOUT_SCRIPT, null)
        }
        // Cosmetic hiding + anti-adblock normally runs at document-start
        // (see syncDocumentStartScripts) so ads never flash. Only inject
        // here as a fallback for WebViews without DOCUMENT_START_SCRIPT,
        // where document-start registration silently no-ops.
        if (prefs.adBlockEnabled &&
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
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
            override fun handleOnBackPressed() = navigationController.handleBackPressed()
        })
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

    /** True when the device has no usable network (no active network, or
     *  the active network can't reach the internet). Defaults to "online"
     *  when connectivity can't be queried so we never falsely block a load
     *  behind the offline screen. */
    private fun isDeviceOffline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return true
        val caps = cm.getNetworkCapabilities(network) ?: return true
        return !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun showOfflineScreen() {
        hideStartPage()
        hideFindBar()
        findViewById<View>(R.id.no_internet_overlay).isVisible = true
        offlineShowing = true
        noInternetController.show {
            // Manual retry: reload. A successful load clears the overlay
            // in onPageFinished; a repeat failure re-shows it.
            activeTabOrNull?.webView?.reload()
        }
    }

    private fun hideOfflineScreen() {
        if (!offlineShowing) return
        offlineShowing = false
        noInternetController.stopAnimations()
        findViewById<View>(R.id.no_internet_overlay).isVisible = false
    }

    /**
     * Show the branded "site not found" screen over the active tab. Mirrors
     * [showOfflineScreen]: prepare the surface (hide start page / find bar),
     * reveal the overlay, hide the active WebView, then hand off to the
     * controller which binds the copy/CTAs and starts the hero animation.
     */
    private fun showSiteNotFound(url: String) {
        hideStartPage()
        hideFindBar()
        findViewById<View>(R.id.site_not_found_overlay).isVisible = true
        activeTabOrNull?.webView?.isVisible = false
        siteNotFoundShowing = true
        siteNotFoundController.show(
            failedUrl = url,
            onBack = {
                hideSiteNotFound()
                val webView = activeTabOrNull?.webView
                if (webView?.canGoBack() == true) {
                    webView.goBack()
                } else {
                    showStartPage()
                }
            },
            onRetry = {
                hideSiteNotFound()
                activeTabOrNull?.webView?.reload()
            },
        )
    }

    private fun hideSiteNotFound() {
        if (!siteNotFoundShowing) return
        siteNotFoundShowing = false
        siteNotFoundController.stopAnimations()
        findViewById<View>(R.id.site_not_found_overlay).isVisible = false
        activeTabOrNull?.webView?.isVisible = true
    }

    /**
     * The offline overlay is one shared surface, not per-tab, so on every
     * tab switch we re-decide what belongs over the now-active tab:
     *  - it failed and we're still offline → show the branded screen
     *    (this is what stops a stale native error page showing through);
     *  - it failed but we're back online → reload to recover it;
     *  - otherwise → hide.
     */
    private fun evaluateOfflineForActiveTab() {
        val tab = activeTabOrNull
        val surface = resolveActiveTabSurface(
            hasMainFrameError = tab?.mainFrameErrored == true,
            hasUnresolvedHostLookup = tab?.siteNotFoundUrl != null,
            isDeviceOffline = isDeviceOffline(),
        )
        when (surface) {
            ActiveSurface.OFFLINE -> {
                // Failed tab + still offline: keep the branded offline screen
                // over it (stops a stale native error page showing through).
                hideSiteNotFound()
                showOfflineScreen()
            }
            ActiveSurface.SITE_NOT_FOUND -> {
                // Online host-lookup failure on this tab: restore its
                // site-not-found overlay for the recorded failing URL.
                hideOfflineScreen()
                tab?.siteNotFoundUrl?.let { showSiteNotFound(it) }
            }
            ActiveSurface.NONE -> {
                // Nothing should cover this tab. Hide both overlays; and if the
                // tab had failed offline and we're now back online, reload to
                // recover it (preserves the prior recovery behavior).
                hideSiteNotFound()
                hideOfflineScreen()
                if (tab != null && tab.mainFrameErrored && !isDeviceOffline() &&
                    tab.webView.url != null
                ) {
                    tab.webView.reload()
                }
            }
        }
    }

    /**
     * Reconnect handler (driven by [networkCallback]): recover any tab
     * stranded on an offline error. The active tab reloads immediately;
     * background tabs reload the next time they're activated. Event-driven
     * off the connectivity callback, so there's no battery-wasting poll —
     * recovery happens the instant the line is back.
     */
    private fun onNetworkBackOnline() {
        val active = activeTabOrNull
        var recoveredActive = false
        for (tab in tabs) {
            if (!tab.mainFrameErrored) continue
            if (tab === active && tab.webView.url != null) {
                tab.webView.reload()
                recoveredActive = true
            } else if (tab !== active) {
                tab.pendingReloadOnActivate = true
            }
        }
        if (!recoveredActive && offlineShowing) {
            // Active tab had nothing to reload (e.g. a blank failed load);
            // drop the overlay so the user isn't stuck behind it.
            hideOfflineScreen()
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            ?: return
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(
                network: android.net.Network,
                caps: android.net.NetworkCapabilities,
            ) {
                if (caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                ) {
                    runOnUiThread { onNetworkBackOnline() }
                }
            }
        }
        networkCallback = callback
        runCatching { cm.registerDefaultNetworkCallback(callback) }
    }

    private fun unregisterNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        networkCallback?.let { cb -> cm?.let { runCatching { it.unregisterNetworkCallback(cb) } } }
        networkCallback = null
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

    /**
     * Launch the bundled ZXing scanner. Camera-permission prompting is
     * handled by the capture activity itself; a device with no camera or a
     * hard denial surfaces as a toast rather than a crash.
     */
    private fun launchQrScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(
                ScanOptions.QR_CODE,
                ScanOptions.DATA_MATRIX,
                ScanOptions.PDF_417,
                ScanOptions.EAN_13,
                ScanOptions.UPC_A,
                ScanOptions.CODE_128,
            )
            setPrompt(getString(R.string.qr_scan_prompt))
            setBeepEnabled(false)
            // Use a fixed-portrait capture activity instead of the library's
            // landscape-locked default. orientationLocked must be false so the
            // library's CaptureManager doesn't override the manifest portrait
            // orientation by re-locking to the device's current rotation.
            setCaptureActivity(PortraitCaptureActivity::class.java)
            setOrientationLocked(false)
        }
        try {
            qrScanLauncher.launch(options)
        } catch (e: Exception) {
            Log.w("MainActivity", "QR scanner unavailable", e)
            showToast(getString(R.string.qr_scan_unavailable))
        }
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
        sizeQuickActionTiles(view)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setOnShowListener {
            dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
                ?.background = ColorDrawable(Color.TRANSPARENT)
        }

        val tab = activeTabOrNull
        val currentUrl = currentPageUrl(tab).orEmpty()
        val hasPage = currentUrl.isNotBlank()
        val isBookmarked = hasPage && BookmarkRepository.isBookmarked(currentUrl)

        val saveTile: LinearLayout = view.findViewById(R.id.menu_save)
        val saveLabel: TextView = view.findViewById(R.id.menu_save_label)
        val saveIcon: ImageView = view.findViewById(R.id.menu_save_icon)
        saveTile.setBackgroundResource(
            if (isBookmarked) R.drawable.bg_menu_quick_action_selected else R.drawable.bg_menu_quick_action,
        )
        saveLabel.setText(if (isBookmarked) R.string.saved_page else R.string.save_page)
        // Distinct from the plain outline bookmark used for "view bookmarks":
        // a bookmark-with-plus to add, a filled bookmark once saved.
        saveIcon.setImageResource(
            if (isBookmarked) R.drawable.ic_bookmark_filled_24 else R.drawable.ic_bookmark_add_24,
        )
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
        setMenuTileEnabled(view.findViewById(R.id.menu_reader), canEnterReaderMode())
        setMenuTileEnabled(view.findViewById(R.id.menu_find), hasPage)
        setMenuTileEnabled(view.findViewById(R.id.menu_backward), tab?.webView?.canGoBack() == true)
        setMenuTileEnabled(view.findViewById(R.id.menu_forward), tab?.webView?.canGoForward() == true)
        setMenuTileEnabled(view.findViewById(R.id.menu_private), tabController.multiProfileSupported)
        setMenuTileEnabled(view.findViewById(R.id.menu_print), hasPage)
        setMenuTileEnabled(view.findViewById(R.id.menu_translate), canEnterReaderMode())
        setMenuTileEnabled(view.findViewById(R.id.menu_offline), canEnterReaderMode())

        val savedPagesCount = OfflineArticleStore.list(this).size
        view.findViewById<TextView>(R.id.menu_saved_pages_summary).text =
            getString(R.string.saved_pages_summary, savedPagesCount)

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
        view.findViewById<View>(R.id.menu_reader).setOnClickListener {
            dialog.dismiss()
            enterReaderMode()
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
        view.findViewById<View>(R.id.menu_translate).setOnClickListener {
            dialog.dismiss()
            translateCurrentPage()
        }
        view.findViewById<View>(R.id.menu_offline).setOnClickListener {
            dialog.dismiss()
            saveCurrentPageOffline()
        }
        view.findViewById<View>(R.id.menu_saved_pages).setOnClickListener {
            dialog.dismiss()
            showSavedPages()
        }
        view.findViewById<View>(R.id.menu_private).setOnClickListener {
            dialog.dismiss()
            tabController.openNewTab(url = homeUrl(), switchTo = true, isPrivate = true)
        }
        view.findViewById<View>(R.id.menu_downloads).setOnClickListener {
            dialog.dismiss()
            downloadCoordinator.openDownloadsScreen()
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

    /**
     * Size the quick-action tiles so exactly four columns fill the sheet
     * width, the rest panning in horizontally. Done in code rather than
     * XML because layout_weight collapses to zero inside a
     * HorizontalScrollView. Posted so the scroller has a measured width.
     */
    private fun sizeQuickActionTiles(view: View) {
        val scroller = view.findViewById<View>(R.id.menu_quick_actions)
        scroller.post {
            val gap = dp(10)
            val available = scroller.width.takeIf { it > 0 }
                ?: resources.displayMetrics.widthPixels
            val tileWidth = ((available - dp(20) * 2 - gap * 3) / 4).coerceAtLeast(dp(64))
            intArrayOf(
                R.id.menu_share, R.id.menu_save, R.id.menu_reader, R.id.menu_desktop,
                R.id.menu_private, R.id.menu_find, R.id.menu_backward,
                R.id.menu_forward, R.id.menu_print, R.id.menu_translate,
                R.id.menu_offline,
            ).forEach { id ->
                val tile = view.findViewById<View>(id)
                tile.layoutParams = tile.layoutParams.apply { width = tileWidth }
            }
        }
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
        val url = currentPageUrl(tab)
        if (url.isNullOrBlank()) {
            showToast(getString(R.string.no_page_loaded))
            return
        }
        val nowSaved = BookmarkRepository.addOrRemove(url, currentPageTitle(tab))
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

    private fun currentPageUrl(tab: Tab? = activeTabOrNull): String? {
        if (tab == null) return null
        return listOf(
            tab.readerModeSourceUrl,
            tab.webView.url,
            tab.displayUrl,
        ).firstOrNull { url ->
            !url.isNullOrBlank() &&
                url != ABOUT_BLANK_URL &&
                url != ABOUT_HOME_URL
        }
    }

    private fun currentHttpPageUrl(tab: Tab? = activeTabOrNull): String? =
        currentPageUrl(tab)?.takeIf(::isHttpPageUrl)

    private fun currentPageTitle(tab: Tab?): String {
        if (tab == null) return ""
        return tab.readerModeSourceTitle
            ?.takeIf { it.isNotBlank() }
            ?: tab.webView.title.orEmpty().ifBlank { tab.displayTitle }
    }

    private fun isHttpPageUrl(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true)

    private fun canEnterReaderMode(): Boolean {
        val tab = activeTabOrNull ?: return false
        return currentHttpPageUrl(tab) != null && tab.loadProgress >= 100
    }

    private fun enterReaderMode() {
        val tab = activeTabOrNull ?: return
        val originalUrl = currentHttpPageUrl(tab)
        if (originalUrl.isNullOrBlank() || !canEnterReaderMode()) {
            showToast(getString(R.string.reader_mode_not_eligible))
            return
        }
        val originalTitle = currentPageTitle(tab)
        cancelAddressEditing()
        ReaderMode.extractInto(tab.webView) { html ->
            if (html == null) {
                showToast(getString(R.string.reader_mode_not_eligible))
                return@extractInto
            }
            tab.readerModeSourceUrl = originalUrl
            tab.readerModeSourceTitle = originalTitle
            tab.readerModeHtml = html
            tab.pendingReaderModeUrl = originalUrl
            tab.webView.loadDataWithBaseURL(
                originalUrl,
                html,
                "text/html",
                "utf-8",
                originalUrl,
            )
        }
    }

    private fun translateCurrentPage() {
        val tab = activeTabOrNull
        val currentUrl = currentPageUrl(tab)
        if (tab == null || currentUrl.isNullOrBlank()) {
            showToast(getString(R.string.translate_not_available))
            return
        }

        val sourceUrl = tab.translationSourceUrl
            ?: PageTranslator.extractSourceUrl(currentUrl)
            ?: currentUrl.takeUnless(PageTranslator::isTranslationUrl)
        if (sourceUrl == null) {
            showToast(getString(R.string.translate_source_unavailable))
            return
        }
        showTranslateLanguageDialog(tab, sourceUrl)
    }

    /**
     * Searchable target-language picker. The page proxy auto-detects the
     * source language; the selected target is remembered for the next page.
     */
    private fun showTranslateLanguageDialog(tab: Tab, sourceUrl: String) {
        val preferred = PageTranslator.preferred(
            deviceLanguageTag = Locale.getDefault().toLanguageTag(),
            persistedCode = prefs.translationTargetLanguage,
        )
        val ordered = buildList {
            add(preferred)
            addAll(PageTranslator.languages.filterNot { it.code.equals(preferred.code, true) })
        }
        val visibleLanguages = ordered.toMutableList()

        val search = EditText(this).apply {
            hint = getString(R.string.translate_search_languages)
            isSingleLine = true
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            visibleLanguages.map(TranslationLanguage::label).toMutableList(),
        )
        val list = ListView(this).apply {
            this.adapter = adapter
            dividerHeight = 0
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(420),
            )
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), 0)
            addView(
                search,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(list)
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.translate_choose_language)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        fun applyFilter(query: String) {
            val needle = query.trim().lowercase(Locale.ROOT)
            val filtered = if (needle.isEmpty()) {
                ordered
            } else {
                ordered.filter {
                    it.name.lowercase(Locale.ROOT).contains(needle) ||
                        it.code.lowercase(Locale.ROOT).contains(needle)
                }
            }
            visibleLanguages.clear()
            visibleLanguages.addAll(filtered)
            adapter.clear()
            adapter.addAll(filtered.map(TranslationLanguage::label))
            adapter.notifyDataSetChanged()
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyFilter(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        list.setOnItemClickListener { _, _, position, _ ->
            val language = visibleLanguages.getOrNull(position) ?: return@setOnItemClickListener
            if (tab !== activeTabOrNull) {
                dialog.dismiss()
                return@setOnItemClickListener
            }
            val proxy = PageTranslator.buildProxyUrl(sourceUrl, language.code)
            if (proxy == null) {
                showToast(getString(R.string.translate_not_available))
                return@setOnItemClickListener
            }
            prefs.translationTargetLanguage = language.code
            tab.translationSourceUrl = sourceUrl
            tab.translationTargetLanguage = language.code
            search.clearFocus()
            hideImeForcefully()
            dialog.dismiss()
            showToast(getString(R.string.translate_loading, language.name))
            navigationController.loadAddress(proxy)
        }
        dialog.setOnShowListener { search.requestFocus() }
        dialog.show()
    }

    /**
     * Save the current page's reader-extracted article for offline reading.
     * Reuses the [ReaderMode] pipeline so we store clean, self-contained
     * article HTML (loaded back via loadDataWithBaseURL, no file access).
     * Non-article pages report "not eligible" rather than saving a broken
     * snapshot.
     */
    private fun saveCurrentPageOffline() {
        val tab = activeTabOrNull
        val url = currentHttpPageUrl(tab)
        if (tab == null || url.isNullOrBlank()) {
            showToast(getString(R.string.offline_save_not_eligible))
            return
        }
        val title = currentPageTitle(tab)
        showToast(getString(R.string.offline_saving))
        val readerHtml = tab.readerModeHtml
        if (!readerHtml.isNullOrBlank() && tab.readerModeSourceUrl == url) {
            OfflineArticleStore.save(this, url, title, readerHtml) { ok ->
                showToast(getString(if (ok) R.string.offline_saved else R.string.offline_save_failed))
            }
            return
        }
        extractAndSaveCurrentPageOffline(tab, url, title, SystemClock.elapsedRealtime())
    }

    private fun extractAndSaveCurrentPageOffline(tab: Tab, url: String, title: String, startedAt: Long) {
        if (tab !== activeTabOrNull || currentHttpPageUrl(tab) != url) return
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        if (tab.loadProgress < 100 && elapsedMs < OFFLINE_SAVE_MAX_WAIT_MS) {
            tab.webView.postDelayed(
                { extractAndSaveCurrentPageOffline(tab, url, title, startedAt) },
                OFFLINE_SAVE_RETRY_DELAY_MS,
            )
            return
        }
        tab.webView.postDelayed(
            {
                if (tab !== activeTabOrNull || currentHttpPageUrl(tab) != url) return@postDelayed
                extractAndPersistOfflineArticle(tab, url, title)
            },
            OFFLINE_SAVE_DOM_SETTLE_DELAY_MS,
        )
    }

    private fun extractAndPersistOfflineArticle(tab: Tab, url: String, title: String) {
        ReaderMode.extractInto(tab.webView) { html ->
            if (html == null) {
                showToast(getString(R.string.offline_save_not_eligible))
                return@extractInto
            }
            OfflineArticleStore.save(this, url, title, html) { ok ->
                showToast(getString(if (ok) R.string.offline_saved else R.string.offline_save_failed))
            }
        }
    }

    private fun showSavedPages() {
        val articles = OfflineArticleStore.list(this)
        if (articles.isEmpty()) {
            showToast(getString(R.string.offline_empty))
            return
        }
        val labels = articles.map { it.title.ifBlank { it.url } }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.saved_pages_title)
            .setItems(labels) { _, which -> openSavedArticle(articles[which]) }
            .setNeutralButton(R.string.offline_clear_all) { _, _ ->
                OfflineArticleStore.clear(this)
                showToast(getString(R.string.offline_cleared))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openSavedArticle(article: OfflineArticleStore.OfflineArticle) {
        val html = OfflineArticleStore.readHtml(this, article.id)
        if (html == null) {
            showToast(getString(R.string.offline_open_failed))
            return
        }
        val tab = activeTabOrNull ?: tabController.openNewTab(url = null, switchTo = true) ?: return
        hideStartPage()
        tab.displayUrl = article.url
        tab.webView.loadDataWithBaseURL(article.url, html, "text/html", "utf-8", article.url)
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
        navigationController.loadAddress(homeUrl())
    }

    /**
     * Silent variant of [clearBrowsingData] for the auto-delete-on-exit
     * path: no toast, no home reload (the activity is going away). Wrapped
     * in runCatching so a teardown-time failure can never crash onDestroy.
     */
    private fun wipeBrowsingDataOnExit() {
        runCatching {
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
            Log.i("MainActivity", "Auto-deleted browsing data on exit")
        }.onFailure { Log.w("MainActivity", "wipeBrowsingDataOnExit failed", it) }
    }

    private fun clearPrivateProfileDataIfAny() {
        if (!tabController.multiProfileSupported) return
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

    private fun shareCurrentPage() {
        val currentUrl = currentPageUrl() ?: return showToast(getString(R.string.no_page_loaded))
        shareUrl(currentUrl, chooserTitleRes = R.string.share_page)
    }

    private fun copyCurrentPageLink() {
        val currentUrl = currentPageUrl() ?: return showToast(getString(R.string.no_page_loaded))
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
                tabController.openNewTab(url = url, switchTo = false)
                showToast(getString(R.string.tab_opened_in_background))
                true
            }
        if (tabController.multiProfileSupported) {
            menu.add(getString(R.string.link_action_open_in_private_tab))
                .setOnMenuItemClickListener {
                    tabController.openNewTab(url = url, switchTo = true, isPrivate = true)
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

    private fun extractOriginLabel(origin: String?): String {
        val uri = origin?.let(Uri::parse)
        return uri?.host ?: origin ?: getString(R.string.this_site)
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {

        private const val CHROME_ANIM_GATE_MS = 350L

        private const val INACTIVITY_HIDE_MS = 4_000L

        private const val FIND_DEBOUNCE_MS = 150L
        private const val ADDRESS_SUGGESTION_DISMISS_DELAY_MS = 120L
        private const val OFFLINE_SAVE_RETRY_DELAY_MS = 250L
        private const val OFFLINE_SAVE_DOM_SETTLE_DELAY_MS = 350L
        private const val OFFLINE_SAVE_MAX_WAIT_MS = 4_000L

        private const val PRIVACY_SCRIPT_TRIM_REFERRER = 1
        private const val PRIVACY_SCRIPT_BLOCK_WEBRTC = 2

        const val PRIVATE_PROFILE_NAME = "incognito"

        const val ABOUT_HOME_URL = "about:home"

        /** Blank page a backgrounded tab is parked on when discarded under
         *  memory pressure; its real URL is reloaded from pendingLoadUrl on
         *  reactivation. Excluded from history and the tab's display URL. */
        const val ABOUT_BLANK_URL = "about:blank"

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

        private const val MEDIA_BRIDGE_NAME = "__eborsMediaBridge"

        // Injected at document start (and again on page-finished) for
        // http(s). Three jobs:
        //  1. While audio plays (keep==true) mask the Page Visibility API and
        //     swallow visibility/pagehide events so the page doesn't auto-pause.
        //  2. Report <video>/<audio> state + the page's MediaSession metadata
        //     (title/artist/artwork) and duration/position to the app so it can
        //     render a full media notification.
        //  3. Capture the page's own navigator.mediaSession action handlers
        //     (play/pause/nexttrack/previoustrack/seekto) so the notification's
        //     transport buttons drive the site generically — no per-site hacks.
        private const val MEDIA_BACKGROUND_SCRIPT = """
            (function() {
                try {
                    if (window.__eborsMediaInit) return;
                    window.__eborsMediaInit = true;
                    var bridge = window.$MEDIA_BRIDGE_NAME;
                    var keep = false;
                    var current = null;
                    var playingNow = false;
                    var durationSec = 0;
                    var positionSec = 0;
                    var handlers = {};

                    function defineVis(proto, prop, masked) {
                        var real = Object.getOwnPropertyDescriptor(proto, prop);
                        try {
                            Object.defineProperty(proto, prop, {
                                configurable: true,
                                get: function() {
                                    if (keep) return masked;
                                    return real && real.get ? real.get.call(this) : masked;
                                }
                            });
                        } catch (e) {}
                    }
                    defineVis(Document.prototype, 'hidden', false);
                    defineVis(Document.prototype, 'visibilityState', 'visible');

                    function guard(e) { if (keep) { e.stopImmediatePropagation(); } }
                    window.addEventListener('visibilitychange', guard, true);
                    document.addEventListener('visibilitychange', guard, true);
                    window.addEventListener('webkitvisibilitychange', guard, true);
                    window.addEventListener('pagehide', guard, true);
                    window.addEventListener('blur', guard, true);

                    function pickArt(arr) {
                        try {
                            if (!arr || !arr.length) return '';
                            var best = arr[0], bestArea = -1;
                            for (var i = 0; i < arr.length; i++) {
                                var sz = (arr[i].sizes || '').split('x');
                                var area = (parseInt(sz[0], 10) || 0) * (parseInt(sz[1], 10) || 0);
                                if (area >= bestArea) { bestArea = area; best = arr[i]; }
                            }
                            return best.src || '';
                        } catch (e) { return ''; }
                    }
                    function ytThumb() {
                        try {
                            var m = location.href.match(/[?&]v=([\w-]{11})/) ||
                                location.href.match(/\/shorts\/([\w-]{11})/);
                            if (m) return 'https://i.ytimg.com/vi/' + m[1] + '/hqdefault.jpg';
                        } catch (e) {}
                        return '';
                    }
                    function meta() {
                        var title = '', artist = '', artwork = '';
                        try {
                            var m = navigator.mediaSession && navigator.mediaSession.metadata;
                            if (m && m.title) { title = m.title; artist = m.artist || ''; artwork = pickArt(m.artwork); }
                        } catch (e) {}
                        if (!title) title = (document.title || '').replace(/\s*-\s*YouTube$/, '');
                        if (!artwork) artwork = ytThumb();
                        return { title: title, artist: artist, artwork: artwork };
                    }
                    function isMedia(t) {
                        return t && (t.tagName === 'VIDEO' || t.tagName === 'AUDIO');
                    }
                    function report(playing) {
                        try {
                            if (!bridge) return;
                            var info = meta();
                            bridge.onState(playing, info.title, info.artist, info.artwork, durationSec || 0, positionSec || 0);
                        } catch (e) {}
                    }
                    // The JS owns the keep-alive flag: flip it the instant
                    // media starts/stops so visibility is already masked by
                    // the time the app is backgrounded (no async race with
                    // onPause). 'play'/'pause'/'ended' don't bubble, so we
                    // capture them at the document.
                    document.addEventListener('play', function(e) {
                        if (isMedia(e.target)) {
                            current = e.target;
                            keep = true;
                            playingNow = true;
                            if (e.target.duration && isFinite(e.target.duration)) durationSec = e.target.duration;
                            report(true);
                        }
                    }, true);
                    document.addEventListener('pause', function(e) {
                        if (isMedia(e.target)) {
                            keep = false;
                            playingNow = false;
                            if (e.target.currentTime) positionSec = e.target.currentTime;
                            report(false);
                        }
                    }, true);
                    document.addEventListener('ended', function(e) {
                        if (isMedia(e.target)) {
                            keep = false;
                            playingNow = false;
                            try { if (bridge) bridge.onStopped(); } catch (x) {}
                        }
                    }, true);

                    // Capture the page's own MediaSession action handlers so
                    // the notification's prev/next/seek invoke them generically.
                    try {
                        var ms = navigator.mediaSession;
                        if (ms && typeof ms.setActionHandler === 'function') {
                            var origSet = ms.setActionHandler.bind(ms);
                            ms.setActionHandler = function(action, handler) {
                                handlers[action] = handler;
                                try { origSet(action, handler); } catch (e) {}
                            };
                        }
                    } catch (e) {}

                    // SPA sites (YouTube) swap tracks without firing a fresh
                    // 'play', and populate their MediaSession metadata slightly
                    // after playback starts. Poll once a second: read live
                    // duration/position from the element and title/artist/art
                    // from the page, pushing an update only when something
                    // changed so we don't rebuild the notification every tick.
                    var lastSig = '';
                    setInterval(function() {
                        try {
                            if (!current) return;
                            if (current.duration && isFinite(current.duration)) durationSec = current.duration;
                            if (typeof current.currentTime === 'number') positionSec = current.currentTime;
                            playingNow = !current.paused;
                            var info = meta();
                            var sig = info.title + '|' + info.artist + '|' + info.artwork +
                                '|' + Math.round(durationSec) + '|' + (playingNow ? 1 : 0);
                            if (sig !== lastSig) {
                                lastSig = sig;
                                report(playingNow);
                            }
                        } catch (e) {}
                    }, 1000);

                    // Reflect in-page scrubbing onto the notification seek bar.
                    document.addEventListener('seeked', function(e) {
                        if (isMedia(e.target)) {
                            positionSec = e.target.currentTime || 0;
                            report(playingNow);
                        }
                    }, true);

                    window.__eborsSetKeepAwake = function(v) { keep = !!v; };
                    window.__eborsMediaAction = function(action, arg) {
                        try {
                            var h = handlers[action];
                            if (h) {
                                var details = { action: action };
                                if (action === 'seekto') details.seekTime = arg;
                                h(details);
                                return;
                            }
                            var el = current;
                            if (!el || !el.parentNode) { el = document.querySelector('video, audio'); }
                            if (!el) return;
                            if (action === 'play') el.play();
                            else if (action === 'pause') el.pause();
                            else if (action === 'seekto' && typeof arg === 'number') el.currentTime = arg;
                        } catch (e) {}
                    };
                } catch (e) {}
            })();
        """

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
