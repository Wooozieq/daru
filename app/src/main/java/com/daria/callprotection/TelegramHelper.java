package com.daria.callprotection;

import android.util.Log;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class TelegramHelper {
    private static final String TAG = "TelegramHelper";

    public static void sendFile(File file, String botToken, String chatId) {
        new Thread(() -> {
            String urlString = "https://api.telegram.org/bot" + botToken + "/sendDocument";
            String boundary = "*****" + Long.toString(System.currentTimeMillis()) + "*****";

            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
                connection.setDoOutput(true);
                connection.setUseCaches(false);
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Connection", "Keep-Alive");
                connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

                DataOutputStream request = new DataOutputStream(connection.getOutputStream());

                request.writeBytes("--" + boundary + "\r\n");
                request.writeBytes("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n");
                request.writeBytes(chatId + "\r\n");

                request.writeBytes("--" + boundary + "\r\n");
                request.writeBytes("Content-Disposition: form-data; name=\"document\"; filename=\"" + file.getName() + "\"\r\n");
                request.writeBytes("Content-Type: application/octet-stream\r\n\r\n");

                FileInputStream fileInputStream = new FileInputStream(file);
                byte[] buffer = new byte[4096];
                int bytesRead;

                while ((bytesRead = fileInputStream.read(buffer)) != -1) {
                    request.write(buffer, 0, bytesRead);
                }

                request.writeBytes("\r\n");
                request.writeBytes("--" + boundary + "--\r\n");

                fileInputStream.close();
                request.flush();
                request.close();

                int responseCode = connection.getResponseCode();
                Log.d(TAG, "Файл отправлен. Код ответа: " + responseCode);

            } catch (Exception e) {
                Log.e(TAG, "Ошибка отправки файла в Telegram", e);
            }
        }).start();
    }
}
