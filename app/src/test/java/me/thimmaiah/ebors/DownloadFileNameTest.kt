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
import org.junit.Test

/**
 * Tests for [DownloadFileNames.sanitize]. The function is the
 * last line of defence against path-traversal in
 * `Content-Disposition: attachment; filename=...` — a malicious server
 * could otherwise pick our temp file location.
 */
class DownloadFileNameTest {

    @Test fun `plain filename is returned as is`() {
        assertEquals("photo.jpg", DownloadFileNames.sanitize("photo.jpg"))
    }

    @Test fun `forward slashes are replaced`() {
        val out = DownloadFileNames.sanitize("subdir/photo.jpg")
        assertFalse("Slashes must not survive sanitisation", out.contains('/'))
    }

    @Test fun `backslashes are replaced`() {
        val out = DownloadFileNames.sanitize("subdir\\photo.jpg")
        assertFalse(out.contains('\\'))
    }

    @Test fun `spaces are replaced`() {
        val out = DownloadFileNames.sanitize("my photo.jpg")
        assertFalse(out.contains(' '))
    }

    @Test fun `path-traversal markers are flattened`() {
        // The dangerous "../../etc/passwd" case. After sanitisation no
        // slashes survive and parent-dir markers are collapsed to a
        // single dot.
        val out = DownloadFileNames.sanitize("../../etc/passwd")
        assertFalse(out.contains('/'))
        assertFalse(out.contains(".."))
    }

    @Test fun `control characters are stripped`() {
        val out = DownloadFileNames.sanitize("filename.txt")
        assertEquals("filename.txt", out)
    }

    @Test fun `leading and trailing dots and spaces are trimmed`() {
        val out = DownloadFileNames.sanitize(" . hidden file.txt . ")
        // Whatever the trimmer leaves it with, leading/trailing dot or
        // space is gone — that's what Windows shells and zip tools
        // expect.
        assertFalse(out.startsWith('.'))
        assertFalse(out.startsWith(' '))
        assertFalse(out.endsWith('.'))
        assertFalse(out.endsWith(' '))
    }

    @Test fun `empty input falls back to download`() {
        assertEquals("download", DownloadFileNames.sanitize(""))
        assertEquals("download", DownloadFileNames.sanitize("..."))
        assertEquals("download", DownloadFileNames.sanitize("   "))
    }
}
