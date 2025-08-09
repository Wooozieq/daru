package com.daria.callprotection;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

public class NetworkChangeReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        FileLogger.log("NetworkChangeReceiver: Получено изменение сети");

        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

        if (cm != null) {
            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            boolean isConnected = activeNetwork != null && activeNetwork.isConnected();

            FileLogger.log("NetworkChangeReceiver: Состояние подключения — " + (isConnected ? "подключено" : "нет подключения"));

            if (isConnected) {
                // Можно инициировать фоновую задачу, если нужно
                Intent uploadService = new Intent(context, UploadService.class);
                context.startService(uploadService);
                FileLogger.log("NetworkChangeReceiver: Запущен UploadService");
            }
        } else {
            FileLogger.log("NetworkChangeReceiver: ConnectivityManager недоступен");
        }
    }
}
