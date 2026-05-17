package me.thimmaiah.effectivebrowser

import android.content.Context
import android.util.Log
import android.webkit.WebResourceResponse
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.IDN
import java.net.URI
import java.util.Locale

/**
 * Network- and DOM-level ad/tracker blocker.
 *
 * The implementation is intentionally pragmatic: rather than carry a full
 * Adblock-Plus filter parser plus updateable EasyList downloads (uBlock
 * Origin's approach), we ship a curated set of the highest-impact rules
 * derived from EasyList / EasyPrivacy / AdGuard Base, hand-tuned to avoid
 * the false positives that break login, payments, and captcha flows.
 *
 * The blocker covers three independent surfaces:
 *
 *  1. **Network blocking** – [findMatch] short-circuits `WebResourceRequest`s
 *     for the ad/tracker domain list and for URLs whose path/query carry an
 *     unambiguous ad-tech signal (e.g. `/pagead/`).
 *  2. **Cosmetic hiding** – CSS injected on every page (`cosmeticHidingCss`)
 *     hides ad containers that the page renders itself, even when the
 *     network request to fetch the ad was already blocked. Without this
 *     pages tend to leave blank rectangles where the ad used to be.
 *  3. **YouTube response pruning** – a document-start scriptlet
 *     ([youTubeResponsePruningScript]) strips the `adPlacements` /
 *     `playerAds` / `adSlots` keys out of `/youtubei/v1/player` responses
 *     so the YouTube web player never schedules pre-rolls or mid-rolls.
 */
object BrowserBlocker {

    /** Master switch for ad/tracker blocking. Toggled from the settings UI. */
    @Volatile
    var adBlockEnabled: Boolean = true

    /** Master switch for the user-managed site block list. */
    @Volatile
    var siteBlockEnabled: Boolean = true

    /**
     * Snapshot of the user's block list. Pushed in by
     * [BlockedSitesRepository] whenever the list changes so the blocker
     * doesn't have to touch SharedPreferences per request.
     */
    @Volatile
    var blockedDomains: Set<String> = emptySet()

    /**
     * Scheme the block page uses to ask the host activity to open a
     * specific settings screen. The path component is the action name —
     * see [Action] for the enumerated values.
     */
    const val ACTION_URL_PREFIX = "effbrowser-action:"

    enum class Action(val token: String) {
        OPEN_SETTINGS("settings"),
        OPEN_BLOCKLIST("blocklist"),
        ;

        companion object {
            fun fromToken(value: String?): Action? =
                entries.firstOrNull { it.token.equals(value, ignoreCase = true) }
        }
    }

    /**
     * Parsed contents of the bundled block list.
     *
     * The four collections used to be Kotlin string literals right here
     * in this file (~250 domains plus the path-signal lists). They now
     * live in `assets/blocklist.json` (loaded by [initialize]) so the
     * data is editable without recompiling, and a future build step or
     * network update can swap the file wholesale.
     */
    private class BlockListData(
        /** Content revision of this list. The network updater
         *  ([BlocklistUpdater]) only adopts a downloaded list whose
         *  version is strictly greater than the currently-effective one,
         *  and [initialize] prefers a cached list only when its version
         *  is >= the bundled asset's. 0 for [EMPTY]. */
        val version: Int,
        /** Ad / tracker / fingerprint hosts. Each entry matches itself
         *  and every subdomain. Flattened from the categorised JSON. */
        val adAndTrackerDomains: Set<String>,
        /** Hosts the built-in rules must never block (sign-in, payments,
         *  captcha). The user's own block list still overrides this. */
        val essentialAllowList: Set<String>,
        /** Path/query substrings that denote ad-tech traffic on any host. */
        val adPathSignals: List<String>,
        /** Path substrings dropped only for non-document subresources. */
        val subResourceOnlyPathSignals: List<String>,
    ) {
        companion object {
            /** Fail-open empty list — used when the bundled resource is
             *  missing or unparseable so the browser still loads pages,
             *  just without built-in ad/tracker blocking. */
            val EMPTY = BlockListData(0, emptySet(), emptySet(), emptyList(), emptyList())
        }
    }

    /**
     * Name of the bundled block list inside `src/main/assets/`.
     *
     * It lives in `assets/` rather than `src/main/resources/` (a plain
     * Java resource) because Java resources are *not* reliably packaged
     * into a release APK once `shrinkResources` is enabled — they
     * survive R8 but get dropped during the resource-shrink / packaging
     * step. `assets/` is the Android-blessed location for bundled data
     * files and is never touched by any shrinker.
     *
     * The tradeoff: loading an asset needs a `Context` (an
     * `AssetManager`), so production code must call [initialize] once
     * at startup. Unit tests can't get an `AssetManager` without
     * Robolectric, so [loadBlockListForTest] is the test seam — it
     * feeds the same JSON text straight to [parseBlockList].
     */
    private const val BLOCKLIST_ASSET_NAME = "blocklist.json"

    /**
     * Filename of the network-fetched block list inside the app's
     * private `filesDir`. Written atomically by [BlocklistUpdater] after
     * a successful, signature-verified download; read by [initialize] /
     * [reload] in preference to the bundled asset when its version is at
     * least as new.
     */
    const val BLOCKLIST_CACHE_NAME = "blocklist-cache.json"

