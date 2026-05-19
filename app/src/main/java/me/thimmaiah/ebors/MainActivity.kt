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
import com.google.android.material.behavior.HideViewOnScrollBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
// URI / IDN / Locale references previously lived in this file's URL
// helpers; those moved to [UrlInputUtils] and the imports moved with them.

/**
 * Browser shell. Owns the chrome (address bar, find-in-page, nav buttons,
 * progress, security indicator, fullscreen container) and the list of open
 * tabs.
 *
 * # Tab model
 *
 * Every browsing context is a [Tab] that owns one [ScrollAwareWebView]. The
 * activity attaches the active tab's WebView to [webContainer] and detaches
 * it when switching tabs; background tabs are kept alive (paused) in memory.
 *
 * This replaces an earlier popup-overlay design where a single static
 * `WebView` lived in the layout and "popups" were extra WebViews stacked on
 * top of it. That design caused two security bugs:
 *
 *  - **V1 (URL-bar spoofing).** `onPageStarted` / `onPageFinished` keyed off
 *    `view === webView`, so the address bar and lock icon always tracked
 *    the *main* WebView even when an overlaid popup was actually on screen.
 *    A page could open a popup to `https://attacker.example` while the
 *    user still saw `https://trustedbank.example` in the bar.
 *  - **V2 (wrong-WebView nav buttons).** Forward / refresh / home only ever
 *    drove the main WebView, never the visible popup.
 *
 * In the tab model every chrome update is sourced from `activeTabOrNull` and
 * every chrome button targets `activeTab.webView`, so V1 and V2 disappear
 * structurally rather than as a patched-over special case.
 *
 * # Permission isolation
 *
 * The original code stored in-flight WebView permission prompts in a single
 * nullable slot on the activity (`pendingWebsitePermissionRequest`,
 * `pendingGeolocationRequest`). If two pages prompted in quick succession
 * the second prompt overwrote the first — leaking the first request and
 * potentially resolving it with the second one's grant (V3).
 *
 * Now: each [Tab] holds its own pending request, and the activity tracks
 * which tab owns the *currently in-flight* runtime permission launcher via
 * [permissionInFlightTabId] / [geolocationInFlightTabId]. A new prompt that
 * arrives while one is in flight is denied immediately (with a user-facing
 * toast) rather than overwriting the slot.
 *
 * # Ad blocking parity across tabs
 *
 * Every [Tab] gets the same [BrowserBlocker] hooks (`shouldInterceptRequest`
 * on the WebViewClient, document-start scripts on YouTube origins, cosmetic
 * CSS injection on every `onPageFinished`). The service-worker network
 * blocker is registered once globally because service workers live outside
 * any individual WebView instance.
 *
 * # Autofill
 *
 * Autofill is supplied by the platform: stock `WebView` reports the
 * page's DOM form fields as a virtual view structure to Android's
 * `AutofillManager`, which routes the request to whichever
 * `AutofillService` the user has configured (Google Password Manager,
 * 1Password, Bitwarden, …). The work in this activity is the negative
 * space around that:
 *
 *  - Regular-tab WebViews are marked `IMPORTANT_FOR_AUTOFILL_YES_EXCLUDE_DESCENDANTS`
 *    so the WebView itself is the autofill anchor; its virtual
 *    children (the form fields) get classified individually by the
 *    framework using their HTML `autocomplete=` hints. We deliberately
 *    don't recurse — the page's DOM is what classifies, not the
 *    View tree below the WebView.
 *  - Private-tab WebViews are marked `IMPORTANT_FOR_AUTOFILL_NO` so
 *    the autofill service never inspects fields inside an incognito
 *    session. Matches Chrome's incognito semantics.
 *  - Chrome inputs (the address bar, find-in-page, the
 *    blocked-sites / custom-search-engine dialog inputs) are marked
 *    `importantForAutofill="no"` in their layouts so a password
 *    manager never offers to fill an "address bar" or a "search
 *    template" with a credential.
 *
 * `ScrollAwareWebView` does not override `onProvideAutofillVirtualStructure`
 * — see its class doc for why that matters.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var rootView: View
    private lateinit var addressBar: EditText
    private lateinit var addressSuggestionAdapter: AddressSuggestionAdapter
    private var addressSuggestionPopup: ListPopupWindow? = null
    private lateinit var captionView: TextView
    private lateinit var securityIndicator: ImageView
    private lateinit var webContainer: androidx.swiperefreshlayout.widget.SwipeRefreshLayout

    /**
     * Tab host. The active tab's WebView is attached here; switching
     * tabs detaches the old WebView and attaches the new one. This is
     * a plain FrameLayout living inside [webContainer] (the
     * SwipeRefreshLayout). The indirection is load-bearing —
     * SwipeRefreshLayout caches its scrollable child as `mTarget` on
     * first layout and only ever measures / lays out that cached view.
     * Swapping WebViews directly under SwipeRefreshLayout leaves the
     * cache pointing at the removed view, and the new WebView never
     * gets measured (black screen). FrameLayout has no such cache, so
     * the swap-in-place pattern works cleanly here.
     */
    private lateinit var webHost: FrameLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var goButton: ImageButton
    private lateinit var menuButton: ImageButton
    // V8: back/forward dropped from the bottom nav in favour of
    // bookmark / tabs / search / home / menu. Back is handled by the
    // system gesture (configureBackHandling); forward via the menu sheet.
    // V9: refresh moved from the (removed) bottom nav to the top app
    // bar — refreshButton lives next to the address pill, not down here.
    private lateinit var bookmarkButton: ImageButton
    private lateinit var tabsButton: FrameLayout
    private lateinit var tabCountText: TextView
    /** Small accent dot painted under the tabs icon while the
     *  tab-switcher overlay is open. Visibility toggled in
     *  [setTabSwitcherActiveIndicator]. */
    private lateinit var tabsButtonActiveDot: View
    private lateinit var searchButton: ImageButton
    private lateinit var homeButton: ImageButton
    private lateinit var refreshButton: ImageButton
    private lateinit var navigationCard: View
    private lateinit var topChrome: AppBarLayout
    private lateinit var bottomChrome: View

    // Brave-style auto-hide state. We accumulate scroll deltas in one
    // direction so a brief jitter doesn't toggle the chrome — only a
    // deliberate scroll of more than a few dp counts. The animation
    // gate suppresses incoming scroll events while the chrome is
    // collapsing / expanding, otherwise the WebView's transient
    // re-layout would feed a small opposite delta back to us and the
    // bars would spring back.
    private var chromeHidden = false
    private var scrollDirAccumPx = 0
    private var chromeAnimGateUntilMs = 0L

    /**
     * Set from JS (via [RefreshGuardBridge]) when the user's finger is
     * down on an element that scrolls / pages on its own — e.g. a
     * YouTube Shorts swipe-paging container or any element with
     * 
     * `overscroll-behavior: contain | none`. The SwipeRefreshLayout's
     * OnChildScrollUpCallback reads this on the UI thread, so the
     * volatile-write / volatile-read pair is enough; no extra lock.
     */
    @Volatile
    private var refreshSuppressedByJs: Boolean = false

    /**
     * Whether the foreground tab is currently on a YouTube Shorts URL.
     * Driven by [ShortsLayoutBridge.setShortsLayoutActive] (which the
     * injected [YOUTUBE_SHORTS_LAYOUT_SCRIPT] calls on every detected
     * URL transition). Only UI-thread reads/writes — no `@Volatile`.
     * When true, `web_container` has a bottom margin equal to the
     * bottom-chrome height so the WebView's viewport sits cleanly above
     * the nav bar instead of behind it.
     */
    private var shortsLayoutActive: Boolean = false

    private lateinit var findBar: View
    private lateinit var findInput: EditText
    private lateinit var findCount: TextView
    private lateinit var findPrev: ImageButton
    private lateinit var findNext: ImageButton
    private lateinit var findClose: ImageButton

    /**
     * Debounce plumbing for the find-in-page input. We debounce so a
     * fast typist doesn't trigger a `findAllAsync` per keystroke — each
     * one cancels the previous in the WebView and produces a brief
     * flash through "no matches" before the new query lands.
     */
    private val findDebounceHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var findDebounceRunnable: Runnable? = null

    /** Open tabs in z-order. The active tab is at [activeTabIndex] and its
     *  WebView is attached to [webContainer]. */
    private val tabs = mutableListOf<Tab>()
    private var activeTabIndex: Int = -1
    private var tabSwitcherView: TabSwitcherView? = null

    /**
     * v10 start-page controller. Shown whenever the active tab's
     * display URL is [ABOUT_HOME_URL] (the user hasn't set a custom
     * homepage in Settings); hidden whenever a real URL is loaded.
     * Initialised once at bind time against the `start_page_overlay`
     * include in `activity_main.xml`.
     */
    private var startPageView: StartPageView? = null

    /** v10 private start-page controller. Distinct overlay + content
     *  from [startPageView]; MainActivity swaps which is visible based
     *  on the active tab's `isPrivate` flag. */
    private var privateStartPageView: PrivateStartPageView? = null

    /**
     * Tab whose runtime permission launcher is currently waiting on an
     * Android grant. New prompts that arrive while this is non-null are
     * denied immediately rather than overwriting the in-flight slot. See
     * the V3 commentary in [handleWebsitePermissionRequest].
     */
    private var permissionInFlightTabId: String? = null
    private var geolocationInFlightTabId: String? = null

    // HTML5 fullscreen state (e.g. YouTube tap-to-fullscreen). Populated when
    // a page calls Element.requestFullscreen() and WebChromeClient hands us a
    // custom view to host the video.
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var fullscreenSavedOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    private lateinit var prefs: BrowserPreferences
    private var lastDesktopMode: Boolean = false
    private var lastForceDark: Boolean = false
    private var lastBlockWebRtc: Boolean = false
    private var lastTrimReferrer: Boolean = false

    private val activeTabOrNull: Tab? get() = tabs.getOrNull(activeTabIndex)

    /**
     * True when this device's WebView supports the AndroidX webkit
     * Profile API (WebView 121+, shipped early 2024). Profile is what
     * gives private tabs a real isolated cookie jar + storage, so the
     * "New private tab" menu item is hidden when this is false rather
     * than offering a fake-private mode where cookies still leak into
     * the default session.
     *
     * Stable for the lifetime of the process — WebView updates require
     * a relaunch — so we cache the value once.
     */
    private val multiProfileSupported: Boolean by lazy {
        WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
    }

    /** Convenience that throws on misuse; only call from sites that have
     *  already verified at least one tab exists (the back handler, button
     *  click listeners after the initial tab has been opened, etc.). */
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
            // V3: resolve the result against the *originating* tab, not
            // whichever tab happens to be active when the launcher returns.
            // Without the tab-id routing a user who tab-switched while the
            // Android prompt was on screen could end up granting the wrong
            // page's camera/mic request.
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
                if (tab === activeTabOrNull) {
                    showToast(getString(R.string.website_permission_denied))
                }
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
            if (!granted && tab === activeTabOrNull) {
                showToast(getString(R.string.location_denied))
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                showToast(getString(R.string.download_notification_permission_denied))
            }
        }

    /**
     * The [android.webkit.ValueCallback] from the most recently
     * launched <input type="file"> picker. Cleared as soon as we
     * post a result (or null) back to WebView — leaving it set
     * across a second picker would leak the previous WebView's
     * callback and starve the form from receiving its result.
     *
     * Only one chooser is in flight at a time: WebView is required
     * to call our `onShowFileChooser` synchronously from the user's
     * tap, and Android only shows one Activity-result chooser at a
     * time anyway, so we don't need a queue.
     */
    private var pendingFileChooserCallback: android.webkit.ValueCallback<Array<android.net.Uri>>? = null

    /**
     * System file picker launcher for <input type="file">. Returns
     * one or more Uris (depending on the page-side `multiple`
     * attribute we passed via `EXTRA_ALLOW_MULTIPLE`). On cancel /
     * empty result we post `null` so WebView clears the form's
     * "loading" state and the user can try again. Posting `null`
     * is also what Chrome does on cancel.
     */
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

    /** Accent that was active when this activity was last (re)created.
     *  onResume compares against [BrowserPreferences.accentKey] and
     *  recreate()s the activity if they diverge — that's how a tap in
     *  Settings → Accent flips the live UI's colour. */
    private var boundAccentKey: String = AccentTheme.DEFAULT.key

    override fun onCreate(savedInstanceState: Bundle?) {
        // BrowserPreferences first — we need it before super.onCreate
        // so applyAccentTheme can pick the right Theme.Ebors
        // overlay before any layout inflation happens. enableEdgeToEdge
        // also has to land before setContentView, so the order below is
        // deliberate.
        val prefs = BrowserPreferences.from(this)

        // First-launch gate. If the user hasn't completed onboarding
        // (welcome → privacy promise → terms acceptance), hand off to
        // WelcomeActivity *before* any of the heavy initialisation
        // below runs. We deliberately don't call super.onCreate /
        // setContentView in that branch — the only thing the user
        // should see on a fresh install is the welcome flow.
        //
        // Done before applyAccentTheme too so we don't pay theme
        // inflation cost on a launch that's about to be torn down.
        if (!prefs.onboardingCompleted) {
            super.onCreate(savedInstanceState)
            startActivity(
                Intent(this, WelcomeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
            finish()
            return
        }

        applyAccentTheme(prefs)
        boundAccentKey = prefs.accentKey

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        this.prefs = prefs
        // Initialise lastDesktopMode/lastForceDark from prefs so the first
        // applyPreferences() pass doesn't see a phantom "preference changed"
        // transition and reload every tab on cold start. (B9 from the audit.)
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
        // Parse the block list now (network cache if present + new
        // enough, otherwise the bundled assets/blocklist.json), off the
        // first-page-load critical path and before any WebView exists.
        // Idempotent — safe if another entry point also reaches it.
        BrowserBlocker.initialize(applicationContext)
        // Opportunistic, rate-limited, fully silent network refresh of
        // the block list. No-ops unless the build has an update source
        // configured AND the user left auto-update on. A successful
        // download calls BrowserBlocker.reload() itself, so the new
        // rules apply to the next request without a restart.
        if (prefs.blocklistAutoUpdate) {
            BlocklistUpdater.checkForUpdate(applicationContext, force = false)
        }
        BlockedSitesRepository.initialize(applicationContext)

        // Wipe any leftover private profile from a previous run before
        // the first WebView gets built. If the process died with
        // private tabs open, their profile is still on disk; clearing
        // here guarantees a fresh isolated state for any new private
        // tab opened this run. We never restore private tabs across
        // process death (onSaveInstanceState skips them), so this is
        // always safe.
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
        // External browseable intents open in a *new* tab so the user's
        // existing browsing context isn't clobbered by another app's deep
        // link. Matches Chrome's "open in browser" behaviour.
        openNewTab(url = url, switchTo = true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        // Private tabs are deliberately excluded from saved state so
        // they vanish on process death — matching Chrome's incognito
        // semantics. (The activity manifest's android:configChanges
        // already covers rotation, so private tabs survive rotation
        // within an activity instance.) Doing this with a filtered
        // list means we have to translate the active index too: if the
        // active tab was private, the restored "active" defaults to
        // the first surviving regular tab.
        val urls = ArrayList<String>(tabs.size)
        var restoredActiveIndex = 0
        for ((i, tab) in tabs.withIndex()) {
            if (tab.isPrivate) continue
            if (i == activeTabIndex) restoredActiveIndex = urls.size
            urls.add(tab.webView.url ?: tab.displayUrl)
        }
        outState.putStringArrayList(STATE_TAB_URLS, urls)
        outState.putInt(STATE_ACTIVE_TAB_INDEX, restoredActiveIndex)

        // Save the active tab's full WebView state so its back/forward
        // history survives process death. Background tabs only persist
        // their last URL — they reload from network when activated. This
        // is the same compromise Chrome makes for low-priority tabs and
        // keeps the saved-state bundle small enough to avoid
        // TransactionTooLargeException on devices with many open tabs.
        //
        // Skip the active-tab state bundle entirely if the active tab
        // is private: persisting its back/forward stack would leak the
        // private session across process death.
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
            // openNewTab(switchTo=false) parks the tab as a paused,
            // unattached WebView. Loading or restoring state on such a
            // WebView leaves it in a half-rendered state once the user
            // switches to it. Instead, defer the load via
            // [Tab.pendingLoadUrl]: switchToTab consumes it on first
            // attach. For the saved active tab we still call
            // restoreState immediately (history is needed before the
            // tab is foregrounded so the back-stack is correct), and
            // skip the deferred load — restoreState already arranges
            // for the current page to reload on attach.
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
        // pauseTimers is class-wide — it pauses JS timers in *every*
        // WebView the process owns. Calling it once on any instance is
        // enough; calling it per-tab would be redundant.
        if (tabs.isNotEmpty()) tabs[0].webView.pauseTimers()
        for (tab in tabs) tab.webView.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        // v10 accent picker: Settings can change the chosen accent while
        // MainActivity sits in the back stack. Re-apply by recreate()
        // so the new theme drives every drawable / layout resource.
        if (prefs.accentKey != boundAccentKey) {
            recreate()
            return
        }
        if (tabs.isNotEmpty()) tabs[0].webView.resumeTimers()
        // Only the active tab gets onResume(); background tabs stay
        // per-WebView paused. This deliberately diverges from the old
        // single-WebView behaviour because resuming every tab on
        // foregrounding would let off-screen pages drain battery on
        // animations and video posters the user can't see.
        activeTabOrNull?.webView?.onResume()
        applyPreferences()
        // v10: when the user returns from Bookmarks / Downloads /
        // Settings to a tab that's sitting on about:home, repaint
        // the start page so any bookmark / history / tracker count
        // changes made on those screens land in the Pinned + Continue
        // reading + Trackers sections.
        if (startPageView?.isShowing() == true) startPageView?.refresh()
    }

    override fun onDestroy() {
        exitFullscreen()
        permissionInFlightTabId = null
        geolocationInFlightTabId = null
        // Drop any pending find-in-page debounce so the runnable doesn't
        // fire after the WebView is destroyed and reach through
        // activeTabOrNull to call findAllAsync on a torn-down view.
        findDebounceHandler.removeCallbacksAndMessages(null)
        findDebounceRunnable = null
        val hadPrivateTabs = tabs.any { it.isPrivate }
        // Detach the active tab's WebView from the container *before*
        // destroying. The platform docs are explicit: "destroy() should
        // be called after this WebView has been removed from the view
        // system" — on some OEM builds calling destroy while attached
        // throws ("WebView is not attached to a window" notwithstanding,
        // the underlying chromium glue checks parent != null). The
        // previous loop went straight to destroy() and only worked
        // because most WebView versions are forgiving.
        val activeTab = activeTabOrNull
        if (activeTab != null) {
            webHost.removeView(activeTab.webView)
        }
        // Destroy in reverse-insertion order so any callbacks fire in a
        // predictable sequence relative to anything else that holds a
        // reference into the tab list.
        for (tab in tabs.reversed()) {
            tab.destroy()
        }
        tabs.clear()
        activeTabIndex = -1
        tabSwitcherView?.dismiss()
        tabSwitcherView = null
        // After all WebViews are destroyed it's safe to delete the
        // private profile. We do this on every onDestroy that saw at
        // least one private tab — including config-change destroys
        // we don't expect (configChanges in the manifest covers
        // orientation, but Android can still recreate the activity
        // under memory pressure). The cold-start path also wipes, so
        // double-deletion is fine (the helper is idempotent).
        if (hadPrivateTabs) {
            wipePrivateProfileIfAny()
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------------
    // Tab management
    // ---------------------------------------------------------------------

    /**
     * Create a tab and append it to the list.
     *
     * The new tab's WebView gets the full ad-blocker / cosmetic-CSS /
     * YouTube-pruning pipeline applied so feature parity is preserved
     * regardless of how the tab was opened (user pressed "New tab", a page
     * called window.open(), or process restoration replayed a saved URL).
     *
     * @param url URL to load, or null to leave the WebView blank — used by
     *   the JS `window.open()` path where the parent page injects the URL
     *   via [WebView.WebViewTransport].
     * @param switchTo whether to bring the new tab to the foreground.
     * @param isPrivate when true, bind the WebView to the shared
     *   "incognito" Profile so cookies / storage / cache are isolated
     *   from the default session, and skip history recording. Caller
     *   must have already verified [multiProfileSupported].
     */
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
        // The Profile binding MUST happen before any load on this
        // WebView — `WebViewCompat.setProfile` documents this — so we
        // do it here, immediately after construction, ahead of
        // configureWebViewForTab. If binding fails we fail closed: the
        // requested private navigation is not loaded in the default
        // profile, because that would quietly turn a private action into
        // a regular browsing-session write.
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

        // Lifecycle ordering matters here. The previous implementation
        // called `newWebView.onPause()` *before* `loadUrl`, even for
        // tabs that were about to be switched to the foreground. That
        // tripped a real-world bug for background tabs: calling
        // `loadUrl` on a paused, never-attached WebView leaves the
        // initial fetch in a half-state on some WebView versions, and
        // by the time the user later switches to the tab the page is
        // either blank or rendered with the wrong viewport (the
        // WebView's viewport metrics are zero until it's been attached
        // to a window and laid out at least once). Two distinct paths
        // now:
        //
        //  * switchTo = true → leave the WebView resumed and let
        //    switchToTab() attach + load. Matches "tap a tab" UX.
        //  * switchTo = false → still pause the background WebView so
        //    it doesn't burn cycles, but defer the load until the tab
        //    is first activated. The displayUrl is stashed on the tab
        //    so the tab switcher and the activation path can recover
        //    it; switchToTab consults `pendingLoadUrl` and dispatches
        //    a single loadUrl on first attach.
        if (!url.isNullOrBlank()) {
            tab.displayUrl = url
        }
        // about:home is not a real URL — the WebView never loads it,
        // and on activation switchToTab paints the start-page overlay
        // on top of the (empty) WebView instead.
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
        // Refresh the bottom-nav tab-count overlay. switchToTab already
        // calls this for the foreground case; background-opened tabs
        // (long-press "open in new tab") still need to bump the count.
        updateNavigationButtons()
        return tab
    }

    /**
     * Make the tab at [index] the foreground tab. Detaches the old active
     * tab's WebView, attaches the new one, and resyncs every piece of
     * chrome UI (address bar, lock, nav buttons, find bar, scroll-hide
     * accumulator) against the new tab.
     */
    private fun switchToTab(index: Int) {
        val target = tabs.getOrNull(index) ?: return
        // True re-attach short-circuit. webHost is the FrameLayout that
        // owns the visible WebView slot — checking childCount on the
        // SwipeRefreshLayout would always be ≥ 1 because of its built-in
        // CircleImageView spinner, so we ask the FrameLayout directly
        // whether the target WebView is currently its child.
        if (index == activeTabIndex && webHost.indexOfChild(target.webView) >= 0) {
            return
        }
        val previousActive = if (activeTabIndex in tabs.indices) tabs[activeTabIndex] else null

        previousActive?.let {
            // v10.1: capture the WebView's current pixels as a tab-
            // switcher thumbnail *before* detaching. The WebView is
            // still attached and laid out at this point, so
            // `webView.draw(canvas)` paints the actual visible content;
            // a moment later we remove the view and the surface goes
            // blank. captureThumbnail is a no-op on private tabs.
            it.captureThumbnail()
            webHost.removeView(it.webView)
            it.webView.onPause()
        }

        webHost.addView(target.webView)
        target.webView.onResume()
        activeTabIndex = index

        // First activation of a tab opened in the background: we
        // deferred the network fetch so the WebView wasn't loading on a
        // detached, paused instance. Now that it's attached and
        // resumed, dispatch the load. consumeAndClear-style: one shot.
        target.pendingLoadUrl?.let { deferredUrl ->
            target.pendingLoadUrl = null
            target.webView.loadUrl(deferredUrl)
        }

        // v10: about:home tabs paint the start-page overlay instead of
        // a real WebView page. Toggle the overlay before address-bar /
        // chrome refresh so updateAddressBar sees a coherent state.
        val targetIsHome = target.displayUrl == ABOUT_HOME_URL
        if (targetIsHome) {
            showStartPage()
        } else {
            hideStartPage()
        }

        // Address bar / lock / nav now reflect the visible tab. The previous
        // overlay-popup design's V1 spoofing surface is gone because every
        // chrome update is sourced from the foreground tab.
        if (targetIsHome) {
            addressBar.setText("")
            updateSecurityIndicator(null)
        } else {
            updateAddressBar(target.webView.url ?: target.displayUrl.takeIf { it.isNotBlank() })
        }
        renderBrowserCaption(SearchEngineResolver.displayName(prefs), target.isPrivate)
        updateNavigationButtons()
        // Find-in-page closes on tab switch — the query rarely makes sense
        // against a different page and the find state isn't migrated.
        hideFindBar()
        scrollDirAccumPx = 0
        setChromeHidden(false)
        // The suppress-refresh flag belongs to whichever WebView was
        // being touched in the *previous* tab. Switching tabs means we
        // start fresh: pull-to-refresh allowed until the new tab's
        // injected guard script says otherwise.
        refreshSuppressedByJs = false
        // Re-evaluate the Shorts inset for the new foreground tab. The
        // foreground-tab gating inside ShortsLayoutBridge means the
        // previous tab can't push state for us during this transition,
        // so we set it ourselves based on the new active tab's URL.
        // The new tab's own poller will keep the state in sync from
        // here on out.
        val newUrl = target.webView.url.orEmpty()
        applyShortsLayout(isYouTubeHost(newUrl) && newUrl.contains("/shorts/"))
    }

    /**
     * Close the tab at [index], destroying its WebView and any in-flight
     * permission prompt it owned. If the active tab is closed, the
     * neighbouring tab (preferring the right) becomes active. If the last
     * tab is closed, the activity finishes.
     */
    private fun closeTab(index: Int) {
        val tab = tabs.getOrNull(index) ?: return
        val wasActive = index == activeTabIndex
        val wasPrivate = tab.isPrivate

        if (wasActive) {
            webHost.removeView(tab.webView)
        }
        tabs.removeAt(index)
        tab.destroy()

        // If this was the last private tab, wipe the entire private
        // profile so nothing survives. ProfileStore.deleteProfile
        // throws if the profile is still in use by a WebView — which
        // is why this runs *after* tab.destroy() above. We can only
        // delete after every WebView bound to the profile has been
        // destroyed.
        if (wasPrivate && tabs.none { it.isPrivate }) {
            wipePrivateProfileIfAny()
        }

        if (tabs.isEmpty()) {
            // Match Chrome on Android: closing the last tab finishes the
            // activity rather than sitting on an empty container.
            finish()
            return
        }

        if (wasActive) {
            val newIndex = index.coerceAtMost(tabs.size - 1)
            // Force switchToTab to re-attach by clearing the active index.
            activeTabIndex = -1
            switchToTab(newIndex)
        } else if (index < activeTabIndex) {
            activeTabIndex--
        }

        if (tabSwitcherView?.isShowing() == true) {
            tabSwitcherView?.refresh(buildTabSnapshots(), activeTabIndex)
        }
        // Tab-count overlay must reflect the new size. switchToTab covers
        // the wasActive path; the wasn't-active path needs an explicit
        // refresh here.
        updateNavigationButtons()
    }

    /**
     * Best-effort delete of the shared private profile and everything
     * inside it (cookies, localStorage, IndexedDB, cache).
     *
     * Throws under the [ProfileStore.deleteProfile] contract if any
     * WebView is still bound — we catch that and log, since the only
     * legitimate caller paths run after every private WebView has
     * been destroyed (closeTab, onDestroy) or before any have been
     * created (onCreate cold-start). A throw here would indicate a
     * real ordering bug; the log makes that surfaceable.
     */
    private fun wipePrivateProfileIfAny() {
        if (!multiProfileSupported) return
        try {
            val store = ProfileStore.getInstance()
            // getAllProfileNames is cheap and the only way to ask
            // "does a profile by this name exist?" without creating
            // it as a side effect.
            if (PRIVATE_PROFILE_NAME in store.allProfileNames) {
                store.deleteProfile(PRIVATE_PROFILE_NAME)
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to delete private profile", e)
        }
    }

    /**
     * Reveal the v10 paper-theme tab switcher overlay over the
     * WebView area. Hides the top chrome (the overlay paints its own
     * title row) and flips the bottom-nav tabs button into its
     * active state (accent badge + dot) so the user can tell at a
     * glance which surface they're on.
     *
     * Show is idempotent: a second tap on the tabs button while the
     * switcher is already up dismisses it (matches how Chrome /
     * Brave's tab buttons behave).
     */
    private fun showTabSwitcher() {
        val view = tabSwitcherView ?: return
        if (view.isShowing()) {
            view.dismiss()
            return
        }
        // v10.1: capture a fresh thumbnail of the *currently active*
        // tab before its WebView is occluded by the overlay. switchToTab
        // covers the "user switches away" case; this covers the "user
        // opens the switcher straight from the active tab" case.
        // captureThumbnail is a no-op on private tabs.
        activeTabOrNull?.captureThumbnail()
        // The overlay sits between web_container and bottom_chrome in
        // the CoordinatorLayout child order so bottom_chrome stays
        // visually on top. Add runtime bottom padding equal to the
        // bottom chrome height so the last grid row never hides under
        // the nav.
        bottomChrome.post {
            view.setBottomInset(bottomChrome.height)
        }
        // Hide top chrome (address bar + progress) while the switcher
        // is up — the switcher paints its own "N open tabs" header.
        topChrome.isVisible = false
        // Active-state marker on the bottom nav.
        setTabSwitcherActiveIndicator(active = true)
        view.show(buildTabSnapshots(), activeTabIndex)
    }

    /**
     * Toggle the active-state visual on the bottom-nav tabs button:
     *  - accent-filled badge (instead of the muted surface chip)
     *  - small accent dot directly under the icon
     */
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

    /** Listener handed to [StartPageView]. Centralises navigation
     *  away from the start page (tile clicks, search-pill focus, edit
     *  / history shortcuts, trackers card) so the activity keeps
     *  control of the tab-state transitions. */
    private val startPageListener = object : StartPageView.Listener {
        override fun onStartPageUrlTapped(url: String) {
            loadAddress(url)
        }

        override fun onStartPageQuerySubmitted(query: String) {
            // Same flow the top address bar uses: a typed string can be
            // either a URL or a search query — resolveUserInput
            // disambiguates. Blank submits go to homeUrl() (which is
            // ABOUT_HOME_URL by default), keeping the user on the
            // start page rather than navigating somewhere weird.
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

    /** Listener for the v10 private start page. Same shape as the
     *  regular start page listener but only the URL / submit hooks
     *  apply — the private surface has neither a Pinned editor nor a
     *  history shortcut. */
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

    /** Listener handed to [TabSwitcherView]. Implemented as an inner
     *  object so the activity stays the single source of truth for
     *  tab-management invariants. */
    private val tabSwitcherListener = object : TabSwitcherView.Listener {
        override fun onSwitchToTab(id: String) = switchToTabById(id)
        override fun onCloseTab(id: String) = closeTabById(id)
        override fun onNewTab(isPrivate: Boolean) {
            openNewTab(url = homeUrl(), switchTo = true, isPrivate = isPrivate)
        }
        override fun onSwitcherClosed() {
            // Restore the top chrome and clear the active-state
            // marker. Done in the host (not the view) because the top
            // chrome is the activity's concern.
            topChrome.isVisible = true
            setTabSwitcherActiveIndicator(active = false)
        }
    }

    /**
     * Dispatch a tab-switcher "switch to" event by stable [Tab.id].
     * Routing on id (rather than the row's list position) means a
     * rebind-in-flight can't make the wrong tab activate when the user
     * taps after a sibling row was closed.
     */
    private fun switchToTabById(id: String) {
        val index = tabs.indexOfFirst { it.id == id }
        if (index >= 0) switchToTab(index)
    }

    /** id-based counterpart to [closeTab]; see [switchToTabById]. */
    private fun closeTabById(id: String) {
        val index = tabs.indexOfFirst { it.id == id }
        if (index >= 0) closeTab(index)
    }

    private fun buildTabSnapshots(): List<TabSwitcherView.TabSnapshot> {
        return tabs.map { tab ->
            // Title fallback chain handles background tabs whose
            // WebView has never rendered: displayTitle is set by
            // onReceivedTitle (page-load time), and tab.webView.title
            // is null/empty before first attach. For a still-loading
            // background tab we fall through to the URL — and only
            // ultimately to the localised "New tab" placeholder inside
            // the switcher (see TabSwitcherView.bind), so the row is
            // never visually empty.
            val displayTitle = tab.displayTitle.ifBlank { tab.webView.title.orEmpty() }
            val displayUrl = tab.displayUrl.ifBlank { tab.webView.url.orEmpty() }
                .ifBlank { tab.pendingLoadUrl.orEmpty() }
            TabSwitcherView.TabSnapshot(
                id = tab.id,
                title = displayTitle,
                url = displayUrl,
                isPrivate = tab.isPrivate,
                // v10.1: live tab-card preview. Null until captureThumbnail
                // has run for this tab (switchToTab on detach, or
                // showTabSwitcher for the active tab). The card layout
                // falls back to the skeleton placeholder for nulls.
                thumbnail = tab.thumbnail,
            )
        }
    }

    // ---------------------------------------------------------------------
    // View binding / chrome configuration
    // ---------------------------------------------------------------------

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

        // v10 tab-switcher overlay. Constructed once at bind time
        // because its constructor caches lookups against the overlay's
        // children — handing it a freshly inflated overlay on every
        // show would rebuild those each time for no benefit.
        tabSwitcherView = TabSwitcherView(
            context = this,
            overlay = findViewById(R.id.tab_switcher_overlay),
            listener = tabSwitcherListener,
        )

        // v10 start-page (about:home). Same one-time-bind pattern.
        startPageView = StartPageView(
            context = this,
            overlay = findViewById(R.id.start_page_overlay),
            listener = startPageListener,
            prefs = prefs,
        )

        // v10 private start page (about:home for incognito tabs).
        // Separate controller because the layout and content diverge
        // from the regular surface enough that conditional binding
        // would be noisier than a second binding.
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
            }
        }

        // V8: bottom nav redesign. Bookmark / tabs / search / home / menu.
        // Every chrome button still operates on the active tab via
        // activeTabOrNull, matching the V2 contract.
        //
        // v10: every chrome button that takes the user *out of* the tab
        // switcher first dismisses the overlay. Without this the
        // overlay would still paint on top of the WebView and the
        // chrome the user actually navigated to (suggestion list,
        // bookmarks activity, home page) would look like it overlaps.
        bookmarkButton.setOnClickListener {
            tabSwitcherView?.dismiss()
            openLibraryLauncher.launch(Intent(this, BookmarksActivity::class.java))
        }

        tabsButton.setOnClickListener {
            showTabSwitcher()
        }

        // Tapping search behaves like the equivalent button in
        // Chrome/Brave: it focuses the address bar and pops the keyboard
        // so the user can type a query or URL straight away.
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

        // V9: top-bar refresh. No-ops when no tab is attached — the
        // alpha update in updateNavigationButtons makes that state
        // visible to the user.
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

        // Task 7: stretch the dropdown to the available screen width
        // (minus a small horizontal gutter) instead of clamping to the
        // address-pill's width. Looks heavier visually but matches the
        // prototype and gives long URLs / titles room to render
        // without truncation.
        val anchor = addressSuggestionAnchor()
        val gutter = dp(8)
        val screenWidth = rootView.width.takeIf { it > 0 } ?: anchor.width
        val targetWidth = (screenWidth - gutter * 2).coerceAtLeast(dp(260))
        // Negative horizontalOffset shifts the popup left of the anchor
        // by the difference between the anchor and the popup, then
        // gutter shifts it back to leave breathing room at the screen
        // edge. Computed against the anchor's on-screen position so a
        // re-anchor (e.g. after the AppBar collapses) stays aligned.
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
                // Always cancel the pending debounce so a rapid backspace
                // doesn't trigger a stale findAllAsync after the user
                // has already cleared the field.
                findDebounceRunnable?.let(findDebounceHandler::removeCallbacks)
                if (query.isEmpty()) {
                    tab.webView.clearMatches()
                    findCount.text = ""
                    return
                }
                // Clear the previous count immediately so the user
                // doesn't see a stale "12 / 47" while they're typing the
                // next query — the count repopulates when findAllAsync
                // returns. Then schedule the real search 150 ms later;
                // that's about the longest gap that still feels
                // responsive while killing the chatty per-keystroke
                // re-search cost.
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
        // FindListener is installed per-tab inside configureWebViewForTab
        // so results land on the right WebView even if the user manages to
        // switch tabs mid-search. (hideFindBar runs on tab switch anyway,
        // so the listener almost always sees its own tab as active.)
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
    }

    private fun hideFindBar() {
        if (!findBar.isVisible) return
        findBar.isVisible = false
        activeTabOrNull?.webView?.clearMatches()
        findCount.text = ""
        getSystemService<InputMethodManager>()
            ?.hideSoftInputFromWindow(findInput.windowToken, 0)
    }

    /**
     * Wire the pull-to-refresh gesture on the active tab. We reload the
     * tab; the spinner is dismissed from [WebViewClient.onPageFinished]
     * / `onReceivedError` so the user sees the gesture has registered
     * but doesn't get a spinner stuck on a tab that never finishes
     * loading (which would block scroll).
     *
     * Note: the SwipeRefreshLayout child is *whichever* WebView is
     * attached by switchToTab — it can be null briefly during a tab
     * switch. The OnRefreshListener no-ops in that case.
     *
     * V8: in addition to the refresh trigger we install
     * [SwipeRefreshLayout.setOnChildScrollUpCallback] so the pull
     * gesture is suppressed whenever the active page is mid-scroll OR
     * the user is touching a JS-detected nested-scroll element (YouTube
     * Shorts swipe-paging, image carousels, in-place chat panels…).
     * Without this gate a downward swipe inside a nested scroller fires
     * a full-page reload, which is the bug we used to hit on Shorts.
     */
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
        // Returning true means "child can scroll up, don't refresh". The
        // default impl just calls View.canScrollVertically(-1) on the
        // child, which is unreliable on WebView (the same dispatch
        // weakness ScrollAwareWebView's class comment warns about).
        webContainer.setOnChildScrollUpCallback { _, _ ->
            val webView = activeTabOrNull?.webView
            // No tab attached → behave like the platform default and
            // allow the refresh gesture (the listener above no-ops it).
            if (webView == null) return@setOnChildScrollUpCallback false
            // scrollY > 0 means the page has been scrolled away from
            // its top, so a downward swipe is just "scroll up", not
            // "pull to refresh". refreshSuppressedByJs covers the
            // page-still-at-top-but-inside-a-carousel case via the
            // injected touchstart listener (see REFRESH_GUARD_SCRIPT).
            webView.scrollY > 0 || refreshSuppressedByJs
        }
    }

    /**
     * JS → native bridge used only by [REFRESH_GUARD_SCRIPT]. The page
     * cannot reach `@Volatile` Kotlin state directly; this thin shim
     * mirrors a single boolean across the JS / native boundary.
     *
     * Only the `@JavascriptInterface`-annotated method is reachable
     * from page JS — that's enforced by WebView since API 17, so an
     * embedded ad iframe can't surprise us by calling something else.
     * The worst a hostile page can do via this surface is permanently
     * suppress our pull-to-refresh gesture, which is annoying but not
     * a security concern.
     */
    private inner class RefreshGuardBridge {
        @JavascriptInterface
        fun setSuppressed(suppressed: Boolean) {
            refreshSuppressedByJs = suppressed
        }
    }

    /**
     * JS → native bridge used only by [YOUTUBE_SHORTS_LAYOUT_SCRIPT].
     * Owns a [Tab] reference so that **only the foreground tab** can
     * mutate the activity-level [shortsLayoutActive] — a background
     * tab that flushes a `setShortsLayoutActive(true)` between
     * `onPause()` calls won't shrink the foreground tab's viewport.
     * That gating is what makes this method safe for the "doesn't
     * affect other modules" guarantee.
     */
    private inner class ShortsLayoutBridge(private val tab: Tab) {
        @JavascriptInterface
        fun setShortsLayoutActive(active: Boolean) {
            // JS-interface methods are called from a non-UI thread.
            // Layout mutation must hop to the main thread; the
            // active-tab check is also done there so it sees a coherent
            // `activeTabOrNull` snapshot.
            runOnUiThread {
                if (tab === activeTabOrNull) {
                    applyShortsLayout(active)
                }
            }
        }
    }

    private fun isYouTubeHost(url: String): Boolean {
        return try {
            val host = Uri.parse(url).host?.lowercase() ?: return false
            host == "youtube.com" || host == "m.youtube.com" ||
                host.endsWith(".youtube.com")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Wire the global service-worker network blocker. Service workers run
     * outside any individual WebView instance — this needs to be set up
     * once per process rather than per tab.
     */
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

    // ---------------------------------------------------------------------
    // Per-tab WebView configuration
    // ---------------------------------------------------------------------

    /**
     * Apply ad-blocker, cosmetic CSS, YouTube response-pruning, force-dark,
     * cookie policy, security settings, and all chrome callbacks to a tab's
     * WebView.
     *
     * Every callback closes over the owning [Tab] and gates chrome updates
     * on `tab === activeTabOrNull`. This is what closes V1 (URL bar
     * spoofing) by construction: only the foreground tab can update the
     * address bar / lock icon / progress bar, regardless of how many other
     * tabs are concurrently loading.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebViewForTab(tab: Tab) {
        val target = tab.webView
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(target, prefs.thirdPartyCookies)

        target.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            // Only the active tab can pop a download dialog. A page running
            // in a background tab can't surprise the user with a "do you
            // want to download foo.exe?" prompt out of sight. The site can
            // re-issue the download once the tab is foregrounded.
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

        // Pull-to-refresh guard. The bridge is the only path the
        // injected REFRESH_GUARD_SCRIPT has back into native code; it
        // toggles refreshSuppressedByJs which the SwipeRefreshLayout's
        // OnChildScrollUpCallback consults on every gesture. We bind
        // it under a deliberately ugly name (REFRESH_GUARD_BRIDGE_NAME)
        // to avoid colliding with any `window.bridge`-style globals a
        // site might define.
        target.addJavascriptInterface(RefreshGuardBridge(), REFRESH_GUARD_BRIDGE_NAME)
        // Shorts layout adapter. Bridge is added on every tab but the
        // companion script (YOUTUBE_SHORTS_LAYOUT_SCRIPT) is only
        // injected on YouTube hosts, so non-YouTube pages don't have a
        // caller for it — the surface is dormant on every other site.
        target.addJavascriptInterface(
            ShortsLayoutBridge(tab),
            SHORTS_LAYOUT_BRIDGE_NAME,
        )

        // Long-press context menu for links + images. We deliberately
        // *don't* register a fallback for plain text / phone / email
        // hit-test results — by returning without adding any menu
        // items the framework falls through to WebView's built-in
        // selection / dialer / mail-compose behaviour, which is what
        // users expect when long-pressing those targets.
        target.setOnCreateContextMenuListener { menu, _, _ ->
            val result = target.hitTestResult
            val url = result.extra
            when (result.type) {
                WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                    if (!url.isNullOrBlank()) addLinkContextMenuItems(menu, url)
                }

                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                    // Image inside an anchor. `extra` is the anchor's
                    // href, which is what addLinkContextMenuItems needs;
                    // the image src is fetched separately via
                    // WebView.requestImageRef when the user picks
                    // "Save image". Keeping both sets of actions in this
                    // menu matches how Chrome / Firefox handle the same
                    // hit-test type.
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
            // V6: default-deny passive mixed content. WebView's default is
            // COMPATIBILITY_MODE which allows HTTPS pages to load HTTP
            // images and posters — flagged as too permissive for a
            // privacy-leaning browser. Users can opt back in via Settings
            // → "Allow mixed content" if a legacy site breaks.
            mixedContentMode = if (prefs.allowMixedContent) {
                WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            } else {
                WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            safeBrowsingEnabled = true
            loadsImagesAutomatically = true
            mediaPlaybackRequiresUserGesture = true
            displayZoomControls = false
            builtInZoomControls = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setGeolocationEnabled(true)
            userAgentString = effectiveUserAgent()
            // Private tabs: never offer to save form data. The setting
            // is deprecated on modern WebView but still respected by
            // many WebChromeClient internals (and by the Autofill
            // bridge when it's present), so we belt-and-braces it
            // off here. The default-profile path leaves the setting
            // at its platform default to preserve normal autofill UX
            // on regular tabs.
            if (tab.isPrivate) {
                @Suppress("DEPRECATION")
                saveFormData = false
            }
        }

        // Autofill: stock WebView reports its DOM form fields as
        // virtual children of itself, which is what lets system
        // password managers fill page-level inputs without per-app
        // integration. The framework default is
        // IMPORTANT_FOR_AUTOFILL_AUTO, which works fine in practice,
        // but we set it explicitly:
        //
        //  * Regular tab → YES_EXCLUDE_DESCENDANTS — the WebView
        //    itself is the autofill anchor; its virtual children
        //    (the form fields) get classified individually by the
        //    framework using their HTML autocomplete attributes,
        //    so we don't need to recurse.
        //  * Private tab → NO. The autofill service shouldn't see
        //    fields inside a private session at all; offering to
        //    save a password from incognito would leak the visit.
        //    Matches Chrome's incognito behaviour.
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

            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                runOnUiThread { handleWebsitePermissionRequest(tab, request) }
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest?) {
                if (tab.pendingWebsitePermission?.request == request) {
                    tab.pendingWebsitePermission = null
                    if (permissionInFlightTabId == tab.id) {
                        permissionInFlightTabId = null
                    }
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
                    // A background tab requesting fullscreen would commandeer
                    // the screen for content the user can't see. Refuse it.
                    callback?.onCustomViewHidden()
                    return
                }
                enterFullscreen(view, callback)
            }

            override fun onHideCustomView() {
                exitFullscreen()
            }

            /**
             * Handle <input type="file"> picker requests. Without this
             * override every file-picker tap on every page silently
             * no-ops — uploads in Gmail web, profile-picture pickers,
             * "attach file" buttons all fail.
             *
             * We launch a system chooser combining ACTION_GET_CONTENT
             * (apps that publish a content provider — gallery, files
             * app, drive) with the MIME types the page asked for via
             * the input's `accept=` attribute. Multi-select is
             * honoured when the page set `multiple`. Camera capture
             * for `accept="image/*" capture` falls through to whatever
             * camera app the chooser surfaces; we deliberately don't
             * roll our own FileProvider+ACTION_IMAGE_CAPTURE chain
             * for v1.0 — Android's chooser already includes the
             * camera as a source on devices that have one.
             */
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                if (filePathCallback == null) return false
                // Background-tab pages shouldn't pop a system picker.
                if (tab !== activeTabOrNull) {
                    filePathCallback.onReceiveValue(null)
                    return true
                }
                // If another chooser is already in flight (shouldn't
                // happen — WebView serialises these — defensive),
                // resolve it with null first so its WebView isn't left
                // hanging.
                pendingFileChooserCallback?.onReceiveValue(null)
                pendingFileChooserCallback = filePathCallback

                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                // Multi-select honours the page's `multiple` attribute.
                if (fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
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
                // Only honour pop-ups triggered by an explicit user gesture
                // (click, tap, key press). This filters out programmatic
                // pop-unders without breaking OAuth / "Open in new tab".
                if (!isUserGesture) return false

                // V1/V2 fix: open a real tab, not an overlay. The address
                // bar now reflects the new browsing context's URL, the
                // security indicator reflects its scheme, and chrome
                // buttons drive the visible WebView. The whole UI-spoofing
                // surface of the previous popup-overlay design is gone.
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
                // V7: opportunistically upgrade http:// navigations to
                // https://. Skips loopback/private/.local hosts because
                // intranet HTTP is legitimate and rarely has a matching
                // TLS endpoint. Loaded via view.loadUrl rather than
                // returning false because we're returning a different URL
                // than the one WebView is about to fetch.
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
                // Applies to every tab so ad/tracker blocking is preserved
                // for new tabs created via window.open(), restored tabs, or
                // any other path. The user can't accidentally land on an
                // unblocked browsing context.
                return BrowserBlocker.createBlockingResponse(
                    url = request?.url?.toString(),
                    isMainFrame = request?.isForMainFrame == true,
                )
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // v10: if the tab was sitting on the start-page overlay
                // and JS on the previously-loaded page just triggered a
                // navigation, transition out of home so the user can
                // see the page that's actually loading.
                if (tab.displayUrl == ABOUT_HOME_URL &&
                    !url.isNullOrBlank() &&
                    url != ABOUT_HOME_URL &&
                    tab === activeTabOrNull
                ) {
                    hideStartPage()
                }
                url?.let { tab.displayUrl = it }
                tab.insecurePageWarningShown = false
                // New document → previous page's touchstart/touchend
                // pairing is moot; the guard script is about to be
                // re-injected by onPageFinished. Reset the flag so a
                // stale `true` from a finger lifted during navigation
                // doesn't block the next pull-to-refresh.
                refreshSuppressedByJs = false
                if (tab === activeTabOrNull) {
                    updateAddressBar(url)
                    updateNavigationButtons()
                    scrollDirAccumPx = 0
                    setChromeHidden(false)
                    // Apply the Shorts inset *eagerly*, before any pixel
                    // of the new document is rendered. The WebView's
                    // viewport (and therefore the page's `100vh`) is
                    // already shrunk when YouTube's Shorts layout code
                    // runs for the first time on this URL, so the
                    // bottom controls / title / page indicator land in
                    // the right place from frame 1. The JS poller
                    // (YOUTUBE_SHORTS_LAYOUT_SCRIPT) is still installed
                    // for SPA navigations that don't trip onPageStarted
                    // — direct URL nav, refreshes, and full reloads are
                    // covered by this native-side branch.
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
                    // Private tabs never record history regardless of
                    // the global "Save browsing history" pref — that's
                    // the whole point of private mode.
                    HistoryRepository.record(url, view?.title.orEmpty())
                }
                if (tab === activeTabOrNull) {
                    progressBar.isVisible = false
                    // Always dismiss the pull-to-refresh spinner here.
                    // If the user pulled and the load eventually finished
                    // they see it complete; if they didn't pull then
                    // setting isRefreshing = false on a non-refreshing
                    // layout is a cheap no-op.
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
                // Reject background-tab SSL errors silently. Showing a
                // dialog for a page the user can't see is confusing and
                // also a UI-spoofing surface (dialog "from" one tab while
                // the address bar shows another). The site will re-issue
                // the request if/when the tab is foregrounded.
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
        // Re-apply settings that the user may have toggled in the Settings
        // screen. Settings touch every tab — JS, popup support, cookies,
        // user agent, force dark — because a stale background tab using
        // the old UA / cookie policy would be a privacy regression.
        BrowserBlocker.adBlockEnabled = prefs.adBlockEnabled
        BrowserBlocker.siteBlockEnabled = prefs.siteBlockEnabled

        // The search-engine caption + address-bar hint were only being
        // refreshed in onCreate, so changing the search engine in
        // Settings left the caption stale until the activity was
        // recreated. applyPreferences runs on every onResume *and* on
        // every settings-return, so this is the right hook.
        updateSearchEngineUi()

        for (tab in tabs) {
            applyToWebView(tab)
        }

        // Reload tabs only when a visual-mode preference flipped, otherwise
        // settings-screen visits would needlessly reload every open tab.
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
            for (tab in tabs) {
                if (tab.webView.url != null) tab.webView.reload()
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
            // V6: keep mixed-content mode in sync with the live preference
            // so toggling "Allow mixed content" affects already-open tabs
            // without forcing a reload.
            mixedContentMode = if (prefs.allowMixedContent) {
                WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            } else {
                WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
        }
        applyForceDark(target)
        syncDocumentStartScripts(tab)
    }

    /**
     * Inject privileged scripts before the first byte of page JS runs.
     *
     * Three scripts get installed per tab when WebView supports the
     * `DOCUMENT_START_SCRIPT` feature (WebView 100+):
     *
     *  1. **YouTube response pruning** — scoped to youtube.com origins.
     *     Hooks fetch/XHR so the player response never sees ad metadata.
     *  2. **Privacy hardening** — scoped to http(s) origins. Enforces a
     *     strict referrer policy (if `trimReferrer` is on) and disables
     *     `RTCPeerConnection` (if `blockWebRtc` is on) so the page can't
     *     leak the local IP via WebRTC ICE candidate gathering.
     *
     * On older WebViews this method silently no-ops; the cosmetic CSS
     * and post-onPageFinished JS still apply.
     */
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
            // Best-effort. Removal takes effect on the next page load.
        }
    }

    @Suppress("DEPRECATION")
    private fun applyForceDark(target: WebView) {
        // Two APIs at play:
        //  - ALGORITHMIC_DARKENING (modern): only kicks in when the *activity*
        //    or system is dark. If the user is in light system mode, this
        //    silently does nothing.
        //  - FORCE_DARK (deprecated but still wired up in the WebView impl
        //    on Android 10–13): unconditional force-on, regardless of system.
        // We use FORCE_DARK as the primary mechanism so the toggle actually
        // does what users expect, and pair it with ALGORITHMIC_DARKENING for
        // newer builds where it's still available.
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

    /**
     * Derive the effective user-agent string at runtime from the system
     * WebView's default UA. We always strip the `; wv` marker — Google's
     * sign-in rejects requests carrying it — and, when desktop mode is
     * on, rewrite the Android platform token to the Linux/X11 desktop
     * form and drop the trailing `Mobile` token.
     *
     * Why derived: hardcoding `Chrome/124.0.0.0` made the app a
     * fingerprintable outlier as the platform's bundled Chromium kept
     * advancing. Sourcing from the WebView's own default tracks
     * upstream automatically.
     */
    private fun effectiveUserAgent(): String =
        if (prefs.desktopMode) toDesktopUa(baseUserAgent) else baseUserAgent

    private val baseUserAgent: String by lazy {
        stripWebViewMarker(WebSettings.getDefaultUserAgent(this))
    }

    private fun stripWebViewMarker(ua: String): String {
        // The token is `; wv` (semicolon-space-wv) before the closing
        // paren of the platform group: `(Linux; Android 14; wv)`. The
        // simplest correct strip is to remove that exact slice; we cope
        // with the rare variant where it sits at the start of the
        // platform group by also handling `wv; `.
        return ua
            .replace("; wv)", ")")
            .replace("; wv;", ";")
    }

    private fun toDesktopUa(mobileUa: String): String {
        // Swap the Android platform group for the Linux/X11 group, then
        // drop the trailing `Mobile` token. Other tokens (Chrome version,
        // Safari version) are preserved so sites see a consistent
        // Chromium release.
        return mobileUa
            .replace(ANDROID_PLATFORM_REGEX, "(X11; Linux x86_64)")
            .replace(" Mobile", "")
    }

    // ---------------------------------------------------------------------
    // Fullscreen
    // ---------------------------------------------------------------------

    /**
     * Host a WebView-supplied fullscreen view (HTML5 video, e.g. YouTube
     * tap-to-fullscreen). The view is attached to the activity's decor so
     * it covers everything; system bars are hidden but can still be swiped
     * back. If a fullscreen view is already active we politely refuse the
     * second one rather than stacking them.
     */
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
        // SENSOR_LANDSCAPE forces landscape regardless of the user's
        // rotation-lock setting — matching how Chrome behaves when an
        // HTML5 video goes fullscreen. The sensor still picks between
        // regular and reverse landscape based on how the phone is held.
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
        // Clear state up-front so a partial failure (e.g. decor.removeView
        // throws because the view was already detached, or the insets
        // controller throws because the activity is being torn down)
        // can't leave the activity stuck in landscape with a stale
        // fullscreenView reference that blocks future entry/exit calls.
        // The original ordering only cleared after every step ran, so
        // any throw mid-exit pinned the orientation permanently.
        val callback = fullscreenCallback
        val savedOrientation = fullscreenSavedOrientation
        fullscreenView = null
        fullscreenCallback = null
        fullscreenSavedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        try {
            val decor = window.decorView as ViewGroup
            decor.removeView(view)
        } catch (_: Exception) {
            // Best-effort: view may already be detached.
        }
        try {
            WindowCompat.getInsetsController(window, window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        } catch (_: Exception) {
            // Ignore — orientation restore is the load-bearing step.
        }
        callback?.onCustomViewHidden()
        requestedOrientation = savedOrientation
    }

    private fun isInFullscreen(): Boolean = fullscreenView != null

    // ---------------------------------------------------------------------
    // Scroll-driven chrome auto-hide
    // ---------------------------------------------------------------------

    /**
     * Drive the auto-hide chrome from a WebView scroll event. We
     * accumulate scroll deltas in one direction and only hide / show
     * once the user has clearly committed (~64 dp down to hide,
     * ~32 dp up to reveal). At the very top of the page we always
     * show the chrome, matching Chrome / Brave. While a hide / show
     * animation is running we ignore further deltas so the WebView's
     * own re-layout during the collapse doesn't bounce us back.
     */
    private fun onWebScroll(scrollY: Int, dy: Int) {
        // SystemClock.uptimeMillis() is monotonic and unaffected by
        // wall-clock changes (NITZ, user setting). System.currentTimeMillis
        // here would let a backward clock jump pin the gate "in the
        // future" forever and silently break chrome auto-hide for the
        // rest of the session.
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
        if (chromeHidden == hidden) return
        chromeHidden = hidden
        scrollDirAccumPx = 0
        chromeAnimGateUntilMs = android.os.SystemClock.uptimeMillis() + CHROME_ANIM_GATE_MS
        topChrome.setExpanded(!hidden, /* animate = */ true)
        val behavior =
            (bottomChrome.layoutParams as? CoordinatorLayout.LayoutParams)?.behavior
        if (behavior is HideViewOnScrollBehavior<*>) {
            @Suppress("UNCHECKED_CAST")
            val bottomBehavior = behavior as HideViewOnScrollBehavior<View>
            if (hidden) {
                bottomBehavior.slideOut(bottomChrome)
            } else {
                bottomBehavior.slideIn(bottomChrome)
            }
        }
    }

    /**
     * Toggle the Shorts-fit inset on the web_container. When `active`,
     * `web_container` is shortened by the bottom-chrome height so the
     * WebView's actual viewport sits *above* the chrome instead of
     * extending behind it. The page then sees a smaller `100vh` and
     * Shorts' video / right-rail / page-indicator naturally reflow into
     * the visible area, regardless of which DOM elements YouTube uses
     * (i.e. this fix is robust across DOM revisions in a way the
     * earlier CSS-selector approach was not).
     *
     * Scoped strictly to Shorts: every call path that toggles this
     * either reads the active tab's URL or is driven by the JS-injected
     * [YOUTUBE_SHORTS_LAYOUT_SCRIPT], which only fires on `/shorts/`
     * URLs. Other tabs / other sites / other pages on YouTube stay on
     * the existing Brave-style overlay layout (margin = 0).
     */
    private fun applyShortsLayout(active: Boolean) {
        if (shortsLayoutActive == active) return
        shortsLayoutActive = active
        val params = webContainer.layoutParams as? CoordinatorLayout.LayoutParams ?: return
        // bottomChrome.height is 0 until the view tree's first layout
        // pass. This can fire from onPageStarted on a cold-start Shorts
        // URL — applying a 0-margin then short-circuits future calls
        // (the early-return at the top), leaving Shorts behind the
        // chrome forever. If we haven't been laid out yet, defer the
        // margin application to the next layout pass.
        val chromeHeight = bottomChrome.height
        if (active && chromeHeight == 0) {
            bottomChrome.post {
                if (!shortsLayoutActive) return@post
                val deferred = webContainer.layoutParams as? CoordinatorLayout.LayoutParams
                    ?: return@post
                val resolved = bottomChrome.height
                if (deferred.bottomMargin != resolved) {
                    deferred.bottomMargin = resolved
                    webContainer.layoutParams = deferred
                }
            }
            return
        }
        val newMargin = if (active) chromeHeight else 0
        if (params.bottomMargin == newMargin) return
        params.bottomMargin = newMargin
        webContainer.layoutParams = params
    }

    private fun injectPageScripts(view: WebView?, url: String?) {
        if (view == null) return
        // Pull-to-refresh guard runs on every page so Shorts / carousels
        // / chat panes inside arbitrary sites all benefit. The script
        // is idempotent (a window-level flag guards re-installation)
        // so the cost of re-injecting on every onPageFinished is one
        // property check.
        view.evaluateJavascript(REFRESH_GUARD_SCRIPT, null)
        // YouTube Shorts layout adapter is URL-gated on the native side
        // so non-YouTube pages don't carry an extra setInterval or an
        // extra `window.__effective…` global. This is the second half
        // of the "doesn't affect other modules" guarantee — the first
        // half is that the bridge only mutates layout when the calling
        // tab is the foreground tab (see ShortsLayoutBridge).
        if (url != null && isYouTubeHost(url)) {
            view.evaluateJavascript(YOUTUBE_SHORTS_LAYOUT_SCRIPT, null)
        }
        if (prefs.adBlockEnabled) {
            // V10: only run the invasive getComputedStyle proxy when the
            // user has explicitly opted in via Settings. The CSS-based
            // hiding still runs regardless because pure CSS can't collide
            // with non-ad code the way the JS proxy can.
            view.evaluateJavascript(
                BrowserBlocker.cosmeticHidingScript(aggressive = prefs.aggressiveAntiAdblock),
                null,
            )
        }
        if (prefs.desktopMode) {
            // Many sites set <meta name="viewport" content="width=device-width">
            // which forces a mobile layout regardless of UA. Override it to
            // a fixed desktop width so the page actually renders as desktop.
            view.evaluateJavascript(DESKTOP_VIEWPORT_SCRIPT, null)
        }
    }

    // ---------------------------------------------------------------------
    // Back handling
    // ---------------------------------------------------------------------

    private fun configureBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // The tab-switcher overlay takes priority over every
                // other back target — it occludes the active tab, so
                // navigating the tab's history while it's up would be
                // surprising.
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
                    // Hitting back at the top of a tab's history closes that
                    // tab and shows the previous one — Chrome on Android does
                    // the same for tabs opened from external intents or
                    // window.open().
                    closeTab(activeTabIndex)
                    return
                }
                finish()
            }
        })
    }

    // ---------------------------------------------------------------------
    // Downloads
    // ---------------------------------------------------------------------

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
                // V4: do NOT capture a cookie snapshot here — the
                // download task reads a live cookie at request time.
                // Incognito-aware: pass the source tab's profile name
                // so DownloadTask reads cookies from the *right*
                // CookieManager (private profile for private-tab
                // downloads). The repository accepts null = default.
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

    // ---------------------------------------------------------------------
    // Permissions (V3 fix)
    // ---------------------------------------------------------------------

    /**
     * Show a permission prompt for [tab]. The prompt is serialised across
     * the activity: if another permission prompt is already on screen or
     * waiting for an Android grant, the new request is denied immediately
     * with a user-facing message. This is what closes the V3 leak — the
     * old code overwrote a single nullable slot, lost the first request,
     * and could resolve a grant against the wrong page.
     */
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

        val origin = extractOriginLabel(request.origin?.toString())
        val requestedAccess = supportedResources.joinToString(separator = getString(R.string.permission_joiner)) {
            when (it) {
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> getString(R.string.microphone_permission_label)
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> getString(R.string.camera_permission_label)
                else -> it
            }
        }

        // Claim the in-flight slot before showing the dialog so a second
        // prompt that arrives while this one is on screen also gets denied.
        permissionInFlightTabId = tab.id

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.website_permission_title, origin))
            .setMessage(getString(R.string.website_permission_message, origin, requestedAccess))
            .setPositiveButton(R.string.allow_once) { _, _ ->
                val missingPermissions = supportedResources
                    .flatMap(::requiredAndroidPermissions)
                    .distinct()
                    .filterNot(::hasPermission)

                if (missingPermissions.isEmpty()) {
                    request.grant(supportedResources.toTypedArray())
                    permissionInFlightTabId = null
                } else {
                    // Keep the in-flight slot held; the launcher result
                    // handler will clear it when the Android prompt
                    // resolves. The pending request lives on the tab so
                    // there's no way to cross-wire it with another tab's
                    // grant.
                    tab.pendingWebsitePermission = Tab.PendingWebsitePermission(
                        request = request,
                        resources = supportedResources,
                    )
                    websitePermissionLauncher.launch(missingPermissions.toTypedArray())
                }
            }
            .setNegativeButton(R.string.deny) { _, _ ->
                request.deny()
                permissionInFlightTabId = null
            }
            .setOnCancelListener {
                request.deny()
                permissionInFlightTabId = null
            }
            .show()
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

        geolocationInFlightTabId = tab.id

        val originLabel = extractOriginLabel(origin)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.location_permission_title, originLabel))
            .setMessage(getString(R.string.location_permission_message, originLabel))
            .setPositiveButton(R.string.allow_once) { _, _ ->
                if (hasAnyLocationPermission()) {
                    callback.invoke(origin, true, false)
                    geolocationInFlightTabId = null
                } else {
                    tab.pendingGeolocation = Tab.PendingGeolocation(origin, callback)
                    geolocationPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        ),
                    )
                }
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

    // ---------------------------------------------------------------------
    // Navigation helpers
    // ---------------------------------------------------------------------

    /**
     * Show a friction-laden SSL error dialog. Default action is Cancel
     * (the safe choice); Proceed is offered for self-signed intranet
     * pages where the user knows what they're doing. The dialog also
     * surfaces a brief description of *why* the cert was rejected so
     * the user can decide whether the cause matches their expectation
     * (expired vs untrusted CA vs hostname mismatch are very different).
     *
     * Note: we deliberately don't remember the proceed decision per host
     * the way Chrome does — adding that without a robust "forget exception"
     * UX is an easy way to silently downgrade security long-term.
     */
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

    /**
     * Handle the "Open settings" / "Open blocked sites" button shown on a
     * block page. The button navigates to `effbrowser-action:<token>` and
     * we route to the appropriate activity. The block page stays open so
     * the user can reload after they've made the edit they need.
     */
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
            null -> true // Unknown action — swallow rather than navigate.
        }
    }

    /**
     * Hand off non-web schemes (tel:, mailto:, sms:, geo:, market:, intent:)
     * to the system. Returns true so WebView treats the URL as handled.
     * Falls back to a toast if no app can handle it.
     */
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

        if (!sanitizeExternalIntent(intent)) {
            showToast(getString(R.string.unsupported_link_message))
            return true
        }

        return try {
            startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            showToast(getString(R.string.unsupported_link_message))
            true
        } catch (_: SecurityException) {
            showToast(getString(R.string.unsupported_link_message))
            true
        }
    }

    /**
     * Strip the dangerous corners of an `intent://` URL before handing it
     * off to the system, and reject anything outside our action allowlist.
     * Closes V5 from the audit.
     *
     * What we strip and why:
     *
     *  - **flags** — Clearing all flags and re-adding only
     *    `FLAG_ACTIVITY_NEW_TASK` blocks crafted URLs from carrying
     *    `FLAG_GRANT_READ_URI_PERMISSION`, `FLAG_ACTIVITY_CLEAR_TASK`,
     *    `FLAG_ACTIVITY_FORWARD_RESULT`, etc.
     *  - **package, selector, component** — These let a page pin the
     *    intent to a specific app or activity. Nulling them forces
     *    normal receiver resolution against installed apps.
     *  - **categories** — A category like `CATEGORY_HOME` or
     *    `CATEGORY_LAUNCHER` lets the intent address system surfaces. We
     *    strip everything; the framework auto-adds `CATEGORY_DEFAULT`
     *    when `startActivity` resolves.
     *  - **EXTRA_INTENT** — Android lets you wrap an inner intent inside
     *    another via this extra. Receivers sometimes unpack and execute
     *    the inner intent with their own privileges; strip it so a page
     *    can't smuggle one through.
     *
     * Action is then validated against a small allowlist of well-known
     * navigational verbs. Anything else returns false and the caller
     * shows the standard "Only web links are supported" toast.
     */
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
        // Only honour http(s) from external intents. Allowing javascript:,
        // data:, or blob: from another app would let any installed app
        // execute arbitrary script in the loaded page's context.
        val lower = rawUrl.lowercase(java.util.Locale.US)
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return null
        }

        return UrlInputUtils.enforceSecureScheme(rawUrl)
    }

    private fun loadAddress(url: String) {
        val tab = activeTabOrNull
        if (tab == null) {
            // Defensive: if somehow the activity has no active tab, create
            // one rather than crashing.
            openNewTab(url = url, switchTo = true)
            return
        }
        // v10 about:home short-circuit. The start page is an in-app
        // overlay, not a real web URL, so we don't push anything into
        // the WebView — we just mark the tab and show the overlay.
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

    /** Show the v10 paper-theme start page overlay over the WebView.
     *  Routes to the regular overlay or the ink-coloured private
     *  overlay based on the active tab's privacy mode — separate
     *  layouts because the visuals diverge enough that conditional
     *  binding inside one would be noisier than two controllers.
     *
     *  Idempotent: a second call while one is already up just
     *  refreshes the data behind it. */
    private fun showStartPage() {
        // Halt anything the WebView is fetching so a long-running
        // resource on the previous page doesn't paint a flash through
        // the overlay or fire a misleading onPageFinished after the
        // user has already moved on.
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

    /** Restore the WebView surface. Hides whichever start-page
     *  overlay (regular or private) was visible. Called whenever a
     *  real URL is loaded into the active tab (loadAddress,
     *  switchToTab to a non-home tab, etc.). */
    private fun hideStartPage() {
        val regular = startPageView
        val incognito = privateStartPageView
        val wasShowing = regular?.isShowing() == true || incognito?.isShowing() == true
        regular?.hide()
        incognito?.hide()
        if (wasShowing) webContainer.isVisible = true
    }

    /**
     * Where the home button (and a freshly opened browser process)
     * should go. v10 default is the paper-theme start page; users who
     * explicitly typed a URL into Settings → Home page get that URL
     * loaded as a real WebView page instead.
     */
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

    // Address-bar URL formatting (V8 + V9) is implemented in
    // [UrlInputUtils.prettifyUrl]; this thin wrapper exists only because
    // the call site reads better as `prettifyUrl(url)`.
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
        // Only https:// counts as "secure". The previous implementation
        // also treated data:, file: and about: as secure, which let a
        // page navigated to e.g. data:text/html,<phish> wear the lock
        // icon. Now only the cold-start blank state defaults to the
        // muted lock; every non-https navigation gets the unlock icon.
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
            // The HTTP-page warning snackbar is only meaningful for an
            // http:// navigation. Local / non-network schemes (file:,
            // data:, content:) still show the unlock icon above but
            // skip the toast — the user already knows they're not on
            // the open web.
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
        // V8/V9: bookmark / search / home / menu are meaningful at any
        // time, even with no tab attached, so they stay enabled.
        // Refresh is the exception — it's the only top-bar button that
        // operates on the active page, so we dim it (alpha 0.35) when
        // there's no tab to reload.
        updateButtonState(bookmarkButton, true)
        updateButtonState(searchButton, true)
        updateButtonState(homeButton, true)
        updateButtonState(menuButton, true)
        updateButtonState(refreshButton, activeTabOrNull != null)

        // Tabs button is always tappable; the overlay shows the count.
        // Two digits is the sane upper bound for the 24dp icon — beyond
        // that we render "9+" so the digits never overflow the square.
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
            // V7: respect the always-HTTPS pref. enforceSecureScheme only
            // *adds* https:// to bare hosts; a user who typed
            // `http://example.com` would otherwise keep their HTTP scheme.
            if (prefs.alwaysHttps && UrlInputUtils.shouldUpgradeToHttps(withScheme)) {
                UrlInputUtils.upgradeToHttps(withScheme)
            } else {
                withScheme
            }
        } else {
            buildSearchUrl(sanitized)
        }
    }

    // The previous private copies of stripJavascriptScheme, looksLikeUrl,
    // and enforceSecureScheme moved to [UrlInputUtils] so they can be
    // unit-tested without spinning up an Activity. Call sites in this
    // file now go through `UrlInputUtils.*` directly.

    // ---------------------------------------------------------------------
    // Menu / commands
    // ---------------------------------------------------------------------

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

        // v10.1: Desktop tile carries the same active-state visual
        // language as Save — accent-soft fill + accent-tinted icon /
        // label when desktop mode is currently on. Without this the
        // user couldn't tell whether a tap had toggled desktop on or
        // off (only the toast confirmed it), which is the bug the
        // user filed.
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

    /**
     * Is the active tab currently on a page where reader mode makes
     * sense? Only http(s) pages have content the extractor can chew
     * on; about:/data: pages and the reader view itself are skipped.
     *
     * Note: this only gates the *menu item visibility*. The actual
     * "found readable content" decision is made by [ReaderMode] at
     * extraction time — even on an eligible-looking URL the page may
     * not have an article structure (think: Reddit, Twitter), and the
     * extractor reports `eligible: false` for those.
     */
    private fun canEnterReaderMode(): Boolean {
        val url = activeTabOrNull?.webView?.url ?: return false
        return url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true)
    }

    /**
     * Run the reader extraction against the active tab. On success we
     * swap the WebView's current view to the rendered template via
     * `loadDataWithBaseURL`, using the original URL as both the base
     * (so relative `<img src>` resolve) and the history URL (so the
     * address bar still shows the article's real URL and system-back
     * returns to the un-reader'd page — the previous WebView history
     * entry).
     *
     * On failure (no readable content, extractor threw, asset load
     * failed) we surface a toast and leave the page untouched.
     */
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
        // CookieManager.getInstance() / WebStorage.getInstance() only
        // touch the *default* profile. Each AndroidX webkit Profile
        // has its own cookie jar + WebStorage; the private profile's
        // copies have to be cleared separately via the profile object.
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        clearPrivateProfileDataIfAny()
        // Per-WebView caches need a pass per tab (regardless of
        // profile — clearCache is a WebView method).
        for (tab in tabs) {
            tab.webView.clearHistory()
            tab.webView.clearCache(true)
            tab.webView.clearFormData()
        }
        WebStorage.getInstance().deleteAllData()
        HistoryRepository.clear()
        showToast(getString(R.string.clear_browsing_data_done))
        loadAddress(homeUrl())
    }

    /**
     * Clear cookies + WebStorage on the private profile without
     * deleting the profile itself. Used by [clearBrowsingData] (which
     * may run while private tabs are still open, in which case the
     * profile is in-use and `deleteProfile` would throw). The wipe is
     * a no-op when no private profile has ever been created.
     */
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

    /**
     * Copy [url] to the system clipboard with our app name as the
     * clip's user-facing label. Used by both the toolbar "Copy link"
     * action and the long-press context menu's "Copy link" /
     * "Copy image URL" items.
     */
    private fun copyUrlToClipboard(url: String) {
        val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), url))
        showToast(getString(R.string.link_copied))
    }

    /**
     * Fire the standard ACTION_SEND chooser for [url] as plain text.
     * The chooser title defaults to "Share link"; the current-page
     * share path passes "Share page" instead to preserve the existing
     * label.
     */
    private fun shareUrl(url: String, chooserTitleRes: Int = R.string.share_link) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        startActivity(Intent.createChooser(shareIntent, getString(chooserTitleRes)))
    }

    /**
     * Populate the context menu for a long-pressed link with the
     * standard browser actions. "Open in private tab" is gated on
     * [multiProfileSupported] — we never offer to open a "private"
     * tab on a WebView that can't actually isolate it.
     *
     * "Open in new tab" opens in the background (so the user stays on
     * the current page); "Open in private tab" foregrounds the new
     * tab because users invoking the private path generally want to
     * see what they just opened in private.
     */
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

    /**
     * Image-specific items in the long-press context menu. "Save image"
     * lives in [addSaveImageItem] and is added separately by the
     * dispatcher — that way the same item can also be appended to the
     * link-image menu without duplicating the copy/share entries.
     */
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

    /**
     * Append "Save image" to the supplied context menu. The image URL
     * isn't known synchronously — we use [WebView.requestImageRef]
     * which posts the last-touched image's src back through a Message.
     * This is the API specifically designed for the "save image" UX
     * and works for both plain images and image-wrapped-in-anchor
     * hit-test types (the `extra` field of the latter is the anchor
     * href, not the image src, so requestImageRef is the only correct
     * source).
     */
    private fun addSaveImageItem(menu: ContextMenu, sourceTab: Tab) {
        menu.add(getString(R.string.image_action_save_image))
            .setOnMenuItemClickListener {
                requestLastTouchedImageUrl(sourceTab) { imageUrl ->
                    saveImageFromUrl(imageUrl, sourceTab)
                }
                true
            }
    }

    /**
     * Ask the WebView for the URL of the last-touched image and call
     * [onUrl] on the main thread once the platform replies. The
     * caller's lambda is fired at most once; failure to retrieve a URL
     * (the WebView can't find an image for the last touch) is silent
     * since there's nothing actionable to show the user.
     */
    private fun requestLastTouchedImageUrl(tab: Tab, onUrl: (String) -> Unit) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper()) { msg ->
            val url = msg.data?.getString("url")
            if (!url.isNullOrBlank()) onUrl(url)
            true
        }
        tab.webView.requestImageRef(handler.obtainMessage())
    }

    /**
     * Enqueue an image download triggered from the long-press context
     * menu. Reuses the existing download pipeline so we get MediaStore
     * placement, foreground-service progress notifications, the
     * 8 GiB cap, resume support, and incognito-aware cookies for
     * free. No confirmation dialog — the user already opted in by
     * picking the menu item.
     */
    private fun saveImageFromUrl(imageUrl: String, sourceTab: Tab) {
        // blob: / data: aren't network-fetchable, so OkHttp can't
        // resolve them. The existing string covers both cases.
        if (imageUrl.startsWith("blob:", ignoreCase = true) ||
            imageUrl.startsWith("data:", ignoreCase = true)
        ) {
            showToast(getString(R.string.blob_download_not_supported))
            return
        }
        // Respect the same blocker that gates regular downloads — a
        // user shouldn't be able to bypass an ad/tracker rule via the
        // long-press context menu.
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

    /**
     * Best-effort MIME guess from a URL's path extension. Returning
     * null is fine: [DownloadRepository] falls back to
     * `application/octet-stream` for the MediaStore row, and the file
     * still saves correctly — the only loss is that Files-app
     * thumbnailing may not kick in for a few seconds while MediaStore
     * re-scans the type from the bytes.
     */
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

    /**
     * Hand the active tab's rendered page to Android's print framework.
     *
     * `WebView.createPrintDocumentAdapter` produces a paginated render
     * of the *current* page (not a re-fetch), so it honours whatever the
     * user is actually looking at — scroll position aside. The system
     * print dialog that `PrintManager.print` opens always includes a
     * built-in "Save as PDF" destination, so this single menu item
     * covers both "print" and "save as PDF" with no extra code.
     */
    private fun printCurrentPage() {
        val tab = activeTabOrNull
        val webView = tab?.webView
        if (webView?.url.isNullOrBlank()) {
            showToast(getString(R.string.no_page_loaded))
            return
        }
        val printManager = getSystemService(Context.PRINT_SERVICE) as? android.print.PrintManager
        if (printManager == null) {
            // PRINT_SERVICE is absent on a small number of stripped
            // builds (some Android TV / Go images). Fail with a clear
            // message rather than a silent no-op.
            showToast(getString(R.string.print_unavailable))
            return
        }
        // Job name shows in the print queue / PDF filename. Fall back to
        // the app name if the page hasn't reported a title yet.
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
        // Slightly longer than the Material collapse / slide animation
        // (~300 ms) so transient WebView scroll deltas during the
        // animation can't toggle the chrome back.
        private const val CHROME_ANIM_GATE_MS = 350L

        /** Empirically about the longest gap that still feels responsive
         *  while collapsing the per-keystroke `findAllAsync` cost. */
        private const val FIND_DEBOUNCE_MS = 150L
        private const val ADDRESS_SUGGESTION_LIMIT = 8
        private const val ADDRESS_SUGGESTION_DISMISS_DELAY_MS = 120L

        private const val STATE_TAB_URLS = "state_tab_urls"
        private const val STATE_ACTIVE_TAB_INDEX = "state_active_tab_index"
        private const val STATE_ACTIVE_TAB_WEBVIEW = "state_active_tab_webview"
        private const val PRIVACY_SCRIPT_TRIM_REFERRER = 1
        private const val PRIVACY_SCRIPT_BLOCK_WEBRTC = 2

        /**
         * Name of the AndroidX webkit [androidx.webkit.Profile] used by
         * every private tab in this process. A single shared profile is
         * fine because private tabs are conceptually one session — the
         * profile is deleted (wiping cookies, storage, cache) when the
         * last private tab closes, at activity destroy, and at every
         * cold start.
         */
        private const val PRIVATE_PROFILE_NAME = "incognito"

        /**
         * Internal URL marker for the v10 start page. Stored as a tab's
         * displayUrl whenever the user is on the paper-theme home page
         * rather than a real web URL — switchToTab / loadAddress key
         * off this constant to decide which surface to show.
         *
         * Not loaded into the WebView (which doesn't understand it);
         * the WebView simply stays attached but visually covered by
         * the start-page overlay.
         */
        const val ABOUT_HOME_URL = "about:home"

        /**
         * Actions that `intent://` URLs are allowed to invoke. Chosen as the
         * smallest set that covers the legitimate browser → other-app
         * surfaces (tel:, mailto:, sms:, geo:, market:, share sheets,
         * web-search hand-offs). Anything else is rejected by
         * [sanitizeExternalIntent].
         */
        private val ALLOWED_EXTERNAL_INTENT_ACTIONS = setOf(
            Intent.ACTION_VIEW,
            Intent.ACTION_DIAL,
            Intent.ACTION_SENDTO,
            Intent.ACTION_SEND,
            Intent.ACTION_SEND_MULTIPLE,
            Intent.ACTION_WEB_SEARCH,
        )

        // V7 private/loopback address matchers live in [UrlInputUtils]
        // alongside the helpers that consume them.

        /**
         * Matches the platform group of an Android WebView user-agent
         * string — e.g. `(Linux; Android 14)`, `(Linux; Android 14; SM-G991B)`,
         * or `(Linux; Android 14; wv)`. Used by [toDesktopUa] to swap the
         * group out for the X11/Linux equivalent when desktop mode is on.
         */
        private val ANDROID_PLATFORM_REGEX = Regex("\\(Linux; Android[^)]*\\)")

        /**
         * Replaces the page's viewport meta tag with one that requests a
         * 1024 px wide layout. Without this, sites whose own meta says
         * `width=device-width` keep rendering as mobile even when the UA is
         * desktop. Idempotent and safe to inject multiple times.
         */
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

        /**
         * Name of the [RefreshGuardBridge] window-global. Must match
         * the identifier the injected [REFRESH_GUARD_SCRIPT] looks up.
         * Picked to be obviously-namespaced so a page can't shadow it by
         * accident with its own `window.foo = …`.
         */
        private const val REFRESH_GUARD_BRIDGE_NAME = "__eborsRefreshGuard"

        /**
         * Name of the [ShortsLayoutBridge] window-global. Same namespacing
         * convention as [REFRESH_GUARD_BRIDGE_NAME].
         */
        private const val SHORTS_LAYOUT_BRIDGE_NAME = "__eborsShortsLayout"

        /**
         * Polls `location.pathname` on YouTube pages and tells native
         * whether the current page is a Short via
         * [ShortsLayoutBridge.setShortsLayoutActive]. Native then sizes
         * `web_container` so its bottom edge sits cleanly above the
         * nav bar (only on Shorts URLs, only for the foreground tab).
         *
         * Why bridge-into-native instead of inline CSS: the previous
         * attempt injected `height: calc(100vh - Xpx)` on a handful of
         * YouTube custom elements. That fails in two ways — the mobile
         * site uses different element names entirely, and the
         * action-rail (like/comment/share) is positioned with
         * `position: absolute; bottom: …` *relative to the viewport*,
         * not relative to any container we could shrink. Shrinking the
         * **WebView** itself, on the other hand, shrinks the viewport
         * — every viewport-relative element reflows for free.
         *
         * The script:
         *  - is injected only when the host is `*.youtube.com` (see
         *    [isYouTubeHost]). Non-YouTube pages never see this code;
         *  - polls `location.href` every 500 ms. YouTube uses SPA
         *    navigation so onPageStarted/onPageFinished often don't
         *    fire on Shorts <-> home transitions. Polling is cheaper
         *    and more robust than monkey-patching `history.pushState`;
         *  - is idempotent — a window-level flag stops re-injection
         *    from stacking timers, which matters because YouTube does
         *    occasional hard navigations that re-run our injection.
         *
         * The 500 ms cadence is the only knob: faster = quicker
         * response on SPA Shorts entry, slower = less CPU. 500 ms is
         * imperceptible in practice and well below the timer-coalescing
         * threshold most CPUs use.
         */
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

        /**
         * Installs a capture-phase touch listener that flips the native
         * `refreshSuppressedByJs` flag whenever the touch lands on an
         * element that owns its own vertical scroll/paging. Without
         * this, SwipeRefreshLayout can't tell a downward swipe inside
         * YouTube Shorts (or any other "swipe to page" widget) apart
         * from a deliberate pull-to-refresh.
         *
         * Detection heuristic walks the ancestor chain and treats an
         * element as nested-scrolling if any of:
         *  - `overscroll-behavior-y` is `contain` or `none` (Shorts'
         *    container, modern carousels, lots of chat panes);
         *  - `overflow-y` is `scroll` or `auto` AND the element
         *    actually has more content than fits (otherwise every
         *    `<body>` with a default style would match).
         *
         * Idempotent: the script tags `window` and bails on re-run
         * because [injectPageScripts] fires on every onPageFinished.
         * Listeners are registered with `{ capture: true, passive:
         * true }` so they observe before the site's own handlers and
         * never block the gesture.
         */
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
