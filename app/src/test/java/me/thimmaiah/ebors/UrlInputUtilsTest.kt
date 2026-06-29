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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure URL helpers used by the address bar and the
 * WebViewClient. These functions are security-load-bearing — the
 * javascript-scheme strip, the HTTPS-upgrade decision, and the
 * mixed-script punycode pass each have an audit finding tied to them
 * (V7 / V8 / V9) — so the table below is deliberately broad.
 */
class UrlInputUtilsTest {

    // ---------- stripJavascriptScheme ------------------------------------

    @Test fun `strips a single javascript scheme`() {
        assertEquals("alert(1)", UrlInputUtils.stripJavascriptScheme("javascript:alert(1)"))
    }

    @Test fun `strips javascript scheme case insensitively`() {
        assertEquals("alert(1)", UrlInputUtils.stripJavascriptScheme("JaVaScRiPt:alert(1)"))
    }

    @Test fun `strips stacked javascript schemes`() {
        assertEquals(
            "alert(1)",
            UrlInputUtils.stripJavascriptScheme("javascript:javascript:javascript:alert(1)"),
        )
    }

    @Test fun `strips leading whitespace between scheme prefixes`() {
        assertEquals(
            "alert(1)",
            UrlInputUtils.stripJavascriptScheme("   javascript:   javascript:alert(1)"),
        )
    }

    @Test fun `leaves non-javascript inputs alone`() {
        assertEquals("https://example.com", UrlInputUtils.stripJavascriptScheme("https://example.com"))
        assertEquals("example.com", UrlInputUtils.stripJavascriptScheme("example.com"))
    }

    // ---------- looksLikeUrl ----------------------------------------------

    @Test fun `bare hosts with a dot look like URLs`() {
        assertTrue(UrlInputUtils.looksLikeUrl("example.com"))
        assertTrue(UrlInputUtils.looksLikeUrl("www.example.com"))
        assertTrue(UrlInputUtils.looksLikeUrl("a.co"))
    }

    @Test fun `localhost is treated as a URL`() {
        assertTrue(UrlInputUtils.looksLikeUrl("localhost"))
    }

    @Test fun `data and about schemes are URLs`() {
        assertTrue(UrlInputUtils.looksLikeUrl("about:blank"))
        assertTrue(UrlInputUtils.looksLikeUrl("data:text/plain,hi"))
    }

    @Test fun `multi-word queries are not URLs`() {
        assertFalse(UrlInputUtils.looksLikeUrl("how to install kotlin"))
        assertFalse(UrlInputUtils.looksLikeUrl("kotlin tutorial"))
    }

    @Test fun `single tokens without a dot are not URLs`() {
        assertFalse(UrlInputUtils.looksLikeUrl("kotlin"))
        assertFalse(UrlInputUtils.looksLikeUrl(""))
    }

    // ---------- enforceSecureScheme ---------------------------------------

    @Test fun `bare host gets https prepended`() {
        assertEquals("https://example.com", UrlInputUtils.enforceSecureScheme("example.com"))
    }

    @Test fun `existing scheme is preserved`() {
        assertEquals("http://example.com", UrlInputUtils.enforceSecureScheme("http://example.com"))
        assertEquals("https://example.com", UrlInputUtils.enforceSecureScheme("https://example.com"))
        assertEquals("ftp://example.com", UrlInputUtils.enforceSecureScheme("ftp://example.com"))
    }

    // ---------- shouldUpgradeToHttps + isPrivateOrLocalHost ---------------

    @Test fun `public http URLs are upgradable`() {
        assertTrue(UrlInputUtils.shouldUpgradeToHttps("http://example.com"))
        assertTrue(UrlInputUtils.shouldUpgradeToHttps("http://example.com/path"))
        assertTrue(UrlInputUtils.shouldUpgradeToHttps("http://sub.example.com:8080/"))
    }

    @Test fun `https URLs are not upgraded`() {
        assertFalse(UrlInputUtils.shouldUpgradeToHttps("https://example.com"))
    }

    @Test fun `loopback and private addresses are skipped`() {
        // Loopback
        assertFalse(UrlInputUtils.shouldUpgradeToHttps("http://localhost"))
        assertFalse(UrlInputUtils.shouldUpgradeToHttps("http://localhost:3000/api"))
        assertFalse(UrlInputUtils.shouldUpgradeToHttps("http://127.0.0.1"))
        assertFalse(UrlInputUtils.shouldUpgradeToHttps("http://127.0.0.1:8080"))
        // RFC1918
        assertFalse(UrlInputUtils.shouldUpgradeToHttps("http://10.0.0.5"))
        assertFalse(UrlInputUtils.shouldUpgradeToHttps("http://10.255.255.255"))
        assertFalse(UrlInputUtils.shouldUpgradeToHttps("http://172.16.0.1"))
        assertFalse(UrlInputUtils.shouldUpgradeToHttps("http://172.31.255.254"))
        assertFalse(UrlInputUtils.shouldUpgradeToHttps("http://192.168.1.1"))
        // link-local
        assertFalse(UrlInputUtils.shouldUpgradeToHttps("http://169.254.1.1"))
        // .local mDNS
        assertFalse(UrlInputUtils.shouldUpgradeToHttps("http://printer.local"))
    }

    @Test fun `172_15 is public not private`() {
        // 172.16/12 starts at 172.16 — 172.15.* is public.
        assertTrue(UrlInputUtils.shouldUpgradeToHttps("http://172.15.0.1"))
        // 172.32.* is also public (above the /12 boundary).
        assertTrue(UrlInputUtils.shouldUpgradeToHttps("http://172.32.0.1"))
    }

