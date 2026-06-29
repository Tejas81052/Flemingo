# Implementation Plan: Site Not Found Screen and Scanner Orientation Lock

## Overview

This plan implements two changes to the EffectiveBrowser Android app (`me.thimmaiah.ebors`):
a branded animated "site not found" overlay for online host-lookup failures, and a portrait
orientation lock for the QR/barcode scanner.

The work starts with the pure routing functions (`resolveErrorSurface` /
`resolveActiveTabSurface`) and their property-based tests, since they are the primary correctness
surface and have no Android dependencies. It then builds the resources, the controller, the per-tab
state, and finally wires everything into `MainActivity`. The scanner change is a small independent
edit. Each step builds on the previous one and ends with the overlay fully integrated into the
existing offline-screen pattern.

Language: Kotlin (matching the existing app and the design document).

## Tasks

- [x] 1. Create pure routing functions and their enums
  - [x] 1.1 Create `ErrorSurfaceRouting.kt` with routing logic
    - Add `app/src/main/java/me/thimmaiah/ebors/ErrorSurfaceRouting.kt` in package `me.thimmaiah.ebors`
    - Define `enum class ErrorSurface { UNCHANGED, OFFLINE, SITE_NOT_FOUND, DEFAULT_WEBVIEW }`
    - Define `enum class ActiveSurface { NONE, OFFLINE, SITE_NOT_FOUND }`
    - Implement `resolveErrorSurface(isForMainFrame: Boolean, isDeviceOffline: Boolean, errorCode: Int): ErrorSurface` with precedence: not-main-frame → `UNCHANGED`; offline → `OFFLINE`; `errorCode == WebViewClient.ERROR_HOST_LOOKUP` → `SITE_NOT_FOUND`; else → `DEFAULT_WEBVIEW`
    - Implement `resolveActiveTabSurface(hasMainFrameError: Boolean, hasUnresolvedHostLookup: Boolean, isDeviceOffline: Boolean): ActiveSurface` with precedence: offline + main-frame error → `OFFLINE`; unresolved host lookup → `SITE_NOT_FOUND`; else → `NONE`
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 4.2, 4.3, 4.4_

  - [x]* 1.2 Write property test for non-main-frame errors
    - **Property 1: Non-main-frame errors leave the tab unchanged**
    - For any `isDeviceOffline` and any `errorCode`, when `isForMainFrame` is false, `resolveErrorSurface` returns `UNCHANGED`
    - Minimum 100 iterations; tag comment: `Feature: site-not-found-and-scanner-ui, Property 1`
    - **Validates: Requirements 1.3**

  - [x]* 1.3 Write property test for offline precedence in error routing
    - **Property 2: Offline takes precedence over host-lookup**
    - For any `errorCode` (including `ERROR_HOST_LOOKUP`), when `isForMainFrame` and `isDeviceOffline` are true, `resolveErrorSurface` returns `OFFLINE` and never `SITE_NOT_FOUND`
    - Minimum 100 iterations; tag comment: `Feature: site-not-found-and-scanner-ui, Property 2`
    - **Validates: Requirements 1.2, 4.4, 6.1**

  - [x]* 1.4 Write property test for online host-lookup routing
    - **Property 3: Online main-frame host-lookup failures route to site-not-found**
    - When `isForMainFrame` is true, `isDeviceOffline` is false, and `errorCode == WebViewClient.ERROR_HOST_LOOKUP`, `resolveErrorSurface` returns `SITE_NOT_FOUND`
    - Minimum 100 iterations; tag comment: `Feature: site-not-found-and-scanner-ui, Property 3`
    - **Validates: Requirements 1.1**

  - [x]* 1.5 Write property test for default WebView behavior
    - **Property 4: Other online main-frame errors keep the default WebView behavior**
    - For any `errorCode != WebViewClient.ERROR_HOST_LOOKUP`, when `isForMainFrame` is true and `isDeviceOffline` is false, `resolveErrorSurface` returns `DEFAULT_WEBVIEW`
    - Use a generator that includes `ERROR_HOST_LOOKUP` and a spread of other codes (`ERROR_CONNECT`, `ERROR_TIMEOUT`, etc.)
    - Minimum 100 iterations; tag comment: `Feature: site-not-found-and-scanner-ui, Property 4`
    - **Validates: Requirements 1.4**

  - [x]* 1.6 Write property test for active-tab offline precedence
    - **Property 5: Active-tab offline precedence**
    - For any `hasUnresolvedHostLookup`, when `isDeviceOffline` and `hasMainFrameError` are true, `resolveActiveTabSurface` returns `OFFLINE` and never `SITE_NOT_FOUND`
    - Minimum 100 iterations; tag comment: `Feature: site-not-found-and-scanner-ui, Property 5`
    - **Validates: Requirements 4.4, 6.1**

  - [x]* 1.7 Write property test for active-tab site-not-found classification
    - **Property 6: Active-tab site-not-found classification**
    - For any boolean combination, `resolveActiveTabSurface` returns `SITE_NOT_FOUND` iff `hasUnresolvedHostLookup` is true and `isDeviceOffline` is false; otherwise `NONE` unless the offline-precedence case applies
    - Minimum 100 iterations; tag comment: `Feature: site-not-found-and-scanner-ui, Property 6`
    - **Validates: Requirements 4.2, 4.3**

