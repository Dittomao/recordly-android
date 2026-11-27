# Windows reference → Android motion

**Approved Android baseline.** The verified export below is the reference for
the app's default auto-zoom. Keep its timing, framing, and effects as shipped;
compare a fresh in-app export against it before changing the motion defaults.
Physical-device validation is a release check, not another tuning pass.

`portfolio.mp4` is an 81.05 s Windows reference export: 1920×1080, H.264 Main,
60 fps constant frame rate, 37.64 Mb/s video. It is a finished composite, not
the original footage or project, so exact effect settings cannot be recovered
from it.

## What to match

- **Wide pose.** The source is a rounded, shadowed rectangle over wallpaper.
  At rest it spans about 86% of frame width. Preserve that composition in
  portrait rather than stretching a landscape layout.
- **Camera.** Frame tracking around 2.40 s and 77.76 s shows a move to roughly
  1.5×. The visible 10–90% travel takes about 0.4 s, then the camera settles into
  the hold. The move out decelerates back to the same wide composition. The
  reference also has cursor-led pans and page animations; those are not all
  camera moves.
- **Effects.** Fast camera travel softens edges briefly, with sharp text when
  the camera is still. Around 77.8–78.1 s this is visible before the page itself
  scrolls. Android uses the same renderer blur, at a restrained default of
  `0.20`, and replaces the Windows cursor with a 420 ms blue tap pulse. The pulse
  is attached to the source coordinates, not a fixed overlay on the export.

## Android timing

Start each spaced tap's approach 200 ms before the detected press. Keep the
adaptive 1.25× / 1.5× / 1.8× framing, the Focused spring shape, and a wide beat
of at least 100 ms between automatically scheduled visits in the same clip.
When there is not enough time for a full visit, shorten the block and fit its
two spring ramps around a brief hold. Independent ramps could almost cancel
each other in a short block; resetting the spring or snapping the camera is
not needed. Very late taps still draw a pulse rather than starting a move that
cannot finish before the cut. Repeats near the same focus can share a visit.
Taps on different targets get separate visits when the 200 ms lead, 100 ms
wide beat, and 320 ms post-release hold fit. Otherwise the later tap keeps
its pulse without extending the earlier visit. A late tap cannot cancel an
earlier visit that fits.

The fit is saved on newly generated touch regions, so preview and export use
the same timestamps and reopening a project does not change the curve.
Existing zoom edits keep their boundaries and curve. Existing Android
projects and preferences migrate the previously forced zero-blur default;
later blur changes, including disabling it, persist.

## Recorder presentation (app chrome)

This is separate from the approved camera motion above. The floating setup is
the actual Sileo 0.1.6 component, not a native drawing: `SileoOverlay.kt` hosts
`recorder.html` in a transparent WebView, and the two SVG rectangles, Gaussian
blur/alpha-threshold filter, 600 ms spring with 0.25 bounce and header morph
come from the vendored MIT source (`src/components/launch/sileo`).

- **Entrance:** the native window is placed at the top from its first frame;
  there is **no bottom-to-top travel**. The page pre-paints one Sileo-shaped
  boot pill, which performs the only entrance: a 500 ms top-anchored pop
  (scale/translate/opacity) with a small spring overshoot. When it finishes, the
  real Sileo pill is revealed in the same frame at identical geometry and the
  boot is removed, so there is no second entrance. Arrival is **compact**: it
  does not auto-expand. The native window is pill-sized (380×128 px at 360 dp),
  so taps beside it reach the app.
- **Expansion:** tapping the whole 40 px pill widens the native window and
  uncovers the already-laid-out controls; the WebView keeps the expanded width
  from the first frame, so the pill/header is never reflowed horizontally.
  The recorder's Sileo spring is critically damped (`bounce: 0`): it expands
  from the pill to the measured body once and stops, with no overshoot or
  settle-back wobble. The body fill is opaque from the first pixel; only its
  height animates, so the app behind cannot ghost through the card. Content
  fades in over 160 ms (the upstream toast delay is shortened so the setup never
  shows an empty black card).
- **Idle fold:** 20 s without pointer or keyboard activity folds the body back
  to the compact pill; any interaction resets the timer. The pill remains and
  can be tapped again.
