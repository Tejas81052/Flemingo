/*
 * Ebors - a privacy-focused Android browser
 * Copyright (C) 2026 Tejas Thimmaiah
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package me.thimmaiah.ebors

import java.util.Locale

/**
 * Network request type, mapped onto the Adblock-Plus / uBlock Origin filter
 * `$type` options. Each carries a distinct bit so a filter can constrain
 * itself to a set of types with a single mask test.
 */
enum class RequestType(val bit: Int) {
    DOCUMENT(1 shl 0),
    SUBDOCUMENT(1 shl 1),
    SCRIPT(1 shl 2),
    IMAGE(1 shl 3),
    STYLESHEET(1 shl 4),
    XHR(1 shl 5),
    FONT(1 shl 6),
    MEDIA(1 shl 7),
    WEBSOCKET(1 shl 8),
    PING(1 shl 9),
    OBJECT(1 shl 10),
    OTHER(1 shl 11),
}

/** Everything the engine needs to evaluate one request. */
class RequestContext(
    url: String,
    val host: String,
    val type: RequestType,
    /** Registrable hostname of the document the request originates from. */
    val sourceHost: String,
    /** True when [host] and [sourceHost] are on different registrable domains. */
    val thirdParty: Boolean,
) {
    /** Lowercased URL — patterns are stored lowercased, so we match in kind. */
    val url: String = url.lowercase(Locale.US)
    val typeBit: Int = type.bit
}

enum class Decision { NONE, BLOCK, ALLOW }

/**
 * A single parsed network filter. Produced by [FilterParser.parse]; consumed
 * only by [FilterEngine]. Pattern matching follows the EasyList/ABP rules:
 * `||` host anchor, `|` start/end anchor, `*` wildcard, `^` separator
 * (any char that is not a letter, digit, `_`, `-`, `.` or `%`, or end-of-URL).
 */
