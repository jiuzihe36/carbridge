package com.carbridge.app;
import android.app.AlertDialog;
import android.content.DialogInterface;
import com.carbridge.carplay.bt.BtIdentity;
import com.carbridge.carplay.CarPlayCtrlCompat;
import com.carbridge.carplay.proto.HuProto;
import com.carbridge.carplay.bt.CarPairing;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothDevice;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.carbridge.carplay.AndroidIdentityStore;
import com.carbridge.carplay.CarPlayWirelessTx;
import com.carbridge.carplay.CarProfile;
import com.carbridge.carplay.bridge.CarPlayBridge;
import com.carbridge.carplay.bt.CarPlaySdpBeacon;
import com.carbridge.carplay.core.AirPlaySession;
import com.carbridge.carplay.core.DisplayGeometry;
import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
/**
 * carbridge 主界面：状态 + 画面预览 + 触摸回传 + 运行日志。
 *
 * P0（本阶段）：把 Car+ 的画面在**本机**接住并显示，验证整条 CarLife 链路。
 * P1：把同一路 H.264 交给 CarPlay 发送端推给车机（见 docs/PROTOCOL-carplay-tx.md）。
 */
public class MainActivity extends Activity implements HuClient.Sink, CarPlayBridge.Sink {
    private static final int C_IDLE = 0xFF8E8E93;
    private static final int C_WORK = 0xFF0A84FF;
    private static final int C_OK = 0xFF30D158;
    private static final int C_ERR = 0xFFFF453A;
    private final Handler main = new Handler(Looper.getMainLooper());
    // ---- 已用时 / 阶段耗时 ----
    private TextView elapsedView;
    private long busySince;
    private long lastStateAtMs;
    private String lastStateLabel;
    private final StringBuilder sessionTimeline = new StringBuilder();
    // ---- 自动重连 ----
    private boolean userWantsConnected;
    private int retryStep;
    private TextView autoReconnectRow;

