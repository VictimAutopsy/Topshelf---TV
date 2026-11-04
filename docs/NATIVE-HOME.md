# Native Home (Phase 1) — plan

Goal: Home drawn by Android itself (smooth, stable frame rate on the Google TV Streamer) with the
same look; the web page keeps all logic and every other screen until those are rebuilt.

## Decision (user, 2026-10-21)
Cleanest and smoothest with every current feature: **fully native** — screens, navigation, menus
and logic in Java, rebuilt screen by screen. Web screens remain only as a temporary bridge for
features not yet moved, then the WebView is removed. (The "JS as brain" outline below is superseded
as the end state; it may still be used briefly for overlays during the migration.)

## Earlier outline
- **JS stays the brain** (apps, order, focus, modes, photos, settings, menus). A new `nativehome.js`
  sends a compact scene to Java: apps/tiles, focus index, mode (grid/deep/top/shelf), photo URL +
  Ken Burns params, caption, clock/weather, top-shelf cards. Key handling is unchanged.
- **`NativeHome.java`** (FrameLayout under the WebView) draws:
  - photo layers with crossfade + slow zoom (ViewPropertyAnimator / RenderThread), scrim gradient;
  - glass panels (dock, tabs pill, top bar pill) with a pre-blurred copy of the current photo
    (downscale + stack blur once per photo) drawn with the photo's transform, tint, rim light;
  - tiles as views: rounded outline clip, elevation shadow, focus scale 1.12 + rotationY tilt +
    shine sweep + label fade, dock glow following focus; drawer grid (deep) sliding up;
  - tabs/top bar text and icons (Inter fonts from assets/fonts), clock;
  - top-shelf card row.
- **Web page while native Home shows** (`body.nhome`): transparent, all Home DOM hidden
  (`visibility:hidden`) — only overlays draw (menu, settings, sheet, toast, search, launch, lkcode),
  exactly like `body.nvfs` for full-screen video. Switching to Movies/Live/Sports: native Home fades,
  page shows the tab as today.
- Tile images: Java already has them (`Bridge.iconPng`, custom icons `IconPacks.readCustom/packIcon`);
  CSS-only custom looks (frost/name/color) re-drawn with Canvas.

## Reference measurements (1.0.51 on the TV)
Home still 58 fps · Home moving 25 · Movies moving 6–16 · Live moving 22 · Sports 49.
Native Live TV video: MediaTek HW decoder stalled (Media3 1e12 µs renderer offset suspected);
1.0.52 tries a 60 s offset first (tools/media3/media3-offset.patch), software decoder as fallback.

## Status
- Settings in sections, preview hides under panels, GitHub updates/reports: done (1.0.50–1.0.52).
- Native Home: design done, implementation not started.
