# Recordly → Android — Porting Plan

**Subject:** `webadderallorg/Recordly` @ `2.0.0-beta.1`, commit `20e9490` (2026-11-15)
**Date:** 2026-11-16
**Scope:** architecture map, porting strategy, and a classified inventory of every bridge method.
**Companion file:** [`electronapi-inventory.csv`](./electronapi-inventory.csv) — all 234 methods, machine-readable.

**Decisions locked (2026-11-16)**

- **Distribution:** sideload / direct APK for now. Play policy no longer constrains the telemetry design.
- **Auto-zoom:** touch-driven auto-zoom is required for v1. This promotes telemetry to the critical path and makes §4.3 the first thing to prove, not the last.
- **Consequence:** AGPL still binds any build you hand to another person (§9). Building only for yourself does not.

---

## 0. Verdict

**You are not porting an app. You are porting a 234-method bridge.**

Recordly's renderer is a normal React + PixiJS + WebCodecs web app that happens to run inside Electron. It reaches the OS through exactly one object, `window.electronAPI`. Replace that object, keep the renderer, and ~68% of the codebase comes across untouched.

| | Lines | Fate |
|---|---|---|
| `src/` renderer (React, PixiJS, exporters) | ~149,000 | **ships as-is** in a WebView |
| `electron/` desktop main process | ~71,000 | replaced by ~5–8k lines of Kotlin |
| `electron/native/` C++ helpers | 8 modules | thrown away |

Two facts make this cheap:

1. **The renderer already runs without Electron** — upstream ships that mode and tests it 245 times over. `RECORDLY_RENDERER_ONLY=1` skips `vite-plugin-electron`; the UI suite runs with "Neither starts Electron." It is *not* bridgeless, though: with `electronAPI` absent the editor crashes into its error boundary, so a shim is mandatory from day one. The good news is that upstream's own stub (`tests/ui/bridge.ts`, 103 methods) is the proven minimum — built and measured, see §4.1.
2. **Export has a pure-browser route that is already the default for unknown platforms.** `backendPolicy.ts:planLightningExportRoutes` falls through to `webcodecs` for any platform it doesn't recognise — and that route does its encoding with `VideoEncoder`/`AudioEncoder`, demuxes with a 3.1 MB wasm file, and muxes MP4 with mediabunny. No ffmpeg, no native encoder.

**Reverse engineering: not needed.** Source is public under AGPL-3.0. The only binaries are desktop capture helpers that have no Android equivalent anyway.

---

## 1. Measured baseline

```
recordly-upstream/          2,486 files · 247 MB with .git
├── src/                    1,367 files
│   ├── components/          97,975 lines   editor UI, timeline, panels
│   ├── lib/                 42,849 lines   exporters, auth, settings
│   ├── hooks/                7,819 lines
│   └── assets/cursors/      8 style packs, bundled
├── electron/                71,328 lines   main process + IPC + native build scripts
│   └── native/               C++ : WGC capture, DXGI, MF encoder, WASAPI, CUDA compositor
├── public/                  67 MB           48 MB wallpapers · 12 MB auth art · 3.1 MB wasm
└── services/                share pages, supabase edge functions
```

Key dependencies, sorted by portability:

| Dependency | Role | Android |
|---|---|---|
| `pixi.js`, `pixi-filters` | frame compositing, zoom, cursor | **runs as-is** (WebGL in WebView) |
| WebCodecs (`VideoEncoder`, `AudioEncoder`, 40 files) | export encode | **runs as-is** (WebView ≥ 94) |
| `mediabunny`, `mp4box` | MP4 muxing in JS | **runs as-is** |
| `web-demuxer.wasm` | source demux | **runs as-is** |
| `react`, `@heroui`, `motion`, `dnd-timeline` | UI | **runs as-is** |
| `ffmpeg-static`, `ffprobe-static` | legacy capture/export, gif optimize | replace: `MediaCodec`, `MediaMetadataRetriever` |
| `uiohook-napi` | global cursor hooks | **replace: touch capture** (see §4.3) |
| `capturekit` | macOS ScreenCaptureKit | replace: `MediaProjection` |
| `sherpa-onnx-node` | speech models | sherpa-onnx Android AAR (v2) |
| `electron-updater` | updates | **drop** — sideload means manual reinstall |

Only **3** files under `src/` import `electron` or `node:*` — and all three are test files.

---

## 2. The seam: 234 methods

`electron/preload.ts` is 1,335 lines and exposes **234 top-level methods** over 228 distinct IPC channels via `contextBridge.exposeInMainWorld("electronAPI", {...})` at `electron/preload.ts:189`.

The renderer calls into it **538 times**. The 26 event channels (`onX` via `ipcRenderer.on`) take a callback and return an unsubscribe function — your shim must preserve that exact contract, because callers store and invoke the returned cleanup.

Classification (full tables in Appendix B):

| Category | Count | Meaning |
|---|---:|---|
| **CORE** | 13 | Real native work: capture, encoder lifecycle, telemetry, interruption |
| **ADAPTER** | 103 | Thin Kotlin: state, prefs, file IO, intents, navigation |
| **WEB** | 5 | Byte sink → the renderer does everything else |
| **STUB** | 14 | Return neutral values; feature degrades gracefully |
| **DROP** | 64 | Desktop shell; delete from the fork |
| **V2** | 35 | Cloud, captions, floating overlay — defer |

Read that ratio carefully. **13 methods are the port.** 103 are boilerplate you can bang out in a week. 64 are deletions, and deletions are free.

**You do not need all 234 to boot.** Measured against upstream's own test stub: the editor renders and plays a clip with **103 methods implemented as stubs and zero real natives** (`tests/ui/bridge.ts`). Treat 234 as the eventual ceiling, not the starting line — the first milestone is 103 stubs.

---

## 3. Architecture for the port

Do **not** rewrite the editor in Compose. Do **not** use `file://`. Replicate upstream's own serving model, which is already web-correct:

```
┌─────────────────────────── Android app (single Activity) ───────────────────────────┐
│                                                                                      │
│   WebView  ──https/http──▶  Embedded HTTP server  127.0.0.1:<ephemeral>              │
│   (renderer)                 ├─ /                → assets/renderer/*.js,*.css        │
│      │                       └─ /media/<path>    → Range requests + HMAC capability  │
│      │                                                                               │
│      │  window.electronAPI (injected shim)                                           │
│      ▼                                                                               │
│   EventBus ◀── @JavascriptInterface ──▶ ElectronBridge.kt ──┬─ CaptureService (FGS)  │
│                                                              ├─ Recorder (MediaCodec)│
│                                                              ├─ TouchCapture          │
│                                                              ├─ ProjectStore          │
│                                                              └─ ExportSink (SAF)      │
└──────────────────────────────────────────────────────────────────────────────────────┘
```

**Why a localhost HTTP server and not `WebViewAssetLoader`:** upstream already ships `electron/rendererServer.ts` and `electron/mediaServer.ts`, which serve *both* the renderer and media from `http://127.0.0.1:<port>` with per-file HMAC capability tokens and byte-range support. Porting them keeps the page same-origin with its media — no CORS, no mixed-content blocking, no `file://` restrictions. Chrome treats loopback as a secure context, so WebCodecs, `crypto.subtle`, and service workers all work. This is roughly 400 lines of Kotlin over `ServerSocket` (or NanoHTTPD/Ktor).

**The bridge shim** must be installed *before* the React bundle runs, so inject it in `index.html` or as the first `<script>`:

```js
window.electronAPI = buildBridge(window.__recordlyNative) // @JavascriptInterface host
```

Keep the 234 method names and signatures **identical** so upstream `src/` stays untouched. That is what makes upstream merges possible later (§8).

WebView settings that matter: `javaScriptEnabled`, `domStorageEnabled`, `mediaPlaybackRequiresUserGesture = false`, hardware acceleration on (default). Do **not** enable `allowFileAccess`.

---

## 4. Subsystem by subsystem

### 4.1 Host shell and asset serving — low risk

**You do not need to write a Vite config.** Upstream already ships a renderer-only mode: `vite.config.ts` skips `vite-plugin-electron` entirely when `mode === "ui"` or `RECORDLY_RENDERER_ONLY === "1"`, and `npm run dev:ui` is the supported entry point. Measured on commit `20e9490`:

```
RECORDLY_RENDERER_ONLY=1 npx vite build --mode ui   →  dist/, 2m12s, no Electron spawned
```

