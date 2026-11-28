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
import com.carbridge.carplay.util.Diag;
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
    /** 配对扫描进行中（防连点并发扫描）。 */
    private volatile boolean pairScanning;
    private boolean userWantsConnected = true;   // 冷启动默认「想连」：3 秒自动连接靠它放行；手动断开置 false
    /** Activity 已销毁：所有延迟任务/异步回调都要据此放手（否则会重建会话、弹窗崩）。 */
    private volatile boolean destroyed;
    /** 启动 3 秒后的自动连接（手动断开时要能取消）。 */
    private Runnable autoConnectOnce;
    /** 已排队的自动重连（ERROR 与 CLOSED 会连着来，必须幂等）。 */
    private Runnable reconnectRunnable;
    private int retryStep;
    private TextView autoReconnectRow;

    private final List<String> logLines = new ArrayList<String>();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private TextView stateView, detailView, logView;
    /** 日志搜索：输入即过滤（空 = 显示尾部 250 行）。 */
    private android.widget.EditText logSearch;
    private TextView logSearchCount;
    private volatile String logQuery = "";
    private TextView btnConnect, logHeader;
    private TextView badgeView, metricFps, metricResolution, metricBitrate;
    private LinearLayout stepsContainer;
    private ScrollView logScroll;
    private ScrollView mainScroll;
    private View logBody;
    /** 「后台保活」卡片：状态文案 + 三个动作。 */
    private HuClient client;
    /** 跨线程：CarPlayBridge 收发线程会读 carPlay/carPlayTx（触摸/按键/端点回调），主线程写 → volatile。 */
    private volatile CarPlayBridge carPlay;
    private volatile CarPlayWirelessTx carPlayTx;
    private TextView btnCarPlay;
    private boolean logOpen;
    /**
     * Car+ 进度走完后是否还允许自动起 CarPlay 推流（用户要求「无感」：
     * 打开 app → 自动连 Car+ → 走完进度 → CarPlay 自己开始）。
     * 每次新连接重新武装一次；只自动拉起一次，避免重连时反复拉。
     */
    private boolean autoCarplayArmed = true;
    /** 用户手动点过「停止推流」→ 别自作主张再帮他拉起来。 */
    private boolean carplayManualStop;
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
    /** 一屏结论：首个断点（判定在 StepDiagnosis，有单测）。 */
    private TextView stepDiagnosisView;
    private String diagKey = "";
    private long diagChangedAt = 0L;
    /** 合并后的步骤行数（CarLife + 分隔标题 + CarPlay）。 */
    private static final int CARLIFE_STEPS = HuClient.Step.values().length;
    private static final int CARPLAY_STEPS = CarPlayWirelessTx.Step.values().length;
    private static final int TOTAL_STEPS = CARLIFE_STEPS + CARPLAY_STEPS;
    /** 连接流向图控件（未接线时保持 null）。 */
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
    /** 由视频线程写、主线程读（updateMetrics）→ 必须 volatile（同组其它字段都标了）。 */
    private volatile float fpsValue;
    /** Car+ 进入 STREAMING 的时刻（0=未在推流）：会话结束据此算「实测平均帧率」给自适应用。 */
    private volatile long streamingSinceMs;
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
            // ⚠️ finish() 是异步的：prev 的会话要等它的 onDestroy 才拆，而新实例
            //    100ms 后就起自己的 tx —— 窗口里两套 iAP2/mDNS 同时存在（时好时坏）。
            //    两个实例共享同一个主线程，直接同步拆（幂等），overlap 归零。
            prev.shutdownSessions();
            prev.finish();
        }
        // 自保护：签名钉死 / 反调试 / 反 hook。命中即 halt，所以放在最前面。
        installCrashHandlersOnce(getApplicationContext());
        com.carbridge.app.HuClient.verboseTrace =
                getSharedPreferences(PREF_NAME, MODE_PRIVATE).getBoolean(KEY_VERBOSE_TRACE, false);
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
        log("carbridge v" + appVersion() + " 启动，日志文件: " + logFile().getAbsolutePath());
        stateView = (TextView) findViewById(R.id.state);
        detailView = (TextView) findViewById(R.id.detail);
        logView = (TextView) findViewById(R.id.log);
        logSearch = (android.widget.EditText) findViewById(R.id.log_search);
        logSearchCount = (TextView) findViewById(R.id.log_search_count);
        if (logSearch != null) {
            logSearch.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
                @Override public void afterTextChanged(android.text.Editable s) {
                    logQuery = s == null ? "" : s.toString().trim();
                    renderLogTail();
                }
            });
        }
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
        stepDiagnosisView = (TextView) findViewById(R.id.step_diagnosis);
        elapsedView = (TextView) findViewById(R.id.elapsed);
        idBtName = (TextView) findViewById(R.id.id_bt_name);
        idBtMac = (TextView) findViewById(R.id.id_bt_mac);
        idSdp = (TextView) findViewById(R.id.id_sdp);
        idSrcvers = (TextView) findViewById(R.id.id_srcvers);
        idIdentityName = (TextView) findViewById(R.id.id_identity_name);
        View rowIdentityName = findViewById(R.id.row_identity_name);
        if (rowIdentityName != null) {
            rowIdentityName.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showIdentityNameDialog(); }
            });
        }
        // 启动时把「同步系统蓝牙名」的意图落地（开关关着就什么都不做）
        try {
            if (com.carbridge.carplay.IdentityName.applySystemNameIfEnabled(this)) {
                log("系统蓝牙名已按身份名同步：" + com.carbridge.carplay.IdentityName.resolve(this));
            }
        } catch (Throwable tDiag) {
            com.carbridge.carplay.util.Diag.w("MainActivity.identityName", tDiag);
        }
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
        View rowRepair = findViewById(R.id.row_repair);
        if (rowRepair != null) {
            rowRepair.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showRepairDialog(); }
            });
        }
        View rowPairScan = findViewById(R.id.row_pair_scan);
        if (rowPairScan != null) {
            rowPairScan.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showPairScanDialog(); }
            });
        }
        View rowCarSide = findViewById(R.id.row_car_side);
        if (rowCarSide != null) {
            rowCarSide.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showCarSideDialog(); }
            });
        }
        // 崩溃处理器在 onCreate 最前面已装过（每进程一次），这里不再重复链
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
        // 「按车机记忆连接参数」：开了才按上次那台车的 srcvers/画布预置（默认关）。
        applyCarProfileMemory();
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
        // ⚠️ 先用 peek（缓存/手填，同步零阻塞）设置一次：realBluetoothAddress 的
        //    resolve 可能走 `su`（最坏 ~1.5s+），在主线程等它就是冷启动卡一下。
        //    后台 resolve 完再覆盖成更准的值（peek 与真实值一致时结果相同）。
        try {
            com.carbridge.carplay.core.AirPlaySetup.setDeviceIdentity(
                    com.carbridge.carplay.bt.BtIdentity.peek(this).mac);
        } catch (Throwable tDiag) { com.carbridge.carplay.util.Diag.w("MainActivity", tDiag);
        }
        // 蓝牙地址解析里可能要走一次 `su`（最长 1.5s）。后台解析完覆盖 + 记入缓存，
        // 免得 100ms 后的 toggleCarPlay() 在主线程上等它（现场表现是冷启动卡一下）。
        com.carbridge.carplay.util.Bg.run("identity-warm", new Runnable() {
            @Override
            public void run() {
                try {
                    com.carbridge.carplay.bt.BtIdentity.Resolved r =
                            com.carbridge.carplay.bt.BtIdentity.resolve(MainActivity.this, false);
                    if (r.ok()) com.carbridge.carplay.bt.BtIdentity.remember(MainActivity.this, r);
                    com.carbridge.carplay.core.AirPlaySetup.setDeviceIdentity(r.mac);
                } catch (Throwable tDiag) {
                    com.carbridge.carplay.util.Diag.w("MainActivity", tDiag);
                }
            }
        });
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
        autoConnectOnce = new Runnable() {
            @Override
            public void run() {
                // ⚠️ 必须看用户意图：打开 App 后 3 秒内手动「连接→断开」时，
                //    以前这里只看 client 是否在跑，会立刻把连接重新拉起来
                //    （连 KeepAlive 的三个锁一起），用户视角是「我断了它自己又连上」。
                if (destroyed || isFinishing() || !userWantsConnected) return;
                if (client == null || !client.isRunning()) {
                    toggleConnect();
                }
            }
        };
        main.postDelayed(autoConnectOnce, 3000L);
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
    /** 自适应帧率上限（0=不设限；仅 autoFit 模式生效，手动改帧率会清空）。 */
    private static final String KEY_FPS_CAP = "fps_cap";
    private static final String KEY_AUTO_FIT = "auto_fit";
    /** 「按车机记忆连接参数」开关（默认关：开启才允许记忆值参与连接，行为可预期）。 */
    private static final String KEY_CAR_PROFILE_MEMORY = "car_profile_memory";
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
    /** 「按车机记忆连接参数」开关（设置页画质区）。 */
    private boolean carProfileMemoryEnabled() {
        return getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                .getBoolean(KEY_CAR_PROFILE_MEMORY, false);
    }

    /**
     * 按上次那台车机的参数预置（仅当开关开启）：
     * srcvers 用记忆值（每台车机只认它那一版）；画布只在「还没采到过」时预置，
     * 车机连上来后 /info 上报值会纠正。开关默认关 → 行为与之前完全一致。
     */
    private void applyCarProfileMemory() {
        if (!carProfileMemoryEnabled()) return;
        try {
            com.carbridge.carplay.CarProfileStore.CarProfile p =
                    com.carbridge.carplay.CarProfileStore.mostRecent(this);
            if (p == null) return;
            if (p.srcvers != null && CarPlayCtrlCompat.isValidValue(p.srcvers)
                    && !p.srcvers.equals(CarPlayCtrlCompat.srcvers(this))) {
                CarPlayCtrlCompat.setValue(this, p.srcvers);
                CarPlayCtrlCompat.apply(this);
                log("按车机记忆恢复 srcvers = " + p.srcvers + "（" + p.mac + "）");
            }
            if (p.hasCanvas() && (carW == 0 || carH == 0)) {
                carW = p.targetW;
                carH = p.targetH;
                if (p.targetFps > 0) carFps = p.targetFps;
                log("按车机记忆预置画布 " + carW + "x" + carH + "@" + carFps + "（" + p.mac + "）");
            }
        } catch (Throwable t) {
            com.carbridge.carplay.util.Diag.w("CarProfile", t);
        }
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
        // 详细协议日志开关（默认关）。逐帧 trace 一天能占掉八九成日志体积。
        CheckBox swTrace = (CheckBox) findViewById(R.id.switch_verbose_trace);
        if (swTrace != null) {
            swTrace.setChecked(com.carbridge.app.HuClient.verboseTrace);
            swTrace.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                    com.carbridge.app.HuClient.verboseTrace = checked;
                    getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                            .putBoolean(KEY_VERBOSE_TRACE, checked).apply();
                    log(checked ? "详细协议日志：开（逐帧 trace，体积会明显变大）" : "详细协议日志：关");
                }
            });
        }
        // 「按车机记忆连接参数」开关（默认关）
        CheckBox swCarProfile = (CheckBox) findViewById(R.id.switch_car_profile);
        if (swCarProfile != null) {
            swCarProfile.setChecked(getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                    .getBoolean(KEY_CAR_PROFILE_MEMORY, false));
            swCarProfile.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                    getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                            .putBoolean(KEY_CAR_PROFILE_MEMORY, checked).apply();
                    log(checked
                            ? "按车机记忆连接参数：开（下次启动按上次那台车预置 srcvers/画布）"
                            : "按车机记忆连接参数：关");
                }
            });
        }
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
                        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                                .putInt(KEY_FPS_CAP, 0).apply();   // 用户意图优先：清掉自适应上限
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
        commitResolution(true);
    }

    /**
     * 把输入框里的内容读出来、校验、落盘。
     *
     * @param userInitiated true = 用户真的编辑完（失焦）；false = 连接前顺手收一次输入框
     *
     * <p>⚠️ 两者必须分开：以前「连接前顺手收一次」也走同一套逻辑，用户宽度框刚敲到
     * 「19」时链路断开触发重连，就会被夹成 320、判成「用户手改」→ autoFit 被静默关掉
     * 并落盘，之后每次连接都请求 320 宽，再也自恢复不了。
     */
    private void commitResolution(boolean userInitiated) {
        if (editWidth == null || editHeight == null) {
            return;
        }
        int w = parseDim(editWidth, DEF_W);
        int h = parseDim(editHeight, DEF_H);
        if (w == encWidth && h == encHeight) {
            return;
        }
        if (userInitiated) {
            encWidth = w;
            encHeight = h;
            autoFit = false;          // 手动改分辨率 = 放弃自动跟随
            editWidth.setText(String.valueOf(w));
            editHeight.setText(String.valueOf(h));
            persistQuality();
            updateQualityTargetView();
            if (client != null) client.setAutoFit(false);
            log("画质：分辨率改为 " + w + "×" + h + "，自动适配已关闭（下次连接生效）");
            return;
        }
        // 非用户编辑：只有输入是完整合法值才采用；半截输入（如「19」）保持原值不动
        if (!isCompleteDim(editWidth) || !isCompleteDim(editHeight)) {
            editWidth.setText(String.valueOf(encWidth));
            editHeight.setText(String.valueOf(encHeight));
            return;
        }
        encWidth = w;
        encHeight = h;
        editWidth.setText(String.valueOf(w));
        editHeight.setText(String.valueOf(h));
        persistQuality();
        updateQualityTargetView();
        log("画质：采用输入框里的 " + w + "×" + h + "（自动适配保持"
                + (autoFit ? "开" : "关") + "）");
    }

    /** 输入框里是不是一个「敲完了」的合法分辨率（半截数字不算）。 */
    private static boolean isCompleteDim(EditText e) {
        try {
            int v = Integer.parseInt(e.getText() == null ? "" : e.getText().toString().trim());
            return v >= QualityPrefs.MIN_DIM && v <= QualityPrefs.MAX_DIM;
        } catch (Throwable t) {
            return false;
        }
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
                encWidth, encHeight, fpsIndex, fpsCap());
    }
    /**
     * Car+ 自适应（AdaptiveResolution 的接线点）：会话结束后按本轮**实测平均帧率**
     * 调整「下一次连接」的**帧率上限**（只有这条通路在 autoFit 模式下真正生效——
     * paramsForStart 在 autoFit 时忽略 enc 尺寸与 fpsIndex，见 J 审计 P1#1）。
     *
     * <p>安全边界：① 只影响下一次连接；② 8 秒以内不判（热身期偏低）；③ 60%~90%
     * 死区防抖；④ 上限只在 {60,45,30,24} 阶梯上走、不低于 24、不高于车机声明；
     * ⑤ 手动改帧率会清掉上限（用户意图优先）；⑥ autoFit 关闭时完全不参与。
     */
    private void maybeAdaptForNextSession() {
        long since = streamingSinceMs;
        streamingSinceMs = 0;
        if (since <= 0 || !autoFit) return;
        long seconds = Math.max(0L, System.currentTimeMillis() - since) / 1000;
        if (seconds < com.carbridge.carplay.core.AdaptiveResolution.MIN_SESSION_SECONDS) return;
        int frames = client != null ? client.frameCount() : 0;
        if (frames <= 0) return;
        double measured = frames / (double) seconds;
        int carFpsClamped = carFps > 0 ? DisplayGeometry.clampFps(carFps) : 0;
        if (carFpsClamped <= 0) return;
        int cap = fpsCap();
        int effective = cap > 0 ? Math.min(cap, carFpsClamped) : carFpsClamped;
        double ratio = measured / (double) effective;
        int next = cap;
        if (ratio < com.carbridge.carplay.core.AdaptiveResolution.LOW_FPS_PERCENT / 100.0) {
            int t = com.carbridge.carplay.core.AdaptiveResolution.downTier(effective);
            if (t < effective && t >= com.carbridge.carplay.core.AdaptiveResolution.MIN_TIER_FPS) {
                next = t;
            }
        } else if (ratio >= com.carbridge.carplay.core.AdaptiveResolution.HIGH_FPS_PERCENT / 100.0
                && cap > 0) {
            // 跑满当前上限 → 往上一档；追上/超过车机声明就撤掉上限
            int t = com.carbridge.carplay.core.AdaptiveResolution.upTier(cap);
            next = t >= carFpsClamped ? 0 : t;
        }
        if (next == cap) {
            log("画质自适应：实测 " + String.format(java.util.Locale.US, "%.1f", measured)
                    + "fps / 目标 " + effective + "fps（" + Math.round(ratio * 100)
                    + "%），保持" + (cap > 0 ? "上限 " + cap + "fps" : "不设限"));
            return;
        }
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                .putInt(KEY_FPS_CAP, next).apply();
        log("画质自适应：实测 " + String.format(java.util.Locale.US, "%.1f", measured)
                + "fps / 目标 " + effective + "fps（" + Math.round(ratio * 100) + "%）→ 下一次连接"
                + (next > 0 ? "帧率上限 " + next + "fps" : "撤掉帧率上限（跟随车机 " + carFpsClamped + "fps）"));
    }

    /** 自适应帧率上限（0=不设限）。唯一写入点是 maybeAdaptForNextSession 与「手动改帧率清空」。 */
    private int fpsCap() {
        return getSharedPreferences(PREF_NAME, MODE_PRIVATE).getInt(KEY_FPS_CAP, 0);
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
            // 车机要关键帧 → 向上游 Car+ 催一个 IDR（0x1800B），别干等自然 GOP
            final CarPlayBridge b = carPlay;
            b.setKeyFrameRequester(new Runnable() {
                @Override public void run() {
                    HuClient c = client;
                    if (c != null) c.requestKeyframe("车机 forceKeyFrame（解码器失步）");
                }
            });
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
        if (destroyed || isFinishing()) return;
        // 权限被拒后用户再点「连接」：这里补一次请求（已授权时是空操作）——
        // 以前只在 onCreate 请求，提示「再点一次连接并允许」实际永远弹不出窗。
        requestRuntimePermissions();
        if (client != null && client.isRunning()) {
            userWantsConnected = false;    // 手动断开：取消自动重连
            retryStep = 0;
            // 把已排队的「自动连接 / 自动重连」一起取消：只置标志位不够，
            // 3s 自动连接那条定时器曾经会在断开后把会话重新拉起来。
            if (autoConnectOnce != null) main.removeCallbacks(autoConnectOnce);
            if (reconnectRunnable != null) main.removeCallbacks(reconnectRunnable);
            reconnectRunnable = null;
            client.stop();
            client = null;
            // ⚠️ 断开是「两条会话都断」：以前只停 CarLife，CarPlay 推流还在跑，
            //    保活却被拆了 → 锁屏即被冻结，车机端黑屏，三处状态互相矛盾。
            CarPlayWirelessTx tx = carPlayTx;
            if (tx != null) {
                tx.stop();
                carPlayTx = null;
                resetCarPlaySteps();
                if (btnCarPlay != null) btnCarPlay.setText(R.string.btn_carplay_start);
            }
            if (carPlay != null) {
                carPlay.disconnect();
            }
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
        commitResolution(false);
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
                // Car+ 会话计时段：进入 STREAMING 记起点；回到终态时按实测帧率给
                // 「下一次连接」做自适应（AdaptiveResolution，有迟滞、只在阶梯上走一档）。
                if (s == HuClient.State.STREAMING) {
                    // 每轮会话重新起表：自动重连后 frameCount 会清零，
                    // 沿用上一轮的起点会让「帧数 ÷ 时长」系统性偏低 → 必然误降档。
                    streamingSinceMs = System.currentTimeMillis();
                } else if (s == HuClient.State.IDLE || s == HuClient.State.ERROR
                        || s == HuClient.State.CLOSED) {
                    maybeAdaptForNextSession();
                }
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
        // 手机型号刻意不显示（投屏中下面挂一串型号名既占地方又没用），接口要求保留回调
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

    /** ⚠️ synchronized：log() 会被主线程/视频线程/桥线程/崩溃钩子并发首调，
     *  无锁懒加载可能建出两个 SessionLog，各自开同一个文件的 fd，其中一个永不关闭。 */
    private synchronized SessionLog sessionLog() {
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
    private static final String LOG_FILE_NAME = "carbridge-session.log";
    /** 逐帧协议日志（默认关）。排查偶发问题才开，开一天能让日志涨十倍。 */
    private static final String KEY_VERBOSE_TRACE = "verbose_trace";
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
            // ⚠️ 「卡住 N 秒」的诊断也是计算型文案：每秒跟着重算，否则永远停在
            //    步骤变化瞬间的数值（≈0s），STALL_MS=8s 的停滞判定永远不出现。
            updateDiagnosis();
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
        if (destroyed || isFinishing()) return;
        // ⚠️ ERROR 与 CLOSED 常常接连到达：以前两个都排一次（退避档位被消耗两次，
        //    等于 4 档预算对半砍）。同一次断开只允许有一条排队。
        if (reconnectRunnable != null) return;
        if (retryStep >= RETRY_DELAYS.length) {
            log("自动重连：已重试 " + retryStep + " 次仍失败，停止自动重试（手动点「连接」可再试）");
            return;
        }
        final long delay = RETRY_DELAYS[retryStep];
        retryStep++;
        log("自动重连：第 " + retryStep + " 次，" + (delay / 1000) + "s 后重试");
        reconnectRunnable = new Runnable() {
            @Override
            public void run() {
                reconnectRunnable = null;
                // 执行时再复查一次：排队之后用户可能关掉了「自动重连」开关。
                if (destroyed || isFinishing()) return;
                if (userWantsConnected && autoReconnectEnabled()
                        && (client == null || !client.isRunning())) {
                    toggleConnect();
                }
            }
        };
        main.postDelayed(reconnectRunnable, delay);
    }

    private void refreshAutoReconnectRow() {
        if (autoReconnectRow == null) return;
        boolean on = autoReconnectEnabled();
        autoReconnectRow.setText("自动重连 · " + (on ? "开" : "关") + "（断开后 3/6/12/30s 退避重试）");
    }

    // ------------------------------------------------------------ 诊断包
    /** 打包：会话日志 + 本机身份 + 配置 + 会话耗时汇总 → 一个 zip，方便整个发出来。 */
    private void exportDiagPack() {
        // ⚠️ 不能在主线程做：日志最多 1MB，读 + 压缩 + 写外部存储（FUSE 慢）会卡 UI，
        //    车机热点下出现过明显卡顿。整个导出挪到后台，结果用 main.post 回主线程。
        toastShort("正在导出诊断包…");
        // ⚠️ 跨线程共享的可变状态必须在主线程**拍快照**：sessionTimeline 是 StringBuilder
        //    （主线程的 trackState 正在 append），后台读它可能读到半截甚至抛异常。
        final boolean snapAutoFit = autoFit;
        final int snapCarW = carW;
        final int snapCarH = carH;
        final int snapCarFps = carFps;
        final String snapTimeline = sessionTimeline.toString();
        com.carbridge.carplay.util.Bg.run("diag-export", new Runnable() {
            @Override
            public void run() {
                String msg;
                try {
                    java.io.File out = writeDiagZip(snapAutoFit, snapCarW, snapCarH, snapCarFps,
                            snapTimeline);
                    log("诊断包已导出：" + out.getAbsolutePath());
                    msg = "已导出：" + out.getName();
                } catch (Throwable t) {
                    log("✗ 诊断包导出失败：" + t);
                    msg = "导出失败：" + t.getMessage();
                }
                final String m = msg;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!destroyed) toastShort(m);
                    }
                });
            }
        });
    }

    /** 真正写 zip（后台线程调用）。 */
    private java.io.File writeDiagZip(boolean snapAutoFit, int snapCarW, int snapCarH,
                                        int snapCarFps, String snapTimeline) throws java.io.IOException {
        java.io.File dir = new java.io.File(getExternalFilesDir(null), "diag");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("建目录失败 " + dir);
        java.io.File out = new java.io.File(dir,
                "carbridge-diag-" + new java.text.SimpleDateFormat("MMdd-HHmmss", java.util.Locale.US)
                        .format(new Date()) + ".zip");
        try (java.util.zip.ZipOutputStream z =
                     new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(out))) {
            put(z, "session.log", readAll(logFile()));
            // 轮转出去的那半份也要进包：1MB 上限切掉的是**最新**的尾部，
            // 只发当前文件等于手里最关键的现场恰好缺失。
            java.io.File rotated = new java.io.File(logFile().getAbsolutePath() + ".1");
            if (rotated.isFile()) {
                put(z, "session.log.1", readAll(rotated));
            }
            StringBuilder dev = new StringBuilder();
            dev.append("时间: ").append(new Date()).append('\n');
            dev.append("机型: ").append(android.os.Build.MANUFACTURER).append(' ')
                    .append(android.os.Build.MODEL).append(" / Android ")
                    .append(android.os.Build.VERSION.RELEASE).append('\n');
            dev.append("蓝牙名/地址: ").append(BtIdentity.peek(this).mac).append('\n');
            dev.append("车机识别: ").append(CarPlaySdpBeacon.statusLine()).append('\n');
            dev.append("广播版本 srcvers: ").append(CarPlayCtrlCompat.srcvers(this)).append('\n');
            dev.append("画质自动适配: ").append(snapAutoFit).append(" / 车机画布 ")
                    .append(snapCarW).append('x').append(snapCarH).append('@').append(snapCarFps).append('\n');
            dev.append("自动重连: ").append(autoReconnectEnabled()).append('\n');
            if (snapTimeline.length() > 0) dev.append("本轮阶段耗时: ").append(snapTimeline).append('\n');
            dev.append("静默异常计数: ").append(com.carbridge.carplay.util.Diag.dump()).append('\n');
            put(z, "device.txt", dev.toString());
        }
        return out;
    }

    private static void put(java.util.zip.ZipOutputStream z, String name, String content)
            throws java.io.IOException {
        z.putNextEntry(new java.util.zip.ZipEntry(name));
        z.write(content == null ? new byte[0] : content.getBytes("UTF-8"));
        z.closeEntry();
    }

    private static String readAll(java.io.File f) {
        final int cap = 2_000_000;   // 日志本身 1MB 封顶 + 轮转文件，2MB 足够且不会失控
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(64 * 1024);
            byte[] b = new byte[64 * 1024];
            int n;
            int total = 0;
            // ⚠️ 必须循环读：单次 read() 允许短读（FUSE/网络盘上很常见），
            //    以前只 read 一次，诊断包里的日志会被静默截断。
            while (total < cap && (n = in.read(b, 0, Math.min(b.length, cap - total))) > 0) {
                bos.write(b, 0, n);
                total += n;
            }
            return new String(bos.toByteArray(), "UTF-8");
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
                    + " · 画布 " + carW + "x" + carH + "@" + carFps
                    + (carProfileMemoryEnabled() ? "" : "（开启「按车机记忆」后参与连接）"));
        } catch (Throwable t) {
            com.carbridge.carplay.util.Diag.w("CarProfile", t);
        }
    }

    /**
     * 全局未捕获异常落到会话日志里。
     *
     * <p>以前线程里抛异常 = App 直接消失，现场只有一句「闪退」，日志里什么都没有。
     */
    /** 崩溃处理器只装一次（每进程）。⚠️ 不能持有 Activity：以前每次 onCreate 都链一层
     *  匿名内类，进程被前台服务长期保活 → 每开关一次 App 就泄漏整个 Activity + View 树。 */
    private static volatile boolean crashHandlerInstalled;

    /**
     * 安装全局崩溃处理器：把「未捕获异常 + 完整栈」追加进会话日志，再交给上一个处理器。
     *
     * <p>只依赖 Application Context 与文件路径，不引用任何 Activity 实例。
     */
    private static void installCrashHandlersOnce(final Context app) {
        if (crashHandlerInstalled) return;
        crashHandlerInstalled = true;
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    File dir = app.getExternalFilesDir(null);
                    if (dir != null) {
                        File f = new File(dir, "carbridge-session.log");
                        FileOutputStream o = new FileOutputStream(f, true);
                        o.write(("!!!! 未捕获异常，进程即将终止 thread=" + t.getName()
                                + " !!!!\n").getBytes("UTF-8"));
                        java.io.StringWriter sw = new java.io.StringWriter();
                        e.printStackTrace(new java.io.PrintWriter(sw));
                        o.write(sw.toString().getBytes("UTF-8"));
                        o.write("\n".getBytes("UTF-8"));
                        o.flush();
                        o.close();
                    }
                } catch (Throwable tDiag) {
                    // 连崩溃日志都写不了就认了，绝不能二次抛
                    com.carbridge.carplay.util.Diag.w("MainActivity", tDiag);
                }
                if (prev != null) {
                    prev.uncaughtException(t, e);
                }
            }
        });
    }

    /** 当前打开的对话框引用（退出时统一 dismiss，防 WindowLeaked）。 */
    private volatile android.app.AlertDialog activeDialog;

    /** 对话框统一出口：Builder.show() 一律走这里，退出时 onDestroy 能 dismiss。 */
    private android.app.AlertDialog showDialog(AlertDialog.Builder b) {
        android.app.AlertDialog d = b.show();
        activeDialog = d;
        return d;
    }

    private void dismissActiveDialog() {
        android.app.AlertDialog d = activeDialog;
        activeDialog = null;
        if (d != null) {
            try {
                d.dismiss();
            } catch (Throwable tDiag) {
                com.carbridge.carplay.util.Diag.w("MainActivity", tDiag);
            }
        }
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
    /** 身份名当前值（iAP2/mDNS/系统蓝牙名三处同源，见 IdentityName）。 */
    private TextView idIdentityName;
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
                    idBtName, idBtMac, idSdp, idSrcvers, idIdentityName, identityHint);
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

        showDialog(new AlertDialog.Builder(this)
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
                        // ⚠️ resolve(true) 可能走 su（最长 1.5s）：挪到后台，结果回主线程刷新
                        com.carbridge.carplay.util.Bg.run("mac-reload", new Runnable() {
                            @Override public void run() {
                                final BtIdentity.Resolved r =
                                        BtIdentity.resolve(MainActivity.this, true);
                                BtIdentity.remember(MainActivity.this, r);
                                log("重新读取本机蓝牙地址：" + r.describe());
                                runOnUiThread(new Runnable() {
                                    @Override public void run() {
                                        if (!destroyed) refreshIdentity();
                                    }
                                });
                            }
                        });
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
        );
    }

    /**
     * 身份名设置：跟随本机蓝牙名 / iPhone / 自定义，外加「同步系统蓝牙名」开关。
     *
     * <p>三处同源（iAP2 识别 / mDNS 实例名 / 可选系统蓝牙名），见 {@link com.carbridge.carplay.IdentityName}。
     */
    private void showIdentityNameDialog() {
        final int curMode = com.carbridge.carplay.IdentityName.mode(this);
        final String curCustom = com.carbridge.carplay.IdentityName.custom(this);

        int pad = dp(20f);
        final android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(pad, dp(8f), pad, 0);

        final android.widget.RadioGroup rg = new android.widget.RadioGroup(this);
        final android.widget.RadioButton rbFollow = new android.widget.RadioButton(this);
        rbFollow.setId(1001);
        String btNow = null;
        try {
            android.bluetooth.BluetoothAdapter a =
                    com.carbridge.carplay.bt.Bt.adapter(this);
            if (a != null) btNow = a.getName();
        } catch (Throwable tDiag) {
            com.carbridge.carplay.util.Diag.w("MainActivity.identityNameDialog", tDiag);
        }
        rbFollow.setText(getString(R.string.identity_name_mode_follow)
                + "（" + (btNow == null || btNow.isEmpty() ? "?" : btNow) + "）");
        final android.widget.RadioButton rbIphone = new android.widget.RadioButton(this);
        rbIphone.setId(1002);
        rbIphone.setText(R.string.identity_name_mode_iphone);
        final android.widget.RadioButton rbCustom = new android.widget.RadioButton(this);
        rbCustom.setId(1003);
        rbCustom.setText(R.string.identity_name_mode_custom);
        rg.addView(rbFollow);
        rg.addView(rbIphone);
        rg.addView(rbCustom);
        rg.check(curMode == com.carbridge.carplay.IdentityName.MODE_IPHONE ? 1002
                : curMode == com.carbridge.carplay.IdentityName.MODE_CUSTOM ? 1003 : 1001);

        final android.widget.EditText et = new android.widget.EditText(this);
        et.setHint(R.string.identity_name_custom_hint);
        et.setText(curCustom);
        et.setVisibility(curMode == com.carbridge.carplay.IdentityName.MODE_CUSTOM
                ? View.VISIBLE : View.GONE);
        final android.widget.CheckBox cb = new android.widget.CheckBox(this);
        cb.setText(R.string.identity_name_sync_bt);
        cb.setChecked(com.carbridge.carplay.IdentityName.syncSystemName(this));
        cb.setEnabled(rg.getCheckedRadioButtonId() != 1001);
        rg.setOnCheckedChangeListener(new android.widget.RadioGroup.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.RadioGroup group, int checkedId) {
                et.setVisibility(checkedId == 1003 ? View.VISIBLE : View.GONE);
                // 「跟随蓝牙名」时同步开关没有意义（名字本来就一样）
                cb.setEnabled(checkedId != 1001);
                if (checkedId == 1001) cb.setChecked(false);
            }
        });
        android.widget.TextView hint = new android.widget.TextView(this);
        hint.setText(R.string.identity_name_sync_hint);
        hint.setTextSize(12f);
        hint.setTextColor(getColorCompat(R.color.text_muted));

        box.addView(rg);
        box.addView(et);
        box.addView(cb);
        box.addView(hint);

        showDialog(new AlertDialog.Builder(this)
                .setTitle(R.string.identity_name_dialog_title)
                .setView(box)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        int checked = rg.getCheckedRadioButtonId();
                        int mode = checked == 1002 ? com.carbridge.carplay.IdentityName.MODE_IPHONE
                                : checked == 1003 ? com.carbridge.carplay.IdentityName.MODE_CUSTOM
                                : com.carbridge.carplay.IdentityName.MODE_FOLLOW;
                        com.carbridge.carplay.IdentityName.save(MainActivity.this, mode,
                                et.getText().toString(), cb.isChecked());
                        boolean synced = com.carbridge.carplay.IdentityName.applySystemNameIfEnabled(MainActivity.this);
                        String name = com.carbridge.carplay.IdentityName.resolve(MainActivity.this);
                        log(getString(R.string.identity_name_applied, name,
                                getString(synced ? R.string.identity_name_sync_yes
                                        : R.string.identity_name_sync_no)));
                        identity().refresh();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null));
    }

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

        showDialog(new AlertDialog.Builder(this)
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
        );
    }

    private void showSrcversCustomDialog() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("950.7.1");
        input.setTextSize(16);
        input.setText(CarPlayCtrlCompat.srcvers(this));
        showDialog(new AlertDialog.Builder(this)
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
        );
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

    /**
     * 「重新配对车机」：车机只在**配对那一刻**查一次 SDP，之后一直吃缓存。
     *
     * <p>现场最常见的一种坏状态：车机在 App 装好之前就和这台手机配对过，
     * 它缓存下来的结论是「普通手机，不支持 CarPlay」—— 之后信标挂得再好也
     * 不会变绿，因为车机根本不重查。唯一解法是**两边都删掉配对、在信标已注册
     * 的状态下重配一次**。
     *
     * <p>这个入口只做三件事：确认信标在跑 → 清手机侧配对 → 打开蓝牙设置页
     * （Android 14+ 唯一能让本机重新可被车机发现的地方，且只有约 120 秒）。
     */
    private void showRepairDialog() {
        if (!CarPlaySdpBeacon.isStarted()) {
            // 信标没跑的时候重配 = 把唯一一次机会浪费掉，先拦住
            Toast.makeText(this, R.string.repair_no_beacon, Toast.LENGTH_LONG).show();
            log("✗ 重新配对中止：SDP 信标没在跑（先确认蓝牙已开、权限已给）");
            return;
        }
        final android.bluetooth.BluetoothDevice car;
        try {
            car = CarPairing.findCar();
        } catch (SecurityException e) {
            Toast.makeText(this, "缺少「附近的设备」权限：请到系统设置授予本应用后重试", Toast.LENGTH_LONG).show();
            return;
        }
        if (car == null) {
            // 已经清过配对、只是还没和车机重配上 —— 这一步不该是死路：
            // 直接把「让车机发现本机」的窗口打开（蓝牙设置页 = 可被发现）
            log("• 现在没有已配对的车机：打开蓝牙设置页，等车机来搜本机");
            showDialog(new AlertDialog.Builder(this)
                    .setTitle(R.string.btn_repair)
                    .setMessage(R.string.repair_no_car_msg)
                    .setPositiveButton(R.string.repair_open_bt, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            openBluetoothSettings();
                        }
                    })
                    .setNegativeButton(R.string.repair_cancel, null)
        );
            return;
        }
        String carName = null;
        try {
            carName = car.getName();
        } catch (Throwable tDiag) {
            Diag.w("MainActivity.showRepairDialog", tDiag);
        }
        if (carName == null || carName.length() == 0) carName = car.getAddress();
        showDialog(new AlertDialog.Builder(this)
                .setTitle(R.string.btn_repair)
                .setMessage(getString(R.string.repair_msg, carName))
                .setPositiveButton(R.string.repair_ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        doRepair(car);
                    }
                })
                .setNegativeButton(R.string.repair_cancel, null)
        );
    }

    /** 真正执行：清手机侧配对 → 打开蓝牙设置页（进入可被发现窗口）。 */
    private void doRepair(android.bluetooth.BluetoothDevice car) {
        log("── 重新配对车机 ──");
        log("① 信标：" + CarPlaySdpBeacon.statusLine());
        boolean ok = CarPairing.forgetCar(car, new CarPairing.Log() {
            @Override
            public void onLog(String line) {
                log("② " + line);
            }
        });
        if (ok) {
            log("③ 手机侧配对已清除。接下来必须**从车机发起**搜索配对，手机不能主动点车机");
            log("   车机侧也要删掉本机 —— 只删手机侧，车机保留旧 link key，不会重查 SDP");
            log("   蓝牙设置页马上打开：打开期间本机可被车机发现，约 120 秒");
            Toast.makeText(this, R.string.repair_done, Toast.LENGTH_LONG).show();
        } else {
            log("③ 手机侧解绑失败：到「设置 → 蓝牙」手动取消配对车机，然后重来");
            Toast.makeText(this, R.string.repair_failed, Toast.LENGTH_LONG).show();
        }
        openBluetoothSettings();
        refreshIdentity();
    }

    /**
     * 打开系统蓝牙设置页。
     *
     * <p>这不是「顺手跳一下」：Android 14+ 起把本机设为可被发现的方法已被框架删除
     * （{@code setDiscoverable} 抛 NoSuchMethodException），而**打开蓝牙设置页会让
     * 系统把本机置成 SCAN_MODE_CONNECTABLE_DISCOVERABLE**——这是普通 App 唯一还能
     * 让车机在搜索里看见本机的入口。
     */
    private void openBluetoothSettings() {
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            log("✗ 打不开蓝牙设置页：" + t);
        }
    }

    /**
     * 「搜索并配对车机」：配对由 App 发起 —— 这是 mcarplay 那边唯一和我们不一样的
     * 实操差异（它的 SDP 注册代码和我们逐行相同，没有 root、没有 EIR、没有后门）。
     *
     * <p>为什么这么重要：车机只在**配对那一刻**读一次我们的 SDP。从系统蓝牙设置里
     * 配对时，用户可能先退出了 App、或系统先做了别的事，时序不受我们控制；
     * 从 App 里发起，就能保证「信标已在跑 → 再 createBond」这个顺序。
     */
    private void showPairScanDialog() {
        android.bluetooth.BluetoothAdapter adapter = com.carbridge.carplay.bt.Bt.adapter(this);
        // ⚠️ Android 12+ 无 BLUETOOTH_CONNECT 时 isEnabled() 会抛 SecurityException，
        //    以前这里直接崩 —— 恰好是本 App 引导用户「去授权」的那个状态。
        boolean enabled;
        try {
            enabled = adapter != null && adapter.isEnabled();
        } catch (SecurityException e) {
            Toast.makeText(this, "缺少「附近的设备」权限：请到系统设置授予本应用后重试", Toast.LENGTH_LONG).show();
            return;
        }
        if (!enabled) {
            Toast.makeText(this, "蓝牙没开", Toast.LENGTH_LONG).show();
            return;
        }
        if (!CarPlaySdpBeacon.isStarted()) {
            Toast.makeText(this, R.string.repair_no_beacon, Toast.LENGTH_LONG).show();
            log("✗ 配对中止：SDP 信标没在跑（先确认蓝牙已开、权限已给）");
            return;
        }
        log("── 搜索并配对车机（App 内发起）──");
        log("① 信标：" + CarPlaySdpBeacon.statusLine() + " —— 配对那一刻它必须在跑");
        Toast.makeText(this, R.string.pair_scan_hint, Toast.LENGTH_LONG).show();
        startPairScan(adapter);
    }

    /** 后台扫 12 秒，收设备，再把结果丢回主线程让用户点。 */
    private void startPairScan(final android.bluetooth.BluetoothAdapter adapter) {
        // ⚠️ 重入保护：连点会并发多条 Bg 扫描，startDiscovery/cancelDiscovery 互相打断，
        //    12s 后弹出多个选择框，选中的列表还可能来自被打断的扫描。
        if (pairScanning) {
            Toast.makeText(this, "扫描进行中，稍等一下", Toast.LENGTH_SHORT).show();
            return;
        }
        pairScanning = true;
        com.carbridge.carplay.util.Bg.run("pair-scan", new Runnable() {
            @Override
            public void run() {
                // ⚠️ 这个列表由广播接收器（主线程）写、后面的 Bg 线程读：
                //    ArrayList 并发读写会读到旧值甚至 ConcurrentModificationException。
                final List<android.bluetooth.BluetoothDevice> found =
                        new java.util.concurrent.CopyOnWriteArrayList<android.bluetooth.BluetoothDevice>();
                android.content.BroadcastReceiver receiver = new android.content.BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        if (!android.bluetooth.BluetoothDevice.ACTION_FOUND.equals(intent.getAction())) {
                            return;
                        }
                        android.bluetooth.BluetoothDevice d =
                                com.carbridge.carplay.bt.Bt.deviceFrom(intent);
                        if (d == null) return;
                        for (android.bluetooth.BluetoothDevice x : found) {
                            if (x.getAddress() != null && x.getAddress().equals(d.getAddress())) return;
                        }
                        found.add(d);
                    }
                };
                android.content.IntentFilter f = new android.content.IntentFilter(
                        android.bluetooth.BluetoothDevice.ACTION_FOUND);
                try {
                    // 系统广播（蓝牙栈发的）必须 EXPORTED，否则一条都收不到
                    // ⚠️ 三参重载是 API 33 才有的；minSdk 26 上直接调会 NoSuchMethodError
                    //    （被 catch 吞成一行「注册扫描监听失败」，扫描恒空）。同文件
                    //    bond watcher（2209+）就是版本分支写法，这里对齐。
                    if (Build.VERSION.SDK_INT >= 33) {
                        registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
                    } else {
                        registerReceiver(receiver, f);
                    }
                } catch (Throwable t) {
                    log("✗ 注册扫描监听失败：" + t);
                    pairScanning = false;
                    return;
                }
                try {
                    adapter.cancelDiscovery();
                    boolean started = adapter.startDiscovery();
                    log("② 开始搜索（startDiscovery=" + started + "），最多等 12 秒");
                    for (int i = 0; i < 24; i++) {
                        Thread.sleep(500);
                        if (found.size() > 0 && i > 12) break;
                    }
                } catch (Throwable t) {
                    log("✗ 搜索失败：" + t);
                } finally {
                    try {
                        adapter.cancelDiscovery();
                    } catch (Throwable tDiag) {
                        Diag.w("MainActivity.run", tDiag);
                    }
                    try {
                        unregisterReceiver(receiver);
                    } catch (Throwable tDiag) {
                        Diag.w("MainActivity.run#2", tDiag);
                    }
                }
                log("③ 搜到 " + found.size() + " 个设备");
                final List<android.bluetooth.BluetoothDevice> result =
                        new ArrayList<android.bluetooth.BluetoothDevice>(found);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        pairScanning = false;   // 结果回到主线程才放行下一次扫描
                        showPairScanResult(result);
                    }
                });
            }
        });
    }

    /** 把搜到的设备列出来（车机排前面），点一个就 createBond。 */
    private void showPairScanResult(List<android.bluetooth.BluetoothDevice> devices) {
        // 配对扫描最长 12 秒：期间用户可能已经退出 —— 在已销毁的 Activity 上
        // show() 会抛 WindowManager$BadTokenException。
        if (destroyed || isFinishing()) return;
        if (devices == null || devices.isEmpty()) {
            Toast.makeText(this, R.string.pair_scan_empty, Toast.LENGTH_LONG).show();
            return;
        }
        final List<android.bluetooth.BluetoothDevice> sorted =
                new ArrayList<android.bluetooth.BluetoothDevice>(devices);
        java.util.Collections.sort(sorted, new java.util.Comparator<android.bluetooth.BluetoothDevice>() {
            @Override
            public int compare(android.bluetooth.BluetoothDevice a, android.bluetooth.BluetoothDevice b) {
                return (isLikelyCar(b) ? 1 : 0) - (isLikelyCar(a) ? 1 : 0);
            }
        });
        final List<String> labels = new ArrayList<String>();
        for (android.bluetooth.BluetoothDevice d : sorted) {
            String name = null;
            try {
                name = d.getName();
            } catch (Throwable tDiag) {
                Diag.w("MainActivity.showPairScanResult", tDiag);
            }
            String addr = "";
            try {
                addr = d.getAddress();
            } catch (Throwable tDiag) {
                Diag.w("MainActivity.showPairScanResult#2", tDiag);
            }
            labels.add((isLikelyCar(d) ? "🚗 " : "") + (name == null ? "(无名)" : name)
                    + "  " + addr);
        }
        showDialog(new AlertDialog.Builder(this)
                .setTitle(R.string.pair_scan_pick)
                .setItems(labels.toArray(new CharSequence[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        beginBond(sorted.get(which));
                    }
                })
                .setNegativeButton(R.string.repair_cancel, null)
        );
    }

    /** 粗略判「这像不像车机」：名字带车机特征，或 CoD 是车载音频。 */
    private static boolean isLikelyCar(android.bluetooth.BluetoothDevice d) {
        try {
            String n = d.getName();
            if (n != null) {
                String s = n.toLowerCase(Locale.US);
                if (s.contains("cloudrive") || s.contains("tiggo") || s.contains("chery")
                        || s.contains("carplay") || s.contains("desay") || s.contains("byd")
                        || s.contains("geely") || s.contains("audi") || s.contains("bmw")) {
                    return true;
                }
            }
        } catch (Throwable tDiag) {
            Diag.w("MainActivity.isLikelyCar", tDiag);
        }
        try {
            int cod = d.getBluetoothClass() == null ? 0 : d.getBluetoothClass().getDeviceClass();
            return ((cod >> 8) & 0x1F) == 0x04;   // 音视频大类：车载音响/免提
        } catch (Throwable tDiag) {
            Diag.w("MainActivity.isLikelyCar#2", tDiag);
        }
        return false;
    }

    /** 发起配对。配对成功后的提示由 bond watcher 负责（它现在能收到广播了）。 */
    private void beginBond(final android.bluetooth.BluetoothDevice device) {
        String name = null;
        String addr = "";
        try {
            name = device.getName();
            addr = device.getAddress();
        } catch (Throwable tDiag) {
            Diag.w("MainActivity.beginBond", tDiag);
        }
        log("④ 发起配对：" + (name == null ? "(无名)" : name) + " [" + addr + "]");
        try {
            boolean ok = device.createBond();
            log(ok ? "⑤ createBond() 已发起 —— 手机弹窗点「配对」" : "✗ createBond() 返回 false");
            Toast.makeText(this, ok ? R.string.pair_started : R.string.pair_start_failed,
                    Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            log("✗ createBond 异常：" + t);
            Toast.makeText(this, R.string.pair_start_failed, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 「车机一键解锁（免电脑）」：手机自己连车机热点、自己 adb 进车机，把 CarPlay 后门写上。
     *
     * <p>为什么需要它：车机判「这台手机算不算 CarPlay 设备」还有第二条路 ——
     * 系统属性 {@code persist.sys.wirelesscarplay.address} 等于手机蓝牙 MAC 时，
     * CarPlay 应用会把它直接放进设备列表，<b>不检查 SDP 里的 2d8d2466</b>。
     * 这条后门只有 adb 能写，而用户不方便把电脑带到车上，所以内置一个最小 adb 客户端。
     *
     * <p>车机的 adbd 早先信任过 Mac 的密钥，包里带的就是同一把，因此正常情况下
     * 不需要在车机屏幕上点「允许调试」。
     */
    private void showCarSideDialog() {
        showDialog(new AlertDialog.Builder(this)
                .setTitle(R.string.btn_car_side)
                .setMessage(R.string.car_side_msg)
                .setPositiveButton(R.string.car_side_run, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        runCarSideSetup();
                    }
                })
                .setNegativeButton(R.string.repair_cancel, null)
        );
    }

    /** 后台跑车机侧流程：扫网段 → adb 连接 → 写属性 → 重启车机 CarPlay 应用。 */
    private void runCarSideSetup() {
        Toast.makeText(this, R.string.car_side_running, Toast.LENGTH_LONG).show();
        log("── 车机一键解锁（免电脑）──");
        com.carbridge.carplay.util.Bg.run("car-side", new Runnable() {
            @Override
            public void run() {
                // ⚠️ resolve 可能走 su（最长 1.5s）：必须在后台线程做 —— 以前在
                //    点击回调的主线程里跑，冷缓存时 UI 冻结。
                final String mac = com.carbridge.carplay.bt.BtIdentity.peek(MainActivity.this).ok()
                        ? com.carbridge.carplay.bt.BtIdentity.peek(MainActivity.this).mac
                        : com.carbridge.carplay.bt.BtIdentity.resolve(MainActivity.this, false).mac;
                if (mac == null) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            Toast.makeText(MainActivity.this, "读不到本机蓝牙地址",
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                    return;
                }
                log("本机蓝牙 MAC：" + mac);
                final com.carbridge.car.CarSideSetup.Result r =
                        com.carbridge.car.CarSideSetup.run(MainActivity.this, mac,
                                new com.carbridge.car.CarSideSetup.Log() {
                                    @Override
                                    public void log(String line) {
                                        MainActivity.this.log(line);
                                    }
                                });
                log((r.ok ? "✓ " : "✗ ") + r.message);
                final String msg = r.ok ? r.message : MainActivity.this.getString(R.string.car_side_fail)
                        + "（" + r.message + "）";
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                        refreshIdentity();
                    }
                });
            }
        });
    }

    /** 版本号只从 PackageInfo 读：日志里对得上装的是哪一版，手写一定会漂。 */
    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
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
    /** 只渲染尾部 LOG_UI_TAIL 行；有搜索词时只渲染匹配行 + 计数。 */
    private void renderLogTail() {
        String q = logQuery;
        if (q == null || q.trim().isEmpty()) {
            logView.setText(sessionLog().tail(SessionLog.UI_TAIL));
            if (logSearchCount != null) logSearchCount.setText("");
        } else {
            SessionLog.SearchResult r = sessionLog().search(q, 500);
            if (r.matches == 0) {
                logView.setText(getString(R.string.log_search_none, r.scanned));
                if (logSearchCount != null) logSearchCount.setText("");
            } else {
                logView.setText(r.text);
                if (logSearchCount != null) {
                    logSearchCount.setText(getString(R.string.log_search_count, r.matches, r.scanned));
                }
            }
        }
        if (logOpen) {
            final ScrollView sv = logScroll;
            sv.post(new Runnable() {
                @Override
                public void run() {
                    sv.fullScroll(ScrollView.FOCUS_DOWN);
                }
            });
        }
    }
    /** 关掉日志文件句柄（Activity 销毁时调用，否则每次重建都漏一个 fd）。 */
    private void closeLogFile() {
        if (sessionLog != null) sessionLog.close();
    }
        /**
     * 崩溃取证：把任何线程的未捕获异常栈直接写进会话日志。
     */
    private File logFile() {
        return sessionLog().file();
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
        // 文件不可写/被清空时的兜底：内存日志在 SessionLog 里（logLines 自迁移后已死）
        return sessionLog().all();
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
            // 文件不在（极端情况）就把当前会话日志落一份，保证分享出去的有内容。
            // ⚠️ 以前这里写的是 logLines —— 日志搬到 SessionLog 之后它再没人 append，
            //    兜底会生成一个 0 字节文件，分享出去是空的。
            if (!logFile().isFile()) {
                sessionLog().dumpToFile();
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
        boolean bluetoothDenied = false;
        for (int i = 0; i < permissions.length; i++) {
            boolean ok = i < grantResults.length
                    && grantResults[i] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            log((ok ? "✓ 已授权 " : "✗ 被拒绝 ") + permissions[i].replace("android.permission.", ""));
            if (!ok && permissions[i] != null && permissions[i].contains("BLUETOOTH")) {
                bluetoothDenied = true;
            }
        }
        if (bluetoothDenied) {
            // ⚠️ 以前被拒只写一行日志：之后自动连接会盲试 4 轮，界面只显示「失败」，
            //    用户根本不知道自己该去开权限。
            boolean canAskAgain = true;
            try {
                String perm = android.os.Build.VERSION.SDK_INT >= 31
                        ? android.Manifest.permission.BLUETOOTH_CONNECT
                        : android.Manifest.permission.ACCESS_FINE_LOCATION;
                canAskAgain = shouldShowRequestPermissionRationale(perm);
            } catch (Throwable tDiag) {
                com.carbridge.carplay.util.Diag.w("MainActivity.permRationale", tDiag);
            }
            String msg = canAskAgain
                    ? "蓝牙权限被拒绝：车机发现不了本机。请再点一次「连接」并允许「附近的设备」。"
                    : "蓝牙权限已被永久拒绝：请到「系统设置 → 应用 → 本应用 → 权限」里打开"
                            + "「附近的设备 / 蓝牙」，再回到本 App。";
            log("⚠ " + msg);
            toastShort(msg);
            if (!canAskAgain) showPermissionSettingsDialog(msg);
        }
        // BLUETOOTH_CONNECT 刚拿到时 onCreate 那次注册会失败，这里补一次
        startCarPlayBeacon();
    }

    /** 权限被永久拒绝时给一个直达设置的入口（否则用户只能自己找）。 */
    private void showPermissionSettingsDialog(String msg) {
        if (destroyed || isFinishing()) return;
        try {
            showDialog(new AlertDialog.Builder(this)
                    .setTitle(R.string.perm_needed_title)
                    .setMessage(msg)
                    .setPositiveButton(R.string.perm_go_settings, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            try {
                                startActivity(new Intent(
                                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        android.net.Uri.parse("package:" + getPackageName())));
                            } catch (Throwable tDiag) {
                                com.carbridge.carplay.util.Diag.w("MainActivity.permSettings", tDiag);
                            }
                        }
                    })
                    .setNegativeButton(R.string.cancel, null)
        );
        } catch (Throwable tDiag) {
            com.carbridge.carplay.util.Diag.w("MainActivity.permDialog", tDiag);
        }
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
            public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                android.bluetooth.BluetoothDevice dev =
                        com.carbridge.carplay.bt.Bt.deviceFrom(intent);
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
                        // ⚠️ 这只能断言「信标当时在跑」，不能断言车机真的重读了 SDP：
                        //    车机眼里这是「新配对」还是「旧配对重新连上」我们看不到 ——
                        //    旧配对重连时车机可能直接用旧缓存（实测踩过：配对成功但就是不认）。
                        //    所以必须给出验收信号，别替车机下结论。
                        log("  信标在配对时是挂着的，车机这次应当学到 "
                                + CarProfile.APPLE_DEVICE_EIR_UUID + " + "
                                + CarProfile.APPLE_IAP2_UUID);
                        log("  验收：车机蓝牙列表里本机名字下应出现「(支持无线 CarPlay 连接)」小字");
                        log("  没出现 = 车机没重读（它那边还留着旧配对）→ 车机上删掉本机、"
                                + "列表里看不到本机后，再配一次");
                        log("  出现了再点「Apple CarPlay」（灰色也能点），等它回连本机");
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
                // ⚠️ 必须 EXPORTED：ACTION_BOND_STATE_CHANGED 是蓝牙栈（另一个进程）发的，
                //    NOT_EXPORTED 收不到 —— 实测 removeBond 之后一条广播都没进来，
                //    于是「配对那一刻信标在不在」这个最关键的证据永远是空的。
                registerReceiver(bondReceiver, f, Context.RECEIVER_EXPORTED);
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
        // ⚠️ 状态源必须是 SdpBeaconService.isAlive()（服务存活）；isStarted() 只是
        //    「SDP 记录已注册」（onDestroy 保留记录但服务已死），拿它当服务状态会假阳性。
        boolean beaconRunning = SdpBeaconService.isAlive();
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

        // ⑥ 静默异常：以前被空 catch 吞掉的异常，这里给计数（详情在诊断包的 Diag 段）
        String diagDump = com.carbridge.carplay.util.Diag.dump();
        if ("(无)".equals(diagDump)) {
            log("✓ 静默异常：无");
        } else {
            log("⚠ 静默异常（tag=次数，导出诊断包可看首个栈）：" + diagDump);
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
                        try {
                            runBtDiag();
                        } finally {
                            diagRunning = false;   // 放过这一轮：进程内后续 am start 诊断不被静默吞掉
                        }
                    }
                }, 1500);
            } else if ("eir".equals(diag)) {
                // 只扫 EIR：车机热点信息（Apple Device IE）
                main.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            BtDiag.scanEirOnly(appCtx, new BtDiag.Log() {
                                @Override
                                public void onLog(String line) {
                                    log(line);
                                }
                            });
                        } finally {
                            diagRunning = false;   // 同 uuid 分支：不被永闩吞掉后续诊断
                        }
                    }
                }, 1500);
            }
        } catch (Throwable tDiag) { com.carbridge.carplay.util.Diag.w("MainActivity", tDiag);
        }
    }
    // ------------------------------------------------------------- 页面切换
    /** 切换底部两页，并同步高亮标签。 */
    private void showPage(int index) {
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
        updateDiagnosis();
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

    /**
     * 一屏结论：现在第一个卡住的阶段是哪一步、卡了多久、下一步该看哪里。
     *
     * <p>判定全在 {@link StepDiagnosis}（纯逻辑 + 单测），这里只做「取当前状态 → 上色」。
     * 步骤变化时重置计时；停住超过阈值才说「无进展」，避免正常推进时也报卡住。
     */
    private void updateDiagnosis() {
        if (stepDiagnosisView == null) return;
        String key = currentStep + "|" + failedStep + "|" + carPlayStep + "|" + carPlayFailedStep;
        long now = System.currentTimeMillis();
        if (!key.equals(diagKey)) {
            diagKey = key;
            diagChangedAt = now;
        }
        long stalled = diagChangedAt == 0L ? 0L : Math.max(0L, now - diagChangedAt);

        String[] cl = new String[HuClient.Step.values().length];
        for (HuClient.Step st : HuClient.Step.values()) cl[st.ordinal()] = st.label;
        String[] cp = new String[CarPlayWirelessTx.Step.values().length];
        for (CarPlayWirelessTx.Step st : CarPlayWirelessTx.Step.values()) cp[st.ordinal()] = st.label;

        StepDiagnosis.Result r = StepDiagnosis.of(cl,
                currentStep == null ? null : currentStep.ordinal(),
                failedStep == null ? null : failedStep.ordinal(),
                cp,
                carPlayStep == null ? null : carPlayStep.ordinal(),
                carPlayFailedStep == null ? null : carPlayFailedStep.ordinal(),
                stalled);
        stepDiagnosisView.setText(r.text());
        switch (r.level) {
            case FAILED:
                stepDiagnosisView.setTextColor(getColorCompat(R.color.state_err));
                break;
            case RUNNING:
                stepDiagnosisView.setTextColor(getColorCompat(R.color.state_work));
                break;
            case DONE:
                stepDiagnosisView.setTextColor(getColorCompat(R.color.state_ok));
                break;
            default:
                stepDiagnosisView.setTextColor(getColorCompat(R.color.text_muted));
                break;
        }
    }

    private void updateProgress(int doneCount) {
        int pct = (int) (doneCount * 100L / TOTAL_STEPS);
        stepsProgress.setProgress(pct);
        stepsProgressText.setText(doneCount + " / " + TOTAL_STEPS);
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
     * 主按钮：空闲=蓝底白字「连接」，已连接=深红底红字「断开连接」。
     *
     * <p>⚠️ 这里以前把文字色设成 {@code accent}（蓝），而按钮底色也是 accent ——
     * 蓝底蓝字，连上之后整条按钮变成「一条没有字的蓝条」（真机截图确认过）。
     * 现在按状态连背景一起换：连接态用 bg_btn_danger，语义和可读性都对。
     */
    private void setConnectButton(boolean running) {
        if (btnConnect == null) return;
        btnConnect.setText(running ? R.string.btn_disconnect : R.string.btn_connect);
        if (running) {
            btnConnect.setBackgroundResource(R.drawable.bg_btn_danger);
            btnConnect.setTextColor(getColorCompat(R.color.state_err));
        } else {
            btnConnect.setBackgroundResource(R.drawable.bg_btn_primary);
            btnConnect.setTextColor(getColorCompat(R.color.on_accent));
        }
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
            @Override public void onState(final CarPlayWirelessTx.State s) {
                log("CarPlay 状态: " + s);
                // ⚠️ 按钮必须跟随 tx 状态机：tx 会自主转 ERROR（会话失败），
                //    以前按钮只在用户点击时改文案 → 失败后仍显示「停止配对/鉴权」，
                //    点它实际执行「重新启动」，语义完全反转。
                if (s == CarPlayWirelessTx.State.IDLE || s == CarPlayWirelessTx.State.ERROR) {
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (destroyed) return;
                            if (btnCarPlay != null) {
                                btnCarPlay.setText(R.string.btn_carplay_start);
                            }
                        }
                    });
                }
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
                if (destroyed) return;   // 回调来自 tx 线程；已销毁实例不能再建会话
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
        // 服务被杀/被系统回收的空档：这里把两个前台服务补齐（幂等）。
        // 以前只补 SDP 记录 —— 服务死了但记录还在，自检和看门狗全都在骗自己。
        if (!SdpBeaconService.isAlive()) {
            SdpBeaconService.start(this);
        }
        if (client != null || carPlayTx != null) {
            KeepAliveService.start(this);
        }
        refreshIdentity();
        kickIdentityProbe();
        // 从系统设置补完蓝牙权限再回来时，配对监听可能还没注册（它原来只在
        // startCarPlayBeacon 里注册）。registerBondWatcher 自身幂等，重复调无副作用。
        registerBondWatcher();
    }

    @Override
    protected void onDestroy() {
        // ⚠️ 顺序很重要：先钉「已销毁」并把主线程队列清空，再拆会话，最后 super。
        //    以前只清 bondPoll，main 里排队的自动连接/重连（最长 30s）会在已销毁的
        //    Activity 上继续跑：new HuClient + startForegroundService + client.start()
        //    → 无界面的幽灵会话；Android 12+ 后台起前台服务还会直接抛异常崩主线程。
        destroyed = true;
        bondPolling = false;
        bondPoll.removeCallbacksAndMessages(null);
        main.removeCallbacksAndMessages(null);
        reconnectRunnable = null;
        autoConnectOnce = null;
        // 打开的对话框跟着销毁：不 dismiss 会 WindowLeaked，部分 ROM 直接崩
        dismissActiveDialog();
        if (sAlive == this) {
            sAlive = null;
        }
        shutdownSessions();
        if (bondReceiver != null) {
            try {
                unregisterReceiver(bondReceiver);
            } catch (Throwable tDiag) { com.carbridge.carplay.util.Diag.w("MainActivity", tDiag);
            }
            bondReceiver = null;
        }
        // 关掉日志文件句柄：Activity 每重建一次就漏一个 fd
        closeLogFile();
        super.onDestroy();
    }

    /** 同步拆掉两条会话并停保活（幂等）。onDestroy 与「旧实例让位」共用——
     *  双实例切换时新实例用它保证旧会话**立即**消失，不等异步的 finish()/onDestroy。 */
    void shutdownSessions() {
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
        // 会话都拆完了：保活（前台服务 + 三把锁 + 常驻通知）没有服务对象了，必须停。
        // SDP 信标是 SdpBeaconService 的事，不受影响（车机识别靠它，不能停）。
        KeepAliveService.stop(this);
    }
}