    private final List<String> logLines = new ArrayList<String>();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private TextView stateView, detailView, metricsView, logView;
    private TextView btnConnect, logHeader;
    private TextView badgeView, metricFps, metricResolution, metricBitrate;
    private LinearLayout stepsContainer;
    private ScrollView logScroll;
    private ScrollView mainScroll;
    private View logBody;
    /** 「后台保活」卡片：状态文案 + 三个动作。 */
    private HuClient client;
    private CarPlayBridge carPlay;    private CarPlayWirelessTx carPlayTx;
    private TextView btnCarPlay;
    private boolean logOpen;
    private String phoneInfo = "";
    /**
     * Car+ 进度走完后是否还允许自动起 CarPlay 推流（用户要求「无感」：
     * 打开 app → 自动连 Car+ → 走完进度 → CarPlay 自己开始）。
     * 每次新连接重新武装一次；只自动拉起一次，避免重连时反复拉。
     */
    private boolean autoCarplayArmed = true;
    /** 用户手动点过「停止推流」→ 别自作主张再帮他拉起来。 */
    private boolean carplayManualStop;
    /** 步骤渲染：所有步骤共用一个容器，按状态着色。 */
    private final java.util.Map<HuClient.Step, TextView> stepViews = new java.util.EnumMap<HuClient.Step, TextView>(HuClient.Step.class);
    private HuClient.Step currentStep;
    private HuClient.Step failedStep;
    private String failedReason = "";
    /** CarPlay 侧步骤。 */
    private CarPlayWirelessTx.Step carPlayStep;
    private CarPlayWirelessTx.Step carPlayFailedStep;
    private String carPlayFailedReason = "";
    /** 整体进度条（合并两组后共 15 步）。 */
    private ProgressBar stepsProgress;
    private TextView stepsProgressText;
    /** 合并后的步骤行数（CarLife + 分隔标题 + CarPlay）。 */
    private static final int CARLIFE_STEPS = HuClient.Step.values().length;
    private static final int CARPLAY_STEPS = CarPlayWirelessTx.Step.values().length;
    private static final int TOTAL_STEPS = CARLIFE_STEPS + CARPLAY_STEPS;
    /** 连接流向图。 */
    private LinkDiagramView linkDiagram;
    // ── 底部两页切换 ──
    /** 页面容器：0=连接、1=诊断。 */
    private final View[] pages = new View[2];
    /** 底部标签（用于高亮当前页）。 */
    private final TextView[] tabs = new TextView[2];
    /** 当前页索引。 */
    private int currentTab;
    /** 配对状态监听（目标车机 只在 bond 那一刻对 SDP，必须看得见）。 */
    private android.content.BroadcastReceiver bondReceiver;
    /** 车机配对轮询：系统 BOND_STATE 广播在这台 ROM 上不稳定，直接看已配对列表更可靠。 */
    private final Handler bondPoll = new Handler(Looper.getMainLooper());
    private boolean bondPolling;
    private String lastBondedCarAddress;
    /** 每 5 次配对轮询（10 秒）做一次信标体检。 */
    private int beaconTick;
    /** 指标：由视频线程写、主线程读 → volatile（否则读到的是旧值）。 */
    private volatile int fpsFrames;
    private volatile long fpsWindowStart;
    private float fpsValue;
    private volatile long totalBytes;
    private volatile long firstFrameDelayMs;
    /** 当前存活的 Activity 实例（防止系统恢复任务时出现两套 CarPlay/iAP2 发送端）。 */
    private static MainActivity sAlive;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 极少数路径（应用更新后系统恢复任务、又从桌面图标拉起）会同时出现两个实例。
        // 保留最新这个，旧实例主动结束：否则两套 iAP2 发送端/iAP2 接收线程会抢同一份
        // 蓝牙连接，表现就是车机侧时好时坏。
        MainActivity prev = sAlive;
        sAlive = this;
        if (prev != null && prev != this) {
            prev.finish();
        }
        // 自保护：签名钉死 / 反调试 / 反 hook。命中即 halt，所以放在最前面。
        installCrashProbe();
        // 上次被系统杀掉时可能没恢复本机铃声（车机模式会把 ring 音量压到 0）→ 先补回来
        com.carbridge.carplay.audio.CarAudioRouter.restoreIfMutedOnStart(this, new com.carbridge.carplay.audio.CarAudioRouter.Log() {
            @Override
            public void onLog(String line) {
                log(line);
            }
        });
        setContentView(R.layout.activity_main);
        // 标题下的发丝线：内容滚动离开顶部才显示（iOS 导航栏行为）
        mainScroll = (ScrollView) findViewById(R.id.main_scroll);
        final View headerDivider = findViewById(R.id.header_divider);
        if (mainScroll != null && headerDivider != null) {
            mainScroll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
                @Override
                public void onScrollChange(View v, int scrollX, int scrollY,
                                           int oldScrollX, int oldScrollY) {
                    headerDivider.setVisibility(scrollY > dp(2) ? View.VISIBLE : View.INVISIBLE);
                }
            });
        }
        // 配对身份持久化：换手机/重装后会重新配对，但同机重启不用
        AirPlaySession.setIdentityStore(new AndroidIdentityStore(this));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applyWindowInsets();
        requestRuntimePermissions();
        startCarPlayBeacon();
        log("carbridge v0.1.1 启动，日志文件: " + logFile().getAbsolutePath());
        stateView = (TextView) findViewById(R.id.state);
        detailView = (TextView) findViewById(R.id.detail);
        logView = (TextView) findViewById(R.id.log);
        btnConnect = (TextView) findViewById(R.id.btn_connect);
        logHeader = (TextView) findViewById(R.id.log_header);
        logBody = findViewById(R.id.log_body);
        logScroll = (ScrollView) findViewById(R.id.log_scroll);
        badgeView = (TextView) findViewById(R.id.badge);
        metricFps = (TextView) findViewById(R.id.metric_fps);
        metricResolution = (TextView) findViewById(R.id.metric_resolution);
        metricBitrate = (TextView) findViewById(R.id.metric_bitrate);
        stepsContainer = (LinearLayout) findViewById(R.id.steps_container);
        stepsProgress = (ProgressBar) findViewById(R.id.steps_progress);
        stepsProgressText = (TextView) findViewById(R.id.steps_progress_text);
        elapsedView = (TextView) findViewById(R.id.elapsed);
        idBtName = (TextView) findViewById(R.id.id_bt_name);
        idBtMac = (TextView) findViewById(R.id.id_bt_mac);
        idSdp = (TextView) findViewById(R.id.id_sdp);
        idSrcvers = (TextView) findViewById(R.id.id_srcvers);
        identityHint = findViewById(R.id.identity_hint);
        View rowMac = findViewById(R.id.row_bt_mac);
        if (rowMac != null) {
            rowMac.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showMacDialog(); }
            });
        }
        View rowSrcvers = findViewById(R.id.row_srcvers);
        if (rowSrcvers != null) {
            rowSrcvers.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showSrcversDialog(); }
            });
        }
        installCrashLogger();
        // 首次启动自动打开 3 步向导（只弹一次）
        try {
            android.content.SharedPreferences gp = getSharedPreferences("guide", MODE_PRIVATE);
            if (!gp.getBoolean("done", false)) {
                gp.edit().putBoolean("done", true).apply();
                startActivity(new Intent(this, GuideWizardActivity.class));
            }
        } catch (Throwable tDiag) {
            com.carbridge.carplay.util.Diag.w("guide", tDiag);
        }
        buildStepRows();
        // 版本号：从 PackageInfo 读，避免手写与打包版本不一致
        TextView aboutVersion = (TextView) findViewById(R.id.about_version);
        if (aboutVersion != null) {
            try {
                aboutVersion.setText(getPackageManager()
                        .getPackageInfo(getPackageName(), 0).versionName);
            } catch (Throwable tDiag) { com.carbridge.carplay.util.Diag.w("MainActivity", tDiag);
            }
        }

        // ── 折叠卡片：操作页默认只显示标题行，点开才展开详情 ──
        setupCollapse(R.id.guide_header, R.id.guide_body, R.string.guide_title);
        setupCollapse(R.id.quality_header, R.id.quality_body, R.string.quality_title);
        View btnExport = findViewById(R.id.btn_export);
        if (btnExport != null) {
            btnExport.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { exportDiagPack(); }
            });
        }
        autoReconnectRow = (TextView) findViewById(R.id.row_autoreconnect);
        if (autoReconnectRow != null) {
            refreshAutoReconnectRow();
            autoReconnectRow.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean now = !autoReconnectEnabled();
                    getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                            .putBoolean("auto_reconnect", now).apply();
                    refreshAutoReconnectRow();
                    log("自动重连：" + (now ? "开" : "关"));
                }
            });
        }
        setupLogCollapse();
        // ── 底部两页切换 ──
        pages[0] = findViewById(R.id.page_ops);
        pages[1] = findViewById(R.id.page_progress);
        tabs[0] = (TextView) findViewById(R.id.tab_ops);
        tabs[1] = (TextView) findViewById(R.id.tab_progress);
        for (int i = 0; i < tabs.length; i++) {
            final int idx = i;
            tabs[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showPage(idx);
                }
            });
        }
        showPage(0);
        setupQualitySettings();
        final View statusDot = findViewById(R.id.dot);
        stateView.setTag(statusDot);
        btnConnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleConnect();
            }
        });
        btnCarPlay = (TextView) findViewById(R.id.btn_carplay);
        btnCarPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleCarPlay();
            }
        });
        // ── 连接指引（无需触发）：车机会自动识别本机并连接 ──
        SharedPreferences carPref = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        TextView btnSelfCheck = (TextView) findViewById(R.id.btn_selfcheck);
        if (btnSelfCheck != null) {
            btnSelfCheck.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    runConnectionSelfCheck();
                }
            });
        }
        TextView btnAutostart = (TextView) findViewById(R.id.btn_autostart);
        if (btnAutostart != null) {
            btnAutostart.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openAutostartSettings();
                }
            });
        }
        findViewById(R.id.btn_copy).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyLog();
            }
        });
        findViewById(R.id.btn_share).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareLog();
            }
        });
        runStartupDiagnostics();
        // 桥先建好：CarPlay 链路随时可能拿到车机端点
        ensureBridge();
        // 统一设备身份：iAP2 的 0x4E0C DeviceUUID 与 AirPlay 的 deviceID/macAddress
        // 用**同一个** UUID + 真实蓝牙 MAC（真 iPhone 就是这样）
        try {
            com.carbridge.carplay.core.AirPlaySetup.setDeviceIdentity(
                    com.carbridge.carplay.bt.BtIap2Server.realBluetoothAddress(this));
        } catch (Throwable tDiag) { com.carbridge.carplay.util.Diag.w("MainActivity", tDiag);
        }
        // ⚠️ 先把 CarPlay 鉴权/配对单独拉起来，而且必须早于 Car+：
        //    车机只会在蓝牙配对/回连的那一刻认「这是 iPhone」，
        //    这一步不依赖 Car+ 画面，也不应该等 CarLife 握手。
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (carPlayTx == null) {
                    autoCarplayArmed = true;
                    // 鉴权/iAP2 要在后台也活着：先上前台服务 + 三把锁钉住进程。
                    // 以前只靠 SdpBeaconService 保住"信标"，iAP2 接收线程跟着
                    // Activity 走，app 一进后台/锁屏就被冻结 → 车机邀请发不进来。
                    KeepAliveService.start(MainActivity.this);
                    log("先启动 CarPlay 配对/鉴权（车机侧认 iPhone）");
                    toggleCarPlay();
                }
            }
        }, 100L);
        // Car+ 的通道窗口很短，进来就自动连；推流仍按原来的流程，
        // 与上面的 CarPlay 鉴权并行，互不等待。
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (client == null || !client.isRunning()) {
                    toggleConnect();
                }
            }
        }, 3000L);
    }
    // ------------------------------------------------------------- 画质设置
    /**
     * 默认编码分辨率。宽高**可以任意手输**，不锁在预设档里。
     *
     * <p>只是提醒一句：视频是**直通不转码**推给车机的，宽高比一变，车机上那块显示区的
     * 排版就会跟着跳（显示区域的大小由车机面板决定，改不了，只能改清晰度与比例）。
     * 所以默认仍是 1920×720 这个 8:3。
     */
    private static final int DEF_W = 1920;
    private static final int DEF_H = 720;
    /** 手输宽高的上下限：低于下限手机不给推流，高于上限基本是打错了。 */
    private static final int MIN_DIM = 320;
    private static final int MAX_DIM = 3840;
    /**
     * 可选帧率。20 是 Car+ 在虚拟显示没建好时会回吐的值，留着是为了能手动对齐它。
     */
    private static final int[] FPS_OPTIONS = {20, 24, 30, 60};
    private static final String PREF_NAME = "carbridge_quality";
    private static final String KEY_W = "enc_width";
    private static final String KEY_H = "enc_height";
    private static final String KEY_FPS = "fps_index";
    private static final String KEY_AUTO_FIT = "auto_fit";
    private static final String KEY_CAR_W = "car_display_width";
    private static final String KEY_CAR_H = "car_display_height";
    private static final String KEY_CAR_FPS = "car_display_fps";
    /** 默认 @30，与改动前的写死值一致，老用户不会感觉到变化。 */
    private static final int DEF_FPS = 2;
    private int encWidth = DEF_W;
    private int encHeight = DEF_H;
    private int fpsIndex = DEF_FPS;
    /** 自动跟随车机 /info 画布（默认开，可在画质卡里关掉）。 */
    private boolean autoFit = true;
    /** 最近一次车机声明的画布；0 = 还没收到。 */
    private int carW, carH, carFps;
    private EditText editWidth;
    private EditText editHeight;
    private TextView qualityTargetView;
    private CheckBox switchAutoFit;
    /** 代码同步开关状态时置 true，避免 onCheckedChanged 回环。 */
    private boolean qualitySwitchSyncing;
    /** 分段控件选中回调。 */
    private interface OnPick {
        void onPick(int index);
    }
    private void setupQualitySettings() {
        SharedPreferences pref = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        encWidth = clampDim(pref.getInt(KEY_W, DEF_W));
        encHeight = clampDim(pref.getInt(KEY_H, DEF_H));
        fpsIndex = clampIndex(pref.getInt(KEY_FPS, DEF_FPS), FPS_OPTIONS.length);
        autoFit = pref.getBoolean(KEY_AUTO_FIT, true);
        carW = pref.getInt(KEY_CAR_W, 0);
        carH = pref.getInt(KEY_CAR_H, 0);
        carFps = pref.getInt(KEY_CAR_FPS, 0);
        // 宽高是手输的，不做分段控件。失焦时才落盘，避免每敲一个数字就写一次盘。
        editWidth = (EditText) findViewById(R.id.edit_width);
        editHeight = (EditText) findViewById(R.id.edit_height);
        qualityTargetView = (TextView) findViewById(R.id.quality_target);
        switchAutoFit = (CheckBox) findViewById(R.id.switch_auto_fit);
        if (editWidth != null) editWidth.setText(String.valueOf(encWidth));
        if (editHeight != null) editHeight.setText(String.valueOf(encHeight));
        View.OnFocusChangeListener commit = new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus) {
                    commitResolution();
                }
            }
        };
        if (editWidth != null) editWidth.setOnFocusChangeListener(commit);
        if (editHeight != null) editHeight.setOnFocusChangeListener(commit);
        if (switchAutoFit != null) {
            switchAutoFit.setChecked(autoFit);
            switchAutoFit.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    if (qualitySwitchSyncing) return;
                    autoFit = isChecked;
                    persistQuality();
                    if (client != null) {
                        client.setAutoFit(autoFit);
                        if (autoFit && carW > 0) {
                            client.onCarDisplay(carW, carH, carFps);
                        }
                    }
                    updateQualityTargetView();
                    log("画质：车机画布自动适配" + (autoFit ? "开启" : "关闭"));
                }
            });
        }
        String[] fpsLabels = new String[FPS_OPTIONS.length];
        for (int i = 0; i < FPS_OPTIONS.length; i++) {
            fpsLabels[i] = String.valueOf(FPS_OPTIONS[i]);
        }
        buildSegment((LinearLayout) findViewById(R.id.seg_fps), fpsLabels, fpsIndex,
                new OnPick() {
                    @Override public void onPick(int index) {
                        if (index == fpsIndex) return;
                        fpsIndex = index;
                        autoFit = false;          // 手动选帧率 = 放弃自动跟随，避免下一次又被覆盖
                        persistQuality();
                        restyleSegments();
                        updateQualityTargetView();
                        if (client != null) client.setAutoFit(false);
                        log("画质：帧率改为 @" + FPS_OPTIONS[index] + "，自动适配已关闭（下次连接生效）");
                    }
                });
        updateQualityTargetView();
    }
    /**
     * 把输入框里的内容读出来、校验、落盘。
     *
     * <p>空串 / 非数字 / 越界一律回落到默认值，并**把输入框改回真实生效值**——
     * 宁可屏幕上显示一个对的数，也不能把 {@code 0} 或 {@code abc} 发给手机。
     */
    private void commitResolution() {
        if (editWidth == null || editHeight == null) {
            return;
        }
        int w = parseDim(editWidth, DEF_W);
        int h = parseDim(editHeight, DEF_H);
        if (w == encWidth && h == encHeight) {
            return;
        }
        encWidth = w;
        encHeight = h;
        autoFit = false;          // 手动改分辨率 = 放弃自动跟随
        editWidth.setText(String.valueOf(w));
        editHeight.setText(String.valueOf(h));
        persistQuality();
        updateQualityTargetView();
        if (client != null) client.setAutoFit(false);
        log("画质：分辨率改为 " + w + "×" + h + "，自动适配已关闭（下次连接生效）");
    }
    /** 读输入框 → 越界夹到合法区间 → 空/非法回落默认值。 */
    private static int parseDim(EditText e, int fallback) {
        return e == null ? fallback
                : QualityPrefs.parseDim(e.getText() == null ? "" : e.getText().toString(), fallback);
    }

    private static int clampDim(int v) {
        return QualityPrefs.clampDim(v);
    }

    private static int clampIndex(int i, int size) {
        return QualityPrefs.clampIndex(i, size);
    }

    private void persistQuality() {
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                .putInt(KEY_W, encWidth)
                .putInt(KEY_H, encHeight)
                .putInt(KEY_FPS, fpsIndex)
                .putBoolean(KEY_AUTO_FIT, autoFit)
                .apply();
    }
    /** 本次连接实际要请求的编码参数：自动模式优先用上一次车机声明的画布。 */
    private int[] encoderParamsForStart() {
        return QualityPrefs.paramsForStart(autoFit, carW, carH, carFps,
                encWidth, encHeight, fpsIndex);
    }
    /** 刷新画质卡里的“车机画布 / 实际编码”状态。只在主线程调用。 */
    private void updateQualityTargetView() {
        if (switchAutoFit != null && switchAutoFit.isChecked() != autoFit) {
            qualitySwitchSyncing = true;
            switchAutoFit.setChecked(autoFit);
            qualitySwitchSyncing = false;
        }
        if (qualityTargetView == null) {
            return;
        }
        if (!autoFit) {
            int aw = client != null && client.encodedWidth() > 0 ? client.encodedWidth() : encWidth;
            int ah = client != null && client.encodedHeight() > 0 ? client.encodedHeight() : encHeight;
            int af = client != null && client.encodedFps() > 0 ? client.encodedFps() : FPS_OPTIONS[fpsIndex];
            qualityTargetView.setText(getString(R.string.quality_target_manual,
                    encWidth, encHeight, FPS_OPTIONS[fpsIndex], aw, ah, af));
            return;
        }
        if (carW <= 0 || carH <= 0) {
            qualityTargetView.setText(R.string.quality_target_unknown);
            return;
        }
        int[] fit = DisplayGeometry.fit(carW, carH);
        if (fit == null) {
            qualityTargetView.setText(R.string.quality_target_unknown);
            return;
        }
        int tf = DisplayGeometry.clampFps(carFps);
        int aw = client != null && client.encodedWidth() > 0 ? client.encodedWidth() : fit[0];
        int ah = client != null && client.encodedHeight() > 0 ? client.encodedHeight() : fit[1];
        int af = client != null && client.encodedFps() > 0 ? client.encodedFps() : tf;
        qualityTargetView.setText(getString(R.string.quality_target,
                fit[0], fit[1], tf, aw, ah, af));
    }
    /**
     * 建一个 Apple 风格的分段控件。
     *
     * <p>选中态靠「浮起的白块」而不是填色，这样整页仍然只有 accent 一个交互色，
     * 不会因为多一个控件就多出一种颜色。所有段等宽（weight=1），文字居中。
     */
    private void buildSegment(LinearLayout host, String[] labels, int selected, final OnPick pick) {
        if (host == null) return;
        host.removeAllViews();
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView t = new TextView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            t.setLayoutParams(lp);
            t.setGravity(Gravity.CENTER);
            t.setText(labels[i]);
            t.setTextSize(13);
            styleSegment(t, i == selected);
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (pick != null) pick.onPick(idx);
                }
            });
            host.addView(t);
        }
    }
    /** 选中段重画（保持视觉状态与 fpsIndex 一致）。 */
    private void restyleSegments() {
        LinearLayout fpsHost = (LinearLayout) findViewById(R.id.seg_fps);
        if (fpsHost != null) {
            for (int i = 0; i < fpsHost.getChildCount(); i++) {
                styleSegment((TextView) fpsHost.getChildAt(i), i == fpsIndex);
            }
        }
    }
    private void styleSegment(TextView t, boolean selected) {
        if (selected) {
            t.setBackgroundResource(R.drawable.bg_seg_selected);
            t.setTextColor(getResources().getColor(R.color.ink, getTheme()));
            t.setTypeface(null, Typeface.BOLD);
        } else {
            t.setBackground(null);
            t.setTextColor(getResources().getColor(R.color.text_dim, getTheme()));
            t.setTypeface(null, Typeface.NORMAL);
        }
    }
    // ------------------------------------------------------------- 连接控制
        /**
     * 拿到（必要时创建）CarPlay 桥。
     */
    private CarPlayBridge ensureBridge() {
        if (carPlay == null) {
            carPlay = new CarPlayBridge(this);
            // 品牌开屏画面：Car+ 还没把画面送过来时，先显示 carbridge 的 logo + 名称，
            // 避免车机黑屏（仿车机开机主页）。
            try {
                android.graphics.Bitmap logo = android.graphics.BitmapFactory.decodeResource(
                        getResources(), R.drawable.app_badge);
                com.carbridge.carplay.core.SplashFrame splash = new com.carbridge.carplay.core.SplashFrame(
                        logo, getString(R.string.app_name), getString(R.string.subtitle));
                carPlay.setSplash(splash);
            } catch (Throwable t) {
                log("开屏画面初始化失败（不影响主链路）: " + t);
            }
        }
        return carPlay;
    }
    private void toggleConnect() {
        if (client != null && client.isRunning()) {
            userWantsConnected = false;    // 手动断开：取消自动重连
            retryStep = 0;
            client.stop();
            client = null;
            autoCarplayArmed = false;      // 断开后不再自动拉推流
            setConnectButton(false);
            setStateDot(C_IDLE);
            // LIVE 徽标表示"此刻正在投屏"，断开就必须收掉。
            // 以前全工程只有 resetSteps() 会收它，而那里的入口（顶栏「重置」）已经删了，
            // 不显式收就是留一个假状态在屏幕上。
            if (badgeView != null) {
                badgeView.setVisibility(View.GONE);
            }
            KeepAliveService.stop(this);   // 断开才释放保活与锁
            log("已停止连接保活");
            return;
        }
        userWantsConnected = true;
        // 新的一轮连接 = 干净的一轮：步骤、进度条、指标计数全部归零。
        // 以前这些只能靠手动点顶栏「重置」，而那个按钮既含糊又常驻，已删。
        resetCarlifeSteps();
        client = new HuClient(this, this);
        // 编码参数：自动模式优先使用上一次车机 /info 的画布；首次连接还没有车机
        // 数据时回退到手动参数。真正的采用值仍以 Car+ 的 MD_VIDEO_ENCODER_INIT 为准。
        // 连接前先收一次输入框：用户可能刚敲完数字还没失焦，不收就用的是旧值。
        commitResolution();
        int[] params = encoderParamsForStart();
        client.setAutoFit(autoFit);
        client.setEncoderSize(params[0], params[1], params[2]);
        if (carW > 0) {
            client.onCarDisplay(carW, carH, carFps);
        }
        log("编码参数目标 " + params[0] + "×" + params[1] + "@" + params[2]
                + (autoFit ? "（自动适配车机画布）" : "（手动）"));
        client.setAutoLaunch(true);
        // 新的一次连接 → 重新武装自动推流，并清掉「上次是用户手动停的」这个状态
        autoCarplayArmed = true;
        carplayManualStop = false;
        // CarPlay 桥：HuClient 的视频 → AirPlaySession 推给车机
        ensureBridge();
        client.setCarPlayBridge(carPlay);
        setConnectButton(true);
        // VPN 式保活：前台服务钉住进程 + CPU/WiFi 锁 → 锁屏不打断连接
        KeepAliveService.start(this);
        ensureBatteryWhitelist();
        log("开始连接（会先拉起 Car+ 的车机通道）");
        client.start();
    }
    /**
     * 申请电池优化白名单（即「不进入省电模式」）。
     *
     * <p>不加白名单，系统会在熄屏后对本进程 Doze/省电：拉长 sleep、限制网络、
     * 最终冻结进程——心跳一停对端就拆通道，表现就是「锁屏后断连」。
     * 这里主动拉起系统对话框让用户勾选「不限制」。
     */
    private void ensureBatteryWhitelist() {
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;
            if (pm.isIgnoringBatteryOptimizations(getPackageName())) {
                log("✓ 已在电池优化白名单（不进省电模式）");
                return;
            }
            log("申请电池优化白名单：请在弹出的对话框里选「允许」，否则锁屏后会被省电策略断开");
            Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:" + getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            // 个别 ROM 禁掉该 Intent，退回通用省电设置页让用户手动加白
            log("直接拉白名单失败（" + t + "），跳到省电设置页手动添加");
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable tDiag) { com.carbridge.carplay.util.Diag.w("MainActivity", tDiag);
            }
        }
    }
    // ------------------------------------------------------------- HuClient.Sink
    @Override
    public void onState(final HuClient.State s, final String detail) {
        main.post(new Runnable() {
            @Override
            public void run() {
                trackState(s);
                // 状态 → 显示规则全部由 StatusView 决定（纯映射、有单测），这里只落地副作用
                StatusView sv = StatusView.of(kindOf(s));
                markBusy(sv.busy);
                String text = getString(stateTextRes(sv.kind));
                int color = sv.tone == StatusView.Tone.WORK ? C_WORK
                        : sv.tone == StatusView.Tone.OK ? C_OK
                        : sv.tone == StatusView.Tone.ERR ? C_ERR : C_IDLE;
                if (sv.releaseButton) setConnectButton(false);
                if (sv.resetSteps) resetCarlifeSteps();
                stateView.setText(text);
                setStateDot(color);
                boolean showDetail;
                if (sv.manualHint) {
                    detailView.setText(getString(R.string.hint_manual));
                    showDetail = true;
                } else {
                    // 只留「与标题不同」的补充信息。
                    // 手机型号（phoneInfo）刻意不显示：投屏中下面挂一串
                    // 设备型号 / 系统版本 / 存储 既不像状态也不像指标，
                    // 让状态卡变脏——日志里已经有一份了。
                    boolean sameAsTitle = detail != null && detail.equals(text);
                    boolean hasExtra = detail != null && !detail.isEmpty() && !sameAsTitle;
                    detailView.setText(hasExtra ? detail : "");
                    showDetail = hasExtra;
                }
                detailView.setVisibility(showDetail ? View.VISIBLE : View.GONE);
                View detailDivider = findViewById(R.id.detail_divider);
                if (detailDivider != null) {
                    detailDivider.setVisibility(showDetail ? View.VISIBLE : View.GONE);
                }
            }
        });
    }
    private void setStateDot(int color) {
        View v = (View) stateView.getTag();
        if (v == null) {
            return;
        }
        if (v.getBackground() != null) {
            android.graphics.drawable.GradientDrawable bg =
                    (android.graphics.drawable.GradientDrawable) v.getBackground().mutate();
            bg.setColor(color);
            v.setBackground(bg);
        }
    }
    @Override
    public void onLog(String line) {
        log(line);
    }
    @Override
    public void onVideo(byte[] data, int len, int ptsMs) {
        // 预览解码器已移除（画面直接由车机显示），这里只做帧率/码率统计
        trackMetrics(len);
    }
    @Override
    public void onPhoneInfo(final String info) {
        phoneInfo = info;
    }
    @Override
    public void onMetrics(final int frames, final long bytes, final long firstDelay) {
        firstFrameDelayMs = firstDelay;
        main.post(new Runnable() {
            @Override
            public void run() {
                updateMetrics();
            }
        });
    }
    @Override
    public void onEncoderInfo(int targetW, int targetH, int targetFps,
                              int actualW, int actualH, int actualFps) {
        main.post(new Runnable() {
            @Override
            public void run() {
                updateQualityTargetView();
                updateMetrics();
            }
        });
    }
    /**
     * CarLife 侧报告「正在通话」→ 转成 CarPlay 的 {@code 0x4155 CallStateUpdate}。
     *
     * <p>车机的 CarPlay app 收到后会把状态转进车机的 CarLife 层，
     * 车机自己的 HMI 与音频路由跟着它走。
     */
    @Override
    public void onPhoneCallState(final boolean active) {
        main.post(new Runnable() {
            @Override
            public void run() {
                CarPlayWirelessTx tx = carPlayTx;
                if (tx != null) tx.reportCallState(active);
            }
        });
    }
    // ------------------------------------------------------------- 日志
    /** 会话日志缓冲（内存环形 + 落盘），实现搬到了 {@link SessionLog}。 */
    private SessionLog sessionLog;

    private SessionLog sessionLog() {
        if (sessionLog == null) sessionLog = new SessionLog(this);
        return sessionLog;
    }

    private void log(String line) {
        android.util.Log.i("carbridge", line);
        final String entry;
        synchronized (clock) {
            entry = clock.format(new Date()) + "  " + line;
        }
        sessionLog().append(entry);
        scheduleLogUiRefresh();
    }

    private static final int LOG_MEM_CHARS = 120 * 1024;
    private static final int LOG_UI_TAIL = 250;
    private static final long LOG_FILE_MAX = 2 * 1024 * 1024;
    private static final String LOG_FILE_NAME = "carbridge-session.log";
    private int logChars;
    private FileOutputStream logFileOut;
    private boolean logFileTried;
    private final java.util.concurrent.atomic.AtomicBoolean logUiPending =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** 已用时：连接中/已连接时每秒刷新，回 idle 时隐藏并清零。 */
    private final Runnable elapsedTick = new Runnable() {
        @Override
        public void run() {
            if (elapsedView == null) return;
            if (busySince <= 0) {
                elapsedView.setVisibility(View.GONE);
                return;
            }
            long s = Math.max(0L, System.currentTimeMillis() - busySince) / 1000;
            elapsedView.setText(String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60));
            elapsedView.setVisibility(View.VISIBLE);
            main.postDelayed(this, 1000);
        }
    };

    private void markBusy(boolean busy) {
        busySince = busy ? System.currentTimeMillis() : 0;
        main.removeCallbacks(elapsedTick);
        main.post(elapsedTick);
    }

    /** 状态变化打点：离开上一状态时记下它用了多久（会话结束时打一行汇总）。 */
    private void trackState(HuClient.State s) {
        long now = System.currentTimeMillis();
        if (lastStateAtMs > 0 && lastStateLabel != null) {
            long ms = Math.max(0L, now - lastStateAtMs);
            if (sessionTimeline.length() > 0) sessionTimeline.append(" / ");
            sessionTimeline.append(lastStateLabel).append(' ')
                    .append(String.format(java.util.Locale.US, "%.1fs", ms / 1000.0));
        }
        lastStateAtMs = now;
        lastStateLabel = stateLabel(s);
        if (s == HuClient.State.IDLE || s == HuClient.State.CLOSED || s == HuClient.State.ERROR) {
            if (sessionTimeline.length() > 0) log("会话耗时汇总：" + sessionTimeline);
            sessionTimeline.setLength(0);
            lastStateAtMs = 0;
            lastStateLabel = null;
        }
        if (s == HuClient.State.STREAMING) retryStep = 0;
        // 断开/失败 → 按退避自动重连（手动点「断开」会先把 userWantsConnected 置 false）
        if ((s == HuClient.State.CLOSED || s == HuClient.State.ERROR)
                && userWantsConnected && autoReconnectEnabled()) {
            scheduleAutoReconnect();
        }
    }

    private String stateLabel(HuClient.State s) {
        switch (s) {
            case WAITING_PORT: return "等端口";
            case CONNECTING: return "建链";
            case HANDSHAKING: return "握手";
            case STREAMING: return "推流";
            case ERROR: return "失败";
            case CLOSED: return "断开";
            default: return "空闲";
        }
    }

    /** HuClient 状态 → StatusView.Kind（映射放界面层，StatusView 才能进 JVM 单测）。 */
    private static StatusView.Kind kindOf(HuClient.State s) {
        if (s == null) return StatusView.Kind.IDLE;
        switch (s) {
            case WAITING_PORT: return StatusView.Kind.WAITING;
            case CONNECTING: return StatusView.Kind.CONNECTING;
            case HANDSHAKING: return StatusView.Kind.HANDSHAKING;
            case STREAMING: return StatusView.Kind.STREAMING;
            case ERROR: return StatusView.Kind.ERROR;
            case CLOSED: return StatusView.Kind.CLOSED;
            default: return StatusView.Kind.IDLE;
        }
    }

    /** Kind → 实际文案资源（放在界面层，StatusView 保持纯逻辑）。 */
    private static int stateTextRes(StatusView.Kind k) {
        switch (k) {
            case WAITING: return R.string.state_waiting;
            case CONNECTING: return R.string.state_connecting;
            case HANDSHAKING: return R.string.state_handshaking;
            case STREAMING: return R.string.state_streaming;
            case ERROR: return R.string.state_error;
            case CLOSED: return R.string.state_closed;
            default: return R.string.state_idle;
        }
    }

    // ------------------------------------------------------------ 自动重连
    private static final long[] RETRY_DELAYS = {3000, 6000, 12000, 30000};

    private boolean autoReconnectEnabled() {
        return getSharedPreferences(PREF_NAME, MODE_PRIVATE).getBoolean("auto_reconnect", true);
    }

    private void scheduleAutoReconnect() {
        if (retryStep >= RETRY_DELAYS.length) {
            log("自动重连：已重试 " + retryStep + " 次仍失败，停止自动重试（手动点「连接」可再试）");
            return;
        }
        final long delay = RETRY_DELAYS[retryStep];
        retryStep++;
        log("自动重连：第 " + retryStep + " 次，" + (delay / 1000) + "s 后重试");
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (userWantsConnected && (client == null || !client.isRunning())) {
                    toggleConnect();
                }
            }
        }, delay);
    }

    private void refreshAutoReconnectRow() {
        if (autoReconnectRow == null) return;
        boolean on = autoReconnectEnabled();
        autoReconnectRow.setText("自动重连 · " + (on ? "开" : "关") + "（断开后 3/6/12/30s 退避重试）");
    }

    // ------------------------------------------------------------ 诊断包
    /** 打包：会话日志 + 本机身份 + 配置 + 会话耗时汇总 → 一个 zip，方便整个发出来。 */
    private void exportDiagPack() {
        try {
            java.io.File dir = new java.io.File(getExternalFilesDir(null), "diag");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("建目录失败 " + dir);
            java.io.File out = new java.io.File(dir,
                    "carbridge-diag-" + new java.text.SimpleDateFormat("MMdd-HHmmss", java.util.Locale.US)
                            .format(new Date()) + ".zip");
            try (java.util.zip.ZipOutputStream z =
                         new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(out))) {
                put(z, "session.log", readAll(logFile()));
                StringBuilder dev = new StringBuilder();
                dev.append("时间: ").append(new Date()).append('\n');
                dev.append("机型: ").append(android.os.Build.MANUFACTURER).append(' ')
                        .append(android.os.Build.MODEL).append(" / Android ")
                        .append(android.os.Build.VERSION.RELEASE).append('\n');
                dev.append("蓝牙名/地址: ").append(BtIdentity.peek(this).mac).append('\n');
                dev.append("车机识别: ").append(CarPlaySdpBeacon.statusLine()).append('\n');
                dev.append("广播版本 srcvers: ").append(CarPlayCtrlCompat.srcvers(this)).append('\n');
                dev.append("画质自动适配: ").append(autoFit).append(" / 车机画布 ")
                        .append(carW).append('x').append(carH).append('@').append(carFps).append('\n');
                dev.append("自动重连: ").append(autoReconnectEnabled()).append('\n');
                if (sessionTimeline.length() > 0) dev.append("本轮阶段耗时: ").append(sessionTimeline).append('\n');
                dev.append("静默异常计数: ").append(com.carbridge.carplay.util.Diag.dump()).append('\n');
                put(z, "device.txt", dev.toString());
            }
            log("诊断包已导出：" + out.getAbsolutePath());
            toastShort("已导出：" + out.getName());
        } catch (Throwable t) {
            log("✗ 诊断包导出失败：" + t);
            toastShort("导出失败：" + t.getMessage());
        }
    }

    private static void put(java.util.zip.ZipOutputStream z, String name, String content)
            throws java.io.IOException {
        z.putNextEntry(new java.util.zip.ZipEntry(name));
        z.write(content == null ? new byte[0] : content.getBytes("UTF-8"));
        z.closeEntry();
    }

    private static String readAll(java.io.File f) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[(int) Math.min(f.length(), 1_000_000)];
            int n = in.read(b);
            return new String(b, 0, Math.max(n, 0), "UTF-8");
        } catch (Throwable t) {
            return "(读取失败 " + t + ")";
        }
    }

    /** 记下这台车机的参数（按车机 IP 归档）。 */
    private void rememberCarProfile(String host) {
        try {
            com.carbridge.carplay.CarProfileStore.CarProfile p =
                    new com.carbridge.carplay.CarProfileStore.CarProfile();
            p.srcvers = CarPlayCtrlCompat.srcvers(this);
            p.autoFit = autoFit;
            p.targetW = carW;
            p.targetH = carH;
            p.targetFps = carFps;
            p.lastSeenAt = System.currentTimeMillis();
            com.carbridge.carplay.CarProfileStore.save(this, host, p);
            log("已记录车机配置：" + host + " · srcvers=" + p.srcvers
                    + " · 画布 " + carW + "x" + carH + "@" + carFps);
        } catch (Throwable t) {
            com.carbridge.carplay.util.Diag.w("CarProfile", t);
        }
    }

    /**
     * 全局未捕获异常落到会话日志里。
     *
     * <p>以前线程里抛异常 = App 直接消失，现场只有一句「闪退」，日志里什么都没有。
     */
    private void installCrashLogger() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    java.io.StringWriter sw = new java.io.StringWriter();
                    e.printStackTrace(new java.io.PrintWriter(sw));
                    log("✗ 未捕获异常 [" + t.getName() + "] " + e + "\n" + sw);
                } catch (Throwable tDiag) {
                    com.carbridge.carplay.util.Diag.w("MainActivity", tDiag);
                }
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
    }

    private void toastShort(String msg) {
        try {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        } catch (Throwable tDiag) {
            com.carbridge.carplay.util.Diag.w("MainActivity", tDiag);
        }
    }

    // ------------------------------------------------------------ 本机身份
    private TextView idBtName, idBtMac, idSdp, idSrcvers;
    private View identityHint;
    private IdentitySection identitySection;

    private IdentitySection identity() {
        if (identitySection == null) {
            identitySection = new IdentitySection(
                    new IdentitySection.Host() {
                        @Override public android.app.Activity activity() { return MainActivity.this; }
                        @Override public void log(String line) { MainActivity.this.log(line); }
                        @Override public int color(int colorRes) { return getColorCompat(colorRes); }
                    },
                    idBtName, idBtMac, idSdp, idSrcvers, identityHint);
        }
        return identitySection;
    }

    private void refreshIdentity() {
        identity().refresh();
    }

    private void kickIdentityProbe() {
        identity().probeInBackground();
    }

    /** 蓝牙地址：手填 / 重新读取 / 清除。写不进去时抖出来，不静默吞掉。 */
    private void showMacDialog() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("A4:50:46:1C:2D:3E");
        input.setTextSize(16);
        BtIdentity.Resolved cur = BtIdentity.peek(this);
        if (cur.source == BtIdentity.Source.MANUAL && cur.mac != null) {
            input.setText(cur.mac);
            input.setSelection(input.getText().length());
        }
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        wrap.setPadding(pad, dp(4), pad, 0);
        TextView tip = new TextView(this);
        tip.setText(R.string.mac_dialog_msg);
        tip.setTextSize(12);
        tip.setTextColor(getColorCompat(R.color.text_muted));
        tip.setLineSpacing(dp(2), 1f);
        wrap.addView(tip);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        wrap.addView(input, lp);

        new AlertDialog.Builder(this)
                .setTitle(R.string.mac_dialog_title)
                .setView(wrap)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String raw = input.getText().toString().trim();
                        // 空输入不等于「成功」：没填过就提示，填过才算清除。
                        if (raw.isEmpty()) {
                            if (BtIdentity.manualMac(MainActivity.this) == null) {
                                Toast.makeText(MainActivity.this, R.string.mac_empty, Toast.LENGTH_SHORT).show();
                                return;
                            }
                            BtIdentity.setManualMac(MainActivity.this, null);
                            log("已清除手动填写的蓝牙地址");
                            Toast.makeText(MainActivity.this, R.string.mac_cleared, Toast.LENGTH_SHORT).show();
                            refreshIdentity();
                            return;
                        }
                        if (!BtIdentity.setManualMac(MainActivity.this, raw)) {
                            Toast.makeText(MainActivity.this, R.string.mac_invalid, Toast.LENGTH_LONG).show();
                            return;
                        }
                        String saved = BtIdentity.peek(MainActivity.this).mac;
                        log("本机蓝牙地址已保存：" + saved + "（下次连接生效）");
                        Toast.makeText(MainActivity.this,
                                getString(R.string.mac_saved, saved), Toast.LENGTH_SHORT).show();
                        refreshIdentity();
                    }
                })
                .setNeutralButton(R.string.mac_dialog_read, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        BtIdentity.Resolved r = BtIdentity.resolve(MainActivity.this, true);
                        BtIdentity.remember(MainActivity.this, r);
                        log("重新读取本机蓝牙地址：" + r.describe());
                        refreshIdentity();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 广播版本（mDNS TXT 的 srcvers）：预设三档 + 自定义，改完下次连接生效。 */
    private void showSrcversDialog() {
        final java.util.List<String> values = new java.util.ArrayList<String>();
        final java.util.List<String> labels = new java.util.ArrayList<String>();
        String current = CarPlayCtrlCompat.srcvers(this);
        for (String preset : CarPlayCtrlCompat.PRESETS) {
            values.add(preset);
            String text = CarPlayCtrlCompat.presetLabel(this, preset);
            // ⚠️ 不要用 setMessage 显示当前值：AlertDialog 同时有 message 和 items 时，
            //    items 列表会被挤掉（真机实测：弹窗里只剩标题 + 一段空白）。
            labels.add(preset.equals(current) ? "✓ " + text : "    " + text);
        }
        values.add(null);
        labels.add(getString(R.string.srcvers_custom));

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.srcvers_dialog_title) + " · " + current)
                .setItems(labels.toArray(new CharSequence[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String v = values.get(which);
                        if (v == null) {
                            showSrcversCustomDialog();
                        } else {
                            applySrcvers(v);
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showSrcversCustomDialog() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("950.7.1");
        input.setTextSize(16);
        input.setText(CarPlayCtrlCompat.srcvers(this));
        new AlertDialog.Builder(this)
                .setTitle(R.string.srcvers_custom_title)
                .setMessage(R.string.srcvers_custom_hint)
                .setView(input)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        applySrcvers(input.getText().toString().trim());
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 写入广播版本：校验 → 落盘 → 同步到 RTSP UA / SETUP sourceVersion。 */
    private void applySrcvers(String value) {
        if (!CarPlayCtrlCompat.isValidValue(value)) {
            Toast.makeText(this, R.string.srcvers_invalid, Toast.LENGTH_LONG).show();
            return;
        }
        CarPlayCtrlCompat.setValue(this, value);
        CarPlayCtrlCompat.apply(this);
        log("广播版本 srcvers = " + value + "（下次连接生效）");
        refreshIdentity();
    }

    private void scheduleLogUiRefresh() {
        if (!logUiPending.compareAndSet(false, true)) {
            return;
        }
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                logUiPending.set(false);
                renderLogTail();
            }
        }, 200);
    }
    /** 只渲染尾部 LOG_UI_TAIL 行。 */
    private void renderLogTail() {
        logView.setText(sessionLog().tail(SessionLog.UI_TAIL));
        if (logOpen) {
            logScroll.post(new Runnable() {
                @Override
                public void run() {
                    logScroll.fullScroll(ScrollView.FOCUS_DOWN);
                }
            });
        }
    }
    /** 日志文件句柄的锁：多条业务线程都会写日志，FileOutputStream 不是线程安全的。 */
    private final Object logFileLock = new Object();
    private void appendLogFile(String entry) {
        synchronized (logFileLock) {
            try {
                if (logFileOut == null && !logFileTried) {
                    logFileTried = true;
                    File f = logFile();
                    if (f.isFile() && f.length() > LOG_FILE_MAX) {
                        truncateLogFileHead(f);
                    }
                    logFileOut = new FileOutputStream(f, true);
                }
                if (logFileOut != null) {
                    logFileOut.write((entry + "\n").getBytes("UTF-8"));
                    logFileOut.flush();
                }
            } catch (Throwable t) {
                // 日志管线绝不能反过来弄挂业务线程。
                // 但**不能完全静默**：日志文件写不动 = 现场导出的是残缺日志，
                // 排查会走弯路。这里只打一次 Log（不进会话日志，避免递归）。
                if (!logFileWarned) {
                    logFileWarned = true;
                    android.util.Log.w("carbridge", "写会话日志文件失败: " + t);
                }
            }
        }
    }
    /** 日志文件写入失败只提示一次（避免刷屏 + 递归）。 */
    private boolean logFileWarned;
    /** 关掉日志文件句柄（Activity 销毁时调用，否则每次重建都漏一个 fd）。 */
    private void closeLogFile() {
        if (sessionLog != null) sessionLog.close();
    }
        /**
     * 崩溃取证：把任何线程的未捕获异常栈直接写进会话日志。
     */
    private void installCrashProbe() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    File f = logFile();
                    FileOutputStream o = new FileOutputStream(f, true);
                    o.write(("!!!! 未捕获异常，进程即将终止 thread=" + t.getName()
                            + " !!!!\n").getBytes("UTF-8"));
                    java.io.StringWriter sw = new java.io.StringWriter();
                    e.printStackTrace(new java.io.PrintWriter(sw));
                    o.write(sw.toString().getBytes("UTF-8"));
                    o.write("\n".getBytes("UTF-8"));
                    o.flush();
                    o.close();
                } catch (Throwable tDiag) { com.carbridge.carplay.util.Diag.w("MainActivity", tDiag);
                    // 连崩溃日志都写不了就认了，绝不能二次抛
                }
                if (prev != null) {
                    prev.uncaughtException(t, e);
                }
            }
        });
    }
    private File logFile() {
        return sessionLog().file();
    }
    private void truncateLogFileHead(File f) {
        try {
            int keep = (int) (LOG_FILE_MAX / 2);
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r");
            byte[] buf = new byte[keep];
            raf.seek(f.length() - keep);
            int n = raf.read(buf);
            raf.close();
            FileOutputStream o = new FileOutputStream(f);
            o.write("...（早期日志已裁剪）\n".getBytes("UTF-8"));
            if (n > 0) {
                o.write(buf, 0, n);
            }
            o.close();
        } catch (Throwable tDiag) { com.carbridge.carplay.util.Diag.w("MainActivity", tDiag);
        }
    }
    /** 分享/复制用：读文件尾部（含历史轮次），文件不在才退回内存。 */
    private String logTailForShare() {
        try {
            File f = logFile();
            if (f.isFile()) {
                int cap = 400 * 1024;
                java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r");
                long len = raf.length();
                int n = (int) Math.min(len, cap);
                byte[] buf = new byte[n];
                raf.seek(len - n);
                raf.readFully(buf);
                raf.close();
                return new String(buf, "UTF-8");
            }
        } catch (Throwable tDiag) { com.carbridge.carplay.util.Diag.w("MainActivity", tDiag);
        }
        synchronized (logLines) {
            return join(logLines);
        }
    }
    private static String join(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            sb.append(l).append('\n');
        }
        return sb.toString();
    }
    private void setupLogCollapse() {
        if (logHeader == null || logBody == null) return;
        logHeader.setText(R.string.log_title);
        setChevron(logHeader, false);
        logHeader.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setLogOpen(!logOpen);
            }
        });
    }
    private void setLogOpen(boolean open) {
        logOpen = open;
        if (logBody != null) {
            logBody.setVisibility(open ? View.VISIBLE : View.GONE);
        }
        if (logHeader != null) {
            setChevron(logHeader, open);
        }
    }
    private void copyLog() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("carbridge", logTailForShare()));
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            log("复制失败：" + t);
        }
    }
    private void shareLog() {
        try {
            // 文件不在（极端情况）就先把内存 buffer 落一份，保证分享出去的一定有内容
            if (!logFile().isFile()) {
                synchronized (logLines) {
                    appendLogFile(join(logLines));
                }
            }
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_STREAM, LogProvider.uriFor(LOG_FILE_NAME));
            i.putExtra(Intent.EXTRA_SUBJECT, "carbridge 会话日志");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, getString(R.string.btn_share_log)));
        } catch (Throwable t) {
            log("导出失败：" + t);
        }
    }
    // ------------------------------------------------------------- CarPlayBridge.Sink
    // 注意：onLog(String) 与 HuClient.Sink 同签名，由已有的 log() 实现统一满足两个接口。
    @Override
    public void onState(String state) {
        log(state);
    }
    @Override
    public void onCarPlayStreaming() {
        // 画面已开始往车机推 → 推进 CarPlay 侧终态步（否则「推流中」永远待开始）
        CarPlayWirelessTx tx = carPlayTx;
        if (tx != null) {
            tx.markStreaming();
        }
    }
    @Override
    public void onCarPlayDisplay(final int width, final int height, final int maxFps) {
        // /info 在 AirPlay 会话线程回调，切回主线程更新 UI / 共享状态。
        main.post(new Runnable() {
            @Override
            public void run() {
                carW = width;
                carH = height;
                carFps = maxFps;
                getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                        .putInt(KEY_CAR_W, width)
                        .putInt(KEY_CAR_H, height)
                        .putInt(KEY_CAR_FPS, maxFps)
                        .apply();
                log("车机画布已记录：" + width + "×" + height + "@" + maxFps
                        + (autoFit ? "（自动适配开启）" : "（自动适配关闭）"));
                updateQualityTargetView();
                HuClient c = client;
                if (c != null) {
                    c.setAutoFit(autoFit);
                    c.onCarDisplay(width, height, maxFps);
                }
            }
        });
    }
    @Override
    public void onAirPlayFailed(final String reason) {
        // AirPlay 会话挂了 → 让无线侧回到「发现」阶段重找端点（否则永远卡在 AIRPLAY）
        log("CarPlay 会话失败：" + reason);
        // ⚠️ 本回调来自 AirPlay 会话线程（有时是 hu-video 线程）——UI 必须切回主线程。
        //    以前这里直接 renderSteps()，会抛 CalledFromWrongThreadException 把进程打死。
        main.post(new Runnable() {
            @Override
            public void run() {
                carPlayFailedStep = CarPlayWirelessTx.Step.AIRPLAY_SESSION;
                carPlayFailedReason = reason;
                renderSteps();
            }
        });
        CarPlayWirelessTx tx = carPlayTx;
        if (tx != null) {
            tx.onAirPlaySessionFailed(reason);
        }
    }
    @Override
    public void onCarHardKey(int carLifeKeycode) {
        // 车机硬键（方向盘/中控）→ CarLife TOUCH 通道 → 手机 Car+ 注入按键事件
        if (client == null) {
            log("车机硬键：CarLife 通道还没连上，丢弃");
            return;
        }
        client.sendHardKey(carLifeKeycode);
    }
    @Override
    public void onCarPlayTouch(int action, int x, int y) {        // 车机触摸 → CarLife CTRL 通道 → 手机 Car+
        //
        // ⚠️ 这里**只发 0x00068001 触摸序列**。曾经在 DOWN 时附带发过高层单击指令
        // 0x00068005 做试探——结果 Car+ 的 AccessibilityService 同时只允许一个手势，
        // 单击先占了派发权，紧随其后的 DOWN/MOVE/UP 被顶掉，
        // 系统日志成串 "Asynchronous input event injection failed"，滑动全部失效。
        // 触摸原本是好的，撤掉后即恢复。
        if (client != null) {
            client.sendTouch(action, x, y);
        }
    }
    // ------------------------------------------------------------- 运行时权限
    /**
     * targetSdk 35 在 Android 15+ 强制 edge-to-edge，内容会顶到状态栏下面。
     * 给内容视图加上系统栏高度的 padding 修掉。
     */
    @SuppressWarnings("deprecation")   // minSdk 26：getInsets(Type) 要 API 30
    private void applyWindowInsets() {
        final android.view.View content = findViewById(android.R.id.content);
        if (content == null) return;
        content.setOnApplyWindowInsetsListener(new android.view.View.OnApplyWindowInsetsListener() {
            @Override
            public android.view.WindowInsets onApplyWindowInsets(android.view.View v,
                                                                 android.view.WindowInsets insets) {
                int top;
                int bottom;
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets bars =
                            insets.getInsets(android.view.WindowInsets.Type.systemBars());
                    top = bars.top;
                    bottom = bars.bottom;
                } else {
                    top = insets.getSystemWindowInsetTop();
                    bottom = insets.getSystemWindowInsetBottom();
                }
                v.setPadding(0, top, 0, bottom);
                return insets;
            }
        });
        content.requestApplyInsets();
    }
    /**
     * 申请蓝牙/WiFi 运行时权限。
     *
     * API 31+：BLUETOOTH_CONNECT / BLUETOOTH_SCAN 是运行时权限，
     * 不申请的话 listenUsingRfcommWithServiceRecord 会直接 SecurityException。
     * API 33+：加 NEARBY_WIFI_DEVICES（入车机热点用）。
     */
    private void requestRuntimePermissions() {
        List<String> want = new ArrayList<String>();
        if (Build.VERSION.SDK_INT >= 31) {
            want.add("android.permission.BLUETOOTH_CONNECT");
            want.add("android.permission.BLUETOOTH_SCAN");
        }
        if (Build.VERSION.SDK_INT >= 33) {
            want.add("android.permission.NEARBY_WIFI_DEVICES");
            // 前台服务的常驻通知要显示，必须拿这个
            want.add("android.permission.POST_NOTIFICATIONS");
        }
        // 注：ACCESS_FINE_LOCATION 只在 manifest 声明、不主动申请——
        // 申请会弹权限框挡住主界面；WifiJoiner 已有「拿不到 SSID 就按已在 WiFi 上复用」的兜底。
        // 用户若手动在设置里授予，SSID 匹配会更精确。
        List<String> missing = new ArrayList<String>();
        for (String p : want) {
            if (checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                missing.add(p);
            }
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), 1001);
            log("申请权限：" + missing);
        }
    }
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != 1001) return;
        for (int i = 0; i < permissions.length; i++) {
            boolean ok = i < grantResults.length
                    && grantResults[i] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            log((ok ? "✓ 已授权 " : "✗ 被拒绝 ") + permissions[i].replace("android.permission.", ""));
        }
        // BLUETOOTH_CONNECT 刚拿到时 onCreate 那次注册会失败，这里补一次
        startCarPlayBeacon();
    }
        /**
     * 挂上 CarPlay 的 SDP 信标。
     */
    private void startCarPlayBeacon() {
        SdpBeaconService.start(this);
        registerBondWatcher();
        startBondPoll();
        if (CarPlaySdpBeacon.isStarted()) {
            log("✓ CarPlay SDP 信标已在运行（开机自启 / 前台服务常驻）");
            return;
        }
        android.bluetooth.BluetoothManager bm =
                (android.bluetooth.BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        android.bluetooth.BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
        CarPlaySdpBeacon.start(adapter, new CarPlaySdpBeacon.Log() {
            @Override public void onLog(String line) {
                log(line);
            }
        });
    }
        /**
     * 盯住配对状态变化。
     */
    private void registerBondWatcher() {
        if (bondReceiver != null) return;
        bondReceiver = new android.content.BroadcastReceiver() {
            @Override
            @SuppressWarnings("deprecation")   // minSdk 26：带 Class 的重载要 API 33
            public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                android.bluetooth.BluetoothDevice dev = intent.getParcelableExtra(
                        android.bluetooth.BluetoothDevice.EXTRA_DEVICE);
                int state = intent.getIntExtra(
                        android.bluetooth.BluetoothDevice.EXTRA_BOND_STATE, -1);
                int prev = intent.getIntExtra(
                        android.bluetooth.BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1);
                String name;
                String addr;
                try {
                    name = dev == null ? "?" : String.valueOf(dev.getName());
                    addr = dev == null ? "?" : dev.getAddress();
                } catch (Throwable t) {
                    name = "?";
                    addr = "?";
                }
                boolean isCar = false;
                try {
                    int cod = dev == null || dev.getBluetoothClass() == null
                            ? 0 : dev.getBluetoothClass().getDeviceClass();
                    isCar = ((cod >> 8) & 0x1F) == 0x04 && ((cod >> 2) & 0x3F) == 0x02;
                } catch (Throwable tDiag) { com.carbridge.carplay.util.Diag.w("MainActivity", tDiag);
                }
                String who = (isCar ? "车机" : "蓝牙设备") + " " + name + " [" + addr + "]";
                if (state == android.bluetooth.BluetoothDevice.BOND_BONDING) {
                    log("蓝牙配对中：" + who
                            + "（此刻信标" + (CarPlaySdpBeacon.isStarted() ? "已在" : "**没在**")
                            + "跑；车机会在这次配对里对 SDP）");
                } else if (state == android.bluetooth.BluetoothDevice.BOND_BONDED) {
                    boolean beacon = CarPlaySdpBeacon.isStarted();
                    log("✓ 蓝牙配对成功：" + who
                            + "（" + describeBond(prev) + " → BONDED）");
                    if (beacon) {
                        log("  信标在配对时是挂着的 → 车机已把 "
                                + CarProfile.APPLE_DEVICE_EIR_UUID + " + "
                                + CarProfile.APPLE_IAP2_UUID + " 记进它的 SDP 缓存");
                        log("  下一步：车机桌面点「Apple CarPlay」（灰色也能点），等它回连本机");
                    } else {
                        log("  ✗ 但配对时信标**没在跑** → 车机这次很可能没学到那两条 UUID！");
                        log("  处理：车机蓝牙里删掉本机 → App 里点「重新配对车机」→ 再配一次");
                    }
                } else if (state == android.bluetooth.BluetoothDevice.BOND_NONE) {
                    log("蓝牙配对已解除：" + who + "（" + describeBond(prev) + " → BOND_NONE）");
                }
            }
        };
        android.content.IntentFilter f = new android.content.IntentFilter(
                android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(bondReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(bondReceiver, f);
            }
        } catch (Throwable t) {
            bondReceiver = null;
            log("配对状态监听注册失败: " + t);
        }
    }
    private static String describeBond(int state) {
        switch (state) {
            case android.bluetooth.BluetoothDevice.BOND_BONDED: return "BONDED";
            case android.bluetooth.BluetoothDevice.BOND_BONDING: return "BONDING";
            case android.bluetooth.BluetoothDevice.BOND_NONE: return "NONE";
            default: return "state=" + state;
        }
    }
        /**
     * 轮询已配对列表，补上系统配对广播漏报的情况。
     */
    private void startBondPoll() {
        if (bondPolling) return;
        bondPolling = true;
        lastBondedCarAddress = currentBondedCarAddress();
        if (lastBondedCarAddress != null) {
            log("车机已在配对列表里；上车后到车机桌面点「Apple CarPlay」");
        }
        bondPoll.postDelayed(bondPollTask, 1500);
    }

    private final Runnable bondPollTask = new Runnable() {
        @Override
        public void run() {
            if (!bondPolling) return;
            checkBondedCar();
            // 每 10 秒顺手体检一次信标：Activity 活着就绝不让 SDP 记录消失。
            // 服务被杀 / 被系统重启的空档，这里是第二道保险。
            if (++beaconTick >= 5) {
                beaconTick = 0;
                keepBeaconAlive("轮询");
            }
            bondPoll.postDelayed(this, 2000);
        }
    };

    /** 信标体检（幂等）：健康时只做一次内存判断，不产生日志。 */
    private void keepBeaconAlive(String from) {
        try {
            android.bluetooth.BluetoothManager bm =
                    (android.bluetooth.BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            android.bluetooth.BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
            if (!CarPlaySdpBeacon.reviveIfDead(adapter, new CarPlaySdpBeacon.Log() {
                @Override public void onLog(String line) {
                    log(line);
                }
            })) {
                log("⚠ SDP 信标未能挂上（" + from + "），车机会认不出本机");
            }
        } catch (Throwable tDiag) { com.carbridge.carplay.util.Diag.w("MainActivity", tDiag);
        }
    }

    private String currentBondedCarAddress() {
        try {
            BluetoothDevice car = CarPairing.findCar();
            return car == null ? null : car.getAddress();
        } catch (Throwable t) {
            return null;
        }
    }

    private void checkBondedCar() {
        try {
            BluetoothDevice car = CarPairing.findCar();
            if (car == null) {
                lastBondedCarAddress = null;
                return;
            }
            String addr = car.getAddress();
            if (addr.equals(lastBondedCarAddress)) {
                return;
            }
            lastBondedCarAddress = addr;
            String name;
            try {
                name = String.valueOf(car.getName());
            } catch (Throwable t) {
                name = "车机";
            }
            log("✓ 已和车机配对：" + name + " [" + addr + "]");
            log("  下一步：到车机桌面点「Apple CarPlay」图标（车机不会自己弹出来）");
            // 不管 CarPlay 开关处于什么状态都要提示：配对成功后的一步在车机屏幕上，
            // 用户可能已经把手机放下，只盯着车机等它「自动弹出来」。
            showCarPairedNotification(name);
        } catch (Throwable tDiag) { com.carbridge.carplay.util.Diag.w("MainActivity", tDiag);
        }
    }

    /** 配对成功时弹一条通知：用户可能已经把手机放在支架上，不一定会看 App。 */
    private void showCarPairedNotification(String name) {
        try {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            String chId = "carplay_pair";
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(chId) == null) {
                NotificationChannel ch = new NotificationChannel(chId,
                        "车机配对提示", NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("车机配对成功后提示下一步");
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            Intent open = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(this, chId)
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .setContentTitle("车机已配对" + (name == null ? "" : "：" + name))
                    .setContentText("现在到车机桌面点「Apple CarPlay」")
                    .setStyle(new Notification.BigTextStyle().bigText(
                            "车机已配对。现在到车机桌面点「Apple CarPlay」图标——"
                                    + "这个入口不会自己弹出来，必须手动点一次。"))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            nm.notify(2001, n);
        } catch (Throwable tDiag) { com.carbridge.carplay.util.Diag.w("MainActivity", tDiag);
        }
    }
    // ------------------------------------------------------------- 后台保活
    /** 蓝牙诊断：把「车机到底看见了什么」写进日志，导出给我看就行。 */
    private void runBtDiag() {
        log("── 蓝牙诊断开始 ──");
        BtDiag.run(this, new BtDiag.Log() {
            @Override
            public void onLog(String line) {
                log(line);
            }
        });
    }

    /**
     * 连接自检：把「车机能否认出本机、会不会被系统杀」一次性查清。
     *
     * <p>真 iPhone 的 SDP 是系统级常驻，我们只能 app 级常驻，所以把
     * 所有「能不能常驻」的因素（蓝牙 / 信标 / 省电白名单 / 通知 / 前台服务）都查一遍，
     * 缺哪个一眼就知道，不用盲等。
     */
    private void runConnectionSelfCheck() {
        log("—— 连接自检 ——");
        // ① 蓝牙 + 信标 + SDP 就绪（2d8d2466 是否在广播）
        android.bluetooth.BluetoothAdapter adapter =
                com.carbridge.carplay.bt.Bt.adapter(this);
        String sdp = com.carbridge.carplay.bt.CarPlaySdpBeacon.diagnose(adapter);
        for (String line : sdp.split("\n")) log(line);

        // ② 电池优化白名单（是否会被 Doze/省电冻结）
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                log("✓ 省电白名单：已免省电");
            } else {
                log("✗ 省电白名单：未豁免，锁屏可能被省电冻结 → 点「后台运行设置」授权");
            }
        } catch (Throwable t) {
            log("省电白名单检查失败：" + t);
        }

        // ③ 通知权限（前台服务常驻通知能不能显示）
        try {
            boolean notif = android.os.Build.VERSION.SDK_INT < 33
                    || ((android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE))
                            .areNotificationsEnabled();
            log(notif ? "✓ 通知权限：已允许" : "✗ 通知权限：被关（前台服务通知不显示，可能被系统判为可疑）");
        } catch (Throwable t) {
            log("通知权限检查失败：" + t);
        }

        // ④ 前台服务是否在跑（进程被杀后能不能自己爬回来）
        boolean beaconRunning = com.carbridge.carplay.bt.CarPlaySdpBeacon.isStarted();
        log(beaconRunning ? "✓ 信标前台服务：在跑" : "✗ 信标前台服务：没跑");

        // ⑤ 精确闹钟权限（看门狗在 Doze/省电下准时醒的关键，Android 12+ 默认拒绝）
        try {
            boolean exact = android.os.Build.VERSION.SDK_INT < 31
                    || ((android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE))
                            .canScheduleExactAlarms();
            log(exact ? "✓ 精确闹钟权限：已允许（看门狗准时醒）"
                    : "✗ 精确闹钟权限：未允许（省电模式下看门狗可能延迟几分钟才醒），点「自启动设置」开「闹钟和提醒」");
        } catch (Throwable t) {
            log("精确闹钟权限检查失败：" + t);
        }

        log("—— 自检结束 ——（缺项按上面提示补，或点「后台运行设置」「自启动设置」）");
        // 结果都在运行日志里；日志默认折叠，主动展开 + Toast，避免「点了没反应」的错觉
        setLogOpen(true);
        Toast.makeText(this, R.string.selfcheck_done, Toast.LENGTH_SHORT).show();
    }

    /** 跳到「自启动」设置页（ColorOS 需要手动允许自启动，否则开机/被杀后不会自己爬起来）。 */
    private void openAutostartSettings() {
        // 先补「闹钟和提醒」权限（看门狗准时醒的关键，Android 12+ 默认拒绝）
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            try {
                android.app.AlarmManager am =
                        (android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE);
                if (am != null && !am.canScheduleExactAlarms()) {
                    Intent i = new Intent(
                            android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            android.net.Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                    log("已打开「闹钟和提醒」设置：请允许本 App 用闹钟（看门狗靠它准时醒）");
                    return;
                }
            } catch (Throwable t) {
                // 个别 ROM 没有这个 intent，落到下面的应用详情页
            }
        }
        try {
            // 各家 ROM 的自启动设置入口不同，先试通用「应用详情」，再试 ColorOS/小米专用
            Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + getPackageName()));
            startActivity(i);
            log("已打开应用详情：请在「耗电管理/自启动」里允许后台运行和自启动");
        } catch (Throwable t) {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
                log("打开应用详情失败（" + t + "），已打开系统设置，请手动找到本 App 允许自启动");
            } catch (Throwable t2) {
                log("打开设置失败：" + t2);
            }
        }
    }
    /** 命令行诊断入口：
     * {@code adb shell am start -n com.carbridge.app/.MainActivity --es diag uuid}
     * {@code adb shell am start -n com.carbridge.app/.MainActivity --es diag eir}
     *
     * <p>车机不在身边时没法对着它敲命令，所以留一个能远程触发的口子。
     */
    private boolean diagRunning;
    private void runStartupDiagnostics() {
        try {
            Intent in = getIntent();
            if (in == null) return;
            final Context appCtx = getApplicationContext();
            String diag = in.getStringExtra("diag");
            if (diag == null) return;
            // 一次进程只跑一遍：am start 可能同时走 onCreate 和 onNewIntent，
            // 不加锁会起两套扫描/诊断（EIR 扫描就会重复两倍）。
            if (diagRunning) return;
            diagRunning = true;
            if ("uuid".equals(diag)) {
                main.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        runBtDiag();
                    }
                }, 1500);
            } else if ("eir".equals(diag)) {
                // 只扫 EIR：车机热点信息（Apple Device IE）
                main.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        BtDiag.scanEirOnly(appCtx, new BtDiag.Log() {
                            @Override
                            public void onLog(String line) {
                                log(line);
                            }
                        });
                    }
                }, 1500);
            }
        } catch (Throwable tDiag) { com.carbridge.carplay.util.Diag.w("MainActivity", tDiag);
        }
    }
    // ------------------------------------------------------------- 页面切换
    /** 切换底部两页，并同步高亮标签。 */
    private void showPage(int index) {
        currentTab = index;
        // 两页共用同一个 ScrollView：切换时回到顶部，避免停在上一页的滚动位置
        if (mainScroll != null) {
            mainScroll.scrollTo(0, 0);
        }
        for (int i = 0; i < pages.length; i++) {
            if (pages[i] != null) {
                pages[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
            }
            if (tabs[i] != null) {
                int color = getColorCompat(i == index ? R.color.accent : R.color.text_muted);
                tabs[i].setTextColor(color);
                // 图标跟着文字一起变色（API 23+，minSdk 26 足够）
                tabs[i].setCompoundDrawableTintList(
                        android.content.res.ColorStateList.valueOf(color));
                tabs[i].setTypeface(null, i == index
                        ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            }
        }
    }
    /** 折叠卡片：点标题切换正文显示，右侧 chevron 跟随展开状态。 */
    private void setupCollapse(final int headerId, final int bodyId, final int titleRes) {
        final View header = findViewById(headerId);
        final View body = findViewById(bodyId);
        if (header == null || body == null) return;
        if (header instanceof TextView) {
            ((TextView) header).setText(titleRes);
            setChevron((TextView) header, false);
        }
        header.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean show = body.getVisibility() != View.VISIBLE;
                body.setVisibility(show ? View.VISIBLE : View.GONE);
                if (header instanceof TextView) {
                    setChevron((TextView) header, show);
                }
            }
        });
    }
    /** 右侧灰色 chevron：收起 ›，展开 ⌄（iOS 分组列表的导航指示）。 */
    private void setChevron(TextView tv, boolean expanded) {
        if (tv == null) return;
        tv.setCompoundDrawablesWithIntrinsicBounds(0, 0,
                expanded ? R.drawable.ic_chevron_down : R.drawable.ic_chevron_right, 0);
        tv.setCompoundDrawablePadding(dp(6));
        tv.setCompoundDrawableTintList(
                android.content.res.ColorStateList.valueOf(getColorCompat(R.color.text_muted)));
    }
    // ------------------------------------------------------------- 连接步骤
    /**
     * 建合并后的步骤清单：CarLife 8 步 + 分隔标题 + CarPlay 7 步，
     * 全部放进同一个容器，用 tag 区分属于哪一组。
     */
    private void buildStepRows() {
        boolean first = true;
        for (final HuClient.Step step : HuClient.Step.values()) {
            if (!first) addStepDivider();
            first = false;
            LinearLayout row = addStepRow(step.label);
            row.setTag(step);   // CarLife 步骤
        }
        addStepDivider();
        // 分隔标题（不是步骤，只是视觉分组）——按 Apple eyebrow 排：12sp 粗体 + 正字距
        TextView header = new TextView(this);
        header.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        header.setPadding(0, dp(16), 0, dp(4));
        header.setText(R.string.section_steps_carplay);
        header.setTextSize(12);
        header.setLetterSpacing(0.06f);
        header.setTypeface(null, android.graphics.Typeface.BOLD);
        header.setTextColor(getColorCompat(R.color.text_muted));
        stepsContainer.addView(header);
        // 给标题行也放一个占位 View，保证行内索引一致
        header.setTag("header");
        boolean firstCarPlay = true;
        for (final CarPlayWirelessTx.Step step : CarPlayWirelessTx.Step.values()) {
            if (!firstCarPlay) addStepDivider();
            firstCarPlay = false;
            LinearLayout row = addStepRow(step.label);
            row.setTag(step);   // CarPlay 步骤
        }
        renderSteps();
    }
    /** 步骤行之间的缩进分隔线（iOS grouped list 风格）。 */
    private void addStepDivider() {
        View d = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        lp.leftMargin = dp(22);   // 对齐图标后的文字，不贯穿整行
        d.setLayoutParams(lp);
        d.setBackgroundColor(getColorCompat(R.color.divider));
        stepsContainer.addView(d);
    }
    /** 建一行「图标 + 文字 + 状态」，返回该行。 */
    private LinearLayout addStepRow(String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));
        TextView icon = new TextView(this);
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(22), LinearLayout.LayoutParams.WRAP_CONTENT));
        icon.setTextSize(13);
        icon.setGravity(android.view.Gravity.CENTER);
        icon.setText("○");
        TextView labelView = new TextView(this);
        labelView.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        labelView.setText(label);
        labelView.setTextSize(14);
        labelView.setTextColor(getColorCompat(R.color.text_dim));
        TextView stateText = new TextView(this);
        stateText.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        stateText.setTextSize(12);
        stateText.setTextColor(getColorCompat(R.color.text_muted));
        stateText.setText(R.string.step_pending);
        row.addView(icon);
        row.addView(labelView);
        row.addView(stateText);
        stepsContainer.addView(row);
        return row;
    }
    /** 单条进度的渲染结果（图标 / 颜色 / 状态文字 / 是否算已完成）。 */
    private static final class RowState {
        final String mark;
        final int color;
        final String statusText;
        final boolean done;
        RowState(String mark, int color, String statusText, boolean done) {
            this.mark = mark;
            this.color = color;
            this.statusText = statusText;
            this.done = done;
        }
    }
    private void renderSteps() {
        int doneCount = 0;
        for (int i = 0; i < stepsContainer.getChildCount(); i++) {
            View child = stepsContainer.getChildAt(i);
            Object tag = child.getTag();
            // ⚠️ 分隔标题是 TextView，不是 LinearLayout——必须先判 tag 再转型
            if ("header".equals(tag) || !(child instanceof LinearLayout)) continue;
            LinearLayout row = (LinearLayout) child;
            RowState st;
            if (tag instanceof HuClient.Step) {
                st = carlifeRowState((HuClient.Step) tag);
            } else if (tag instanceof CarPlayWirelessTx.Step) {
                st = carplayRowState((CarPlayWirelessTx.Step) tag);
            } else {
                continue;
            }
            if (st.done) doneCount++;
            TextView icon = (TextView) row.getChildAt(0);
            TextView label = (TextView) row.getChildAt(1);
            TextView stateText = (TextView) row.getChildAt(2);
            icon.setText(st.mark);
            icon.setTextColor(st.color);
            label.setTextColor(st.done || "●".equals(st.mark)
                    ? getColorCompat(R.color.text) : getColorCompat(R.color.text_dim));
            // 完成的步骤不显示「完成」文字（✓ 图标已经表达了），减少视觉噪音
            if (st.done) {
                stateText.setText("");
            } else {
                stateText.setText(st.statusText);
                stateText.setTextColor(st.color);
            }
        }
        updateProgress(doneCount);
        updateDiagram();
    }
    /**
     * 单步显示状态：判定交给 {@link StepChain}（纯逻辑、有 13 条断言），这里只做「状态 → 文案/颜色」。
     *
     * <p>以前这段判定写在方法里，踩过两个 bug（失败回滚过头、终态不打勾），
     * 抽出去之后这类回归由单测挡住，而不是靠人肉看界面。
     */
    private RowState carlifeRowState(HuClient.Step step) {
        Integer cur = currentStep == null ? null : currentStep.ordinal();
        Integer fail = failedStep == null ? null : failedStep.ordinal();
        return rowState(StepChain.stateOf(step.ordinal(), cur, fail,
                HuClient.Step.values().length - 1));
    }

    private RowState carplayRowState(CarPlayWirelessTx.Step step) {
        Integer cur = carPlayStep == null ? null : carPlayStep.ordinal();
        Integer fail = carPlayFailedStep == null ? null : carPlayFailedStep.ordinal();
        return rowState(StepChain.stateOf(step.ordinal(), cur, fail,
                CarPlayWirelessTx.Step.values().length - 1));
    }

    /** 共用的「状态 → 行样式」映射。 */
    private RowState rowState(StepChain.State st) {
        switch (st) {
            case DONE:
                return new RowState("✓", getColorCompat(R.color.state_ok),
                        getString(R.string.step_done), true);
            case ACTIVE:
                return new RowState("●", getColorCompat(R.color.state_work),
                        getString(R.string.step_active), false);
            case FAILED:
                return new RowState("✕", getColorCompat(R.color.state_err),
                        getString(R.string.step_failed), false);
            default:
                return new RowState("○", getColorCompat(R.color.state_idle),
                        getString(R.string.step_pending), false);
        }
    }

    private void updateProgress(int doneCount) {
        int pct = (int) (doneCount * 100L / TOTAL_STEPS);
        stepsProgress.setProgress(pct);
        stepsProgressText.setText(doneCount + " / " + TOTAL_STEPS);
    }
    /** 更新流向图状态（由 CarLife/CarPlay 综合决定）。 */
    private void updateDiagram() {
        if (linkDiagram == null) return;
        LinkDiagramView.Phase p;
        if (currentStep == HuClient.Step.STREAMING
                && carPlayStep == CarPlayWirelessTx.Step.CARPLAY_STREAM) {
            p = LinkDiagramView.Phase.DONE;
        } else if (failedStep != null || carPlayFailedStep != null) {
            p = LinkDiagramView.Phase.ERROR;
        } else if (currentStep != null || carPlayStep != null) {
            p = LinkDiagramView.Phase.ACTIVE;
        } else {
            p = LinkDiagramView.Phase.IDLE;
        }
        linkDiagram.setPhase(p);
    }
    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
    /** 只重置 Car+ 投屏侧进度（CarPlay 鉴权/链路状态不动）。 */
    private void resetCarlifeSteps() {
        currentStep = null;
        failedStep = null;
        failedReason = "";
        fpsFrames = 0;
        fpsWindowStart = 0;
        fpsValue = 0;
        totalBytes = 0;
        firstFrameDelayMs = 0;
        renderSteps();
        updateMetrics();
        if (badgeView != null) {
            badgeView.setVisibility(android.view.View.GONE);
        }
        log("已重置 Car+ 投屏进度");
    }
    /** 只重置 CarPlay 侧进度（重新发起配对/鉴权时用）。 */
    private void resetCarPlaySteps() {
        carPlayStep = null;
        carPlayFailedStep = null;
        carPlayFailedReason = "";
        renderSteps();
    }
    private int getColorCompat(int resId) {
        return getResources().getColor(resId, getTheme());
    }
    // ------------------------------------------------------------- HuClient.Sink（步骤）
    @Override
    public void onStep(final HuClient.Step step) {
        main.post(new Runnable() {
            @Override
            public void run() {
                currentStep = step;
                failedStep = null;
                renderSteps();
                if (step == HuClient.Step.STREAMING) {
                    setBadge(R.string.badge_live, R.color.accent);
                    // 画面已经从手机流过来了 → 自动把 CarPlay 推给车机，用户不用再点
                    maybeAutoCarPlay();
                }
            }
        });
    }
        /**
     * CarLife 到达终态（手机画面已收到）→ 自动启动 CarPlay 推流。
     */
    private void maybeAutoCarPlay() {
        if (!autoCarplayArmed) return;
        if (carplayManualStop) {
            autoCarplayArmed = false;
            return;
        }
        if (carPlayTx != null && carPlayTx.state() != CarPlayWirelessTx.State.IDLE
                && carPlayTx.state() != CarPlayWirelessTx.State.ERROR) {
            autoCarplayArmed = false;
            return;
        }
        autoCarplayArmed = false;   // 先落闸再启动，避免启动过程中步骤回调重复触发
        log("Car+ 进度已走完 → 自动启动 CarPlay 推流");
        toggleCarPlay();
    }
    @Override
    public void onStepFailed(final HuClient.Step step, final String reason) {
        main.post(new Runnable() {
            @Override
            public void run() {
                failedStep = step;
                failedReason = reason;
                renderSteps();
                setBadge(R.string.step_failed, R.color.state_err);
            }
        });
    }
    /**
     * 主按钮的两种形态：未连接 = 实心 Action Blue（页面上唯一的饱和色块），
     * 已连接 = 同形状的描边（白底 + hairline），把视觉重心让给深色流向卡片。
     *
     * <p>形状不变（都是 9999 胶囊），只换填充与文字色，避免按钮在
     * 「开始连接 → 断开」之间变形。
     */
    private void setConnectButton(boolean running) {
        if (btnConnect == null) return;
        btnConnect.setText(running ? R.string.btn_disconnect : R.string.btn_connect);
        btnConnect.setTextColor(getColorCompat(R.color.accent));
    }
    /**
     * 状态徽标：底色跟着状态走。
     *
     * <p>原来徽标底色固定蓝，失败时只把文字染红 —— 红字压蓝底，对比度只有 1.3:1，
     * 远看就是一团糊。现在 LIVE 蓝底白字、失败红底白字。
     */
    private void setBadge(int textRes, int bgColor) {
        if (badgeView == null) return;
        badgeView.setText(textRes);
        badgeView.setTextColor(getColorCompat(R.color.on_accent));
        badgeView.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(getColorCompat(bgColor)));
        badgeView.setVisibility(View.VISIBLE);
    }
    // ------------------------------------------------------------- 指标（FPS / 码率）
    /** 每帧调用：累计字节，按 1 秒窗口算瞬时帧率。 */
    private void trackMetrics(long bytes) {
        totalBytes += bytes;
        fpsFrames++;
        long now = SystemClock.elapsedRealtime();
        if (fpsWindowStart == 0) {
            fpsWindowStart = now;
        } else if (now - fpsWindowStart >= 1000) {
            fpsValue = fpsFrames * 1000f / (now - fpsWindowStart);
            fpsFrames = 0;
            fpsWindowStart = now;
            // ⚠️ 本方法由 hu-video 线程调用，必须切回主线程再碰 View
            main.post(new Runnable() {
                @Override
                public void run() {
                    updateMetrics();
                }
            });
        }
    }
    private void updateMetrics() {
        if (metricFps == null) return;
        // 所有格式化都交给 MetricsText（纯函数、有单测），这里只负责填控件
        String fpsText = MetricsText.fps(fpsValue);
        String ph = getString(R.string.metric_placeholder);
        metricFps.setText(fpsText == null ? ph : fpsText);

        int w = client != null ? (client.encodedWidth() > 0 ? client.encodedWidth() : client.streamWidth()) : 0;
        int h = client != null ? (client.encodedHeight() > 0 ? client.encodedHeight() : client.streamHeight()) : 0;
        String resText = MetricsText.resolution(w, h);
        metricResolution.setText(resText == null ? ph : resText);

        long frames = client == null ? 0 : client.frameCount();
        String mbpsText = MetricsText.mbpsText(totalBytes, frames, fpsValue);
        metricBitrate.setText(mbpsText == null ? ph : mbpsText);

        if (metricsView == null) return;
        String detail = MetricsText.detail(frames, totalBytes, firstFrameDelayMs);
        if (detail != null) {
            metricsView.setText(detail);
            metricsView.setVisibility(View.VISIBLE);
        } else {
            metricsView.setText("");
            metricsView.setVisibility(View.GONE);
        }
    }

    /** 用累计字节/帧数 × 瞬时帧率估码率（Mbps）。 */
    private float totalMbps() {
        return MetricsText.mbps(totalBytes, client == null ? 0 : client.frameCount(), fpsValue);
    }
    // ------------------------------------------------------------- CarPlay 无线推流
    private void toggleCarPlay() {
        if (carPlayTx != null && carPlayTx.state() != CarPlayWirelessTx.State.IDLE
                && carPlayTx.state() != CarPlayWirelessTx.State.ERROR) {
            carPlayTx.stop();
            carPlayTx = null;
            // AirPlay 会话也要停：否则停止配对后它还会挂在车机上
            if (carPlay != null) carPlay.disconnect();
            carplayManualStop = true;      // 用户主动停的 → 自动逻辑别再抢着起
            autoCarplayArmed = false;
            btnCarPlay.setText(R.string.btn_carplay_start);
            // 车机侧步骤也要清空：否则停了配对，诊断页还显示一片绿
            resetCarPlaySteps();
            log("已停止 CarPlay 配对/鉴权");
            return;
        }
        // ⚠️ 上一轮是 ERROR 状态时，上面那个分支不接管 → 这里必须**先 stop 再新建**。
        //    否则旧的 CarPlayWirelessTx 不会被释放：mDNS 注册、ctrl-int 监听端口、
        //    WifiJoiner 的网络回调、iAP2 会话、广播接收器全都泄漏，
        //    而且 `setProcessDefaultNetwork(null)` 不会执行 → 进程一直绑在车机热点上。
        if (carPlayTx != null) {
            log("上一轮 CarPlay 是 ERROR 状态，先释放再重开");
            carPlayTx.stop();
            carPlayTx = null;
            if (carPlay != null) carPlay.disconnect();
        }
        carplayManualStop = false;
        resetCarPlaySteps();
        carPlayTx = new CarPlayWirelessTx(this, new CarPlayWirelessTx.Listener() {
            @Override public void onState(CarPlayWirelessTx.State s) {
                log("CarPlay 状态: " + s);
            }
            @Override public void onLog(String line) { log(line); }
            @Override public void onStep(final CarPlayWirelessTx.Step s) {
                main.post(new Runnable() {
                    @Override public void run() {
                        carPlayStep = s;
                        carPlayFailedStep = null;
                        renderSteps();
                    }
                });
            }
            @Override public void onStepFailed(final CarPlayWirelessTx.Step s, final String reason) {
                main.post(new Runnable() {
                    @Override public void run() {
                        carPlayFailedStep = s;
                        carPlayFailedReason = reason;
                        renderSteps();
                    }
                });
            }
            @Override public void onCarPlayEndpoint(String host, int port) {
                rememberCarProfile(host);
                // 拿到车机 AirPlay 端点 → 让 CarPlayBridge 建会话并开始推流
                CarPlayBridge b = carPlay;
                if (b == null) {
                    log("CarPlayBridge 尚未创建，先点「开始连接」再启动 CarPlay");
                    return;
                }
                log("★ 桥接到 AirPlay " + host + ":" + port);
                b.connect(host, port);
            }
        });
        btnCarPlay.setText(R.string.btn_carplay_stop);
        log("启动 CarPlay 配对/鉴权（等车机蓝牙回连）");
        carPlayTx.start();
    }
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // singleTask：从桌面/最近任务再次打开不会重建 Activity，
        // 这里只需处理命令行诊断参数；日常点击就是单纯把本页带回前台。
        runStartupDiagnostics();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 用户从桌面点开 App 的这一刻，最需要的是「车机随时能认出本机」。
        // 服务被杀 / 蓝牙重开的空档都靠这一下补齐（幂等，健康时不做事）。
        keepBeaconAlive("打开 App");
        refreshIdentity();
        kickIdentityProbe();
    }

    @Override
    protected void onDestroy() {
        bondPolling = false;
        bondPoll.removeCallbacksAndMessages(null);
        if (sAlive == this) {
            sAlive = null;
        }
        super.onDestroy();
        if (client != null) {
            client.stop();
        }
        if (carPlay != null) {
            carPlay.disconnect();
            carPlay = null;
        }
        if (carPlayTx != null) {
            carPlayTx.stop();
            carPlayTx = null;
        }
        if (bondReceiver != null) {
            try {
                unregisterReceiver(bondReceiver);
            } catch (Throwable tDiag) { com.carbridge.carplay.util.Diag.w("MainActivity", tDiag);
            }
            bondReceiver = null;
        }
        // 关掉日志文件句柄：Activity 每重建一次就漏一个 fd
        closeLogFile();
    }
}