class NetworkFilter(
    val isException: Boolean,
    val important: Boolean,
    /** 0 = any party, 1 = third-party only, 2 = first-party only. */
    val party: Int,
    /** 0 = all types, else OR of [RequestType.bit]s the filter applies to. */
    val typeMask: Int,
    val notTypeMask: Int,
    /** `$domain=` source constraints (host suffixes), or null when unconstrained. */
    val includeDomains: List<String>?,
    val excludeDomains: List<String>?,
    private val anchorHost: Boolean,
    private val anchorStart: Boolean,
    private val anchorEnd: Boolean,
    private val parts: List<String>,
    /** Bucket token (lowercased), or null for the catch-all bucket. */
    val token: String?,
    /** Non-null for plain `||host^` filters; routes them to the suffix set. */
    val pureHost: String? = null,
) {
    fun matches(ctx: RequestContext): Boolean {
        if (ctx.type == RequestType.DOCUMENT) {
            // A top-level navigation is only blocked by a filter that
            // explicitly opts in with $document. Generic filters target
            // subresources, never the page itself — applying them to
            // navigations breaks ordinary link clicks (ABP/uBO semantics).
            if ((typeMask and RequestType.DOCUMENT.bit) == 0) return false
        } else if (typeMask != 0 && (typeMask and ctx.typeBit) == 0) {
            return false
        }
        if (notTypeMask != 0 && (notTypeMask and ctx.typeBit) != 0) return false
        if (party == 1 && !ctx.thirdParty) return false
        if (party == 2 && ctx.thirdParty) return false
        includeDomains?.let { if (!sourceMatches(ctx.sourceHost, it)) return false }
        excludeDomains?.let { if (sourceMatches(ctx.sourceHost, it)) return false }
        return matchPattern(ctx.url)
    }

    private fun matchPattern(url: String): Boolean {
        if (parts.isEmpty()) return true // degenerate `||` / `*`
        val starts = when {
            anchorHost -> hostAnchorPositions(url)
            anchorStart -> intArrayOf(0)
            else -> null
        }
        if (starts != null) {
            for (s in starts) if (matchFrom(url, s)) return true
            return false
        }
        // Free-floating: the first part may begin anywhere.
        var p = 0
        val first = parts[0]
        while (p <= url.length) {
            val e = matchLiteral(url, p, first, parts.size == 1)
            if (e >= 0 && matchRest(url, e, 1)) return true
            p++
        }
        return false
    }

    private fun matchFrom(url: String, pos: Int): Boolean {
        val e = matchLiteral(url, pos, parts[0], parts.size == 1)
        if (e < 0) return false
        return matchRest(url, e, 1)
    }

    /** Match parts[index..] against url starting at [from], honouring wildcards. */
    private fun matchRest(url: String, from: Int, index: Int): Boolean {
        var cur = from
        var i = index
        while (i < parts.size) {
            val part = parts[i]
            val isLast = i == parts.size - 1
            var found = -1
            var p = cur
            while (p <= url.length) {
                val e = matchLiteral(url, p, part, isLast)
                if (e >= 0) { found = e; break }
                p++
            }
            if (found < 0) return false
            cur = found
            i++
        }
        return !anchorEnd || cur == url.length
    }

    /** Match literal [part] (may contain `^`) at [pos]; returns end index or -1. */
    private fun matchLiteral(url: String, pos: Int, part: String, lastPart: Boolean): Int {
        var u = pos
        var i = 0
        while (i < part.length) {
            val pc = part[i]
            if (pc == '^') {
                when {
                    u < url.length && isSeparator(url[u]) -> u++
                    u == url.length && lastPart && i == part.length - 1 -> { /* matches end */ }
                    else -> return -1
                }
            } else {
                if (u >= url.length || url[u] != pc) return -1
                u++
            }
            i++
        }
        return u
    }

    companion object {
        fun isSeparator(c: Char): Boolean =
            !((c in 'a'..'z') || (c in '0'..'9') || c == '_' || c == '-' || c == '.' || c == '%')

        /** Start indices in [url] that sit on a hostname label boundary. */
        private fun hostAnchorPositions(url: String): IntArray {
            val schemeIdx = url.indexOf("://")
            val hostStart = if (schemeIdx >= 0) schemeIdx + 3 else 0
            var hostEnd = url.length
            for (i in hostStart until url.length) {
                val c = url[i]
                if (c == '/' || c == '?' || c == '#') { hostEnd = i; break }
            }
            val positions = ArrayList<Int>()
            positions.add(hostStart)
            for (i in hostStart until hostEnd) if (url[i] == '.') positions.add(i + 1)
            return positions.toIntArray()
        }

        private fun sourceMatches(sourceHost: String, domains: List<String>): Boolean {
            if (sourceHost.isEmpty()) return false
            for (d in domains) {
                when {
                    // Entity form `name.*` — matches name.<any-tld> and its subdomains.
                    d.endsWith(".*") -> {
                        val e = d.dropLast(2)
                        if (e.isNotEmpty() &&
                            (sourceHost == e || sourceHost.startsWith("$e.") || sourceHost.contains(".$e."))
                        ) return true
                    }
                    // Wildcard-subdomain form `*.suffix`.
                    d.startsWith("*.") -> {
                        val suf = d.substring(2)
                        if (suf.isNotEmpty() && (sourceHost == suf || sourceHost.endsWith(".$suf"))) return true
                    }
                    else -> if (sourceHost == d || sourceHost.endsWith(".$d")) return true
                }
            }
            return false
        }
    }
}

/**
 * Parses one filter-list line into a [NetworkFilter], or null when the line is
 * a comment, a cosmetic/scriptlet rule (handled elsewhere), or uses an option
 * we deliberately don't support yet. Unknown or behaviour-changing options
 * ($redirect, $removeparam, $csp, $popup, …) cause the line to be skipped
 * rather than mis-treated as a plain block — false positives break pages.
 */
object FilterParser {

