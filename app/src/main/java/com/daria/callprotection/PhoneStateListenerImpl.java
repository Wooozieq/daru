// File: PhoneStateListenerImpl.java
package com.daria.callprotection;

import android.content.Context;
import android.content.Intent;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.core.content.ContextCompat;

/**
 * PhoneStateListener — отслеживает CALL_STATE и запускает UploadService на OFFHOOK,
 * останавливает запись на IDLE.
 */
public class PhoneStateListenerImpl extends PhoneStateListener {

    private static final String TAG = "PhoneStateListener";
    private boolean isRecording = false;
    private final Context context;

    public PhoneStateListenerImpl(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public void onCallStateChanged(int state, String phoneNumber) {
        super.onCallStateChanged(state, phoneNumber);

        switch (state) {
            case TelephonyManager.CALL_STATE_RINGING:
                Log.i(TAG, "RINGING from: " + phoneNumber);
                break;

            case TelephonyManager.CALL_STATE_OFFHOOK:
                // Звонок начался (входящий принят или исходящий соединён)
                if (!isRecording) {
                    isRecording = true;
                    Log.i(TAG, "OFFHOOK — starting recording");
                    Intent start = new Intent(context, UploadService.class);
                    start.setAction(UploadService.ACTION_START_RECORD);
                    ContextCompat.startForegroundService(context, start);
                }
                break;

            case TelephonyManager.CALL_STATE_IDLE:
                // Звонок завершён
                if (isRecording) {
                    isRecording = false;
                    Log.i(TAG, "IDLE — stopping recording");
                    Intent stop = new Intent(context, UploadService.class);
                    stop.setAction(UploadService.ACTION_STOP_RECORD);
                    ContextCompat.startForegroundService(context, stop);
                }
                break;
        }
    }
}
