package com.daria.callprotection;

import android.app.Application;
import android.content.Context;
import android.os.StrictMode;
import android.os.Build;

public class App extends Application {
    private static Context context;

    @Override
    public void onCreate() {
        super.onCreate();
        context = getApplicationContext();
        // StrictMode for debug builds to catch accidental main-thread IO
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build());
            StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .detectLeakedSqlLiteObjects()
                    .penaltyLog()
                    .build());
        }
    }

    public static Context getContext() {
        return context;
    }
}