    private val HOSTS_PREFIX = Regex("^(0\\.0\\.0\\.0|127\\.0\\.0\\.1|::1?)\\s+")
    private val HOSTNAME = Regex("^[a-z0-9.\\-_]+$")
    private val STOP_TOKENS = setOf(
        "http", "https", "www", "com", "net", "org", "html", "the", "for",
    )

    fun parse(line: String): NetworkFilter? {
        var s = line.trim()
        if (s.isEmpty()) return null
        // `!` / `[` are ABP comments; a leading `#` is a hosts-file comment or
        // a generic cosmetic rule (`##.ad`) — none are network filters.
        if (s[0] == '!' || s[0] == '[' || s[0] == '#') return null
        // Cosmetic / scriptlet rules carry `##`, `#@#`, `#?#`, `#$#` — not ours.
        if (s.contains("##") || s.contains("#@#") || s.contains("#?#") || s.contains("#\$#")) return null

        // Hosts-file format: `0.0.0.0 host` → treat as `||host^`.
        HOSTS_PREFIX.find(s)?.let { m ->
            val host = s.substring(m.range.last + 1).substringBefore('#').trim()
            if (host.isEmpty() || host == "localhost" || !HOSTNAME.matches(host)) return null
            return hostFilter(host, isException = false)
        }

        val isException = s.startsWith("@@")
        if (isException) s = s.substring(2)

        // Split trailing `$options`. The `$` must not be inside a regex pattern
        // (`/.../`) — we don't support regex filters, so skip those outright.
        if (s.startsWith("/") && s.endsWith("/") && s.length > 1) return null
        var pattern = s
        var options = ""
        val dollar = s.lastIndexOf('$')
        if (dollar >= 0) {
            pattern = s.substring(0, dollar)
            options = s.substring(dollar + 1)
        }
        if (pattern.isEmpty()) return null

        var party = 0
        var typeMask = 0
        var notTypeMask = 0
        var important = false
        var include: MutableList<String>? = null
        var exclude: MutableList<String>? = null

        if (options.isNotEmpty()) {
            for (rawOpt in options.split(',')) {
                val opt = rawOpt.trim().lowercase(Locale.US)
                if (opt.isEmpty()) continue
                when {
                    opt == "third-party" || opt == "3p" -> party = 1
                    opt == "~third-party" || opt == "~3p" ||
                        opt == "first-party" || opt == "1p" -> party = 2
                    opt == "important" -> important = true
                    opt == "all" -> { /* all types: leave mask 0 */ }
                    opt == "match-case" -> { /* we match lowercased; ignore */ }
                    opt.startsWith("domain=") -> {
                        // Keep entity (`site.*`) and wildcard-subdomain (`*.site`)
                        // forms — sourceMatches understands them. Dropping them
                        // would empty the include list and turn a site-scoped
                        // filter into a catastrophic global one.
                        var sawInclude = false
                        for (d in opt.substring(7).split('|')) {
                            val dom = d.trim()
                            if (dom.isEmpty() || dom == "*") continue
                            if (dom.startsWith("~")) {
                                val v = dom.substring(1)
                                if (v.isNotEmpty() && v != "*") {
                                    (exclude ?: ArrayList<String>().also { exclude = it }).add(v)
                                }
                            } else {
                                sawInclude = true
                                (include ?: ArrayList<String>().also { include = it }).add(dom)
                            }
                        }
                        // A $domain= that names include domains but yields none
                        // must never widen into a global filter.
                        if (sawInclude && include.isNullOrEmpty()) return null
                    }
                    else -> {
                        val t = TYPE_OPTIONS[opt.removePrefix("~")]
                            ?: return null // unknown / unsupported option → skip filter
                        if (opt.startsWith("~")) notTypeMask = notTypeMask or t
                        else typeMask = typeMask or t
                    }
                }
            }
        }

        // Pure `||host^` with no options → hostname fast-path.
        if (typeMask == 0 && notTypeMask == 0 && party == 0 && !important &&
            include == null && exclude == null && pattern.startsWith("||")
        ) {
            val body = pattern.substring(2).removeSuffix("^")
            if (HOSTNAME.matches(body.lowercase(Locale.US)) && !body.contains('*')) {
                return hostFilter(body.lowercase(Locale.US), isException)
            }
        }

        return buildPatternFilter(
            pattern, isException, important, party, typeMask, notTypeMask, include, exclude,
        )
    }

