package com.carbridge.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import com.carbridge.carplay.util.Diag;

/**
 * VPN 式保活服务：常驻前台通知 + 三把锁，让锁屏/后台**只影响屏幕显示，不打断连接**。
 */
public final class KeepAliveService extends Service {

    private static final String TAG = "carbridge";
    private static final String CHANNEL_ID = "carbridge_keepalive";
    private static final int NOTIF_ID = 0x0B1A;

    private PowerManager.WakeLock cpuLock;
    private WifiManager.WifiLock wifiLock;
    private WifiManager.MulticastLock mcastLock;

    /** 启动保活服务（幂等，可重复调用）。 */
    public static void start(Context ctx) {
        Intent i = new Intent(ctx, KeepAliveService.class);
        // Android 8+ 必须用 startForegroundService，且要在 5 秒内 startForeground
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Throwable t) {
            // Android 12+ 禁止从后台启动前台服务（onState 回调/广播等后台上下文会撞）。
            // 退化为普通服务：onCreate 里照样 startForeground()，效果一样；
            // 仍失败只记日志 —— 下次回到前台时调用方会再试，不能让这里把 App 带崩。
            android.util.Log.w("carbridge", "保活服务前台启动被拒（后台限制）→ 退化为普通服务：" + t);
            try {
                ctx.startService(new Intent(ctx, KeepAliveService.class));
            } catch (Throwable t2) {
                android.util.Log.w("carbridge", "启动保活服务失败：" + t2);
            }
        }
    }

    /** 停止保活服务并释放全部锁。 */
    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, KeepAliveService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // 自保护兜底：MainActivity 之外这条入口也验一次（服务虽未 exported，仍从严）
        acquireLocks();
        startForegroundCompat(buildNotification("连接已保活：锁屏不影响"));
        Log.i(TAG, "保活服务已启动（前台通知 + CPU/WiFi 锁已持有）");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 被系统杀掉后自动重建；START_STICKY 让保活尽量常驻
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        releaseLocks();
        Log.i(TAG, "保活服务已停止（锁已释放）");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------- 锁

        /**
     * 拿三把锁：CPU 常醒、WiFi 常醒、多播可收。全部非引用计数，拿一次即可。
     */
    @SuppressWarnings("deprecation")
    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "carbridge:session");
                cpuLock.setReferenceCounted(false);
                cpuLock.acquire();
                Log.i(TAG, "✓ CPU wake lock 已持有");
            }
        } catch (Throwable t) {
            Log.w(TAG, "拿 CPU wake lock 失败：" + t);
        }

        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                // FULL_LOW_LATENCY：投屏场景要低延迟，且阻止 WiFi 进省电
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "carbridge:wifi");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
                Log.i(TAG, "✓ WiFi lock 已持有");
            }
        } catch (Throwable t) {
            Log.w(TAG, "拿 WiFi lock 失败：" + t);
        }

        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                mcastLock = wm.createMulticastLock("carbridge:mdns");
                mcastLock.setReferenceCounted(false);
                mcastLock.acquire();
                Log.i(TAG, "✓ Multicast lock 已持有");
            }
        } catch (Throwable t) {
            Log.w(TAG, "拿 Multicast lock 失败：" + t);
        }
    }

    private void releaseLocks() {
        try {
            if (cpuLock != null && cpuLock.isHeld()) cpuLock.release();
        } catch (Throwable tDiag) {
            Diag.w("KeepAliveService.releaseLocks", tDiag);
        }
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Throwable tDiag) {
            Diag.w("KeepAliveService.releaseLocks#2", tDiag);
        }
        try {
            if (mcastLock != null && mcastLock.isHeld()) mcastLock.release();
        } catch (Throwable tDiag) {
            Diag.w("KeepAliveService.releaseLocks#3", tDiag);
        }
        cpuLock = null;
        wifiLock = null;
        mcastLock = null;
    }

    // ------------------------------------------------------------- 通知

    /** 建通知渠道并把服务推到前台（必须在 startForeground 之前建渠道）。 */
    private void startForegroundCompat(Notification n) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                        "连接保活", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("保持与车机的连接，锁屏后不中断");
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
        // connectedDevice 类型必须随 startForeground 声明（targetSdk 35 强制）
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    /** 常驻通知：点一下回到主界面。 */
    private Notification buildNotification(String text) {
        Intent back = new Intent(this, MainActivity.class);
        back.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, back, piFlags);

        // minSdk 26：带 channelId 的构造在所有目标设备上都可用，不再需要分支
        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID);
        b.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true);   // 优先级由 channel 的 IMPORTANCE_LOW 决定（setPriority 已废弃）
        return b.build();
    }
}
