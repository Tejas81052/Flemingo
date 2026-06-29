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
import android.util.AttributeSet
import android.view.View
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

    /**
     * When true, the WebView reports itself as window-visible to the
     * underlying Chromium engine even after the host activity is
     * backgrounded. This is what actually keeps audio/video decoding in
     * the background: Chromium suspends media when the WebView's *window*
     * becomes invisible, and that suspension is independent of (and
     * happens regardless of) the JavaScript Page Visibility API. The JS
     * visibility mask in MainActivity only stops the *page* from pausing
     * itself; this stops the *engine* from pausing the media.
     *
     * Set by MainActivity while web audio is playing and cleared when it
     * stops, so normal backgrounding still suspends an idle WebView.
     */
    var keepAliveWhenHidden = false

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        onScrollChangedListener?.invoke(t, t - oldt)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        if (keepAliveWhenHidden && visibility != View.VISIBLE) {
            super.onWindowVisibilityChanged(View.VISIBLE)
        } else {
            super.onWindowVisibilityChanged(visibility)
        }
    }
}