    /**
     * The only `schemaVersion` this build knows how to parse. The
     * network updater rejects any downloaded list with a different
     * value so a future breaking layout change can't be force-fed to an
     * old client.
     */
    const val SUPPORTED_SCHEMA_VERSION = 1

    private const val LOG_TAG = "BrowserBlocker"

    /**
     * Parsed block list. Defaults to [BlockListData.EMPTY] so that any
     * [findMatch] call that lands before [initialize] simply fails open
     * (no built-in blocking) rather than NPEing. [initialize] is called
     * early in `MainActivity.onCreate`, well before any WebView exists.
     */
    @Volatile
    private var blockList: BlockListData = BlockListData.EMPTY

    @Volatile
    private var blockListLoaded = false

    /**
     * Content version of the block list currently in effect. Used by
     * [BlocklistUpdater] to decide whether a downloaded list is newer,
     * and by the Settings UI to show the user what they're running.
     */
    val currentBlocklistVersion: Int get() = blockList.version

    /**
     * Load the block list. Call once at app startup
     * (MainActivity.onCreate). Idempotent — subsequent calls are
     * no-ops, so it's safe if more than one entry point reaches it.
     *
     * Prefers the network-fetched cache over the bundled asset (see
     * [loadBestAvailable]). A load failure is swallowed (logged) and
     * leaves [blockList] as [BlockListData.EMPTY]: the browser still
     * works, it just doesn't apply the built-in ad/tracker rules.
     */
    @Synchronized
    fun initialize(context: Context) {
        if (blockListLoaded) return
        blockList = loadBestAvailable(context.applicationContext)
        blockListLoaded = true
    }

    /**
     * Force a re-load of the block list, ignoring the
     * already-initialised guard. Called by [BlocklistUpdater] right
     * after it writes a newer cache file so the new rules take effect
     * without an app restart.
     */
    @Synchronized
    fun reload(context: Context) {
        blockList = loadBestAvailable(context.applicationContext)
        blockListLoaded = true
    }

    /**
     * Pick the best available block list: the network-fetched cache if
     * it's present, parseable, and at least as new as the bundled
     * asset; otherwise the bundled asset; otherwise [BlockListData.EMPTY].
     *
     * The "at least as new" check matters because an app update can
     * ship a bundled asset that's newer than a long-stale cache — in
     * that case the freshly-installed asset should win.
     */
    private fun loadBestAvailable(appContext: Context): BlockListData {
        val bundled = try {
            appContext.assets.open(BLOCKLIST_ASSET_NAME)
                .bufferedReader(Charsets.UTF_8)
                .use { parseBlockList(it.readText()) }
        } catch (error: Exception) {
            Log.w(LOG_TAG, "Failed to load bundled block list; built-in blocking is off", error)
            BlockListData.EMPTY
        }

        val cached = try {
            val cacheFile = java.io.File(appContext.filesDir, BLOCKLIST_CACHE_NAME)
            if (cacheFile.isFile) {
                parseBlockList(cacheFile.readText(Charsets.UTF_8))
            } else {
                null
            }
        } catch (error: Exception) {
            // A corrupt cache shouldn't poison the browser — fall back
            // to the bundled asset. BlocklistUpdater will overwrite the
            // cache on its next successful run.
            Log.w(LOG_TAG, "Failed to load cached block list; using bundled asset", error)
            null
        }

        return if (cached != null && cached.version >= bundled.version) cached else bundled
    }

    /**
     * Test seam: feed the block-list JSON directly, bypassing the
     * `AssetManager` (unavailable in plain JVM unit tests). Production
     * code uses [initialize]; only [BrowserBlockerTest] /
     * [BlockListResourceTest] call this.
     */
    internal fun loadBlockListForTest(jsonText: String) {
        blockList = parseBlockList(jsonText)
        blockListLoaded = true
    }

    private fun parseBlockList(jsonText: String): BlockListData {
        val root = JSONObject(jsonText)
        return BlockListData(
            version = root.optInt("version", 0),
            // adAndTrackerDomains / essentialAllowList are categorised
            // objects in the JSON (the category keys double as inline
            // documentation). Flatten every category's array into one set.
            adAndTrackerDomains = flattenCategorisedHosts(root.getJSONObject("adAndTrackerDomains")),
            essentialAllowList = flattenCategorisedHosts(root.getJSONObject("essentialAllowList")),
            adPathSignals = root.getJSONArray("adPathSignals").toStringList(),
            subResourceOnlyPathSignals =
                root.getJSONArray("subResourceOnlyPathSignals").toStringList(),
        )
    }

    /** Flatten a `{ "category": ["host", ...], ... }` object into one set. */
    private fun flattenCategorisedHosts(categorised: JSONObject): Set<String> {
        val result = LinkedHashSet<String>()
        for (category in categorised.keys()) {
            val array = categorised.optJSONArray(category) ?: continue
            for (i in 0 until array.length()) {
                array.optString(i).takeIf { it.isNotBlank() }?.let(result::add)
            }
        }
        return result
    }

    private fun JSONArray.toStringList(): List<String> {
        val result = ArrayList<String>(length())
        for (i in 0 until length()) {
            optString(i).takeIf { it.isNotBlank() }?.let(result::add)
        }
        return result
    }

