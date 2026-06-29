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

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Live address-bar autocomplete sourced from the configured search
 * engine's public suggestion endpoint. Most engines answer in the
 * OpenSearch shape `["query", ["s1", "s2", …]]`; DuckDuckGo's
 * `type=list` endpoint matches it, and its object form
 * (`[{"phrase": …}]`) is handled as a fallback.
 *
 * # Privacy
 *
 * Each keystroke (debounced by the caller) sends the typed prefix to
 * the chosen engine — the same trade-off every mainstream browser's
 * search box makes. It is gated behind
 * [BrowserPreferences.searchSuggestionsEnabled] (default on) so a
 * privacy-leaning user can switch it off and fall back to local
 * history + the "search this" row only.
 *
 * # Threading
 *
 * [fetch] returns immediately; the request runs on a private
 * single-thread executor and [onResult] is posted on the main thread
 * with the originating [query] so the caller can drop stale responses.
 * Any failure (offline, timeout, unparseable body, unsupported engine)
 * resolves to an empty list rather than an exception.
 */
object SearchSuggestions {
    private const val TAG = "SearchSuggestions"
    private const val MAX_SUGGESTIONS = 6

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(3, TimeUnit.SECONDS)
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()
    }

    fun fetch(prefs: BrowserPreferences, query: String, onResult: (String, List<String>) -> Unit) {
        val trimmed = query.trim()
        val engine = SearchEngineCatalog.fromStoredValue(prefs.searchEngine)
        val url = suggestUrl(engine, trimmed)
        if (trimmed.isBlank() || url == null) {
            onResult(query, emptyList())
            return
        }
        executor.execute {
            val suggestions = runCatching { request(url) }.getOrElse {
                Log.d(TAG, "suggestion fetch failed: ${it.message}")
                emptyList()
            }
            mainHandler.post { onResult(query, suggestions) }
        }
    }

    private fun request(url: String): List<String> {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android) Ebors")
            .header("Accept", "application/json,*/*")
            .build()
        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val text = response.body?.string() ?: return emptyList()
            return parse(text)
        }
    }

    /** Parse either the OpenSearch list form or DuckDuckGo's object form. */
    private fun parse(body: String): List<String> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        val root = JSONArray(trimmed)
        val openSearchList = root.optJSONArray(1)
        if (openSearchList != null) {
            for (i in 0 until openSearchList.length()) {
                openSearchList.optString(i).takeIf { it.isNotBlank() }?.let { out.add(it) }
            }
        } else {
            for (i in 0 until root.length()) {
                root.optJSONObject(i)?.optString("phrase")
                    ?.takeIf { it.isNotBlank() }?.let { out.add(it) }
            }
        }
        return out.distinct().take(MAX_SUGGESTIONS)
    }

    private fun suggestUrl(engine: SearchEngineCatalog, query: String): String? {
        if (query.isBlank()) return null
        val q = URLEncoder.encode(query, "UTF-8")
        return when (engine) {
            SearchEngineCatalog.DUCKDUCKGO -> "https://duckduckgo.com/ac/?q=$q&type=list"
            SearchEngineCatalog.GOOGLE ->
                "https://suggestqueries.google.com/complete/search?client=firefox&q=$q"
            SearchEngineCatalog.BING -> "https://api.bing.com/osjson.aspx?query=$q"
            SearchEngineCatalog.BRAVE -> "https://search.brave.com/api/suggest?q=$q&rich=false"
            // Startpage / Custom engines have no documented OpenSearch
            // endpoint we can rely on — fall back to local + search row.
            SearchEngineCatalog.STARTPAGE, SearchEngineCatalog.CUSTOM -> null
        }
    }
}