If you want a different `outDir` for `assets/renderer/`, a thin wrapper config that sets only that is enough. Do not fork `vite.config.ts` — you'll be maintaining it forever (§8).

**This path is maintained, not incidental.** 245 Playwright UI specs run against it with `RECORDLY_RENDERER_ONLY: "1"`, and the comment in `playwright.config.ts` says "Neither starts Electron."

**But renderer-only is not bridgeless.** Measured: with `window.electronAPI` absent, the editor throws in a `useState` initializer —

```
TypeError: Cannot read properties of undefined (reading 'getAppSetting')
```

— and lands on the "Recordly ran into a problem" error boundary. The `try/catch` blocks in `index.html:11-34` protect the boot markup only. **A shim must exist before the React bundle runs**, which the §3 injection point already assumes.

**Port upstream's own stub first.** `tests/ui/bridge.ts` is 376 lines implementing 103 methods — a working, tested reference for exactly how much bridge the editor needs. With it injected, the editor boots clean: 2 canvases, full chrome, zero console errors. That is your Kotlin bridge's first milestone, and it is 103 methods, not 234.

**Free test assets.** `tests/ui/fixtures/preview.mp4`, `filmstrip.mp4`, `background.webm` give you a first project and a background without sourcing anything.

- The renderer is a multi-window app (`?windowType=editor|hud-overlay|capture-picker|countdown|onboarding|...`). On Android you only need **`editor`**. The rest map to activities, overlays, or nothing.
- `getAssetBasePath` → `https://…/` from your server; `listAssetDirectory` → walk `assets/`.
- **Trim the payload.** `public/wallpapers` is 48 MB and `public/auth` is 12 MB. Ship ~8 wallpapers instead of the full pack or you will ship a 100 MB APK. Measured bundle: the editor chunk alone is 1.89 MB (560 KB gzip), plus 1.44 MB for the shell and 87 KB for the video-processing chunk.

### 4.2 Capture — medium risk, well-trodden

`MediaProjection` → `VirtualDisplay` → `MediaCodec` (H.264, `COLOR_FormatSurface`) → `MediaMuxer` (MP4) is a standard recipe with a decade of sample code. What upstream's helpers (WGC / ScreenCaptureKit / Media Foundation) do in C++ you get from the platform.

Implement these 13 CORE methods:

| Method | Android implementation |
|---|---|
| `startNativeScreenRecording` | consent → FGS → `VirtualDisplay` + `MediaCodec` surface input |
| `stopNativeScreenRecording` | signal EOS, drain, `muxer.stop()` |
| `pause/resumeNativeScreenRecording` | pause muxer presentation clock |
| `recoverNativeScreenRecording` | finalize a left-over `.mp4` on cold start |
| `reserveRecordingSession` | single-owner lock in the foreground service |
| `onRecordingInterrupted` | `onStop` / audio focus loss / screen off |
| `get/setCursorTelemetry` | read/write `<video>.cursor.json` |
| `pause/resumeCursorCapture` | gate the telemetry sampler |
| `getAccessibilityPermissionStatus`, `requestAccessibilityPermission` | see §4.3 |

Android-specific gotchas that are not in the desktop code:

- A foreground service with `foregroundServiceType="mediaProjection"` is **mandatory**, and on Android 14+ it must start *after* consent is granted, with `FOREGROUND_SERVICE_MEDIA_PROJECTION` declared.
- Consent is granted **per session**. There is no persistent screen-recording permission. Do not model this as a boolean preference.
- Android 14 lets the user pick "single app" or "entire screen" at consent time. Your `getSources` adapter can collapse this to one synthetic "Entire screen" entry and let the OS ask.
- **System audio is not generally available.** `AudioPlaybackCapture` (API 29+) only captures apps that opt in via `allowAudioPlaybackCapture`; most media apps opt out. Microphone capture is fine. Plan for mic-only in v1 and label system audio honestly.
- `FLAG_SECURE` windows (banking, DRM) capture as black. You cannot fix this.

### 4.3 Touch telemetry — verified, and it is not what it looks like

Upstream's headline feature is auto-zoom driven by cursor motion. `uiohook` plus a 133-line C++ monitor sample global pointer position, and the samples persist as a JSON sidecar next to the video:

```
recordings/demo.mp4
recordings/demo.mp4.cursor.json    ← electron/ipc/utils.ts:67
```

The schema is `CursorTelemetryPoint { timeMs, cx, cy, pressure?, interactionType?, mouseButton?, cursorType? }` (`src/components/video-editor/types.ts:21`). `cx`/`cy` are normalised 0–1 and `interactionType` already covers taps, so **the data model ports cleanly**. Producing the data is the whole problem.

#### The API that looks right and isn't

Android 14 (API 34) added `AccessibilityService.setMotionEventSources(int)` + `onMotionEvent(MotionEvent)` — system-wide motion events, gated by `android:canRequestMotionEvents` / `CAPABILITY_CAN_REQUEST_MOTION_EVENTS`. It reads like the answer. It is a trap:

> "MotionEvents from sources in `getMotionEventSources()` **are not sent to the rest of the system**."
> — AOSP `AccessibilityService.java` (API 34), `onMotionEvent` Javadoc

Registering a source **consumes** those events. Register `SOURCE_TOUCHSCREEN` and the app you are demoing stops receiving taps. It is an input-takeover API for switch and stylus access, not an observation API. `SOURCE_TOUCHSCREEN` is not even in the AOSP `@MotionEventSources` IntDef, which lists mouse, stylus, trackball, touchpad, joystick, rotary encoder and sensor.

#### What actually works: observe UI events, not input

Accessibility *event* callbacks are notifications about UI changes. They arrive alongside normal app input, so they observe without consuming, and they need no touch-exploration mode. For a mobile demo the semantically right signal is not the finger track anyway — it is **the thing that was tapped**.

| Signal | What you get | Input consumed |
|---|---|---|
| `TYPE_VIEW_CLICKED` | `event.getSource().getBoundsInScreen()` — the tapped view's rect; centre it for `cx`/`cy` | none |
| `TYPE_VIEW_LONG_CLICKED` | long-press location | none |
| `TYPE_VIEW_SCROLLED` | scroll signal + scrollable node bounds | none |
| `TYPE_WINDOW_STATE_CHANGED` | screen transitions, useful for scene awareness | none |

That is enough to drive `detectInteractionCandidates` / `detectZoomDwellCandidates` (`src/components/video-editor/timeline/`) unchanged: a tap becomes a `CursorTelemetryPoint` with `interactionType: "click"` and `cx`/`cy` from the node-bounds centre.

Limits, stated plainly:

- You get **view bounds, not finger coordinates**. For a list row or a button that is arguably better framing; for a pinch or a drag it is nothing.
- No continuous motion ⇒ **cursor smoothing, motion blur and sway have no input** and become inert on Android. Click bounce and zoom-on-tap survive, and they are the ones that matter on touch.
- No events from surfaces without accessibility nodes: games, canvas/WebGL, some Flutter and Compose trees. Chrome, Settings and ordinary View-based apps do emit them.
- Requires the user to enable the service in Settings. One-time, but it is a frightening screen — onboard it explicitly.

#### Fallback for node-less apps: "Show taps"

Developer Options → *Show taps* draws a system indicator on every touch, and `MediaProjection` captures it. A post-recording pass over downscaled frames can detect the indicator and synthesise telemetry for apps that emit no accessibility events. It needs no special permission and works wherever anything is drawn. Cost: a decode pass over the recording, taps only, no drags. Prototype it as the second tier.

#### One candidate left open

`TouchInteractionController` (API 33) is the documented route for touchscreen events and, in `STATE_DELEGATING`, may let a service observe a gesture while the system still performs it. Whether it activates without touch exploration is not documented clearly enough to plan on. **Treat it as an experiment inside the spike, never as a dependency.**

#### Measured on device (2026-11-16) — verdict: **go**

Run on the API 37 emulator (`app.recordly.android.debug`, `TelemetryAccessibilityService`). Every claim below is from logcat, from samples read back through `getCursorTelemetry`, or from the saved project JSON — not from documentation.

