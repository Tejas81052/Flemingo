# Effective Browser v10 — design handoff

This folder bridges the **paper-theme prototype** (`Effective Browser.html`) back to the Kotlin app. Drop files in, rebuild, no code refactor needed beyond two small `MainActivity.kt` additions.

---

## Drop-in mapping

| Prototype | Android target | Status |
|---|---|---|
| `tokens.css` light palette | `app/src/main/res/values/colors.xml` | **replace** with `handoff/res/values/colors.xml` |
| `tokens.css` ink palette | `app/src/main/res/values-night/colors.xml` | **replace** with `handoff/res/values-night/colors.xml` |
| Top chrome + bottom nav | `app/src/main/res/layout/activity_main.xml` | **replace** with `handoff/res/layout/activity_main.xml` |
| Address pill background | new drawable | **add** `handoff/res/drawable/bg_address_pill.xml` |
| Tab-count badge | new drawable | **add** `handoff/res/drawable/bg_tab_badge.xml` |
| Host/path two-tone text | `MainActivity.kt` | **merge** `handoff/MainActivity.patch.kt` (two routines) |

Every existing view ID is preserved — `MainActivity.kt` compiles against the new layout without rename or refactor.

---

## What actually changed visually

### Chrome
1. **Address pill** swaps `MaterialCardView` for a `FrameLayout + shape drawable`. The shape paints a 1dp warm stroke and surface fill; same 22dp corner radius (height/2) as before. One fewer view in the hierarchy.
2. **URL text** is monospace 13sp with a Spannable: bold host, faint path. Applied on every `onPageFinished` / tab switch; cleared when the EditText is focused so editing is unimpeded.
3. **Refresh icon** retints from `browser_icon` → `browser_text_2` so it reads as secondary chrome, not a primary action.
4. **Progress hairline** insets 12dp on both sides so it lives inside the chrome margin rather than running edge-to-edge.

### Bottom nav
1. **Hairline** 1dp `browser_stroke` separates nav from content (replaces shadow elevation).
2. **Caption** prepends an accent terracotta dot via a Spannable, sized in monospace 11sp at 0.02em tracking. New routine `renderBrowserCaption()` in the patch.
3. **Tab count** moves onto a soft `bg_tab_badge` chip — a 4dp-radius pill in `browser_surface_muted` — so it reads as a count, not a free-floating glyph.
4. **Touch target** grows 48 → 52dp, padding 12 → 14dp.

### Color system
All semantic names kept; only hex values changed. Adds three new tokens used by the new chrome:

```
browser_accent_soft  — accent at ~14% over paper, for tinted cards
browser_on_accent    — ink color on solid accent fills
browser_text_2       — secondary ink (subtitles, secondary icons)
browser_faint        — mono metadata (URL path)
```

`browser_chip_bg` is aliased to `browser_accent_soft` so any existing references to it (chip styling in `bookmarks` / `downloads`) keep working without a search-and-replace.

---

## What's *not* in this handoff (yet)

These prototype screens map to existing activities but weren't included as XML rewrites — happy to do them next:

- **Tabs sheet** (`dialog_tab_switcher.xml` + `item_tab.xml`) — segmented Regular/Private, card grid with thumbnails, private cards on ink surface.
- **Library** (`activity_bookmarks.xml` + `item_bookmark.xml`) — Bookmarks/History segmented tabs, big serif title, mono hostnames.
- **Settings** (`activity_settings.xml` + `item_settings_switch.xml`) — section headers in serif italic, icon-leading rows, paper-warm dividers.
- **Menu** — currently a `PopupMenu`. The prototype uses a 4×2 quick-action grid + list bottom sheet; would become a `BottomSheetDialogFragment` with a new `sheet_browser_menu.xml`.
- **Reader mode** (`reader.html`) — pure CSS replacement, drop-in for `app/src/main/assets/reader.html`.

---

## Type recommendation

