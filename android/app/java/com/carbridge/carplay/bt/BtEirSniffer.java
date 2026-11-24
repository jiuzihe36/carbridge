package com.carbridge.carplay.bt;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 扫车机的 EIR，拿<strong>车机热点的 BSSID / SSID / 密码</strong>。
 */
public final class BtEirSniffer {

    /** AOSP 蓝牙服务把远端 EIR 厂商数据放进发现广播用的 extra 名。 */
    private static final String EXTRA_EIR_MANUFACTURER =
            "android.bluetooth.device.extra.EIR_MANUFACTURER";

    /** 扫多久就放弃（毫秒）。一次完整 inquiry 约 12s，给 35s 余量（要跟别人抢扫描机会）。 */
    private static final long DEFAULT_SCAN_MS = 35000L;

    /** 抢不到扫描机会时，隔多久再试一次。 */
    private static final long RETRY_MS = 2000L;

    public interface Listener {
        void onLog(String line);

        /** 扫到了带 Apple Device IE 的设备（大概率是车机）。 */
        void onAppleDeviceIe(String deviceName, String deviceAddress, AppleDeviceIe ie);
    }

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final long scanMs;

    private BroadcastReceiver receiver;
    private volatile boolean running;
    /** 已经报出去过，避免同一次扫描里重复回调。 */
    private final AtomicBoolean reported = new AtomicBoolean(false);
    /** 扫描过程中有没有看到车机（按名字/地址认）。 */
    private boolean sawCar;
    /** 车机的名字/地址特征（CarPlay / 已知 MAC）。 */
    private String carHint;
    /** 已经报过的设备（addr|name），避免一次扫描里刷屏。 */
    private final java.util.Set<String> seen = new java.util.HashSet<String>();

    public BtEirSniffer(Context ctx, Listener listener) {
        this(ctx, listener, DEFAULT_SCAN_MS);
    }

    public BtEirSniffer(Context ctx, Listener listener, long scanMs) {
        this(ctx, listener, scanMs, null);
    }