    private fun hostFilter(host: String, isException: Boolean): NetworkFilter =
        NetworkFilter(
            isException = isException, important = false, party = 0,
            typeMask = 0, notTypeMask = 0, includeDomains = null, excludeDomains = null,
            anchorHost = true, anchorStart = false, anchorEnd = false,
            parts = listOf("$host^"), token = pickToken(host), pureHost = host,
        )

    private fun buildPatternFilter(
        rawPattern: String,
        isException: Boolean,
        important: Boolean,
        party: Int,
        typeMask: Int,
        notTypeMask: Int,
        include: List<String>?,
        exclude: List<String>?,
    ): NetworkFilter? {
        var body = rawPattern.lowercase(Locale.US)
        var anchorHost = false
        var anchorStart = false
        var anchorEnd = false
        if (body.startsWith("||")) { anchorHost = true; body = body.substring(2) }
        else if (body.startsWith("|")) { anchorStart = true; body = body.substring(1) }
        if (body.endsWith("|")) { anchorEnd = true; body = body.dropLast(1) }
        // Leading/trailing `*` simply means "not anchored on that side".
        if (body.startsWith("*")) { anchorStart = false; body = body.trimStart('*') }
        if (body.endsWith("*")) { anchorEnd = false; body = body.trimEnd('*') }
        val parts = body.split('*').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        return NetworkFilter(
            isException = isException, important = important, party = party,
            typeMask = typeMask, notTypeMask = notTypeMask,
            includeDomains = include, excludeDomains = exclude,
            anchorHost = anchorHost, anchorStart = anchorStart, anchorEnd = anchorEnd,
            parts = parts, token = pickTokenFromBody(body),
        )
    }

    /** Pick the most selective bucket token from a pattern body. */
    private fun pickTokenFromBody(body: String): String? {
        var best: String? = null
        val tokens = tokenize(body)
        for (t in tokens) {
            if (t in STOP_TOKENS) continue
            if (best == null || t.length >= best!!.length) best = t
        }
        return best
    }

    private fun pickToken(host: String): String? = pickTokenFromBody(host.lowercase(Locale.US))

    private val TYPE_OPTIONS: Map<String, Int> = buildMap {
        put("script", RequestType.SCRIPT.bit)
        put("image", RequestType.IMAGE.bit)
        put("stylesheet", RequestType.STYLESHEET.bit)
        put("css", RequestType.STYLESHEET.bit)
        put("object", RequestType.OBJECT.bit)
        put("object-subrequest", RequestType.OBJECT.bit)
        put("xmlhttprequest", RequestType.XHR.bit)
        put("xhr", RequestType.XHR.bit)
        put("subdocument", RequestType.SUBDOCUMENT.bit)
        put("frame", RequestType.SUBDOCUMENT.bit)
        put("document", RequestType.DOCUMENT.bit)
        put("doc", RequestType.DOCUMENT.bit)
        put("font", RequestType.FONT.bit)
        put("media", RequestType.MEDIA.bit)
        put("websocket", RequestType.WEBSOCKET.bit)
        put("ping", RequestType.PING.bit)
        put("beacon", RequestType.PING.bit)
        put("other", RequestType.OTHER.bit)
    }

    fun tokenize(s: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        val n = s.length
        while (i < n) {
            if (isTokenChar(s[i])) {
                var j = i + 1
                while (j < n && isTokenChar(s[j])) j++
                if (j - i >= 3) out.add(s.substring(i, j))
                i = j
            } else i++
        }
        return out
    }