The prototype uses Instrument Serif + Geist + JetBrains Mono. On Android, `fontFamily="monospace"` already gives a serviceable mono. For the serif moments (settings section headers, library title), pull a font into `res/font/` — `instrument_serif.ttf` for display, fall back to `serif` for the activity titles. Body remains the system sans.

---

---

## Site not found (`activity_site_not_found.xml`)

Animated "address not found" state — shown when the device is online but the **host doesn't resolve** (`onReceivedError` with `ERROR_HOST_LOOKUP`). Different screen, different metaphor from the offline one: a freestanding door in a field — the browser knocked, nobody's home.

### Drop-ins

| File | Destination | Status |
|---|---|---|
| `handoff/res/layout/activity_site_not_found.xml` | `app/src/main/res/layout/` | **add** |
| `handoff/res/drawable/ill_site_not_found_hero.xml` | `app/src/main/res/drawable/` | **add** (static composition) |
| `handoff/res/drawable/avd_site_not_found_hero.xml` | `app/src/main/res/drawable/` | **add** (animations) |
| `handoff/res/drawable/bg_site_not_found_secondary.xml` | `app/src/main/res/drawable/` | **add** |
| `handoff/res/drawable/bg_site_not_found_favicon.xml` | `app/src/main/res/drawable/` | **add** |
| `handoff/strings.site_not_found.xml` | merge into `values/strings.xml` | **add 6 strings** |
| `handoff/SiteNotFoundActivity.patch.kt` | merge into `MainActivity` | **add `SiteNotFoundController`** |

Reuses `bg_no_internet_tab_pill.xml` and `ic_no_internet_globe.xml` from the no-internet handoff — ship those first (or together).

### Animations packed in the AVD

| Target | What it does | Period |
|---|---|---|
| `grp_door` | knock shudder (rotation, hinge-left), still 0–70% of cycle | 4.2s loop |
| `grp_knock_1/2` | sound rings scale 0.3 → 1.8 + alpha, sync with the knock | 4.2s loop |
| `grp_plate` | "404" number-plate swing on one screw | 3.6s ease, reverse |
| `grp_key` | float up, jiggle the lock, give up, drift back (translate + rotate keyframes) | 5.5s loop |
| `grp_mote_1..4` | dust drifting up-right through the keyhole light + fade | 4s, staggered |
| `grp_shaft` | light shaft alpha breathe | 4s ease, reverse |
| `grp_blade_1..5` | grass sway (rotation, bottom pivot) | 3.4s, staggered |
| `grp_tumble` | paper wad rolling across + spinning | 9s loop |
| `grp_specks_1..3` | drift up | 7/8.5/9.5s |

`(hero.drawable as Animatable).start()` runs them all.

### Two text caveats (VectorDrawable can't render `<text>`)

1. **"404"** on the plate is drawn as **stroked paths** (three glyphs) inside `grp_plate` — they swing with the plate. Crisp enough at the rendered size.
2. **"NOT FOUND"** on the doormat is approximated as small tick marks suggesting lettering. If you want real crisp text, drop a tiny rotated `TextView` over the mat in the layout instead — but the ticks read fine at icon scale and keep the hero a single self-contained drawable.

### Controller

`SiteNotFoundController.show(failedUrl, onSearch, onBack, onRetry)`:
- Strikes through the dead host in the tab pill (`Paint.STRIKE_THRU_TEXT_FLAG`)
- Fills the body copy and the **"Search for …"** button with the failing host / bare term (host minus `www.` and TLD)
- Wires the three CTAs and starts the AVD

### Routing — important ordering

In `onReceivedError`, **check offline first**: a DNS failure while the device has no network is really an offline problem, so route that to the no-internet screen. Only show site-not-found when there *is* a network but the host won't resolve:

```kotlin
val offline = connectivityManager.activeNetwork == null
when {
    offline -> showOffline(failingUrl)
    error.errorCode == ERROR_HOST_LOOKUP -> showSiteNotFound(failingUrl)
    else -> { /* default WebView error or a generic state */ }
}
```

Guard on `request.isForMainFrame` so a failed sub-resource (an image, an ad the blocker killed) never triggers the full-screen takeover.

