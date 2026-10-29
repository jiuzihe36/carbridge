package com.carbridge.app;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Set;

/**
 * 车机配对关系管理。
 */
public final class CarPairing {

    private static final String TAG = "carbridge";

    /** 日志出口。 */
    public interface Log {
        void onLog(String line);
    }

    private CarPairing() {
    }

    /**
     * 在已配对设备里找出车机。
     *
     * <p>已知地址优先；其次看 CoD 是否为主「车载音频」。
     */
    public static BluetoothDevice findCar() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) return null;
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        String known = knownCarAddress();
        for (BluetoothDevice d : bonded) {
            if (known != null && d.getAddress().equalsIgnoreCase(known)) {
                return d;
            }
            int cod = safeCod(d);
            int major = (cod >> 8) & 0x1F;
            int minor = (cod >> 2) & 0x3F;
            if (major == 0x04 && minor == 0x02) {
                return d;                      // 车载音频
            }
        }
        return null;
    }

    /** 记住「用 RFCOMM 对端认出」的车机地址。 */
    public static void rememberCarAddress(String address) {
        if (address == null || address.isEmpty()) return;
        KNOWN_CAR_ADDRESS = address;
    }

    private static volatile String KNOWN_CAR_ADDRESS;

    private static String knownCarAddress() {
        return KNOWN_CAR_ADDRESS;
    }

    /**
     * 清除手机这边的配对记录（隐藏 API {@code BluetoothDevice.removeBond}）。
     *
     * @return true = 已经发起解绑
     */
    public static boolean forgetCar(BluetoothDevice car, Log log) {
        if (car == null) {
            emit(log, "✗ 没找到已配对的车机");
            return false;
        }
        try {
            Method m = BluetoothDevice.class.getMethod("removeBond");
            m.setAccessible(true);
            Object ok = m.invoke(car);
            emit(log, "已发起解绑（手机侧）：" + safeName(car) + " [" + car.getAddress() + "] → " + ok);
            return true;
        } catch (Throwable t) {
            emit(log, "✗ 解绑失败（removeBond 不可用）：" + t);
            return false;
        }
    }

    /** 只检查解绑 API 能不能用，不真动手（诊断用）。 */
    public static boolean canForget() {
        try {
            BluetoothDevice.class.getMethod("removeBond");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String safeName(BluetoothDevice d) {
        try {
            return d.getName();
        } catch (Throwable t) {
            return null;
        }
    }

    private static int safeCod(BluetoothDevice d) {
        try {
            return d.getBluetoothClass() == null ? 0 : d.getBluetoothClass().getDeviceClass();
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void emit(Log log, String line) {
        android.util.Log.i(TAG, line);
        if (log != null) log.onLog(line);
    }
}