- **Fold/dismiss:** a touch outside the compact window is observed with
  `FLAG_WATCH_OUTSIDE_TOUCH` and folds the body without consuming the app's tap.
  System Back or Cancel closes after the exit animation; leaving TapSwoop
  dismisses the setup.
- **Early start:** Home starts the overlay on the press, while its own 320 ms
  Record reveal is running. The press never expands the pill; only the pill's
  tap does that.
- **Reduced motion:** `useReducedMotion` sets the SVG targets to zero and the
  boot pop to zero; the state change remains.
- **Capture boundary:** the setup is removed before the first captured frame.
  A visible secure overlay left a black pill in the API 37 raw MP4, and an
  ordinary overlay is recorded. Pause and Stop remain in the foreground
  notification; this presentation change does not alter capture, telemetry,
  auto-zoom or export timing.

## Verified output

The API 37 emulator recorded four Clock taps and one swipe. The four saved
zooms each had a 200 ms lead, and the two closely spaced pairs had exactly
100 ms between their blocks. No swipe zoom was generated.

The in-app MP4 export was 576×1280 H.264 Main, 15.25 s, with 915 frames at a
constant 60 fps. Tracking rendered frames gave peaks of 1.50×, 1.25×, 1.50×,
and 1.50×. The normal moves took about 0.32–0.33 s from 10–90% of travel; the
compact moves took 0.17–0.18 s and still returned to the wide pose. The
frame sheets show the brief blur during travel, sharp holds, and tap rings
landing on the keys.

Run `node dev/verify-autozoom.mjs` then `node dev/verify-motion-export.mjs`
with the default motion settings to regenerate
`dev/verification/android-motion-parity.mp4` and its report.
Inspect the video from about 8.3 s for the zoom sequence. The short protected
screen at the end is from the test bringing the HUD forward to stop capture.

Physical finger coordinates and non-clickable surfaces are not available from
Android's observe-only accessibility events; the touch target is the
clickable view's center.

### Cut Room regression check

On 2026-11-22 the TapSwoop debug APK repeated the API 37 emulator test. Four
taps produced four fitted regions at depths 1.50× / 1.25× / 1.50× / 1.50×,
all with 200 ms lead. The two closely spaced pairs retained their 100 ms
wide beat; the swipe produced no zoom. **Export → Save to device** produced
576×1280 H.264, 932 frames at constant 60 fps, with less than 0.001 ms frame
interval error. MediaStore confirmed the completed entry in `Movies/TapSwoop/`.
This checks the new UI and package against the existing motion contract;
it does not replace the frame-tracked reference above.

On 2026-11-23 the same project was exported again after adding the Android
preview canvas-copy path. The result was again 576×1280 H.264 with 932 frames
at a constant 60 fps (0.0007 ms maximum frame-interval error). The canvas copy
changes only how the WebView preview samples decoded frames; export timing and
the motion contract are unchanged.

### Tap targeting follow-up

Rechecked the attached Windows export around 77.8 s: the camera approaches
the target, holds, then returns to the same wide framing. A page scroll
overlaps the return, but does not by itself initiate another camera visit.
The Android follow-up changes only which taps get visits, not the spring,
adaptive depth, blur, pulse, or export defaults. The empty Zoom lane now
scrubs in Cut Room; a centered zoom is created only by the explicit
**Add zoom** action, not by touching an empty lane.

On the rebuilt API 37 APK, four Clock taps again produced four fitted
visits, a 200 ms lead for each, and no extra visit for the swipe. The frame
sheet shows the return to wide between visits. The in-app export was
576×1280 H.264 with 1,054 frames at 60 fps CFR and less than 0.001 ms of
frame-interval error.

One saved Nord 5 example had an empty cursor sidecar and a manual
viewport-centered zoom. In that file the manual block and the absent tap
events are separate issues. A subsequent 15.94 s Play Store and Chrome take
also saved zero tap samples, this time with no zoom blocks.

On the same Nord 5, a controlled probe distinguished the event types:
Play Store's Games/Apps tabs emitted `TYPE_VIEW_SELECTED` with the selected
tab's bounds, and Chrome's three-dot button opened a separate popup exposing
`app_menu_list` but did not send `TYPE_VIEW_CLICKED`. Chrome's tab switcher
did send a normal click. The capture service now uses the selected bottom-tab
bounds and the actual Chrome toolbar button bounds for those two cases only.
A swipe, a generic selection, a page load and SystemUI events do not trigger
new fallbacks. The app does not record control text or intercept touch input.
Unreported taps from already-saved recordings cannot be reconstructed.

