# Requirements Document

## Introduction

This feature adds two related improvements to the EffectiveBrowser Android app (package `me.thimmaiah.ebors`):

1. **Custom "site not found" screen.** When the device is online but a navigation fails because the host cannot be resolved (DNS lookup failure), the app currently falls through to the default WebView "page not found" error page. This feature replaces that fallback with a branded, animated in-app screen built from the provided `handoff/` assets. Per explicit user direction, the provided design is adapted by removing the struck-through host address pill and the "Search for ..." button, keeping only the animated hero, the explanatory copy, and the "Go back" / "Try again" actions.

2. **Scanner orientation fix.** The QR/barcode scanner currently passes `setOrientationLocked(false)` to the ZXing capture flow, which lets the capture screen auto-rotate into landscape. This feature locks the scanner capture UI to a stable portrait orientation so it no longer rotates while scanning.

Both changes follow existing app patterns: the site-not-found screen reuses the offline-screen pattern (an overlay inside `web_container` toggled by visibility against the WebView, driven by a controller), and the scanner change adjusts the existing ZXing `ScanOptions` configuration in `launchQrScanner()`.

## Glossary

- **Browser**: The EffectiveBrowser Android application (`me.thimmaiah.ebors`), specifically the `MainActivity` and its `WebViewClient`.
- **Site_Not_Found_Screen**: The branded in-app overlay shown when an online host-resolution failure occurs, inflated from `activity_site_not_found.xml` into the `web_container` and toggled by visibility against the active WebView.
- **Site_Not_Found_Controller**: The controller class (adapted from `handoff/SiteNotFoundActivity.patch.kt`) that binds copy, wires the action buttons, and starts/stops the animated hero for the Site_Not_Found_Screen.
- **Offline_Screen**: The existing branded no-internet overlay, surfaced by `showOfflineScreen()` / `hideOfflineScreen()` and driven by `NoInternetController`.
- **Host_Lookup_Failure**: A main-frame WebView error whose error code is `WebViewClient.ERROR_HOST_LOOKUP` (the DNS host could not be resolved).
- **Main_Frame_Error**: A `WebViewClient.onReceivedError` callback where `WebResourceRequest.isForMainFrame` is `true`.
- **Device_Offline**: The condition reported by the existing `isDeviceOffline()` helper, true when there is no active network with internet capability.
- **Failing_URL**: The URL passed to `onReceivedError` (`request.url`) that triggered the error.
- **Search_Routing**: The Browser's existing input-resolution path (`resolveUserInput()` → `navigationController.loadAddress()`) that turns a non-URL term into a search-engine query.
- **Scanner**: The QR/barcode capture flow launched via the ZXing `com.journeyapps.barcodescanner` library (`ScanContract` / `ScanOptions`) from `launchQrScanner()`.
- **Hero_Animation**: The `AnimatedVectorDrawable` (`avd_site_not_found_hero`) displayed in the Site_Not_Found_Screen, controlled through the `Animatable` interface.

## Requirements

### Requirement 1: Route online host-resolution failures to the custom screen

**User Story:** As a browser user, I want a clear, branded screen when a site address cannot be found while I am online, so that I understand the address failed to resolve instead of seeing a raw WebView error page.

#### Acceptance Criteria

1. WHEN a Main_Frame_Error occurs AND the Browser is not Device_Offline AND the error code is a Host_Lookup_Failure, THE Browser SHALL display the Site_Not_Found_Screen over the active tab.
2. WHEN a Main_Frame_Error occurs AND the Browser is Device_Offline, THE Browser SHALL display the Offline_Screen and SHALL NOT display the Site_Not_Found_Screen.
3. IF an `onReceivedError` callback reports a non-main-frame error, THEN THE Browser SHALL leave the active tab presentation unchanged.
4. WHEN a Main_Frame_Error occurs AND the Browser is not Device_Offline AND the error code is not a Host_Lookup_Failure, THE Browser SHALL retain the existing default WebView error behavior for that tab.
5. WHEN the Site_Not_Found_Screen is displayed, THE Browser SHALL make the active tab WebView non-visible while the Site_Not_Found_Screen is visible.
6. WHEN the Site_Not_Found_Screen is displayed for the active tab, THE Browser SHALL record that the active tab has a main-frame error so that tab-switch evaluation can restore the correct surface.

### Requirement 2: Render the adapted site-not-found layout

**User Story:** As a browser user, I want the site-not-found screen to show a focused message and animation without redundant controls, so that the screen stays clean and matches the product's intent.

