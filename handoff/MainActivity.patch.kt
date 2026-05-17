// ──────────────────────────────────────────────────────────────────────
// Patch for MainActivity.kt — paper-theme address bar (v10).
//
// This isn't a full file — it's the two routines that change. Drop them
// into MainActivity.kt (replace the existing address-text plumbing).
//
// Behavior: the address bar shows the URL split into a bold host and a
// dimmed-mono path while the field is *not* focused. On focus the user
// sees the raw URL again so editing works exactly like before; on blur
// or page-load complete we re-apply the spans.
// ──────────────────────────────────────────────────────────────────────

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned.SPAN_INCLUSIVE_INCLUSIVE
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import androidx.core.content.ContextCompat

// Call from onPageStarted / onPageFinished / tab-switch.
// Skips formatting while the EditText is focused so typing is never
// fighting with span re-application.
private fun renderAddressBar(url: String?) {
    if (addressBar.hasFocus()) return
    val display = url.orEmpty()
        .removePrefix("https://")
        .removePrefix("http://")

    val slash = display.indexOf('/')
    val host = if (slash == -1) display else display.substring(0, slash)
    val path = if (slash == -1) ""      else display.substring(slash)

    val faint = ContextCompat.getColor(this, R.color.browser_faint)
    val full  = SpannableString(host + path)

    // Host: bold ink.
    full.setSpan(StyleSpan(Typeface.BOLD), 0, host.length, SPAN_INCLUSIVE_INCLUSIVE)
    // Path: faint, still monospace (the EditText is already fontFamily=monospace).
    if (path.isNotEmpty()) {
        full.setSpan(
            ForegroundColorSpan(faint),
            host.length, full.length, SPAN_INCLUSIVE_INCLUSIVE
        )
    }
    addressBar.setText(full, android.widget.TextView.BufferType.SPANNABLE)
    addressBar.setSelection(full.length)  // caret to the end if user does focus it later
}

// Wire focus-aware repaint in onCreate after addressBar is found.
private fun configureAddressBarSpans() {
    addressBar.setOnFocusChangeListener { _, focused ->
        if (focused) {
            // Show raw URL for editing.
            val raw = currentTab?.url.orEmpty()
            addressBar.setText(raw)
            addressBar.selectAll()
        } else {
            renderAddressBar(currentTab?.url)
        }
    }
}

// ── Bottom caption with the accent dot ─────────────────────────────────
// Replace the plain setText on browser_caption with this so the bullet
// renders in accent terracotta while the rest stays hint-grey.

import android.text.SpannableStringBuilder

private fun renderBrowserCaption(searchEngineName: String, isPrivate: Boolean) {
    val accent = ContextCompat.getColor(this, R.color.browser_accent)
    val mute   = ContextCompat.getColor(this, R.color.browser_hint)

    val prefix = if (isPrivate) "Private · " else "Search · "
    val span = SpannableStringBuilder().apply {
        append("● ")
        setSpan(ForegroundColorSpan(accent), 0, 1, SPAN_INCLUSIVE_INCLUSIVE)
        append(prefix)
        append(searchEngineName)
        setSpan(ForegroundColorSpan(mute), 2, length, SPAN_INCLUSIVE_INCLUSIVE)
    }
    browserCaption.text = span
}