After installing that build, a 16.73 s Nord 5 take saved one press/release
pair for the Play Store profile button at 10.456 s. The editor saved one
fitted zoom starting at 10.256 s, and no extra zoom during the scroll.
That take did not switch tabs or open Chrome's menu, so it did not exercise
either fallback. API 37 regression: four Clock taps, four
fitted zooms, no swipe zoom; the 576×1280 H.264 export has 1,184 frames at
60 fps CFR and less than 0.001 ms of frame-interval error.

In a controlled, consented Nord 5 take, Play Store's **Games** and **Apps**
tabs and Chrome's overflow button each produced one press/release pair and
one fitted zoom, with a 200 ms lead. The Play Store swipe did not create a
zoom. This verifies those two semantic event paths on real hardware, not
arbitrary Chrome page gestures.

The 16.18 s portrait reference (`WhatsApp Video 2026-11-20 at 10.05.30 PM.mp4`)
shows the wide frame between Clock interactions and close holds on the
selected controls. The launcher probe on the Nord 5 observed the clickable
Play Store dock icon at its actual accessibility bounds, but tapping it did
not send a launcher click event. The service associates a direct launch from
the visible OxygenOS dock with the accessibility bounds of the known icon.
On a consented take this caught the Chrome dock tap, but missed a cold Play
Store launch. The swipe and Recents return produced no extra zooms.
All four saved zooms on that take started 200 ms before their detected press.

Separate cold-launch probes observed an initial splash `FrameLayout` for
both apps, then the matching activity 708 ms later for Play Store and
465 ms later for Chrome. The recorder now waits up to 1.5 s for that
confirmation, and uses the splash timestamp instead of the later activity
timestamp for cold starts. Other window transitions clear the candidate.
The time is an estimate, not a raw finger timestamp.

On the final debug APK, a consented Nord 5 take with both apps cold recorded
five press/release pairs: Play Store dock, Games, Apps, Chrome dock, and
Chrome overflow. All five produced fitted 1.8× edge-target zooms with 200 ms
lead and at least 100 ms wide between visits. The Play Store swipe, menu
dismissal, and Recents return produced no extra samples or zooms. The API 37
Clock tap/swipe check still passed on this APK.

The preceding launcher build passed the API 37 Clock tap/swipe check. The
export verifier timed out while saving, but the completed file on the device
was independently checked: 576×1280 H.264, 1,127 frames at constant 60 fps
with less than 0.001 ms frame-interval error.

### Physical phone: OnePlus Nord 5

The approved build exported 36.10 s at 870×1920 (portrait FHD), H.264 High,
with 2,166 frames at a constant 60 fps. The largest measured frame-interval
error was under 0.001 ms. Four taps produced four regions with the 200 ms
lead, and the swipe produced no extra region. In the two measurable Clock
segments, frame tracking confirmed the 1.8× edge zooms and the return to the
wide pose. The local export and frame-timing report are in `dev/verification/`.

**Capture follow-up:** The raw MP4 is missing the status bar throughout and
has black content at about 9.2–15.6 s and 34.4 s to the end, with the navigation
indicator still present. The raw capture shows both Clock and the launcher, so
single-app sharing does not explain this take. These are source-capture defects,
separate from the verified 60 fps export and zoom timing.

A 20.2 s follow-up Settings take reproduced the missing status bar in all 101
frames sampled at 5 fps. The middle of the take retained visible content;
the picture was black around the opening transition (0.2–2.4 s) and at the
end (18.8 s onward). The 14 s built-in recorder comparison kept status icons
on settled Settings screens and showed no fully black center in 70 frames
sampled at 5 fps. OxygenOS's built-in recorder is a privileged system app.

An explicit full-display MediaProjection request reproduced the same result:
all 108 sampled frames of the 21.6 s raw capture had a black status-bar strip.
The device logged a 1080×2378 mirror at scale 1.0 with no offset, and logged
applying secure flags to its mirror surface. Black sections around 8.2–9.4 s
and 20.4 s to the end line up with the system notification shade opening
mid-take and opening again to stop it. This rules out single-app selection
and an editor crop or export problem for those takes. It does not prove which
platform policy or recorder interaction caused each blank span, or that other
Play Store recorders fail. The experimental scope change was removed.

