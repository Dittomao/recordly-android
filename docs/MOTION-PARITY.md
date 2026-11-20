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
mid-take and opening again to stop it. This points to the device's
third-party screen-share path, not single-app selection, an output crop, or
the export compositor. The experimental scope change was removed. For a
take that needs the status bar or shade on this phone, use the built-in
recorder and import the MP4; it will not have Recordly tap telemetry.
