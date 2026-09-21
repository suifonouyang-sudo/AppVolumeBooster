package com.rk.appvolume;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.audiofx.LoudnessEnhancer;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * 按应用放大音量的核心服务。
 *
 * 背景：Android 原生没有 per-app 音量 API（音量按 stream 控制），
 * 而且 AudioPlaybackConfiguration 的 isActive()/getClientUid()/getSessionId()
 * 都是 @hide 隐藏 API，不能直接调用。
 *
 * 因此本服务用三条路径识别「哪个应用正在放音」：
 *   1) MediaSessionManager.getActiveSessions()  —— 最准，需通知使用权
 *   2) AudioPlaybackConfiguration + 反射 getClientUid —— 覆盖游戏/无 MediaSession 的应用
 *   3) UsageStats 前台应用 —— 兜底，需使用情况访问权限
 *
 * 增益施加方式：
 *   A) 若能反射拿到 session id，就把 LoudnessEnhancer 只挂到该 session（真正的独立通道）
 *   B) 否则用 session 0（全局输出混音），按当前播放应用动态切换增益值
 */
public class BoostService extends Service {

    private static final String TAG = "AppVolume";
    private static final String CHANNEL_ID = "appvolume_channel";
    private static final int NOTIFY_ID = 1001;

    public static final String ACTION_STATE = "com.rk.appvolume.ACTION_STATE";
    public static final String EXTRA_ACTIVE = "extra_active"; // 逗号分隔包名(含前台兜底)
    public static final String EXTRA_ACTIVE_AUDIO = "extra_active_audio"; // 逗号分隔: 真正检测到音频输出的包名
    public static final String EXTRA_GAIN = "extra_gain";     // 当前生效增益 mB
    public static final String EXTRA_MODE = "extra_mode";     // session / global / none

    /** 服务是否在运行（同进程，供 UI 读取） */
    public static volatile boolean running = false;

    private AudioManager am;
    private PackageManager pm;
    private Prefs prefs;
    private Handler handler;

    private LoudnessEnhancer globalFx;
    private int lastGain = -1;

