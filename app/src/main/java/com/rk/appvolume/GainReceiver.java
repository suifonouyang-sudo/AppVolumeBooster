package com.rk.appvolume;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * 外部（adb / Tasker / 自动化脚本）设置某个应用增益的入口。
 *
 * 用法：
 *   adb shell am broadcast -a com.rk.appvolume.SET_GAIN \
 *        --es pkg com.example.app --ei gain 1000
 *
 * gain 单位为 mB（1000 = +10 dB，上限 1500）。
 * 服务每 2 秒轮询一次配置，设置后几秒内自动生效。
 */
public class GainReceiver extends BroadcastReceiver {

    private static final String TAG = "AppVolume";

    @Override
    public void onReceive(Context c, Intent i) {
        if (i == null) return;
        String pkg = i.getStringExtra("pkg");
        int gain = i.getIntExtra("gain", 0);
        if (pkg == null || pkg.isEmpty()) return;

        new Prefs(c).setGain(pkg, gain);
        Log.i(TAG, "setGain " + pkg + " = " + gain + "mB");

        if (!BoostService.running) {
            try {
                Intent s = new Intent(c, BoostService.class);
                if (Build.VERSION.SDK_INT >= 26) {
                    c.startForegroundService(s);
                } else {
                    c.startService(s);
                }
            } catch (Throwable t) {
                Log.w(TAG, "auto start service failed: " + t.getMessage());
            }
        }
    }
}
