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

import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import androidx.webkit.ScriptHandler
import java.util.UUID

/**
 * One browsing context: a single [ScrollAwareWebView] plus the per-tab state
 * that previously lived as nullable slots on [MainActivity] and which could
 * be cross-wired when more than one tab had work in flight.
 *
 * Two important invariants this class enforces:
 *
 *  1. **Permission isolation.** [pendingWebsitePermission] and
 *     [pendingGeolocation] are stored per-tab so that a launcher result for
 *     tab A can never resolve a prompt that originated in tab B. This is
 *     the structural half of the V3 fix; the matching half lives on the
 *     activity (an `in-flight tab id` pointer + an early-deny path for
 *     overlapping prompts).
 *
 *  2. **Lifecycle hygiene.** [destroy] resolves any in-flight permission
 *     prompts before tearing down the WebView. Without this, closing a tab
 *     with a pending prompt would leave the browser-side
 *     [PermissionRequest] / [GeolocationPermissions.Callback] forever
 *     unresolved — a slow leak in pages that listen for the promise to
 *     settle.
 *
 * The tab does **not** know about chrome UI; the activity drives all
 * address-bar/lock/navigation updates and routes them through whichever
 * tab is currently active. That separation is what closes the popup-style
 * URL-spoofing class of bugs (V1/V2).
 */
