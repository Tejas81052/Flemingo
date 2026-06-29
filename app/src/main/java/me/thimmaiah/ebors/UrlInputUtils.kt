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

import java.net.IDN
import java.net.URI
import java.util.Locale

/**
 * Pure URL-handling helpers used by the address bar and the WebViewClient.
 *
 * Extracted from MainActivity for two reasons:
 *
 *  1. Testability. These functions are all `String → String` / `String →
 *     Boolean` — easy to drive from a JVM unit test. Leaving them as
 *     private methods on an Activity made them impossible to test without
 *     spinning up Robolectric or instrumentation.
 *  2. Separation. None of them depend on any Android runtime state
 *     (no `Context`, no preferences) once the caller passes them their
 *     inputs. Keeping them in their own file makes the security-relevant
 *     transformations easy to audit at a glance.
 *
 * Anything here that *does* take a preference takes it as an explicit
 * parameter rather than reading a singleton — the caller knows which
 * pref is relevant.
 */
object UrlInputUtils {

    private const val JAVASCRIPT_SCHEME = "javascript:"

    /**
     * Strip every leading `javascript:` scheme (case-insensitive,
     * tolerating whitespace and stacked prefixes like
     * `javascript:javascript:foo`).
     *
     * Loading a javascript: URL via WebView.loadUrl runs the script in
     * the *currently loaded* page's context, which is a self-XSS vector.
     * Chrome blocks it from the omnibox; we do the same here, both for
     * direct user input and for clipboard pastes.
     */
    fun stripJavascriptScheme(input: String): String {
        var current = input
        while (true) {
            val trimmed = current.trimStart()
            if (trimmed.regionMatches(0, JAVASCRIPT_SCHEME, 0, JAVASCRIPT_SCHEME.length, ignoreCase = true)) {
                current = trimmed.substring(JAVASCRIPT_SCHEME.length)
            } else {
                return trimmed
            }
        }
    }

