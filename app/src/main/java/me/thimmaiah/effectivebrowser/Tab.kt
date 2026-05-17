package me.thimmaiah.effectivebrowser

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
     * Once-per-load latch for the "this page is HTTP" warning snackbar. Was
     * previously a field on MainActivity, which meant flipping to a new
     * insecure tab didn't re-warn. Per-tab makes the warning fire exactly
     * once per page load per tab — matching what a user would expect.
     */
    var insecurePageWarningShown: Boolean = false

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

    private var destroyed: Boolean = false

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
        webView.stopLoading()
        webView.destroy()
    }

    fun removeDocumentStartScripts() {
        runCatching { youTubeDocumentStartScript?.remove() }
        runCatching { privacyDocumentStartScript?.remove() }
        youTubeDocumentStartScript = null
        privacyDocumentStartScript = null
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
    }
}