    private val responseHeaders = mapOf(
        "Cache-Control" to "no-store, no-cache, must-revalidate",
        "Pragma" to "no-cache",
        "X-Content-Type-Options" to "nosniff",
    )

    fun isSupportedBrowserScheme(url: String): Boolean {
        val scheme = parseUri(url)?.scheme?.lowercase(Locale.US)
        return scheme in setOf("http", "https", "about", "data", "javascript", "blob")
    }

    fun createBlockingResponse(url: String?, isMainFrame: Boolean): WebResourceResponse? {
        val match = findMatch(url, isMainFrame) ?: return null

        return when (match.kind) {
            BlockingKind.BLOCKED_SITE -> {
                if (isMainFrame) {
                    htmlResponse(
                        title = "Site blocked",
                        message = "${htmlEscape(match.host)} is on your blocked-sites list. " +
                            "Open Settings to remove it from the list, then reload the page.",
                        action = Action.OPEN_BLOCKLIST,
                        actionLabel = "Open blocked sites",
                    )
                } else {
                    emptyResponse(statusCode = 403, reasonPhrase = "Blocked")
                }
            }

            BlockingKind.AD_OR_TRACKER -> {
                if (isMainFrame) {
                    htmlResponse(
                        title = "Ad or tracker blocked",
                        message = "A built-in protection rule blocked ${htmlEscape(match.host)}. " +
                            "If this is breaking the page you need, you can turn off ad blocking in Settings.",
                        action = Action.OPEN_SETTINGS,
                        actionLabel = "Open settings",
                    )
                } else {
                    emptyResponse(statusCode = 204, reasonPhrase = "No Content")
                }
            }
        }
    }

    /**
     * Legacy overload — preserved for call sites that don't know whether
     * the request is for the main frame. Defaults to "yes" so we err on
     * the side of showing the user the friendly block page rather than a
     * silent 204.
     */
    internal fun findMatch(url: String?): BlockingMatch? = findMatch(url, isMainFrame = true)

    internal fun findMatch(url: String?, isMainFrame: Boolean): BlockingMatch? {
        val parsed = parseUri(url) ?: return null
        val host = normalizeHost(parsed.host) ?: return null

        // The user's explicit block list wins over everything else. If
        // someone adds google.com they expect it blocked, captcha cost
        // included.
        if (siteBlockEnabled && blockedDomains.isNotEmpty() && hostMatchesSuffixSet(host, blockedDomains)) {
            return BlockingMatch(kind = BlockingKind.BLOCKED_SITE, host = host)
        }

        // Essential allow list short-circuits the ad/tracker tier so login,
        // payments and captchas keep working.
        if (hostMatchesSuffixSet(host, blockList.essentialAllowList)) {
            return null
        }

        if (adBlockEnabled) {
            if (hostMatchesSuffixSet(host, blockList.adAndTrackerDomains)) {
                return BlockingMatch(kind = BlockingKind.AD_OR_TRACKER, host = host)
            }

            val path = (parsed.rawPath.orEmpty() + (parsed.rawQuery?.let { "?$it" } ?: ""))
                .lowercase(Locale.US)
            if (path.isNotEmpty()) {
                if (containsPathSignal(path, blockList.adPathSignals)) {
                    return BlockingMatch(kind = BlockingKind.AD_OR_TRACKER, host = host)
                }
                if (!isMainFrame && containsPathSignal(path, blockList.subResourceOnlyPathSignals)) {
                    return BlockingMatch(kind = BlockingKind.AD_OR_TRACKER, host = host)
                }
            }
        }

        return null
    }