    /** @param carHint 车机的名字/地址特征（不区分大小写包含即可），用于「扫到没扫到车机」的结论 */
    public BtEirSniffer(Context ctx, Listener listener, long scanMs, String carHint) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
        this.scanMs = scanMs;
        this.carHint = carHint;
    }

    /** 开始扫描。已在扫描中则忽略。 */
    public void start() {
        if (running) return;
        BluetoothAdapter adapter;
        try {
            adapter = Bt.adapter(BtEirSniffer.this.ctx);
        } catch (Throwable t) {
            listener.onLog("EIR 扫描：拿不到 BluetoothAdapter: " + t);
            return;
        }
        if (adapter == null || !adapter.isEnabled()) {
            listener.onLog("EIR 扫描：蓝牙没开，跳过");
            return;
        }
        running = true;
        reported.set(false);
        synchronized (seen) { seen.clear(); }

        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                onFound(intent);
            }
        };
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                // ⚠️ **必须用 EXPORTED**：发广播的是蓝牙服务进程
                // （ColorOS 上是 android.uid.bluetooth/1002，**不是** system uid），
                // 用 NOT_EXPORTED 会把它的广播**整个丢掉**（实测收不到任何 ACTION_FOUND）。
                // 安全性：ACTION_FOUND 的内容（设备名/地址/EIR）任何持有
                // BLUETOOTH_SCAN 的应用自己扫一次就能拿到，这里不引入新风险。
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(receiver, filter);
            }
        } catch (Throwable t) {
            running = false;
            listener.onLog("EIR 扫描：注册广播失败: " + t);
            return;
        }
        try {
            if (startDiscovery(adapter)) {
                listener.onLog("EIR 扫描已开始（" + (scanMs / 1000) + "s 内等车机的 EIR）");
            } else {
                // ⚠️ **必须自己发起扫描才收得到结果**：Android 12+ 的 ACTION_FOUND
                // 广播是定向的（intent.setPackage(发起扫描的那个包)），
                // 别人扫到的结果不会发给我们。所以要**跟别人抢扫描机会**：
                // 别人那次 inquiry 结束（约 12s）后我们就能发起。
                listener.onLog("EIR 扫描：暂时抢不到扫描机会（别人在扫），"
                        + "每 " + (RETRY_MS / 1000) + "s 再试一次");
                main.postDelayed(retryTask, RETRY_MS);
            }
        } catch (Throwable t) {
            listener.onLog("EIR 扫描：startDiscovery 异常: " + t);
        }
        main.postDelayed(stopTask, scanMs);
    }

    /** 发起扫描；抢不到机会返回 false。 */
    private boolean startDiscovery(BluetoothAdapter adapter) {
        try {
            return adapter.startDiscovery();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 扫描机会的轮询重试。 */
    private final Runnable retryTask = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            BluetoothAdapter adapter;
            try {
                adapter = Bt.adapter(BtEirSniffer.this.ctx);
            } catch (Throwable t) {
                return;
            }
            if (adapter == null || !adapter.isEnabled()) return;
            if (startDiscovery(adapter)) {
                listener.onLog("EIR 扫描：抢到扫描机会了");
                return;
            }
            main.postDelayed(this, RETRY_MS);
        }
    };

    /** 停止扫描并注销广播。 */
    public void stop() {
        main.removeCallbacks(stopTask);
        main.removeCallbacks(retryTask);
        if (!running) return;
        running = false;
        synchronized (seen) { seen.clear(); }
        if (receiver != null) {
            try {
                ctx.unregisterReceiver(receiver);
            } catch (Throwable t) { com.carbridge.carplay.util.Diag.w("BtEirSniffer", t);
            }
            receiver = null;
        }
        try {
            BluetoothAdapter adapter = Bt.adapter(BtEirSniffer.this.ctx);
            if (adapter != null && adapter.isEnabled()) {
                adapter.cancelDiscovery();
            }
        } catch (Throwable t) { com.carbridge.carplay.util.Diag.w("BtEirSniffer", t);
        }
    }

    private final Runnable stopTask = new Runnable() {
        @Override
        public void run() {
            if (running) {
                listener.onLog("EIR 扫描结束，没扫到车机的 Apple Device IE"
                        + (sawCar ? "" : "（**而且扫描里根本没出现车机**"
                        + " → 车机蓝牙多半是关的 / 不可被发现，先确认车机蓝牙已开）"));
            }
            stop();
        }
    };

    // ================================================================== 收到发现结果

    private void onFound(Intent intent) {
        if (intent == null) return;
        BluetoothDevice device = null;
        String name = null;
        try {
            device = Bt.deviceFrom(intent);
            name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME);
        } catch (Throwable t) { com.carbridge.carplay.util.Diag.w("BtEirSniffer", t);
        }
        if (device == null) return;

        String raw = null;
        try {
            raw = intent.getStringExtra(EXTRA_EIR_MANUFACTURER);
        } catch (Throwable t) { com.carbridge.carplay.util.Diag.w("BtEirSniffer", t);
        }

        String addr = "";
        try {
            addr = device.getAddress();
        } catch (Throwable t) { com.carbridge.carplay.util.Diag.w("BtEirSniffer", t);
        }
        if (name == null || name.isEmpty()) name = addr;

        // 是不是车机？（按名字/地址特征认）
        if (carHint != null && !carHint.isEmpty()) {
            String probe = (name + " " + addr).toLowerCase(java.util.Locale.US);
            if (probe.contains(carHint.toLowerCase(java.util.Locale.US))) {
                sawCar = true;
                listener.onLog("★ 扫描里看到车机了：" + name + " [" + addr + "]");
            }
        }
        // 每个地址只报一次（免得刷屏）
        if (!seen.add(addr)) return;

        if (raw == null || raw.isEmpty()) {
            listener.onLog("EIR 扫到 " + name + " [" + addr + "]：无厂商数据");
            return;
        }
        AppleDeviceIe ie = AppleDeviceIe.parse(raw);
        listener.onLog("EIR 扫到 " + name + " [" + addr + "]：厂商数据 " + raw.length()
                + " 字符 → " + (ie == null ? "解析不出" : ie.toString()));
        if (ie != null && ie.hasHotspot() && reported.compareAndSet(false, true)) {
            listener.onAppleDeviceIe(name, addr, ie);
        }
    }
}
