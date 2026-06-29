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

import android.app.role.RoleManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.ViewFlipper
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * First-launch onboarding. Three pages, advanced via a [ViewFlipper]:
 *
 *  1. **Welcome.** Value prop + "Continue" button.
 *  2. **Privacy promise.** Bullet list of what the app does and doesn't
 *     do, plus a checkbox the user must tick to accept the Terms of
 *     use and acknowledge the Privacy policy. Tapping "Accept &
 *     continue" persists [BrowserPreferences.termsAcceptedAt] and
 *     [BrowserPreferences.onboardingCompleted].
 *  3. **Set as default browser** (optional). Offers the system role
 *     picker; user can skip. Either way, finishes and routes the user
 *     into [MainActivity].
 *
 * The activity is its own task entry — MainActivity launches it on
 * cold start when [BrowserPreferences.onboardingCompleted] is false,
 * and finishes() when this returns so the user lands on the browser.
 *
 * System-back is overridden to step the flipper backwards instead of
 * dismissing the activity, except on page 1 where back exits the app
 * (no half-onboarded state).
 */
class WelcomeActivity : AppCompatActivity() {

    private lateinit var prefs: BrowserPreferences
    private lateinit var flipper: ViewFlipper

    /**
     * Result for the system "default browser" role picker. We don't
     * branch on the result here — whether the user granted the role or
     * not, the next step is the same (finish onboarding and open
     * MainActivity). Their choice is reflected in the actual default
     * status the OS now reports.
     */
    private val defaultBrowserRoleLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            completeOnboarding()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = BrowserPreferences.from(this)
        applyAccentTheme(prefs)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.welcome_root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        flipper = findViewById(R.id.welcome_flipper)

        // Promise cards (page 2) are populated in code; paint the
        // initial step dot for page 1.
        bindPromises()
        refreshSteps(0)

        // Page 1 — Welcome
        findViewById<Button>(R.id.welcome_continue).setOnClickListener {
            showPage(1)
        }

        // Page 2 — Privacy & terms
        val acceptCheck = findViewById<CheckBox>(R.id.welcome_terms_check)
        val acceptButton = findViewById<Button>(R.id.welcome_accept)
        // Disabled until the box is ticked; matches the standard
        // "must read" pattern Google Play expects.
        acceptButton.isEnabled = false
        acceptCheck.setOnCheckedChangeListener { _, checked ->
            acceptButton.isEnabled = checked
        }
        acceptButton.setOnClickListener {
            prefs.termsAcceptedAt = System.currentTimeMillis()
            // Stamp the "on by default" toggles to disk explicitly so a
            // future read can't fall back to a different in-code default
            // and silently flip the user's settings on them. This is the
            // first-launch bootstrap pass; the user can still toggle any
            // of them off later from Settings.
            prefs.bootstrapDefaults()
            // Onboarding is logically complete now — the default-browser
            // step is optional. Flip the flag here so the user is
            // never re-prompted even if they kill the app on page 3.
            prefs.onboardingCompleted = true
            showPage(2)
        }
        // "Privacy policy" / "Terms of use" → render the bundled copies in
        // a local dialog so the user can actually read them before ticking
        // the consent box, without leaving the onboarding flow.
        findViewById<View>(R.id.welcome_privacy_link).setOnClickListener {
            showLegalDialog("privacy.html")
        }
        findViewById<View>(R.id.welcome_terms_link).setOnClickListener {
            showLegalDialog("terms.html")
        }

        // Page 3 — Default browser (optional)
        findViewById<Button>(R.id.welcome_set_default).setOnClickListener {
            requestDefaultBrowserRole()
        }
        findViewById<View>(R.id.welcome_skip_default).setOnClickListener {
            completeOnboarding()
        }

