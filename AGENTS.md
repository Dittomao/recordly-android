# Handoff for coding agents

Read this first. Then `android/README.md` (current state and evidence),
`docs/MOTION-PARITY.md` (the approved motion contract), and
`docs/COMMANDS.md` (commands). `PORTING-PLAN.md` is the long history;
sections 11–19 record what was built and why. Trust these files over
assumptions, and update them when behaviour changes.

## What this project is

A hybrid port of [Recordly](https://recordly.app) to Android. The upstream
React/PixiJS/WebCodecs editor runs unchanged in a WebView. Kotlin replaces the
Electron host: capture, tap telemetry, project storage, a loopback media
server, and export.

Do not rewrite the editor in Compose. Do not fork `vite.config.ts`. Keep the
`window.electronAPI` names and signatures identical to upstream's preload so
upstream merges remain possible (`scripts/check-parity.mjs` enforces this).

## Fresh checkout

Upstream is a separate, ignored git repo. This repository only tracks the
renderer changes as `patches/recordly-android.patch`. A clone without the
steps below will not build.

```powershell
git clone https://github.com/Dittomao/recordly-android.git
cd recordly-android
git clone https://github.com/webadderallorg/Recordly.git recordly-upstream
git -C recordly-upstream checkout 20e9490fb3b1739cb777fa1dd56c43021114521b
git -C recordly-upstream apply ../patches/recordly-android.patch
Push-Location recordly-upstream; npm ci; Pop-Location
```

`npm ci` installs the Vite/Playwright toolchain the renderer build and the
verifiers need. It also runs upstream's postinstall (Electron binaries); that
download is expected on a first setup.

Check the patch landed:

```powershell
git -C recordly-upstream apply --check -R ../patches/recordly-android.patch
```

`.gitattributes` marks `patches/*.patch` as `-text` so a Windows checkout keeps
the LF bytes `git apply` needs. Keep it LF when regenerating; a CRLF patch fails
every hunk.

Toolchain: Android Studio's bundled JDK (21), Android SDK platform 35,
Node.js 20+ (the workspace used Node 24), and the
`Medium_Phone_API_37.0` AVD. Export needs Android System WebView 130+.
`android/local.properties` is ignored; `scripts/build-android.ps1` writes it
from `ANDROID_HOME` (or `%LOCALAPPDATA%\Android\Sdk`) when it is missing.

## Build

```powershell
node scripts/build-renderer.mjs
powershell.exe -ExecutionPolicy Bypass -File scripts/build-android.ps1 -Offline
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

The APK build rebuilds a stale renderer and forces a repackage when assets are
newer than the APK. The debug package is `app.recordly.android.debug`.

## Gates — run before every commit

```powershell
node --test bridge/bridge.test.mjs bridge/android.test.mjs
node scripts/check-parity.mjs --strict
cd recordly-upstream
.\node_modules\.bin\vitest run src/components/video-editor/touchZoomPolicy.test.ts src/components/video-editor/timeline/zoomSuggestionUtils.test.ts src/components/video-editor/videoPlayback/sceneMotion.test.ts src/components/video-editor/videoPlayback/zoomRegionTiming.test.ts src/components/video-editor/videoPlayback/cameraMotionBlur.test.ts src/lib/exporter/zoomDuration.test.ts
.\node_modules\.bin\tsc --noEmit
cd ..
git -C recordly-upstream apply --check -R ../patches/recordly-android.patch
```

Name the two bridge test files explicitly: `node --test bridge/` fails on
Node 24 with `MODULE_NOT_FOUND`.

## Emulator and device verifiers

The app must be installed and running. Each verifier brings the task forward.
`dev/adb.mjs` talks to the existing ADB server because `adb.exe` cannot
initialize its per-user directory on this managed Windows host; use it rather
than raw `adb` where a script already does. Verifiers default to
`emulator-5554`; set `RECORDLY_DEVICE` for another serial.

| Command | Proves |
|---|---|
| `node dev/verify-autozoom.mjs` | Four Clock taps and a swipe produce four fitted zoom regions, one per tap, 200 ms lead, adaptive depth, a wide beat; no swipe region. Refuses non-emulators. |
| `node dev/verify-motion-export.mjs` | Exports the open take through Publish → Save to device and checks H.264, 60 fps, and frame-interval jitter < 0.1 ms. |
| `node dev/verify-capture.mjs` | Microphone take through notification pause/resume/stop; A/V delta ≤ 250 ms, audio ≥ 115 kbps, editor handoff. |
| `node dev/verify-telemetry.mjs` | Real Settings taps reach the sidecar as click/mouseup pairs. |
| `node dev/verify-editor.mjs` | Library listing, same-format append, wallpaper byte upload. |
| `node dev/verify-token.mjs` | Project media URLs persist without the loopback token and are re-authorized on load. |

`dev/webview.mjs state|eval|button|click` inspects the running WebView through
Playwright. `dev/verification/` receives artifacts and is intentionally ignored;
never commit its media.

## Hard rules

1. **Edit `bridge/bridge.js`, never the generated copy.** Then rerun
   `node scripts/build-renderer.mjs` (or `--skip-build`) so
   `android/app/src/main/assets/renderer/bridge-host/` matches. The parity
   gate byte-compares them.
2. **Renderer edits belong in `recordly-upstream/`.** Export them to
   `patches/recordly-android.patch` following `patches/README.md`, and keep the
   reverse check passing. Do not commit the upstream checkout.
3. **Unimplemented bridge methods fall back to the shim's neutral values.**
   Never answer with a guessed shape. Kotlin gaps are reported by parity, not
   failed; structural drift fails.
4. **Never commit generated or evidence files**: `android/app/src/main/assets/renderer/`,
   `dev/verification/`, `recordly-upstream/`, APKs, keystores.
5. **Do not change the approved motion without evidence.** Compare against the
   Windows reference and regenerate an export, then update
   `docs/MOTION-PARITY.md`.
6. **Licence.** The renderer is AGPL-3.0 (`LICENSE.md`). Any APK handed to
   another person carries the same obligations.

## Approved motion contract (do not drift)

- 200 ms lead before the tap; 100 ms wide beat between automatic visits in the
  same clip; at least 320 ms of hold after the last press.
- Focused preset: 200 ms in / 285.7 ms out, sampled from the shared spring by
  timestamp in both preview and export.
- Adaptive depth 1.25× / 1.5× / 1.8×; edge targets get the deeper crop.
- Compact blocks fit the ramps around a short hold (`fitTransitions: true`);
  existing zoom edits keep their boundaries.
- Camera motion blur 0.20, only while moving; blue `echo` tap pulse, 420 ms,
  `#2563EB`, `showCursor` false, idle hide off.
- Export: FHD (longest edge 1280 on the emulator, 1920 on a phone), Quality
  bitrate, 60 fps CFR.

The reference measurements and the verified outputs are in
`docs/MOTION-PARITY.md`.

## Architecture map

| Path | Role |
|---|---|
| `android/app/src/main/java/app/recordly/android/AppHost.kt` | Process-wide renderer install, media registry, loopback token and server |
| `.../MainActivity.kt` | Single WebView, insets, surfaces (`editor`, `hud-overlay`), file chooser, capture hooks |
| `.../NativeBridge.kt` | Native half of `window.electronAPI`; `dispatch`/`dispatchSync` are the seam |
| `.../CaptureSession.kt` | Capture state machine, consent, stop/fallback, events |
| `.../ScreenCaptureService.kt` | MediaProjection → MediaCodec → per-segment MP4, mic, pause, join |
| `.../TouchTelemetry.kt` | Accessibility clicks → `CursorTelemetryPoint` sidecar |
| `.../RecordlyServer.kt` | Loopback HTTP: renderer assets, Range media, export channel |
| `.../ProjectStore.kt` | Project CRUD, trash/relink, look migration |
| `.../TouchPresentation.kt` | Android editor policy: touch cursor, motion blur, pulse, frame look |
| `.../ExportStreams.kt` | Byte-channel export, integrity checks, MediaStore publish |
| `bridge/bridge.js`, `bridge/android.js` | Shim that builds `electronAPI` before React; transport |
| `scripts/build-renderer.mjs` | Upstream UI build → Android assets + bridge copy |
| `scripts/check-parity.mjs` | Inventory ↔ shim ↔ Kotlin drift gate |
| `patches/recordly-android.patch` | Every renderer change, against the pinned commit |

## Verified state (2026-11-21)

- Bridge: 9/9 tests pass; strict parity clean; 125/234 inventory methods
  implemented in Kotlin, 109 on documented fallback.
- Upstream: 118 focused unit tests pass; `tsc --noEmit` clean.
- Emulator: four-tap capture produced four regions; in-app export 576×1280,
  915 frames, constant 60 fps, peaks 1.50/1.25/1.50/1.50×.
- OnePlus Nord 5: in-app export 870×1920 H.264 High, 2,166 frames at constant
  60 fps; two measurable segments confirmed 1.8× and return to wide.

## Open work and known limits

- **SystemUI is withheld from third-party capture on the Nord 5.** Raw MP4s
  have a black status-bar strip, and the picture goes black while the
  notification shade is open. An explicit full-display MediaProjection request
  reproduced it; the device logs a full-size mirror with secure flags applied.
  OnePlus's built-in recorder is a privileged system app
  (`/product/priv-app/OplusScreenRecorder`). Do not chase a bypass: system
  privilege, hidden APIs, or an exploit are out of scope. Public workarounds
  only hide or freeze the blank frames; they do not recover the pixels.
  Documented in `docs/MOTION-PARITY.md`.
- Touch focus is the clicked view's centre, not the finger. Non-clickable
  surfaces produce no zoom. Observe-only accessibility is deliberate;
  `setMotionEventSources` would consume input from the recorded app.
- Imported clips have no Recordly telemetry, so zooms must be added manually.
- Appending recordings with different resolution or encoder settings needs a
  re-encode path; today it fails with a readable error.
- `recordings/.recordly-media/` scratch files and imported wallpapers are never
  swept.
- The build forces development flags (`vite.android.config.mjs`); replace them
  with a local permission gate before release.
- Model downloads, cloud, and auto-updates are intentionally absent.
- The Cursor settings panel still exposes a "show cursor" switch; the next
  save/reload coerces it off.
- Emulator ANRs during verification coincided with host memory pressure.

## Evidence

Artifacts are written to `dev/verification/` and are not tracked:
`autozoom-report.json`, `p0-final.mp4`, `p1-reliability.json`,
`oneplus-nord-5-motion-fhd60.mp4`, and `android-motion-parity.mp4`, plus the
source and export checks in `docs/MOTION-PARITY.md`. Regenerate rather than
commit them.

## Project knowledge outside the repo

OpenChamber notes, todos, and plans are stored by the app, not in this
repository. The one open item recorded there is the Nord 5 SystemUI capture
limitation described above; everything else lives in the docs listed at the
top.
