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
cannot finish before the cut. Repeated taps in a short burst share a visit.

The fit is saved on newly generated touch regions, so preview and export use
the same timestamps and reopening a project does not change the curve.
Existing zoom edits keep their boundaries and curve. Existing Android
projects and preferences migrate the previously forced zero-blur default;
later blur changes, including disabling it, persist.

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
path. Recordly previously used only the no-arg user-choice intent. It now
requests the default display on API 34+ with the no-arg intent as a fallback.
The static evidence does not prove the installed 2.5.7 build uses the same
branch at runtime; a controlled Nord 5 take decides whether this changes the
SystemUI result.

1. Install the new API 34+ build, which requests the default display directly.
   Confirm the system share prompt (the source chooser may be gone), wait on a
   neutral screen, open and dismiss the shade mid-take, then stop. Check the
   original MP4; score the mid-take shade separately from the start/stop
   transitions. Make a visible screen change after dismissing the shade to test
   recovery independently of the stop and a static screen's variable frame
   rate. Keep test content non-sensitive.
2. Compare Android's active projection metadata and relevant platform logs
   during a fresh XRecorder and Recordly session on the same phone, to see
   whether Android is routing the captures differently. Respect protected
   content. A production fix passes only if
   Recordly's raw MP4 shows the real status icons and an opening, visible,
   and dismissing notification panel on-device, with privacy settings and
   the approved motion unchanged. Lower resolution is a diagnostic, not an
   accepted shipping trade-off.

For a take that needs SystemUI today, verify the built-in recorder's clip shows
the desired UI before importing it. It will not have Recordly tap telemetry;
zooms must be placed manually. Trimming, freezing or cropping does not restore
SystemUI and is not a capture fix.
