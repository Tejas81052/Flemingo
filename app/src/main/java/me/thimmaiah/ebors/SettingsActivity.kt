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

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: BrowserPreferences

    /**
     * Re-prompt path for POST_NOTIFICATIONS (B12). Refreshes the
     * notification row after the system returns. We intentionally do NOT
     * read back the granted boolean here — the row's `refreshNotificationRow`
     * call covers both the granted case and the case where Android
     * silently denied because the user previously hit "Don't allow"
     * twice.
     */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshNotificationRow()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply the v10 accent theme before inflation so every drawable
        // / view that reads ?attr/browserAccent resolves to the user's
        // chosen palette.
        val prefs = BrowserPreferences.from(this)
        applyAccentTheme(prefs)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        this.prefs = prefs

        val toolbar: MaterialToolbar = findViewById(R.id.settings_toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settings_root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        bindSwitchRow(
            R.id.row_desktop_mode,
            R.string.setting_desktop_mode_title,
            R.string.setting_desktop_mode_summary,
            { prefs.desktopMode },
            { prefs.desktopMode = it },
        )
        bindAccentRow()
        bindSwitchRow(
            R.id.row_ad_block,
            R.string.setting_ad_block_title,
            R.string.setting_ad_block_summary,
            { prefs.adBlockEnabled },
            {
                prefs.adBlockEnabled = it
                BrowserBlocker.adBlockEnabled = it
            },
        )
        bindSwitchRow(
            R.id.row_site_block,
            R.string.setting_site_block_title,
            R.string.setting_site_block_summary,
            { prefs.siteBlockEnabled },
            {
                prefs.siteBlockEnabled = it
                BrowserBlocker.siteBlockEnabled = it
            },
        )

        findViewById<View>(R.id.row_manage_blocked_sites).setOnClickListener {
            startActivity(Intent(this, BlockedSitesActivity::class.java))
        }
        findViewById<View>(R.id.row_blocklist_update).setOnClickListener {
            onBlocklistUpdateRowClicked()
        }
        refreshBlocklistRow()
        bindSwitchRow(
            R.id.row_blocklist_auto_update,
            R.string.setting_blocklist_auto_update_title,
            R.string.setting_blocklist_auto_update_summary,
            { prefs.blocklistAutoUpdate },
            { prefs.blocklistAutoUpdate = it },
        )
        bindSwitchRow(
            R.id.row_third_party_cookies,
            R.string.setting_third_party_cookies_title,
            R.string.setting_third_party_cookies_summary,
            { prefs.thirdPartyCookies },
            { prefs.thirdPartyCookies = it },
        )
        bindSwitchRow(
            R.id.row_always_https,
            R.string.setting_always_https_title,
            R.string.setting_always_https_summary,
            { prefs.alwaysHttps },
            { prefs.alwaysHttps = it },
        )
        bindSwitchRow(
            R.id.row_allow_mixed_content,
            R.string.setting_allow_mixed_content_title,
            R.string.setting_allow_mixed_content_summary,
            { prefs.allowMixedContent },
            { prefs.allowMixedContent = it },
        )
        bindSwitchRow(
            R.id.row_aggressive_anti_adblock,
            R.string.setting_aggressive_anti_adblock_title,
            R.string.setting_aggressive_anti_adblock_summary,
            { prefs.aggressiveAntiAdblock },
            { prefs.aggressiveAntiAdblock = it },
        )
        bindSwitchRow(
            R.id.row_block_webrtc,
            R.string.setting_block_webrtc_title,
            R.string.setting_block_webrtc_summary,
            { prefs.blockWebRtc },
            { prefs.blockWebRtc = it },
        )
        bindSwitchRow(
            R.id.row_trim_referrer,
            R.string.setting_trim_referrer_title,
            R.string.setting_trim_referrer_summary,
            { prefs.trimReferrer },
            { prefs.trimReferrer = it },
        )
        bindSwitchRow(
            R.id.row_history,
            R.string.setting_history_title,
            R.string.setting_history_summary,
            { prefs.historyEnabled },
            { prefs.historyEnabled = it },
        )
        bindSwitchRow(
            R.id.row_search_suggestions,
            R.string.setting_search_suggestions_title,
            R.string.setting_search_suggestions_summary,
            { prefs.searchSuggestionsEnabled },
            { prefs.searchSuggestionsEnabled = it },
        )
        bindSwitchRow(
            R.id.row_javascript,
            R.string.setting_javascript_title,
            R.string.setting_javascript_summary,
            { prefs.javaScriptEnabled },
            { prefs.javaScriptEnabled = it },
        )
        bindSwitchRow(
            R.id.row_block_popups,
            R.string.setting_block_popups_title,
            R.string.setting_block_popups_summary,
            { prefs.blockPopups },
            { prefs.blockPopups = it },
        )
        bindSwitchRow(
            R.id.row_tab_sleeping,
            R.string.setting_tab_sleeping_title,
            R.string.setting_tab_sleeping_summary,
            { prefs.tabSleeping },
            { prefs.tabSleeping = it },
        )
        bindSwitchRow(
            R.id.row_clear_data_on_exit,
            R.string.setting_clear_data_on_exit_title,
            R.string.setting_clear_data_on_exit_summary,
            { prefs.clearDataOnExit },
            { prefs.clearDataOnExit = it },
        )

        val searchSummary = findViewById<TextView>(R.id.search_engine_summary)
        searchSummary.text = SearchEngineResolver.displayName(prefs)

        findViewById<View>(R.id.row_search_engine).setOnClickListener {
            showSearchEnginePicker(searchSummary)
        }

        findViewById<View>(R.id.row_notification_permission).setOnClickListener {
            onNotificationRowClicked()
        }
        refreshNotificationRow()

        findViewById<View>(R.id.row_clear_data).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.clear_browsing_data_title)
                .setMessage(R.string.clear_browsing_data_message)
                .setPositiveButton(R.string.clear_browsing_data_confirm) { _, _ ->
                    setResult(RESULT_CLEAR_BROWSING_DATA)
                    Toast.makeText(this, R.string.clear_browsing_data_done, Toast.LENGTH_SHORT).show()
                    finish()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        // About card carries just the app name and version here; tapping
        // it opens AboutActivity, which is the home of the longer
        // copyright / engine / known-limitation notes and the
        // Privacy / Terms / Open-source-licenses links. Keeping the
        // Settings list short is the whole point of this consolidation.
        val versionLabel = findViewById<TextView>(R.id.about_version)
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            "1.0"
        }
        versionLabel.text = getString(R.string.about_version_label, versionName)

        findViewById<View>(R.id.row_about).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // The user may have flipped POST_NOTIFICATIONS in system settings
        // while we were paused. Re-bind the row so the summary reflects
        // the current grant state.
        refreshNotificationRow()
        // A background startup check (or a previous "check now") may have
        // changed the version / last-checked time since we were last
        // visible.
        refreshBlocklistRow()
    }

    /**
     * Populate the "Block list updates" row summary with the current
     * version and either the last-checked time or a "not configured"
     * note. Called on create, on resume, and after a manual check.
     */
    private fun refreshBlocklistRow() {
        val summary = findViewById<TextView>(R.id.blocklist_update_summary) ?: return
        val version = BrowserBlocker.currentBlocklistVersion
        summary.text = when {
            !BlocklistUpdateConfig.isConfigured() ->
                getString(R.string.setting_blocklist_update_not_configured, version)

            prefs.blocklistLastCheckedAt <= 0L ->
                getString(R.string.setting_blocklist_update_version_never, version)

            else -> {
                val relative = android.text.format.DateUtils.getRelativeTimeSpanString(
                    prefs.blocklistLastCheckedAt,
                    System.currentTimeMillis(),
                    android.text.format.DateUtils.MINUTE_IN_MILLIS,
                )
                getString(R.string.setting_blocklist_update_version_checked, version, relative)
            }
        }
    }

    /**
     * Manual "check now" — forces a check (bypasses the 24 h rate
     * limit), toasts the outcome, and re-renders the row. Safe to tap
     * when the build has no update source configured: the updater
     * returns [BlocklistUpdater.Result.NotConfigured] and we say so.
     */
    private fun onBlocklistUpdateRowClicked() {
        Toast.makeText(this, R.string.blocklist_update_checking, Toast.LENGTH_SHORT).show()
        BlocklistUpdater.checkForUpdate(this, force = true) { result ->
            val message = when (result) {
                BlocklistUpdater.Result.NotConfigured ->
                    getString(R.string.blocklist_update_not_configured)
                BlocklistUpdater.Result.Skipped ->
                    getString(R.string.blocklist_update_skipped)
                BlocklistUpdater.Result.UpToDate ->
                    getString(R.string.blocklist_update_up_to_date)
                is BlocklistUpdater.Result.Updated ->
                    getString(R.string.blocklist_update_updated, result.newVersion)
                is BlocklistUpdater.Result.Failed ->
                    getString(R.string.blocklist_update_failed, result.reason)
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            refreshBlocklistRow()
        }
    }

    private fun refreshNotificationRow() {
        val summary = findViewById<TextView>(R.id.notification_permission_summary) ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            summary.setText(R.string.setting_notification_permission_legacy)
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        summary.text = when {
            granted -> getString(R.string.setting_notification_permission_granted)
            // shouldShowRequestPermissionRationale is true after the user
            // has denied once but the system will still show the prompt.
            // It's also true before the very first ask — but we can detect
            // that path via the persisted notificationPromptShown flag.
            shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) ||
                !prefs.notificationPromptShown ->
                getString(R.string.setting_notification_permission_can_prompt)
            else -> getString(R.string.setting_notification_permission_permanent_denied)
        }
    }

    private fun onNotificationRowClicked() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

        // Already granted → there's nothing the in-app prompt can do.
        // Open the OS app-info screen so the user can revoke if they
        // want. Same fallback for permanently denied: the OS prompt won't
        // show, and the only path forward is system settings.
        if (granted ||
            (prefs.notificationPromptShown &&
                !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))
        ) {
            openAppInfoSettings()
            return
        }

        prefs.notificationPromptShown = true
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun openAppInfoSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        )
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Extremely rare — only on very stripped AOSP builds. Toast
            // the user so they know the row click "worked" rather than
            // looking broken.
            Toast.makeText(this, R.string.unsupported_link_message, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Build the search-engine picker dialog. For the built-in engines
     * the dialog closes on click; for `CUSTOM` we chain a second dialog
     * that asks for the URL template (and an optional display name +
     * home URL) before persisting.
     */
    private fun showSearchEnginePicker(searchSummary: TextView) {
        val engines = SearchEngineCatalog.entries
        val current = SearchEngineCatalog.fromStoredValue(prefs.searchEngine)
        val labels = engines.map { engine ->
            if (engine == SearchEngineCatalog.CUSTOM &&
                prefs.customSearchEngineName.isNotBlank()
            ) {
                "${prefs.customSearchEngineName} (custom)"
            } else {
                engine.displayName
            }
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.search_engine_dialog_title)
            .setSingleChoiceItems(labels, engines.indexOf(current)) { dialog, which ->
                val picked = engines[which]
                dialog.dismiss()
                if (picked == SearchEngineCatalog.CUSTOM) {
                    showCustomSearchEditor(searchSummary)
                } else {
                    prefs.searchEngine = picked.storageValue
                    searchSummary.text = picked.displayName
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Inline editor for the custom search engine. Three inputs:
     * display name (free text), search-URL template (must contain `%s`
     * or we'll do a best-effort append), and an optional home URL. We
     * validate the template loosely — must be http(s) with at least a
     * dot — to catch the obvious typo cases without rejecting templates
     * that happen to use unusual paths.
     */
    private fun showCustomSearchEditor(searchSummary: TextView) {
        val view = layoutInflater.inflate(R.layout.dialog_custom_search_engine, null)
        val nameInput = view.findViewById<android.widget.EditText>(R.id.custom_search_name)
        val urlInput = view.findViewById<android.widget.EditText>(R.id.custom_search_url)
        val homeInput = view.findViewById<android.widget.EditText>(R.id.custom_search_home)
        nameInput.setText(prefs.customSearchEngineName)
        urlInput.setText(prefs.customSearchEngineUrlTemplate)
        homeInput.setText(prefs.customSearchEngineHomeUrl)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.setting_custom_search_dialog_title)
            .setView(view)
            .setPositiveButton(R.string.setting_custom_search_save) { _, _ ->
                val template = urlInput.text?.toString().orEmpty().trim()
                if (!isPlausibleSearchTemplate(template)) {
                    Toast.makeText(
                        this,
                        R.string.setting_custom_search_invalid_url,
                        Toast.LENGTH_LONG,
                    ).show()
                    return@setPositiveButton
                }
                prefs.customSearchEngineName = nameInput.text?.toString().orEmpty().trim()
                prefs.customSearchEngineUrlTemplate = template
                prefs.customSearchEngineHomeUrl = homeInput.text?.toString().orEmpty().trim()
                prefs.searchEngine = SearchEngineCatalog.CUSTOM.storageValue
                searchSummary.text = SearchEngineResolver.displayName(prefs)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun isPlausibleSearchTemplate(template: String): Boolean {
        if (template.isBlank()) return false
        val lower = template.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        // Require at least one dot in the scheme-less remainder so things
        // like `https://localhost` slip through but `https://x` doesn't.
        val rest = template.substringAfter("://")
        return rest.contains(".") || rest.startsWith("localhost")
    }

    private fun bindSwitchRow(
        rowId: Int,
        titleRes: Int,
        summaryRes: Int,
        get: () -> Boolean,
        set: (Boolean) -> Unit,
    ) {
        val row = findViewById<View>(rowId)
        row.findViewById<TextView>(R.id.switch_title).setText(titleRes)
        row.findViewById<TextView>(R.id.switch_summary).setText(summaryRes)
        val switch = row.findViewById<MaterialSwitch>(R.id.switch_value)

        // Belt-and-braces defence against the "accent change flips every
        // toggle" bug:
        //
        //  1. isSaveEnabled = false — matches saveEnabled="false" set in
        //     item_settings_switch.xml. Skips this switch in the
        //     activity's view-state save/restore pass, which would
        //     otherwise collide across the ~14 included rows that all
        //     share the same @id/switch_value identifier.
        //  2. Detach any prior listener BEFORE writing isChecked. If
        //     state restore (or anything else) sets isChecked while a
        //     listener is attached, the listener fires and overwrites
        //     the durable pref with whatever transient value came in.
        //     bindSwitchRow runs on every onCreate after recreate(); on
        //     the second pass the switch already exists from layout
        //     inflation, but we re-bind defensively.
        //  3. Read the durable value (prefs) into isChecked.
        //  4. Re-attach the listener so subsequent user taps write back.
        switch.isSaveEnabled = false
        switch.setOnCheckedChangeListener(null)
        switch.isChecked = get()
        switch.setOnCheckedChangeListener { _, value -> set(value) }
        // Forward taps anywhere on the row to the switch — this gives the
        // user the full row as a hit target while keeping the switch
        // animation/ripple intact (performClick triggers them properly).
        row.setOnClickListener { switch.performClick() }
    }

    /**
     * Wire the v10 accent picker — five swatches at the bottom of the
     * Appearance section. Tapping a swatch:
     *   1. saves the new accent key to prefs
     *   2. recreate()s the activity so the new accent theme drives
     *      every drawable / view (including this picker, which then
     *      paints its own selection ring in the new accent)
     */
    private fun bindAccentRow() {
        val row = findViewById<View>(R.id.row_accent)
        val accents = AccentTheme.values()
        val swatchIds = intArrayOf(
            R.id.accent_swatch_0,
            R.id.accent_swatch_1,
            R.id.accent_swatch_2,
            R.id.accent_swatch_3,
            R.id.accent_swatch_4,
        )
        val checkIds = intArrayOf(
            R.id.accent_swatch_check_0,
            R.id.accent_swatch_check_1,
            R.id.accent_swatch_check_2,
            R.id.accent_swatch_check_3,
            R.id.accent_swatch_check_4,
        )
        val currentKey = prefs.accentKey
        for (i in accents.indices) {
            val accent = accents[i]
            val swatch = row.findViewById<View>(swatchIds[i])
            val check = row.findViewById<View>(checkIds[i])
            val isSelected = accent.key == currentKey

            // Bind colour at runtime via tintList. We pick the swatch
            // background drawable (plain vs. selected-with-stroke)
            // based on the selected state so the active swatch reads
            // as "pressed in" even before the check icon overlays it.
            val bg = if (isSelected) R.drawable.bg_accent_swatch_selected
            else R.drawable.bg_accent_swatch
            swatch.setBackgroundResource(bg)
            swatch.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, accent.strongColorRes),
            )
            check.visibility = if (isSelected) View.VISIBLE else View.GONE

            val label = getString(accent.labelRes)
            swatch.contentDescription = getString(
                if (isSelected) R.string.accent_swatch_selected_content_description
                else R.string.accent_swatch_content_description,
                label,
            )

            swatch.setOnClickListener {
                if (accent.key == prefs.accentKey) return@setOnClickListener
                prefs.accentKey = accent.key
                // recreate() rebuilds the activity with the new theme,
                // so every drawable / view that resolves
                // ?attr/browserAccent retints in one pass. MainActivity
                // picks up the change on its next onResume via the
                // boundAccentKey check.
                recreate()
            }
        }
    }

    companion object {
        const val RESULT_CLEAR_BROWSING_DATA = 1001
    }
}
