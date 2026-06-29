// ──────────────────────────────────────────────────────────────────────
// SiteNotFoundController — patch for MainActivity.
//
// Shows the animated "address not found" screen when a navigation fails
// to resolve the host. Fold into MainActivity (the offline screen and
// this one both replace the WebView inside web_container, swapped by
// visibility — neither is a separate Activity).
//
// Trigger: WebViewClient.onReceivedError. The relevant error codes:
//   ERROR_HOST_LOOKUP (-2)   → DNS couldn't resolve the host  ← main case
//   ERROR_CONNECT     (-6)   → host resolved but refused      ← optional
//   ERROR_TIMEOUT     (-8)   → no response                    ← optional
// (Use WebViewClient constants, not raw ints, where possible.)
//
// Distinguish from the *no-internet* screen first: if the device has no
// active network at all, show NoInternetController instead — a DNS
// failure while offline is really an offline problem.
// ──────────────────────────────────────────────────────────────────────

import android.graphics.Paint
import android.graphics.drawable.Animatable
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SiteNotFoundController(private val activity: AppCompatActivity) {

    private val hero: ImageView = activity.findViewById(R.id.site_not_found_hero)
    private val tabUrl: TextView = activity.findViewById(R.id.site_not_found_tab_url)
    private val body: TextView = activity.findViewById(R.id.site_not_found_body)
    private val searchBtn: Button = activity.findViewById(R.id.site_not_found_search)
    private val backBtn: Button = activity.findViewById(R.id.site_not_found_back)
    private val retryBtn: Button = activity.findViewById(R.id.site_not_found_retry)

    init {
        // Strike through the dead address once — it never changes.
        tabUrl.paintFlags = tabUrl.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
    }

    /**
     * @param failedUrl the URL that failed to resolve
     * @param onSearch  invoked with the bare search term (host minus TLD)
     * @param onBack    invoked to navigate back / dismiss
     * @param onRetry   invoked to reload failedUrl
     */
    fun show(
        failedUrl: String,
        onSearch: (String) -> Unit,
        onBack: () -> Unit,
        onRetry: () -> Unit,
    ) {
        val host = Uri.parse(failedUrl).host ?: failedUrl
        // Bare term for the search CTA: strip a leading www. and the TLD.
        val term = host.removePrefix("www.").substringBeforeLast('.')

        tabUrl.text = host
        body.text = activity.getString(R.string.site_not_found_body, host)
        searchBtn.text = activity.getString(R.string.site_not_found_search, term)

        searchBtn.setOnClickListener { onSearch(term) }
        backBtn.setOnClickListener { onBack() }
        retryBtn.setOnClickListener { onRetry() }

        (hero.drawable as? Animatable)?.start()
    }

    fun hide() {
        (hero.drawable as? Animatable)?.stop()
    }
}

// ── Hookup inside MainActivity ────────────────────────────────────────
//
//   private val siteNotFoundView: View by lazy {
//       layoutInflater.inflate(R.layout.activity_site_not_found, web_container, false)
//           .also { it.visibility = View.GONE; web_container.addView(it) }
//   }
//   private val siteNotFoundCtrl by lazy { SiteNotFoundController(this) }
//
//   // In your WebViewClient:
//   override fun onReceivedError(
//       view: WebView, request: WebResourceRequest, error: WebResourceError
//   ) {
//       // Only handle main-frame failures; ignore sub-resource errors.
//       if (!request.isForMainFrame) return
//
//       val offline = connectivityManager.activeNetwork == null
//       val failingUrl = request.url.toString()
//
//       when {
//           offline -> showOffline(failingUrl)                  // no-internet screen
//           error.errorCode == ERROR_HOST_LOOKUP -> showSiteNotFound(failingUrl)
//           else -> { /* leave default WebView error page, or a generic state */ }
//       }
//   }
//
//   private fun showSiteNotFound(url: String) {
//       siteNotFoundView.visibility = View.VISIBLE
//       webView.visibility = View.GONE
//       siteNotFoundCtrl.show(
//           failedUrl = url,
//           onSearch = { term ->
//               dismissSiteNotFound()
//               loadUrl(searchEngine.queryUrl(term))   // your existing search plumbing
//           },
//           onBack = {
//               dismissSiteNotFound()
//               if (webView.canGoBack()) webView.goBack() else openHome()
//           },
//           onRetry = {
//               dismissSiteNotFound()
//               webView.reload()
//           },
//       )
//   }
//
//   private fun dismissSiteNotFound() {
//       siteNotFoundView.visibility = View.GONE
//       webView.visibility = View.VISIBLE
//       siteNotFoundCtrl.hide()
//   }
//
// Battery note: AnimatedVectorDrawable keeps animating while attached
// even if the view is off-screen behind another tab. Call
// siteNotFoundCtrl.hide() whenever you switch away, and .show()'s
// start() again on return.
