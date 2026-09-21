package com.rk.appvolume;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {

    private Switch swEnable;
    private TextView tvStatus;
    private EditText etSearch;
    private ListView lvApps;

    private Prefs prefs;
    private final List<AppItem> allItems = new ArrayList<>();
    private final List<AppItem> shown = new ArrayList<>();
    private AppAdapter adapter;

    /** 当前真正检测到音频输出的应用（由服务广播更新） */
    private final Set<String> playingPkgs = new LinkedHashSet<>();
    /** 用户正在拖滑块时暂停自动重排，避免列表在手指下乱跳 */
    private boolean dragging = false;
    private String lastQuery = "";

    static class AppItem {
        String pkg;
        String name;
        Drawable icon;
        int gainMb;
        boolean playing;
    }

    // ------------------------------------------------------------------
    private final BroadcastReceiver stateRx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String audio = i.getStringExtra(BoostService.EXTRA_ACTIVE_AUDIO);
            int gain = i.getIntExtra(BoostService.EXTRA_GAIN, 0);
            String mode = i.getStringExtra(BoostService.EXTRA_MODE);
            updateStatus(audio, gain, mode);
            // 播放集合变化时重排：正在播放的置顶
            if (setPlaying(audio)) {
                refreshList();
            }
        }
    };

    /** 返回 true 表示集合发生变化（需要重排） */
    private boolean setPlaying(String csv) {
        Set<String> next = new LinkedHashSet<>();
        if (csv != null && !csv.trim().isEmpty()) {
            for (String p : csv.split(",")) {
                String t = p == null ? "" : p.trim();
                if (!t.isEmpty()) next.add(t);
            }
        }
        if (playingPkgs.equals(next)) return false;   // Set.equals 与顺序无关
        playingPkgs.clear();
        playingPkgs.addAll(next);
        return true;
    }

    private void refreshList() {
        if (dragging) return;   // 用户正在拖动滑块，先不重排
        applyPlayingFlag();
        sortItems();
        filter(lastQuery);
    }

    private void applyPlayingFlag() {
        for (AppItem it : allItems) {
            it.playing = playingPkgs.contains(it.pkg);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = new Prefs(this);
        swEnable = findViewById(R.id.swEnable);
        tvStatus = findViewById(R.id.tvStatus);
        etSearch = findViewById(R.id.etSearch);
        lvApps = findViewById(R.id.lvApps);

        adapter = new AppAdapter();
        lvApps.setAdapter(adapter);

        loadApps();
        filter("");

        swEnable.setChecked(BoostService.running);
        swEnable.setOnCheckedChangeListener((v, checked) -> {
            BootReceiver.setEnabled(MainActivity.this, checked);
            if (checked) {
                startBoostService();
            } else {
                stopService(new Intent(this, BoostService.class));
                Toast.makeText(this, R.string.toast_stopped, Toast.LENGTH_SHORT).show();
                updateStatus("", 0, "none");
            }
        });

        etSearch.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
            public void onTextChanged(CharSequence s, int st, int b, int c) {
                filter(s == null ? "" : s.toString());
            }
            public void afterTextChanged(Editable s) { }
        });

        findViewById(R.id.btnPerm).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));

        findViewById(R.id.btnUsage).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));

        requestNotificationPermissionIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter(BoostService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateRx, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateRx, f);
        }
        // 同步一次开关状态
        swEnable.setChecked(BoostService.running);
        // 增益可能已被改过，重新加载
        reloadGains();
    }

    @Override
    protected void onPause() {
        try {
            unregisterReceiver(stateRx);
        } catch (Throwable ignore) {
        }
        super.onPause();
    }

    // ------------------------------------------------------------------
    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
            }
        }
    }

    private void startBoostService() {
        Intent i = new Intent(this, BoostService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
            Toast.makeText(this, R.string.toast_started, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "启动失败: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 增益改动后立即让服务重新应用 */
    private void pokeService() {
        if (!BoostService.running) return;
        try {
            startService(new Intent(this, BoostService.class));
        } catch (Throwable ignore) {
        }
    }

    private void updateStatus(String activeCsv, int gainMb, String mode) {
        if (activeCsv == null || activeCsv.isEmpty()) {
            tvStatus.setText(getString(R.string.status_idle));
            return;
        }
        StringBuilder sb = new StringBuilder("正在播放：");
        String[] pkgs = activeCsv.split(",");
        PackageManager pm = getPackageManager();
        for (int i = 0; i < pkgs.length; i++) {
            if (i > 0) sb.append("、");
            sb.append(appLabel(pm, pkgs[i]));
        }
        if (gainMb > 0) {
            sb.append("　（全局 +").append(String.format(Locale.getDefault(), "%.1f", gainMb / 100.0))
              .append(" dB）");
        }
        if (mode != null && "session".equals(mode)) {
            sb.append("　[独立通道]");
        }
        tvStatus.setText(sb.toString());
    }

    private String appLabel(PackageManager pm, String pkg) {
        try {
            CharSequence cs = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            if (cs != null) return cs.toString();
        } catch (Throwable ignore) {
        }
        return pkg;
    }

    // ------------------------------------------------------------------
    private void loadApps() {
        allItems.clear();
        PackageManager pm = getPackageManager();
        Map<String, AppItem> map = new LinkedHashMap<>();

        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> ris = pm.queryIntentActivities(main, 0);
        if (ris != null) {
            for (ResolveInfo ri : ris) {
                String pkg = ri.activityInfo.packageName;
                if (pkg == null || pkg.equals(getPackageName())) continue;
                AppItem it = new AppItem();
                it.pkg = pkg;
                try {
                    it.name = pm.getApplicationLabel(ri.activityInfo.applicationInfo).toString();
                } catch (Throwable ignore) {
                    it.name = pkg;
                }
                try {
                    it.icon = pm.getApplicationIcon(pkg);
                } catch (Throwable ignore) {
                    it.icon = null;
                }
                it.gainMb = prefs.getGain(pkg);
                map.put(pkg, it);
            }
        }

        // 补上曾经设置过增益、但没有 launcher 图标的应用
        for (Map.Entry<String, Integer> e : prefs.all().entrySet()) {
            if (map.containsKey(e.getKey())) continue;
            AppItem it = new AppItem();
            it.pkg = e.getKey();
            try {
                it.name = pm.getApplicationLabel(pm.getApplicationInfo(it.pkg, 0)).toString();
            } catch (Throwable ignore) {
                it.name = it.pkg;
            }
            try {
                it.icon = pm.getApplicationIcon(it.pkg);
            } catch (Throwable ignore) {
                it.icon = null;
            }
            it.gainMb = e.getValue();
            map.put(it.pkg, it);
        }

        allItems.addAll(map.values());
        applyPlayingFlag();
        sortItems();
    }

    private void reloadGains() {
        for (AppItem it : allItems) {
            it.gainMb = prefs.getGain(it.pkg);
        }
        applyPlayingFlag();
        sortItems();
        filter(etSearch.getText() == null ? "" : etSearch.getText().toString());
    }

    private void sortItems() {
        Collections.sort(allItems, new Comparator<AppItem>() {
            @Override
            public int compare(AppItem a, AppItem b) {
                // 1) 正在播放的永远在最上面
                if (a.playing != b.playing) return a.playing ? -1 : 1;
                // 2) 同组内按名称字母序（保持稳定，拖滑块不会让条目乱跳）
                return a.name.compareToIgnoreCase(b.name);
            }
        });
    }

    private void filter(String q) {
        lastQuery = q == null ? "" : q;
        shown.clear();
        String key = lastQuery.trim().toLowerCase(Locale.getDefault());
        for (AppItem it : allItems) {
            if (key.isEmpty()
                    || it.name.toLowerCase(Locale.getDefault()).contains(key)
                    || it.pkg.toLowerCase(Locale.getDefault()).contains(key)) {
                shown.add(it);
            }
        }
        adapter.notifyDataSetChanged();
    }

    // ------------------------------------------------------------------
    private static String formatGain(int mb) {
        if (mb <= 0) return "未放大";
        return String.format(Locale.getDefault(), "+%.1f dB", mb / 100.0);
    }

    private class AppAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            Holder h;
            if (v == null) {
                v = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_app, parent, false);
                h = new Holder();
                h.icon = v.findViewById(R.id.ivIcon);
                h.name = v.findViewById(R.id.tvName);
                h.pkg = v.findViewById(R.id.tvPkg);
                h.gain = v.findViewById(R.id.tvGain);
                h.playing = v.findViewById(R.id.tvPlaying);
                h.seek = v.findViewById(R.id.sbGain);
                v.setTag(h);
            } else {
                h = (Holder) v.getTag();
            }

            AppItem it = shown.get(position);
            if (it.icon != null) {
                h.icon.setImageDrawable(it.icon);
            } else {
                h.icon.setImageResource(android.R.drawable.sym_def_app_icon);
            }
            h.name.setText(it.name);
            h.pkg.setText(it.pkg);
            h.gain.setText(formatGain(it.gainMb));
            h.playing.setVisibility(it.playing ? View.VISIBLE : View.GONE);
            h.seek.setMax(Prefs.MAX_GAIN_MB);
            h.seek.setProgress(it.gainMb);

            h.seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    if (!fromUser) return;
                    it.gainMb = progress;
                    prefs.setGain(it.pkg, progress);
                    h.gain.setText(formatGain(progress));
                }

                @Override
                public void onStartTrackingTouch(SeekBar sb) {
                    dragging = true;   // 拖动期间冻结自动重排
                }

                @Override
                public void onStopTrackingTouch(SeekBar sb) {
                    dragging = false;
                    pokeService();
                    refreshList();     // 解冻后补一次排序刷新
                }
            });

            return v;
        }

        class Holder {
            ImageView icon;
            TextView name;
            TextView pkg;
            TextView gain;
            TextView playing;
            SeekBar seek;
        }
    }
}
