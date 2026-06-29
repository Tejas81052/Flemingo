/*
 * Copyright 2026 Tejas Thimmaiah
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package me.thimmaiah.ebors

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BrowserBlockerTest {

    @Before
    fun setUp() {
        // The block-list data now lives in assets/blocklist.json, loaded
        // at runtime via AssetManager. Unit tests have no AssetManager,
        // so we feed the same file through the test seam. The unit-test
        // task runs with the module dir (app/) as the working directory,
        // so this relative path resolves to the real bundled asset.
        BrowserBlocker.loadBlockListForTest(
            java.io.File("src/main/assets/blocklist.json").readText(),
        )
        BrowserBlocker.adBlockEnabled = true
        BrowserBlocker.siteBlockEnabled = true
        BrowserBlocker.blockedDomains = emptySet()
    }

    @After
    fun tearDown() {
        BrowserBlocker.blockedDomains = emptySet()
    }

    @Test
    fun userBlockedSiteUrlsAreMatchedOnRootAndSubdomains() {
        BrowserBlocker.blockedDomains = setOf("asurascans.com")

        val rootMatch = BrowserBlocker.findMatch("https://asurascans.com/")
        val subdomainMatch = BrowserBlocker.findMatch("https://www.asurascans.com/chapter-1")

        assertNotNull(rootMatch)
        assertEquals(BrowserBlocker.BlockingKind.BLOCKED_SITE, rootMatch?.kind)
        assertEquals("asurascans.com", rootMatch?.host)

        assertNotNull(subdomainMatch)
        assertEquals(BrowserBlocker.BlockingKind.BLOCKED_SITE, subdomainMatch?.kind)
        assertEquals("www.asurascans.com", subdomainMatch?.host)
    }

    @Test
    fun userBlockedSitesTakePriorityOverAdBlockEssentialAllowList() {
        // The user explicitly added google.com — that should win even though
        // www.google.com is on the built-in essential allow list for captchas.
        BrowserBlocker.blockedDomains = setOf("google.com")

        val match = BrowserBlocker.findMatch("https://www.google.com/search?q=foo")

        assertNotNull(match)
        assertEquals(BrowserBlocker.BlockingKind.BLOCKED_SITE, match?.kind)
    }

    @Test
    fun adAndTrackerRequestsAreDetected() {
        val match = BrowserBlocker.findMatch("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js")

        assertNotNull(match)
        assertEquals(BrowserBlocker.BlockingKind.AD_OR_TRACKER, match?.kind)
    }

    @Test
    fun pathSignalsAreDetectedOnNeutralHosts() {
        // The host isn't on the block list but the path screams "ads".
        val match = BrowserBlocker.findMatch("https://news.example.com/wp-content/plugins/advertising/banner.js")

        assertNotNull(match)
        assertEquals(BrowserBlocker.BlockingKind.AD_OR_TRACKER, match?.kind)
    }

    @Test
    fun youtubeStartupLogEventIsAllowedWithoutOpeningGenericLogEventTrackers() {
        assertNull(
            BrowserBlocker.findMatch(
                "https://m.youtube.com/youtubei/v1/log_event?alt=json",
                isMainFrame = false,
            ),
        )
        assertNull(
            BrowserBlocker.findMatch(
                "https://www.youtube.com/youtubei/v1/log_event?alt=json",
                isMainFrame = false,
            ),
        )

        val genericTracker = BrowserBlocker.findMatch(
            "https://news.example.com/log_event?ad_unit=hero",
            isMainFrame = false,
        )
        assertNotNull(genericTracker)
        assertEquals(BrowserBlocker.BlockingKind.AD_OR_TRACKER, genericTracker?.kind)

        val youtubeAdBeacon = BrowserBlocker.findMatch(
            "https://www.youtube.com/pagead/viewthroughconversion/123/?label=followon_view",
            isMainFrame = false,
        )
        assertNotNull(youtubeAdBeacon)
        assertEquals(BrowserBlocker.BlockingKind.AD_OR_TRACKER, youtubeAdBeacon?.kind)
    }

    @Test
    fun essentialAllowListLetsCaptchaThrough() {
        // recaptcha.net is on the allow list — even though `/recaptcha/` could
        // look ad-ish, this must never be blocked or sign-in breaks.
        assertNull(BrowserBlocker.findMatch("https://www.recaptcha.net/recaptcha/api.js"))
    }

    @Test
    fun regularContentIsAllowed() {
        assertNull(BrowserBlocker.findMatch("https://developer.android.com/"))
    }

    @Test
    fun adBlockMasterSwitchDisablesAdMatchesButLeavesSiteBlocksAlone() {
        BrowserBlocker.blockedDomains = setOf("asurascans.com")
        BrowserBlocker.adBlockEnabled = false

        assertNull(BrowserBlocker.findMatch("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"))
        assertNotNull(BrowserBlocker.findMatch("https://www.asurascans.com/chapter-1"))
    }

    @Test
    fun siteBlockMasterSwitchDisablesUserListButLeavesAdBlockAlone() {
        BrowserBlocker.blockedDomains = setOf("asurascans.com")
        BrowserBlocker.siteBlockEnabled = false

        assertNull(BrowserBlocker.findMatch("https://www.asurascans.com/chapter-1"))
        assertNotNull(BrowserBlocker.findMatch("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"))
    }

    @Test
    fun adblockHostIsDetectedAcrossExpandedList() {
        // Hosts pulled from the expanded EasyList/EasyPrivacy tier — these
        // must all resolve to ad/tracker matches.
        val samples = listOf(
            "https://stats.g.doubleclick.net/r/collect",
            "https://www.googletagmanager.com/gtm.js",
            "https://cdn.taboola.com/libtrc/foo.js",
            "https://b.scorecardresearch.com/beacon.js",
            "https://script.hotjar.com/modules.js",
            "https://connect.facebook.net/en_US/fbevents.js",
            // Facebook pixel — host is facebook.com (not blocked at host
            // level) but the URL query is the unmistakable pixel pattern.
            "https://www.facebook.com/tr?id=12345&ev=PageView",
        )

        for (url in samples) {
            val match = BrowserBlocker.findMatch(url)
            assertNotNull("Expected $url to be blocked", match)
            assertEquals(
                "Expected ad/tracker kind for $url",
                BrowserBlocker.BlockingKind.AD_OR_TRACKER,
                match?.kind,
            )
        }
    }

    @Test
    fun internationalDomainsAreNormalisedBeforeMatching() {
        // bücher.example is the Punycode case — `xn--bcher-kva.example`. We
        // shouldn't crash, and we shouldn't accidentally treat the raw
        // unicode label as different from the punycode one when the user
        // adds the unicode version to their block list.
        BrowserBlocker.blockedDomains = setOf("xn--bcher-kva.example")

        val match = BrowserBlocker.findMatch("https://shop.xn--bcher-kva.example/")
        assertNotNull(match)
        assertTrue(match?.host?.endsWith("xn--bcher-kva.example") == true)
    }

    @Test
    fun supportedSchemesIncludeStandardWebSchemes() {
        assertTrue(BrowserBlocker.isSupportedBrowserScheme("https://example.com/"))
        assertTrue(BrowserBlocker.isSupportedBrowserScheme("http://example.com/"))
        assertTrue(BrowserBlocker.isSupportedBrowserScheme("about:blank"))
        assertTrue(BrowserBlocker.isSupportedBrowserScheme("data:text/plain,hi"))
        // Custom schemes (tel:, intent:, market:) are NOT supported — they
        // hand off to system intents instead of being loaded in WebView.
        assertFalse(BrowserBlocker.isSupportedBrowserScheme("tel:+15551234"))
        assertFalse(BrowserBlocker.isSupportedBrowserScheme("intent://foo#Intent;scheme=https;end"))
    }
}
