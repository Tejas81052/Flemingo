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
import java.util.Locale

/**
 * Pure (no Android dependencies) implementation of the user-blocklist
 * input normaliser.
 *
 * Lives in its own object so the test module can exercise the table of
 * inputs without forcing [BlockedSitesRepository]'s class init to run
 * (it builds a main-thread `Handler` at class-load time, which NPEs in
 * JVM tests where `Looper.getMainLooper()` is null).
 *
 * Accepts pasted full URLs ("https://www.YouTube.com/feed/foo"),
 * bare hosts ("youtube.com"), and host-with-path ("youtube.com/foo");
 * returns null for anything that can't reasonably be turned into a
 * domain (no dot, whitespace, etc.). The result is lower-cased and
 * IDN-encoded to its ASCII form so the [BrowserBlocker] suffix match
 * compares apples-to-apples against URL hosts.
 */
internal object BlockedSitesNormalizer {

    fun normalize(rawValue: String?): String? {
        if (rawValue.isNullOrBlank()) return null
        var value = rawValue.trim()
        if (value.isEmpty()) return null

        // Pasted full URL — pull out the host portion via the shared
        // [UrlInputUtils] extractor. We use the manual extractor rather
        // than [android.net.Uri.parse] both because Uri isn't available
        // in unit tests and because the extractor copes with non-ASCII
        // hosts that Java's URI rejects.
        if (value.contains("://")) {
            value = UrlInputUtils.extractHost(value) ?: return null
        } else {
            // Bare host — strip a trailing path / query / fragment that
            // a user might have left in by accident.
            value = value.substringBefore('/').substringBefore('?').substringBefore('#')
        }

        value = value.removePrefix("www.")
        if (value.isBlank()) return null
        if (' ' in value) return null
        // Must look like a domain — at least one dot. Bare single-label
        // tokens like "youtube" are rejected to keep typos out of the
        // list (where they'd be silently no-op against any real URL).
        if ('.' !in value) return null

        // Broad catch: IDN.toASCII throws IllegalArgumentException for
        // most malformed inputs, but malformed Punycode can surface as
        // other RuntimeException subclasses. Falling back to the
        // lower-cased original is the safe default — at worst the
        // entry survives in an unusual but human-readable form.
        return try {
            IDN.toASCII(value).lowercase(Locale.US)
        } catch (_: Exception) {
            value.lowercase(Locale.US)
        }
    }
}
