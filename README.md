# Ebors

A fast, privacy-focused Android web browser with built-in, uBlock-grade
ad and tracker blocking. Built on Android's system WebView.

**Copyright (C) 2026 Tejas Thimmaiah**

---

## License

Ebors is **free software**, licensed under the
**GNU General Public License, version 3 or later (GPL-3.0-or-later)**.

You may use, study, share, and modify it under the terms of that license.
It comes with **no warranty**. The complete license text is in
[`LICENSE`](LICENSE), and a summary of incorporated components is in
[`NOTICE`](NOTICE).

Ebors incorporates GPL-licensed material from
[uBlock Origin](https://github.com/gorhill/uBlock) and the
[EasyList](https://easylist.to/) project (see
[Third-party components](#third-party-components)), which is why the whole
program is distributed under the GPL.

## Getting the source code (your rights under the GPL)

**This repository is the complete corresponding source code** for the Ebors
application as distributed (including the Google Play release).

If you received Ebors as a compiled `.apk`/`.aab` and want the source that
built it, you already have your answer: it is here. For the exact source of a
specific released version, check out the matching `versionName` tag (e.g.
`v1.1.2`). If a tag is missing or you cannot access this repository, you may
request the corresponding source for any version you received by emailing the
address in [Contact](#contact); this is a written offer valid as required by
the GPL.

You are free to build, modify, and redistribute your own version, provided
you keep it under the GPL-3.0-or-later and pass these same freedoms (and the
source) on to whoever you give it to. Please rebrand forks — see
[Trademarks](#trademarks).

## Building from source

**Requirements**

| Tool | Version |
|------|---------|
| JDK | 17+ |
| Android SDK | API 36 (`compileSdk 36.1`), min API 29 |
| Android Gradle Plugin | 9.1 (provided via the Gradle wrapper) |
| Gradle | 9.4.1 (via `./gradlew`, no separate install needed) |

**Build**

```bash
# Debug APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleDebug

# Run the unit tests
./gradlew testDebugUnitTest

# Release bundle (configure your own signing first) -> app/build/outputs/bundle/release/
./gradlew bundleRelease
```

The release build is unsigned by default. To produce a signed build, add a
`signingConfigs { release { ... } }` block to [`app/build.gradle.kts`](app/build.gradle.kts)
sourced from a keystore you keep outside the repository.

## Content blocking

Ebors blocks ads and trackers with an independent Kotlin implementation of
the EasyList / Adblock-Plus filter syntax
([`FilterEngine.kt`](app/src/main/java/me/thimmaiah/ebors/FilterEngine.kt)),
fed by real filter lists:

- **Bundled in the app** (`app/src/main/assets/filters/`): EasyList plus the
  uBlock Origin *ads*, *unbreak*, and *quick-fixes* lists.
- **Fetched/updated at runtime**: EasyPrivacy, the Online Malicious URL
  Blocklist, and Peter Lowe's list.

The engine is wired into WebView's `shouldInterceptRequest`
([`BrowserBlocker.kt`](app/src/main/java/me/thimmaiah/ebors/BrowserBlocker.kt)).

## Third-party components

**Incorporated GPL material** (reason the project is GPL-3):

| Component | License | Copyright |
|-----------|---------|-----------|
| [uBlock Origin](https://github.com/gorhill/uBlock) filter lists & scriptlets | GPL-3.0 | Raymond Hill and contributors |
| [EasyList / EasyPrivacy](https://easylist.to/) | GPL-3.0 / CC BY-SA 3.0 | The EasyList authors |

**Apache-2.0 libraries bundled in the APK** (compatible with GPL-3):
AndroidX, Material Components for Android, Kotlin standard library, OkHttp,
ZXing Android Embedded. See [`NOTICE`](NOTICE) for details.

**Rendering engine:** Ebors uses the device's **system WebView**
(Chromium-based), which is provided by the operating system and is *not*
bundled in the APK.

## Trademarks

"Ebors" and the Ebors logo are unregistered trademarks of Tejas Thimmaiah.
The GPL does not grant rights to these marks. If you redistribute a modified
version, please give it a different name and icon so users can tell it apart
from the upstream project.

"Android" and "Google Play" are trademarks of Google LLC. Ebors is
independent and is not affiliated with or endorsed by Google LLC or The
Chromium Authors.

## Contact

Licensing questions, or to request corresponding source for a released
version: **ThimmaiahKK@proton.me**