- [x] 2. Checkpoint - Ensure routing functions and property tests pass
  - Ensure all property tests pass, ask the user if questions arise.

- [x] 3. Add site-not-found resources
  - [x] 3.1 Copy drawables and add string resources
    - Copy from `handoff/res/drawable` into `app/src/main/res/drawable`: `avd_site_not_found_hero.xml`, `ill_site_not_found_hero.xml`, `bg_site_not_found_secondary.xml`, `bg_site_not_found_favicon.xml`
    - Verify `bg_no_internet_tab_pill.xml` and `ic_no_internet_globe.xml` are present; only copy/keep them if a retained view in the adapted layout references them, otherwise omit
    - Merge into `app/src/main/res/values/strings.xml` from `handoff/strings.site_not_found.xml`: `site_not_found_eyebrow`, `site_not_found_title`, `site_not_found_body`, `site_not_found_back`, `site_not_found_retry`
    - EXCLUDE `site_not_found_search`
    - _Requirements: 2.1, 2.2, 2.4, 2.5_

  - [x] 3.2 Add adapted `activity_site_not_found.xml` layout
    - Add `app/src/main/res/layout/activity_site_not_found.xml` adapted from `handoff/res/layout/activity_site_not_found.xml`
    - Keep: root container, hero `ImageView` (`@id/site_not_found_hero`), eyebrow text, title text, body `TextView` (`@id/site_not_found_body`), and the back/retry row (`@id/site_not_found_back`, `@id/site_not_found_retry`)
    - REMOVE the tab-pill `LinearLayout` (containing `site_not_found_tab_url`, the favicon `ImageView`, and `site_not_found_close`)
    - REMOVE the `site_not_found_search` `Button`
    - _Requirements: 2.1, 2.3, 2.4, 2.5_

  - [x] 3.3 Include the overlay in `activity_main.xml`
    - Add `<include android:id="@+id/site_not_found_overlay" layout="@layout/activity_site_not_found" android:visibility="gone" .../>` as a sibling of `no_internet_overlay` inside the same parent under `web_container`
    - _Requirements: 1.5, 2.1_

- [x] 4. Implement `SiteNotFoundController`
  - [x] 4.1 Create `SiteNotFoundController.kt`
    - Add `app/src/main/java/me/thimmaiah/ebors/SiteNotFoundController.kt`, modeled on `NoInternetController`
    - Resolve views: hero `ImageView`, body `TextView`, back `Button`, retry `Button`
    - Implement `show(failedUrl: String, onBack: () -> Unit, onRetry: () -> Unit)`: derive host via `Uri.parse(failedUrl).host ?: failedUrl`, set body via `getString(R.string.site_not_found_body, host)`, wire back/retry click listeners, then `startAnimations()`
    - Implement `startAnimations()` / `stopAnimations()` using `(hero.drawable as? Animatable)?.start()` / `?.stop()`
    - Do NOT include the `site_not_found_tab_url` strike-through field or the `site_not_found_search` button/`onSearch` parameter
    - _Requirements: 2.1, 2.2, 2.6, 2.7_

  - [x]* 4.2 Write unit tests for `SiteNotFoundController.show`
    - Verify body copy populated via `getString(R.string.site_not_found_body, host)` and click listeners wired to supplied lambdas, and hero animation started
    - Verify host fallback when `failedUrl` is null/blank/host-less (uses raw string, no crash)
    - _Requirements: 2.2, 2.6_

- [x] 5. Extend `Tab` per-tab state
  - [x] 5.1 Add `siteNotFoundUrl` to `Tab`
    - Add `var siteNotFoundUrl: String? = null` to `Tab.kt`
    - Document that it holds the unresolved host-lookup failing URL and is cleared on `onPageStarted` and on successful `onPageFinished`
    - _Requirements: 1.6, 3.4, 4.1, 4.2_

