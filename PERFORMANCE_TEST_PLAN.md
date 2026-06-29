# Ebors performance test plan

## Baseline

Captured on June 23, 2026 using the connected `2411DRN47I` device
(Android 16 / API 36), debug build `1.1.2-debug`.

- Cold-start `TotalTime`, five force-stopped launches:
  `2096, 1901, 1990, 1796, 1769 ms`
- Median cold start: `1901 ms`
- Idle home-screen memory: `124062 KB PSS`, `272628 KB RSS`

Post-change steady-state verification on the same device:

- Cold-start `TotalTime`, seven force-stopped launches:
  `1900, 1922, 1770, 1772, 1938, 1806, 1917 ms`
- Median cold start: `1900 ms` (effectively unchanged, within measurement noise)
- Memory snapshot: `114510 KB PSS`, `262356 KB RSS`
- A 10-second HTML5 video opened in a new tab played through with one audio
  focus owner (Chromium/WebView) and no focus request from the notification service.

Run the repeatable check from the repository root:

```powershell
.\tools\measure-performance.ps1
```

## Measures and acceptance gates

1. Startup
   - Five force-stopped launches, compare the median rather than the fastest run.
   - No regression larger than 5% on the same device/build type.
   - The first tab must be attached and resumed before its first URL starts loading.

2. Media startup and tab switching
   - Start a direct HTML5 video three times from a cold process and three times in
     a newly opened tab.
   - Playback must not receive a synthetic pause during the first two seconds.
   - Starting playback must create one media notification without a second app
     audio-focus request.
   - Background/foreground and notification play/pause controls must continue to work.

3. Rendering smoothness
   - Reset `dumpsys gfxinfo`, scroll a long article for 15 seconds, then collect
     frame percentiles and janky-frame count.
   - Compare reader mode, a normal page, and the tab switcher separately.

4. Memory pressure
   - Record PSS/RSS with 1, 4, and 8 tabs.
   - Leave background tabs idle beyond the configured sleep threshold and confirm
     memory falls without discarding the active tab or a media-playing tab.

5. Feature regression matrix
   - Translator: search the language list, translate to English, Hindi, Kannada,
     Arabic, and Traditional Chinese, then change the target on the translated tab.
   - Reader: test a news article, blog post, code-heavy article, image-heavy
     article, RTL article, and a non-article page.
   - Browser: verify downloads, history, bookmarks, blocking, private tabs,
     permissions, fullscreen video, and process-state restoration.

## Current optimizations

- New WebViews are attached/resumed before their initial navigation.
- The foreground media service no longer competes with WebView for audio focus.
- Large filter-list parsing runs at Android background thread priority.
- Reader assets are cached after first use.
