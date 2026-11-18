package com.carbridge.app;
import com.carbridge.carplay.bt.CarPairing;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import com.carbridge.carplay.bt.CarPlaySdpBeacon;

/**
 * 首次连接向导：把「车机为什么认不出本机」拆成三步，一步只讲一件事。
 *
 * <p>为什么单独开一个 Activity，而不是塞进主界面：
 * 主界面正在被别人改；向导是「看一遍就关」的说明页，状态少、生命周期短，
 * 独立出来既能随时从别处（帮助入口、首次启动）拉起，也不会碰到 MainActivity 的任何逻辑。
 *
 * <p>三盏灯全部按系统真实状态判定，不写本地「已完成」标记：
 * 用户可能转头去系统设置里关掉蓝牙、删掉配对，灯必须自己变回去，否则向导会骗人。
 *
 * <p>入口：这次不动 MainActivity，所以没在主界面挂按钮；任何地方都可以直接
 * {@code startActivity(new Intent(this, GuideWizardActivity.class))} 拉起来。
 */
public class GuideWizardActivity extends Activity {

    /** 与主界面同一组状态色：灰=未完成、蓝=进行中、绿=完成。 */
    private static final int C_IDLE = 0xFF8E8E93;
    private static final int C_WORK = 0xFF0A84FF;
    private static final int C_OK = 0xFF30D158;

    /** 步骤状态：还没轮到 / 现在做这个 / 已经满足。 */
    private static final int ST_TODO = 0;
    private static final int ST_DOING = 1;
    private static final int ST_DONE = 2;

    /** Android 12+ 读蓝牙开关、读已配对列表都要 BLUETOOTH_CONNECT（运行时权限）。 */
    private static final int REQ_BT = 1001;

    /**
     * 轮询间隔。蓝牙开关、配对关系都发生在系统设置里，没有可靠的广播（这台 ROM 上
     * BOND_STATE 本来就不稳），轮询最省心；2 秒够快，且只在页面可见时跑。
     */
    private static final long TICK_MS = 2000L;

    private final Handler ticker = new Handler(Looper.getMainLooper());

    private final View[] dots = new View[3];
    private final TextView[] stateTexts = new TextView[3];
    private final View[] cards = new View[3];

    /** 上一次画出去的状态；-1 表示还没画过。轮询每 2 秒一次，状态没变就别反复换 drawable。 */
    private final int[] shownStates = new int[] {-1, -1, -1};

    private TextView beaconView;

    /** 轮询体自己续约；onPause 时 removeCallbacks 掐断，页面不可见就不耗电。 */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            ticker.postDelayed(this, TICK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_guide);

        dots[0] = (View) findViewById(R.id.dot_step1);
        dots[1] = (View) findViewById(R.id.dot_step2);
        dots[2] = (View) findViewById(R.id.dot_step3);
        stateTexts[0] = (TextView) findViewById(R.id.state_step1);
        stateTexts[1] = (TextView) findViewById(R.id.state_step2);
        stateTexts[2] = (TextView) findViewById(R.id.state_step3);
        cards[0] = findViewById(R.id.card_step1);
        cards[1] = findViewById(R.id.card_step2);
        cards[2] = findViewById(R.id.card_step3);
        beaconView = (TextView) findViewById(R.id.beacon_status);

