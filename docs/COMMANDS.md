# Commands

Everything runs from the repository root on Windows (PowerShell).

## Toolchain

- Android Studio JDK (the build script uses `JAVA_HOME`, else Android Studio's
  `jbr`).
- Android SDK at `%LOCALAPPDATA%\Android\Sdk`, with the
  `Medium_Phone_API_37.0` AVD.
- Node.js 20+.

## Build

```powershell
# Renderer bundle (also runs automatically from build-android.ps1 when stale)
node scripts/build-renderer.mjs

# Debug APK
powershell.exe -ExecutionPolicy Bypass -File scripts/build-android.ps1 -Offline

# Install and launch
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb install -r android/app/build/outputs/apk/debug/app-debug.apk
& $adb shell am start -n app.tapswoop.android.debug/app.recordly.android.MainActivity
```

## Emulator

```powershell
$emulator = "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe"
Start-Process $emulator -ArgumentList @(
  '-avd','Medium_Phone_API_37.0','-gpu','auto','-no-boot-anim',
  '-netdelay','none','-netspeed','full','-no-snapshot-save'
)
```

On a low-memory host use `-no-window`; the verifiers use `adb` and a forwarded
WebView DevTools socket, so a window is not required.

## Gates

```powershell
node --test bridge/bridge.test.mjs bridge/android.test.mjs
node scripts/check-parity.mjs --strict
cd recordly-upstream; .\node_modules\.bin\vitest run src/components/video-editor/touchZoomPolicy.test.ts src/components/video-editor/timeline/zoomSuggestionUtils.test.ts src/components/video-editor/videoPlayback/sceneMotion.test.ts src/components/video-editor/videoPlayback/zoomRegionTiming.test.ts src/components/video-editor/videoPlayback/cameraMotionBlur.test.ts src/components/video-editor/videoPlayback/previewFrameTexture.test.ts src/lib/exporter/zoomDuration.test.ts
cd recordly-upstream; .\node_modules\.bin\tsc --noEmit
```

`check-parity` compares `electronapi-inventory.csv`, `bridge/bridge.js`, and
`NativeBridge.kt`, and byte-compares the generated bridge assets.

`node dev/verify-cut-room-ui.mjs` checks the built Android renderer in Chrome
at 360, 390 and 430 px, using fixture media. It covers Home, library switching
and search, recording/import calls, project opening, playback, ruler and empty
Zoom-lane scrubbing, split/undo, explicit Add zoom, inline editing panels, and
export controls. Tool panels must replace only the timeline and its actions;
the tests verify an undimmed, playable preview, live padding and wallpaper edits,
and the stacked Wake sliders, with Padding and Corner radius visible on opening
Look. All five Look tabs must
fit at 360 px, and Advanced must reveal the padding-link switch without scrolling.
The editor header must fit one 44 px row without shrinking
Back, rename, or Export. It uses real touch events to check ruler and crop dragging
and continuous playhead tracking across a cut, in both directions, to the
timeline endpoints. It also checks a 320×390 viewport and the Android Back adapter
for nested crop, aspect ratio, rename, and export. Desktop Electron uses
the upstream layout.

`node dev/verify-onboarding-ui.mjs` runs the packaged renderer against the
first-run tour at 360, 390 and 430 px. It checks every page, the required
microphone and Tap focus setup (Start recording stays disabled until both are
ready), the disabled Explore/Start gate, no Skip or Escape dismissal,
completion, one-time persistence, Settings replay, Back, reduced motion and
dark mode.

`node dev/verify-cut-room-chrome.mjs` checks a running debug app with `ffmpeg`
and the WebView debugger. It opens Settings, verifies Android Back returns to
Home, and samples the real status and navigation bar backgrounds in light and
dark mode. It restores the app's original appearance preference. Set
`RECORDLY_DEVICE` to target a connected phone.

For editor visual checks, open a test project with zoom regions in the
**windowed** emulator and run `node dev/verify-cut-room-visual.mjs --record`.
It refuses an emulator without a visible Windows window, checks the timeline
during a finger drag, opens the aspect picker, Export and inline tools, and
saves native screenshots and a replay under `dev/verification/cut-room/visible/`.

## Emulator verifiers

The app must be installed and running. Each verifier brings the task forward.
They resolve the app process by exact name: `pidof <package>` can also return a
helper process carrying the package in its command line, such as
`media.swcodec` on OnePlus, whose WebView socket does not exist.

| Command | Asserts |
|---|---|
| `node dev/verify-autozoom.mjs` | 4 Clock keypad taps + 1 swipe → matching click targets and four saved, fitted zoom regions, 200 ms lead, adaptive depth, and a wide beat between visits |
| `node dev/verify-motion-export.mjs` | Exports the open auto-zoom test take through **Export → Save to device** and uses `ffprobe` to verify 60 fps frame timestamps |
| `node dev/verify-export-save.mjs dev/verification/phone-av-sync-fixture.mp4` | A failed save keeps the rendered bytes; retry publishes one byte-identical, visible gallery copy |
| `node dev/verify-capture.mjs` | microphone take through notification pause/resume/stop, A/V delta ≤ 250 ms, audio ≥ 115 kbps, editor handoff. Requires the overlay appop **denied** so the HUD surface is used; see below. |
| `node dev/verify-capture.mjs --interrupt` | Android revokes projection when a second app starts recording; editor opens the first take, and a new take asks for fresh consent and saves separately |
| `node dev/verify-telemetry.mjs` | real Settings taps reach the sidecar as click/mouseup pairs |
| `node dev/verify-editor.mjs` | library listing, same-format append, wallpaper byte upload |
| `node dev/verify-mixed-append.mjs` | mixed resolution, distinct encoder profile, different audio formats, silence, join timing, tap coordinate fit, and lossless compatible append |
| `node dev/verify-token.mjs` | project URLs persist without the loopback token and are re-authorized on load |

