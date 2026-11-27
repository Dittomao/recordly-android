# TapSwoop

A hybrid port of [Recordly](https://recordly.app) — the open-source screen
recorder and editor — to Android. The upstream React/PixiJS/WebCodecs renderer
runs inside a WebView; Kotlin supplies the `window.electronAPI` bridge, screen
capture, tap telemetry, project storage, a loopback media server, and export.

> The porting strategy is: **do not port the app, port the bridge.** The
> renderer keeps Recordly's floating-frame look and auto-zoom; Android replaces
> the desktop host.

## Layout

| Path | Contents |
|---|---|
| `android/` | Kotlin host, Gradle project, generated renderer assets |
| `bridge/` | `android.js` transport and the `bridge.js` shim that builds `window.electronAPI` |
| `recordly-upstream/` | pinned upstream checkout (own git repo, not tracked here) |
| `scripts/` | renderer/APK build and parity checks |
| `dev/` | emulator verifiers and inspection tools |
| `patches/` | renderer changes as a patch against the pinned upstream commit |
| `PORTING-PLAN.md` | full bridge inventory, decisions, and history |
| `AGENTS.md` | handoff: fresh checkout, gates, rules, known limits |
| `LICENSE.md` | AGPL-3.0, inherited from the Recordly renderer |

## Build

Requirements: Android Studio with its bundled JDK, Android SDK platform 35,
Node.js 20+, and a WebView 130+ device or emulator for export.

```sh
node scripts/build-renderer.mjs          # optional; the APK build rebuilds and repackages when stale
powershell.exe -ExecutionPolicy Bypass -File scripts/build-android.ps1 -Offline
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

The debug APK uses `app.tapswoop.android.debug`. It installs separately from
older APKs and does not move their private data. See `docs/COMMANDS.md` for verification or
[`docs/PLAY-LISTING.md`](docs/PLAY-LISTING.md) for the store listing draft.

## Core behavior

- **Cut Room**: searchable Projects and Recordings on Home; a full-width
  preview, labeled tracks, and contextual editing tools in the Editor.
- **First-run tour**: four TapSwoop pages cover recording, tap focus, the Cut
  Room and setup. Its Start recording action unlocks only after Tap focus and
  the microphone are ready; screen sharing is required on every take. It shows
  once, respects Reduce Motion and the theme, and can be replayed from
  Settings → Dev. There is no Skip: **Explore library** and **Start recording**
  stay disabled until all three rows are complete.
- **Capture**: MediaProjection → MediaCodec H.264 → per-segment MP4 → sample-level
  join, with AAC microphone audio and pause/resume.
- **Touch telemetry**: accessibility click bounds become `CursorTelemetryPoint`
  samples in `<video>.cursor.json`; no cursor is ever drawn.
- **Auto-zoom**: a 200 ms lead and adaptive 1.25×/1.5×/1.8× depth for spaced
  taps. Close visits get the same spring shape fitted into shorter blocks with
  a wide beat between them. The 60 fps CFR export includes a brief touch pulse
  and light camera-motion blur. See [Motion parity](docs/MOTION-PARITY.md).
- **Composition**: captured screen inset on a wallpaper at ~86% width with
  rounded corners and shadow, matching the Windows reference.
- **Storage**: atomic project/metadata writes, byte-channel export into
  `media/exports`, MediaStore publication.

## Verification

```sh
node --test bridge/bridge.test.mjs bridge/android.test.mjs
node scripts/check-parity.mjs --strict
node dev/verify-cut-room-ui.mjs
node dev/verify-onboarding-ui.mjs
node dev/verify-autozoom.mjs
node dev/verify-capture.mjs
node dev/verify-telemetry.mjs
node dev/verify-editor.mjs
node dev/verify-token.mjs
```

`dev/verification/` holds the artifacts and is not tracked. The current state,
limits, and evidence are documented in `android/README.md`, `PORTING-PLAN.md`
§18–19, and `docs/COMMANDS.md`.

## Constraints

- Touch focus is the tapped view's centre, not the finger; non-clickable
  surfaces cannot produce a zoom without consuming input from the recorded app.
- The emulator's WebCodecs encoder is 720p-class; real devices default to FHD.
- Android captures the microphone, not system audio.
- The system consent dialog defaults to "Share one app"; choose **Share entire
  screen → Share screen** for full-screen capture.
- AGPL-3.0 obligations still apply to any distributed APK.
