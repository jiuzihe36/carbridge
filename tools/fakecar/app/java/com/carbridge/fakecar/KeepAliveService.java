package com.carbridge.fakecar;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

/**
 * 前台保活服务：锁屏后保持进程前台 + 持 partial wake lock，
 * 避免 Android 12+ 的 Cached App Freezer 冻结蓝牙/视频/音频线程。
 */
public final class KeepAliveService extends Service {

    private static final String CHANNEL_ID = "fakecar_keepalive";
    private static final int NOTIF_ID = 1;

    private static final String TAG = "fakecar-keepalive";
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    @Override
    public void onCreate() {
        super.onCreate();
        // 通知渠道（Android 8+）
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "假车机保活",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("保持 CarPlay 链路在锁屏后不断开");
            nm.createNotificationChannel(ch);
        }
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("fakecar 假车机运行中")
                .setContentText("锁屏后 CarPlay 链路保持连接")
                .setOngoing(true)
                .build();
        // ⚠️ targetSdk 34+ 必须显式带 type，否则抛 MissingForegroundServiceTypeException
        //    （异常在服务线程里，外面 catch 不到 → 保活服务看起来"启动成功"其实立刻死了）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIF_ID, n);
        }
        Log.i(TAG, "foreground started");

        // Wi-Fi 高性能锁：息屏后 Wi-Fi 进入省电会直接把 AirPlay 流憋死
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "fakecar:wifi");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
                Log.i(TAG, "wifi lock acquired");
            }
        } catch (Throwable t) {
            Log.w(TAG, "wifi lock failed: " + t);
        }

        // partial wake lock：CPU 不睡
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fakecar:keepalive");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
            Log.i(TAG, "wake lock acquired");
        } catch (Throwable t) {
            Log.w(TAG, "wake lock failed: " + t);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;   // 被杀后自动重启
    }

    @Override
    public void onDestroy() {
        if (wifiLock != null && wifiLock.isHeld()) {
            try { wifiLock.release(); } catch (Throwable ignored) { }
            wifiLock = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Throwable ignored) {
            }
            wakeLock = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** 启动/停止前台保活服务。 */
    public static void start(Context context) {
        try {
            context.startForegroundService(new Intent(context, KeepAliveService.class));
        } catch (Throwable ignored) {
        }
    }

    public static void stop(Context context) {
        try {
            context.stopService(new Intent(context, KeepAliveService.class));
        } catch (Throwable ignored) {
        }
    }
}
