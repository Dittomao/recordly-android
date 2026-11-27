# OnePlus support report: System UI black in full-display capture

**Status:** Reproduced on the Nord 5. The user submitted the report via
OnePlus India support. Case reference and vendor response are pending.

**Device:** OnePlus Nord 5, CPH2707, Android 16,
`CPH2707_16.0.5.1301(EX01)`. The tested display was 1080×2378 at 480 dpi.
Android screen-sharing privacy settings were unchanged.

**Expected:** With user consent to capture the entire display, Home's
normal status icons and the non-sensitive parts of the notification panel
or Quick Settings appear in the recording. Android should still redact
content it protects, such as verification codes and secure app windows.

**Observed:** In Recordly's raw recordings, Home and other ordinary apps
are visible but the status-bar region remains black. The center goes
black for several seconds around opening the notification panel and
recovers after dismissing it and opening Display Settings. The same issue
occurs when Android's public `ImageReader` receives frames directly from
`MediaProjection`, without any encoder or editor. XRecorder 2.5.7 installed
from Google Play recorded the live status icons, notification panel, and
Quick Settings on the same phone. Android reported standard screen capture
for XRecorder at 720×1584 and 1 dpi; applying that same size and density to
Recordly did not restore the missing UI. Testing API target 36 and
attaching the encoder surface at display creation also did not fix it.

**Standalone result (2026-11-22):** The replay below produced a playable
18.09 s, 1080×2378 H.264 video with no audio. The top 4.2% of the frame was
near-black in all 90 frames sampled at 5 fps. The center was near-black in
37 samples, including a continuous span around 4.0–10.0 s. Home and
Display Settings were visible at other points. The standalone app has no
Recordly editor or native recording pipeline, overlay, audio, or telemetry.

**Minimal replay:**

1. Use the standalone [System UI Capture Repro](../dev/systemui-repro/) APK
   on the same build. Tap **Start 18-second capture** and approve the
   entire-display consent prompt.
2. Go to Home, open the notification panel for three seconds, close it,
   and open **Display & brightness** before the timed stop.
3. Inspect the debug app's `files/probe.mp4` before any editing. Record
   whether the top region and the panel are visible; do not use personal
   notification content for the test.

**Firmware finding:** Both Recordly and the standalone app logged
`ContentRecorder: Setting secure flags on mirrorRoot surface`. On this
exact firmware, that log is on the branch where the OxygenOS
`ScreenProtectionController` decides that projection protection is needed.
The controller checks its exceptions by package and signing certificate.
The installed XRecorder 2.5.7 APK's verified signing certificate matches
its built-in exception entry. Recordly and the standalone probe are not
in the default exceptions. We did not change the firmware or its settings.

**Independent Play app check:** ADV Screen Recorder 4.22.1 (target SDK 37),
installed from Google Play, reproduced the issue on the same phone. Home
appears without its normal status icons in the original clip, and the
center goes near-black from 1.8 to 10.2 s during the shade test before
recovering to Home. `ScreenProtectionController` explicitly classified
the ADV session as `should protected`, followed by the same secure
mirror-root marker. ADV's package is absent from the built-in exceptions.

**Request for OnePlus:** Can OxygenOS leave non-sensitive System UI visible
to user-approved full-display recording while preserving redaction of
protected content? If behavior must remain app-specific, is there an
official compatibility process for Recordly's own production package and
signing certificate? We are not requesting access to protected content.

Share only the minimal APK and its source, the exact device build, and
sanitized timing/log snippets, subject to approval. Do not attach user
recordings, notification text, a device serial, or a full device bugreport.