    /**
     * Heuristic: does this look like a URL the user is trying to navigate
     * to (as opposed to a search query)? A token with a space in it is
     * never a URL; an `about:` / `data:` prefix always is; everything
     * else is decided by trying to parse it as a host that contains at
     * least one dot.
     */
    fun looksLikeUrl(input: String): Boolean {
        if (input.contains(" ")) return false
        if (input.startsWith("about:", ignoreCase = true) ||
            input.startsWith("data:", ignoreCase = true)
        ) return true

        val candidate = if (input.contains("://")) input else "https://$input"
        return try {
            val parsed = URI(candidate)
            val host = parsed.host ?: return false
            host.contains(".") || host.equals("localhost", ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Prepend `https://` if the input doesn't already declare a scheme.
     * This is the *minimum* enforcement — see [shouldUpgradeToHttps] for
     * the bigger lever that also rewrites explicit `http://` URLs.
     */
    fun enforceSecureScheme(input: String): String = when {
        input.contains("://") -> input
        else -> "https://$input"
    }

    /**
     * Should an `http://` URL be silently upgraded to `https://`?
     *
     * Returns false for loopback, `.local`, and RFC1918 private IPv4
     * ranges (plus the IPv6 ULA / link-local / loopback equivalents) —
     * intranet HTTP is legitimate and forcing HTTPS there mostly just
     * produces broken pages.
     *
     * Note: we hand-roll host extraction via [hostRange] rather than
     * use `java.net.URI` because URI rejects non-ASCII hostnames and
     * keeps the surrounding `[ ]` on IPv6 literals — both inconvenient
     * for the private-host check.
     */
    fun shouldUpgradeToHttps(url: String): Boolean {
        if (!url.startsWith("http://", ignoreCase = true)) return false
        val range = hostRange(url) ?: return false
        val host = url.substring(range.first, range.last + 1)
        return !isPrivateOrLocalHost(host)
    }

    /** Mechanical scheme rewrite. Caller decides whether the rewrite is allowed. */
    fun upgradeToHttps(url: String): String =
        url.replaceFirst("http://", "https://", ignoreCase = true)

    fun isPrivateOrLocalHost(host: String): Boolean {
        val h = host.lowercase(Locale.US)
        if (h == "localhost") return true
        if (h.endsWith(".local") || h.endsWith(".localhost")) return true
        if (h.matches(IPV4_LOOPBACK) ||
            h.matches(IPV4_PRIVATE_10) ||
            h.matches(IPV4_PRIVATE_172) ||
            h.matches(IPV4_PRIVATE_192) ||
            h.matches(IPV4_LINK_LOCAL)
        ) return true
        // IPv6: URI.host strips the surrounding `[ ]`, so we compare the
        // bare address. Coverage: ::1 (loopback), fc00::/7 (ULA),
        // fe80::/10 (link-local).
        if (h == "::1") return true
        if (h.startsWith("fc") || h.startsWith("fd")) return true
        if (h.startsWith("fe8") || h.startsWith("fe9") ||
            h.startsWith("fea") || h.startsWith("feb")
        ) return true
        return false
    }

    /**
     * Format [url] for display in the address bar.
     *
     * Two security-relevant rules:
     *
     *  1. **Do not strip `www.`.** `https://www.google.com` and
     *     `https://google.com` are different hosts; collapsing them makes
     *     phishing easier. Returns the host portion verbatim.
     *  2. **Punycode mixed-script hostnames.** Anything that mixes Latin
     *     with Cyrillic / Greek / etc. inside a single label is shown as
     *     `xn--…` so a homoglyph attacker can't pass off Cyrillic `а` as
     *     Latin `a` to disguise the host.
     */
    fun prettifyUrl(url: String): String = forcePunycodeIfMixedScript(url)

    fun forcePunycodeIfMixedScript(url: String): String {
        val range = hostRange(url) ?: return url
        val host = url.substring(range.first, range.last + 1)
        if (host.all { it.code < 128 }) return url
        if (host.split('.').none(::isMixedScriptLabel)) return url
        val ascii = try {
            IDN.toASCII(host, IDN.ALLOW_UNASSIGNED)
        } catch (_: Exception) {
            return url
        }
        return url.replaceRange(range, ascii)
    }

    /**
     * Extract the bare host (no scheme, no user-info, no port, no
     * IPv6 brackets) from a URL. Null if the URL doesn't have an
     * `://` separator, has a malformed bracketed-IPv6 literal, or has
     * no host segment at all.
     *
     * Exposed for callers (e.g. BlockedSitesNormalizer) that need
     * JVM-friendly host extraction — `android.net.Uri.parse` would
     * NPE in unit tests.
     */
    fun extractHost(url: String): String? {
        val range = hostRange(url) ?: return null
        return url.substring(range.first, range.last + 1)
    }

    /**
     * Returns the inclusive `[first, last]` range within [url] that
     * contains the bare host — no scheme, no user-info, no port, no
     * brackets on IPv6. Null if the URL doesn't have an `://` separator
     * or the bracketed-IPv6 literal is malformed.
     *
     * Hand-rolled because `java.net.URI` won't accept non-ASCII hosts
     * and keeps the IPv6 brackets in [URI.getHost]. Hand-rolling lets
     * [shouldUpgradeToHttps], [forcePunycodeIfMixedScript], and
     * [extractHost] all share the same primitive.
     */
    private fun hostRange(url: String): IntRange? {
        val schemeEnd = url.indexOf("://").takeIf { it >= 0 } ?: return null
        val authStart = schemeEnd + 3
        var pathStart = url.length
        for (i in authStart until url.length) {
            val c = url[i]
            if (c == '/' || c == '?' || c == '#') {
                pathStart = i
                break
            }
        }
        // Strip user-info ("user:pass@") if present. We search the slice
        // between authStart and pathStart for the *last* '@' to avoid
        // mis-matching the literal '@' that can legitimately appear
        // inside a percent-encoded user info.
        val authority = url.substring(authStart, pathStart)
        val atIdxLocal = authority.lastIndexOf('@')
        val hostPartStart = authStart + (if (atIdxLocal >= 0) atIdxLocal + 1 else 0)

        // IPv6 literal: [::1] or [fe80::1]:8080. Return the substring
        // between the brackets.
        if (hostPartStart < url.length && url[hostPartStart] == '[') {
            val closeBracket = url.indexOf(']', hostPartStart + 1)
            if (closeBracket == -1 || closeBracket > pathStart) return null
            return IntRange(hostPartStart + 1, closeBracket - 1)
        }

        // IPv4 / DNS host: terminated by ':' (port) or by pathStart.
        var hostEnd = pathStart
        for (i in hostPartStart until pathStart) {
            if (url[i] == ':') {
                hostEnd = i
                break
            }
        }
        if (hostEnd <= hostPartStart) return null
        return IntRange(hostPartStart, hostEnd - 1)
    }

    /**
     * Canonicalise [url] for equality / dedup comparisons. The fragment is
     * dropped (anchors don't change which page is being visited), the
     * trailing slash is stripped from a non-root path, and scheme + host
     * are lower-cased. Query is preserved because it often *does*
     * identify a distinct page (search results, single-page apps).
     *
     * Falls back to the trimmed original on parse failure so callers
     * never see null on a weird input — at worst the two near-identical
     * URLs survive in the caller's de-duplicated set, matching the
     * legacy behaviour.
     *
     * Shared by [BookmarkRepository] (canonical storage form) and
     * [HistoryRepository] (dedup key) so a bookmark and a history entry
     * for the same logical page agree on what "same" means.
     */
    fun canonicalForCompare(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return trimmed
        return try {
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase(Locale.US) ?: return trimmed
            val host = uri.host?.lowercase(Locale.US) ?: return trimmed
            val port = if (uri.port == defaultPortForScheme(scheme)) -1 else uri.port
            var path = uri.path ?: ""
            if (path.length > 1 && path.endsWith('/')) {
                path = path.dropLast(1)
            }
            URI(scheme, uri.userInfo, host, port, path, uri.query, /* fragment = */ null)
                .toASCIIString()
        } catch (_: Exception) {
            trimmed
        }
    }

    private fun defaultPortForScheme(scheme: String): Int = when (scheme) {
        "http" -> 80
        "https" -> 443
        else -> -1
    }

    fun isMixedScriptLabel(label: String): Boolean {
        var first: Character.UnicodeScript? = null
        for (c in label) {
            val script = Character.UnicodeScript.of(c.code)
            if (script == Character.UnicodeScript.COMMON ||
                script == Character.UnicodeScript.INHERITED
            ) continue
            if (first == null) {
                first = script
            } else if (script != first) {
                return true
            }
        }
        return false
    }

    private val IPV4_LOOPBACK = Regex("^127(\\.\\d{1,3}){3}$")
    private val IPV4_PRIVATE_10 = Regex("^10(\\.\\d{1,3}){3}$")
    private val IPV4_PRIVATE_172 = Regex("^172\\.(1[6-9]|2[0-9]|3[01])(\\.\\d{1,3}){2}$")
    private val IPV4_PRIVATE_192 = Regex("^192\\.168(\\.\\d{1,3}){2}$")
    private val IPV4_LINK_LOCAL = Regex("^169\\.254(\\.\\d{1,3}){2}$")
}
