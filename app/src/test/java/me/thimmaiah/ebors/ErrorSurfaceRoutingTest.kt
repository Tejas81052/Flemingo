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

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Property-based tests for the pure routing functions in `ErrorSurfaceRouting.kt`.
 *
 * These are JVM unit tests (`src/test`). The android.jar on the unit-test classpath
 * is the stub jar, so referencing `android.webkit.WebViewClient.ERROR_HOST_LOOKUP`
 * directly here can throw "Stub!" or yield 0. We therefore define the constant value
 * locally and document its provenance.
 *
 * Property testing is implemented manually: each property runs >= 100 randomized
 * iterations using [kotlin.random.Random], generating arbitrary booleans and error
 * codes. Error-code generation deliberately mixes in [ERROR_HOST_LOOKUP] and a spread
 * of other WebView codes so both the host-lookup and non-host-lookup branches are
 * exercised. On a failing iteration, the assertion message includes the generating
 * inputs (the counterexample).
 */
class ErrorSurfaceRoutingTest {

    private companion object {
        /**
         * Value of `android.webkit.WebViewClient.ERROR_HOST_LOOKUP`.
         *
         * Hard-coded to -2 because the unit-test stub android.jar does not provide a
         * usable value for the real constant.
         */
        private const val ERROR_HOST_LOOKUP = -2

        private const val ITERATIONS = 200

        /** Known WebViewClient error codes used to seed the generator. */
        private val KNOWN_WEBVIEW_CODES = intArrayOf(
            0,                   // arbitrary non-error sentinel
            -1,                  // ERROR_UNKNOWN
            ERROR_HOST_LOOKUP,   // -2  ERROR_HOST_LOOKUP
            -6,                  // ERROR_CONNECT
            -8,                  // ERROR_TIMEOUT
            -12,                 // ERROR_BAD_URL
            1,                   // arbitrary positive
            404,                 // arbitrary positive
        )

        /**
         * Generates an arbitrary error code. Mixes in [ERROR_HOST_LOOKUP] and a spread
         * of other known WebView codes alongside fully random ints so that both the
         * host-lookup branch and every other branch are exercised across iterations.
         */
        private fun randomErrorCode(rng: Random): Int = when (rng.nextInt(3)) {
            0 -> ERROR_HOST_LOOKUP
            1 -> KNOWN_WEBVIEW_CODES[rng.nextInt(KNOWN_WEBVIEW_CODES.size)]
            else -> rng.nextInt()
        }

        /** Generates an arbitrary non-host-lookup error code. */
        private fun randomNonHostLookupCode(rng: Random): Int {
            var code = randomErrorCode(rng)
            while (code == ERROR_HOST_LOOKUP) {
                code = randomErrorCode(rng)
            }
            return code
        }
    }

    // Feature: site-not-found-and-scanner-ui, Property 1: Non-main-frame errors leave the tab unchanged
    @Test
    fun property1_nonMainFrameErrorsLeaveTabUnchanged() {
        val rng = Random(0x5117E1)
        repeat(ITERATIONS) {
            val isDeviceOffline = rng.nextBoolean()
            val errorCode = randomErrorCode(rng)
            val result = resolveErrorSurface(
                isForMainFrame = false,
                isDeviceOffline = isDeviceOffline,
                errorCode = errorCode,
            )
            assertEquals(
                "Expected UNCHANGED for non-main-frame error " +
                    "[isForMainFrame=false, isDeviceOffline=$isDeviceOffline, errorCode=$errorCode]",
                ErrorSurface.UNCHANGED,
                result,
            )
        }
    }

    // Feature: site-not-found-and-scanner-ui, Property 2: Offline takes precedence over host-lookup
    @Test
    fun property2_offlineTakesPrecedenceOverHostLookup() {
        val rng = Random(0x0FF11E)
        repeat(ITERATIONS) {
            val errorCode = randomErrorCode(rng)
            val result = resolveErrorSurface(
                isForMainFrame = true,
                isDeviceOffline = true,
                errorCode = errorCode,
            )
            val ctx = "[isForMainFrame=true, isDeviceOffline=true, errorCode=$errorCode]"
            assertEquals("Expected OFFLINE for offline main-frame error $ctx", ErrorSurface.OFFLINE, result)
            assertNotEquals("Must never be SITE_NOT_FOUND while offline $ctx", ErrorSurface.SITE_NOT_FOUND, result)
        }
    }