| Check | Result |
|---|---|
| `TYPE_VIEW_CLICKED` from our own WebView | yes, `bounds=Rect(370, 462 - 504, 561)` |
| ... from an ordinary View-based app (DeskClock) | yes, `Rect(1008, 849 - 1392, 1017) text=[Timers]` |
| ... from SystemUI (notification shade) | yes, attributed to `com.google.android.gms`, `Rect(1914, 488 - 2066, 620)` |
| ... from Settings (RecyclerView + Compose homepage) | yes, `Rect(0, 544 - 2400, 736) text=[Network & internet]` |
| ... from a *different package* at all | yes — no package filter is applied |
| `TYPE_VIEW_SCROLLED` | yes, `androidx.recyclerview.widget.RecyclerView` |
| `TYPE_WINDOW_STATE_CHANGED` | yes, every app tested |
| `TYPE_TOUCH_INTERACTION_START` / `_END` | **never delivered once**, for any package, while the service was enabled |

That last row is the one that changes the design: a recorder that waits for a release event to close a tap records **nothing**. The click event is the tap. The release is either observed (when the system offers it) or synthesized 220 ms later with the click as the release time and an 80 ms back-dated press — both arrive as a `click`+`mouseup` pair with `mouseButton: 1`, which is exactly what `getCursorPressIntervals` expects. Taps are never dropped for want of a release.

End-to-end proof, with real footage: `screenrecord` of DeskClock (ordinary app) while tapping the timers keypad, telemetry from the same window, clip origin derived from the recording itself (two tab switches land on video frames 2.090 s and 4.356 s; both solve to a clip start of 301481 ms, 3 ms apart). Loading the footage with its sidecar in the editor produced:

```
"startMs": 1602, "endMs": 13579, "depth": 3, "mode": "auto",
"focus": { "cx": 0.66, "cy": 0.8638 }
```

`cx 0.66 / cy 0.8638` is the exact centre of the Stopwatch tab that was tapped; `1602…13579` is the first click − 500 ms to the last click + 500 ms. The documented definition of done is met.

Two behaviours worth knowing before writing the capture service:

- **Clustering is upstream's, and it is aggressive.** Clicks less than `CLICK_CLUSTER_MERGE_GAP_MS` (2500 ms) apart collapse into one zoom region anchored on the strongest (here: first) click. Seven taps at 1.5 s intervals produced *one* region focused on the first tap. That is Recordly behaving as designed, but it means a demo that taps rapidly gets one wide zoom, not one per tap.
- **The focus is the view's centre, not the finger.** For a row or a key that is the better framing; for a tap on a large empty surface it can be far from the finger.

Not tested / still open:

- A real finger on real hardware. Every tap here was `adb shell input tap`, which produces genuine app-level click events but no `TYPE_TOUCH_INTERACTION_*`; whether a physical touch produces those is unknown and does not matter, because the recorder no longer depends on them.
- Node-less surfaces (games, canvas/WebGL, some Flutter). None on this image to test; the tier-2 fallback stays a real requirement.
- A server-side fix landed during the spike: the framework **unbinds and unregisters** the service when the app is force-stopped, and reinstalling clears `enabled_accessibility_services`. Any test that force-stops the app must re-enable the service first, or it will silently observe nothing.

#### What this means for v1

Ship **tap-driven auto-zoom**, not cursor-driven. Keep manual zoom regions as the universal fallback, and label the feature honestly on Android ("zoom on tap"). If the `TouchInteractionController` experiment succeeds, add a continuous-track mode later behind the same telemetry schema — the renderer needs no changes either way.

### 4.4 The editor — free

Timeline, zoom regions, cursor styling (8 bundled packs), click effects, webcam PiP, wallpaper/background, annotations, speed regions, trim, crop, captions UI, i18n (locales included), keyboard shortcuts, project dashboard. None of it is Electron-specific. Expect to spend your time on **layout**, not logic: a 1440-px desktop timeline does not fit a 6-inch screen. Budget real design work for touch targets and panel collapse — the renderer is responsive-ish but not mobile-designed.

### 4.5 Export — low risk, one performance question

Force the route. `planLightningExportRoutes` already selects `webcodecs` for an unrecognised platform, and `getPlatform` returning `"android"` normalises to `"unknown"` (`backendPolicy.ts:normalizeLightningRuntimePlatform`). Belt and braces: also set the backend preference to `webcodecs` in `getNativeExportCapabilities` and do not implement the `nativeVideoExport*` methods at all — their call sites are all guarded by `typeof window.electronAPI?.nativeVideoExportStart === "function"`.

The pipeline, entirely in the renderer:

```
source video ──web-demuxer.wasm──▶ VideoDecoder ─┐
                                                 ├─▶ Pixi scene composite ──▶ VideoEncoder (H.264)
cursor json ──▶ zoom/cursor motion ──────────────┘                                  │
                                                                                    ▼
source audio ──AudioDecoder──▶ AudioEncoder (AAC) ──────────────────────────▶ mediabunny MP4 muxer
                                                                                    │
                                                        openExportStream / writeExportStreamChunk
                                                                                    ▼
                                                                            temp file → SAF save
```

`export-stream-*` is a positional byte sink (16 MiB chunks, `src/lib/exporter/muxer.ts:19`). On Android: open a temp file in `cacheDir`, `RandomAccessFile.seek(position)` + write, close, then hand to `MediaStore` or a SAF `Uri`.

**The open question is throughput.** `VideoEncoder` in Android WebView goes to the platform encoder, and Pixi composites on the GPU, so this should be fast — but compositing 1080p60 through a WebView on a mid-range SoC is not something to assume. Measure before you promise. Fallbacks, in order: cap default export at 1080p30; expose a bitrate/fps control (already in the UI); only then consider a native `MediaCodec` compositor.

### 4.6 Storage and projects — low risk, high tedium

`.recordly` is JSON: `{ version, projectId?, videoPath, editor: {...} }` validated at `src/components/video-editor/projectPersistence.ts:54`. Mirror upstream's directory layout in app-private storage:

```
files/recordings/         captures + *.cursor.json sidecars + *.audio sidecars
files/projects/           *.recordly + thumbnails
files/.recordly-media/    transcode scratch
```

`saveProjectFile` uses atomic temp+rename upstream (`electron/ipc/project/atomicSave.ts`) — do the same. Only touch SAF at the boundary: import, export, and relink. `relinkProjectRecording` exists precisely because desktop `.recordly` files carry absolute paths, which means **desktop project files can open on Android after a relink** — a genuinely nice feature, and cheap.

**`videoPath` is treated as a URL, not a filesystem path.** Upstream's own test bridge returns `videoPath: "http://127.0.0.1:5179/tests/ui/fixtures/filmstrip.mp4"` and the editor loads it directly. That confirms the §3 server design: the Kotlin bridge never hands the renderer a file path — it returns `http://127.0.0.1:<port>/media/...`, keeping the page same-origin with its media and avoiding `file://` CORS. Byte-range support is what makes seeking work, so the server has to implement it.

---

## 5. What cannot be ported 1:1

Say these out loud before you start, so nobody is surprised at demo time:

1. **Continuous cursor motion.** No pointer track — tap locations only, from accessibility events (§4.3). Auto-zoom is still in v1; it zooms on taps, not on a cursor.
2. **System audio.** Opt-in apps only, user consent required (§4.2).
3. **Window/source picking.** Android records the display or one app, chosen by the OS dialog.
4. **Silent or background recording.** Consent per session + persistent notification.
5. **Floating HUD with click-through.** Possible via `SYSTEM_ALERT_WINDOW` but a permission maze; notification actions are the honest v1.
6. **`FLAG_SECURE` content.** Black frames, unavoidable.
7. **Webcam PiP.** `CameraX` into a second `MediaCodec` track — feasible, but it's a new capture surface, not a port.
8. **Captions.** sherpa-onnx ships Android artifacts, but the desktop code shells out to a binary; this is a rewrite of ~14 methods. V2.
9. **Cloud share / billing.** HTTP + Play Billing; keep the server, replace the client. V2.

---

## 6. Risk register

| # | Risk | Severity | Mitigation |
|---|---|---|---|
| 1 | Accessibility events are thin or absent on node-less surfaces (games, canvas, some Compose/Flutter) | **Critical** | Telemetry spike is P1, before any other work; "Show taps" pixel detection as tier 2; manual zoom always available |
| 2 | Onboarding friction: accessibility service must be enabled by hand | High | Explain in-app before deep-linking; recording works without it; degrade to manual zoom |
| 3 | WebView export throughput on mid-range devices | High | Measure in P4; cap 1080p30; native compositor as escape hatch |
| 4 | Desktop timeline UX doesn't fit a phone | High | Budget design time; don't assume "responsive" means "usable" |
| 5 | AGPL forces source disclosure of your whole app | High | It does, on any build you hand out — including a sideloaded APK (§9). Personal-use-only builds are unconstrained |
| 6 | WebCodecs/AAC encode differences across OEMs | Medium | Feature-detect with `isConfigSupported`; the code already does this |
| 7 | APK size (48 MB wallpapers + 3 MB wasm + renderer) | Medium | Trim asset packs; split per ABI (no App Bundle path when sideloading) |
| 8 | Upstream is on `2.0.0-beta.1` and moving | Medium | Isolate all edits; expect churn (§8) |
| 9 | AccessibilityService feels invasive to users / OEMs may kill it | Medium | Request only what's needed; no `canRetrieveWindowContent` unless required; rebind on service death; never gate recording on it |