    private final AudioManager.AudioPlaybackCallback callback =
            new AudioManager.AudioPlaybackCallback() {
                @Override
                public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                    refresh();
                }
            };

    private final Runnable periodic = new Runnable() {
        @Override
        public void run() {
            refresh();
            if (running) handler.postDelayed(this, 2000L);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        pm = getPackageManager();
        prefs = new Prefs(this);
        handler = new Handler(Looper.getMainLooper());

        createChannel();
        startForeground(NOTIFY_ID, buildNotification());

        try {
            am.registerAudioPlaybackCallback(callback, handler);
        } catch (Throwable t) {
            Log.w(TAG, "register playback callback failed: " + t);
        }
        running = true;
        refresh();
        handler.postDelayed(periodic, 2000L);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        refresh();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacks(periodic);
        try {
            am.unregisterAudioPlaybackCallback(callback);
        } catch (Throwable ignore) {
        }
        releaseAll();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ==================================================================
    // 刷新：识别当前播放应用 → 应用对应增益
    // ==================================================================
    private void refresh() {
        Set<String> pkgs = new LinkedHashSet<>();

        // --- 路径 1：MediaSession（最准确） ---
        try {
            MediaSessionManager msm =
                    (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            List<MediaController> ctrls =
                    msm.getActiveSessions(new ComponentName(this, NotiListener.class));
            if (ctrls != null) {
                for (MediaController mc : ctrls) {
                    try {
                        android.media.session.PlaybackState st = mc.getPlaybackState();
                        if (st != null && st.getState()
                                == android.media.session.PlaybackState.STATE_PLAYING) {
                            pkgs.add(mc.getPackageName());
                        }
                    } catch (Throwable ignore) {
                    }
                }
            }
        } catch (Throwable t) {
            Log.d(TAG, "media session path unavailable: " + t);
        }

        // --- 路径 2：AudioPlaybackConfiguration + 反射 getClientUid ---
        List<AudioPlaybackConfiguration> configs = null;
        try {
            configs = am.getActivePlaybackConfigurations();
        } catch (Throwable t) {
            Log.d(TAG, "getActivePlaybackConfigurations failed: " + t);
        }
        if (configs != null) {
            for (AudioPlaybackConfiguration c : configs) {
                Integer uid = reflectInt(c, "getClientUid");
                if (uid == null) continue;
                String[] ps = pm.getPackagesForUid(uid);
                if (ps != null && ps.length > 0) pkgs.add(ps[0]);
            }
        }

        // 到此为止是「真正检测到音频输出」的应用，UI 排序只用这个集合
        Set<String> audioPkgs = new LinkedHashSet<>(pkgs);

        // --- 路径 3：UsageStats 前台应用（兜底，仅用于施加增益，不代表在放音） ---
        if (pkgs.isEmpty()) {
            String fg = foregroundPackage();
            if (fg != null) pkgs.add(fg);
        }

        // --- 计算应施加的增益 ---
        int gain = 0;
        for (String p : pkgs) {
            int g = prefs.getGain(p);
            if (g > gain) gain = g;
        }

        // --- 尝试独立 session 通道（能拿到 sessionId 才走） ---
        boolean perSession = false;
        if (configs != null && gain > 0) {
            for (AudioPlaybackConfiguration c : configs) {
                Integer sid = reflectInt(c, "getSessionId");
                if (sid == null || sid <= 0) continue;
                Integer uid = reflectInt(c, "getClientUid");
                if (uid == null) continue;
                String[] ps = pm.getPackagesForUid(uid);
                if (ps == null || ps.length == 0) continue;
                int g = prefs.getGain(ps[0]);
                if (g <= 0) continue;
                if (attachSession(sid, g)) perSession = true;
            }
        }

        // --- 全局通道 ---
        boolean globalOn = false;
        if (gain > 0 && !perSession) {
            globalOn = applyGlobal(gain);
        } else if (gain <= 0) {
            disableGlobal();
        }

        String mode = perSession ? "session" : (globalOn ? "global" : "none");
        Log.i(TAG, "playing=" + pkgs + " gain=" + gain + "mB mode=" + mode);
        broadcast(pkgs, audioPkgs, globalOn ? gain : (perSession ? gain : 0), mode);
    }

    // ==================================================================
    private boolean attachSession(int sessionId, int gainMb) {
        try {
            LoudnessEnhancer fx = new LoudnessEnhancer(sessionId);
            fx.setTargetGain(gainMb);
            fx.setEnabled(true);
            // 注意：这里不长期持有引用。session 结束后效果器会被系统回收，
            // 为避免泄漏，创建后即刻交由系统管理并释放 Java 侧句柄。
            return true;
        } catch (Throwable t) {
            Log.d(TAG, "per-session attach failed: " + t);
            return false;
        }
    }

    private boolean applyGlobal(int gainMb) {
        try {
            if (globalFx == null) {
                globalFx = new LoudnessEnhancer(0);
            }
            if (lastGain != gainMb) {
                globalFx.setTargetGain(gainMb);
                lastGain = gainMb;
            }
            if (!globalFx.getEnabled()) globalFx.setEnabled(true);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "global effect failed: " + t);
            return false;
        }
    }

    private void disableGlobal() {
        if (globalFx != null) {
            try {
                globalFx.setEnabled(false);
                globalFx.setTargetGain(0);
            } catch (Throwable ignore) {
            }
            lastGain = -1;
        }
    }

    private void releaseAll() {
        if (globalFx != null) {
            try {
                globalFx.setEnabled(false);
                globalFx.release();
            } catch (Throwable ignore) {
            }
            globalFx = null;
        }
    }

    // ==================================================================
    // 工具
    // ==================================================================
    private Integer reflectInt(Object target, String methodName) {
        try {
            Method m = target.getClass().getMethod(methodName);
            Object v = m.invoke(target);
            if (v instanceof Integer) return (Integer) v;
        } catch (Throwable t) {
            Log.d(TAG, methodName + " reflect failed: " + t.getMessage());
        }
        return null;
    }

    private String foregroundPackage() {
        // 优先 UsageStats（需 GET_USAGE_STATS）
        try {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY,
                    now - 5 * 60 * 1000L, now);
            if (stats != null && !stats.isEmpty()) {
                SortedMap<Long, UsageStats> sorted = new TreeMap<>();
                for (UsageStats us : stats) {
                    sorted.put(us.getLastTimeUsed(), us);
                }
                if (!sorted.isEmpty()) {
                    return sorted.get(sorted.lastKey()).getPackageName();
                }
            }
        } catch (Throwable t) {
            Log.d(TAG, "usage stats unavailable: " + t.getMessage());
        }

        // 再退：ActivityManager 运行中进程
        try {
            ActivityManager amgr = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> procs = amgr.getRunningAppProcesses();
            if (procs != null) {
                for (ActivityManager.RunningAppProcessInfo pi : procs) {
                    if (pi.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                        if (pi.pkgList != null && pi.pkgList.length > 0) return pi.pkgList[0];
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    private void broadcast(Set<String> pkgs, Set<String> audioPkgs, int gainMb, String mode) {
        Intent i = new Intent(ACTION_STATE);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_ACTIVE, join(pkgs));
        i.putExtra(EXTRA_ACTIVE_AUDIO, join(audioPkgs));
        i.putExtra(EXTRA_GAIN, gainMb);
        i.putExtra(EXTRA_MODE, mode);
        sendBroadcast(i);
    }

    private static String join(Set<String> s) {
        return s == null || s.isEmpty() ? "" : String.join(",", s);
    }

    // ==================================================================
    // 通知
    // ==================================================================
    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "应用音量放大", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("按应用放大音量服务运行中");
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("应用音量放大器")
                .setContentText("正在监听播放状态，按应用放大音量")
                .setSmallIcon(R.drawable.ic_launcher)
                .setOngoing(true)
                .build();
    }
}
