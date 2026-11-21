# Recordly for Android

This project hosts the upstream Recordly React renderer in an Android WebView.
Kotlin provides the `window.electronAPI` bridge, a local media server, screen
capture, project storage, and export. Two small renderer changes handle
thumbnail capture on Android; the rest follows upstream.

## Build

Requirements: Android Studio with its bundled JDK 21, Android SDK platform 35,
and Node.js 20 or newer. Set Gradle JDK to Android Studio's bundled JDK.

```sh
node scripts/build-renderer.mjs
# On Windows, build the APK with:
powershell.exe -ExecutionPolicy Bypass -File scripts/build-android.ps1 -Offline
```

`node scripts/build-renderer.mjs --skip-build` copies an existing renderer
`dist/` into Android assets. `scripts/build-android.ps1` checks the generated
renderer and bridge assets first: if they are missing, or older than the
renderer/bridge sources, it runs the renderer build before Gradle. When the asset
tree is newer than the debug APK, it also drops the merged-asset and packaging
outputs, so Gradle cannot report the previous APK up-to-date. Always edit
`bridge/bridge.js`, then rerun the copy step; edits to the generated asset are
overwritten.

The renderer and media are served from `127.0.0.1` over HTTP. WebView needs
this secure loopback origin for WebCodecs, and media playback needs HTTP Range.
`bridge/android.js` defines the native transport before `bridge/bridge.js`
constructs `window.electronAPI`.

Export needs **Android System WebView 130 or newer** (`VideoEncoder` became
default-on there). `minSdk 29` is not enough on a device whose WebView was never
updated; feature-detect and send the user to the Play Store rather than
treating the API level as the gate.

## Implemented

- Editor boot and local video playback.
- MediaProjection screen capture with foreground service, pause, resume, and
  stop, plus AAC microphone audio. An API 37 emulator recording was verified
  through notification pause, resume, stop, and editor handoff. The recorder
  now actually leaves the foreground on every take (`moveTaskToBack(false)`);
  the old non-root call was a no-op on a cold start and recorded the HUD.
- Accessibility touch telemetry is wired into capture and can supply cursor
  samples when the user enables the service in Android settings.
- Taps are presented as a touch indicator, never a mouse cursor. A tap at
  `(cx, cy)` renders blue concentric rings and no cursor sprite, in preview and
  export alike.
- Auto-zoom is tuned for touch: every tap gets its own zoom in/out (clicks less
  than 800 ms apart merge), starting 200 ms before the tap with a depth chosen so
  edge targets are reachable; swipes and notification/SystemUI controls produce
  none, and auto-apply no longer depends on a visible preview canvas.
- Spaced taps return to the wide frame between visits. Short blocks fit the
  shared spring to the available time instead of running competing ramps. Touch
  rings fade in 420 ms and light blur follows fast camera movement. See
  [Motion parity](../docs/MOTION-PARITY.md) for the Windows measurements.
- The recording HUD bar fits phone widths: the desktop-width idle bar used to
  overflow to the left and clip the Record button (449 px bar on a 411 px
  viewport, Record at x = -24). The Android host adapter caps the bar and makes
  it scroll, keeping Record visible.
- Phone-first editor shell. The desktop column layout gave the preview 19 px of
  a 411 px row; it now keeps the full width beside a 64 px tool rail while the
  inspector is a closed-by-default slide-over drawer with a close button and
  backdrop. The header respects the status bar and drops desktop-only chrome;
  the app is locked to portrait.
- Phone-first dashboard. The 192 px navigation column became a drawer opened
  from the header, leaving the project card the full viewport width.
- Settings persistence; cursor telemetry sidecar read/write.
- Project save, load, list, rename, trash, preview, and thumbnail cache.
  Project and metadata writes are atomic; thumbnail caching checks revision.
  Android extracts a source frame when WebView cannot compose a thumbnail.
  `loadProjectFile(path)` returns a project document, and trash can remove the
  source recordings a deleted project owned while keeping sources referenced by
  surviving projects or listed as protected.
