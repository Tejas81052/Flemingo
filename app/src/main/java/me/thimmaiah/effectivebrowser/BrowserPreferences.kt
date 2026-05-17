package me.thimmaiah.effectivebrowser

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Centralised, typed access to user-facing browser preferences. All features
 * (settings screen, main activity, blocker) read and write through this class
 * so defaults stay consistent.
 *
 * # Known privacy limitations
 *
 *  - **No per-site storage partitioning.** Cookies, localStorage,
 *    IndexedDB, and the cache are all shared across every site you
 *    visit (this is how Android's [android.webkit.WebView] is wired —
 *    it doesn't expose a partitioning API on stable channels). A third-
 *    party tracker embedded across multiple top-level sites can
 *    correlate visits via its own cookie. Mitigations available here:
 *    "Block third-party cookies" (off by default for sign-in
 *    compatibility), the built-in ad/tracker blocker, and "Clear
 *    browsing data" which wipes everything in one shot.
 *  - **No DNS-over-HTTPS.** WebView uses the system resolver, so the
 *    network in front of the device can see which hosts you're
 *    looking up. There's no Settings toggle for this today.
 *  - **Private profile availability depends on WebView.** Private tabs
 *    use AndroidX WebKit profiles for an isolated cookie/storage jar
 *    when the installed Android System WebView supports
 *    `WebViewFeature.MULTI_PROFILE`. On older WebView builds the UI hides
 *    private-tab entry points instead of offering a fake private mode.
 *
 * These are tracked as deferred items — see the audit summary at the
 * end of the most recent review pass.
 */
class BrowserPreferences private constructor(private val prefs: SharedPreferences) {

    var searchEngine: String
        get() = prefs.getString(KEY_SEARCH_ENGINE, null) ?: DEFAULT_SEARCH_ENGINE
        set(value) = prefs.edit { putString(KEY_SEARCH_ENGINE, value) }

    /** Mobile-style or desktop-style user agent. */
    var desktopMode: Boolean
        get() = prefs.getBoolean(KEY_DESKTOP_MODE, false)
        set(value) = prefs.edit { putBoolean(KEY_DESKTOP_MODE, value) }

    /** Apply force-dark / algorithmic darkening to web content. */
    var forceDark: Boolean
        get() = prefs.getBoolean(KEY_FORCE_DARK, false)
        set(value) = prefs.edit { putBoolean(KEY_FORCE_DARK, value) }

    /** Master switch for the network-level ad/tracker blocker. */
    var adBlockEnabled: Boolean
        get() = prefs.getBoolean(KEY_AD_BLOCK, true)
        set(value) = prefs.edit { putBoolean(KEY_AD_BLOCK, value) }

    /** Master switch for the user-managed site block list. */
    var siteBlockEnabled: Boolean
        get() = prefs.getBoolean(KEY_SITE_BLOCK, true)
        set(value) = prefs.edit { putBoolean(KEY_SITE_BLOCK, value) }

    /**
     * Whether to accept third-party cookies. Default true because most users
     * need it for federated sign-in. Privacy-leaning users can flip it off.
     */
    var thirdPartyCookies: Boolean
        get() = prefs.getBoolean(KEY_THIRD_PARTY_COOKIES, true)
        set(value) = prefs.edit { putBoolean(KEY_THIRD_PARTY_COOKIES, value) }

    var javaScriptEnabled: Boolean
        get() = prefs.getBoolean(KEY_JS_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_JS_ENABLED, value) }

    /** Whether to record browsing history locally. */
    var historyEnabled: Boolean
        get() = prefs.getBoolean(KEY_HISTORY_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_HISTORY_ENABLED, value) }

    /** Block popup windows opened via window.open(). OAuth still works because
     *  it's an explicit user gesture handled separately. */
    var blockPopups: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_POPUPS, false)
        set(value) = prefs.edit { putBoolean(KEY_BLOCK_POPUPS, value) }

    /**
     * Upgrade `http://` navigations to `https://` when the host isn't
     * localhost or a private network address. Default on — matches Chrome's
     * "Always Use Secure Connections" behaviour. Closes V7 from the audit
     * (no in-app HTTPS-enforcement toggle).
     */
    var alwaysHttps: Boolean
        get() = prefs.getBoolean(KEY_ALWAYS_HTTPS, true)
        set(value) = prefs.edit { putBoolean(KEY_ALWAYS_HTTPS, value) }

    /**
     * Permit HTTPS pages to load HTTP subresources ("mixed content") in
     * WebView's compatibility mode. Default off — the WebView default of
     * `MIXED_CONTENT_COMPATIBILITY_MODE` is too permissive for a privacy-
     * leaning browser and was flagged as V6 in the audit. The user can
     * still flip this on if a legacy site they need breaks under
     * `NEVER_ALLOW`.
     */
    var allowMixedContent: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_MIXED_CONTENT, false)
        set(value) = prefs.edit { putBoolean(KEY_ALLOW_MIXED_CONTENT, value) }

    /**
     * Run the aggressive anti-anti-adblock JS hooks (notably the
     * `window.getComputedStyle` proxy that lies about the height of
     * ad-baity elements). Default off because the proxy can interact
     * badly with code on legitimate sites whose classNames happen to
     * contain tokens like `adsbox` or `ad-banner` — see V10 in the
     * audit. The basic cosmetic CSS still runs regardless; this only
     * controls the invasive script-level evasion.
     */
    var aggressiveAntiAdblock: Boolean
        get() = prefs.getBoolean(KEY_AGGRESSIVE_ANTI_ADBLOCK, false)
        set(value) = prefs.edit { putBoolean(KEY_AGGRESSIVE_ANTI_ADBLOCK, value) }

    /**
     * Block WebRTC at the page level so STUN / TURN candidate gathering
     * can't leak the user's local IP address even when behind a VPN.
     * Default on for a privacy-leaning browser. Implementation is a
     * document-start scriptlet that replaces `RTCPeerConnection`
     * constructors with a stub that throws.
     */
    var blockWebRtc: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_WEBRTC, true)
        set(value) = prefs.edit { putBoolean(KEY_BLOCK_WEBRTC, value) }

    /**
     * Inject a `<meta name="referrer">` tag forcing
     * `strict-origin-when-cross-origin` on every page load so cross-site
     * navigations don't carry the full referring URL. Default on.
     */
    var trimReferrer: Boolean
        get() = prefs.getBoolean(KEY_TRIM_REFERRER, true)
        set(value) = prefs.edit { putBoolean(KEY_TRIM_REFERRER, value) }

    /** User-friendly label for a custom search engine. Shown in Settings
     *  and the address-bar caption. */
    var customSearchEngineName: String
        get() = prefs.getString(KEY_CUSTOM_SEARCH_NAME, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_CUSTOM_SEARCH_NAME, value) }

    /**
     * Custom search-URL template. Supports a `%s` placeholder for the
     * URL-encoded query (matching Firefox / Chromium keyword-search
     * convention). When `%s` is absent we fall back to appending
     * `?q=...` — see [SearchEngineResolver.buildSearchUrl].
     */
    var customSearchEngineUrlTemplate: String
        get() = prefs.getString(KEY_CUSTOM_SEARCH_URL, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_CUSTOM_SEARCH_URL, value) }

    /** Optional home-page URL associated with the custom search engine
     *  (e.g. `https://search.example.com/`). Used when the user has no
     *  explicit homePage configured. */
    var customSearchEngineHomeUrl: String
        get() = prefs.getString(KEY_CUSTOM_SEARCH_HOME, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_CUSTOM_SEARCH_HOME, value) }

    /**
     * Whether the app may check for an updated ad/tracker block list over
     * the network on startup. Default on, but it only does anything when
     * the build actually has an update source wired up (see
     * [BlocklistUpdateConfig.isConfigured]) — otherwise it's a no-op
     * regardless of this flag.
     */
    var blocklistAutoUpdate: Boolean
        get() = prefs.getBoolean(KEY_BLOCKLIST_AUTO_UPDATE, true)
        set(value) = prefs.edit { putBoolean(KEY_BLOCKLIST_AUTO_UPDATE, value) }

    /**
     * Epoch-millis of the last block-list update *attempt* (success or
     * failure). Used to rate-limit the opportunistic startup check and
     * to show "last checked …" in Settings. 0 = never checked.
     */
    var blocklistLastCheckedAt: Long
        get() = prefs.getLong(KEY_BLOCKLIST_LAST_CHECKED_AT, 0L)
        set(value) = prefs.edit { putLong(KEY_BLOCKLIST_LAST_CHECKED_AT, value) }

    /** Persisted home/start page URL. Empty -> use search engine home. */
    var homePage: String
        get() = prefs.getString(KEY_HOME_PAGE, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_HOME_PAGE, value) }

    var defaultBrowserPromptShown: Boolean
        get() = prefs.getBoolean(KEY_DEFAULT_BROWSER_PROMPT_SHOWN, false)
        set(value) = prefs.edit { putBoolean(KEY_DEFAULT_BROWSER_PROMPT_SHOWN, value) }

    var notificationPromptShown: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATION_PERMISSION_PROMPT_SHOWN, false)
        set(value) = prefs.edit { putBoolean(KEY_NOTIFICATION_PERMISSION_PROMPT_SHOWN, value) }

    companion object {
        private const val PREFS_NAME = "effective_browser_prefs"

        private const val KEY_SEARCH_ENGINE = "key_search_engine"
        private const val KEY_DESKTOP_MODE = "key_desktop_mode"
        private const val KEY_FORCE_DARK = "key_force_dark"
        private const val KEY_AD_BLOCK = "key_ad_block"
        private const val KEY_SITE_BLOCK = "key_site_block"
        private const val KEY_THIRD_PARTY_COOKIES = "key_third_party_cookies"
        private const val KEY_JS_ENABLED = "key_javascript_enabled"
        private const val KEY_HISTORY_ENABLED = "key_history_enabled"
        private const val KEY_BLOCK_POPUPS = "key_block_popups"
        private const val KEY_HOME_PAGE = "key_home_page"
        private const val KEY_DEFAULT_BROWSER_PROMPT_SHOWN = "key_default_browser_prompt_shown"
        private const val KEY_NOTIFICATION_PERMISSION_PROMPT_SHOWN = "key_notification_permission_prompt_shown"
        private const val KEY_ALWAYS_HTTPS = "key_always_https"
        private const val KEY_ALLOW_MIXED_CONTENT = "key_allow_mixed_content"
        private const val KEY_AGGRESSIVE_ANTI_ADBLOCK = "key_aggressive_anti_adblock"
        private const val KEY_BLOCK_WEBRTC = "key_block_webrtc"
        private const val KEY_TRIM_REFERRER = "key_trim_referrer"
        private const val KEY_CUSTOM_SEARCH_NAME = "key_custom_search_name"
        private const val KEY_CUSTOM_SEARCH_URL = "key_custom_search_url"
        private const val KEY_CUSTOM_SEARCH_HOME = "key_custom_search_home"
        private const val KEY_BLOCKLIST_AUTO_UPDATE = "key_blocklist_auto_update"
        private const val KEY_BLOCKLIST_LAST_CHECKED_AT = "key_blocklist_last_checked_at"

        private const val DEFAULT_SEARCH_ENGINE = "duckduckgo"

        fun from(context: Context): BrowserPreferences {
            return BrowserPreferences(
                context.applicationContext.getSharedPreferences(
                    PREFS_NAME,
                    Context.MODE_PRIVATE,
                ),
            )
        }
    }
}
