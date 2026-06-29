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
import android.util.Log
import android.webkit.WebView
import org.json.JSONObject
import java.io.IOException

/**
 * Reader-mode glue: evaluate the bundled extractor JS against the
 * WebView's current page, parse the resulting object, render through
 * the template, and hand the finished HTML back to the caller.
 *
 * The actual UX (menu item, snackbar/toast feedback, the
 * `loadDataWithBaseURL` call that swaps the page out) lives in
 * `MainActivity.enterReaderMode` — keeping this object focused on the
 * extraction/rendering pipeline keeps it free of any Activity refs.
 *
 * # Why this isn't full Readability.js
 *
 * Mozilla's Readability.js is ~30 KB minified and handles a long tail
 * of real-world DOM structures, but it's also a maintenance commitment
 * (license + update cadence). The extractor here is a Readability-lite
 * — same scoring approach (text density + class/id pattern hints +
 * link/text ratio) — that covers the common case of news articles and
 * blog posts. On app-shell SPAs it correctly reports "not eligible"
 * instead of producing a broken reader view, which is the most
 * important property: false negatives are fine, false positives are
 * confusing.
 */
internal object ReaderMode {
    private const val TAG = "ReaderMode"
    private const val EXTRACTOR_ASSET = "reader.js"
    private const val TEMPLATE_ASSET = "reader.html"

    @Volatile
    private var cachedExtractor: String? = null

    @Volatile
    private var cachedTemplate: String? = null

    /**
     * Run extraction against [webView]'s current document and call
     * [onResult] on the WebView's thread (the JS engine's main thread
     * == the UI thread, same one that called us) with either a fully
     * rendered HTML string ready for `loadDataWithBaseURL`, or null if
     * the page isn't reader-eligible (extractor returned `eligible: false`,
     * threw, or the assets couldn't be loaded).
     */
    fun extractInto(webView: WebView, onResult: (String?) -> Unit) {
        val script = try {
            cachedExtractor ?: loadAsset(webView.context, EXTRACTOR_ASSET).also {
                cachedExtractor = it
            }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to load $EXTRACTOR_ASSET", e)
            onResult(null)
            return
        }
        webView.evaluateJavascript(script) { rawJson ->
            // evaluateJavascript's callback delivers the JSON encoding
            // of the script's completion value. The extractor's IIFE
            // returns an object, so the callback receives an object
            // literal — JSONObject parses it directly. A "null" string
            // (the JS engine's encoding of `undefined`) or a non-JSON
            // body means the extractor failed somewhere outside its
            // own try/catch — treat that as "not eligible".
            if (rawJson.isNullOrEmpty() || rawJson == "null") {
                onResult(null)
                return@evaluateJavascript
            }
            val parsed = try {
                JSONObject(rawJson)
            } catch (e: Exception) {
                Log.w(TAG, "Reader extractor returned non-JSON: $rawJson", e)
                onResult(null)
                return@evaluateJavascript
            }
            if (!parsed.optBoolean("eligible", false)) {
                onResult(null)
                return@evaluateJavascript
            }
            val html = try {
                renderTemplate(webView.context, parsed)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to render reader template", e)
                onResult(null)
                return@evaluateJavascript
            }
            onResult(html)
        }
    }

    private fun loadAsset(context: Context, name: String): String =
        context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }

    /**
     * Substitute the four placeholders in `reader.html`. Title/byline/
     * site/lang are HTML-escaped because they come from page metadata
     * we don't fully trust; the article content is *not* re-escaped
     * because it's HTML by construction, but it does go through
     * [scrubInjectionVectors] for defence in depth on top of the JS
     * sanitiser. `loadDataWithBaseURL` will give this content the
     * original page's origin, so any leftover script tag would execute
     * with that origin's privileges — hence the second pass.
     */
    private fun renderTemplate(context: Context, data: JSONObject): String {
        val template = cachedTemplate ?: loadAsset(context, TEMPLATE_ASSET).also {
            cachedTemplate = it
        }

        val title = data.optString("title").trim()
            .ifBlank { context.getString(R.string.reader_mode_default_title) }
        val byline = data.optString("byline").trim()
        val siteName = data.optString("siteName").trim()
        val wordCount = data.optInt("wordCount", 0)
        val readingMinutes = (
            (wordCount + READING_WORDS_PER_MINUTE - 1) / READING_WORDS_PER_MINUTE
            ).coerceAtLeast(1)
        val lang = data.optString("lang").trim().ifBlank { "en" }
        val direction = data.optString("dir").trim().lowercase()
            .takeIf { it == "rtl" } ?: "ltr"
        val publishedTime = data.optString("publishedTime").trim()

        val metaParts = mutableListOf<String>()
        if (byline.isNotBlank()) metaParts += byline
        if (siteName.isNotBlank()) metaParts += siteName
        if (publishedTime.isNotBlank()) metaParts += publishedTime.substringBefore('T')
        if (wordCount > 0) {
            metaParts += context.getString(R.string.reader_mode_reading_time, readingMinutes)
        }
        val meta = metaParts.joinToString(" · ") { htmlEscape(it) }

        val content = scrubInjectionVectors(data.optString("content"))

        return template
            .replace("\${LANG}", htmlEscape(lang))
            .replace("\${DIR}", direction)
            .replace("\${TITLE}", htmlEscape(title))
            .replace("\${META}", meta)
            .replace("\${CONTENT}", content)
    }

    private fun htmlEscape(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    /**
     * Belt-and-braces sanitiser on the cleaned article HTML before it
     * goes into the template. The JS extractor already strips scripts /
     * iframes / styles / event handlers, but `loadDataWithBaseURL`
     * gives the resulting page the original site's origin — so a
     * leftover `<script>` would have that origin's privileges. The
     * regex passes here are not a complete HTML sanitiser; they're a
     * targeted second line of defence against the specific surfaces
     * the JS pass aims at.
     */
    private fun scrubInjectionVectors(html: String): String {
        return html
            .replace(Regex("(?is)<script\\b[^>]*>.*?</script\\s*>"), "")
            .replace(Regex("(?is)<iframe\\b[^>]*>.*?</iframe\\s*>"), "")
            .replace(Regex("(?is)<style\\b[^>]*>.*?</style\\s*>"), "")
            // Inline event handlers (`onclick="..."` etc.) — covers
            // both quoting styles. Anything else (URL-form like
            // `javascript:` in href) is a hardening pass too far; the
            // JS sanitiser handles those cases at the attribute level.
            .replace(Regex("(?i)\\son\\w+\\s*=\\s*\"[^\"]*\""), "")
            .replace(Regex("(?i)\\son\\w+\\s*=\\s*'[^']*'"), "")
    }

    /** Average adult reading speed in words per minute. Used for the
     *  "N min read" indicator in the meta row. */
    private const val READING_WORDS_PER_MINUTE = 200
}