    /**
     * CSS rules injected after page load to hide common ad slot containers
     * that survive network blocking (e.g. layout placeholders, in-feed ads,
     * sponsored sections rendered by the page itself).
     *
     * The selectors below are derived from EasyList element-hiding rules,
     * filtered to those that don't risk hiding legitimate content. Anything
     * that could collide with a generic class name (`.ad`, `.banner`) is
     * deliberately excluded — false positives on real content are worse
     * than a missed ad.
     */
    fun cosmeticHidingCss(): String = """
        /* ---------- Google Ads / DoubleClick ---------- */
        ins.adsbygoogle,
        iframe[src*="doubleclick.net"],
        iframe[src*="googlesyndication.com"],
        iframe[src*="googleadservices.com"],
        iframe[src*="ad.doubleclick"],
        iframe[id^="google_ads_iframe"],
        iframe[id^="aswift_"],
        iframe[name^="google_ads_iframe"],
        iframe[name^="aswift_"],
        div[id^="div-gpt-ad"],
        div[id^="google_ads_"],
        div[data-google-query-id],
        div[data-ad-slot],
        div[data-ad-client],
        div[data-adunit],
        div[data-ad-region],
        a[href*="googleadservices.com"],
        a[href*="doubleclick.net"],
        a[href*="googlesyndication.com"],

        /* ---------- Banner / ad-slot id naming conventions ---------- */
        div[id^="banner-ad-"],
        div[id^="ad-banner"],
        div[id^="dfp-ad-"],
        div[id^="adngin-"],
        div[id^="ad-container"],
        div[id^="ad-wrapper"],
        div[id^="adcontainer"],
        div[id^="adwrapper"],
        div[id^="ad_slot_"],
        div[id^="adslot_"],
        div[id^="ad-position-"],
        div[id$="-advert"],
        div[id$="-advertisement"],
        section[id^="ad-"],
        section[id$="-ad"],
        aside[id^="ad-"],
        aside[id$="-ad"],

        /* ---------- data-* ad signals (very high precision) ---------- */
        [data-ad-type],
        [data-ad-unit],
        [data-ad-name],
        [data-ad-targeting],
        [data-google-av-cxn],
        [data-prebid],
        [data-amp-ad],
        amp-ad,
        amp-embed,

        /* ---------- Outbrain / Taboola / Revcontent / MGID ---------- */
        div[id^="taboola-"],
        div[id*="-taboola-"],
        div[id^="outbrain-"],
        div[id*="-outbrain-"],
        div[id^="revcontent"],
        div[id*="revcontent_"],
        div[id^="MG_ID_"],
        .taboola-placement,
        .outbrain-placement,
        .OUTBRAIN,
        .ob_what,
        .trc_related_container,
        .trc_rbox_container,
        .trc-content-sponsored,
        [class*="revcontent-"],
        [class*="mgid-"],
        a[href*="taboola.com"],
        a[href*="outbrain.com"],
        a[href*="revcontent.com"],

        /* ---------- Semantic ad labels (high precision) ---------- */
        aside[aria-label*="advertisement" i],
        section[aria-label*="advertisement" i],
        aside[aria-label*="sponsored" i],
        section[aria-label*="sponsored" i],
        div[role="complementary"][aria-label*="advertisement" i],
        div[role="complementary"][aria-label*="sponsored" i],

        /* ---------- Sponsored / promoted content sections ---------- */
        .sponsored-content,
        .sponsored-post,
        .sponsored-section,
        .sponsored-results,
        .partner-content,
        .promo-section,
        .promoted-content,
        .ad-feedback,
        .advert-wrapper,
        .ad-zone,
        .ad-rail,
        .ad-rail-wrapper,
        .ads-sidebar,
        .sidebar-ads,
        .ads-wrapper,
        .ads-container,
        .top-ad,
        .bottom-ad,
        .footer-ad,
        .leaderboard-ad,
        .mpu-ad,
        .skyscraper-ad,
        .interstitial-ad,
        .sticky-ad,
        .sticky-ad-container,
        .floating-ad,

        /* ---------- YouTube native ads ---------- */
        ytd-display-ad-renderer,
        ytd-promoted-sparkles-text-search-renderer,
        ytd-promoted-sparkles-web-renderer,
        ytd-promoted-video-renderer,
        ytd-ad-slot-renderer,
        ytd-companion-slot-renderer,
        ytd-banner-promo-renderer,
        ytd-statement-banner-renderer,
        ytd-in-feed-ad-layout-renderer,
        ytd-merch-shelf-renderer,
        ytd-action-companion-ad-renderer,
        ytd-rich-section-renderer:has(ytd-statement-banner-renderer),
        ytm-promoted-sparkles-web-renderer,
        ytm-companion-slot,
        [is-ad="true"],
        [layout="search-ad"],

        /* ---------- YouTube in-player ad overlays ---------- */
        .ytp-ad-overlay-container,
        .ytp-ad-text-overlay,
        .ytp-ad-image-overlay,
        .ytp-ad-player-overlay,
        .ytp-ad-player-overlay-layout,
        .ytp-ad-player-overlay-instream-info,
        .ytp-ad-action-interstitial,
        .ytp-ad-message-container,
        .ytp-ad-preview-container,

        /* ---------- Twitter / X promoted tweets ---------- */
        [data-testid="placementTracking"],
        [data-testid="promoted-tweet-cluster"],
        [data-promoted-trend="true"],
        div[aria-label="Promoted"],

        /* ---------- Reddit promoted posts ---------- */
        shreddit-ad-post,
        shreddit-comments-page-ad,
        [data-promoted="true"],
        [data-before-content="advertisement"],
        .promotedlink,
        ._2oEYZXchPfHwcf9mTMGMg8, /* legacy "Promoted" card */

        /* ---------- LinkedIn / Facebook / Instagram sponsored ---------- */
        div[data-ad-preview="message"],
        div[data-pagelet="FeedUnit"][aria-label*="Sponsored" i],
        article[aria-label*="Sponsored" i],
        .feed-shared-update-v2:has([data-tracking-control-name*="sponsored"]),

        /* ---------- Newsletter / paywall nag overlays often paired
           with ad networks (high false-positive risk so we only target
           the explicit "newsletter-ad" / "subscription-cta-ad" classes;
           generic .modal is intentionally NOT included) ---------- */
        .newsletter-ad,
        .newsletter-promo-ad,
        .subscription-cta-ad,

        /* ---------- Generic ad-iframe pattern ---------- */
        iframe[src*="/ads/"],
        iframe[src*="/adserver/"],
        iframe[src*="/adframe"],
        iframe[src*="/advertising/"],
        iframe[id*="adfox"],
        iframe[id*="adlinks"],
        iframe[name*="advert" i]
        { display: none !important; visibility: hidden !important; height: 0 !important; min-height: 0 !important; }
    """.trimIndent()

