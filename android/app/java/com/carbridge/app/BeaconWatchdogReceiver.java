package com.carbridge.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 看门狗：定时把 {@link SdpBeaconService} 拉回来。
 */
public final class BeaconWatchdogReceiver extends BroadcastReceiver {

    private static final String TAG = "carbridge";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "看门狗触发 → 重新拉起 SDP 信标服务");
        // 幂等：服务已经在跑就直接返回，只有被杀掉了才会真的重建。
        SdpBeaconService.start(context);
    }
}
