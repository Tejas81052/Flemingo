/*
 * Copyright 2026 Tejas Thimmaiah
 *
 * All rights reserved. This source file is part of Ebors. Use,
 * modification, and redistribution outside the scope of the project's
 * own distribution are not permitted without prior written consent
 * from the copyright holder.
 *
 * Third-party libraries shipped inside the Ebors APK retain their
 * own licenses; see the Open-source licenses screen reachable from
 * here for the full list.
 */
package me.thimmaiah.ebors

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar

/**
 * "About Ebors" screen. Reachable only from Settings → About row. All
 * the bits that used to live as separate top-level rows (Privacy,
 * Terms, Open-source licenses) live here now so the main Settings
 * list stays short and focused on actual configuration.
 *
 * The screen itself is read-only: nothing here writes back to prefs.
 */
class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = BrowserPreferences.from(this)
        applyAccentTheme(prefs)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.about_root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        findViewById<MaterialToolbar>(R.id.about_toolbar).setNavigationOnClickListener {
            finish()
        }

        // Stamp the running version into the header. Fall back to "1.0"
        // if PackageManager fails — that's a pathological case (broken
        // install) and a placeholder is better than a hard crash.
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            "1.0"
        }
        findViewById<TextView>(R.id.about_version)
            .text = getString(R.string.about_version_label, versionName)

        findViewById<View>(R.id.row_privacy_policy).setOnClickListener {
            openExternalUrl(WelcomeActivity.PRIVACY_POLICY_URL)
        }
        findViewById<View>(R.id.row_terms).setOnClickListener {
            openExternalUrl(WelcomeActivity.TERMS_OF_USE_URL)
        }
        findViewById<View>(R.id.row_open_source_licenses).setOnClickListener {
            startActivity(Intent(this, OpenSourceLicensesActivity::class.java))
        }
    }

    /**
     * Hand a URL off to the system's default browser via ACTION_VIEW.
     * No createChooser() here: by the time the user reaches the About
     * screen they have a working browser (this one) and forcing a
     * chooser feels redundant. We do show a toast if literally no app
     * can handle http(s) — that's a near-impossible state but the
     * tooling is cheap.
     */
    private fun openExternalUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.unsupported_link_message, Toast.LENGTH_SHORT).show()
        }
    }
}
