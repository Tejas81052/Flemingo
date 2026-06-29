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
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * Smoke test over the real bundled ad lists (EasyList + uBO ads). The unit-test
 * task runs with app/ as the working directory, so the asset path resolves to
 * the shipped files — this catches list-format regressions the synthetic
 * [FilterEngineTest] can't.
 */
class FilterListBundleTest {

    companion object {
        private lateinit var engine: FilterEngine

        @BeforeClass @JvmStatic fun loadBundle() {
            val builder = FilterEngine.Builder()
            File("src/main/assets/filters").listFiles { f -> f.extension == "txt" }!!
                .forEach { builder.addList(it.readText()) }
            engine = builder.build()
        }
    }

    private fun hostOf(url: String): String =
        url.substringAfter("://", url).substringBefore('/').substringBefore('?')
            .substringBefore(':').lowercase()

    private fun decide(url: String, type: RequestType = RequestType.OTHER,
                       source: String = "third-party.example", thirdParty: Boolean = true): Decision =
        engine.match(RequestContext(url, hostOf(url), type, source, thirdParty))

    @Test fun bundleParsesIntoLargeEngine() {
        // ~110k raw lines; a healthy chunk are network filters.
        assertTrue("only ${engine.filterCount} filters", engine.filterCount > 50_000)
    }

    @Test fun knownAdHostsAreBlocked() {
        assertEquals(Decision.BLOCK, decide("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"))
        assertEquals(Decision.BLOCK, decide("https://googleads.g.doubleclick.net/pagead/id"))
        assertEquals(Decision.BLOCK, decide("https://c.amazon-adsystem.com/aax2/apstag.js"))
        assertEquals(Decision.BLOCK, decide("https://ib.adnxs.com/ut/v3/prebid"))
    }

    @Test fun firstPartyAppScriptsAreNotBlocked() {
        // Regression: an entity-scoped `domain=site.*` filter once leaked
        // global and blocked every first-party .js on the web (broke pages).
        val urls = listOf(
            "https://www.theverge.com/_next/static/chunks/6226-40f643e4d90ee354.js",
            "https://www.theverge.com/_next/static/abc/_ssgManifest.js",
            "https://www.theverge.com/_next/static/chunks/9027-416dabbb71fe3774.js",
        )
        for (u in urls) {
            val host = hostOf(u)
            val d = engine.match(RequestContext(u, host, RequestType.SCRIPT, host, false))
            assertEquals("wrongly blocked $u", Decision.NONE, d)
        }
    }

    @Test fun contentRequestsAreNotBlocked() {
        assertEquals(Decision.NONE, decide(
            "https://en.wikipedia.org/wiki/Cat", RequestType.DOCUMENT, "en.wikipedia.org", false))
        assertEquals(Decision.NONE, decide(
            "https://example.org/index.html", RequestType.DOCUMENT, "example.org", false))
    }
}
