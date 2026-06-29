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

import android.app.Activity
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.annotation.StyleRes

/**
 * The five user-selectable accent palettes wired up in
 * `values/themes.xml` (one `Theme.Ebors.Accent*` style per
 * entry). Each entry carries:
 *
 *  - [key] — the stable string we persist in [BrowserPreferences.accentKey]
 *  - [themeRes] — the activity theme to apply at onCreate
 *  - [strongColorRes] / [softColorRes] — the colour resources behind
 *    the theme attrs, used by the settings UI to draw the picker
 *    swatches (which don't go through theme attrs themselves)
 *  - [labelRes] — the localisable display name for the picker
 *
 * Add a new accent by appending a sixth entry and adding a matching
 * theme + colour pair; no other code change required.
 */
enum class AccentTheme(
    val key: String,
    @param:StyleRes val themeRes: Int,
    @param:ColorRes val strongColorRes: Int,
    @param:ColorRes val softColorRes: Int,
    @param:StringRes val labelRes: Int,
) {
    TERRACOTTA(
        key = "terracotta",
        themeRes = R.style.Theme_Ebors_AccentTerracotta,
        strongColorRes = R.color.accent_terracotta,
        softColorRes = R.color.accent_terracotta_soft,
        labelRes = R.string.accent_label_terracotta,
    ),
    TEAL(
        key = "teal",
        themeRes = R.style.Theme_Ebors_AccentTeal,
        strongColorRes = R.color.accent_teal,
        softColorRes = R.color.accent_teal_soft,
        labelRes = R.string.accent_label_teal,
    ),
    ROYAL_BLUE(
        key = "royal_blue",
        themeRes = R.style.Theme_Ebors_AccentRoyalBlue,
        strongColorRes = R.color.accent_royal_blue,
        softColorRes = R.color.accent_royal_blue_soft,
        labelRes = R.string.accent_label_royal_blue,
    ),
    DEEP_PURPLE(
        key = "deep_purple",
        themeRes = R.style.Theme_Ebors_AccentDeepPurple,
        strongColorRes = R.color.accent_deep_purple,
        softColorRes = R.color.accent_deep_purple_soft,
        labelRes = R.string.accent_label_deep_purple,
    ),
    CHARCOAL(
        key = "charcoal",
        themeRes = R.style.Theme_Ebors_AccentCharcoal,
        strongColorRes = R.color.accent_charcoal,
        softColorRes = R.color.accent_charcoal_soft,
        labelRes = R.string.accent_label_charcoal,
    );

    companion object {
        val DEFAULT = TERRACOTTA

        fun fromKey(key: String?): AccentTheme =
            values().firstOrNull { it.key == key } ?: DEFAULT
    }
}

/**
 * Apply the user-chosen accent theme. Must be called BEFORE
 * [Activity.setContentView] (the theme decides resource resolution
 * for every view inflated below it). Centralised here so every
 * activity opts in by adding a single line at the top of onCreate.
 */
fun Activity.applyAccentTheme(prefs: BrowserPreferences) {
    setTheme(AccentTheme.fromKey(prefs.accentKey).themeRes)
}
