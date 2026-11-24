package com.carbridge.carplay.bt;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Parcelable;
import android.os.ParcelUuid;

/**
 * 取蓝牙适配器 / 蓝牙设备广播载荷的唯一入口。
 *
 * <p>{@code BluetoothAdapter.getDefaultAdapter()} 从 API 31 起被标记废弃，官方推荐走
 * {@link BluetoothManager}。这里统一收口，避免调用点各自判断；实在拿不到 Context 的
 * 老路径（静态工具方法）继续走 {@link #adapterDeprecated()}，并且**只在这一处**
 * 压制警告——这样 lint 里再出现 deprecation 就一定是新代码引入的。
 *
 * <p>同理，{@code Intent.getParcelableExtra(String)} 从 API 33 起废弃，新重载要带
 * Class。所有蓝牙广播里取设备的调用点都走 {@link #deviceFrom(Intent)}。
 */
public final class Bt {

    private Bt() {
    }

    /** 优先走 BluetoothManager；拿不到再退回旧 API。 */
    public static BluetoothAdapter adapter(Context ctx) {
        if (ctx != null) {
            try {
                BluetoothManager m = (BluetoothManager) ctx.getApplicationContext()
                        .getSystemService(Context.BLUETOOTH_SERVICE);
                if (m != null && m.getAdapter() != null) return m.getAdapter();
            } catch (Throwable t) {
                com.carbridge.carplay.util.Diag.w("Bt.adapter", t);
            }
        }
        return adapterDeprecated();
    }

    /**
     * 没有 Context 可用的静态路径专用（如配对工具、信标状态）。
     *
     * <p>这是全项目唯一允许出现 {@code getDefaultAdapter()} 的地方；
     * 它仍然可用，只是官方不推荐。要迁移就得给这些静态方法补 Context 参数，
     * 那属于接口改动，单独一轮做。
     */
    @SuppressWarnings("deprecation")
    public static BluetoothAdapter adapterDeprecated() {
        try {
            return BluetoothAdapter.getDefaultAdapter();
        } catch (Throwable t) {
            com.carbridge.carplay.util.Diag.w("Bt.adapterDeprecated", t);
            return null;
        }
    }

    /**
     * 从蓝牙广播里取设备（{@code ACTION_FOUND} / {@code BOND_STATE_CHANGED} 等）。
     *
     * <p>API 33+ 用带 Class 的新重载，旧版本走旧的——**全项目只在这里压制废弃警告**。
     */
    public static BluetoothDevice deviceFrom(Intent intent) {
        if (intent == null) return null;
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
            } catch (Throwable t) {
                com.carbridge.carplay.util.Diag.w("Bt.deviceFrom", t);
                return null;
            }
        }
        return deviceFromLegacy(intent);
    }

    @SuppressWarnings("deprecation")
    private static BluetoothDevice deviceFromLegacy(Intent intent) {
        try {
            return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        } catch (Throwable t) {
            com.carbridge.carplay.util.Diag.w("Bt.deviceFromLegacy", t);
            return null;
        }
    }

    /**
     * 从蓝牙广播里取 UUID 列表（{@code ACTION_UUID}）。
     *
     * <p>API 33+ 用带 Class 的新重载，旧版本走旧的——全项目只在这里压制废弃警告。
     */
    public static ParcelUuid[] uuidsFrom(Intent intent) {
        if (intent == null) return null;
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                return intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID, ParcelUuid.class);
            } catch (Throwable t) {
                com.carbridge.carplay.util.Diag.w("Bt.uuidsFrom", t);
                return null;
            }
        }
        Parcelable[] raw = uuidsLegacy(intent);
        if (raw == null || raw.length == 0) return null;
        ParcelUuid[] u = new ParcelUuid[raw.length];
        for (int i = 0; i < raw.length; i++) {
            u[i] = raw[i] instanceof ParcelUuid ? (ParcelUuid) raw[i] : null;
        }
        return u;
    }

    @SuppressWarnings("deprecation")
    private static Parcelable[] uuidsLegacy(Intent intent) {
        try {
            return intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID);
        } catch (Throwable t) {
            com.carbridge.carplay.util.Diag.w("Bt.uuidsLegacy", t);
            return null;
        }
    }
}