    /**
     * Document-start scriptlet injected on YouTube. Monkey-patches fetch()
     * and XMLHttpRequest so the player's `/youtubei/v1/player` response
     * (and related) comes back with ad metadata stripped out. The player
     * then has nothing to schedule and no pre-roll / mid-roll plays.
     *
     * Same approach uBlock Origin and SponsorBlock-Ads use; this is a
     * stripped-down implementation that covers the common case without
     * chasing YouTube's nightly experiments.
     */
    fun youTubeResponsePruningScript(): String = cachedYouTubeScript

    /**
     * Document-start script that enforces a strict-origin-when-cross-origin
     * referrer policy and (optionally) neutralises WebRTC so the page
     * can't gather ICE candidates carrying the user's local IP.
     *
     * Two opt-outable behaviours, each gated by a flag. Both are
     * implemented in JS rather than via WebView settings because the
     * platform doesn't expose either as a knob — referrer policy is set
     * by page meta-tags / response headers, and WebView has no built-in
     * "disable WebRTC" switch the way Firefox's `media.peerconnection.enabled`
     * pref does.
     */
    fun privacyDocumentStartScript(trimReferrer: Boolean, blockWebRtc: Boolean): String {
        val key = (if (trimReferrer) 1 else 0) or (if (blockWebRtc) 2 else 0)
        return cachedPrivacyScripts.getOrPut(key) { renderPrivacyScript(trimReferrer, blockWebRtc) }
    }

    private val cachedPrivacyScripts = java.util.concurrent.ConcurrentHashMap<Int, String>()

    private fun renderPrivacyScript(trimReferrer: Boolean, blockWebRtc: Boolean): String {
        // Each clause is independently guarded so toggling one off
        // doesn't disable the other. The whole script is wrapped in a
        // try/catch so a broken assumption on one site never tanks the
        // others.
        val referrerBlock = if (trimReferrer) """
                try {
                    // Only inject if the page hasn't already set a policy
                    // — sites that *intentionally* opt out of stricter
                    // referrer policies usually do so for an OAuth flow
                    // or analytics they care about. We don't override
                    // explicit signals.
                    if (!document.querySelector('meta[name="referrer"]')) {
                        var m = document.createElement('meta');
                        m.name = 'referrer';
                        m.content = 'strict-origin-when-cross-origin';
                        (document.head || document.documentElement).appendChild(m);
                    }
                } catch (e) { /* ignore */ }
        """.trimIndent() else ""

        val webRtcBlock = if (blockWebRtc) """
                try {
                    // Replace every flavour of RTCPeerConnection with a
                    // constructor that throws. Throwing (rather than
                    // returning a no-op object) makes detection easy:
                    // sites that need WebRTC will see an exception in
                    // their try/catch and fall back to a non-RTC path
                    // (or surface a clear error message), instead of
                    // hanging waiting for ICE.
                    var blocked = function() {
                        throw new Error('WebRTC disabled by Effective Browser');
                    };
                    try { Object.defineProperty(window, 'RTCPeerConnection',
                        { configurable: false, writable: false, value: blocked }); } catch (e) {}
                    try { Object.defineProperty(window, 'webkitRTCPeerConnection',
                        { configurable: false, writable: false, value: blocked }); } catch (e) {}
                    try { Object.defineProperty(window, 'mozRTCPeerConnection',
                        { configurable: false, writable: false, value: blocked }); } catch (e) {}
                    // Also stub the data-channel constructor since some
                    // libraries probe for that as an "is WebRTC alive"
                    // check before instantiating the peer connection.
                    try { Object.defineProperty(window, 'RTCDataChannel',
                        { configurable: false, writable: false, value: blocked }); } catch (e) {}
                } catch (e) { /* ignore */ }
        """.trimIndent() else ""

        return """
            (function() {
                try {
$referrerBlock
$webRtcBlock
                } catch (e) { /* ignore */ }
            })();
        """.trimIndent()
    }