#### Next SystemUI capture check

**Same-phone comparison (2026-11-21):** On Android 16,
`CPH2707_16.0.5.1301(EX01)`, XRecorder 2.5.7 installed from Google Play
recorded the real status icons, notification shade, and Quick Settings in its
original 720×1584 H.264 MP4. It had no near-black top strip or central region
in 56 frames sampled at 5 fps. Two new Recordly raw MP4s (1080×2378) had a
near-black top strip in all 76 and 41 sampled frames, with a near-black
central region in 36 and 20 respectively. The top check uses the top 4.2%
of the frame; the central check excludes the top and bottom 15%. The
global Android screen-share-protection override was off when read; no
privacy settings were changed during analysis. This proves the UI is
recordable by at least one Play-installed app on this build, not why the
two recorders differ. The clips used different content and resolutions,
and the apps target different SDKs (Recordly 35, XRecorder 36).

**Native-only follow-up:** Bypassing the renderer's start flow reproduced the
failure with the countdown, microphone, and Recordly's own window protection
all off. The 8.65 s raw H.264 clip had a near-black top strip in all 43 frames
sampled at 5 fps. Its center turned black in the sampled interval from 4.6 s
through the video tail (20 frames), after the shade was opened about 4.1 s
after native start. That take ended before a fresh visible frame confirmed
recovery after closing the shade. The MP4 and timing report are local only
(`dev/verification/nord5-recordly-native-only.*`).

**Initial-surface probe:** A temporary debug build passed the encoder surface
directly to `createVirtualDisplay` rather than attaching it afterward, with
API target 35 and capture dimensions 1080×2378 unchanged. The 12.70 s
recording still had a near-black status strip in all 63 frames sampled at
5 fps and a near-black center in 23 frames, including a 4.0 s span during
the shade test. Display Settings was visible after dismissal, confirming
capture could recover when the screen changed. The prior APK was restored
and verified byte-for-byte. Artifacts are local only
(`dev/verification/nord5-recordly-initial-surface.*`).

**API 36 probe:** The diagnostic APK differed from the GitHub API 35 build
only in `targetSdkVersion`; the compiled code, resources, and renderer assets
matched. With native video-only capture at 1080×2378, the 11.16 s raw MP4 still
had a near-black top strip in all 56 frames sampled at 5 fps and a near-black
center in 22, including about 4 s around the shade opening. Raising the target
to 36 is not a fix on this build. The prior APK was restored and verified
byte-for-byte; the test did not change project code or Android privacy settings.
Local artifacts: `dev/verification/nord5-recordly-target36.*`.

**Resolution and source probes:** Matching XRecorder's 720×1584 file
dimensions did not help: the status strip was near-black in all 64 sampled
frames, and the center was near-black in 23 frames. A separate full-display
1080×2378 session read Android's public `ImageReader` surface directly. Its
top strip had peak RGB 0 in all 21 samples. Its center also had peak RGB 0
in seven samples during or just after the shade opening, then recovered on
Display Settings. This probe had no encoder, muxer, or renderer in the pixel
path, so the blank areas already exist in what Android delivers to Recordly's
projection. That does not identify the policy or routing difference versus
XRecorder. No source video or audio was saved by the pixel probe. The prior
APK was restored byte-for-byte and the fresh main-profile install retained.
Artifacts: `dev/verification/nord5-recordly-resolution.*` and
`dev/verification/nord5-recordly-source-pixels.*`.

**Public-API comparison:** The XRecorder 2.5.6.1 APKM (package
`videoeditor.videorecorder.screenrecorder`, target SDK 36) contains a public
`MediaProjectionConfig` path. In `classes4.dex`, `lj5.k(MediaProjectionManager)`
requests `MediaProjectionConfig.createConfigForDefaultDisplay()` and passes it
to the API-34 `MediaProjectionManager.createScreenCaptureIntent(config)` when
the config key `AllowRecordSingleAppLocal` is not 1 (its default is 0);
otherwise it falls back to the no-arg single-app/entire-screen chooser.
`gj5.a()` and `hj5.a()` are the small wrappers. The default therefore selects
default-display capture. This is public API, not a hidden-API or privileged
path. The prior GitHub baseline used the no-arg user-choice intent. The local
build now requests the default display on API 34+ with the no-arg intent as
a fallback. This avoids accidental single-app sharing, but an earlier
explicit full-display prototype already reproduced the Nord 5 failure. Do
not treat the consent change alone as a SystemUI fix.