- [x] 6. Wire site-not-found into `MainActivity`
  - [x] 6.1 Add controller, show/hide methods, and showing flag
    - Add `siteNotFoundController: SiteNotFoundController by lazy { ... }` and `siteNotFoundShowing: Boolean` (mirroring `noInternetController` / `offlineShowing`)
    - Implement `showSiteNotFound(url: String)`: hide start page / find bar, set `site_not_found_overlay` visible, set active WebView non-visible, set `siteNotFoundShowing = true`, call `controller.show(url, onBack, onRetry)`
    - `onRetry`: `hideSiteNotFound()`, restore WebView visibility, `activeTabOrNull?.webView?.reload()`
    - `onBack`: `hideSiteNotFound()`, restore WebView visibility, then `if (webView.canGoBack()) webView.goBack() else showStartPage()`
    - Implement `hideSiteNotFound()`: early-return if not showing; clear overlay visibility, restore WebView visibility, `controller.stopAnimations()`, set `siteNotFoundShowing = false`
    - _Requirements: 1.5, 2.6, 2.7, 3.1, 3.2, 3.3_

  - [x] 6.2 Rewrite `onReceivedError` to dispatch on `resolveErrorSurface`
    - Replace the single `if (isDeviceOffline())` check with a dispatch on `resolveErrorSurface(request.isForMainFrame, isDeviceOffline(), error.errorCode)`
    - `UNCHANGED` → return without touching tab presentation
    - `OFFLINE` → set `tab.mainFrameErrored = true`, existing `showOfflineScreen()` path
    - `SITE_NOT_FOUND` → set `tab.mainFrameErrored = true`, `tab.siteNotFoundUrl = failingUrl`, and `showSiteNotFound(failingUrl)` only if `tab === activeTabOrNull`
    - `DEFAULT_WEBVIEW` → set `tab.mainFrameErrored = true` only
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.6, 6.1_

  - [x] 6.3 Extend `onPageStarted` and `onPageFinished` for recovery
    - In `onPageStarted`: clear `tab.siteNotFoundUrl = null` (new attempt begins)
    - In `onPageFinished`: alongside the existing `if (!tab.mainFrameErrored) hideOfflineScreen()`, on a successful finish for the active tab clear `tab.siteNotFoundUrl = null` and call `hideSiteNotFound()`
    - _Requirements: 3.4, 4.1_

  - [x] 6.4 Extend `evaluateOfflineForActiveTab` to dispatch on `resolveActiveTabSurface`
    - Compute `resolveActiveTabSurface(tab.mainFrameErrored, tab.siteNotFoundUrl != null, isDeviceOffline())`
    - `OFFLINE` → existing offline show path; `SITE_NOT_FOUND` → `showSiteNotFound(tab.siteNotFoundUrl!!)`; `NONE` → `hideSiteNotFound()` (and existing offline hide / online-recovery reload)
    - _Requirements: 4.2, 4.3, 4.4_

  - [x] 6.5 Hook site-not-found hero animation into lifecycle
    - In `onResume`: if `siteNotFoundShowing`, `controller.startAnimations()`
    - In `onPause` / `onStop`: if `siteNotFoundShowing`, `controller.stopAnimations()`
    - Mirror the existing offline controller lifecycle hooks
    - _Requirements: 2.6, 2.7_

- [x] 7. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 8. Lock scanner orientation
  - [x] 8.1 Change `setOrientationLocked` in `launchQrScanner()`
    - In `MainActivity.launchQrScanner()`, change `setOrientationLocked(false)` to `setOrientationLocked(true)`
    - Leave the rest of `ScanOptions`, the launcher, and the decode-result routing (`resolveUserInput` → `navigationController.loadAddress`) unchanged
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5_

  - [x]* 8.2 Write smoke test for scanner options
    - Assert `launchQrScanner()` builds `ScanOptions` with orientation locked (`setOrientationLocked(true)`)
    - _Requirements: 5.1_

- [x] 9. Build verification
  - [x] 9.1 Build the debug app
    - Run `./gradlew assembleDebug` (or the equivalent Gradle wrapper command) and resolve any compile errors
    - _Requirements: 1.1, 2.1, 5.1_

## Notes

- Tasks marked with `*` are optional test sub-tasks and can be skipped for a faster MVP.
- Each task references specific requirement sub-clauses for traceability.
- Property tests (1.2–1.7) validate the universal correctness properties for the pure routing
  functions; unit tests cover controller binding and the scanner option change.
- On-device manual verification (animated overlay rendering, WebView hide-while-shown, hero
  start/stop on lifecycle, scanner portrait lock during rotation) is performed by the user outside
  this plan, since those behaviors depend on Android view rendering and the ZXing capture activity
  and cannot be reliably automated here.
- Checkpoints (tasks 2 and 7) ensure incremental validation before moving on.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "3.1", "8.1"] },
    { "id": 1, "tasks": ["1.2", "3.2"] },
    { "id": 2, "tasks": ["1.3", "3.3", "5.1"] },
    { "id": 3, "tasks": ["1.4", "4.1"] },
    { "id": 4, "tasks": ["1.5", "4.2", "6.1"] },
    { "id": 5, "tasks": ["1.6", "6.2"] },
    { "id": 6, "tasks": ["1.7", "6.3"] },
    { "id": 7, "tasks": ["8.2", "6.4"] },
    { "id": 8, "tasks": ["6.5"] },
    { "id": 9, "tasks": ["9.1"] }
  ]
}
```

Wave rationale: `MainActivity.kt` is edited by 8.1, 6.1, 6.2, 6.3, 6.4, and 6.5, so each is placed
in a distinct wave to avoid write conflicts. The six property tests share
`ErrorSurfaceRoutingTest.kt`, so 1.2–1.7 are likewise spread across separate waves. Setup tasks
(routing function, resources, Tab state) land in early waves; build verification (9.1) runs last.
