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

import android.webkit.WebViewClient

/**
 * The outcome of the [MainActivity] `onReceivedError` routing decision.
 *
 * The four values are mutually exclusive and together fully characterize how a
 * WebView error is surfaced to the user:
 *  - [UNCHANGED]      — sub-resource (non-main-frame) error; leave the tab as-is.
 *  - [OFFLINE]        — main-frame error while the device is offline.
 *  - [SITE_NOT_FOUND] — online main-frame host-lookup failure.
 *  - [DEFAULT_WEBVIEW] — any other online main-frame error; keep WebView's page.
 */
enum class ErrorSurface { UNCHANGED, OFFLINE, SITE_NOT_FOUND, DEFAULT_WEBVIEW }

/**
 * The surface that belongs over the active tab after a tab switch / restore
 * evaluation (see `evaluateOfflineForActiveTab`).
 */
enum class ActiveSurface { NONE, OFFLINE, SITE_NOT_FOUND }

/**
 * Pure routing decision for a single `WebViewClient.onReceivedError` callback.
 *
 * Precedence (strict, top to bottom):
 *  1. A non-main-frame (sub-resource) error never changes tab presentation.
 *  2. Offline takes precedence over everything else for main-frame errors, so
 *     the existing offline screen is shown rather than the site-not-found one.
 *  3. An online main-frame host-lookup failure routes to the site-not-found
 *     screen.
 *  4. Any other online main-frame error keeps the default WebView error page.
 *
 * Holds no Android UI dependencies beyond the [WebViewClient.ERROR_HOST_LOOKUP]
 * integer constant, so it is unit/property-testable without instrumentation.
 */
fun resolveErrorSurface(
    isForMainFrame: Boolean,
    isDeviceOffline: Boolean,
    errorCode: Int,
): ErrorSurface = when {
    !isForMainFrame -> ErrorSurface.UNCHANGED
    isDeviceOffline -> ErrorSurface.OFFLINE
    errorCode == WebViewClient.ERROR_HOST_LOOKUP -> ErrorSurface.SITE_NOT_FOUND
    else -> ErrorSurface.DEFAULT_WEBVIEW
}

/**
 * Pure decision for which surface belongs over the now-active tab on a tab
 * switch / restore evaluation.
 *
 * Precedence (strict, top to bottom):
 *  1. Offline + a recorded main-frame error → the offline screen wins.
 *  2. An unresolved host-lookup failure → the site-not-found screen.
 *  3. Otherwise no overlay surface applies.
 */
fun resolveActiveTabSurface(
    hasMainFrameError: Boolean,
    hasUnresolvedHostLookup: Boolean,
    isDeviceOffline: Boolean,
): ActiveSurface = when {
    isDeviceOffline && hasMainFrameError -> ActiveSurface.OFFLINE
    hasUnresolvedHostLookup -> ActiveSurface.SITE_NOT_FOUND
    else -> ActiveSurface.NONE
}
