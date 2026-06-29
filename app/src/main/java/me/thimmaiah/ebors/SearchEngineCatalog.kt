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

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * The list of built-in search engines. The `CUSTOM` sentinel is special:
 * its [homeUrl] and search template are sourced from
 * [BrowserPreferences.customSearchEngineHomeUrl] /
 * [BrowserPreferences.customSearchEngineUrlTemplate] at call time rather
 * than hardcoded here. Resolution goes through [SearchEngineResolver] so
 * callers don't have to special-case `CUSTOM`.
 */
enum class SearchEngineCatalog(
    val storageValue: String,
    val displayName: String,
    val homeUrl: String,
    private val searchBaseUrl: String,
) {
    DUCKDUCKGO(
        storageValue = "duckduckgo",
        displayName = "DuckDuckGo",
        homeUrl = "https://duckduckgo.com/",
        searchBaseUrl = "https://duckduckgo.com/?q=",
    ),
    GOOGLE(
        storageValue = "google",
        displayName = "Google",
        homeUrl = "https://www.google.com/",
        searchBaseUrl = "https://www.google.com/search?q=",
    ),
    BING(
        storageValue = "bing",
        displayName = "Bing",
        homeUrl = "https://www.bing.com/",
        searchBaseUrl = "https://www.bing.com/search?q=",
    ),
    BRAVE(
        storageValue = "brave",
        displayName = "Brave",
        homeUrl = "https://search.brave.com/",
        searchBaseUrl = "https://search.brave.com/search?q=",
    ),
    STARTPAGE(
        storageValue = "startpage",
        displayName = "Startpage",
        homeUrl = "https://www.startpage.com/",
        searchBaseUrl = "https://www.startpage.com/do/search?query=",
    ),

    /**
     * User-supplied search template. The real URL is stored in
     * BrowserPreferences and resolved by [SearchEngineResolver]; the
     * placeholders here exist so the Settings UI can still render an
     * entry for the user to pick. `homeUrl` / `searchBaseUrl` here are
     * intentionally unused — they're guarded behind the `CUSTOM` check
     * in [SearchEngineResolver].
     */
    CUSTOM(
        storageValue = "custom",
        displayName = "Custom…",
        homeUrl = "",
        searchBaseUrl = "",
    ),
    ;

    fun buildSearchUrl(query: String): String {
        return searchBaseUrl + URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
    }

    companion object {
        fun fromStoredValue(value: String?): SearchEngineCatalog {
            return entries.firstOrNull { it.storageValue == value } ?: DUCKDUCKGO
        }
    }
}

/**
 * Bridges [SearchEngineCatalog] and [BrowserPreferences] so call sites
 * don't have to know whether the user picked a built-in or a custom
 * search engine. The `CUSTOM` branch reads the per-user template /
 * home URL from preferences; everything else uses the catalog defaults.
 *
 * Search templates accept a single `%s` placeholder for the
 * URL-encoded query (matching Firefox / Chromium's keyword search
 * vocabulary). If the user types a template without `%s` we append
 * the query as a `?q=` query string parameter so they get *something*
 * reasonable instead of a static URL.
 */
object SearchEngineResolver {

    fun displayName(prefs: BrowserPreferences): String {
        val engine = SearchEngineCatalog.fromStoredValue(prefs.searchEngine)
        return if (engine == SearchEngineCatalog.CUSTOM) {
            val label = prefs.customSearchEngineName
            label.ifBlank { engine.displayName }
        } else {
            engine.displayName
        }
    }

    fun homeUrl(prefs: BrowserPreferences): String {
        val engine = SearchEngineCatalog.fromStoredValue(prefs.searchEngine)
        return if (engine == SearchEngineCatalog.CUSTOM) {
            prefs.customSearchEngineHomeUrl
                .takeIf { it.isNotBlank() }
                ?: SearchEngineCatalog.DUCKDUCKGO.homeUrl
        } else {
            engine.homeUrl
        }
    }

    fun buildSearchUrl(prefs: BrowserPreferences, query: String): String {
        val engine = SearchEngineCatalog.fromStoredValue(prefs.searchEngine)
        if (engine != SearchEngineCatalog.CUSTOM) {
            return engine.buildSearchUrl(query)
        }
        val template = prefs.customSearchEngineUrlTemplate
        if (template.isBlank()) {
            return SearchEngineCatalog.DUCKDUCKGO.buildSearchUrl(query)
        }
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
        return if ("%s" in template) {
            template.replace("%s", encoded)
        } else {
            // No `%s` — fall back to appending `?q=` (or `&q=` if the
            // template already has a query string). This is a friendly
            // best-effort for users who pasted a homepage URL rather
            // than a search template.
            val joiner = if ("?" in template) "&" else "?"
            "$template${joiner}q=$encoded"
        }
    }
}