    @Test fun `IPv6 loopback and ULA and link-local are skipped`() {
        assertFalse(UrlInputUtils.shouldUpgradeToHttps("http://[::1]/"))
        assertFalse(UrlInputUtils.shouldUpgradeToHttps("http://[fc00::1]/"))
        assertFalse(UrlInputUtils.shouldUpgradeToHttps("http://[fe80::1]/"))
    }

    @Test fun `upgradeToHttps rewrites scheme only`() {
        assertEquals("https://example.com/foo", UrlInputUtils.upgradeToHttps("http://example.com/foo"))
        // Case insensitive on the scheme; the path is preserved verbatim.
        assertEquals("https://example.com/Foo", UrlInputUtils.upgradeToHttps("HTTP://example.com/Foo"))
        // Only the first http:// is replaced, so an http://-in-the-path
        // query parameter stays put.
        assertEquals(
            "https://example.com/redirect?to=http://other.com",
            UrlInputUtils.upgradeToHttps("http://example.com/redirect?to=http://other.com"),
        )
    }

    // ---------- prettifyUrl / forcePunycodeIfMixedScript ------------------

    @Test fun `ASCII URLs are returned verbatim`() {
        assertEquals(
            "https://www.google.com/foo",
            UrlInputUtils.prettifyUrl("https://www.google.com/foo"),
        )
    }

    @Test fun `pure-script IDN hosts stay native`() {
        // All-Cyrillic label — legitimate Russian domain, not a spoof.
        val russian = "https://яндекс.рф/"
        assertEquals(russian, UrlInputUtils.prettifyUrl(russian))
    }

    @Test fun `mixed Latin-Cyrillic label gets punycoded`() {
        // "аpple.com" with a Cyrillic а in front of Latin pple — classic
        // homoglyph spoof. We expect the rendered host to be in xn--
        // form so the user can tell.
        val spoof = "https://аpple.com/"
        val rendered = UrlInputUtils.prettifyUrl(spoof)
        assertTrue(
            "expected punycode rendering, got $rendered",
            rendered.contains("xn--"),
        )
    }

    @Test fun `URL with non-host content matching the host string still works`() {
        // The host substring also appears in the path. We use the URI
        // rebuild path so we should replace only the host occurrence.
        val mixed = "https://аpple.com/?next=аpple.com"
        val rendered = UrlInputUtils.prettifyUrl(mixed)
        assertTrue(rendered.startsWith("https://xn--"))
        // The path is URI-encoded by URI.toASCIIString — that's fine.
        assertTrue(rendered.contains("?next="))
    }

    @Test fun `isMixedScriptLabel ignores digits and combining marks`() {
        // Digits are Common script; pure Latin + digits is not mixed.
        assertFalse(UrlInputUtils.isMixedScriptLabel("example42"))
        // Pure Cyrillic + digits is also single-script.
        assertFalse(UrlInputUtils.isMixedScriptLabel("привет42"))
    }

    // ---------- canonicalForCompare ---------------------------------------

    @Test fun `canonicalForCompare collapses trailing slash on non-root path`() {
        assertEquals(
            UrlInputUtils.canonicalForCompare("https://example.com/foo"),
            UrlInputUtils.canonicalForCompare("https://example.com/foo/"),
        )
    }

    @Test fun `canonicalForCompare leaves the bare root slash in place`() {
        // The trailing-slash strip is deliberately scoped to non-root
        // paths (path.length > 1) — touching the root "/" would
        // canonicalise "https://example.com/" to "https://example.com",
        // which `java.net.URI` then refuses to round-trip. The contract
        // is "different inputs that mean the same page compare equal";
        // we don't try to fold root-slash vs no-slash, since they're
        // emitted in different forms by different sources.
        val withSlash = UrlInputUtils.canonicalForCompare("https://example.com/")
        assertTrue(
            "expected slash to survive on root path, got $withSlash",
            withSlash.endsWith("/"),
        )
    }

    @Test fun `canonicalForCompare drops fragment`() {
        assertEquals(
            UrlInputUtils.canonicalForCompare("https://example.com/foo"),
            UrlInputUtils.canonicalForCompare("https://example.com/foo#section"),
        )
    }

    @Test fun `canonicalForCompare lowercases scheme and host`() {
        assertEquals(
            UrlInputUtils.canonicalForCompare("https://example.com/Foo"),
            UrlInputUtils.canonicalForCompare("HTTPS://Example.COM/Foo"),
        )
    }

    @Test fun `canonicalForCompare preserves query`() {
        // Query is page-identifying for many sites (search, SPAs).
        val withQuery = UrlInputUtils.canonicalForCompare("https://example.com/foo?bar=1")
        val withoutQuery = UrlInputUtils.canonicalForCompare("https://example.com/foo")
        assertTrue(
            "expected different canonicalisations when only query differs",
            withQuery != withoutQuery,
        )
    }

    @Test fun `canonicalForCompare drops default port`() {
        assertEquals(
            UrlInputUtils.canonicalForCompare("https://example.com/foo"),
            UrlInputUtils.canonicalForCompare("https://example.com:443/foo"),
        )
        assertEquals(
            UrlInputUtils.canonicalForCompare("http://example.com/foo"),
            UrlInputUtils.canonicalForCompare("http://example.com:80/foo"),
        )
    }

    @Test fun `canonicalForCompare returns trimmed input on parse failure`() {
        // Whitespace-only is empty.
        assertEquals("", UrlInputUtils.canonicalForCompare("   "))
        // Non-URL input falls through trimmed.
        assertEquals("not a url", UrlInputUtils.canonicalForCompare("  not a url  "))
    }
}