class Tab(
    val webView: ScrollAwareWebView,
    val id: String = UUID.randomUUID().toString(),
    /**
     * True for tabs opened via "New private tab". A private tab's
     * WebView is bound to a dedicated AndroidX webkit [Profile] (see
     * `MainActivity.PRIVATE_PROFILE_NAME`) so cookies, localStorage,
     * IndexedDB, and the HTTP cache are isolated from regular tabs.
     * Private tabs also skip [HistoryRepository] recording, disable
     * `WebSettings.saveFormData`, and are deliberately excluded from
     * `onSaveInstanceState` so they vanish on process death.
     *
     * Only meaningful on devices whose WebView supports
     * `WebViewFeature.MULTI_PROFILE` (WebView 121+). The "New private
     * tab" menu item is hidden when that feature is absent rather than
     * offering a fake-private mode that silently leaks cookies.
     */
    val isPrivate: Boolean = false,
) {
    /** Most-recent title we've observed for this tab. Shown in the tab switcher. */
    var displayTitle: String = ""

    /** Most-recent URL we've observed for this tab. Shown in the tab switcher
     *  and used to repopulate the address bar when the user switches to it. */
    var displayUrl: String = ""

    /**
     * Current main-frame load progress. Kept per-tab so page actions that
     * depend on a settled DOM (Reader/Offline) do not guess from the active
     * progress bar, which only reflects the foreground tab.
     */
    var loadProgress: Int = 100

    /**
     * Reader mode replaces the WebView document with app-generated HTML.
     * These fields keep the original page identity and rendered reader HTML
     * attached to the tab so bookmark/offline/share style actions can still
     * operate on the real website rather than the synthetic reader document.
     */
    var readerModeSourceUrl: String? = null
    var readerModeSourceTitle: String? = null
    var readerModeHtml: String? = null
    var pendingReaderModeUrl: String? = null

    /** Wall-clock time this tab was last the active/foreground tab (set when
     *  it's switched away from). Drives proactive tab-sleeping: a background
     *  tab idle past the threshold has its page dropped to free memory. */
    var lastActiveAt: Long = System.currentTimeMillis()

    /**
     * URL queued for load on first activation. Background-opened tabs
     * (`openNewTab(switchTo = false)`) defer the network fetch until
     * the tab is actually attached to a window — loading on a paused,
     * never-attached WebView leaves the page in a half-state where the
     * viewport metrics are zero and the page renders blank or with the
     * wrong layout when the user later switches in. The activity clears
     * this once consumed.
     */
    var pendingLoadUrl: String? = null

    /**
     * Set by [MainActivity.applyPreferences] when a visual-mode pref
     * (desktop UA, force dark, WebRTC block, referrer policy) flips
     * while this tab was in the background. The reload itself happens
     * lazily on the next [MainActivity.switchToTab] so a Settings
     * round-trip doesn't freeze the UI re-fetching every open tab.
     * The activity clears this immediately after consuming.
     */
    var pendingReloadOnActivate: Boolean = false

    /**
     * Once-per-load latch for the "this page is HTTP" warning snackbar. Was
     * previously a field on MainActivity, which meant flipping to a new
     * insecure tab didn't re-warn. Per-tab makes the warning fire exactly
     * once per page load per tab — matching what a user would expect.
     */
    var insecurePageWarningShown: Boolean = false

    /**
     * Set when the most recent main-frame load failed at the network level
     * ([android.webkit.WebViewClient.onReceivedError] for the main frame).
     * Cleared on [android.webkit.WebViewClient.onPageStarted]. The activity
     * uses it to decide whether `onPageFinished` represents a real page
     * (clear the offline overlay) or the error page that follows a failure
     * (leave the overlay up).
     */
    var mainFrameErrored: Boolean = false

    /**
     * The unresolved host-lookup failing URL for this tab, or `null` when the
     * tab has no outstanding host-resolution failure. Set when
     * [android.webkit.WebViewClient.onReceivedError] routes a main-frame
     * failure to the site-not-found surface (`resolveErrorSurface` returns
     * `SITE_NOT_FOUND`). Cleared on
     * [android.webkit.WebViewClient.onPageStarted] (a new attempt begins) and
     * on a successful [android.webkit.WebViewClient.onPageFinished] — mirroring
     * how [mainFrameErrored] is managed.
     *
     * `siteNotFoundUrl != null` is the per-tab predicate "this tab has an
     * unresolved host-lookup failure" used by `resolveActiveTabSurface` to
     * decide which surface belongs over the active tab after a tab switch.
     */
    var siteNotFoundUrl: String? = null

    /** WebView media permission prompt awaiting an Android runtime grant. */
    var pendingWebsitePermission: PendingWebsitePermission? = null

    /** Geolocation prompt awaiting an Android runtime grant. */
    var pendingGeolocation: PendingGeolocation? = null

    /** Document-start hook used for YouTube ad-response pruning. */
    var youTubeDocumentStartScript: ScriptHandler? = null

    /** Document-start hook used for referrer/WebRTC privacy hardening. */
    var privacyDocumentStartScript: ScriptHandler? = null

    /** Bitmask of the privacy document-start preferences installed above. */
    var privacyDocumentStartFlags: Int = DOCUMENT_START_FLAGS_UNSET

    /**
     * Host of the current top-level document. Read off the WebView worker
     * thread in `shouldInterceptRequest` to decide first- vs third-party, so
     * it is @Volatile and set from the main thread on each main-frame load.
     */
    @Volatile
    var currentHost: String? = null

    /**
     * Original page URL retained while this tab is showing a Google
     * Translate proxy. Keeping it explicitly lets the user switch target
     * languages without trying to reverse Google’s translated host name.
     */
    var translationSourceUrl: String? = null
    var translationTargetLanguage: String? = null

    fun clearReaderModeState() {
        readerModeSourceUrl = null
        readerModeSourceTitle = null
        readerModeHtml = null
        pendingReaderModeUrl = null
    }

    /**
     * Document-start hook that installs the `webkitSpeechRecognition`
     * polyfill (backed by Android's native SpeechRecognizer via a JS
     * bridge). Injected at document start so a page's early feature
     * detection sees the API and shows its voice button — onPageFinished
     * would be too late for sites that probe at load. http(s) only.
     */
    var speechDocumentStartScript: ScriptHandler? = null

    /**
     * Document-start hook that masks the Page Visibility API and reports
     * `<video>`/`<audio>` play state to the app so media can keep playing
     * (with lock-screen controls) while the app is backgrounded. http(s) only.
     */
    var mediaDocumentStartScript: ScriptHandler? = null

    /**
     * Document-start hook that injects the cosmetic ad-hiding CSS and
     * anti-adblock stubs before the page renders, so ad slots never flash
     * into view and detector scripts see the stubs first. Gated on the
     * ad-block master switch; falls back to an onPageFinished injection on
     * WebViews without DOCUMENT_START_SCRIPT support. http(s) only.
     */
    var cosmeticDocumentStartScript: ScriptHandler? = null

    /** Whether [cosmeticDocumentStartScript] was installed in aggressive
     *  mode — lets the sync logic re-install when that pref flips. */
    var cosmeticDocumentStartAggressive: Boolean = false

    /**
     * Downscaled bitmap of the WebView's last visible state, painted
     * inside the tab-switcher card so the user sees real page content
     * instead of skeleton placeholders.
     *
     * Captured by [captureThumbnail] right before the WebView is
     * detached from the foreground slot (in `MainActivity.switchToTab`)
     * and when the switcher is first opened (in
     * `MainActivity.showTabSwitcher`). Private tabs deliberately keep
     * this null — `TabSwitcherView` paints the incognito-glyph
     * placeholder for them so an attacker who watches the switcher
     * never sees a private page's frame.
     */
    var thumbnail: Bitmap? = null
        private set

    private var destroyed: Boolean = false

    /**
     * Render the WebView into a downscaled ARGB_8888 bitmap and
     * store it on [thumbnail]. The previous bitmap (if any) is
     * recycled before being replaced so we don't accumulate native
     * heap pressure across rapid tab switches.
     *
     * No-op when:
     *  - the tab is private (privacy carve-out — see [thumbnail])
     *  - the WebView has never been laid out (zero width/height —
     *    happens for background-opened tabs whose first activation
     *    hasn't fired yet)
     *  - the tab has been [destroy]ed (the WebView is gone)
     *
     * `webView.draw(canvas)` must be called on the UI thread; the
     * 400 px target keeps a single render cheap (single-digit ms in
     * practice).
     */
    fun captureThumbnail(): Bitmap? {
        if (destroyed || isPrivate) return null
        val w = webView.width
        val h = webView.height
        if (w <= 0 || h <= 0) return null

        val targetWidth = THUMBNAIL_WIDTH_PX
        val targetHeight = (h.toFloat() / w.toFloat() * targetWidth).toInt().coerceAtLeast(1)
        val bitmap = try {
            Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        } catch (_: OutOfMemoryError) {
            return null
        }
        val canvas = Canvas(bitmap)
        val scale = targetWidth.toFloat() / w.toFloat()
        canvas.scale(scale, scale)
        webView.draw(canvas)

        // Swap in the new bitmap. We deliberately do NOT recycle the
        // previous one here — a recently-shown tab-switcher card may
        // still hold a BitmapDrawable backed by it (in a recycled
        // view-holder waiting for the next bind, or mid-fade-out).
        // Recycling under that drawable would crash the very next
        // draw with `Canvas: trying to use a recycled bitmap`.
        // The old bitmap is unreferenced once this assignment lands
        // and any drawables release it; GC handles native pixel
        // memory on Android 8+. We DO recycle in [destroy] where we
        // know the tab + its UI are gone.
        thumbnail = bitmap
        return bitmap
    }

    /**
     * Tear down the WebView and resolve any in-flight permission prompts as
     * denied. Calling more than once is safe — subsequent calls no-op rather
     * than re-entering `WebView.destroy()`, which the Android docs do *not*
     * promise tolerates double invocation (on some implementations it
     * throws once the underlying chromium objects are gone).
     */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        pendingWebsitePermission?.request?.deny()
        pendingWebsitePermission = null
        pendingGeolocation?.let { it.callback.invoke(it.origin, false, false) }
        pendingGeolocation = null
        removeDocumentStartScripts()
        // Drop the thumbnail reference; we deliberately do NOT call
        // recycle() because a tab-switcher card in the middle of a
        // close-animation may still hold a BitmapDrawable backed by
        // this bitmap, and `Canvas: trying to use a recycled bitmap`
        // crashes the very next draw. Native pixel memory for an
        // ARGB_8888 bitmap lives on the Java heap on API 26+
        // (minSdk is 29), so GC handles cleanup once the last
        // reference goes away on its own.
        thumbnail = null
        webView.stopLoading()
        webView.destroy()
    }

    fun removeDocumentStartScripts() {
        runCatching { youTubeDocumentStartScript?.remove() }
        runCatching { privacyDocumentStartScript?.remove() }
        runCatching { speechDocumentStartScript?.remove() }
        runCatching { mediaDocumentStartScript?.remove() }
        runCatching { cosmeticDocumentStartScript?.remove() }
        youTubeDocumentStartScript = null
        privacyDocumentStartScript = null
        speechDocumentStartScript = null
        mediaDocumentStartScript = null
        cosmeticDocumentStartScript = null
        privacyDocumentStartFlags = DOCUMENT_START_FLAGS_UNSET
    }

    data class PendingWebsitePermission(
        val request: PermissionRequest,
        val resources: List<String>,
    )

    data class PendingGeolocation(
        val origin: String,
        val callback: GeolocationPermissions.Callback,
    )

    companion object {
        const val DOCUMENT_START_FLAGS_UNSET = -1

        /** Target width (in px) of the captured tab-switcher
         *  thumbnail. 400 px lands roughly one-and-a-half times the
         *  card's display width on a 1080p phone — enough to look
         *  crisp without bloating the heap. */
        private const val THUMBNAIL_WIDTH_PX = 400
    }
}
