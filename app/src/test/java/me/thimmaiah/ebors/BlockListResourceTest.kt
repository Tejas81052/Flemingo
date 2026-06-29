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

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Verifies the bundled `assets/blocklist.json` — the data that used to
 * be Kotlin string literals in [BrowserBlocker] — is present, parses
 * cleanly, and has the shape [BrowserBlocker] expects.
 *
 * The JVM unit-test task runs with the module directory (`app/`) as its
 * working directory, so the relative path below resolves to the real
 * bundled asset. The behavioural side (does a given host get blocked)
 * is covered by [BrowserBlockerTest].
 */
class BlockListResourceTest {

    private val assetFile = File("src/main/assets/blocklist.json")

    private fun loadJson(): JSONObject {
        assertTrue(
            "assets/blocklist.json must exist (looked at ${assetFile.absolutePath})",
            assetFile.isFile,
        )
        return JSONObject(assetFile.readText())
    }

    @Test fun `asset is present and is valid json`() {
        val root = loadJson()
        assertEquals(1, root.getInt("schemaVersion"))
    }

    @Test fun `ad and tracker domains flatten to a substantial set`() {
        val categorised = loadJson().getJSONObject("adAndTrackerDomains")
        val all = mutableSetOf<String>()
        for (key in categorised.keys()) {
            val arr = categorised.getJSONArray(key)
            for (i in 0 until arr.length()) all.add(arr.getString(i))
        }
        // The original hardcoded list was ~250 entries. Assert a floor
        // well below that so trimming the list later doesn't break the
        // test, but high enough to catch a truncated / empty file.
        assertTrue("expected 150+ ad/tracker domains, got ${all.size}", all.size >= 150)
        // Spot-check one entry per category so a dropped category array
        // is caught.
        assertTrue("doubleclick.net" in all)
        assertTrue("amazon-adsystem.com" in all)
        assertTrue("revcontent.com" in all)
        assertTrue("hotjar.com" in all)
        assertTrue("connect.facebook.net" in all)
        assertTrue("popads.net" in all)
        assertTrue("coinhive.com" in all)
        assertTrue("linksynergy.com" in all)
        assertTrue("adcolony.com" in all)
    }

    @Test fun `essential allow list has the four expected categories`() {
        val categorised = loadJson().getJSONObject("essentialAllowList")
        val keys = categorised.keys().asSequence().toSet()
        assertEquals(
            setOf("googleAccountInfra", "captchaFraudPrevention", "genericOauth", "payments"),
            keys,
        )
        val all = mutableSetOf<String>()
        for (key in keys) {
            val arr = categorised.getJSONArray(key)
            for (i in 0 until arr.length()) all.add(arr.getString(i))
        }
        assertTrue("accounts.google.com" in all)
        assertTrue("hcaptcha.com" in all)
        assertTrue("appleid.apple.com" in all)
        assertTrue("js.stripe.com" in all)
    }

    @Test fun `path signal arrays are non-empty`() {
        val root = loadJson()
        assertTrue(root.getJSONArray("adPathSignals").length() > 0)
        assertTrue(root.getJSONArray("subResourceOnlyPathSignals").length() > 0)
    }

    @Test fun `loadBlockListForTest wires the asset into findMatch`() {
        // End-to-end: parse the real bundled asset through the test seam,
        // then confirm a host that only exists in the JSON file (never
        // re-hardcoded in BrowserBlocker.kt) resolves to a block. This
        // proves parseBlockList + flattenCategorisedHosts ran correctly,
        // not just that the file is valid JSON.
        BrowserBlocker.loadBlockListForTest(assetFile.readText())
        BrowserBlocker.adBlockEnabled = true
        BrowserBlocker.blockedDomains = emptySet()
        val match = BrowserBlocker.findMatch("https://cdn.taboola.com/libtrc/x.js")
        assertTrue("taboola.com should be blocked via the bundled list", match != null)
        assertEquals(BrowserBlocker.BlockingKind.AD_OR_TRACKER, match!!.kind)
    }
}