---



Animated offline state shown when `ConnectivityManager.activeNetwork == null` or `WebViewClient.onReceivedError` fires with `ERR_INTERNET_DISCONNECTED`. Replaces the WebView in `web_container` until the user taps Try Again.

### Drop-ins

| File | Destination | Status |
|---|---|---|
| `handoff/res/layout/activity_no_internet.xml` | `app/src/main/res/layout/` | **add** |
| `handoff/res/drawable/ill_no_internet_hero.xml` | `app/src/main/res/drawable/` | **add** (static composition) |
| `handoff/res/drawable/avd_no_internet_hero.xml` | `app/src/main/res/drawable/` | **add** (animations) |
| `handoff/res/drawable/bg_no_internet_stamp.xml` | `app/src/main/res/drawable/` | **add** (perforated stamp button) |
| `handoff/res/drawable/bg_no_internet_stamp_chip.xml` | `app/src/main/res/drawable/` | **add** |
| `handoff/res/drawable/bg_no_internet_tab_pill.xml` | `app/src/main/res/drawable/` | **add** |
| `handoff/res/drawable/bg_no_internet_favicon.xml` | `app/src/main/res/drawable/` | **add** |
| `handoff/res/drawable/ic_no_internet_globe.xml` | `app/src/main/res/drawable/` | **add** |
| `handoff/strings.no_internet.xml` | merge into `values/strings.xml` | **add 6 strings + 1 path** |
| `handoff/NoInternetActivity.patch.kt` | merge into `MainActivity` | **add `NoInternetController`** |

### Animations packed in the AVD

The hero uses **one** AnimatedVectorDrawable wrapping a single static vector. Each animated piece is a named `<group>` or `<path>` and the AVD has a `<target>` per piece:

| Target | What it does | Period |
|---|---|---|
| `grp_compass` | 360° rotation | 60s linear loop |
| `grp_pin` | translateY 0 → −3 (bob) | 3.4s ease, reverse |
| `grp_arc_{1..3}` | alpha 0.15 → 0.55 pulse | 3s, staggered 0/0.5/1s |
| `path_flight` | `trimPathOffset` 0 → 0.25 (dash crawl) | 1.6s linear loop |
| `grp_plane_push` | keyframed translateX/Y/rotation (push & bounce) | 4.6s loop |
| `grp_plane_bob` | translateY 0 → −3 (bob layered on push) | 2.8s ease, reverse |
| `grp_ripple_{1..3}` | scale 0.2 → 2.2 + alpha 0.55 → 0 | 2.6s, staggered 0/0.9/1.8s |
| `grp_specks_{1..3}` | translateY 0 → −310 (drift up) | 7/8.5/9.5s loop |

`(hero.drawable as Animatable).start()` kicks them all off; `.stop()` halts cleanly.

### Why the stamp path is a string resource

The perforated outline traces 60+ `M/L/A` commands. Both the shadow and the fill in `bg_no_internet_stamp.xml` reference the same path — extracting it to `strings.xml` (`@string/no_internet_stamp_path`, marked `translatable="false"`) keeps them in lockstep. Editing the shape later only touches one place.

### Refresh icon spin

The CSS keyframe holds for 60% of the cycle then sweeps −360° with ease-in-out. The Kotlin patch uses a `ValueAnimator` + `PathInterpolator(0.6, 0, 0.45, 1)` to match — flat through 0..0.6, accelerated curve to the end. Cancel it in `onPause` to save the CPU cycle when the user backgrounds the app.

### Wiring it up

```kotlin
private lateinit var offlineCtrl: NoInternetController
private val offlineView: View by lazy {
    layoutInflater.inflate(R.layout.activity_no_internet, web_container, false)
        .also { it.visibility = View.GONE; web_container.addView(it) }
}

private fun showOffline(failedUrl: String) {
    offlineView.visibility = View.VISIBLE
    webView.visibility = View.GONE
    offlineCtrl = NoInternetController(this).also {
        it.show(Uri.parse(failedUrl).host ?: failedUrl) {
            offlineView.visibility = View.GONE
            webView.visibility = View.VISIBLE
            it.hide()
            webView.reload()
        }
    }
}
```

