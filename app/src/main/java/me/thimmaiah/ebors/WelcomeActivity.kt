/*
 * Copyright 2025 Tejas Thimmaiah
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
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.ViewFlipper
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

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

        // Page 1 — Welcome
        findViewById<Button>(R.id.welcome_continue).setOnClickListener {
            flipper.displayedChild = 1
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
            // Onboarding is logically complete now — the default-browser
            // step is optional. Flip the flag here so the user is
            // never re-prompted even if they kill the app on page 3.
            prefs.onboardingCompleted = true
            flipper.displayedChild = 2
        }
        // "Privacy policy" / "Terms of use" links → open external URLs
        // via a CHOOSER so the user picks which browser opens them.
        // We deliberately don't load them inside Ebors here — the user
        // hasn't accepted yet, so Ebors hasn't been initialised as a
        // browsing surface for them.
        findViewById<View>(R.id.welcome_privacy_link).setOnClickListener {
            openExternalUrl(PRIVACY_POLICY_URL)
        }
        findViewById<View>(R.id.welcome_terms_link).setOnClickListener {
            openExternalUrl(TERMS_OF_USE_URL)
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
                    flipper.displayedChild--
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
