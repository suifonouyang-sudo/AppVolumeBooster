package com.rk.appvolume;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 开机后自动恢复放大服务（仅当用户上次是开启状态时）。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String PREF_BOOT = "boot_state";
    private static final String KEY_ENABLED = "enabled";

    static void setEnabled(Context c, boolean enabled) {
        c.getSharedPreferences(PREF_BOOT, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)) return;

        boolean enabled = context.getSharedPreferences(PREF_BOOT, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false);
        if (!enabled) return;

        Intent i = new Intent(context, BoostService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(i);
            } else {
                context.startService(i);
            }
        } catch (Throwable ignore) {
        }
    }
}
