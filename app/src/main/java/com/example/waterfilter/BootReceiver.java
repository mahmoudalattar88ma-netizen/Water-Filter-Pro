
package com.example.waterfilter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        SharedPreferences p = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        if (p.getBoolean(MainActivity.ENABLED_KEY, true)) {
            MainActivity.scheduleAll(context, p.getString(MainActivity.DATA_KEY, "[]"));
        }
    }
}