        // System-back: step pages back rather than abandon onboarding.
        // The page-1 case (nothing to step back to) falls through to the
        // default handler, which finishes the activity — equivalent to
        // exiting the app before any consent was given.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (flipper.displayedChild > 0) {
                    showPage(flipper.displayedChild - 1)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        // Stamp the version line so the user sees what they're
        // accepting, and so QA can tell two onboarding pass-throughs
        // of different builds apart.
        val versionLabel = findViewById<TextView>(R.id.welcome_version)
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            "1.0"
        }
        versionLabel.text = getString(R.string.welcome_version_label, versionName)
    }

    private fun showPage(index: Int) {
        flipper.displayedChild = index
        refreshSteps(index)
    }

    // Paint the step indicator for the active page. Page 3 uses the
    // inverse header (cream dots styled statically in XML), so skip it.
    private fun refreshSteps(activeIndex: Int) {
        if (activeIndex >= 2) return
        val page = flipper.getChildAt(activeIndex) ?: return
        val dotIds = intArrayOf(R.id.welcome_step_1, R.id.welcome_step_2, R.id.welcome_step_3)
        dotIds.forEachIndexed { idx, dotId ->
            val dot = page.findViewById<View>(dotId) ?: return@forEachIndexed
            val active = idx == activeIndex
            dot.layoutParams = dot.layoutParams.apply { width = dpToPx(if (active) 28 else 14) }
            dot.setBackgroundResource(
                if (active) R.drawable.bg_welcome_step_dot_active
                else R.drawable.bg_welcome_step_dot,
            )
        }
    }

    private fun bindPromises() {
        val rows = intArrayOf(
            R.id.welcome_promise_1, R.id.welcome_promise_2,
            R.id.welcome_promise_3, R.id.welcome_promise_4,
        )
        val titles = intArrayOf(
            R.string.welcome_promise_1_title, R.string.welcome_promise_2_title,
            R.string.welcome_promise_3_title, R.string.welcome_promise_4_title,
        )
        val bodies = intArrayOf(
            R.string.welcome_promise_1_body, R.string.welcome_promise_2_body,
            R.string.welcome_promise_3_body, R.string.welcome_promise_4_body,
        )
        rows.forEachIndexed { i, rowId ->
            val row = findViewById<View>(rowId) ?: return@forEachIndexed
            row.findViewById<TextView>(R.id.welcome_promise_title).setText(titles[i])
            row.findViewById<TextView>(R.id.welcome_promise_body).setText(bodies[i])
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun requestDefaultBrowserRole() {
        val roleManager = getSystemService<RoleManager>()
        if (roleManager == null || !roleManager.isRoleAvailable(RoleManager.ROLE_BROWSER)) {
            // No RoleManager (very old / stripped Android) → just skip
            // through. The user can set the default later via Android
            // Settings → Apps → Default apps.
            completeOnboarding()
            return
        }
        if (roleManager.isRoleHeld(RoleManager.ROLE_BROWSER)) {
            completeOnboarding()
            return
        }
        defaultBrowserRoleLauncher.launch(
            roleManager.createRequestRoleIntent(RoleManager.ROLE_BROWSER),
        )
    }

    private fun completeOnboarding() {
        // [onboardingCompleted] is already true (set when the user
        // accepted the terms). Mark the default-browser prompt as
        // shown too so MainActivity doesn't pop a duplicate snackbar
        // — the user has either accepted it here, declined, or
        // skipped, and any of those mean the question has been
        // asked once.
        prefs.defaultBrowserPromptShown = true
        val next = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(next)
        finish()
    }

    /**
     * Show a bundled legal doc (`privacy.html` / `terms.html` in assets) in
     * a locked-down WebView inside a dialog, so the user can read it without
     * leaving onboarding. The bundled docs and their in-page anchors stay in
     * the dialog; mailto/http links are handed off to an external app.
     */
    @Suppress("DEPRECATION")
    private fun showLegalDialog(assetFile: String) {
        val night = resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

        val web = WebView(this).apply {
            with(settings) {
                javaScriptEnabled = false
                domStorageEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            // Honour the system dark setting so the doc's
            // prefers-color-scheme: dark CSS branch fires.
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true)
            } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_AUTO)
            }
            // Match the doc's body colour so a slow load doesn't flash white.
            setBackgroundColor(if (night) Color.parseColor("#1A1817") else Color.parseColor("#FAF6EE"))
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val url = request.url.toString()
                    if (url.startsWith("file:///android_asset/")) return false
                    openExternalUrl(url)
                    return true
                }
            }
            loadUrl("file:///android_asset/$assetFile")
        }

        // Cap the WebView height so the dialog reads as a popup over the
        // onboarding page rather than a full-screen takeover; the doc
        // scrolls inside it.
        val height = (resources.displayMetrics.heightPixels * 0.72f).toInt()
        val container = FrameLayout(this).apply {
            addView(
                web,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height),
            )
        }

        MaterialAlertDialogBuilder(this)
            .setView(container)
            .setPositiveButton(R.string.welcome_legal_close, null)
            .show()
    }

    private fun openExternalUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.welcome_open_with)))
        } catch (_: Exception) {
            // Worst case: no browser installed to open the URL. The
            // user can still tap "Accept" — they've seen the in-app
            // summary above the link.
        }
    }

    companion object {
        // Hosted on thimmaiah.me; these MUST match the files in the
        // /website folder of the source repo. Centralised here so the
        // settings screen and any future re-prompt land on the same URL.
        const val PRIVACY_POLICY_URL = "https://thimmaiah.me/privacy.html"
        const val TERMS_OF_USE_URL = "https://thimmaiah.me/terms.html"
    }
}
