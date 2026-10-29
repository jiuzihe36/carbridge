package com.carbridge.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机 / 覆盖安装后把 {@link SdpBeaconService} 拉起来。
 */
public final class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "carbridge";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {
            Log.i(TAG, "收到 " + action + " → 拉起 SDP 信标服务");
            SdpBeaconService.start(context);
        }
    }
}
