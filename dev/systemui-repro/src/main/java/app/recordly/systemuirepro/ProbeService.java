package app.recordly.systemuirepro;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public final class ProbeService extends Service {
    static final String START = "app.recordly.systemuirepro.START";
    static final String STOP = "app.recordly.systemuirepro.STOP";
    static final String CONSENT = "consent";
    static final String WIDTH = "width";
    static final String HEIGHT = "height";
    static final String DENSITY = "density";
    private static final String CHANNEL = "projection";
    private static final String TAG = "SystemUiCaptureRepro";
    private static final int NOTIFICATION_ID = 1;
    private static final int DURATION_MS = 18_000;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable timeout = () -> finishCapture(true);
    private final MediaProjection.Callback revoked = new MediaProjection.Callback() {
        @Override public void onStop() {
            finishCapture(true);
        }
    };
    private MediaProjection projection;
    private VirtualDisplay display;
    private MediaRecorder recorder;
    private File stagingFile;
    private boolean starting;
    private boolean recording;
    private boolean finishing;

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public int onStartCommand(Intent command, int flags, int startId) {
        if (command != null && START.equals(command.getAction())) {
            if (!starting && !recording && !finishing) startCapture(command);
        } else if (recording) {
            finishCapture(true);
        } else {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void startCapture(Intent command) {
        starting = true;
        try {
            NotificationManager notifications = getSystemService(NotificationManager.class);
            notifications.createNotificationChannel(new NotificationChannel(
                    CHANNEL, "Screen capture", NotificationManager.IMPORTANCE_LOW));
            Notification notice = new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .setContentTitle("System UI capture test")
                    .setContentText("Video only. Stops automatically.")
                    .setOngoing(true)
                    .build();
            startForeground(NOTIFICATION_ID, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);

            Intent consent = command.getParcelableExtra(CONSENT, Intent.class);
            int width = command.getIntExtra(WIDTH, 0);
            int height = command.getIntExtra(HEIGHT, 0);
            int density = command.getIntExtra(DENSITY, 0);
            if (consent == null || width <= 0 || height <= 0 || density <= 0) {
                throw new IllegalArgumentException("Missing consent or display dimensions");
            }
            projection = getSystemService(MediaProjectionManager.class)
                    .getMediaProjection(Activity.RESULT_OK, consent);
            if (projection == null) throw new IllegalStateException("Android did not grant projection");
            projection.registerCallback(revoked, main);

            stagingFile = File.createTempFile("capture-", ".mp4", getFilesDir());
            recorder = new MediaRecorder(this);
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            recorder.setVideoSize(width, height);
            recorder.setVideoFrameRate(30);
            recorder.setVideoEncodingBitRate(12_000_000);
            recorder.setOutputFile(stagingFile.getAbsolutePath());
            recorder.prepare();
            display = projection.createVirtualDisplay("SystemUiCaptureRepro", width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, recorder.getSurface(), null, null);
            if (display == null) throw new IllegalStateException("Android did not create the display");
            recorder.start();
            recording = true;
            Log.i(TAG, "Started " + width + "x" + height + " @ " + density + " dpi; no audio");
            main.postDelayed(timeout, DURATION_MS);
        } catch (Exception error) {
            Log.e(TAG, "Could not start public MediaProjection capture", error);
            Toast.makeText(this, "Capture failed. See diagnostic log.", Toast.LENGTH_SHORT).show();
            finishCapture(false);
        } finally {
            starting = false;
        }
    }

    private void finishCapture(boolean keepRecording) {
        if (finishing) return;
        finishing = true;
        main.removeCallbacks(timeout);
        boolean stopped = false;
        if (recording && recorder != null) {
            recording = false;
            try {
                recorder.stop();
                stopped = true;
            } catch (RuntimeException error) {
                Log.w(TAG, "MediaRecorder could not finalize the clip", error);
            }
        }
        if (display != null) {
            display.release();
            display = null;
        }
        if (recorder != null) {
            recorder.release();
            recorder = null;
        }
        if (projection != null) {
            projection.unregisterCallback(revoked);
            projection.stop();
            projection = null;
        }
        if (stagingFile != null) {
            try {
                if (keepRecording && stopped && stagingFile.length() > 0) {
                    Files.move(stagingFile.toPath(), new File(getFilesDir(), "probe.mp4").toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                    Log.i(TAG, "Saved files/probe.mp4");
                    Toast.makeText(this, "Test recording saved", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception error) {
                Log.e(TAG, "Could not save test clip", error);
            } finally {
                if (stagingFile.exists() && !stagingFile.delete()) {
                    Log.w(TAG, "Could not delete this test's scratch file");
                }
                stagingFile = null;
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() {
        finishCapture(false);
        super.onDestroy();
    }
}
