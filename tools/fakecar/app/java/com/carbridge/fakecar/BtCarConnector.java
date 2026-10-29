package com.carbridge.fakecar;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.ParcelUuid;
import android.os.Parcelable;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 便携假车机的 <b>蓝牙 RFCOMM 客户端</b> —— 扮演「车机」主动连目标手机的 carbridge。
 *
 * <p>真车识别 iPhone 只看 SDP 里的 2d8d2466（CommonUtil.isSupportCarPlay），真正连的是
 * 车机「笔误版」iAP2 服务 {@code 00000000-cade-defa-deca-deafdecacafe}。这里 1:1 复刻：
 * 先查 SDP 认 2d8d2466，再连 CADE 通道；连不上退回通道扫描（1..30）。
 */
public final class BtCarConnector {

    public interface Log {
        void onLog(String line);
    }

    public static final UUID MARKER_UUID = UUID.fromString("2d8d2466-e14d-451c-88bc-7301abea291a");
    public static final UUID CADE_UUID = UUID.fromString("00000000-cade-defa-deca-deafdecacafe");
    public static final UUID STD_IAP2_UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacafe");
    public static final UUID WIRELESS_UUID = UUID.fromString("02030302-1d19-415f-86f2-22a2106a0a77");

    private final Context context;

    public BtCarConnector(Context context) {
        this.context = context.getApplicationContext();
    }

