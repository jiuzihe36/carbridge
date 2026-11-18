package com.carbridge.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.carbridge.carplay.bt.CarPlaySdpBeacon;

/**
 * 只做一件事：把 CarPlay 的 SDP 信标常驻住。
 */
public final class SdpBeaconService extends Service {

    private static final String TAG = "carbridge";
    private static final String CHANNEL_ID = "carbridge_carplay";
    private static final int NOTIF_ID = 0x0B1B;

    /** 看门狗闹钟的 action（内部用，不对外暴露）。 */
    private static final String ACTION_WATCHDOG = "com.carbridge.app.SDP_WATCHDOG";
    /** 看门狗间隔：2 分钟。 */
    private static final long WATCHDOG_MS = 2 * 60 * 1000L;

    /** 服务是否活着（UI 用来显示「信标在不在」）。 */
    private static volatile boolean alive;

    private BroadcastReceiver btStateReceiver;
    /** 周期健康检查：监听线程死了就立刻重挂，不能等 2 分钟的看门狗。 */
    private final Handler healthHandler = new Handler(Looper.getMainLooper());
    private final Runnable healthCheck = new Runnable() {
        @Override
        public void run() {
            try {
                BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
                BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
                CarPlaySdpBeacon.reviveIfDead(adapter, new CarPlaySdpBeacon.Log() {
                    @Override
                    public void onLog(String line) {
                        Log.i(TAG, line);
                        sessionLog(line);
                    }
                });
            } catch (Throwable t) {
                Log.w(TAG, "SDP 健康检查异常：" + t);
            }
            healthHandler.postDelayed(this, 10_000L);
        }
    };

    /**
     * 服务是否正在运行。
     *
     * <p>这不是装饰：SDP 记录只在进程活着的时候存在，进程被系统杀掉
     * 车机就再也认不出本机。UI 要把这个状态如实告诉用户。
     */
    public static boolean isAlive() {
        return alive;
    }