---

## 7. Build plan

Rough ranges for one experienced Android developer, not a commitment. The order below is not the order of feature value — telemetry is first because it is the go/no-go.

### P0 — Boot the editor (3–5 days, **partly verified**)
Already done on this machine: renderer builds standalone (`RECORDLY_RENDERER_ONLY=1 npx vite build --mode ui`, 2m12s) and boots in Chrome with a stub bridge. Remaining: port `tests/ui/bridge.ts` (103 methods) into a JS shim, serve `dist/` over a Kotlin localhost server, load it in a WebView with the fixture MP4.
**Done when:** the timeline renders and plays a sample clip with a background applied, with no Kotlin implementation behind the 103 stub methods.
**Not** a condition: booting with no bridge at all. That crashes (§4.1).

### P1 — Telemetry spike (1 week, **go/no-go**)
AccessibilityService emitting `TYPE_VIEW_CLICKED` bounds → `CursorTelemetryPoint` → a sidecar the existing editor already reads. Also in the spike: the `TouchInteractionController` experiment, and a dry run of "Show taps" pixel detection on 30 s of footage.
**Done when:** you have a tap-driven auto-zoom working on footage from an ordinary app, *or* you have written down exactly what does not fire and are deciding between tier 2 and manual-zoom-only. Do not move past this phase without an answer.

### P2 — Capture (2–3 weeks)
MediaProjection + FGS + MediaCodec/MediaMuxer · consent flow · notification controls · crash recovery.
**Done when:** record 60 s of the device, get a playable MP4 in the library.

### P3 — Integration (1 week)
Wire P1 and P2 into the bridge properly: session lock, pause/resume across capture and telemetry, sidecar written adjacent to the MP4.
**Done when:** one press of record produces a video and its telemetry that open together in the editor.

### P4 — Export (1–2 weeks)
`export-stream-*` sink · SAF save · throughput measurement and tuning.
**Done when:** 30 s of 1080p exports to the gallery at a rate you'd tolerate.

### P5 — Library (1–2 weeks)
Project save/load, thumbnails, recents, import/relink, deletion.
**Done when:** a project survives app restart and can open a desktop-authored `.recordly`.

### P6 — Parity (ongoing)
Captions, cloud share, floating overlay, webcam track, system audio where offered.

**Credible v1: ~8–12 weeks**, with the risk front-loaded into P1. If P1 comes back empty, the timeline shrinks to ~7–10 weeks and the product is "screen recorder with manual zoom" — which is the honest outcome to price in before you start, not after.

---

## 8. Keeping up with upstream

Treat `recordly-upstream/` as a read-only remote. Your fork should add, not modify:

```
recordly-for-android/
├── upstream/            # remote webadderallorg/Recordly (read-only)
├── src/                 # your minimal patches to the renderer
│   └── platform/androidBridge.ts     # the shim, 1 new file
├── android/             # the actual app
└── patches/             # if you must touch upstream files, keep it a patch queue
```

**Target zero edits to upstream `src/`.** Every method is behind `window.electronAPI`, so the shim can supply everything. The startup path does *not* tolerate its complete absence — it crashes (§4.1) — but a stub shim covering 103 methods is enough to boot and is proven by upstream's own UI suite. The only file you may need to touch is `index.html` (inject the shim). If you find yourself editing `src/components/…`, stop and ask whether the bridge can absorb it instead.

Upstream is a fork of OpenScreen that has diverged heavily and is still shipping (v1.4.0 in September, pushed into November). Expect breaking changes in `preload.ts`; diff it every time you merge.

---

## 9. Licence — read before you write code

`LICENSE.md` is **GNU AGPL-3.0**, copyright 2026 webadderall.

- **Sideloading is distributing.** Handing an APK to a friend, posting it on a forum, or shipping on F-Droid all trigger the licence exactly as the Play Store would: the whole derivative — *your* Kotlin and the renderer — must be AGPL-3.0, and you must offer complete corresponding source.
- **A build that never leaves your own devices is not distributing.** If that is genuinely the endgame, the licence imposes nothing and you can move fast. The moment the plan changes, the obligation attaches retroactively to the builds you hand out.
- **"Sideload only" does not dodge this.** It changes the *distribution channel*, not the legal trigger. Decide now whether the Android port is a public artifact. If yes, plan for a public repo and a labelled release, and add that to P0, because it is cheap at the start and expensive at the end.
- **Play Store permits AGPL apps** — you must publish the source and link it. Not relevant today, but it stays an option.
- **A closed-source commercial Android app is not permitted** without the author's explicit permission. Ask them; they may dual-licence.
- **Name and branding are separate from the code licence.** Don't ship as "Recordly" with their marks without permission.
- `THIRD_PARTY_NOTICES.md` exists in the repo — carry it forward.

---

## 10. Decisions

**Resolved (2026-11-16)**

| Question | Answer | Consequence |
|---|---|---|
| Distribution | Sideload / direct APK | Play policy stops constraining the telemetry design; AGPL still binds anything handed to another person (§9) |
| Auto-zoom in v1 | **Required, non-negotiable** | Telemetry moves from last to first; P1 is a go/no-go spike (§4.3, §7) |
| Feature shape | Tap-driven zoom, not cursor-driven | Smoothing / motion blur / sway are inert on Android; click bounce and zoom-on-tap are the core |

**Still open**

1. **Fork policy** — zero-edit shim via `window.electronAPI` (recommended), or a hard fork you're willing to maintain? This is still the highest-leverage choice in the plan; everything in §8 assumes the shim.
2. **Target devices** — minimum Android version and a reference device for performance work. API 29 is the natural floor for `AudioPlaybackCapture`; WebCodecs needs WebView 94+, effectively automatic on supported devices. The accessibility route needs API 26+ in practice and API 30+ for the nicer node APIs.
3. **Fate of the port** — private build, or something you intend to publish? It does not change the architecture, but it decides the licence work in §9 and whether the public repo exists from commit one.

---

## 11. Implementation status (2026-11-16)

What is actually built, and what was proved by running it.

### Proved by execution

| Claim | Evidence |
|---|---|
| The renderer builds standalone, no Electron | `RECORDLY_RENDERER_ONLY=1 vite build --mode ui` → `dist/`, 2m12s |
| The renderer needs a bridge — it is **not** bridgeless | Removing it crashes with `TypeError: … reading 'getAppSetting'` (§8) |
| A 103-method stub is enough to boot the editor | 2 canvases, zero page errors, full chrome |
| `window.electronAPI` can be implemented outside Electron | `bridge/bridge.js` boots the real editor against a real video |
| `videoPath` is a URL, not a path | Video plays from `http://127.0.0.1:4400/fixtures/preview.mp4` |
| Four methods are synchronous | `.then is not a function` white-screen until `SYNC_METHODS` was added |

### Built

| File | Role | State |
|---|---|---|
| `bridge/bridge.js` | The whole seam: native transport, dev defaults, event bus, sync methods | **Verified in Chrome** |
| `bridge/android.js` | Host adapter: `__recordlyHost` → `__recordlyNative` | Written, unverified |
| `dev/serve.mjs` | Executable spec for the Kotlin server: shim injection + Range | **Verified** |
| `scripts/build-renderer.mjs` | Build upstream → copy into APK assets | Written |
| `android/app/…/RecordlyServer.kt` | Loopback server: routes, Range (206/416), SPA fallback, injection | Written, unverified |
| `android/app/…/NativeBridge.kt` | `@JavascriptInterface` host; 30 methods implemented | Written, unverified |
| `android/app/…/MediaRegistry.kt` | `/media/…` → file; links external files without copying | Written, unverified |
| `android/app/…/ProjectStore.kt` | Project library: save/load/list/rename/trash + thumbnails | Written, unverified |
| `android/app/…/SettingsStore.kt` | SharedPreferences-backed app settings | Written, unverified |
| `android/app/…/RendererAssets.kt` | assets → disk on version change (Range needs a real file) | Written, unverified |
| `android/app/…/MainActivity.kt` | WebView shell, bridge attach, renderer-crash recovery | Written, unverified |