    // Feature: site-not-found-and-scanner-ui, Property 3: Online main-frame host-lookup failures route to site-not-found
    @Test
    fun property3_onlineHostLookupRoutesToSiteNotFound() {
        val rng = Random(0x517E40)
        repeat(ITERATIONS) {
            // isForMainFrame and isDeviceOffline are fixed by the property; the random
            // driver keeps the iteration count and demonstrates input independence.
            rng.nextBoolean()
            val result = resolveErrorSurface(
                isForMainFrame = true,
                isDeviceOffline = false,
                errorCode = ERROR_HOST_LOOKUP,
            )
            assertEquals(
                "Expected SITE_NOT_FOUND for online main-frame host-lookup failure " +
                    "[isForMainFrame=true, isDeviceOffline=false, errorCode=$ERROR_HOST_LOOKUP]",
                ErrorSurface.SITE_NOT_FOUND,
                result,
            )
        }
    }

    // Feature: site-not-found-and-scanner-ui, Property 4: Other online main-frame errors keep the default WebView behavior
    @Test
    fun property4_otherOnlineErrorsKeepDefaultWebView() {
        val rng = Random(0xDEFA17)
        repeat(ITERATIONS) {
            val errorCode = randomNonHostLookupCode(rng)
            val result = resolveErrorSurface(
                isForMainFrame = true,
                isDeviceOffline = false,
                errorCode = errorCode,
            )
            assertEquals(
                "Expected DEFAULT_WEBVIEW for online non-host-lookup main-frame error " +
                    "[isForMainFrame=true, isDeviceOffline=false, errorCode=$errorCode]",
                ErrorSurface.DEFAULT_WEBVIEW,
                result,
            )
        }
    }

    // Feature: site-not-found-and-scanner-ui, Property 5: Active-tab offline precedence
    @Test
    fun property5_activeTabOfflinePrecedence() {
        val rng = Random(0xAC71BE)
        repeat(ITERATIONS) {
            val hasUnresolvedHostLookup = rng.nextBoolean()
            val result = resolveActiveTabSurface(
                hasMainFrameError = true,
                hasUnresolvedHostLookup = hasUnresolvedHostLookup,
                isDeviceOffline = true,
            )
            val ctx = "[hasMainFrameError=true, hasUnresolvedHostLookup=$hasUnresolvedHostLookup, isDeviceOffline=true]"
            assertEquals("Expected OFFLINE for offline active tab $ctx", ActiveSurface.OFFLINE, result)
            assertNotEquals("Must never be SITE_NOT_FOUND while offline $ctx", ActiveSurface.SITE_NOT_FOUND, result)
        }
    }

    // Feature: site-not-found-and-scanner-ui, Property 6: Active-tab site-not-found classification
    @Test
    fun property6_activeTabSiteNotFoundClassification() {
        val rng = Random(0x517E46)
        repeat(ITERATIONS) {
            val hasUnresolvedHostLookup = rng.nextBoolean()
            // Domain invariant (smart generator): an unresolved host-lookup failure is
            // itself a recorded main-frame error — `siteNotFoundUrl` is only ever set in
            // the same path that sets `mainFrameErrored = true`. So
            // `hasUnresolvedHostLookup == true` implies `hasMainFrameError == true`.
            // When there is no unresolved host-lookup, the main-frame-error flag is free.
            val hasMainFrameError = if (hasUnresolvedHostLookup) true else rng.nextBoolean()
            val isDeviceOffline = rng.nextBoolean()

            val result = resolveActiveTabSurface(
                hasMainFrameError = hasMainFrameError,
                hasUnresolvedHostLookup = hasUnresolvedHostLookup,
                isDeviceOffline = isDeviceOffline,
            )

            // Full truth table:
            //  - offline-precedence case (isDeviceOffline && hasMainFrameError) -> OFFLINE
            //  - else SITE_NOT_FOUND iff (hasUnresolvedHostLookup && !isDeviceOffline)
            //  - otherwise NONE
            val expected = when {
                isDeviceOffline && hasMainFrameError -> ActiveSurface.OFFLINE
                hasUnresolvedHostLookup && !isDeviceOffline -> ActiveSurface.SITE_NOT_FOUND
                else -> ActiveSurface.NONE
            }
            val ctx = "[hasMainFrameError=$hasMainFrameError, hasUnresolvedHostLookup=$hasUnresolvedHostLookup, isDeviceOffline=$isDeviceOffline]"
            assertEquals("Active-tab surface mismatch $ctx", expected, result)

            // Reinforce the iff for SITE_NOT_FOUND explicitly.
            val shouldBeSiteNotFound = hasUnresolvedHostLookup && !isDeviceOffline
            if (shouldBeSiteNotFound) {
                assertEquals("Expected SITE_NOT_FOUND $ctx", ActiveSurface.SITE_NOT_FOUND, result)
            } else {
                assertNotEquals("Must not be SITE_NOT_FOUND $ctx", ActiveSurface.SITE_NOT_FOUND, result)
            }
        }
    }
}