    // B19: pre-rendered once at class load. The string is ~5 KB and the
    // template build doesn't depend on any runtime state, so a single
    // `val` is the simplest cache.
    private val cachedYouTubeScript: String = """
        (function() {
            try {
                var host = (location && location.hostname || '').toLowerCase();
                if (!(host === 'youtube.com' || host === 'm.youtube.com' ||
                      host === 'www.youtube.com' || host.endsWith('.youtube.com'))) {
                    return;
                }

                var YT_API = /\/youtubei\/v1\/(player|next|browse|search|reel|guide|account_menu)/;

                // Fields anywhere in the response that we strip. Same list
                // uBlock prunes; safe to remove because the YouTube web app
                // gracefully handles them being absent.
                var AD_KEYS = [
                    'adPlacements',
                    'adSlots',
                    'playerAds',
                    'adBreakHeartbeatParams',
                    'adSafetyReason',
                    'adRequestParams',
                    'adVideoId',
                    'serviceTrackingParams', // includes ad-related tracking
                    'pcr',                   // ad-prefetch metadata
                    'addToWatchLaterCommand'
                ];

                function strip(obj, depth) {
                    if (!obj || typeof obj !== 'object' || depth > 12) return obj;
                    if (Array.isArray(obj)) {
                        for (var i = 0; i < obj.length; i++) strip(obj[i], depth + 1);
                        return obj;
                    }
                    for (var i = 0; i < AD_KEYS.length; i++) {
                        if (Object.prototype.hasOwnProperty.call(obj, AD_KEYS[i])) {
                            try { delete obj[AD_KEYS[i]]; } catch (e) {}
                        }
                    }
                    for (var k in obj) {
                        if (Object.prototype.hasOwnProperty.call(obj, k)) {
                            var v = obj[k];
                            if (v && typeof v === 'object') strip(v, depth + 1);
                        }
                    }
                    return obj;
                }

                function pruneJsonText(text) {
                    if (typeof text !== 'string' || text.length < 16) return text;
                    var trimmed = text.trimStart ? text.trimStart() : text;
                    if (trimmed[0] !== '{' && trimmed[0] !== '[') return text;
                    try {
                        var data = JSON.parse(text);
                        strip(data, 0);
                        return JSON.stringify(data);
                    } catch (e) {
                        return text;
                    }
                }

                // ---- fetch hook ----
                var origFetch = window.fetch;
                if (typeof origFetch === 'function') {
                    window.fetch = function(input, init) {
                        var url = '';
                        try {
                            url = typeof input === 'string'
                                ? input
                                : (input && (input.url || ''));
                        } catch (e) {}
                        var match = url && YT_API.test(url);
                        var p = origFetch.apply(this, arguments);
                        if (!match) return p;
                        return p.then(function(response) {
                            try {
                                if (!response || !response.ok) return response;
                                return response.clone().text().then(function(text) {
                                    var pruned = pruneJsonText(text);
                                    if (pruned === text) return response;
                                    return new Response(pruned, {
                                        status: response.status,
                                        statusText: response.statusText,
                                        headers: response.headers
                                    });
                                }, function() { return response; });
                            } catch (e) {
                                return response;
                            }
                        });
                    };
                }

                // ---- XMLHttpRequest hook ----
                var origOpen = XMLHttpRequest.prototype.open;
                XMLHttpRequest.prototype.open = function(method, url) {
                    try { this.__eb_url = url; } catch (e) {}
                    return origOpen.apply(this, arguments);
                };

                var responseDesc = Object.getOwnPropertyDescriptor(
                    XMLHttpRequest.prototype, 'responseText'
                );
                var responseRawDesc = Object.getOwnPropertyDescriptor(
                    XMLHttpRequest.prototype, 'response'
                );
                if (responseDesc && typeof responseDesc.get === 'function') {
                    Object.defineProperty(XMLHttpRequest.prototype, 'responseText', {
                        get: function() {
                            var raw = responseDesc.get.call(this);
                            if (this.__eb_url && YT_API.test(this.__eb_url)) {
                                return pruneJsonText(raw);
                            }
                            return raw;
                        },
                        configurable: true
                    });
                }
                if (responseRawDesc && typeof responseRawDesc.get === 'function') {
                    Object.defineProperty(XMLHttpRequest.prototype, 'response', {
                        get: function() {
                            var raw = responseRawDesc.get.call(this);
                            if (this.__eb_url && YT_API.test(this.__eb_url) &&
                                typeof raw === 'string') {
                                return pruneJsonText(raw);
                            }
                            return raw;
                        },
                        configurable: true
                    });
                }

                // ---- Static ytInitialPlayerResponse object ----
                // YouTube embeds the initial player response in the HTML.
                // We can't intercept that, but the watch-page version is
                // assigned to window.ytInitialPlayerResponse — overwrite it
                // when the page sets it.
                var ytpr;
                try {
                    Object.defineProperty(window, 'ytInitialPlayerResponse', {
                        configurable: true,
                        get: function() { return ytpr; },
                        set: function(v) {
                            try { strip(v, 0); } catch (e) {}
                            ytpr = v;
                        }
                    });
                } catch (e) { /* ignore */ }
            } catch (outer) {
                /* Top-level catch so a single broken assumption never
                   tanks the whole page. */
            }
        })();
    """.trimIndent()

    /**
     * Scriptlet injected on every page load after the DOM is ready. Applies
     * [cosmeticHidingCss], stubs out common adsbygoogle, runs a YouTube
     * skip-ad auto-clicker, and neutralises the most common anti-adblock
     * detection scripts so the page doesn't replace its content with a
     * "please disable your ad blocker" overlay.
     *
     * @param aggressive when true, additionally monkey-patches
     *   `window.getComputedStyle` so anti-adblock bait elements report
     *   visible dimensions. This is the most invasive hook — class-name
     *   collisions can affect legitimate pages — so the setting is opt-in
     *   (V10 from the audit). The narrower stubs (BlockAdBlock,
     *   FuckAdBlock, etc.) target specific known globals and run
     *   regardless because they're collision-safe.
     */
    fun cosmeticHidingScript(aggressive: Boolean = false): String {
        // B19: cache the per-mode rendered scripts so the CSS escape pass
        // and template assembly only happen once per process lifetime.
        // Two variants exist (aggressive on / off) so a Map keyed by the
        // flag covers all callers.
        cachedCosmeticHidingScripts[aggressive]?.let { return it }
        val rendered = renderCosmeticHidingScript(aggressive)
        cachedCosmeticHidingScripts[aggressive] = rendered
        return rendered
    }

