package jackpal.androidterm;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.Button;
import android.graphics.Color;
import android.view.Gravity;
import android.util.TypedValue;

public class SetupActivity extends Activity {
    private ProgressBar progressBar;
    private TextView statusText;
    private TextView percentText;
    private Button retryButton;
    private Handler handler;
    private LinuxEnvironment linuxEnv;
    private PowerManager.WakeLock wakeLock;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PismoTerminal:Setup");
        wakeLock.acquire(30 * 60 * 1000L);

        handler = new Handler(Looper.getMainLooper());
        linuxEnv = new LinuxEnvironment(this);

        if (linuxEnv.isSetupComplete()) {
            launchTerminal();
            return;
        }
        createUI();
        startSetup();
    }

    private void createUI() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setBackgroundColor(Color.parseColor("#1a1a2e"));
        layout.setPadding(48, 48, 48, 48);

        TextView title = new TextView(this);
        title.setText("Pismo Terminal");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        title.setGravity(Gravity.CENTER);
        layout.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Setting up Linux environment...");
        subtitle.setTextColor(Color.parseColor("#888888"));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, 16, 0, 48);
        layout.addView(subtitle);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        LinearLayout.LayoutParams pparams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pparams.setMargins(0, 0, 0, 16);
        progressBar.setLayoutParams(pparams);
        layout.addView(progressBar);

        percentText = new TextView(this);
        percentText.setText("0%");
        percentText.setTextColor(Color.WHITE);
        percentText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        percentText.setGravity(Gravity.CENTER);
        layout.addView(percentText);

        statusText = new TextView(this);
        statusText.setText("Initializing...");
        statusText.setTextColor(Color.parseColor("#aaaaaa"));
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(0, 24, 0, 0);
        layout.addView(statusText);

        retryButton = new Button(this);
        retryButton.setText("Retry");
        retryButton.setVisibility(View.GONE);
        retryButton.setOnClickListener(v -> startSetup());
        layout.addView(retryButton);

        setContentView(layout);
    }

    private void startSetup() {
        retryButton.setVisibility(View.GONE);
        progressBar.setProgress(0);
        percentText.setText("0%");
        statusText.setText("Starting setup...");

        new Thread(() -> {
            linuxEnv.setup(new LinuxEnvironment.SetupCallback() {
                @Override
                public void onProgress(String message, int percent) {
                    handler.post(() -> {
                        statusText.setText(message);
                        progressBar.setProgress(percent);
                        percentText.setText(percent + "%");
                    });
                }

                @Override
                public void onComplete(boolean success, String error) {
                    handler.post(() -> {
                        if (success) {
                            launchTerminal();
                        } else {
                            statusText.setText("Setup failed: " + error);
                            statusText.setTextColor(Color.parseColor("#ff6666"));
                            retryButton.setVisibility(View.VISIBLE);
                        }
                    });
                }
            });
        }).start();
    }

    private void launchTerminal() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        Intent intent = new Intent(this, Term.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    @Override
    protected void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }
}
