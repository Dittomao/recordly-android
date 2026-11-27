# Renderer patches

The Android build runs the upstream Recordly renderer from
`recordly-upstream/`, which is an independent checkout pinned at:

- version `2.0.0-beta.1`
- commit `20e9490fb3b1739cb777fa1dd56c43021114521b` (2026-11-15)

`recordly-android.patch` contains every renderer change this port needs:

- Android Cut Room: local library, record/import buttons, full-width preview,
  labeled timeline, and bottom editing panels that replace the timeline without
  obscuring the preview; Look opens on frame controls with wallpapers in their
  own tab, and the frame settings use Bencho's MIT-licensed Wake slider in one
  column; touch scrubbing, full-stage crop, and Android Back handling for
  editor overlays; desktop keeps the upstream layout
- Sileo 0.1.6 recorder surface (MIT, pinned, vendored in
  `src/components/launch/sileo`): a separate `recorder.html` entry, the real
  SVG goo filter and spring, and one top-anchored boot-pill entrance that hands
  off to Sileo at identical geometry. The native window starts at the top and
  arrives compact; there is no bottom-to-top travel. Tap the pill to expand;
  20 s of inactivity folds it back. Home and the setup share
  `src/components/ui/CutRoomRecordButton.tsx`; Home starts the overlay on the
  press but never expands it. Android hosts the transparent window in
  `SileoOverlay.kt`; the setup is removed before capture.
- TapSwoop first-run tour: four Android pages, mandatory Tap focus,
  microphone and screen-sharing setup with Explore/Start locked until complete,
  no Skip, live state, theme and reduced-motion support, and replay from Settings
- Android-only app display name, without changing desktop releases
- touch auto-zoom scheduling, adaptive depth, and fitted spring timing shared
  by playback and export; separate nearby-in-time taps on distinct targets
  when they fit, and use the empty Android Zoom lane for scrubbing
- touch settings surface (no taskbar/updates/shortcuts/account on Android)
- Android WebView preview: decoded frames copied through a 2D canvas before
  Pixi upload (this WebView uploads black from `VideoSource`), first-frame
  refresh at 0:00, a capped playback copy with full-resolution paused frames,
  and a retrying filmstrip extraction
- consent-denial handling and the `canceled` result type
- the earlier thumbnail-capture fixes

The generated renderer bundle is not tracked. Apply the patch to a clean
upstream checkout, then build the bundle:

```sh
git -C recordly-upstream apply ../patches/recordly-android.patch
node scripts/build-renderer.mjs
```

`scripts/build-android.ps1` also builds the renderer automatically when the
bundle is missing or older than the renderer/bridge sources, and forces an asset
merge and repackage when the bundle is newer than the debug APK.

## Regenerating

After editing `recordly-upstream/`, regenerate from the repository root:

```sh
git -C recordly-upstream add -N .
git -C recordly-upstream diff --binary > patches/recordly-android.patch
git -C recordly-upstream reset
```

Use raw redirection (`cmd /c` on Windows) so the patch is UTF-8, not UTF-16,
and keep it LF. The repository marks `patches/*.patch` as `-text` so a Windows
checkout does not convert it to CRLF, which would make every hunk fail. Check
that it matches the working tree in reverse, and that it applies to a pristine
upstream checkout:

```sh
git -C recordly-upstream apply --check -R ../patches/recordly-android.patch
git -C <clean-upstream-at-pinned-commit> apply --check ../patches/recordly-android.patch
```
