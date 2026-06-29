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
 * Drives [BlockedSitesNormalizer.normalize] through every shape of
 * user input the "Add blocked sites" dialog might see in the wild —
 * bare hosts, URLs with paths, case variations, IDN, and the obvious
 * malformed cases that the input box can't easily defend against.
 */
class BlockedSitesNormalizationTest {

    @Test fun `bare lowercase host is returned as is`() {
        assertEquals("example.com", BlockedSitesNormalizer.normalize("example.com"))
    }

    @Test fun `host is lowercased`() {
        assertEquals("example.com", BlockedSitesNormalizer.normalize("Example.COM"))
    }

    @Test fun `www prefix is stripped`() {
        assertEquals("example.com", BlockedSitesNormalizer.normalize("www.example.com"))
    }

    @Test fun `full URL is reduced to host`() {
        assertEquals("example.com", BlockedSitesNormalizer.normalize("https://example.com/foo/bar"))
        assertEquals("example.com", BlockedSitesNormalizer.normalize("http://www.example.com/?x=1"))
    }

    @Test fun `bare host with path strips path`() {
        assertEquals("example.com", BlockedSitesNormalizer.normalize("example.com/path"))
        assertEquals("example.com", BlockedSitesNormalizer.normalize("example.com?q=1"))
        assertEquals("example.com", BlockedSitesNormalizer.normalize("example.com#frag"))
    }

    @Test fun `surrounding whitespace is trimmed`() {
        assertEquals("example.com", BlockedSitesNormalizer.normalize("   example.com   "))
    }

    @Test fun `null and blank inputs return null`() {
        assertNull(BlockedSitesNormalizer.normalize(null))
        assertNull(BlockedSitesNormalizer.normalize(""))
        assertNull(BlockedSitesNormalizer.normalize("    "))
    }

    @Test fun `host without a dot is rejected`() {
        // No TLD-shaped thing → can't be a real domain. Rejecting keeps
        // typos like "youtube" out of the list (where they'd be a no-op
        // and silently confuse the user).
        assertNull(BlockedSitesNormalizer.normalize("youtube"))
    }

    @Test fun `host containing spaces is rejected`() {
        assertNull(BlockedSitesNormalizer.normalize("not a host.com"))
    }

    @Test fun `IDN gets punycoded`() {
        // bücher.example → xn--bcher-kva.example. Normalising to ASCII
        // means the blocker can suffix-match consistently against a
        // URL's punycoded host.
        val out = BlockedSitesNormalizer.normalize("bücher.example")
        assertEquals("xn--bcher-kva.example", out)
    }

    @Test fun `subdomain is preserved when user supplied it`() {
        // The block matches on suffix anyway, so this is harmless — but
        // we shouldn't trim sub.example.com down to example.com because
        // the user may want only the subdomain blocked.
        assertEquals("ads.example.com", BlockedSitesNormalizer.normalize("ads.example.com"))
    }
}
