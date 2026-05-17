package me.thimmaiah.effectivebrowser

import android.content.Context
import android.util.AttributeSet
import android.webkit.WebView

/**
 * Drop-in WebView that surfaces its scroll deltas to a callback so the
 * hosting activity can drive Brave-style address-bar / nav-bar auto-hide.
 *
 * Why this exists: stock WebView technically implements
 * NestedScrollingChild, but in practice its dispatch to a parent
 * CoordinatorLayout is unreliable (consumed dy is reported as zero on
 * many builds), so AppBarLayout and HideBottomViewOnScrollBehavior
 * never fire. Listening to [onScrollChanged] directly works on every
 * WebView build we support.
 *
 * No other behaviour is changed — every WebSettings, WebViewClient,
 * WebChromeClient and ad-block hook from MainActivity still works
 * because this is just `WebView` with one extra callback.
 *
 * In particular this class does *not* override
 * `onProvideAutofillVirtualStructure` (or any other autofill-related
 * method). Stock WebView reports its DOM form fields as virtual
 * children of itself to Android's AutofillManager, which is what lets
 * password managers (1Password, Bitwarden, Google Password Manager…)
 * fill page-level inputs without per-app integration. Overriding
 * `onProvideAutofillVirtualStructure` here would silently break that
 * — don't add it.
 */
class ScrollAwareWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.webViewStyle,
) : WebView(context, attrs, defStyleAttr) {

    /**
     * Fired after the WebView's scroll position changes. `scrollY` is
     * the new absolute scroll offset and `deltaY` is the change since
     * the last event (positive = page moved up, i.e. user scrolled
     * down).
     */
    var onScrollChangedListener: ((scrollY: Int, deltaY: Int) -> Unit)? = null

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        onScrollChangedListener?.invoke(t, t - oldt)
    }
}