        findViewById(R.id.btn_skip).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 跳过 = 直接关页面。向导不写任何设置，主界面本来就会自己连，关掉不会留下半套状态。
                finish();
            }
        });

        findViewById(R.id.btn_detect).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 蓝牙信标的真实状态只有 SDP 注册层知道：直接问它，别在这里把判断逻辑再抄一遍。
                // statusLine() 读的是本进程里的静态状态，不需要任何权限，所以先拿它再谈权限。
                String line = CarPlaySdpBeacon.statusLine();
                if (!hasBtPermission()) {
                    // 状态读得到，但「蓝牙开没开 / 有没有配对」两盏灯要靠权限。用户主动点了按钮，
                    // 此时弹权限框是合理的（不是每次进页面都骚扰），同时把不确定性写在文案里。
                    line += " · 蓝牙权限未授权，灯可能不准";
                    requestBtPermission();
                }
                beaconView.setText(line);
                // 检测顺带把灯重算一次：用户点按钮的潜台词就是「现在到底到哪一步了」。
                refresh();
            }
        });

        requestBtPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 用户很可能刚从系统设置或车机配对流程回来：页面一可见立刻重算，再开始轮询。
        refresh();
        ticker.removeCallbacks(tick);
        ticker.postDelayed(tick, TICK_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ticker.removeCallbacks(tick);
    }

    /**
     * 按系统真实状态重算三盏灯。
     *
     * <p>只有「第一盏没满足的灯」算进行中：用户一次只做一件事，
     * 三步同时发蓝光等于没重点。
     */
    private void refresh() {
        boolean btOn = isBtOn();
        boolean paired = btOn && isCarPaired();
        // 第三步的完成条件用「信标已注册 + CarPlay 标记在 + 监听还活着」：
        // 车机点没点 CarPlay 我们读不到，但这一刻只要信标健康，车机就一定认得出来。
        boolean ready = paired && CarPlaySdpBeacon.carplayMarkPresent();

        if (!btOn) {
            applyState(0, ST_DOING);
            applyState(1, ST_TODO);
            applyState(2, ST_TODO);
        } else if (!paired) {
            applyState(0, ST_DONE);
            applyState(1, ST_DOING);
            applyState(2, ST_TODO);
        } else if (!ready) {
            applyState(0, ST_DONE);
            applyState(1, ST_DONE);
            applyState(2, ST_DOING);
        } else {
            applyState(0, ST_DONE);
            applyState(1, ST_DONE);
            applyState(2, ST_DONE);
        }
    }

    /** 一盏灯 = 圆点颜色 + 状态文字颜色 + 卡片描边，三者必须同步，不然会互相打脸。 */
    private void applyState(int index, int state) {
        if (shownStates[index] == state) {
            // 状态没变就什么都不做：轮询是高频调用，省掉每秒几十次的 setText / 换背景。
            return;
        }
        shownStates[index] = state;

        int color = state == ST_DONE ? C_OK : (state == ST_DOING ? C_WORK : C_IDLE);
        dots[index].setBackgroundTintList(ColorStateList.valueOf(color));

        TextView label = stateTexts[index];
        label.setTextColor(color);
        label.setText(state == ST_DONE ? "完成" : (state == ST_DOING ? "进行中" : "未完成"));

        // 进行中的卡片加 1dp accent 描边：灯是点、卡是面，扫一眼就知道该看哪一块。
        // 卡片自身的 padding 在内层 LinearLayout 上，换背景不会把内容挤变形。
        cards[index].setBackgroundResource(state == ST_DOING
                ? R.drawable.bg_card_active : R.drawable.bg_card);
    }

    /**
     * 蓝牙开着没有。
     *
     * <p>API 31+ 读状态也要权限；没授权按「没开」处理，绝不让 SecurityException
     * 把向导直接崩掉 —— 灯是引导，不是功能本身。
     */
    private boolean isBtOn() {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            return adapter != null && adapter.isEnabled();
        } catch (SecurityException e) {
            return false;
        }
    }

    /** 手机里有没有已配对的车机；「哪台算车机」交给 CarPairing（它认得已知地址和车载音频 CoD）。 */
    private boolean isCarPaired() {
        try {
            return CarPairing.findCar() != null;
        } catch (SecurityException e) {
            // 同 isBtOn：没有 BLUETOOTH_CONNECT 时 getBondedDevices() 会抛，按「没配对」处理。
            return false;
        }
    }

    private boolean hasBtPermission() {
        return Build.VERSION.SDK_INT < 31
                || checkSelfPermission("android.permission.BLUETOOTH_CONNECT")
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 只申请读状态必需的 CONNECT；SCAN 一并要，免得用户在向导和主界面之间来回被弹两次。 */
    private void requestBtPermission() {
        if (Build.VERSION.SDK_INT < 31 || hasBtPermission()) {
            return;
        }
        requestPermissions(new String[] {
                "android.permission.BLUETOOTH_CONNECT",
                "android.permission.BLUETOOTH_SCAN"
        }, REQ_BT);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_BT) {
            return;
        }
        // 授权结果直接改变灯色：被拒绝也要重算一次，让页面回到「未完成」而不是停在旧状态。
        refresh();
    }
}
