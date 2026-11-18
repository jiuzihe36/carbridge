package com.carbridge.fakecar;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * 便携假车机 —— 把一台 root 手机变成「真车机」来测另一台手机上的 carbridge。
 *
 * <p>单页 UI：自动找手机并连接 / 停止 / 查 SDP，底部是滚动日志。
 * 核心链路都在 {@link FakeCarController} 里，这里只做交互和日志展示。
 */
public final class MainActivity extends Activity {

    private final Handler main = new Handler(Looper.getMainLooper());
    private final StringBuilder logBuf = new StringBuilder();

    private FakeCarController controller;
    private TextView statusView;
    private TextView logView;
    private ScrollView logScroll;
    private EditText phoneIpEdit;
    private Button btnAuto;
    private Button btnSdp;
    private Button btnStop;
    private VideoSurface videoSurface;
    private volatile boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusView = (TextView) findViewById(R.id.status);
        logView = (TextView) findViewById(R.id.log);
        logScroll = (ScrollView) findViewById(R.id.log_scroll);
        phoneIpEdit = (EditText) findViewById(R.id.phone_ip);
        btnAuto = (Button) findViewById(R.id.btn_auto);
        btnSdp = (Button) findViewById(R.id.btn_sdp);
        btnStop = (Button) findViewById(R.id.btn_stop);

        controller = new FakeCarController(this,
                new FakeCarController.Log() {
                    @Override
                    public void onLog(String line) {
                        log(line);
                    }
                },
                new FakeCarController.Phase() {
                    @Override
                    public void onPhase(String name) {
                        setStatus(name);
                    }
                });

        // 真车 1:1：有 carprofile.json 就用它覆盖自报参数（model/serial/want-list/deviceId…）
        controller.loadCarProfile(getExternalFilesDir(null));

        phoneIpEdit.setText(controller.config().phoneIp);

