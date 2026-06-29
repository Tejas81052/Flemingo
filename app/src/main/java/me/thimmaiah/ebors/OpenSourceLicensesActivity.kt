/*
 * Copyright 2026 Tejas Thimmaiah
 *
 * All rights reserved. This source file is part of Ebors.
 *
 * Third-party libraries shipped inside the Ebors APK retain their own
 * licenses; this activity is what surfaces them at runtime.
 */
package me.thimmaiah.ebors

import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.appbar.MaterialToolbar

/**
 * Renders `assets/open_source_licenses.html`. A standalone activity (rather
 * than a route inside SettingsActivity) so the licenses screen can be
 * reached from anywhere in the app and so its WebView is isolated from the
 * main browsing WebViews.
 *
 * The WebView here is locked down: JavaScript off, no file/content access,
 * no DOM storage. It only renders a static asset.
 *
 * Theme: we let the HTML's `prefers-color-scheme: dark` media query do the
 * heavy lifting, but Android's system WebView does not by default report
 * the system's dark setting to web content (only Chrome does). We opt in
 * explicitly via [WebSettingsCompat.setAlgorithmicDarkeningAllowed] when
 * available, and fall back to the deprecated FORCE_DARK API on older
 * WebView builds — same belt-and-braces pattern used by MainActivity's
 * force-dark path. The WebView background is also painted to match the
 * activity's surface so the first frame doesn't flash white.
 */
class OpenSourceLicensesActivity : AppCompatActivity() {

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = BrowserPreferences.from(this)
        applyAccentTheme(prefs)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_open_source_licenses)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.licenses_root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        findViewById<MaterialToolbar>(R.id.licenses_toolbar).setNavigationOnClickListener {
            finish()
        }

        val web: WebView = findViewById(R.id.licenses_webview)
        with(web.settings) {
            javaScriptEnabled = false
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }

        // Tell WebView to honour the system dark setting so the
        // HTML's prefers-color-scheme: dark CSS branch actually fires.
        // ALGORITHMIC_DARKENING is the modern API; FORCE_DARK covers
        // older WebView builds and is harmless on newer ones.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(web.settings, true)
        } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            // Fallback for older WebView builds without algorithmic
            // darkening. FORCE_DARK_AUTO matches the activity's day/night
            // state without overriding the page's own CSS where it cares.
            WebSettingsCompat.setForceDark(web.settings, WebSettingsCompat.FORCE_DARK_AUTO)
        }

        // Paint the WebView's initial background to the surface colour
        // so a slow asset load doesn't flash a white square on the
        // dark-themed activity.
        val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        web.setBackgroundColor(
            if (nightMode == Configuration.UI_MODE_NIGHT_YES) Color.parseColor("#1A1817")
            else Color.parseColor("#FAF6EE"),
        )

        web.loadUrl("file:///android_asset/open_source_licenses.html")
    }
}