### Two design rules adopted while writing it

1. **Only implement a native method whose success shape has been read out of
   upstream.** Everything else throws, and the shim falls back to the same
   neutral values upstream's own Playwright harness uses. A guessed shape is
   worse than a known-good placeholder, because a wrong-but-successful reply
   isn't caught by any fallback.
2. **Bytes never travel the JSON transport.** `JSON.stringify` turns an
   `ArrayBuffer` into `{}` and a `Uint8Array` into a giant index-keyed object.
   Export methods therefore fail loudly (`BINARY_METHODS` in `bridge.js`) until
   a byte channel exists — see §4.7's replacement below.

### Addendum to §4.7 — the export byte channel

Export is the one place the JSON seam genuinely cannot carry the data. The
design, when P4 starts:

- `openExportStream({extension})` → returns `streamId` and a temp path.
- `writeExportStreamChunk(streamId, position, chunk)` → `POST` the raw chunk to
  `http://127.0.0.1:<port>/__upload/chunk?stream=<id>&position=<n>`. The server
  writes at `position` with `RandomAccessFile`, so chunks may arrive out of order
  and the transport never buffers a whole video in memory.
- `closeExportStream(streamId, {abort})` → closes, optionally deletes.
- `saveExportedVideo` / `writeExportedVideoToPath` → same endpoint, single POST.

The server side is a natural extension of `RecordlyServer`; the shim intercepts
these method names before the JSON transport, exactly as it already does for the
four sync methods.

### Blocked

No APK has been compiled. This machine has **JDK 25.0.1 only** and no Android
SDK, Gradle, or `adb`; AGP 8.7 requires JDK 17–21. Install Android Studio
(bundled JDK 21) before any Kotlin here can be built or run.

---

## Appendix A — inventory at a glance

| Category | Count | Where the work is |
|---|---:|---|
| CORE | 13 | capture, encoder lifecycle, telemetry |
| ADAPTER | 103 | prefs, files, intents, navigation |
| WEB | 5 | temp-file byte sink / media URL |
| STUB | 14 | diagnostics, GIF optimize, system cursors |
| DROP | 64 | updater, window chrome, capture picker, menus |
| V2 | 35 | cloud, captions, floating HUD |

## Appendix B — full method classification

Generated from `electron/preload.ts` (234 top-level keys, line 189 onward). Machine-readable copy: `electronapi-inventory.csv`.

### CORE (13)

| method | note |
|---|---|
| `getAccessibilityPermissionStatus` | touch-capture capability check |
| `getCursorTelemetry` | read `<video>.cursor.json` sidecar |
| `onRecordingInterrupted` | phone call / doze / auth prompt interrupts capture |
| `pauseCursorCapture` | stop sampling on record-pause |
| `pauseNativeScreenRecording` | pause muxer timeline + telemetry pausing |
| `recoverNativeScreenRecording` | crash/background recovery of an in-flight capture |
| `requestAccessibilityPermission` | deep-link to Accessibility settings |
| `reserveRecordingSession` | single-owner session lock (foreground service) |
| `resumeCursorCapture` | resume sampling on record-resume |
| `resumeNativeScreenRecording` | resume muxer timeline |
| `setCursorTelemetry` | write `<video>.cursor.json` sidecar |
| `startNativeScreenRecording` | MediaProjection + MediaCodec + MediaMuxer → MP4 |
| `stopNativeScreenRecording` | flush MediaCodec, finalize MediaMuxer |

### ADAPTER (103)

| method | note |
|---|---|
| `ackAuthCallbackUrl` | deep-link intent |
| `authStorageAvailable` | EncryptedSharedPreferences |
| `authStorageGetItem` | EncryptedSharedPreferences |
| `authStorageMigrate` | one-time migration |
| `authStorageRemoveItem` | EncryptedSharedPreferences |
| `authStorageSetItem` | EncryptedSharedPreferences |
| `cacheProjectThumbnail` | write thumbnail PNG/WebP |
| `cancelCountdown` | renderer overlay drives it |
| `cancelRecordingImport` | abort import |
| `chooseRecordingsDirectory` | SAF directory picker (optional) |
| `clearCurrentVideoPath` | state |
| `createProjectFile` | write `.recordly` JSON into app files dir |
| `deleteRecordingFile` | delete file + sidecars |
| `finalizeExportedVideo` | move temp → final location |
| `findDuplicateRecordingSources` | hash/size match |
| `finishRecordingEditor` | navigation |
| `finishRecordingImport` | commit import |
| `finishRecordingStartup` | navigation |
| `generateWallpaperThumbnail` | BitmapFactory downscale + JPEG |
| `getActiveCountdown` | state |
| `getAnnouncements` | HTTPS fetch, cached |
| `getApplicationPreferences` | SharedPreferences |
| `getAppSetting` | SharedPreferences |
| `getAppSettingWithStatus` | SharedPreferences |
| `getAppVersion` | BuildConfig.VERSION_NAME |
| `getAssetBasePath` | local server URL |
| `getCountdownDelay` | pref |
| `getCurrentRecordingSession` | state |
| `getCurrentVideoPath` | state |
| `getEditorMode` | state |
| `getEditorStartupView` | state |
| `getMediaPermissionStatus` | RECORD_AUDIO / POST_NOTIFICATIONS check |
| `getPendingAuthCallbackUrl` | deep-link intent |
| `getPlatform` | return `"android"` |
| `getProjectPreview` | read cached preview JSON |
| `getProjectsDirectory` | app files dir path |
| `getRecordedVideoPath` | state |
| `getRecordingPreferences` | SharedPreferences |
| `getRecordingsDirectory` | app files dir path |
| `getRecordingThumbnail` | extract first frame or read cache |
| `getScreenRecordingPermissionStatus` | MediaProjection consent is per-session |
| `getShortcuts` | persist keymap |
| `getSources` | single synthetic "Entire screen" source |
| `getVideoAudioFallbackPaths` | sidecar audio paths |
| `importRecording` | copy from SAF Uri into recordings dir |
| `importWallpaperVideo` | SAF video picker |
| `listAssetDirectory` | list bundled wallpaper dir from assets |
| `listProjectFiles` | scan projects dir |
| `listRecordings` | scan recordings dir |
| `loadCurrentProjectFile` | parse current project |
| `loadProjectFile` | parse + validate |
| `onAuthCallbackUrl` | deep-link intent |
| `onCountdownTick` | event fan-out |
| `onCursorStateChanged` | event fan-out |
| `onEditorModeChanged` | event fan-out |
| `onProjectThumbnailReady` | event fan-out |
| `onRecordingPermissionsRequested` | event fan-out |
| `onRecordingSessionChanged` | event fan-out |
| `onRecordingStateChanged` | event fan-out |
| `onRequestSaveBeforeClose` | `OnBackPressedDispatcher` |
| `onStopRecordingFromTray` | notification action → stop |
| `openAccessibilityPreferences` | Intent → `ACTION_ACCESSIBILITY_SETTINGS` |
| `openAudioFilePicker` | SAF |
| `openExternalUrl` | `ACTION_VIEW` intent |
| `openLoadingRecordingEditor` | navigation |
| `openMediaPermissionPreferences` | Intent → app settings |
| `openProjectFileAtPath` | route to editor |
| `openScreenRecordingPreferences` | Intent → capture settings / consent |
| `openVideoFilePicker` | SAF |
| `probeNativeVideoMetadata` | MediaMetadataRetriever |
| `readLocalFile` | read bytes from approved path |
| `relinkProjectRecording` | repoint `videoPath` after import/SAF |
| `renameLibraryProject` | rename file + metadata |
| `requestMediaPermission` | runtime permission request |
| `reviewProjectDeletion` | return deletion summary |
| `saveExportedVideo` | SAF `createDocument` / MediaStore insert |
| `saveProjectFile` | atomic write (temp + rename) |
| `saveProjectFileNamed` | atomic write with chosen name |
| `saveShortcuts` | persist keymap |
| `setApplicationPreferences` | SharedPreferences |
| `setAppLocale` | `AppCompatDelegate.setApplicationLocales` |
| `setAppSetting` | SharedPreferences |
| `setCountdownDelay` | pref |
| `setCurrentRecordingSession` | state |
| `setCurrentVideoPath` | state |
| `setCursorScale` | persist DPI-independent scale |
| `setExportInProgress` | state for back-press guard |
| `setHasUnsavedChanges` | state for back-press guard |
| `setRecordingPreferences` | SharedPreferences |
| `setRecordingsRemoved` | persist removal set |
| `setRecordingState` | state |
| `showProjectDashboard` | navigation |
| `showRecordingPermissions` | permission UI |
| `startCountdown` | renderer overlay drives it |
| `storeMicrophoneSidecar` | write mic track sidecar |
| `storeRecordedVideo` | move capture temp into recordings dir |
| `suggestExportFileName` | derive name from project |
| `switchToEditor` | activity/fragment navigation |
| `trashProjectFiles` | move to trash dir |
| `trashRecordingSources` | move to trash dir |
| `waitForRecordingEditor` | navigation |
| `writeClipboardText` | ClipboardManager |
| `writeExportedVideoToPath` | write to caller-provided Uri |

