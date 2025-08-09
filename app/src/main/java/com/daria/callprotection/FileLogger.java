package com.daria.callprotection;

import android.util.Log;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class FileLogger {
    private static final String TAG = "FileLogger";
    private static final String LOG_FILE = "app_log.txt";
    private static final Object LOCK = new Object();

    public static void log(String text) {
        String line = "[" + now() + "] " + text;
        append(line);
        Log.d(TAG, line);
    }

    public static void append(String text) {
        synchronized (LOCK) {
            File f = getLogFile();
            try (FileWriter fw = new FileWriter(f, true);
                 BufferedWriter bw = new BufferedWriter(fw)) {
                bw.write(text);
                bw.newLine();
                bw.flush();
            } catch (IOException e) {
                Log.e(TAG, "append error", e);
            }
        }
    }

    public static String getFullLog() {
        synchronized (LOCK) {
            File f = getLogFile();
            if (!f.exists()) return "";
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            } catch (IOException e) {
                Log.e(TAG, "getFullLog error", e);
            }
            return sb.toString();
        }
    }

    public static void clearLog() {
        synchronized (LOCK) {
            File f = getLogFile();
            try (FileWriter fw = new FileWriter(f, false)) {
                fw.write("");
            } catch (IOException e) {
                Log.e(TAG, "clearLog error", e);
            }
        }
    }

    private static File getLogFile() {
        return new File(App.getContext().getFilesDir(), LOG_FILE);
    }

    private static String now() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        return sdf.format(new Date());
    }
}
