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

    /**
     * Send the address-bar prefix to the configured search engine's
     * autocomplete endpoint for live suggestions. Default on (matches every
     * mainstream browser). Off falls back to local history + a "search this"
     * row, so nothing the user types leaves the device until they submit.
     */
    var searchSuggestionsEnabled: Boolean
        get() = prefs.getBoolean(KEY_SEARCH_SUGGESTIONS, true)
        set(value) = prefs.edit { putBoolean(KEY_SEARCH_SUGGESTIONS, value) }

    /** Block popup windows opened via window.open(). OAuth still works because
     *  it's an explicit user gesture handled separately. Default on so a
     *  fresh install isn't ambushed by drive-by popunders. */
    var blockPopups: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_POPUPS, true)
        set(value) = prefs.edit { putBoolean(KEY_BLOCK_POPUPS, value) }

    /** Put tabs left untouched for a few minutes to sleep — their page is
     *  dropped to free memory and reloaded when you return to the tab.
     *  Default on; the trade-off is a quick reload on a long-idle tab. */
    var tabSleeping: Boolean
        get() = prefs.getBoolean(KEY_TAB_SLEEPING, true)
        set(value) = prefs.edit { putBoolean(KEY_TAB_SLEEPING, value) }

    /**
     * Wipe cookies, cache, web storage, history and per-site permissions
     * when the browser is closed (the task is finished / swiped away).
     * Default off — opt-in for users who want a clean slate every session.
     * Private tabs are always wiped on exit regardless of this flag.
     */
    var clearDataOnExit: Boolean
        get() = prefs.getBoolean(KEY_CLEAR_DATA_ON_EXIT, false)
        set(value) = prefs.edit { putBoolean(KEY_CLEAR_DATA_ON_EXIT, value) }

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
        get() = prefs.getBoolean(KEY_ALLOW_MIXED_CONTENT, true)
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
        get() = prefs.getBoolean(KEY_AGGRESSIVE_ANTI_ADBLOCK, true)
        set(value) = prefs.edit { putBoolean(KEY_AGGRESSIVE_ANTI_ADBLOCK, value) }

    /**
     * Block WebRTC at the page level so STUN / TURN candidate gathering
     * can't leak the user's local IP address. Implementation is a
     * document-start scriptlet that replaces `RTCPeerConnection`
     * constructors with a stub that throws.
     *
     * Default **off**. Blocking WebRTC stubs RTCPeerConnection, which
     * many mic/camera/voice/video-call sites construct as part of their
     * normal flow — when it throws, those sites report "mic/camera not
     * enabled" even though the user granted permission. Functionality
     * wins for the default; the privacy-conscious can turn it on in
     * Settings. (Chrome and Brave both ship WebRTC enabled by default
     * for the same reason.)
     */
    var blockWebRtc: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_WEBRTC, false)
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

    /** Last target selected in the page translator. Empty means choose from
     *  the device locale, with English as the final fallback. */
    var translationTargetLanguage: String
        get() = prefs.getString(KEY_TRANSLATION_TARGET_LANGUAGE, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_TRANSLATION_TARGET_LANGUAGE, value) }

    var defaultBrowserPromptShown: Boolean
        get() = prefs.getBoolean(KEY_DEFAULT_BROWSER_PROMPT_SHOWN, false)
        set(value) = prefs.edit { putBoolean(KEY_DEFAULT_BROWSER_PROMPT_SHOWN, value) }

    var notificationPromptShown: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATION_PERMISSION_PROMPT_SHOWN, false)
        set(value) = prefs.edit { putBoolean(KEY_NOTIFICATION_PERMISSION_PROMPT_SHOWN, value) }

    /**
     * Has the user completed the first-launch onboarding flow
     * (welcome → privacy promise → terms acceptance → optional
     * set-as-default-browser)? When false, MainActivity defers normal
     * startup and launches WelcomeActivity instead. Set to true
     * exactly once, when the user taps "Accept &amp; continue" on
     * the privacy/terms screen.
     */
    var onboardingCompleted: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
        set(value) = prefs.edit { putBoolean(KEY_ONBOARDING_COMPLETED, value) }

    /**
     * Timestamp (epoch millis) when the user accepted the in-app
     * Terms of use. Stored alongside [onboardingCompleted] so that if
     * the terms text changes in a future update we can re-prompt
     * users who accepted an older version.
     */
    var termsAcceptedAt: Long
        get() = prefs.getLong(KEY_TERMS_ACCEPTED_AT, 0L)
        set(value) = prefs.edit { putLong(KEY_TERMS_ACCEPTED_AT, value) }

    /**
     * Stamp every "default on" toggle to disk *if it has never been
     * written before*. Idempotent and non-destructive: a key the user
     * has explicitly toggled (either direction) will already exist in
     * the backing prefs file, so [SharedPreferences.contains] returns
     * true and we leave it alone. Keys that the user has never seen
     * yet (fresh install, or new keys introduced by an app update)
     * land at our preferred default.
     *
     * This is called from two places:
     *  - [WelcomeActivity], when the user accepts the terms on a
     *    fresh install. Catches the cold-start case.
     *  - [MainActivity.onCreate], on every launch after the onboarding
     *    gate. Catches existing installs that upgrade to a version
     *    where one of these toggles became "default on" — without this
     *    pass, the user would see those toggles read as `true` from
     *    the new in-code default, then silently flip back to `false`
     *    the moment any code path wrote the prefs file (because the
     *    key is absent until written).
     *
     * Pre-1.0 there is no migration cost worth tracking; we just want
     * the user's first interactive session to start with the toggles
     * the welcome flow promised.
     */
    fun bootstrapDefaults() {
        prefs.edit {
            if (!prefs.contains(KEY_AD_BLOCK)) putBoolean(KEY_AD_BLOCK, true)
            if (!prefs.contains(KEY_SITE_BLOCK)) putBoolean(KEY_SITE_BLOCK, true)
            if (!prefs.contains(KEY_BLOCKLIST_AUTO_UPDATE)) putBoolean(KEY_BLOCKLIST_AUTO_UPDATE, true)
            if (!prefs.contains(KEY_ALLOW_MIXED_CONTENT)) putBoolean(KEY_ALLOW_MIXED_CONTENT, true)
            if (!prefs.contains(KEY_AGGRESSIVE_ANTI_ADBLOCK)) putBoolean(KEY_AGGRESSIVE_ANTI_ADBLOCK, true)
            // KEY_BLOCK_WEBRTC is intentionally NOT bootstrapped on — see
            // [blockWebRtc] KDoc. Blocking it breaks mic/camera/calls.
            if (!prefs.contains(KEY_TRIM_REFERRER)) putBoolean(KEY_TRIM_REFERRER, true)
            if (!prefs.contains(KEY_HISTORY_ENABLED)) putBoolean(KEY_HISTORY_ENABLED, true)
            if (!prefs.contains(KEY_SEARCH_SUGGESTIONS)) putBoolean(KEY_SEARCH_SUGGESTIONS, true)
            if (!prefs.contains(KEY_JS_ENABLED)) putBoolean(KEY_JS_ENABLED, true)
            if (!prefs.contains(KEY_BLOCK_POPUPS)) putBoolean(KEY_BLOCK_POPUPS, true)
            if (!prefs.contains(KEY_TAB_SLEEPING)) putBoolean(KEY_TAB_SLEEPING, true)
        }
    }

    /**
     * One-shot corrections for defaults that shipped wrong in an
     * earlier build and were persisted to disk before the fix.
     * Idempotent via a per-correction flag so it runs at most once.
     * Called from MainActivity.onCreate before bootstrapDefaults.
     *
     * Correction #1: an earlier build defaulted (and bootstrapped) the
     * WebRTC block ON, which made RTCPeerConnection throw and broke
     * mic / camera / voice / video-call sites that the user had
     * actually granted access to. We clear the stored value once so it
     * falls back to the new default (off). A user who deliberately
     * wants WebRTC blocked can re-enable it in Settings afterward — the
     * flag stops us clobbering that choice on subsequent launches.
     */
    fun migrateDefaults() {
        if (!prefs.getBoolean(KEY_WEBRTC_DEFAULT_CORRECTED, false)) {
            prefs.edit {
                remove(KEY_BLOCK_WEBRTC)
                putBoolean(KEY_WEBRTC_DEFAULT_CORRECTED, true)
            }
        }
    }

    /**
     * True until [markSitePermissionsReset] is called. Gates a one-time
     * wipe of [SitePermissionStore]. An earlier build let a single
     * "Block" tap persist a silent, permanent per-site denial — which
     * left sites like chatgpt.com auto-denied with no prompt and no way
     * back. The persistent-block behaviour is gone now (only
     * "Always allow" persists), but stale BLOCK entries from the old
     * build are still on disk; this flag drives clearing them once so
     * every site re-prompts cleanly.
     */
    val needsSitePermissionReset: Boolean
        get() = !prefs.getBoolean(KEY_SITE_PERMISSIONS_RESET, false)

    fun markSitePermissionsReset() {
        prefs.edit { putBoolean(KEY_SITE_PERMISSIONS_RESET, true) }
    }

    /**
     * v10 user-selectable accent colour. Stored as one of the keys
     * defined in [AccentTheme]; an unknown value falls back to the
     * terracotta default at read time so we never paint a missing
     * theme. Activities call [applyAccentTheme] in onCreate before
     * setContentView to honour this preference.
     */
    var accentKey: String
        get() = prefs.getString(KEY_ACCENT, AccentTheme.DEFAULT.key) ?: AccentTheme.DEFAULT.key
        set(value) = prefs.edit { putString(KEY_ACCENT, value) }

    companion object {
        private const val PREFS_NAME = "effective_browser_prefs"

        private const val KEY_SEARCH_ENGINE = "key_search_engine"
        private const val KEY_DESKTOP_MODE = "key_desktop_mode"
        private const val KEY_AD_BLOCK = "key_ad_block"
        private const val KEY_SITE_BLOCK = "key_site_block"
        private const val KEY_THIRD_PARTY_COOKIES = "key_third_party_cookies"
        private const val KEY_JS_ENABLED = "key_javascript_enabled"
        private const val KEY_HISTORY_ENABLED = "key_history_enabled"
        private const val KEY_SEARCH_SUGGESTIONS = "key_search_suggestions"
        private const val KEY_BLOCK_POPUPS = "key_block_popups"
        private const val KEY_TAB_SLEEPING = "key_tab_sleeping"
        private const val KEY_CLEAR_DATA_ON_EXIT = "key_clear_data_on_exit"
        private const val KEY_HOME_PAGE = "key_home_page"
        private const val KEY_TRANSLATION_TARGET_LANGUAGE = "key_translation_target_language"
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
        private const val KEY_ACCENT = "key_accent"
        private const val KEY_ONBOARDING_COMPLETED = "key_onboarding_completed"
        private const val KEY_TERMS_ACCEPTED_AT = "key_terms_accepted_at"
        private const val KEY_WEBRTC_DEFAULT_CORRECTED = "key_webrtc_default_corrected_v1"
        private const val KEY_SITE_PERMISSIONS_RESET = "key_site_permissions_reset_v1"

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