### WEB (5)

| method | note |
|---|---|
| `closeExportStream` | flush + close the temp file |
| `discardExportedTemp` | delete temp file |
| `getLocalMediaUrl` | build `http://127.0.0.1:<port>/media/…?cap=<hmac>` |
| `openExportStream` | open a temp file; renderer muxes with mediabunny |
| `writeExportStreamChunk` | positional write to the temp file |

### STUB (14)

| method | note |
|---|---|
| `appendExportDiagnostic` | no-op or ring buffer |
| `cancelGifOptimization` | no-op |
| `getExportDiagnostics` | return empty |
| `getExportHardwareInfo` | advisory only; return nulls |
| `getExportRuntimeInfo` | diagnostics only |
| `getLastNativeCaptureDiagnostics` | return empty diagnostics |
| `getNativeExportCapabilities` | report webcodecs-only, no native encoder |
| `getRecordingAudioLabConfig` | advanced lab config; return defaults |
| `getSystemCursorAssets` | Android uses bundled cursor styles, not OS bitmaps |
| `hideOsCursor` | no OS cursor to hide |
| `isNativeWindowsCaptureAvailable` | return `false` |
| `optimizeGif` | ffmpeg/gifsicle pass; skip, keep unoptimised GIF |
| `recoverRecordingOwner` | return `null`; Android owns the service lifecycle |
| `releaseRecordingStartup` | no-op |

### DROP (64)

| method | note |
|---|---|
| `appendRecordingFileStream` | desktop chunked-capture pipe |
| `capturePickerReady` | no desktop-style window picker |
| `checkForAppUpdates` | no updater in v1 (sideload = manual reinstall) |
| `closeOnboardingWindow` | separate onboarding window |
| `closePermissionAccessory` | macOS permission accessory window |
| `closeRecordingFileStream` | desktop chunked-capture pipe |
| `completeCapturePick` | no desktop-style window picker |
| `deferDownloadedUpdate` | no updater in v1 (sideload = manual reinstall) |
| `discardRecordingFileStream` | desktop chunked-capture pipe |
| `dismissUpdateToast` | no updater in v1 (sideload = manual reinstall) |
| `downloadAvailableUpdate` | no updater in v1 (sideload = manual reinstall) |
| `downloadReleaseVersion` | no updater in v1 (sideload = manual reinstall) |
| `getAutomaticUpdateDownloadsEnabled` | no updater in v1 (sideload = manual reinstall) |
| `getCapturePickerContext` | no desktop-style window picker |
| `getCurrentUpdateToastPayload` | no updater in v1 (sideload = manual reinstall) |
| `getDesktopWallpaper` | desktop wallpaper as background |
| `getExperimentalUpdatesEnabled` | no updater in v1 (sideload = manual reinstall) |
| `getLinuxWindowSystem` | X11/Wayland detection |
| `getSelectedSource` | Android captures the whole display |
| `getUpdateStatusSummary` | no updater in v1 (sideload = manual reinstall) |
| `getWindowChrome` | native window chrome |
| `installDownloadedUpdate` | no updater in v1 (sideload = manual reinstall) |
| `isWindowFullscreen` | native window chrome |
| `listReleaseVersions` | no updater in v1 (sideload = manual reinstall) |
| `muxExportedVideoAudio` | ffmpeg audio mux; webcodecs path muxes in JS |
| `muxExportedVideoAudioFromPath` | ffmpeg audio mux; webcodecs path muxes in JS |
| `muxNativeWindowsRecording` | Windows Media Foundation only |
| `nativeStaticLayoutExport` | CUDA / D3D11 desktop compositor |
| `nativeStaticLayoutExportCancel` | paired with `nativeStaticLayoutExport` |
| `nativeVideoExportCancel` | paired with `nativeVideoExportStart` |
| `nativeVideoExportFinish` | paired with `nativeVideoExportStart` |
| `nativeVideoExportStart` | Media Foundation / VideoToolbox encoder |
| `nativeVideoExportWriteFrame` | paired with `nativeVideoExportStart` |
| `nativeVideoExportWriteFrames` | paired with `nativeVideoExportStart` |
| `onCapturePickerWindowResized` | no desktop-style window picker |
| `onMenuLoadProject` | native menu bar |
| `onMenuSaveProject` | native menu bar |
| `onMenuSaveProjectAs` | native menu bar |
| `onNativeStaticLayoutExportProgress` | paired with `nativeStaticLayoutExport` |
| `onOnboardingIntroShown` | separate onboarding window |
| `onSelectedSourceChanged` | no desktop-style window picker |
| `onUpdateReadyToast` | no updater in v1 (sideload = manual reinstall) |
| `onUpdateToastStateChanged` | no updater in v1 (sideload = manual reinstall) |
| `onWindowChromeChanged` | native window chrome |
| `onWindowFullscreenChanged` | native window chrome |
| `openProjectsDirectory` | no user-visible projects folder |
| `openRecordingFileStream` | desktop chunked-capture pipe |
| `openRecordingsFolder` | no user-visible recordings folder |
| `openSourceSelector` | no source selection window |
| `pickCaptureTarget` | no desktop-style window picker |
| `previewUpdateToast` | no updater in v1 (sideload = manual reinstall) |
| `reportOnboardingIntro` | separate onboarding window |
| `resizeCaptureWindow` | no desktop-style window picker |
| `revealInFolder` | no file manager reveal |
| `selectSource` | no source selection |
| `setAutomaticUpdateDownloadsEnabled` | no updater in v1 (sideload = manual reinstall) |
| `setExperimentalUpdatesEnabled` | no updater in v1 (sideload = manual reinstall) |
| `showOnboardingWindow` | separate onboarding window |
| `showSourceHighlight` | no window-selection highlight |
| `skipUpdateVersion` | no updater in v1 (sideload = manual reinstall) |
| `startFfmpegRecording` | Electron-driven ffmpeg capture; unused on Android |
| `startPermissionAccessoryDrag` | macOS permission accessory window |
| `stopFfmpegRecording` | paired with `startFfmpegRecording` |
| `windowControl` | native window chrome |

### V2 (35)

| method | note |
|---|---|
| `cloudBilling` | Play Billing instead |
| `cloudBillingCancel` | Play Billing instead |
| `cloudShareBeginExport` | HTTP multipart upload |
| `cloudShareCancel` | HTTP |
| `cloudShareManage` | HTTP |
| `cloudShareUpdateToken` | HTTP |
| `cloudShareUpload` | HTTP multipart upload |
| `onCloudShareProgress` | event fan-out |
| `deleteCaptionModel` | caption model lifecycle |
| `downloadCaptionModel` | caption model lifecycle |
| `getCaptionModelStatus` | caption model lifecycle |
| `onCaptionModelDownloadProgress` | caption model lifecycle |
| `deleteWhisperSmallModel` | whisper model lifecycle |
| `downloadWhisperSmallModel` | whisper model lifecycle |
| `getWhisperSmallModelStatus` | whisper model lifecycle |
| `openWhisperExecutablePicker` | external whisper binary (desktop-only concept) |
| `openWhisperModelPicker` | external whisper model file |
| `onWhisperSmallModelDownloadProgress` | whisper model lifecycle |
| `deleteParakeetModel` | parakeet model lifecycle |
| `downloadParakeetModel` | parakeet model lifecycle |
| `getParakeetModelStatus` | parakeet model lifecycle |
| `onParakeetModelDownloadProgress` | parakeet model lifecycle |
| `generateAutoCaptions` | sherpa-onnx Android AAR or SpeechRecognizer |
| `getHudOverlayCaptureProtection` | Android 14+ capture exclusion |
| `setHudOverlayCaptureProtection` | Android 14+ capture exclusion |
| `getHudOverlayMousePassthroughSupported` | floating overlay |
| `hudOverlayClose` | floating overlay |
| `hudOverlayDrag` | floating overlay |
| `hudOverlayHide` | floating overlay |
| `hudOverlayRendererReady` | floating overlay |
| `hudOverlaySetIgnoreMouse` | floating overlay |
| `hudOverlaySetInteractiveBounds` | floating overlay |
| `hudOverlaySetSourceSelectionActive` | floating overlay |
| `hudOverlaySetWebcamPreviewVisible` | floating overlay |
| `showRecordingHud` | `SYSTEM_ALERT_WINDOW` floating controls, or notification |