`dev/verification/` receives the artifacts; it is not tracked. The latest
results are summarized in `android/README.md` and `PORTING-PLAN.md` §18–19.

## Floating recording setup

`New recording` opens the **actual Sileo 0.1.6** component in a transparent
WebView overlay when **Display over other apps** is granted. The window is
placed at the **top** from its first frame; there is no bottom-to-top travel.
A static Sileo pill performs the only entrance, a top-anchored Dynamic Island
pop, then hands off to the real Sileo pill at identical geometry. It arrives
**compact**. Tapping the 40 px pill opens the body with a critically damped
600 ms Sileo spring (`bounce: 0`, no overshoot); 20 s without pointer or
keyboard activity folds it back to the pill.
The native window is only as large as the compact pill (380×128 px at 360 dp),
so taps beside it reach the app. Home's press starts the overlay while its own
reveal is still running, but that press does not expand the pill. Grant the
permission on a test device with:

```powershell
node dev/adb.mjs shell appops set app.tapswoop.android.debug SYSTEM_ALERT_WINDOW allow
```

The setup has Mic and Screen and the app's red New recording control. Tap the
whole 40 px pill to open the body; tap it again to fold it. A tap outside folds
it and still reaches the app underneath (`FLAG_WATCH_OUTSIDE_TOUCH`). System
**Back** or **Cancel** dismisses it. It is created only by Home's **New
recording**; leaving TapSwoop dismisses it, so relaunching never shows a stale
setup. Apps that hide non-system overlays, such as Settings, hide it while they
are in front. Start removes the overlay before consent. The notification owns
pause and stop, and stopping opens the editor. The overlay is intentionally not
on screen during capture: a secure overlay left a black pill in the raw MP4 on
API 37, and an ordinary overlay is recorded. To verify a take, record with the
notification and inspect a frame; `dev/verification/` holds the latest local
evidence.

`node dev/verify-recorder-ui.mjs` checks the packaged recorder page in Chromium
at 360, 390 and 430 px: compact arrival without auto-expansion, tap-to-expand
with the real Sileo spring, the 20 s idle fold and its interaction reset,
outside-tap event, Mic toggle, Record reveal, Cancel/Back, reduced motion and
phone containment. It is a renderer check; the native window, consent and
notification handoff need the emulator or a device.

The HUD verifiers (`verify-autozoom`, `verify-capture`, `verify-telemetry` and
the other HUD flows) expect the overlay appop denied:

```powershell
node dev/adb.mjs shell appops set app.tapswoop.android.debug SYSTEM_ALERT_WINDOW deny
```

The interruption verifier also needs the [standalone probe APK](../dev/systemui-repro/)
installed on the emulator. It uses the probe only to start a second
system-consented projection. Both capture verifier modes refuse physical devices.
On a fresh install, select **Default microphone** and dismiss Audio settings
before running the microphone verifier.

## Real-device microphone sync

Use a short, non-sensitive take. The operator approves microphone and
entire-screen capture on the device; do not use the emulator verifier to
answer those dialogs on a phone.

```powershell
node dev/verify-av-sync.mjs fixture dev/verification/phone-av-sync-fixture.mp4
# Play the fixture on the phone and record it with Recordly's microphone.
node dev/verify-av-sync.mjs measure dev/verification/phone-av-sync-recording.mp4
```

Open the fixture at the start in a media player. Use the phone speaker at a
comfortable volume, not headphones, and leave the center of the video clear.
With Recordly capturing, let at least two flash/tone pairs play, pause
Recordly for a few seconds via its notification, then resume and capture
at least two more pairs. Stop via the notification. Verify pause, resume,
stop, and editor handoff separately and measure the original take, not an
editor export. Check that matching pairs fall on both sides of the join.
Use a separate take for testing Android-initiated projection stop and
retry with fresh consent.

`measure` reports audio-minus-video marker offsets and track-end skew. It
rejects offsets or skew above 250 ms and reports a take with too few
markers as inconclusive. This measures end-to-end sync, including the
phone player's screen and speaker timing; it does not isolate encoder
latency. Keep the original take and any verification artifacts local.

## Renderer patches

`recordly-upstream/` is pinned at `20e9490` (2.0.0-beta.1). Its changes are kept
in `patches/recordly-android.patch`:

```powershell
git -C recordly-upstream apply ../patches/recordly-android.patch
```

See `patches/README.md` for regenerating the patch.

## Notes

- Export requires Android System WebView 130+ (`VideoEncoder` is on by default).
- Enable the accessibility service for tap telemetry; without it the recording
  has no zoom regions.
- On Android 14+ the app requests the default display directly
  (`MediaProjectionConfig.createConfigForDefaultDisplay()`), so the system
  shows a single share confirmation rather than the app/entire-screen chooser.
  Older versions use the legacy chooser. The auto-zoom verifier accepts either
  sheet.
- The emulator's H.264 encoder is 720p-class; the app defaults to HD there and
  FHD on real devices.
- `node --test bridge/` fails on Node 24 with `MODULE_NOT_FOUND`; name the two
  test files explicitly, as above.
