package com.carbridge.carplay.bt;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;

/**
 * 取蓝牙适配器的唯一入口。
 *
 * <p>{@code BluetoothAdapter.getDefaultAdapter()} 从 API 31 起被标记废弃，官方推荐走
 * {@link BluetoothManager}。这里统一收口，避免调用点各自判断；实在拿不到 Context 的
 * 老路径（静态工具方法）继续走 {@link #adapterDeprecated()}，并且**只在这一处**
 * 压制警告——这样 lint 里再出现 deprecation 就一定是新代码引入的。
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
}
