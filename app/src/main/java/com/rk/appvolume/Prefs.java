package com.rk.appvolume;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;

/**
 * 每个应用的增益值持久化（单位 mB，1 dB = 100 mB）。
 */
public class Prefs {

    private static final String NAME = "app_gains";
    public static final int MAX_GAIN_MB = 1500; // +15 dB

    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public int getGain(String pkg) {
        return sp.getInt(pkg, 0);
    }

    public void setGain(String pkg, int gainMb) {
        if (gainMb < 0) gainMb = 0;
        if (gainMb > MAX_GAIN_MB) gainMb = MAX_GAIN_MB;
        sp.edit().putInt(pkg, gainMb).apply();
    }

    public Map<String, Integer> all() {
        Map<String, Integer> out = new HashMap<>();
        for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            Object v = e.getValue();
            if (v instanceof Integer) {
                out.put(e.getKey(), (Integer) v);
            }
        }
        return out;
    }
}
