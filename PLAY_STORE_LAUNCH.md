# Ebors — Play Store launch checklist

Last updated: 2026. Pre-1.0 release.

This file tracks everything the Google Play Console needs before
Ebors can ship. Sections are ordered roughly by when you'll hit
them during the submission flow.

---

## 1. Code & build

- [x] `applicationId = "me.thimmaiah.ebors"` (unique on Play)
- [x] `versionCode = 1`, `versionName = "1.0"`
- [x] `compileSdk = 36`, `targetSdk = 36`, `minSdk = 29`
- [x] `isMinifyEnabled = true`, `isShrinkResources = true` on release build
- [x] LICENSE (Apache-2.0) + NOTICE at repo root
- [x] In-app "Open-source licenses" screen
- [x] First-launch onboarding with Terms acceptance
- [ ] **Sign the release APK** — set up a release keystore (do not check it into the repo). See `app/build.gradle.kts` commentary; add:
  ```kotlin
  signingConfigs {
      create("release") {
          storeFile = file(System.getenv("EBORS_KEYSTORE") ?: "release.jks")
          storePassword = System.getenv("EBORS_KEYSTORE_PASS")
          keyAlias = System.getenv("EBORS_KEY_ALIAS")
          keyPassword = System.getenv("EBORS_KEY_PASS")
      }
  }
  buildTypes { release { signingConfig = signingConfigs.getByName("release") } }
  ```
- [ ] **Build an AAB** (Android App Bundle), not an APK: `./gradlew bundleRelease`
- [ ] **Enable Play App Signing** during Play Console upload (Google holds the upload key)

---

## 2. Store listing

### App details
- App name: **Ebors**
- Short description (80 chars):
  > A calmer, privacy-first Android browser. Ads blocked by default. No telemetry.
- Full description (4000 chars max): see `STORE_DESCRIPTION.md` (TODO — write this)
- Category: **Communication** (browsers go here on Play)
- Tags: privacy, browser, ad blocker
- Contact email: ThimmaiahKK@proton.me
- Website: https://thimmaiah.me

### Visual assets (Play required)
- [ ] App icon: 512×512 PNG (you already have `mipmap-xxxhdpi/ic_launcher.webp`; export 512×512)
- [ ] Feature graphic: 1024×500 PNG/JPG (banner across the top of your listing)
- [ ] Screenshots: **at least 2, up to 8** phone screenshots; tablet ones optional
- [ ] Promo video: optional, YouTube URL

### Content rating
- Run the IARC questionnaire in Play Console. For a vanilla browser the
  answers are: no violence, no sexual content, no profanity FROM YOUR
  APP. Browser content rating typically lands at **Teen** because of
  the open-web caveat. Be honest in the questionnaire.

---

## 3. Privacy & data safety

### Privacy policy URL (required)
- **https://thimmaiah.me/privacy.html**
- File lives at `website/privacy.html`. Deploy via GitHub Pages /
  Cloudflare Pages / Netlify before submitting (see `website/README.md`).

### Data Safety form (required)
Honest answers given Ebors's posture:

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |
| Is all of the user data collected by your app encrypted in transit? | N/A — none collected |
| Do you provide a way for users to request that their data is deleted? | N/A — none stored off-device; uninstall removes everything |

The Data Safety section will also surface your declared permissions.
Tap "Add data type" only if you collect off-device — Ebors does not.

### Permission justifications

Play asks for a justification for each runtime-dangerous permission.
Copy-paste these into the console:

| Permission | Justification |
|---|---|
| `CAMERA` | Used only when a website you visit requests camera access (e.g. for a video call). The app shows an in-app prompt before granting. |
| `RECORD_AUDIO` | Used only when a website you visit requests microphone access (e.g. for a voice message). The app shows an in-app prompt before granting. |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | Used only when a website you visit requests location via the geolocation API (e.g. a maps page). The app shows an in-app prompt before granting. |
| `POST_NOTIFICATIONS` | Used to show download progress in the notification shade. The user can deny it. |
| `REQUEST_INSTALL_PACKAGES` | **Used only when the user explicitly installs an APK they have downloaded through the browser.** Tapping "Install" on a downloaded `.apk` hands the file to Android's package installer. The browser does not install anything without an explicit user tap. |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` | Keeps long-running downloads alive while the app is backgrounded. The notification surfaces the in-flight download. |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Keeps web audio/video playing when the app is backgrounded (e.g. music or a video's soundtrack). The media notification shows playback controls. |

**Heads-up on `REQUEST_INSTALL_PACKAGES`:** Play scrutinises this
permission heavily — apps with it can be flagged as "high-risk." Be
prepared to demonstrate the user-initiated install flow in a screen
recording if Play asks. (You hit the path from
`DownloadsActivity.kt:271` after tapping a downloaded APK.)

### Target audience
- Primary: **18+** (Adults). Browsers should not be marked as for
  children — they expose the open web.

---

## 4. Compliance & policies

- [ ] **Declare app uses no advertising ID** — Play will ask under
      "App content → Advertising ID." Ebors doesn't use it.
- [ ] **Government apps disclosure** — N/A
- [ ] **News app disclosure** — N/A (browser is general-purpose)
- [ ] **COVID-19 contact tracing** — N/A
- [ ] **Financial features** — N/A
- [ ] **Health features** — N/A

---

## 5. Release tracks

Suggested rollout:

1. **Internal testing** (just you + 1–2 friends with their Google
   account emails added). Validate the install, default-browser
   prompt, downloads, and at least one camera/mic/location web flow.
2. **Closed testing** (10–20 users) for a week. Watch the crash
   dashboard (Play surfaces ANRs / crashes automatically).
3. **Production**, 20% rollout on day 1, ramp to 100% over a few days.

---

## 6. Known limitations to document in release notes

- Voice search on Google's homepage and similar sites does not work.
  The Android System WebView does not implement the Web Speech API;
  only Chrome on the same device does. Standard mic access via
  `getUserMedia` (used by video calls, voice messages, etc.) works
  normally. The About card in Settings calls this out.
- No DNS-over-HTTPS — WebView uses the system resolver.
- Private profile requires WebView 121+ (`MULTI_PROFILE`). On older
  WebView builds the "New private tab" entry is hidden rather than
  offering a fake-private mode.

---

## 7. Post-launch monitoring

- **Crashes/ANRs**: Play Console → Quality → Crashes & ANRs.
  Set up email alerts for ANR rate > 0.47% or crash rate > 1.09%
  (Play's bad-behaviour thresholds).
- **User reviews**: respond to every 1–2★ review within a week.
- **Permission usage**: Play occasionally re-runs the "your app uses
  X permission, please re-declare" check. Re-confirm via the
  Justification page above.

---

## 8. Open follow-ups (not blocking 1.0)

From the audit pass:

- Move `BrowserBlocker.initialize()` and `HistoryRepository.loadFromDb()` off the main thread to remove cold-start I/O. Behavioural-safe refactor: load synchronously today, but on a background thread; the @Volatile fields already fail-open until loaded.
- Debounce address-bar suggestion filtering — currently O(history) per keystroke.
- Cache normalised bookmark URLs in a `HashSet<String>` so `isBookmarked()` is O(1) instead of O(n).
- Add `setOnSafeBrowsingHit` override so Safe-Browsing warning pages can be themed.
