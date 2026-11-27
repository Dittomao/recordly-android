# System UI capture reproduction

Standalone Android diagnostic for the Nord 5 capture investigation. This is
not part of the Recordly app. The package `app.recordly.systemuirepro` can be
installed alongside it without replacing Recordly or sharing its projects.

The probe uses a user-approved default-display `MediaProjection`, a foreground
service, and an H.264 `MediaRecorder` surface. There is no renderer, overlay,
microphone, accessibility service, or network permission. It captures at the
current display's portrait dimensions and density and stops after 18 seconds.

## Build

From the Recordly repository root with Android SDK Platform 35 and a JDK
(for example Android Studio's JDK 21):

```powershell
.\android\gradlew.bat -p dev/systemui-repro :assembleDebug
```

The APK is `dev/systemui-repro/build/outputs/apk/debug/RecordlySystemUiRepro-debug.apk`.
On the managed Windows host, use the existing Android SDK/Gradle environment
from `scripts/build-android.ps1`; the optional `probeDebugKeystore` Gradle
property accepts a path to a local Android debug keystore.

## Reproduce

1. Install the debug APK on the Nord 5 and open **System UI Capture Repro**.
   Keep the phone in portrait and leave system privacy settings unchanged.
2. Tap **Start 18-second capture** and approve the Android system prompt for
   the entire display. Go to Home. Open the notification panel for about
   three seconds, close it, and open **Display & brightness** before the
   timed stop. Use only neutral, non-sensitive screens and notifications.
3. Inspect the original clip for visible status icons on Home, the live
   panel opening/closing, and recovery to Display Settings. An empty, invalid,
   or unfinished MP4 is inconclusive rather than a reproduction of blank frames.

The latest successful clip replaces `files/probe.mp4` in this app's private
storage. On this Windows host, with `RECORDLY_DEVICE` set to the phone serial,
copy only that clip and collect only the diagnostic tag:

```powershell
node dev/adb.mjs exec-out dev/verification/nord5-standalone-probe.mp4 run-as app.recordly.systemuirepro cat files/probe.mp4
node dev/adb.mjs shell logcat -d -s SystemUiCaptureRepro
```

A pass would show that this standalone package and public pipeline can capture
the UI on the same device. It would not, by itself, distinguish package identity
from the simpler capture pipeline; isolate that difference before changing
Recordly. A failure with an otherwise playable clip is a useful small example
for OnePlus. See [the draft support report](../../docs/ONEPLUS-SYSTEMUI-REPRO.md).
