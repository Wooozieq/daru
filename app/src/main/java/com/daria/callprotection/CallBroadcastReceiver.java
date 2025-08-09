package com.daria.callprotection;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.TelephonyManager;
import android.util.Log;

public class CallBroadcastReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);

        FileLogger.log("CallBroadcastReceiver: State = " + state);

        if (TelephonyManager.EXTRA_STATE_RINGING.equals(state)) {
            String incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
            FileLogger.log("CallBroadcastReceiver: Incoming call from: " + incomingNumber);
        }

        if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(state)) {
            FileLogger.log("CallBroadcastReceiver: Call answered or outgoing call.");
        }

        if (TelephonyManager.EXTRA_STATE_IDLE.equals(state)) {
            FileLogger.log("CallBroadcastReceiver: Call ended or no call.");
        }
    }
}
