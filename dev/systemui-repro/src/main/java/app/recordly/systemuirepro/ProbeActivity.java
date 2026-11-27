package app.recordly.systemuirepro;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Rect;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class ProbeActivity extends Activity {
    private static final int REQUEST_DISPLAY = 1;
    private Button start;
    private TextView instructions;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(pad, pad, pad, pad);
        layout.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            var bars = windowInsets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout());
            view.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom);
            return windowInsets;
        });
        instructions = new TextView(this);
        instructions.setText("This diagnostic records only video for 18 seconds. "
                + "Use non-sensitive screens. After approving, go to Home, open the "
                + "notification panel, close it, and open Display Settings.");
        layout.addView(instructions);

        start = new Button(this);
        start.setText("Start 18-second capture");
        start.setOnClickListener(view -> {
            start.setEnabled(false);
            MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
            startActivityForResult(manager.createScreenCaptureIntent(
                    MediaProjectionConfig.createConfigForDefaultDisplay()), REQUEST_DISPLAY);
        });
        layout.addView(start);

        Button stop = new Button(this);
        stop.setText("Stop capture");
        stop.setOnClickListener(view -> startService(new Intent(this, ProbeService.class)
                .setAction(ProbeService.STOP)));
        layout.addView(stop);
        setContentView(layout);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQUEST_DISPLAY) return;
        start.setEnabled(true);
        if (result != RESULT_OK || data == null) return;
        Rect bounds = getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();
        startForegroundService(new Intent(this, ProbeService.class)
                .setAction(ProbeService.START)
                .putExtra(ProbeService.CONSENT, data)
                .putExtra(ProbeService.WIDTH, bounds.width() & ~1)
                .putExtra(ProbeService.HEIGHT, bounds.height() & ~1)
                .putExtra(ProbeService.DENSITY, getResources().getConfiguration().densityDpi));
        instructions.setText("Capture requested. Go to Home, open the notification panel, "
                + "then close it and open Display Settings. Recording stops automatically.");
    }
}
