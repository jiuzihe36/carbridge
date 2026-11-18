package com.carbridge.app;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.view.View;
import android.widget.TextView;

import com.carbridge.carplay.CarPlayCtrlCompat;
import com.carbridge.carplay.bt.BtIdentity;
import com.carbridge.carplay.bt.CarPlaySdpBeacon;

/**
 * 「本机身份」这一块的读取与呈现（蓝牙名/地址/车机识别/广播版本）。
 *
 * <p>从 MainActivity 抽出来的第二批：这一块只做「读状态 → 填四行文本」，
 * 不碰连接状态机，所以边界清楚。带输入框的两个对话框仍留在 Activity 里
 * （它们本质是交互，跟着 Activity 走更自然）。
 */
public final class IdentitySection {

    /** Activity 需要提供的最小能力（避免把整个 Activity 传进来后到处乱用）。 */
    public interface Host {
        Activity activity();

        void log(String line);

        int color(int colorRes);
    }

    private final Host host;
    private final TextView vName, vMac, vSdp, vSrcvers;
    private final View hint;

    public IdentitySection(Host host, TextView vName, TextView vMac, TextView vSdp,
                           TextView vSrcvers, View hint) {
        this.host = host;
        this.vName = vName;
        this.vMac = vMac;
        this.vSdp = vSdp;
        this.vSrcvers = vSrcvers;
        this.hint = hint;
    }

    /** 把当前身份刷进四行。任何一行取不到都不抛异常，界面照样能看。 */
    public void refresh() {
        if (vName == null) return;
        Activity act = host.activity();

        String name = null;
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if (a != null) name = a.getName();
        } catch (Throwable t) {
            com.carbridge.carplay.util.Diag.w("IdentitySection.name", t);
        }
        vName.setText(name == null || name.length() == 0
                ? act.getString(R.string.identity_bt_name_unknown) : name);

        BtIdentity.Resolved id = BtIdentity.peek(act);
        if (id.ok()) {
            vMac.setText(id.mac);
            vMac.setTextColor(host.color(R.color.ink));
            if (hint != null) hint.setVisibility(View.GONE);
        } else {
            // 拿不到地址时**必须**标红 + 显示指引：车机是按这个地址认人的，
            // 地址错了后面全是白费功夫，不能让用户以为「没填也行」。
            vMac.setText(R.string.identity_mac_missing);
            vMac.setTextColor(host.color(R.color.state_err));
            if (hint != null) hint.setVisibility(View.VISIBLE);
        }

        if (vSdp != null) {
            boolean btOn = BtIdentity.bluetoothOn(act);
            boolean good = CarPlaySdpBeacon.carplayMarkPresent();
            vSdp.setText(btOn ? CarPlaySdpBeacon.statusLine()
                    : act.getString(R.string.identity_sdp_bt_off));
            vSdp.setTextColor(host.color(good ? R.color.state_ok : R.color.state_err));
        }
        if (vSrcvers != null) {
            vSrcvers.setText(CarPlayCtrlCompat.srcvers(act));
        }
    }

    /**
     * 后台解析一次真实 MAC（可能要走 root shell，最长 1.5s），成功再刷界面。
     *
     * <p>为什么不在主线程直接 resolve：`su` 是阻塞调用，主线程上做会把打开 App
     * 的一瞬卡住；而界面停在「未获取」，用户会以为功能坏了。
     */
    public void probeInBackground() {
        final Activity act = host.activity();
        BtIdentity.Resolved quick = BtIdentity.peek(act);
        if (quick.ok() || !BtIdentity.bluetoothOn(act)) return;
        com.carbridge.carplay.util.Bg.run("identity-probe", new Runnable() {
            @Override
            public void run() {
                final BtIdentity.Resolved r = BtIdentity.resolve(act, false);
                BtIdentity.remember(act, r);
                if (r.ok()) {
                    act.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            host.log("本机蓝牙地址（后台解析）：" + r.mac + "（来源 " + r.source + "）");
                            refresh();
                        }
                    });
                }
            }
        });
    }
}