---

## 12. Implementation update — 2026-11-18

The §11 "blocked" note is resolved: Android Studio, platform 35/37 and an API 37
emulator are installed, the debug APK builds, and capture, the project library
and export run on it. What changed since the plan was written:

- **Export is proven end-to-end.** A 65.8-second project exported through the
  in-WebView WebCodecs route to a 1920x1080 30 fps H.264 MP4, wrote through the
  HTTP byte channel, finalized into app storage and appeared in MediaStore.
  The route is confirmed; see `android/README.md` for timings and the save bug
  that was fixed. A microphone take also exported with an AAC track, so the
  audio side of the pipeline works even though the emulator's microphone is
  silent.
- **WebCodecs needs a current Android System WebView.** The emulator runs
  WebView 145. `VideoEncoder`/`VideoDecoder` only became default-on in WebView
  ~130, so `minSdk 29` with an old WebView will fail export. Feature-detect and
  direct the user to update System WebView; do not treat API level as the gate.
- **H.264 hardware encoding was absent on the emulator.** `isConfigSupported`
  rejected every H.264 1080p configuration there, yet a software encode
  succeeded and took roughly 5.5x realtime. Do not infer device throughput from
  this; a real phone should expose MediaCodec hardware. Measure before
  promising export speed.
- **`importRecording` is implemented** as a sample-level MP4 concat rather than
  an ffmpeg rewrite. It preserves video and audio samples, offsets the second
  half by the first's duration, merges cursor telemetry, and refuses mismatched
  resolutions/codec configurations. Different-resolution appends still need a
  re-encode path.
- **`importWallpaperVideo` is implemented.** `onShowFileChooser` makes the
  page's file inputs work; video `File`s upload over the export byte channel
  because they cannot cross the JSON bridge.
- **The recording library was silently empty.** Kotlin's `Regex.matches`
  requires the entire string, so the `\.mp4$`-style patterns matched nothing.
  Fixed by anchoring them with `.*`.
- **Mobile layout remains the largest user-facing gap.** At 411 CSS px the
  desktop header's Undo/Redo cluster overlaps the Clips button and swallows its
  taps; the Scene panel and timeline still assume a desktop viewport. This is
  design work, not a bridge method.

---

## 13. Verification update — 2026-11-19

The four emulator verifiers pass against the current debug build:

| Verifier | Covers |
|---|---|
| `dev/verify-editor.mjs` | library listing, same-format recording append, wallpaper byte upload |
| `dev/verify-token.mjs` | loopback token stripped on save, reissued on load, unrelated query params preserved |
| `dev/verify-capture.mjs` | microphone take, notification pause/resume/stop, editor handoff |
| `dev/verify-telemetry.mjs` | real Settings taps observed by accessibility, sidecar click/mouseup pairs |

Changes made during the pass:

- **Taps are presented as touches.** Project and preference writes force
  `showCursor: false`, `cursorClickEffect: "echo"` in `#2563EB`, and disable
  idle-hide. Verified through an export of a real take: blue rings on the tapped
  control, no cursor, no indicator away from a tap. `TouchPresentation.kt` is
  applied on project save/load/preview and on the editor-preferences setting.
- **The HUD fits a phone.** `bridge/android.js` injects a narrow-screen rule
  capping the bar and allowing horizontal scroll. Before: 449 px bar at x=-38 on
  a 411 px viewport with Record at x=-24; after: Record at x=15, fully tappable.
- **Countdown and session events were dead.** They were emitted with IPC names
  while the shim subscribes by API name; fixed to `onCountdownTick` and
  `onRecordingSessionChanged` and observed firing.
- **Fallback adapter gaps closed:** `loadProjectFile`, `reviewProjectDeletion`,
  `trashRecordingSources`, `findDuplicateRecordingSources`,
  `relinkProjectRecording`, `chooseRecordingsDirectory`,
  `showRecordingPermissions`, and project trash with `includeSources`.
- Coverage is now **125/234 inventory methods implemented**; the remaining
  fallbacks are auth/cloud/ASR (V2), desktop-only helpers, and event channels
  that are emitted rather than dispatched.

Still open: editor layout at 411 px (HUD fixed, editor header/timeline not),
real-device telemetry/microphone validation, release signing, APK size (77 MB,
48 MB of wallpapers), a root git repository, and `docs/` (currently empty).

---

## 14. Touch auto-zoom — 2026-11-19

Auto-zoom was the feature most distorted by desktop assumptions. The fixes, all
verified on the emulator:

| Problem | Fix |
|---|---|
| Clicks within 2.5 s merged into one region | Touch passes `mergeGapMs: 800`, `padMs: 450`; each tap gets a 2 s region |
| Auto-apply skipped when the phone preview was 0 px wide | Touch ready gate needs media duration + initialized clip, not `isPreviewReady`; a skip no longer latches |
| Auto framing followed cursor moves | `zoomClassicMode: true`; regions frame the tap |
| Connected zooms held the camera between taps | `connectZooms: false` |
| Swipes produced `move` samples | Scroll events emit no telemetry |
| Notification Stop/Pause taps became zooms | SystemUI, permission controllers and Recordly's own package are ignored |
| Zoom-out cut off at the final frame | 360 ms trailing reserve when the tap has room |
| Text smeared during zoom | `zoomMotionBlur: 0` on touch |
| HUD recorded itself on a cold start | `moveTaskToBack(false)` instead of `true` |

Evidence: three taps + one swipe produced exactly three 2 s regions covering the
taps and none for the swipe or Stop tap; export frames show zoom in, zoom out,
a wide swipe segment, and crisp text. Gates: `dev/verify-autozoom.mjs` and
`recordly-upstream/src/components/video-editor/touchZoomPolicy.test.ts` (4
tests). Remaining: the phone editor still collapses its preview behind the
settings panel, so layout work is needed to judge zooms live.

---

## 15. Portrait mobile shell — 2026-11-19

The desktop layout was unusable at phone width. Measured at 411 px: the editor
top row was [64 px rail][320 px inspector][19 px preview] — the video was
invisible — and the dashboard was [192 px nav][207 px card], wrapping "Your
projects" and squeezing every card.

The app is now portrait-first:

| Area | Before | After |
|---|---|---|
| Editor preview | 19 px wide, canvas 0×0 | 339 px panel, 251×557 canvas |
| Inspector | always-open 320 px column | slide-over drawer, closed by default, X + backdrop |
| Header | 7 px project column, under status bar | 1fr project column, system-bar insets, ellipsis |
| Dashboard nav | 192 px column | drawer from a header menu button |
| Timeline panel | 188 px, 64 px clip + 64 px zoom row | 121 px, 48 px clip + 28 px zoom row |
| Dashboard card | 207 px | 395 px |
| Orientation | sensor | locked portrait |

Implementation: `EditorSidebar.mobile.css` + drawer state in `EditorSidebar.tsx`,
`Dashboard.mobile.css` + `Dashboard.tsx` nav state, system-bar/IME padding on
the root view in `MainActivity.kt`, and extra CSS for the header. The tuned
layout was verified on the API 37 emulator (preview plays, both drawers open and
close, dashboard thumbnails load). Evidence under `dev/verification/mobile-*`.

Secondary dialogs were verified at 411 px (Publish sheet, editor Settings,
dashboard Settings), and the project name now wraps over two lines. Remaining
phone polish: hide desktop-only taskbar/update settings entries, and real-device
testing.

---

## 16. Windows-recording parity — 2026-11-19

Reference: `portfolio.mp4` from Recordly on Windows — 1920×1080, H.264 Main,
60 fps CFR, 37.6 Mbps, stereo AAC 317 kbps, 81.05 s, cursor + wallpaper
composition. The bitrate equals Recordly's quality formula
(`1920×1080×60×0.3`), so the reference recipe is Original + Quality + 60 fps.

