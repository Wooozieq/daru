package com.daria.callprotection;

import android.content.Context;
import android.telephony.TelephonyManager;

public class PhoneCallManager {

    private final Context context;
    private final TelephonyManager telephonyManager;
    private PhoneStateListenerImpl phoneStateListener;

    public PhoneCallManager(Context context) {
        this.context = context;
        telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
    }

    public void startListening() {
        if (phoneStateListener == null) {
            phoneStateListener = new PhoneStateListenerImpl(context);
            telephonyManager.listen(phoneStateListener, PhoneStateListenerImpl.LISTEN_CALL_STATE);
        }
    }

    public void stopListening() {
        if (phoneStateListener != null) {
            telephonyManager.listen(phoneStateListener, PhoneStateListenerImpl.LISTEN_NONE);
            phoneStateListener = null;
        }
    }
}