- Library cleanup: deletion review counts owned/preserved sources, duplicate
  detection hashes file contents, and raw recordings can be deleted with their
  sidecars. `chooseRecordingsDirectory` reports "canceled" because Android
  recordings stay in app-private storage.
- Reconnecting a project whose recording is missing (`relinkProjectRecording`):
  SAF picker, metadata probe, edit-length validation, atomic path update, then
  the loaded project is returned to the editor.
- Missing-permission flow emits `onRecordingPermissionsRequested` so the
  renderer shows its permission banner; runtime prompts still happen when a
  take starts.
- Android document picker for supported video and audio files. Selected files
  are copied into app storage. Project import works when its source video is
  already in Recordly's app storage.
- Recording library listing (`listRecordings`) for the editor's Clips panel.
- Appending a recording to the open timeline (`importRecording`): a
  sample-level MP4 concat with the second file shifted by the first's duration,
  plus one merged cursor-telemetry sidecar. No ffmpeg and no re-encode.
  Requires matching resolution and codec settings; mismatches are refused with
  a readable error rather than producing a corrupt file.
- Video wallpapers chosen through the page's own `<input type="file">`
  (`WebChromeClient.onShowFileChooser` → `importWallpaperVideo`). The `File`
  is uploaded over the export byte channel and stored under
  `media/imported-wallpapers/`.
- Raw byte export stream, `readLocalFile` byte hydration, and saving an export
  to `Movies/Recordly` + MediaStore.
- Project previews enumerate every media reference in the document (video,
  webcam, audio, video wallpaper), so library cleanup does not treat a project
  as having no dependencies.

## Open work

- Real-device checks of microphone level and capture synchronization.
- Appending recordings with different resolutions or encoder settings needs a
  re-encode path; today it fails with an explicit error.
- Import scratch files under `recordings/.recordly-media/` (and imported
  wallpapers) are never swept. Upstream leaves its normalized copies too, but
  a long-lived install will accumulate them.
- Replace the Android build's forced development flags with a local recorder
  permission gate before release.
- Emulator ANRs seen during verification coincided with host memory pressure
  (the system process itself stopped responding); reproduce with host headroom
  before treating one as an app bug.
- The dashboard card's folder assignment is in the card's options menu on
  touch, so there is no hover-only control to hunt for.
- Real-device validation of touch telemetry accuracy; model downloads, cloud,
  and auto-updates.

### Google Play submission