Android now defaults to Quality mode and 60 fps, with FHD resolution on real
devices; emulators (720p-class WebCodecs only) default to HD so export succeeds.
The capture pipeline also got two core fixes:

- **A/V tail.** MediaMuxer repeated the previous sample interval for the final
  video frame; after a long static stretch the container was 0.9–1.3 s longer
  than the audio. The join writes a short duplicate of the last sample at the
  next frame interval, bounding the tail. New pause/resume takes are 3–136 ms
  apart instead of 1.3 s.
- **Consent denial.** Native capture failure on touch no longer falls back to
  the unavailable browser-capture path; a dismissed dialog stops cleanly.

Microphone capture is now 128 kbps and `verify-capture` asserts A/V alignment
and bitrate. Constraints: system audio is unavailable, microphone is mono, raw
capture is VFR while export is CFR, and the emulator cannot encode FHD/60.
Real-device FHD/60 export validation remains.

---

## 17. Floating frame and zoom parity — 2026-11-19

> Superseded by §18. The first pass used padding 75 (70% wide) and a depth-1
> click-centred block; the reference actually composes at ~86% wide, and a
> 1.25× crop cannot centre edge taps.

The Windows reference's core look is a captured window inset on a wallpaper,
with the camera zooming the composition around interactions. Android's defaults
had the wallpaper but zero padding/radius, so exports were full-frame.

New takes now default to: wallpaper `/wallpapers/sonoma-dark.jpg`, linked
padding 75, radius 3%, shadow 0.6. The values ship in the editor-preferences
seed and a one-time v2 migration for existing installs; user changes after the
migration win. Existing projects with zero padding/radius are migrated on first
open (custom wallpaper preserved), and saving them writes the
`touchFrameMigratedV2` marker. Touch auto-zoom matches the reference geometry:
depth 1 (1.25×) with Recordly’s Focused curve (200/285.7 ms), classic framing
and no connected zooms.

Verified by exporting at 576×1280 60 fps and sampling every 50 ms: the wide
screen occupies ~70% of the frame, grows to ~85% through intermediate frames
over ~400 ms, holds, and returns to ~69% over ~350 ms with the wallpaper
visible throughout.

Remaining: the emulator caps H.264 at 720p; real-device FHD look/zoom validation
and a landscape-source composition pass remain.

---

## 18. Touch zoom P0 — lead, reach, composition — 2026-11-20

Reassessment of the core effect against `portfolio.mp4` found three measurable
mismatches, not a reason to rebuild the app. The renderer already produces the
floating frame and spring zoom; the touch policy was the problem.

**Measured reference.** The composed source sits at `left 132, right 1787` of
1920 — **86.2% of frame width (~7% per side)** — in
`dev/verification/win-portfolio/analysis/bounds_keyframes.json` and `full/t0.png`.
The previous Android default (padding 75) rendered the screen at 70%, visibly
smaller and more inset than the reference.

**Changes**

- **Composition.** `TOUCH_PADDING` is now 35 (7% per side, 86% wide). The
  preference migration key is v3; existing projects with uniform padding 0, 20,
  or the v2-default 75 (with the v2 marker) are upgraded on open. A different
  padding or corner radius is treated as a user choice and preserved.
- **Lead.** `buildInteractionZoomSuggestions` accepts `leadMs`; touch passes
  200 ms, desktop keeps the old click-centred block. Regions now start exactly
  200 ms before their tap, so the zoom begins with the ripple instead of a
  second earlier. The tap is still inside the block, with ~1.3 s of hold.
- **Reach.** `touchZoomDepth(focus)` picks the shallowest depth (1 = 1.25×,
  2 = 1.5×, 3 = 1.8×) whose clamp keeps the target within a tenth of the frame.
  Central taps stay at 1.25×; top/bottom rows get 1.5× or 1.8× instead of being
  pulled toward the centre. An extreme edge row (94% down) cannot be centred
  without a ~9× zoom or uncovering the stage, so the renderer still pins it to
  the edge; the ripple marks the view. This ceiling is documented and asserted.

**Verification.** `node dev/verify-autozoom.mjs` records three real accessibility
taps and one swipe, then asserts region count, 200 ms lead, expected depth,
rendered focus error `<= 0.25`, and no swipe region. Latest run:
`checks = [{leadMs:200, depth:3, error:0.093}, {leadMs:200, depth:3,
error:0.222}, {leadMs:200, depth:2, error:0.057}]`; the swipe produced nothing.
The take was exported at 576×1280 60 fps (`dev/verification/p0-final.mp4`,
4.64 Mbps video, 128 kbps audio, 21.75 s): `p0-8.1.png` shows the 86%-wide
floating frame, `p0-8.5.png` the top-row 1.8× tap zoom, `p0-16.9.png` the 1.5×
mid-screen tap. Unit coverage: 40 tests across `touchZoomPolicy.test.ts` and
`zoomSuggestionUtils.test.ts`, including desktop centring (unchanged), touch
lead, swipe, trailing reserve, and depth/reach.

**What is still not parity.** Touch focus is a view centre, not a finger
coordinate; `setMotionEventSources` would consume input from the recorded app
(Android docs), so this is the observe-only ceiling. Non-clickable surfaces emit
no click and therefore no zoom. The emulator's WebCodecs encoder is 720p-class,
so FHD/60 and real-device touch accuracy still need device validation.

**Consent fix.** A dismissed MediaProjection dialog now returns
`{success:false, canceled:true}` instead of relying on the always-true
`userNotified`; touch stops cleanly and a retry reopens the dialog, while a real
native failure surfaces its message. The renderer type now declares `canceled`,
and `tsc --noEmit` is clean. The system dialog still defaults to "Share one app"
on Android 14+; the recorder needs **Share entire screen → Share screen**.

---

## 19. Capture and export reliability — 2026-11-20

The P0 work fixed the effect; this pass fixed the failure modes that made a take
unreliable or an export silently wrong.

- **Stale stop identity.** `CaptureSession.start` clears `lastOutput` and
  `recoverable`. A stop issued after a failed/denied start now returns
  "No screen recording is active" instead of the previous recording; a second
  stop after a completed take still returns the same path. Probed: double stop
  returned `recording-20261120-025856.mp4` twice; stop after a denied consent
  returned the failure.
- **Microphone.** `hasSamples` validates the AAC track with MediaExtractor
  instead of requiring `failure == null`, and `endCurrentSegment` no longer
  disables the microphone for the rest of the take. A cleanup/EOS error with
  valid samples is kept; the toast is emitted once per take. `verify-capture`
  after the change: 120.0 kbps.
- **A/V tail.** `endCurrentSegment` now drains the video before stopping the
  microphone, and the join drops audio past the final video frame. The 263 ms
  end gap that exposed this is gone: latest paused take **-16 ms**, a manual
  5.3 s take 4.8 ms, and a 172 s take 0.7 ms. `verify-capture` asserts
  `|delta| <= 250 ms` and >= 115 kbps.
- **Segment drain.** `SEGMENT_DRAIN_TIMEOUT_S` is 10 s. A segment that still
  misses the deadline leaves `files/capture` in place for diagnosis and is
  logged; `cleanupSegments` clears the in-memory list regardless so the next
  take cannot reuse it.
- **Export finalize.** `closeExportStream` stores the high-water mark keyed by
  canonical temp path; `finalize` rejects empty/truncated files and restricts
  `outputPath` to `media/exports`. The temp directory is swept for files older
  than 24 h on install; caption sidecars use the filename stem. Probed: an
  outside-exports target was rejected, a 100→10-byte truncation was rejected,
  and a normal 18.25 s export finalized at 4.08 Mbps.
- **Telemetry fallback.** A missing sidecar returns samples only while a take is
  in progress. Probed after a 2-tap take: an imported wallpaper returned 0
  samples instead of the live 2.
- **Foreground service.** `showNotification` reports whether `startForeground`
  succeeded; the initial failure aborts the take instead of recording with no
  stop control.
- **Consent watchdog.** `detach` counts down and clears `pendingConsent`, so a
  destroyed activity does not park the single finalizer thread for the
  10-minute consent timeout. The launcher-error and timeout paths also clear it.
- **Media index.** `MediaRegistry.saveIndex` uses `AtomicFile`; a failed write
  can no longer truncate the link index and force the next launch to discard it.

Constraints unchanged: view-centre touch focus, no non-clickable taps, raw
capture VFR, mic-only mono audio, emulator 720p-class. Real-device FHD/60 and
physical-touch validation remain.