#### Acceptance Criteria

1. WHEN the Site_Not_Found_Screen is displayed, THE Browser SHALL show the animated hero, the eyebrow text, the title text, and the body copy from the site-not-found string resources.
2. WHEN the Site_Not_Found_Screen is displayed, THE Browser SHALL populate the body copy with the host of the Failing_URL using the `site_not_found_body` string resource.
3. THE Site_Not_Found_Screen SHALL NOT display the struck-through host address pill (`site_not_found_tab_url` and its containing tab pill row).
4. THE Site_Not_Found_Screen SHALL NOT display the "Search for ..." button (`site_not_found_search`).
5. THE Site_Not_Found_Screen SHALL display a "Go back" action (`site_not_found_back`) and a "Try again" action (`site_not_found_retry`).
6. WHEN the Site_Not_Found_Screen becomes visible, THE Site_Not_Found_Controller SHALL start the Hero_Animation.
7. WHEN the Site_Not_Found_Screen is dismissed, THE Site_Not_Found_Controller SHALL stop the Hero_Animation.

### Requirement 3: Site-not-found actions

**User Story:** As a browser user, I want to go back or retry from the site-not-found screen, so that I can recover from a mistyped or temporarily unresolvable address.

#### Acceptance Criteria

1. WHEN the user activates the "Try again" action, THE Browser SHALL dismiss the Site_Not_Found_Screen, restore the active tab WebView visibility, and reload the Failing_URL.
2. WHEN the user activates the "Go back" action AND the active tab WebView can navigate back, THE Browser SHALL dismiss the Site_Not_Found_Screen, restore the active tab WebView visibility, and navigate the WebView back.
3. WHEN the user activates the "Go back" action AND the active tab WebView cannot navigate back, THE Browser SHALL dismiss the Site_Not_Found_Screen and present the start page for the active tab.
4. WHEN a navigation initiated from the Site_Not_Found_Screen completes successfully for the active tab, THE Browser SHALL clear the recorded main-frame error state for that tab.

### Requirement 4: Recovery and tab-switch consistency

**User Story:** As a browser user, I want the site-not-found screen to behave correctly across tab switches and successful reloads, so that I never get stuck behind a stale overlay.

#### Acceptance Criteria

1. WHEN a page finishes loading for a tab AND that tab has no recorded main-frame error, THE Browser SHALL ensure the Site_Not_Found_Screen is not shown for that tab.
2. WHEN the active tab changes, THE Browser SHALL show the Site_Not_Found_Screen only if the now-active tab has an unresolved Host_Lookup_Failure and is not Device_Offline.
3. WHEN the active tab changes to a tab without an unresolved host-lookup error, THE Browser SHALL hide the Site_Not_Found_Screen.
4. IF the Site_Not_Found_Screen and the Offline_Screen would both apply to the active tab, THEN THE Browser SHALL display the Offline_Screen and SHALL NOT display the Site_Not_Found_Screen.

### Requirement 5: Lock scanner orientation

**User Story:** As a browser user, I want the QR/barcode scanner to stay in a fixed portrait orientation, so that the capture view does not auto-rotate to landscape while I am scanning.

#### Acceptance Criteria

1. WHEN the Scanner is launched from `launchQrScanner()`, THE Browser SHALL configure the ZXing `ScanOptions` with orientation locked.
2. WHILE the Scanner capture UI is active, THE Scanner SHALL remain in portrait orientation regardless of device rotation.
3. WHEN the Scanner successfully decodes a value that resolves to a URL, THE Browser SHALL load that URL in the active tab.
4. WHEN the Scanner successfully decodes a value that does not resolve to a URL, THE Browser SHALL route that value through Search_Routing.
5. IF the Scanner returns no result (cancelled or empty), THEN THE Browser SHALL leave the active tab presentation unchanged.

### Requirement 6: Preserve existing offline behavior

**User Story:** As a browser user, I want the existing offline screen to keep working exactly as before, so that adding the site-not-found screen does not regress connectivity handling.

#### Acceptance Criteria

1. WHEN the Browser is Device_Offline and a Main_Frame_Error occurs, THE Browser SHALL display the Offline_Screen using the existing `showOfflineScreen()` path.
2. WHEN connectivity is restored for a tab stranded on an offline error, THE Browser SHALL recover that tab using the existing reconnect handling.
3. WHEN a link tap occurs while the Browser is Device_Offline, THE Browser SHALL display the Offline_Screen using the existing `shouldOverrideUrlLoading` path.