- The app currently targets API 35. As of November 2026,
  [new phone apps must target API 36 or higher](https://support.google.com/googleplay/android-developer/answer/11926878).
  Recheck the requirement at submission and test capture on a release build.
- Keep the per-session Android screen-capture consent. Declare the
  [`mediaProjection` and `microphone` foreground-service use](https://support.google.com/googleplay/android-developer/answer/13392821)
  in Play Console.
- Tap-based auto-zoom uses `AccessibilityService`, not an accessibility tool
  for disability support. If shipped, it needs a
  [Play declaration, listing disclosure, prominent in-app disclosure and affirmative consent](https://support.google.com/googleplay/android-developer/answer/10964491).
  Keep recording and manual zoom usable without enabling the service.
- Play distribution does not grant additional capture privileges. Do not
  advertise status-bar or notification-shade support until validated on devices
  with privacy settings unchanged. See the [Nord 5 comparison procedure](../docs/MOTION-PARITY.md#next-systemui-capture-check).

## Verification

`node --test bridge/bridge.test.mjs bridge/android.test.mjs` runs 9 tests:
export byte transport, whole-buffer saves, upload abort, wallpaper upload,
local file hydration, and microphone discovery. The renderer build and debug
APK build pass in this workspace.

`node dev/verify-editor.mjs` drives the running debug app through the same
flows: it asserts the recording library is non-empty, concatenates two
same-format recordings and checks the joined duration, and uploads a `File`
through the wallpaper channel. `node dev/verify-token.mjs` proves project media
URLs persist without the loopback token and are re-authorized on load.
`node dev/verify-capture.mjs` records a microphone take through the notification
pause/resume/stop path and checks the editor opens it. `node dev/verify-telemetry.mjs`
enables the accessibility service, records a take while tapping real Settings
rows, and asserts the sidecar contains normalized click/mouseup pairs.
`node dev/verify-autozoom.mjs` records four Clock keypad taps and one swipe,
then asserts four fitted zoom regions matching the tap targets, a 200 ms lead,
adaptive depth, a wide beat between regions, and no region for the swipe.
`node dev/webview.mjs eval dev/check-codecs.js` reports WebCodecs encoder
support for the installed WebView.

All five emulator verifiers pass. `dev/verification/` holds their artifacts.
The touch policy also has unit coverage:
`cd recordly-upstream && ./node_modules/.bin/vitest run src/components/video-editor/touchZoomPolicy.test.ts`.
`cd recordly-upstream && ./node_modules/.bin/tsc --noEmit` is clean. A denied
MediaProjection dialog now resolves `canceled:true`, stops without a browser
fallback, and a retry reopens the dialog (probed on the API 37 emulator).

### Library, relink, and events (2026-11-19)

A feature pass against the renderer's real call sites found four flows that
were failing on fallback values, plus two events that could never arrive:

- `loadProjectFile` was not dispatched. It now loads an explicit path (used by
  the token verifier and by callers that already resolved one) or the current
  project.
- Library cleanup called `reviewProjectDeletion`, `findDuplicateRecordingSources`
  and `trashProjectFiles({includeSources:true})`. The first two now answer with
  real counts/content hashes, and project trash deletes sources only when no
  surviving project or protected path references them. Source deletion removes
  the video and every sidecar suffix in `discardFiles.ts`.
- `trashRecordingSources` now deletes a selected raw recording plus sidecars,
  refuses paths outside the recordings directory, and keeps protected paths.
- `chooseRecordingsDirectory` answers `canceled:true`; Android has no folder to
  pick and the settings row no longer raises an error.
- `relinkProjectRecording` was unimplemented. It now opens the SAF picker,
  probes the replacement, rejects one shorter than the project's clips require,
  updates `videoPath` atomically, and returns the loaded project.
- `showRecordingPermissions` emits `onRecordingPermissionsRequested` so the
  renderer's permission banner appears.
- The countdown and session events were emitted with IPC names
  (`countdown-tick`, `recording-session-changed`) while the shim subscribes by
  API name (`onCountdownTick`, `onRecordingSessionChanged`); `emit` drops
  unsubscribed channels, so both were dead. They now use the API names and were
  observed end-to-end (`3,2,1,0` ticks and a session payload).

Verified on the API 37 emulator with scratch projects and recordings: review
counted 2 owned sources and 0 preserved, trashing one project deleted its source
while keeping a protected source, content-hash duplicate detection grouped
identical copies and shrank after a delete, relinking a missing 5 s project
succeeded, a 60 s project rejected a 13 s replacement, and the permission event
fired. Scratch artifacts were removed afterwards.

Still deliberately on fallback: auth/cloud/announcement methods (offline
sideload), `storeMicrophoneSidecar` (native capture embeds microphone audio in
the MP4), and unused desktop helpers.

### Floating frame and touch zoom (2026-11-20)

The Windows reference keeps the captured window inset on a wallpaper and zooms
the whole composition around interactions. Android had the wallpaper setting but
padding and corner radius were zero, so exports filled the frame and the effect
was missing.

New takes now start with a floating-frame composition:

- wallpaper `/wallpapers/sonoma-dark.jpg`
- linked padding `35` — 7% per side, so the captured screen occupies ~86% of
  the frame width. That is the measured width of the Windows reference
  (`analysis/bounds_keyframes.json`: 132..1787 of 1920).
- corner radius `3%` with `shadowIntensity: 0.6`, background blur off

The values live in the editor-preferences seed and in a one-time migration
(`recordly.android.touchFrameMigrated.v3`) for existing installs. A renderer save
sets the migration marker, so later user look changes are not overwritten.

Existing projects are migrated the first time they are opened: a project with
uniform padding 0 or 20, or the v2 default 75 when its `touchFrameMigratedV2`
marker is present, gets the look in memory (a custom wallpaper is kept), and
saving it writes both the look and the `touchFrameMigratedV3` meta flag. A
different padding or corner radius is treated as a user choice and preserved;
after the migration, a user's saved look wins, including an intentional
full-frame choice.

Touch auto-zoom is tuned to the reference's motion and to the way touch input
actually arrives:

- **Lead 200 ms.** Desktop blocks are centred on the click, so the camera is
  fully zoomed ~800 ms before the click while the pointer travels. Touch has no
  pointer, so the zoom now starts with the tap. Measured regions start exactly
  200 ms before their tap (`autozoom-report.json` → `checks[].leadMs`).
- **Adaptive depth.** The focus clamp at 1.25× can only centre the middle 20% of
  the screen, so edge taps used to be pulled toward the centre. Depth 1/2/3
  (1.25×/1.5×/1.8×) is chosen as the shallowest crop that keeps the target
  within a tenth of the frame. Central taps stay gentle; top and bottom rows
  still zoom. An extreme edge row cannot be centred without a ~9× zoom or
  uncovering the stage, so its rendered offset is capped at ~0.22 frame.
- the **Focused** preset exactly: **200 ms in / 285.7 ms out**, with the standard
  Recordly spring
- classic framing and disconnected regions stay enabled

Verified by exporting at 576×1280 60 fps: the screen sits at ~86% width on the
wallpaper, zooms in on a tap over ~400 ms, holds, and returns over ~350 ms.
Evidence: `dev/verification/p0-final.mp4`, `p0-8.1.png` (wide),
`p0-8.5.png` (tap zoom), `p0-16.9.png` (1.5× tap), and
`autozoom-report.json` (lead/depth/focus checks). Padding A/B against the
reference: `p0-padding-ab.png` — reference (86%), old 75 (70%), new 35 (86%).

### Capture and export reliability (2026-11-20)

A reliability pass on the paths that made takes inconsistent:

- **Stale stops.** Starting a take clears `lastOutput`/`recoverable`, so a stop
  after a failed or denied start answers "No screen recording is active"
  instead of handing back the previous file. A second stop after a finished
  take still returns the same path (idempotent).
- **Microphone failures.** A cleanup/EOS failure no longer disables the mic for
  the rest of the take. `hasSamples` validates the AAC track with MediaExtractor,
  so a segment with valid samples is kept even if `muxer.release` logged a
  problem. The unavailable toast fires at most once per take.
- **A/V tail.** The microphone now runs through the video drain instead of
  stopping at the EOS request, and the join drops audio past the final video
  frame. The 263 ms end gap that failed the gate is gone: the latest paused
  take is **-16 ms** (audio 16 ms longer), a 5.3 s take 4.8 ms, and a 172 s take
  0.7 ms. `verify-capture` asserts `|delta| <= 250 ms` and >= 115 kbps audio.
- **Segment drain.** The per-segment deadline is 10 s (was 5 s). A segment that
  still misses it leaves the scratch directory in place for diagnosis instead
  of being deleted silently; the finished segments are still joined.
- **Export integrity.** `finalize` compares the file length to the close-time
  high-water mark (canonical paths), rejects empty/truncated output, and
  restricts `outputPath` to `media/exports`. Caption sidecars use the filename
  stem, and `export-temp` files older than 24 h are swept on install.
- **Telemetry fallback.** A video without a sidecar returns no samples unless a
  take is in progress, so an old or imported video can no longer inherit the
  last take's taps.
- **Foreground service.** If `startForeground` fails, the take aborts instead of
  continuing without the notification that is the only stop control.
- **Consent watchdog.** Detaching the activity releases the consent latch, so a
  destroyed activity can no longer park the single finalizer thread for the
  10-minute timeout (which also delayed a projection-revoked stop).
- **Media index.** `MediaRegistry.saveIndex` writes through `AtomicFile`, so a
  crash or partial write cannot leave a truncated link index that the next
  launch discards wholesale.

Probed on the API 37 emulator: double stop returns the same path; stop after a
denied consent returns "No screen recording is active"; an imported wallpaper
returns 0 samples after a take with 2 taps; an outside-exports target is
rejected; a 100-byte stream truncated to 10 bytes is rejected; and a normal
18.25 s export still finalizes (4.08 Mbps, 128 kbps audio). Evidence:
`dev/verification/p1-reliability.json`, `p1-export.mp4`.

### Windows-recording parity (2026-11-19)

The reference `portfolio.mp4` recorded/exported by Recordly on Windows measures:
1920×1080, H.264 Main, **60 fps CFR**, **37.6 Mbps**, stereo AAC 317 kbps,
81.05 s, with a rendered cursor and a wallpaper/browser composition. The bitrate
matches Recordly's own quality formula exactly (`1920 × 1080 × 60 × 0.3 bit` =
37.3 Mbps), which identifies the export recipe as **Original resolution +
Quality + 60 fps**.

Android now defaults to that recipe:

- **Export defaults** — `advancedExportSettings: true`,
  `exportEncodingMode: "quality"`, `mp4FrameRate: 60`, resolution FHD
  (`864×1920` at the phone's portrait aspect). Emulators report only 720p-class
  WebCodecs H.264, so they default to HD (`576×1280`) instead of failing at FHD.
- **Final-frame duration** — MediaMuxer repeats the previous sample interval for
  the last video frame. After a static stretch that held the last frame for
  ~0.86 s, the container was ~0.9–1.3 s longer than the audio, and the editor
  treated the tail as extra video with no sound. The join now writes one short
  duplicate of the last encoded sample at the next frame interval, bounding the
  tail to ~33 ms. Verified: a new pause/resume take has video 14.363 s and audio
  14.227 s (**136 ms**, previously 1.3 s), and the first post-fix take was
  3 ms apart.
- **Microphone quality** — AAC capture raised from 96 to **128 kbps**
  (`verify-capture` now asserts ≥115 kbps and A/V delta ≤250 ms).
- **Consent denial** — a dismissed MediaProjection dialog previously fell
  through to browser capture and produced a second, misleading WebView error.
  A denial now resolves `{success:false, canceled:true}`; touch hosts stop
  cleanly and a retry opens a fresh dialog. Other native failures surface their
  real message instead of being treated as a cancellation.
- **Entire-screen selection** — the Android 14+ consent dialog defaults to
  "Share one app". The recorder needs **Share entire screen → Share screen** for
  tap coordinates and the full demo to line up; the HUD's source row reports
  "Screen 1 (Primary)". The system dialog cannot be preselected by the app.

Honest parity limits: Android captures the microphone, not system audio, and
the microphone is mono, so Windows' stereo 317 kbps mix cannot be reproduced;
raw capture is VFR (frames on screen change) while export is 60 fps CFR; and the
emulator's H.264 encoder caps output at 720p-class. Real phones are expected to
use the FHD/Quality/60 default. Android renders touch rings rather than a mouse
cursor by design.

Evidence: `dev/verification/win-portfolio/` (reference frames),
`dev/verification/fresh-export-hd60.mp4` (576×1280 60 fps, Main, 128 kbps,
video/audio within 34 ms), `dev/verification/capture-result.json` (`avSync`).

### Mobile shell (2026-11-19)

The editor was a desktop three-column layout. At 411 px the top row measured
[64 px rail][320 px inspector][19 px preview], so the video was invisible and
the settings card covered the screen. The dashboard had the same problem: a
192 px navigation column left a 207 px card, wrapping "Your projects" and
squeezing every project card.

The app is now portrait-first:

- **Editor** — the inspector is a slide-over drawer (width `min(320px, 100vw -
  5rem)`) opened from the tool rail, with a header close button and a tappable
  backdrop. It starts closed, so the preview keeps the full width beside the
  64 px rail. At 411 px the preview canvas is 251×557 (was 0×0).
- **Timeline** — touch uses a 28 px axis, 48 px clip row and 28 px zoom lane,
  shrinking the panel from 188 px to 121 px so the preview gains the space.
- **Header** — the left group gets `minmax(0, 1fr)` instead of 7 px, decorative
  separators are removed, and the project name wraps to two lines. Upgrade,
  Feedback and account controls that only make sense on desktop are hidden.
- **Dashboard** — the library navigation is a drawer opened from a header
  button; the project card spans the viewport (395 px at 411 px width).
- **Dialogs** — the Publish sheet, the editor Settings dialog, and the
  dashboard Settings page all fit 411 px with internal scrolling; no desktop
  width assumptions remain in their layout.
- **System bars** — the activity remains edge-to-edge, but `MainActivity` pads
  its root view with the status/navigation/cutout/IME insets, so the header is
  no longer under the clock and the timeline clears the gesture bar.
- **Orientation** — `MainActivity` is locked to portrait, matching the layout
  that was tuned and verified.

Verified on the API 37 emulator at 411×914: the preview renders and plays, the
inspector opens from every rail icon and closes via X or backdrop, the dashboard
shows full-width project cards with thumbnails, both drawers open and close, and
the export/settings dialogs fit. Evidence:
`dev/verification/mobile-editor-final2.png`, `mobile-editor-playing2.png`,
`mobile-drawer-open-2.png`, `mobile-dashboard-3.png`,
`mobile-dashboard-nav.png`, `mobile-dashboard-settings-2.png`,
`mobile-export-dialog.png`, `mobile-settings-dialog.png`.

Remaining polish: real-device validation is pending.

### Mobile settings (2026-11-20)

The editor and dashboard settings now hide desktop-only surfaces on touch:

- `ApplicationSettings` (taskbar/Dock, launch at login) is not rendered.
- Updates, Shortcuts, and Account categories are removed from the tab list.
- The Storage folder pickers are hidden; Android keeps recordings/projects in
  app-private storage and the library is the entry point.
- General keeps Appearance and Language; Recording and Motion keep their
  controls.

Verified on the API 37 emulator at 411 px: the dialog tabs are General,
Recording, Motion, Dev (debug only); General shows Appearance and Language only,
with no taskbar, updates, shortcut, account or folder rows. Evidence:
`dev/verification/settings-mobile2.png`.

### Project cards on touch (2026-11-20)

Project cards no longer render the hover-only "Add folder" pill on touch.
Folder assignment moved into the card's ••• menu as a checkable list, and the
folder chips still remove an assignment with a tap. `tsc` and the renderer build
pass; the emulator click-through was blocked by host memory pressure (see
Verification limits).

### Auto-zoom on touch (2026-11-19)

Auto-zoom is the app's key feature, and several desktop assumptions broke it on
a phone:

- **Taps merged.** Clicks within 2.5 s became one zoom region, so a demo that
  tapped every second or two zoomed once and never zoomed out between taps.
- **Auto-apply was skipped.** The hook required `ui.isPreviewReady`; at 411 px
  the editor preview collapses to a 0-width canvas, so the flag never flipped.
  A latching ref then treated the take as already handled. This is why some
  takes had no auto zooms until the button was pressed manually.
- **Framing followed the cursor.** Auto regions used cursor-follow framing, so
  scroll positions (which the accessibility sampler emitted as `move` samples)
  could pan the camera during a zoom.
- **Connected zooms** kept the camera between nearby taps instead of returning
  wide.
- **System UI taps zoomed.** The notification's Stop/Pause taps were recorded
  as interactions and produced a zoom at the end of every take.

The touch policy now is:

- **Telemetry is tap-only.** `TYPE_VIEW_SCROLLED` produces no samples, and events
  from SystemUI, permission controllers, and Recordly's own package are ignored.
  The recording still shows the swipe; it just does not drive zoom.
- **One region per tap.** `touchZoomPolicy.ts` passes `mergeGapMs: 800`,
  `padMs: 450` and a 360 ms trailing reserve to both auto-apply and the
  "Apply auto zooms" action. Taps under 800 ms apart (typing, double taps) still
  merge; every other tap gets a 2 s region with room to zoom in and out.
- **Tap-anchored framing.** `connectZooms=false`, `zoomClassicMode=true` and
  `zoomMotionBlur=0` are part of `TouchPresentation`, so the camera stays on the
  tapped view, returns wide between taps, and text stays crisp.
- **Auto-apply runs without a preview.** On a touch host the ready gate needs
  only the media duration and an initialized clip; the skip no longer latches.

A separate capture fix came out of this work: `moveTaskToBack(true)` is a no-op
when Recordly is the root of its task, which is the normal cold-start case, so
the recorder stayed on screen and recorded itself. It now calls
`moveTaskToBack(false)`.

Verified on the API 37 emulator with three Settings taps (~5 s apart) and one
swipe: the recording produced exactly three regions, each 2 s and covering its
tap; the swipe and the notification Stop tap produced none. A separate
telemetry project re-generated a depth-1 region through the UI, confirming the
touch depth. Evidence: `dev/verification/autozoom-report.json`,
`pad75-3.5.png`, `pad75-5.0.png`, `pad75-6.5.png`, `pad75-in-sheet.png`,
`pad75-out-sheet.png`, `pad75-zoom.mp4`. Regression:
`node dev/verify-autozoom.mjs` and four unit tests in
`recordly-upstream/src/components/video-editor/touchZoomPolicy.test.ts`.

The preview is visible by default now, so zooms can be judged live; opening the
settings drawer covers it until the drawer is closed.

### Touch presentation (2026-11-19)

Taps used to inherit Recordly's cursor rendering: the editor and exporter drew
the selected cursor artwork at every tap position, so a mouse arrow appeared in
the final video even though no pointer was ever used. Android telemetry comes
from accessibility click events, so the correct visual is a touch indicator.

Upstream already separates the two drawings: `showCursor` gates the sprite, and
`cursorClickEffect` draws at the press position regardless. `TouchPresentation`
therefore stores:

- `showCursor: false` — no arrow, in preview or export.
- `cursorClickEffect: "echo"` with `#2563EB` — blue concentric rings at the tap.
  A white or gray pulse disappears on a light notification card, so this is a
  platform policy rather than a preserved desktop choice; any stored effect is
  normalised to `echo` on load/save.
- `cursorIdleHide.enabled: false` — idle-hide fades an unattended cursor, and
  with tap-only samples its opacity is 0 at the instant of each tap, so the
  pulse would fade in after the finger.

The policy is applied wherever settings enter Kotlin: project `editor` objects
on `save`, `load` and `preview`, and the `recordly.editor.preferences` app
setting on `getAppSetting` / `setAppSetting` (with a seeded value on first run).
That covers restored projects, projects imported from desktop, new takes and
renderer reloads without patching upstream `src/`.

Verified on the API 37 emulator with a real accessibility-driven take: two
Settings taps were written to the sidecar, the project exported to 1280x720,
and a frame 150 ms after the first tap shows the blue rings centered on the
tapped Pause control. A frame with no nearby tap shows no indicator, and the
source recording contains no pointer. Evidence: `dev/verification/touch-export.mp4`,
`dev/verification/touch-frame-tap.png`, `dev/verification/touch-frame-no-tap.png`.

Known limit: the Cursor settings panel still exposes a "show cursor" switch. It
can enable a sprite for the current session; the next save/reload coerces it
back off. Replacing that control with a touch-indicator control is follow-up
work.

### Export (2026-11-18)

A 65.8-second 1080x2400 project exported to a 1920x1080 30 fps H.264 MP4
(14.07 MB) through the in-WebView WebCodecs path and the HTTP byte channel.
The emulator has no hardware H.264 encoder, so the export used the software
encoder and took about six minutes. The first save failed with
"Failed to save video": the renderer sends `outputPath: null`, and
`JSONObject.optString` turns JSON null into the string `"null"`, which was
then treated as a relative path and rejected as outside app storage. Fixed in
`ExportStreams.finalize`; the same file then finalized to
`files/media/exports/` and appeared in MediaStore as
`Movies/Recordly/probe-export.mp4` (duration 65833 ms).

The full UI path was then exercised on the 2:14.9 project at HD
(1280x720/30 fps): the exported MP4 is 134.9 s, 4047 frames, 8.59 MB, and
MediaStore lists `Movies/Recordly/20261117-100111-f54c0f.recordly.mp4` with
duration 134900 ms. It took about 9.5 minutes on the emulator's software
encoder.

A separate 15.3-second take with microphone audio exported to 1280x720/30 fps
H.264 plus a 48 kHz mono AAC track (711 packets), 1.30 MB. Both streams decode.
The emulator's microphone is effectively silent (-78 dB mean), so real-device
level and sync checks remain.

### Appending a recording (2026-11-18)

`importRecording` concatenated `recording-20261117-100013.mp4` (65.805 s) and
`recording-20261117-015359.mp4` (69.063 s) into one 134.868-second,
1080x2400, 258-frame MP4 in 776 ms. A keyframe sits exactly at 65.805 s, and
`ffmpeg -f null -` decodes the whole file (the same VFR timestamp warnings
appear on the source captures). The editor's Clips panel added it to the
timeline (`Video added to timeline`, duration 2:14.9), the project saved the
new source, and it reopened at 2:14.9 after a force-stop and relaunch.

The same operation with a microphone recording (15.326 s, AAC 48 kHz mono)
appended at 134.868 s produced a 150.194-second file with the AAC track
starting at 134.883 s (711 packets); both streams decode. Appending a second
file that has no audio, or one that does, is therefore supported; only
mismatched resolutions or codec configurations are refused.

The Clips panel was empty before this work: `Regex.matches` requires the whole
string, so the library's `\.mp4$` pattern matched nothing. The media patterns
now start with `.*`.

### Video wallpapers (2026-11-18)

Tapping the Scene panel's "Add wallpaper" opened the system document picker
(`onShowFileChooser`); choosing a 777,386-byte MP4 uploaded the `File` over the
export byte channel, stored it under `media/imported-wallpapers/`, served it
back at a loopback URL that plays (2400x1080, 13.9 s), applied it as the
project wallpaper, and saved that absolute path into the project JSON.

### Capture and library (2026-11-17)

On the API 37 `Medium_Phone_API_37.0` emulator, recording from the HUD and
stopping through the foreground notification produced a 1080x2400 H.264 MP4
(`recording-verification-20261117-100013.mp4`). `ffprobe` found 86 video
samples over 65.8 seconds; `ffmpeg` decoded the file, and the editor reopened
without a video-load error. A decoded frame at 30 seconds shows the emulator
home screen. This run exercised an automatic pause and resume
when the capture UI returned to the home screen. Microphone capture, cursor
telemetry, and export were not part of this run.

A second API 37 emulator recording exercised microphone capture across two
pauses. The saved 15.3-second MP4 has H.264 video and 48 kHz mono AAC audio;
both streams decode, and the editor loaded it. The emulator's audio level was
very low, so a real-device microphone check remains. The project library
generated and displayed a cached PNG from the recording. Android's document
picker copied a 6-second MP4 from Downloads into app storage, and the editor
loaded it at 640x360 with no media error. Importing a project file whose source
video was already in app storage created a separate project ID, and its
15.3-second recording opened in the editor.

For WebView diagnostics, use `chrome://inspect` with a connected device.
Android logs: `adb logcat -s Recordly RecordlyWeb RecordlyServer NativeBridge`.
