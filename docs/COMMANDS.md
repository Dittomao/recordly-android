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
& $adb shell am start -n app.recordly.android.debug/app.recordly.android.MainActivity
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
cd recordly-upstream; .\node_modules\.bin\vitest run src/components/video-editor/touchZoomPolicy.test.ts src/components/video-editor/timeline/zoomSuggestionUtils.test.ts
cd recordly-upstream; .\node_modules\.bin\tsc --noEmit
```

`check-parity` compares `electronapi-inventory.csv`, `bridge/bridge.js`, and
`NativeBridge.kt`, and byte-compares the generated bridge assets.

## Emulator verifiers

The app must be installed and running. Each verifier brings the task forward.

| Command | Asserts |
|---|---|
| `node dev/verify-autozoom.mjs` | 4 Clock keypad taps + 1 swipe → matching click targets and four saved, fitted zoom regions, 200 ms lead, adaptive depth, and a wide beat between visits |
| `node dev/verify-motion-export.mjs` | Exports the open auto-zoom test take through **Publish → Save to device** and uses `ffprobe` to verify 60 fps frame timestamps |
| `node dev/verify-capture.mjs` | microphone take through notification pause/resume/stop, A/V delta ≤ 250 ms, audio ≥ 115 kbps, editor handoff |
| `node dev/verify-telemetry.mjs` | real Settings taps reach the sidecar as click/mouseup pairs |
| `node dev/verify-editor.mjs` | library listing, same-format append, wallpaper byte upload |
| `node dev/verify-token.mjs` | project URLs persist without the loopback token and are re-authorized on load |

`dev/verification/` receives the artifacts; it is not tracked. The latest
results are summarized in `android/README.md` and `PORTING-PLAN.md` §18–19.

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