    private val cachedCosmeticHidingScripts = java.util.concurrent.ConcurrentHashMap<Boolean, String>()

    private fun renderCosmeticHidingScript(aggressive: Boolean): String {
        val css = cosmeticHidingCss()
            .replace("\\", "\\\\")
            .replace("`", "\\`")
            .replace("$", "\\$")
        // Gated block: monkey-patches getComputedStyle to lie about
        // ad-baity elements' dimensions. Default off because publishers
        // aren't the only code that calls getComputedStyle — a
        // legitimate component whose className contains "adsbox" or
        // "ad-banner" for non-ad reasons would also see fake heights.
        // The user opts in via Settings → Aggressive anti-adblock.
        val computedStyleHook = if (aggressive) """
                        var BAIT_TOKENS = ['adsbox', 'ad-banner', 'ad-placeholder', 'ad_unit', 'sponsored-content'];
                        var origGetCS = window.getComputedStyle;
                        if (typeof origGetCS === 'function') {
                            window.getComputedStyle = function(el, pseudo) {
                                var cs = origGetCS.call(this, el, pseudo);
                                try {
                                    if (el && el.className) {
                                        var cn = String(el.className);
                                        for (var i = 0; i < BAIT_TOKENS.length; i++) {
                                            if (cn.indexOf(BAIT_TOKENS[i]) !== -1) {
                                                return new Proxy(cs, {
                                                    get: function(target, prop) {
                                                        if (prop === 'display') return 'block';
                                                        if (prop === 'visibility') return 'visible';
                                                        if (prop === 'height') return '100px';
                                                        return target[prop];
                                                    }
                                                });
                                            }
                                        }
                                    }
                                } catch (e) {}
                                return cs;
                            };
                        }
        """.trimIndent() else ""
        return """
            (function() {
                try {
                    var styleId = '__eb_cosmetic_style__';
                    if (!document.getElementById(styleId)) {
                        var s = document.createElement('style');
                        s.id = styleId;
                        s.textContent = `$css`;
                        (document.head || document.documentElement).appendChild(s);
                    }
                    // Neutralise common adsbygoogle stub.
                    if (window.adsbygoogle && typeof window.adsbygoogle.push === 'function') {
                        window.adsbygoogle = { loaded: true, push: function() {} };
                    }

                    // Anti-anti-adblock — stub out the most common detection
                    // hooks that publishers use to swap the page out for a
                    // "please disable your adblocker" splash. Each of these
                    // is read by detector libraries (BlockAdBlock,
                    // FuckAdBlock, AdBlock-Notify, sgpb, etc.) and false
                    // results are interpreted as "ad blocker is off".
                    // These stubs target *specific* global names so they
                    // can't collide with anything legitimate the page
                    // might define.
                    try {
                        var noop = function() {};
                        var noopReturn = function() { return this; };
                        var BlockAdBlockStub = function() {
                            this.setOption = noopReturn;
                            this.onDetected = noopReturn;
                            this.onNotDetected = function(cb) {
                                try { if (typeof cb === 'function') cb(); } catch (e) {}
                                return this;
                            };
                            this.on = function(name, cb) {
                                if (name === 'detected') return this;
                                try { if (typeof cb === 'function') cb(); } catch (e) {}
                                return this;
                            };
                            this.emit = noop;
                            this.check = noop;
                        };
                        try { window.BlockAdBlock = BlockAdBlockStub; } catch (e) {}
                        try { window.FuckAdBlock = BlockAdBlockStub; } catch (e) {}
                        try { window.blockAdBlock = new BlockAdBlockStub(); } catch (e) {}
                        try { window.fuckAdBlock = new BlockAdBlockStub(); } catch (e) {}
                        try { window.adblockDetector = { init: noop }; } catch (e) {}
                        try { window.canRunAds = true; } catch (e) {}
                        try { window.isAdBlockActive = false; } catch (e) {}
                        try { window.adsbygoogle = window.adsbygoogle || { loaded: true, push: noop }; } catch (e) {}

$computedStyleHook
                    } catch (e) { /* ignore — anti-detection is best-effort */ }

                    // YouTube skip-ad button auto-clicker. Safety net for
                    // any ad that slips past the document-start API filter.
                    // Deliberately *only* clicks the skip button — no mute,
                    // no playback-rate hacks, since those interfere with
                    // the real video and false-positive on ad-showing
                    // transitions.
                    var host = (location.hostname || '').toLowerCase();
                    var isYouTube = host === 'youtube.com' ||
                        host === 'm.youtube.com' ||
                        host === 'www.youtube.com' ||
                        host.endsWith('.youtube.com');
                    if (isYouTube && !window.__eb_yt_ad_skipper__) {
                        window.__eb_yt_ad_skipper__ = setInterval(function() {
                            try {
                                var skip = document.querySelector(
                                    '.ytp-ad-skip-button-modern, .ytp-ad-skip-button, ' +
                                    '.ytp-skip-ad-button, .videoAdUiSkipButton'
                                );
                                if (skip) skip.click();

                                // If an ad is currently playing, fast-forward
                                // it to the end. The "ad-showing" class is
                                // set on the player container by YouTube
                                // itself; we never touch a regular video.
                                var player = document.querySelector('.html5-video-player.ad-showing');
                                if (player) {
                                    var video = player.querySelector('video');
                                    if (video && isFinite(video.duration) && video.duration > 0) {
                                        // Jump to the end; YouTube treats that
                                        // as the ad finishing.
                                        video.currentTime = video.duration;
                                    }
                                }
                            } catch (e) { /* ignore */ }
                        }, 400);
                    }
                } catch (e) { /* ignore */ }
            })();
        """.trimIndent()
    }

