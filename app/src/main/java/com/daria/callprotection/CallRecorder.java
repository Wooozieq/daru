package com.daria.callprotection;

import android.content.Context;
import android.media.MediaRecorder;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class CallRecorder {
    private static final String TAG = "CallRecorder";

    private MediaRecorder recorder;
    private File audioFile;
    private final Context context;

    public CallRecorder(Context context) {
        this.context = context;
    }

    public void startRecording() {
        try {
            File dir = new File(context.getExternalFilesDir(null), "recordings");
            if (!dir.exists()) {
                dir.mkdirs();
            }

            String fileName = "call_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".3gp";
            audioFile = new File(dir, fileName);

            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION); // Более надёжный источник
            recorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            recorder.setOutputFile(audioFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();

            Log.d(TAG, "Запись начата: " + audioFile.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "Ошибка при запуске записи", e);
        } catch (Exception e) {
            Log.e(TAG, "Общая ошибка при старте записи", e);
        }
    }

    public File stopRecording() {
        try {
            if (recorder != null) {
                recorder.stop();
                recorder.release();
                recorder = null;
                Log.d(TAG, "Запись завершена: " + audioFile.getAbsolutePath());
                return audioFile;
            }
        } catch (Exception e) {
            Log.e(TAG, "Ошибка при завершении записи", e);
        }
        return null;
    }
}
