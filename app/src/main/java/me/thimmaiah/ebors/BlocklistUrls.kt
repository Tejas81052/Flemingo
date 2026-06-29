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

/**
 * Pure URL helpers for [BlocklistUpdater]. Kept in its own object so the
 * unit-test JVM doesn't trip [BlocklistUpdater]'s class init — which
 * builds a main-thread `Handler(Looper.getMainLooper())` that NPEs on a
 * bare JVM where Looper.prepareMainLooper hasn't run.
 */
internal object BlocklistUrls {

    /**
     * Build the detached-signature URL from the payload URL by appending
     * `.sig` to the path segment only. A naive `"$url.sig"` concatenation
     * breaks when the operator's URL carries a query string or fragment
     * (e.g. `?token=abc` would yield `?token=abc.sig`, which the server
     * routes nowhere). Returns null if the URL is so malformed we can't
     * even find its path.
     */
    fun signatureUrlFor(url: String): String? {
        val schemeEnd = url.indexOf("://").takeIf { it >= 0 } ?: return null
        val authStart = schemeEnd + 3
        // Locate the path/query/fragment boundary.
        var pathBoundary = url.length
        var sawPathSlash = false
        for (i in authStart until url.length) {
            val c = url[i]
            if (c == '?' || c == '#') {
                pathBoundary = i
                break
            }
            if (c == '/') {
                sawPathSlash = true
            }
        }
        // A URL with no path at all (e.g. `https://host` or
        // `https://host?token=…`) can't host a `.sig` cleanly — appending
        // would change the host. Refuse rather than produce nonsense.
        if (!sawPathSlash) return null
        return url.substring(0, pathBoundary) + ".sig" + url.substring(pathBoundary)
    }
}
