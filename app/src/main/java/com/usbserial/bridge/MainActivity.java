package com.usbserial.bridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

/**
 * Minimal launcher activity for the USB Serial Bridge.
 * Shows connection status and allows starting/stopping the bridge service.
 *
 * In Termux/Proot environments, connect to the bridge with:
 *   socat UNIX-CONNECT:@usb_serial_bridge STDIO
 * or use a helper script that connects to the abstract local socket.
 */
public class MainActivity extends AppCompatActivity {

    private Button startButton;
    private Button stopButton;
    private TextView statusText;
    private TextView logText;

    private final StringBuilder log = new StringBuilder();

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String status = intent.getStringExtra(UsbSerialBridgeService.EXTRA_STATUS);
            String message = intent.getStringExtra(UsbSerialBridgeService.EXTRA_MESSAGE);

            if (status != null) {
                updateStatus(status, message);
            }
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Build simple UI programmatically (no XML layouts needed)
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);

        // Title
        TextView title = new TextView(this);
        title.setText("USB Serial Bridge");
        title.setTextSize(22);
        title.setTextAlignment(TextView.TEXT_ALIGNMENT_CENTER);
        title.setPadding(0, 0, 0, 16);

        // Status
        statusText = new TextView(this);
        statusText.setText("Tap Start to begin");
        statusText.setTextSize(16);
        statusText.setPadding(0, 0, 0, 16);
        statusText.setTextAlignment(TextView.TEXT_ALIGNMENT_CENTER);

        // Buttons
        startButton = new Button(this);
        startButton.setText("Start Bridge");
        startButton.setEnabled(true);

        stopButton = new Button(this);
        stopButton.setText("Stop Bridge");
        stopButton.setEnabled(false);

        // Log area
        logText = new TextView(this);
        logText.setTextSize(12);
        logText.setPadding(0, 16, 0, 0);
        logText.setMaxLines(100);
        logText.setVerticalScrollBarEnabled(true);

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(logText);

        // Wire up buttons
        startButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, UsbSerialBridgeService.class);
            intent.setAction(UsbSerialBridgeService.ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            log("Starting bridge...");
            startButton.setEnabled(false);
            stopButton.setEnabled(true);
        });

        stopButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, UsbSerialBridgeService.class);
            intent.setAction(UsbSerialBridgeService.ACTION_STOP);
            startService(intent);
            log("Stopping bridge...");
            startButton.setEnabled(true);
            stopButton.setEnabled(false);
        });

        // Assemble layout
        root.addView(title);
        root.addView(statusText);
        root.addView(startButton);
        root.addView(stopButton);
        root.addView(scrollView);

        setContentView(root);

        // Register for status broadcasts
        LocalBroadcastManager.getInstance(this).registerReceiver(
                statusReceiver,
                new IntentFilter(UsbSerialBridgeService.ACTION_STATUS)
        );
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver);
        } catch (Exception ignored) {}
    }

    private void updateStatus(String status, String message) {
        String display;
        switch (status) {
            case "connected":
                display = "Connected: " + message;
                startButton.setEnabled(false);
                stopButton.setEnabled(true);
                break;
            case "listening":
                display = "Bridge active - " + message;
                break;
            case "no_device":
                display = "No USB device found. Plug one in.";
                break;
            case "permission_denied":
                display = "Permission denied!";
                startButton.setEnabled(true);
                stopButton.setEnabled(false);
                break;
            case "error":
                display = "Error: " + message;
                break;
            case "stopped":
                display = "Bridge stopped";
                startButton.setEnabled(true);
                stopButton.setEnabled(false);
                break;
            default:
                display = status + ": " + message;
                break;
        }

        String ts = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date());
        statusText.setText(display);
        log("[" + ts + "] " + status + " - " + message);
    }

    private void log(String msg) {
        log.append(msg).append("\n");
        logText.setText(log.toString());

        // Auto-scroll to bottom
        logText.post(() -> {
            int scrollAmount = logText.getLayout() != null
                    ? logText.getLayout().getLineTop(logText.getLineCount()) - logText.getHeight()
                    : 0;
            if (scrollAmount > 0) {
                logText.scrollTo(0, scrollAmount);
            } else {
                logText.scrollTo(0, 0);
            }
        });
    }
}
