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
     * uBO/EasyList network filter engine, built off the main thread from the
     * bundled ad lists (`assets/filters/`) plus any on-demand lists cached in
     * `filesDir/filters/`. Null until the background build finishes — requests
     * before then fall back to the curated [blockList] rules below.
     */
    @Volatile
    private var filterEngine: FilterEngine? = null

    @Volatile
    private var filterEngineLoading = false

    /** Bundled ad lists live here; fetched lists are cached under the same name in filesDir. */
    private const val FILTERS_ASSET_DIR = "filters"
    const val FILTERS_CACHE_DIR = "filters"

    /** Network filters currently loaded, for the Settings UI. 0 until built. */
    val filterCount: Int get() = filterEngine?.filterCount ?: 0

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
        loadFilterEngineAsync(context.applicationContext)
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
        filterEngineLoading = false
        loadFilterEngineAsync(context.applicationContext)
    }

    /**
     * Build the [FilterEngine] off the main thread. Parsing the bundled ad
     * lists (~100k+ rules) takes a few hundred ms, so it must never run on the
     * UI thread. Idempotent while a build is in flight.
     */
    private fun loadFilterEngineAsync(appContext: Context) {
        if (filterEngineLoading) return
        filterEngineLoading = true
        Thread({
            // Parsing the large bundled filter lists is deliberately
            // best-effort and must not compete with first paint, tab setup, or
            // initial media decoding. Requests continue to use the curated
            // rules until this background build is ready.
            android.os.Process.setThreadPriority(
                android.os.Process.THREAD_PRIORITY_BACKGROUND,
            )
            val startedAt = android.os.SystemClock.elapsedRealtime()
            try {
                filterEngine = buildFilterEngine(appContext)
            } catch (error: Exception) {
                Log.w(LOG_TAG, "Filter engine build failed; using curated rules only", error)
            } finally {
                filterEngineLoading = false
                Log.d(
                    LOG_TAG,
                    "Filter engine background build took " +
                        "${android.os.SystemClock.elapsedRealtime() - startedAt} ms",
                )
            }
        }, "ebors-filter-engine").start()
    }

    private fun buildFilterEngine(appContext: Context): FilterEngine {
        val builder = FilterEngine.Builder()
        val assets = appContext.assets
        val bundled = try {
            assets.list(FILTERS_ASSET_DIR)?.toList().orEmpty()
        } catch (error: Exception) {
            emptyList()
        }
        for (name in bundled) {
            if (!name.endsWith(".txt")) continue
            try {
                assets.open("$FILTERS_ASSET_DIR/$name")
                    .bufferedReader(Charsets.UTF_8).use { builder.addList(it.readText()) }
            } catch (error: Exception) {
                Log.w(LOG_TAG, "Skipping bundled filter list $name", error)
            }
        }
        val cacheDir = java.io.File(appContext.filesDir, FILTERS_CACHE_DIR)
        if (cacheDir.isDirectory) {
            cacheDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(".txt")) {
                    try {
                        builder.addList(file.readText(Charsets.UTF_8))
                    } catch (error: Exception) {
                        Log.w(LOG_TAG, "Skipping fetched filter list ${file.name}", error)
                    }
                }
            }
        }
        val engine = builder.build()
        Log.i(LOG_TAG, "Filter engine ready: ${engine.filterCount} network filters")
        return engine
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

    fun createBlockingResponse(url: String?, isMainFrame: Boolean): WebResourceResponse? =
        createBlockingResponse(url, isMainFrame, null, null)

    fun createBlockingResponse(
        url: String?,
        isMainFrame: Boolean,
        requestHeaders: Map<String, String>?,
        documentHost: String?,
    ): WebResourceResponse? {
        val match = findMatch(url, isMainFrame, requestHeaders, documentHost) ?: return null

        // v10 start-page "trackers blocked today" counter. Increment
        // happens regardless of whether this is the user's site block
        // list (BLOCKED_SITE) or the built-in ad/tracker list — both
        // count as "something we kept off your screen" from the user's
        // perspective.
        TrackerStats.recordBlock()

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
    internal fun findMatch(url: String?): BlockingMatch? = findMatch(url, isMainFrame = true, null, null)

    internal fun findMatch(url: String?, isMainFrame: Boolean): BlockingMatch? =
        findMatch(url, isMainFrame, null, null)

    internal fun findMatch(
        url: String?,
        isMainFrame: Boolean,
        requestHeaders: Map<String, String>?,
        documentHost: String?,
    ): BlockingMatch? {
        val originalUrl = url ?: return null
        val parsed = parseUri(originalUrl) ?: return null
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
            // uBO/EasyList engine first — its @@ exceptions can explicitly allow.
            filterEngine?.let { engine ->
                val sourceHost = normalizeHost(documentHost)
                val ctx = RequestContext(
                    url = originalUrl,
                    host = host,
                    type = inferRequestType(requestHeaders, isMainFrame, parsed),
                    sourceHost = sourceHost ?: host,
                    thirdParty = isThirdParty(host, sourceHost),
                )
                when (engine.match(ctx)) {
                    Decision.BLOCK -> return BlockingMatch(BlockingKind.AD_OR_TRACKER, host)
                    Decision.ALLOW -> return null
                    Decision.NONE -> { /* fall through to curated rules */ }
                }
            }

            if (hostMatchesSuffixSet(host, blockList.adAndTrackerDomains)) {
                return BlockingMatch(kind = BlockingKind.AD_OR_TRACKER, host = host)
            }

            val path = (parsed.rawPath.orEmpty() + (parsed.rawQuery?.let { "?$it" } ?: ""))
                .lowercase(Locale.US)
            if (isAllowedFirstPartyStartupRequest(host, path)) {
                return null
            }
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

    private fun isAllowedFirstPartyStartupRequest(host: String, path: String): Boolean {
        if (!isYouTubeHost(host)) return false

        // Mobile YouTube waits on this first-party Innertube event stream
        // during watch startup. Blocking it makes the player retry with a
        // visible 4-10s delay, while the actual ad surfaces are still handled
        // by DoubleClick/pagead host rules and the player-response pruner.
        return path.startsWith("/youtubei/v1/log_event")
    }

    private fun isYouTubeHost(host: String): Boolean =
        host == "youtube.com" ||
            host == "m.youtube.com" ||
            host == "www.youtube.com" ||
            host.endsWith(".youtube.com")

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
                        throw new Error('WebRTC disabled by Ebors');
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

    // B19: pre-rendered once at class load; the template has no runtime
    // state so a single `val` caches it. Redesigned for latency — only
    // /youtubei/v1/player (and Shorts reel) responses are inspected, a cheap
    // substring pre-check skips JSON parsing unless an ad is actually
    // present, and XHR reads are memoised — so a tapped video starts with no
    // added delay while ads are still stripped.
    private val cachedYouTubeScript: String = """
        (function() {
            try {
                var host = (location && location.hostname || '').toLowerCase();
                if (!(host === 'youtube.com' || host === 'm.youtube.com' ||
                      host === 'www.youtube.com' || host.endsWith('.youtube.com'))) {
                    return;
                }

                // Watch-config endpoints that carry pre-roll / mid-roll ad
                // scheduling. CRITICAL: mobile m.youtube.com uses get_watch (not
                // player) — missing it meant ads were never stripped on mobile,
                // so the ad creative had to download + auto-skip before the real
                // video began (the 4-16s delay). Desktop uses player; Shorts use
                // reel_*. Feed/search/guide ads stay cosmetic (CSS), so we don't
                // parse those large, frequent responses.
                var YT_PLAYER_API = /\/youtubei\/v1\/(player|get_watch|reel_item_watch|reel_watch_sequence)/;

                // Ad-scheduling keys only. Kept tiny so the substring pre-check
                // below can skip JSON.parse on any response that has no ads.
                // (Notably no serviceTrackingParams/addToWatchLaterCommand —
                // those aren't ad scheduling and stripping them only added
                // cost and, for watch-later, broke a real button.)
                var AD_KEYS = ['adPlacements', 'playerAds', 'adSlots', 'adBreakHeartbeatParams'];

                function hasAdKeys(text) {
                    return text.indexOf('adPlacements') !== -1 ||
                           text.indexOf('playerAds') !== -1 ||
                           text.indexOf('adSlots') !== -1;
                }

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
                    // Fast path: no ad keys in the raw text -> return it
                    // untouched, skipping JSON.parse / stringify entirely. This
                    // is what keeps video start instant: the player response is
                    // only parsed on the rare load that actually carries an ad.
                    if (typeof text !== 'string' || text.length < 16 || !hasAdKeys(text)) {
                        return text;
                    }
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
                        var p = origFetch.apply(this, arguments);
                        if (!url || !YT_PLAYER_API.test(url)) return p;
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
                            if (!this.__eb_url || !YT_PLAYER_API.test(this.__eb_url)) return raw;
                            // Cache by raw identity so repeated reads of the
                            // same body never re-parse (the player reads its
                            // response more than once).
                            if (this.__eb_text_raw === raw) return this.__eb_text_out;
                            this.__eb_text_raw = raw;
                            this.__eb_text_out = pruneJsonText(raw);
                            return this.__eb_text_out;
                        },
                        configurable: true
                    });
                }
                if (responseRawDesc && typeof responseRawDesc.get === 'function') {
                    Object.defineProperty(XMLHttpRequest.prototype, 'response', {
                        get: function() {
                            var raw = responseRawDesc.get.call(this);
                            if (!this.__eb_url || !YT_PLAYER_API.test(this.__eb_url) ||
                                typeof raw !== 'string') return raw;
                            if (this.__eb_resp_raw === raw) return this.__eb_resp_out;
                            this.__eb_resp_raw = raw;
                            this.__eb_resp_out = pruneJsonText(raw);
                            return this.__eb_resp_out;
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

        // --- Autoplay nudge -------------------------------------------------
        // A watch page opened in a new/background tab loads via loadUrl with no
        // user activation in this WebView, so YouTube won't start the video on
        // its own (it shows the big play button). Gesture enforcement is already
        // off app-side, so start it once. We never fight a user pause: only a
        // video that has not begun (paused at time 0) is nudged, and we stop as
        // soon as it is playing.
        (function() {
            try {
                var host = (location.hostname || '').toLowerCase();
                if (host.indexOf('youtube.com') === -1) return;
                if (window.__eb_yt_autoplay__) return;
                window.__eb_yt_autoplay__ = true;
                var tries = 0;
                var timer = setInterval(function() {
                    try {
                        if (++tries > 40) { clearInterval(timer); return; }
                        var path = location.pathname || '';
                        if (path.indexOf('/watch') !== 0 && path.indexOf('/shorts') !== 0) return;
                        var v = document.querySelector('video');
                        if (!v) return;
                        if (!v.paused) { clearInterval(timer); return; }
                        if (v.currentTime === 0 && !v.ended) {
                            var p = v.play();
                            if (p && p.catch) {
                                p.catch(function() {
                                    var b = document.querySelector('.ytp-large-play-button, .ytp-play-button');
                                    if (b) b.click();
                                });
                            }
                        }
                    } catch (e) {}
                }, 250);
            } catch (e) {}
        })();
    """.trimIndent()

    /**
     * Scriptlet injected at document-start on every page (with an
     * onPageFinished fallback on WebViews lacking DOCUMENT_START_SCRIPT).
     * Applies [cosmeticHidingCss], stubs out common adsbygoogle, runs a
     * YouTube skip-ad auto-clicker, and neutralises the most common
     * anti-adblock detection scripts so the page doesn't replace its content
     * with a "please disable your ad blocker" overlay.
     *
     * Running at document-start matters: the `<style>` lands before ad
     * containers paint (no flash), and the detector stubs are defined
     * before the page's own scripts probe for them. The body only uses
     * APIs available that early (`document.documentElement`, `setInterval`,
     * `location`) and inserts its `<style>` into `document.head ||
     * document.documentElement`, so it is safe before `<head>` exists.
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
                        // Skip YouTube: it never shows adblock walls, and wrapping
                        // getComputedStyle (which its player UI calls constantly)
                        // only adds latency to video start.
                        if (typeof origGetCS === 'function' &&
                            (location.hostname || '').toLowerCase().indexOf('youtube.com') === -1) {
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
                        try { window.canShowAds = true; } catch (e) {}
                        try { window.isAdBlockActive = false; } catch (e) {}
                        try { window.adblockDetected = false; } catch (e) {}
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

    /** Normalised host of [url], or null for non-network URLs (about:, data:…). */
    fun hostOf(url: String?): String? = normalizeHost(parseUri(url)?.host)

    /**
     * A small public-suffix table for registrable-domain extraction. Not the
     * full PSL — covers the common multi-label TLDs so first-/third-party
     * classification and `$domain=` don't mis-split e.g. `bbc.co.uk`. A full
     * PSL is a future upgrade.
     */
    private val MULTI_PART_SUFFIXES = setOf(
        "co.uk", "org.uk", "gov.uk", "ac.uk", "me.uk", "ltd.uk", "plc.uk", "net.uk",
        "com.au", "net.au", "org.au", "edu.au", "gov.au", "id.au",
        "co.jp", "or.jp", "ne.jp", "ac.jp", "go.jp", "co.kr", "or.kr",
        "com.cn", "net.cn", "org.cn", "gov.cn", "com.hk", "com.tw", "com.sg",
        "com.br", "net.br", "org.br", "gov.br", "com.mx", "com.ar", "com.co",
        "co.in", "net.in", "org.in", "gen.in", "firm.in", "co.za", "org.za",
        "com.tr", "com.ua", "com.pl", "com.ru", "com.sa", "com.eg", "com.ng",
        "co.nz", "org.nz", "co.id", "co.th", "in.th", "com.my", "com.ph", "com.pk",
        "com.vn", "com.bd", "com.np", "com.kw", "com.qa", "com.lb", "co.il",
    )

    /** Registrable ("eTLD+1") domain of [host], using [MULTI_PART_SUFFIXES]. */
    fun registrableDomain(host: String?): String? {
        if (host.isNullOrEmpty()) return null
        val labels = host.split('.')
        if (labels.size <= 2) return host
        val lastTwo = labels.takeLast(2).joinToString(".")
        return if (lastTwo in MULTI_PART_SUFFIXES) labels.takeLast(3).joinToString(".") else lastTwo
    }

    /**
     * True when [host] is a different registrable domain than [sourceHost].
     * An unknown source (null) is treated as first-party so we never block a
     * page's own resources just because the document host wasn't known yet.
     */
    private fun isThirdParty(host: String, sourceHost: String?): Boolean {
        if (sourceHost.isNullOrEmpty()) return false
        val a = registrableDomain(host) ?: return false
        val b = registrableDomain(sourceHost) ?: return false
        return !a.equals(b, ignoreCase = true)
    }

    /**
     * Map a WebView request onto an EasyList `$type`. Modern WebView sends
     * `Sec-Fetch-Dest`, which is authoritative; we fall back to the main-frame
     * flag and a file-extension heuristic when it's absent.
     */
    private fun inferRequestType(
        headers: Map<String, String>?,
        isMainFrame: Boolean,
        parsed: URI,
    ): RequestType {
        when (headerValue(headers, "Sec-Fetch-Dest")?.lowercase(Locale.US)) {
            "document" -> return RequestType.DOCUMENT
            "iframe", "frame" -> return RequestType.SUBDOCUMENT
            "script", "serviceworker", "sharedworker", "worker" -> return RequestType.SCRIPT
            "style" -> return RequestType.STYLESHEET
            "image" -> return RequestType.IMAGE
            "font" -> return RequestType.FONT
            "audio", "video", "track" -> return RequestType.MEDIA
            "object", "embed" -> return RequestType.OBJECT
            "empty" -> return RequestType.XHR
        }
        if (isMainFrame) return RequestType.DOCUMENT
        val path = parsed.rawPath.orEmpty().lowercase(Locale.US)
        return when {
            path.endsWith(".js") || path.endsWith(".mjs") -> RequestType.SCRIPT
            path.endsWith(".css") -> RequestType.STYLESHEET
            path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") ||
                path.endsWith(".gif") || path.endsWith(".webp") || path.endsWith(".svg") ||
                path.endsWith(".ico") || path.endsWith(".bmp") -> RequestType.IMAGE
            path.endsWith(".woff") || path.endsWith(".woff2") || path.endsWith(".ttf") ||
                path.endsWith(".otf") || path.endsWith(".eot") -> RequestType.FONT
            path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".m4a") ||
                path.endsWith(".mp3") || path.endsWith(".ogg") || path.endsWith(".m3u8") ||
                path.endsWith(".ts") -> RequestType.MEDIA
            else -> RequestType.OTHER
        }
    }

    /** Case-insensitive header lookup ([WebResourceRequest] preserves sent case). */
    private fun headerValue(headers: Map<String, String>?, name: String): String? {
        if (headers.isNullOrEmpty()) return null
        headers[name]?.let { return it }
        for ((key, value) in headers) if (key.equals(name, ignoreCase = true)) return value
        return null
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
                    /* Palette mirrors the app's warm paper/terracotta
                       theme (values + values-night browser_* tokens) so
                       the block page matches the browser chrome. Dark is
                       the default (the app ships dark-on); a light media
                       override tracks the day palette. */
                    :root { color-scheme: light dark; }
                    * { box-sizing: border-box; }
                    body {
                        margin: 0;
                        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                        background: #211C16;
                        color: #F0E8D8;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        min-height: 100vh;
                        padding: 24px;
                    }
                    @media (prefers-color-scheme: light) {
                        body { background: #F6F2EA; color: #2A241D; }
                        .card { background: #FBF8F1; border-color: #E2DBC8; }
                        .badge { background: #F2DDD2; color: #C8533A; }
                        p { color: #574E42; }
                        .action { background: #C8533A; color: #FDFAF3; }
                    }
                    .card {
                        max-width: 520px;
                        width: 100%;
                        background: #2A241D;
                        border: 1px solid #3F372D;
                        border-radius: 16px;
                        padding: 28px;
                    }
                    .badge {
                        display: inline-block;
                        font-size: 11px;
                        font-weight: 600;
                        letter-spacing: 0.08em;
                        text-transform: uppercase;
                        background: #3F2A22;
                        color: #E26545;
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
                        color: #C8BFA9;
                    }
                    .action {
                        display: inline-block;
                        margin-top: 20px;
                        padding: 11px 20px;
                        border-radius: 999px;
                        background: #E26545;
                        color: #1A140F;
                        text-decoration: none;
                        font-weight: 600;
                        font-size: 14px;
                    }
                    .action:active { opacity: 0.85; }
                </style>
            </head>
            <body>
                <div class="card">
                    <span class="badge">Ebors</span>
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