    /** 幂等启动（开机广播 / 看门狗 / 手动都走这里）。 */
    public static void start(Context ctx) {
        try {
            Intent i = new Intent(ctx, SdpBeaconService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Throwable t) {
            // Android 12+ 禁止从后台启动前台服务 —— 看门狗在后台被闹钟叫醒时就撞这个。
            // 退化成普通服务：onCreate 里照样会 startForeground()，效果一样。
            Log.w(TAG, "前台启动被拒（后台限制）→ 退化为普通服务：" + t);
            try {
                ctx.startService(new Intent(ctx, SdpBeaconService.class));
            } catch (Throwable t2) {
                Log.w(TAG, "启动 SDP 信标服务失败：" + t2);
            }
        }
    }

    /**
     * 安排下一次看门狗闹钟。
     *
     * <p>用 {@code setExactAndAllowWhileIdle} 是为了在 Doze 里也能准时醒；
     * 拿不到精确闹钟权限（Android 12+ 的 SCHEDULE_EXACT_ALARM 需要用户授权）
     * 就退化成 {@code setAndAllowWhileIdle}，照样能醒，只是没那么准。
     */
    static void scheduleWatchdog(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(ctx, BeaconWatchdogReceiver.class);
            i.setAction(ACTION_WATCHDOG);
            PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            long at = System.currentTimeMillis() + WATCHDOG_MS;
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (Throwable t) {
            Log.w(TAG, "看门狗闹钟安排失败：" + t);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        alive = true;
        startForegroundCompat(buildNotification());
        ensureBeacon();
        // JobScheduler 兜底只在这里排一次（放在 start() 里会与任务自身互相触发成死循环）
        BeaconJobService.schedule(this);
        // 让本机在蓝牙扫描里可被发现 —— 车机就是靠经典蓝牙扫描找手机的
        // （车机 CarPlay app 的 performWirelessCarplayScan）。
        boolean disc = CarPlaySdpBeacon.setDiscoverable(180);
        Log.i(TAG, disc ? "已开启蓝牙可被发现（180s，车机扫描用）"
                : "开启蓝牙可被发现失败（不影响已配对设备的连接）");
        // 注册完再刷一次通知：文字要如实反映信标到底挂上没有。
        startForegroundCompat(buildNotification());
        scheduleWatchdog(this);
        healthHandler.removeCallbacks(healthCheck);
        healthHandler.postDelayed(healthCheck, 10_000L);

        btStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
                if (state == BluetoothAdapter.STATE_ON) {
                    Log.i(TAG, "蓝牙已开启 → 重新挂 SDP 信标");
                    ensureBeacon();
                } else if (state == BluetoothAdapter.STATE_OFF) {
                    Log.i(TAG, "蓝牙已关闭 → 释放 SDP 信标");
                    CarPlaySdpBeacon.reset();
                }
            }
        };
        try {
            registerReceiver(btStateReceiver,
                    new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
        } catch (Throwable t) {
            Log.w(TAG, "注册蓝牙状态广播失败：" + t);
        }
        Log.i(TAG, "CarPlay SDP 信标服务已启动");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        alive = true;
        ensureBeacon();
        // 每次启动都把看门狗往后挪一格：服务活着就有人盯着，死了就靠闹钟叫回来。
        scheduleWatchdog(this);
        return START_STICKY;
    }

    /**
     * 用户把 App 从后台划掉时，系统会顺手把服务一起收走。
     * 这里立刻把自己拉回来 —— SDP 记录不能断。
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Log.i(TAG, "App 被划掉 → 重新拉起 SDP 信标服务");
        start(this);
    }

    @Override
    public void onDestroy() {
        alive = false;
        healthHandler.removeCallbacks(healthCheck);
        if (btStateReceiver != null) {
            try {
                unregisterReceiver(btStateReceiver);
            } catch (Throwable ignored) {
            }
            btStateReceiver = null;
        }
        // ⚠️ 这里**不能** reset()。
        // 现场 bug：系统秒重启服务时，新实例已经注册好信标，旧实例的 onDestroy
        // 随后才跑 —— 一 reset 就把新实例的记录全关了，进程还活着但车机再也连不上。
        // 日志里表现为「SDP 信标线程结束 ×4: read failed, socket might closed」，之后
        // 车机点 Apple CarPlay 一点反应也没有。信标是进程级静态资源，跟着进程走；
        // 蓝牙关闭由 btStateReceiver 负责 reset，服务停掉时进程退出 socket 自然释放。
        Log.i(TAG, "CarPlay SDP 信标服务已停止（信标保留，随进程释放）");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void ensureBeacon() {
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
        CarPlaySdpBeacon.reviveIfDead(adapter, new CarPlaySdpBeacon.Log() {
            @Override
            public void onLog(String line) {
                Log.i(TAG, line);
                sessionLog(line);
            }
        });
    }

    /**
     * 把信标侧的关键事件写进会话日志文件。
     *
     * <p>这些行以前只进 logcat：用户在 App 里点「分享日志」拿不到「车机连上来了」
     * 「信标线程结束」这类最关键的证据，现场只能靠猜。服务还活着时进程就在，
     * 直接以追加方式写同一个文件（每行一次 open/write/close，事件很少，开销无感）。
     */
    private void sessionLog(String line) {
        java.io.FileOutputStream out = null;
        try {
            java.io.File dir = getExternalFilesDir(null);
            if (dir == null) return;
            java.io.File f = new java.io.File(dir, "carbridge-session.log");
            String ts = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                    .format(new java.util.Date());
            byte[] bytes = (ts + "  " + line + "\n").getBytes("UTF-8");
            out = new java.io.FileOutputStream(f, true);
            out.write(bytes);
        } catch (Throwable ignored) {
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ------------------------------------------------------------- 通知

    private void startForegroundCompat(Notification n) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                        "CarPlay 就绪", NotificationManager.IMPORTANCE_MIN);
                ch.setDescription("保持 CarPlay 认证信标，让车机随时能识别本机");
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
        // ⚠️ 不能让它把服务带崩：Android 14+ 的 connectedDevice 类型要求
        //    BLUETOOTH_CONNECT 已经授权。首次安装时权限框还没点，这里会抛
        //    SecurityException —— 服务直接崩掉 = 信标没了 = 车机认不出本机。
        //    拿不到前台状态就退化成普通服务，至少把 SDP 记录挂上。
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Throwable t) {
            Log.w(TAG, "startForeground 失败（权限没批？）→ 退化为普通服务继续挂信标：" + t);
        }
    }

    private Notification buildNotification() {
        Intent back = new Intent(this, MainActivity.class);
        back.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, back, piFlags);

        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this, CHANNEL_ID);
        b.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(CarPlaySdpBeacon.isStarted()
                        ? "CarPlay 认证信标运行中"
                        : "CarPlay 认证信标未就绪")
                .setContentIntent(pi)
                .setOngoing(true);
        return b.build();
    }
}