All five direct `MediaProjection.createVirtualDisplay` call sites found in
the supplied base APK's four DEX files pass `VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR`
(`16`), the same flag Recordly requests. There are multiple paths: two request
a density argument of `1`, while others use a stored density; one supplies
an `ImageReader` surface. Static inspection does not tell us which branch
the installed 2.5.7 build used in the successful Nord 5 recording, or how
OxygenOS handled that session. The supplied APK was not installed or run.

**Live XRecorder 2.5.7 observation (2026-11-22):** Android reported a standard
`TYPE_SCREEN_CAPTURE` projection and a 720×1584 virtual display named
`Capturing Display`, with reported density `1 dpi`. A separate Recordly
probe changed only the virtual-display density from its already-failing
720×1584 configuration and verified that Android applied `1 dpi`. The raw
13.45 s MP4 still had a near-black top strip in all 67 sampled frames and a
near-black center in 21 (about 4.2 s around the shade). Android logged
`Setting secure flags on mirrorRoot surface` for Recordly again. That does
not, on its own, say which layer was withheld or why. Matching XRecorder's
dimensions and density did not fix Recordly. The exact prior APK was restored;
the probe did not change the phone's display or privacy settings.
Local artifacts: `dev/verification/nord5-xrecorder-live-display.json` and
`dev/verification/nord5-recordly-density-1.*`.

**Standalone reproduction (2026-11-22):** The [probe](../dev/systemui-repro/)
used a separate package, a `MediaRecorder` surface, and no renderer,
overlays, audio, or telemetry. Its 18.09 s raw 1080×2378 H.264 MP4 again had
a near-black status strip in all 90 samples at 5 fps and a near-black
center in 37. Home and Display Settings were visible at other points.
`ContentRecorder` logged the same mirror-root marker. The failure can
therefore be reproduced without Recordly's capture code. Local-only evidence:
`dev/verification/nord5-standalone-probe.*`.

**Firmware root cause (2026-11-22):** Read-only inspection of the exact
phone's shipped framework ties the runtime marker to the OxygenOS
`ScreenProtectionController`. The log is emitted only when that controller
requires protection for the virtual display owner. Its default exceptions
are keyed by app package and signing certificate. The exact installed
Play XRecorder 2.5.7 signer was verified and matches its built-in entry;
Recordly and the standalone probe are absent from the default list and
both logged the protected branch. Public `MediaProjection` setup does not
confer that exception; neither does distribution through Google Play by
itself. This supports an OEM compatibility request, not spoofing an app
or disabling protection. The user submitted the updated
[OnePlus report](ONEPLUS-SYSTEMUI-REPRO.md) via OnePlus India support.
A vendor response is pending.

**Independent recorder check (2026-11-22):** Google Play's ADV Screen
Recorder 4.22.1 (target SDK 37) reproduced the issue on the same Nord 5
firmware. Its original 17.97 s, 720×1280 H.264 stream showed Home without
the normal status icons, then recovered to Home after the shade test.
At 5 fps, 42 of 90 samples had a near-black center, all in the 1.8–10.2 s
interval. In that session OxygenOS logged `needScreenShareProtection`
with ADV's package and `should protected`, immediately followed by the
secure mirror-root marker. ADV is absent from the built-in initializer.
AZ Screen Recorder and DU Recorder appear there, but neither one's installed
signer or capture behavior was tested. This shows that Play installation
and a current target SDK did not remove the restriction for ADV, not that
every other recorder behaves the same. Local-only evidence:
`dev/verification/nord5-adv-original.mp4`, `nord5-adv-contact.png`, and
`nord5-adv-result.json` in the same directory.

A production fix passes only if Recordly's raw MP4 shows the real status
icons and the notification panel opening, visible, and dismissing on-device,
with privacy settings, normal output quality, and the approved motion unchanged.

For a take that needs SystemUI today, verify the built-in recorder's clip shows
the desired UI before importing it. It will not have Recordly tap telemetry;
zooms must be placed manually. Trimming, freezing or cropping does not restore
SystemUI and is not a capture fix.