    private fun isTokenChar(c: Char): Boolean =
        (c in 'a'..'z') || (c in '0'..'9') || c == '%'
}

/**
 * Tokenised matching engine. Pure host filters (`||host^`) go into a suffix
 * set for O(label-count) lookup; everything else is bucketed by a single token
 * so a request only tests the handful of filters whose token appears in its
 * URL, plus a small catch-all bucket. Block and exception (`@@`) filters are
 * kept apart; an exception wins unless the matching block filter is `important`.
 */
class FilterEngine private constructor(
    private val blockHosts: Set<String>,
    private val allowHosts: Set<String>,
    private val blockBuckets: Map<String, List<NetworkFilter>>,
    private val blockNoToken: List<NetworkFilter>,
    private val allowBuckets: Map<String, List<NetworkFilter>>,
    private val allowNoToken: List<NetworkFilter>,
    val filterCount: Int,
) {
    fun match(ctx: RequestContext): Decision {
        val block = firstMatch(ctx, blockHosts, blockBuckets, blockNoToken) ?: return Decision.NONE
        if (block.important) return Decision.BLOCK
        val allow = firstMatch(ctx, allowHosts, allowBuckets, allowNoToken)
        return if (allow != null) Decision.ALLOW else Decision.BLOCK
    }

    private fun firstMatch(
        ctx: RequestContext,
        hosts: Set<String>,
        buckets: Map<String, List<NetworkFilter>>,
        noToken: List<NetworkFilter>,
    ): NetworkFilter? {
        // Host filters are generic (no $document), so they too must not block
        // a top-level navigation — only its subresources.
        if (ctx.type != RequestType.DOCUMENT && hostInSet(ctx.host, hosts)) return HOST_HIT
        for (token in FilterParser.tokenize(ctx.url).toHashSet()) {
            val bucket = buckets[token] ?: continue
            for (f in bucket) if (f.matches(ctx)) return f
        }
        for (f in noToken) if (f.matches(ctx)) return f
        return null
    }

    companion object {
        /** Sentinel returned when a request matches the hostname suffix set. */
        private val HOST_HIT = NetworkFilter(
            isException = false, important = false, party = 0,
            typeMask = 0, notTypeMask = 0, includeDomains = null, excludeDomains = null,
            anchorHost = true, anchorStart = false, anchorEnd = false,
            parts = emptyList(), token = null,
        )

        private fun hostInSet(host: String, set: Set<String>): Boolean {
            if (host.isEmpty() || set.isEmpty()) return false
            if (set.contains(host)) return true
            var i = host.indexOf('.')
            while (i in 0 until host.length - 1) {
                if (set.contains(host.substring(i + 1))) return true
                i = host.indexOf('.', i + 1)
            }
            return false
        }
    }

    class Builder {
        private val blockHosts = HashSet<String>()
        private val allowHosts = HashSet<String>()
        private val blockBuckets = HashMap<String, MutableList<NetworkFilter>>()
        private val blockNoToken = ArrayList<NetworkFilter>()
        private val allowBuckets = HashMap<String, MutableList<NetworkFilter>>()
        private val allowNoToken = ArrayList<NetworkFilter>()
        private var count = 0

        fun addList(text: String): Builder {
            for (line in text.lineSequence()) add(line)
            return this
        }

        fun add(line: String): Builder {
            val f = FilterParser.parse(line) ?: return this
            val host = f.pureHost
            if (host != null) {
                (if (f.isException) allowHosts else blockHosts).add(host)
                count++
                return this
            }
            val buckets = if (f.isException) allowBuckets else blockBuckets
            val noToken = if (f.isException) allowNoToken else blockNoToken
            val token = f.token
            if (token != null) buckets.getOrPut(token) { ArrayList() }.add(f)
            else noToken.add(f)
            count++
            return this
        }

        fun build(): FilterEngine {
            return FilterEngine(
                blockHosts, allowHosts, blockBuckets, blockNoToken,
                allowBuckets, allowNoToken, count,
            )
        }
    }
}