    /** 已配对设备（按名称+MAC 排成一行）。 */
    public List<BluetoothDevice> bondedDevices() {
        List<BluetoothDevice> out = new ArrayList<>();
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a == null) return out;
        Set<BluetoothDevice> s = a.getBondedDevices();
        if (s != null) out.addAll(s);
        return out;
    }

    public String describe(BluetoothDevice d) {
        return (d.getName() == null ? "(未命名)" : d.getName()) + "  " + d.getAddress();
    }

    /**
     * 查一台设备的 SDP UUID（认 iPhone 靠 2d8d2466）。
     * 先读缓存，缓存没有就 {@code fetchUuidsWithSdp()} 强制重查（配对后缓存可能为空）。
     */
    public ParcelUuid[] fetchUuids(BluetoothDevice device, long timeoutMs, Log log) {
        return fetchUuids(device, timeoutMs, log, false);
    }

    /**
     * 强制现场重查 SDP（不走缓存）。
     *
     * <p>车机/手机拿到的 UUID 名单是**配对那一刻**的缓存，普通重连不会刷新。
     * 自检时要看「现在挂了什么」，必须强制重查，否则看到的可能是旧版本留下的名单。
     */
    public ParcelUuid[] fetchUuidsFresh(BluetoothDevice device, long timeoutMs, Log log) {
        return fetchUuids(device, timeoutMs, log, true);
    }

    private ParcelUuid[] fetchUuids(BluetoothDevice device, long timeoutMs, Log log,
                                    boolean force) {
        ParcelUuid[] cached = device.getUuids();
        if (!force && cached != null && cached.length > 0) return cached;
        if (log != null) log.onLog("  强制现场重查 SDP…");

        final Object lock = new Object();
        final ParcelUuid[][] result = new ParcelUuid[1][];
        final BroadcastReceiver recv = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                BluetoothDevice dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (dev != null && dev.equals(device)) {
                    // ⚠️ Android 12+ 这里实际给的是 Parcelable[]，直接强转 ParcelUuid[]
                    // 会 ClassCastException（现场把假车机 App 整个崩掉）。
                    Parcelable[] raw = intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID);
                    if (raw != null) {
                        ParcelUuid[] arr = new ParcelUuid[raw.length];
                        for (int i = 0; i < raw.length; i++) {
                            arr[i] = raw[i] instanceof ParcelUuid ? (ParcelUuid) raw[i] : null;
                        }
                        result[0] = arr;
                    }
                    synchronized (lock) {
                        lock.notifyAll();
                    }
                }
            }
        };
        try {
            context.registerReceiver(recv, new IntentFilter(BluetoothDevice.ACTION_UUID));
            device.fetchUuidsWithSdp();
            synchronized (lock) {
                try {
                    lock.wait(timeoutMs);
                } catch (InterruptedException ignored) {
                }
            }
        } catch (Throwable t) {
            if (log != null) log.onLog("  SDP 重查异常: " + t);
        } finally {
            try {
                context.unregisterReceiver(recv);
            } catch (Throwable ignored) {
            }
        }
        return result[0] != null ? result[0] : device.getUuids();
    }

    /** 判定一台设备是不是「支持 CarPlay 的 iPhone」（SDP 里有 2d8d2466）。 */
    public boolean isCarPlayDevice(BluetoothDevice device, long timeoutMs, Log log) {
        ParcelUuid[] uuids = fetchUuids(device, timeoutMs, log);
        if (uuids == null) return false;
        for (ParcelUuid u : uuids) {
            if (u == null) continue;
            String s = u.getUuid().toString();
            if (MARKER_UUID.toString().equalsIgnoreCase(s)) return true;
        }
        return false;
    }

    /** 逐条列出设备的 SDP UUID，匹配我们关心的 4 条。 */
    public String describeUuids(BluetoothDevice device, long timeoutMs, Log log) {
        return describeUuids(device, timeoutMs, log, false);
    }

    /** 逐条列出设备的 SDP UUID；force=true 时强制现场重查，不看缓存。 */
    public String describeUuids(BluetoothDevice device, long timeoutMs, Log log, boolean force) {
        ParcelUuid[] uuids = force
                ? fetchUuidsFresh(device, timeoutMs, log)
                : fetchUuids(device, timeoutMs, log);
        if (uuids == null || uuids.length == 0) return "(无 UUID)";
        StringBuilder sb = new StringBuilder();
        for (ParcelUuid u : uuids) {
            if (u == null) continue;
            String s = u.getUuid().toString();
            String tag;
            if (MARKER_UUID.toString().equalsIgnoreCase(s)) tag = "← 2d8d2466 认 iPhone";
            else if (CADE_UUID.toString().equalsIgnoreCase(s)) tag = "← CADE 连这个";
            else if (STD_IAP2_UUID.toString().equalsIgnoreCase(s)) tag = "← 标准 iAP2";
            else if (WIRELESS_UUID.toString().equalsIgnoreCase(s)) tag = "← Wireless iAP v2";
            else tag = "";
            sb.append("\n     · ").append(s).append(" ").append(tag);
        }
        return sb.toString();
    }

    /**
     * 连目标手机：先 CADE UUID（createRfcommSocketToServiceRecord 内部做 SDP 查通道），
     * 失败退回通道扫描 1..30（反射 {@code createRfcommSocket(int)}）。
     */
    public BluetoothSocket connectCade(BluetoothDevice device, Log log) {
        if (log != null) log.onLog("WCM：连接 CADE 通道 " + CADE_UUID);
        try {
            BluetoothSocket s = device.createRfcommSocketToServiceRecord(CADE_UUID);
            s.connect();
            if (log != null) log.onLog("✓ CADE RFCOMM 已连接");
            return s;
        } catch (IOException e) {
            if (log != null) log.onLog("  CADE 直连失败: " + e.getMessage() + " → 退回通道扫描");
        }

        for (int ch = 1; ch <= 30; ch++) {
            try {
                BluetoothSocket s = createRfcommSocketByChannel(device, ch);
                s.connect();
                if (log != null) log.onLog("✓ 通道扫描命中：RFCOMM channel " + ch);
                return s;
            } catch (Exception ignored) {
                // 继续扫
            }
        }
        if (log != null) log.onLog("✗ 没有找到活口通道（1..30 全失败）");
        return null;
    }

    private BluetoothSocket createRfcommSocketByChannel(BluetoothDevice device, int channel)
            throws Exception {
        Method m = device.getClass().getMethod("createRfcommSocket", int.class);
        return (BluetoothSocket) m.invoke(device, channel);
    }
}
