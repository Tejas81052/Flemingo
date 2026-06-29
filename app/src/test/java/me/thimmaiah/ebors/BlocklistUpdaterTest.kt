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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the pure URL helpers in [BlocklistUrls]. The end-to-end
 * update flow (download, verify, install) needs a network and a
 * filesystem, so it's exercised by manual operator testing — what we can
 * assert here is that the URL composition for the detached signature
 * never produces a broken URL, regardless of what query string or
 * fragment the operator's update URL carries.
 */
class BlocklistUpdaterTest {

    @Test fun `appends sig to the path of a simple URL`() {
        assertEquals(
            "https://example.org/lists/blocklist.json.sig",
            BlocklistUrls.signatureUrlFor("https://example.org/lists/blocklist.json"),
        )
    }

    @Test fun `appends sig before the query string`() {
        // A naive string concatenation would yield
        //   https://example.org/blocklist.json?token=abc.sig
        // which routes nowhere on most servers.
        assertEquals(
            "https://example.org/blocklist.json.sig?token=abc",
            BlocklistUrls.signatureUrlFor("https://example.org/blocklist.json?token=abc"),
        )
    }

    @Test fun `appends sig before the fragment`() {
        assertEquals(
            "https://example.org/blocklist.json.sig#frag",
            BlocklistUrls.signatureUrlFor("https://example.org/blocklist.json#frag"),
        )
    }

    @Test fun `returns null for URL with no scheme separator`() {
        assertNull(BlocklistUrls.signatureUrlFor("not a url"))
    }

    @Test fun `returns null for URL with no path`() {
        // "https://host" — the .sig has nowhere to go. Better to fail
        // closed than to produce "https://host.sig" (different host).
        assertNull(BlocklistUrls.signatureUrlFor("https://host"))
        assertNull(BlocklistUrls.signatureUrlFor("https://host?token=x"))
    }
}