    /**
     * Returns true when [host] exactly matches an entry in [domainSet] or
     * is a subdomain of one. Lookup is O(depth) — we slice the host on each
     * dot and check the suffix in the hash set, rather than iterating
     * `domainSet` for each request. With the expanded list now in the
     * thousands of suffix entries this matters.
     */
    private fun hostMatchesSuffixSet(host: String, domainSet: Set<String>): Boolean {
        if (host.isEmpty() || domainSet.isEmpty()) return false
        if (domainSet.contains(host)) return true

        var index = host.indexOf('.')
        while (index in 0 until host.length - 1) {
            val suffix = host.substring(index + 1)
            if (domainSet.contains(suffix)) return true
            index = host.indexOf('.', index + 1)
        }
        return false
    }

    private fun containsPathSignal(path: String, signals: List<String>): Boolean {
        return signals.any(path::contains)
    }

    private fun parseUri(url: String?): URI? {
        if (url.isNullOrBlank()) {
            return null
        }

        return try {
            URI(url.trim())
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeHost(host: String?): String? {
        if (host.isNullOrBlank()) {
            return null
        }

        // Broad catch: IDN.toASCII throws IllegalArgumentException for
        // most malformed inputs, but malformed Punycode can surface as
        // other RuntimeException subclasses (e.g. internal ArrayIndex
        // from the Punycode decoder on certain crafted inputs). A throw
        // out of normalizeHost would crash the request thread mid-load.
        return try {
            IDN.toASCII(host.trim()).lowercase(Locale.US)
        } catch (_: Exception) {
            host.trim().lowercase(Locale.US)
        }
    }

    private fun htmlResponse(
        title: String,
        message: String,
        action: Action?,
        actionLabel: String?,
    ): WebResourceResponse {
        val actionBlock = if (action != null && !actionLabel.isNullOrBlank()) {
            val href = htmlEscape("$ACTION_URL_PREFIX${action.token}")
            val label = htmlEscape(actionLabel)
            """<a class="action" href="$href">$label</a>"""
        } else {
            ""
        }

        val html = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="utf-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1" />
                <title>$title</title>
                <style>
                    :root { color-scheme: light dark; }
                    * { box-sizing: border-box; }
                    body {
                        margin: 0;
                        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                        background: #0b1020;
                        color: #e6edf7;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        min-height: 100vh;
                        padding: 24px;
                    }
                    @media (prefers-color-scheme: light) {
                        body { background: #f6f8fc; color: #0b1020; }
                        .card { background: #ffffff; border-color: #e2e8f0; }
                        .badge { background: #eef2ff; color: #4338ca; }
                        .action { background: #4338ca; color: #ffffff; }
                        .action:active { background: #3730a3; }
                    }
                    .card {
                        max-width: 520px;
                        width: 100%;
                        background: #111a30;
                        border: 1px solid #1f2a44;
                        border-radius: 16px;
                        padding: 28px;
                    }
                    .badge {
                        display: inline-block;
                        font-size: 11px;
                        font-weight: 600;
                        letter-spacing: 0.08em;
                        text-transform: uppercase;
                        background: #1f2a44;
                        color: #93c5fd;
                        padding: 4px 10px;
                        border-radius: 999px;
                        margin-bottom: 16px;
                    }
                    h1 {
                        margin: 0 0 10px;
                        font-size: 22px;
                        font-weight: 600;
                        letter-spacing: -0.01em;
                    }
                    p {
                        margin: 0;
                        line-height: 1.55;
                        font-size: 15px;
                        opacity: 0.9;
                    }
                    .action {
                        display: inline-block;
                        margin-top: 20px;
                        padding: 11px 20px;
                        border-radius: 999px;
                        background: #93c5fd;
                        color: #0b1020;
                        text-decoration: none;
                        font-weight: 600;
                        font-size: 14px;
                    }
                    .action:active { opacity: 0.85; }
                </style>
            </head>
            <body>
                <div class="card">
                    <span class="badge">Effective Browser</span>
                    <h1>$title</h1>
                    <p>$message</p>
                    $actionBlock
                </div>
            </body>
            </html>
        """.trimIndent()

        return WebResourceResponse(
            "text/html",
            "utf-8",
            403,
            "Blocked",
            responseHeaders,
            ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)),
        )
    }

    private fun emptyResponse(statusCode: Int, reasonPhrase: String): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            statusCode,
            reasonPhrase,
            responseHeaders,
            ByteArrayInputStream(ByteArray(0)),
        )
    }

    private fun htmlEscape(input: String): String {
        return input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    internal data class BlockingMatch(
        val kind: BlockingKind,
        val host: String,
    )

    internal enum class BlockingKind {
        BLOCKED_SITE,
        AD_OR_TRACKER,
    }
}
