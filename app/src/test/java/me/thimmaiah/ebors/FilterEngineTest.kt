/*
 * Ebors - a privacy-focused Android browser
 * Copyright (C) 2026 Tejas Thimmaiah
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package me.thimmaiah.ebors

import org.junit.Assert.assertEquals
import org.junit.Test

class FilterEngineTest {

    private fun engine(vararg lines: String): FilterEngine =
        FilterEngine.Builder().addList(lines.joinToString("\n")).build()

    private fun hostOf(url: String): String =
        url.substringAfter("://", url)
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore(':')
            .lowercase()

    private fun ctx(
        url: String,
        type: RequestType = RequestType.OTHER,
        source: String = "publisher.example",
        thirdParty: Boolean = true,
    ) = RequestContext(url, hostOf(url), type, source, thirdParty)

    private fun decide(engine: FilterEngine, url: String, type: RequestType = RequestType.OTHER,
                       source: String = "publisher.example", thirdParty: Boolean = true): Decision =
        engine.match(ctx(url, type, source, thirdParty))

    // ---- hostname fast-path ------------------------------------------------

    @Test fun plainHostFilterBlocksHostAndSubdomains() {
        val e = engine("||doubleclick.net^")
        assertEquals(Decision.BLOCK, decide(e, "https://doubleclick.net/ad"))
        assertEquals(Decision.BLOCK, decide(e, "https://stats.g.doubleclick.net/x.gif"))
        assertEquals(Decision.NONE, decide(e, "https://notdoubleclick.net/x"))
        assertEquals(Decision.NONE, decide(e, "https://doubleclick.net.evil.com/x"))
    }

    @Test fun hostsFileFormatIsTreatedAsHostFilter() {
        val e = engine("0.0.0.0 ads.tracker.com", "127.0.0.1 evil.example # comment")
        assertEquals(Decision.BLOCK, decide(e, "https://ads.tracker.com/x"))
        assertEquals(Decision.BLOCK, decide(e, "https://evil.example/beacon"))
    }

    @Test fun benignRequestIsNotBlocked() {
        val e = engine("||doubleclick.net^", "||ads.example.com^")
        assertEquals(Decision.NONE, decide(e, "https://en.wikipedia.org/wiki/Main_Page"))
    }

    // ---- pattern matching --------------------------------------------------

    @Test fun pathAnchoredPatternBlocks() {
        val e = engine("||example.com/ads/*")
        assertEquals(Decision.BLOCK, decide(e, "https://example.com/ads/banner.png"))
        assertEquals(Decision.NONE, decide(e, "https://example.com/news/story"))
    }

    @Test fun separatorMatchesEndAndDelimiter() {
        val e = engine("||example.com^")
        // ^ at end matches a path slash, a port colon, and true end-of-URL.
        assertEquals(Decision.BLOCK, decide(e, "https://example.com/"))
        assertEquals(Decision.BLOCK, decide(e, "https://example.com:8443/x"))
        assertEquals(Decision.BLOCK, decide(e, "https://example.com"))
    }

    @Test fun substringFilterMatchesAnywhere() {
        val e = engine("/-advertisement-")
        assertEquals(Decision.BLOCK, decide(e, "https://cdn.site.com/x/-advertisement-/300x250.js"))
        assertEquals(Decision.NONE, decide(e, "https://cdn.site.com/x/content/300x250.js"))
    }

    @Test fun trailingAnchorRequiresEndOfUrl() {
        val e = engine("/banner.gif|")
        assertEquals(Decision.BLOCK, decide(e, "https://site.com/banner.gif"))
        assertEquals(Decision.NONE, decide(e, "https://site.com/banner.gif?cb=1"))
    }

    // ---- exceptions --------------------------------------------------------

    @Test fun exceptionOverridesBlock() {
        val e = engine("||ads.example.com^", "@@||ads.example.com/allowed^")
        assertEquals(Decision.ALLOW, decide(e, "https://ads.example.com/allowed/pixel.gif"))
        assertEquals(Decision.BLOCK, decide(e, "https://ads.example.com/other/pixel.gif"))
    }

    @Test fun importantBlockBeatsException() {
        val e = engine("||example.com/ads^\$important", "@@||example.com^")
        assertEquals(Decision.BLOCK, decide(e, "https://example.com/ads"))
    }

    // ---- options -----------------------------------------------------------

    @Test fun typeOptionConstrainsMatch() {
        val e = engine("||example.com/x^\$script")
        assertEquals(Decision.BLOCK, decide(e, "https://example.com/x", type = RequestType.SCRIPT))
        assertEquals(Decision.NONE, decide(e, "https://example.com/x", type = RequestType.IMAGE))
    }

    @Test fun thirdPartyOptionConstrainsMatch() {
        val e = engine("||tracker.com^\$third-party")
        assertEquals(Decision.BLOCK, decide(e, "https://tracker.com/t", thirdParty = true))
        assertEquals(Decision.NONE, decide(e, "https://tracker.com/t", thirdParty = false))
    }

    @Test fun domainOptionConstrainsBySource() {
        val e = engine("/ads.js\$domain=publisher.example")
        assertEquals(Decision.BLOCK, decide(e, "https://cdn.com/ads.js", source = "publisher.example"))
        assertEquals(Decision.BLOCK, decide(e, "https://cdn.com/ads.js", source = "sub.publisher.example"))
        assertEquals(Decision.NONE, decide(e, "https://cdn.com/ads.js", source = "other.example"))
    }

    @Test fun negatedDomainOptionExcludesSource() {
        val e = engine("||widget.com/x^\$domain=~trusted.example")
        assertEquals(Decision.BLOCK, decide(e, "https://widget.com/x", source = "random.example"))
        assertEquals(Decision.NONE, decide(e, "https://widget.com/x", source = "trusted.example"))
    }

    @Test fun entityDomainScopedFilterStaysScoped() {
        // Regression: a $domain= built only from entity wildcards (`site.*`)
        // must stay scoped to those sites, not leak into a global filter.
        val e = engine("*.js|\$script,1p,domain=isohunt.*|coolsite.*")
        assertEquals(Decision.BLOCK, decide(e, "https://isohunt.to/app.js",
            type = RequestType.SCRIPT, source = "isohunt.to", thirdParty = false))
        assertEquals(Decision.BLOCK, decide(e, "https://www.coolsite.org/bundle.js",
            type = RequestType.SCRIPT, source = "www.coolsite.org", thirdParty = false))
        // ...and never blocks first-party scripts on unrelated sites.
        assertEquals(Decision.NONE, decide(e, "https://www.theverge.com/_next/static/chunks/x.js",
            type = RequestType.SCRIPT, source = "www.theverge.com", thirdParty = false))
    }

    @Test fun unsupportedModifierFiltersAreSkippedNotMistreated() {
        // $removeparam / $redirect change behaviour; treating them as plain
        // blocks would over-block. They must be skipped entirely.
        val e = engine(
            "||example.com/track^\$removeparam=fbclid",
            "||example.com/img.png^\$redirect=1x1.gif",
        )
        assertEquals(Decision.NONE, decide(e, "https://example.com/track?fbclid=1"))
        assertEquals(Decision.NONE, decide(e, "https://example.com/img.png"))
    }

    // ---- top-level document semantics (regression: links must keep working) ----

    @Test fun genericFiltersDoNotBlockTopLevelNavigation() {
        val e = engine("/ads^", "||tracker.com^", "||evil.example^\$third-party")
        // As subresources these block as expected...
        assertEquals(Decision.BLOCK, decide(e, "https://x.com/ads", type = RequestType.IMAGE))
        assertEquals(Decision.BLOCK, decide(e, "https://tracker.com/beacon", type = RequestType.SCRIPT))
        // ...but a main-frame navigation to the same URLs must not (no $document).
        assertEquals(Decision.NONE, decide(e, "https://x.com/ads", type = RequestType.DOCUMENT))
        assertEquals(Decision.NONE, decide(e, "https://tracker.com/x", type = RequestType.DOCUMENT))
        assertEquals(Decision.NONE, decide(e, "https://evil.example/", type = RequestType.DOCUMENT))
    }

    @Test fun documentFiltersDoBlockTopLevelNavigation() {
        val e = engine("||malware.example^\$document")
        assertEquals(Decision.BLOCK, decide(e, "https://malware.example/landing", type = RequestType.DOCUMENT))
        // ...and still nothing for an unrelated navigation.
        assertEquals(Decision.NONE, decide(e, "https://safe.example/", type = RequestType.DOCUMENT))
    }

    @Test fun commentsAndCosmeticRulesAreIgnoredByNetworkEngine() {
        val e = engine(
            "! this is a comment",
            "[Adblock Plus 2.0]",
            "example.com##.ad-banner",
            "##.generic-ad",
        )
        assertEquals(0, e.filterCount)
    }
}