        // ── 屏幕流渲染 + 触摸回传 ──
        SurfaceView videoView = (SurfaceView) findViewById(R.id.video);
        videoSurface = new VideoSurface(videoView, new VideoSurface.Log() {
            @Override
            public void onLog(String line) {
                log(line);
            }
        });
        controller.setVideoFrame(new AirPlayServer.VideoFrame() {
            @Override
            public void onConfig(byte[] avcC) {
                videoSurface.onConfig(avcC);
            }

            @Override
            public void onFrame(int frameNum, byte[] avcc) {
                videoSurface.onFrame(avcc);
            }
        });
        videoView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                float nx = Math.max(0f, Math.min(1f, event.getX() / Math.max(1, v.getWidth())));
                float ny = Math.max(0f, Math.min(1f, event.getY() / Math.max(1, v.getHeight())));
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                    case MotionEvent.ACTION_MOVE:
                        controller.sendTouch(nx, ny, true);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        controller.sendTouch(nx, ny, false);
                        return true;
                }
                return false;
            }
        });

        btnAuto.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                if (busy) {
                    log("正在连接中，请先点「停止」");
                    return;
                }
                controller.config().phoneIp = phoneIpEdit.getText().toString().trim();
                runInBackground(new Runnable() {
                    @Override
                    public void run() {
                        busy = true;
                        BluetoothDevice dev = controller.findCarPlayDevice();
                        if (dev == null) {
                            log("没有找到 CarPlay 手机。请确认：目标手机已配对 + 目标手机 carbridge 已打开。");
                            busy = false;
                            return;
                        }
                        controller.start(dev);
                        busy = false;
                    }
                });
            }
        });

        btnSdp.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                runInBackground(new Runnable() {
                    @Override
                    public void run() {
                        queryAllSdp();
                    }
                });
            }
        });

        btnStop.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                runInBackground(new Runnable() {
                    @Override
                    public void run() {
                        controller.stop();
                        setStatus("idle");
                    }
                });
            }
        });

        requestRuntimePermissions();
        log("—— 便携假车机就绪 ——");
        log("本机(LAN) IP: " + FakeCarController.detectLanIp());
        log("本机蓝牙 MAC: " + FakeCarController.detectBtMac());
        log("目标手机先和本机蓝牙配对，再点「自动找手机并连接」");
        // 自动化用：adb shell am start -n com.carbridge.fakecar/.MainActivity --ez autoconnect true
        // 冒烟测试要把「点按钮」这一步也自动化，否则每次都要人手点。
        // 一进来就把保活服务拉起来：平板（vivo）会把后台进程冻死，
        // 只靠「点连接后才启动」的话，放一会儿整个 App 就没了 —— 现场表现就是
        // 「假车机连不上」，其实进程早被杀掉。
        KeepAliveService.start(this);
        autoConnectIfRequested();
        log("屏幕流会自动渲染到上方画面，直接点画面即可触摸回传手机");
    }

    private void queryAllSdp() {
        BtCarConnector conn = new BtCarConnector(this);
        List<BluetoothDevice> bonded = conn.bondedDevices();
        log("已配对 " + bonded.size() + " 台，逐个查 SDP：");
        for (BluetoothDevice d : bonded) {
            log("· " + conn.describe(d));
            log("  UUID：" + conn.describeUuids(d, 5000, new BtCarConnector.Log() {
                @Override
                public void onLog(String line) {
                    log(line);
                }
            }, true));
        }
        log("—— SDP 查询结束 ——");
    }

    // ================================================================== 权限

    private void requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT < 31) return;
        java.util.List<String> want = new java.util.ArrayList<>();
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            want.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
            want.add(Manifest.permission.BLUETOOTH_SCAN);
        }
        if (!want.isEmpty()) {
            requestPermissions(want.toArray(new String[0]), 1001);
        }
    }

    // ================================================================== UI

    private void runInBackground(final Runnable r) {
        new Thread(r, "fakecar-bg").start();
    }

    /** 冒烟测试用：adb 带 --ez autoconnect true 启动时自动点「找手机并连接」。 */
    private void autoConnectIfRequested() {
        if (getIntent() == null || !getIntent().getBooleanExtra("autoconnect", false)) return;
        getIntent().removeExtra("autoconnect");   // 只触发一次
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (btnAuto != null) btnAuto.performClick();
            }
        }, 800);
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Activity 已在运行时，am start --ez autoconnect true 走的是 onNewIntent，
        // 只读 onCreate 的 intent 会漏掉（冒烟脚本踩过这个坑）。
        autoConnectIfRequested();
    }

    private void log(final String line) {
        android.util.Log.i("fakecar", line);
        main.post(new Runnable() {
            @Override
            public void run() {
                logBuf.append(line).append('\n');
                logView.setText(logBuf.toString());
                logScroll.post(new Runnable() {
                    @Override
                    public void run() {
                        logScroll.fullScroll(ScrollView.FOCUS_DOWN);
                    }
                });
            }
        });
    }

    private void setStatus(final String phase) {
        final String text = phaseLabel(phase);
        main.post(new Runnable() {
            @Override
            public void run() {
                statusView.setText(text);
            }
        });
    }

    private static String phaseLabel(String phase) {
        if (phase == null) return "未连接";
        switch (phase) {
            case "iap2":
                return "✓ iAP2 握手完成";
            case "identified":
                return "✓ 设备识别完成";
            case "mfi":
                return "✓ MFi 认证通过";
            case "greeting":
                return "✓ 收到设备问候";
            case "wifi":
                return "✓ 已发 WiFi 配置(0x5703)";
            case "ctrl-int":
                return "→ ctrl-int 邀请中";
            case "ctrl-int-ok":
                return "✓ ctrl-int 邀请被接受";
            case "endpoint":
                return "✓ 已发端点(0x4301)";
            case "airplay":
                return "✓ AirPlay 配对成功(ANNOUNCE)";
            case "screen":
                return "✓ 屏幕流出帧";
            case "audio":
                return "✓ 音频流首包";
            case "idle":
                return "未连接";
            default:
                return phase;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (controller != null) controller.stop();
        if (videoSurface != null) videoSurface.release();
    }
}