`MainActivity` already routes `WebViewClient.onReceivedError` — just call `showOffline(failingUrl)` from that path when the error code is `ERR_INTERNET_DISCONNECTED` or `ERR_ADDRESS_UNREACHABLE`.

---



Three-page intro: Welcome / Privacy promise / Default browser. Re-skin only — every existing view ID and string reference is preserved, so `WelcomeActivity.kt` compiles unchanged (one optional patch adds the step indicator + promise binding).

### Drop-ins

| File | Destination | Status |
|---|---|---|
| `handoff/res/layout/activity_welcome.xml` | `app/src/main/res/layout/` | **replace** |
| `handoff/res/layout/welcome_header.xml` | `app/src/main/res/layout/` | **add** |
| `handoff/res/layout/welcome_header_inverse.xml` | `app/src/main/res/layout/` | **add** |
| `handoff/res/layout/welcome_promise_row.xml` | `app/src/main/res/layout/` | **add** |
| `handoff/res/drawable/ill_welcome_paper.xml` | `app/src/main/res/drawable/` | **add** (hero on page 1) |
| `handoff/res/drawable/ill_welcome_default.xml` | `app/src/main/res/drawable/` | **add** (hero on page 3) |
| `handoff/res/drawable/ic_welcome_check.xml` | `app/src/main/res/drawable/` | **add** |
| `handoff/res/drawable/bg_welcome_*.xml` (×9) | `app/src/main/res/drawable/` | **add** |
| `handoff/strings.welcome.xml` | merge into `values/strings.xml` | **add 8 new keys** |
| `handoff/WelcomeActivity.patch.kt` | merge into `WelcomeActivity.kt` | **add 2 routines** |

### What changed visually

1. **Step indicator** (top-right of each page) — 3 dashes; the active one is filled accent and 28dp wide, inactive ones are 14dp neutral.
2. **Brand line** (top-left) — Ebros logo monogram tile + small mono wordmark on every page.
3. **Hero illustration on pages 1 & 3** — `ill_welcome_paper` (layered paper sheets with a folded terracotta corner and a bookmark ribbon) and `ill_welcome_default` (a phone with the Ebros launcher icon haloed in dashed accent).
4. **Page 2 promises** are now four cards with check chips + inline title/body. The old single multiline TextView (`@string/welcome_privacy_bullets`) is no longer referenced — you can delete the key.
5. **Page 3 finish-line** — solid accent panel across the top (with the wordmark + headline inverted on it), paper bottom with the hero and CTAs. Visually rewards the user for finishing the flow.
6. **Buttons go pill-shaped** (28dp radius) and gain a small forward arrow drawable.

### Kotlin changes (small)

`WelcomeActivity.patch.kt` adds two helpers:
- `refreshSteps(activeIndex: Int)` — paints the active dot (wider + accent fill) whenever the ViewFlipper changes child. Call from your existing `showNext()` / `showPrevious()` paths.
- `bindPromises()` — populates the four promise card titles & bodies on page 2. Call once in `onCreate` after `setContentView`.

Both routines tolerate the include-layout duplication (the header layout is `<include>`d on every page so the same view IDs appear 3×; the helper walks the tree and updates all instances).

### String migration

Keep your existing welcome strings as-is. The patch adds 8 new keys:
- `welcome_promise_1_title` / `_body` … `welcome_promise_4_title` / `_body`

If you want the suggested copy upgrades (more vibrant tone), `handoff/strings.welcome.xml` also includes `*_new` variants of the existing keys. Either rename them and overwrite, or just copy the text into your existing keys.

### Light/dark

The new drawables reference semantic color tokens (`@color/browser_accent`, `@color/browser_surface`, etc.) which already have night variants in `values-night/colors.xml` — so the new welcome flow respects system dark mode without per-drawable variants.
