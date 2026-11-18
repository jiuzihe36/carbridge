package com.carbridge.app;

/* JADX INFO: loaded from: MainActivity.class */
public class MainActivity extends android.app.Activity implements com.carbridge.app.HuClient.Sink, com.carbridge.carplay.bridge.CarPlayBridge.Sink {
    private static final int C_IDLE = -7434605;
    private static final int C_WORK = -16087809;
    private static final int C_OK = -13577896;
    private static final int C_ERR = -47814;
    private android.widget.TextView stateView;
    private android.widget.TextView detailView;
    private android.widget.TextView metricsView;
    private android.widget.TextView logView;
    private android.widget.TextView elapsedView;
    private android.widget.TextView btnConnect;
    private android.widget.TextView logHeader;
    private android.widget.TextView badgeView;
    private android.widget.TextView metricFps;
    private android.widget.TextView metricResolution;
    private android.widget.TextView metricBitrate;
    private android.widget.TextView idBtName;
    private android.widget.TextView idBtMac;
    private android.widget.TextView idSdp;
    private android.widget.TextView idSrcvers;
    private android.view.View identityHint;
    private android.widget.LinearLayout stepsContainer;
    private android.widget.ScrollView logScroll;
    private android.widget.ScrollView mainScroll;
    private android.view.View logBody;
    private com.carbridge.app.HuClient client;
    private com.carbridge.carplay.bridge.CarPlayBridge carPlay;
    private com.carbridge.carplay.CarPlayWirelessTx carPlayTx;
    private android.widget.TextView btnCarPlay;
    private boolean logOpen;
    private boolean carplayManualStop;
    private com.carbridge.app.HuClient.Step currentStep;
    private com.carbridge.app.HuClient.Step failedStep;
    private long lastStateAtMs;
    private java.lang.String lastStateLabel;
    private boolean userWantsConnected;
    private int retryStep;
    private android.widget.TextView autoReconnectRow;
    private com.carbridge.carplay.CarPlayWirelessTx.Step carPlayStep;
    private com.carbridge.carplay.CarPlayWirelessTx.Step carPlayFailedStep;
    private android.widget.ProgressBar stepsProgress;
    private android.widget.TextView stepsProgressText;
    private com.carbridge.app.LinkDiagramView linkDiagram;
    private int currentTab;
    private android.content.BroadcastReceiver bondReceiver;
    private boolean bondPolling;
    private java.lang.String lastBondedCarAddress;
    private int beaconTick;
    private volatile int fpsFrames;
    private volatile long fpsWindowStart;
    private float fpsValue;
    private volatile long totalBytes;
    private volatile long firstFrameDelayMs;
    private static com.carbridge.app.MainActivity sAlive;
    private static final int DEF_W = 1920;
    private static final int DEF_H = 720;
    private static final int MIN_DIM = 320;
    private static final int MAX_DIM = 3840;
    private static final java.lang.String PREF_NAME = "carbridge_quality";
    private static final java.lang.String KEY_W = "enc_width";
    private static final java.lang.String KEY_H = "enc_height";
    private static final java.lang.String KEY_FPS = "fps_index";
    private static final java.lang.String KEY_AUTO_FIT = "auto_fit";
    private static final java.lang.String KEY_CAR_W = "car_display_width";
    private static final java.lang.String KEY_CAR_H = "car_display_height";
    private static final java.lang.String KEY_CAR_FPS = "car_display_fps";
    private static final int DEF_FPS = 2;
    private int carW;
    private int carH;
    private int carFps;
    private android.widget.EditText editWidth;
    private android.widget.EditText editHeight;
    private android.widget.TextView qualityTargetView;
    private android.widget.CheckBox switchAutoFit;
    private boolean qualitySwitchSyncing;
    private com.carbridge.app.SessionLog sessionLog;
    private static final int LOG_UI_TAIL = 250;
    private static final java.lang.String LOG_FILE_NAME = "carbridge-session.log";
    private long busySince;
    private boolean diagRunning;
    private com.carbridge.app.IdentitySection identitySection;
    private static final int CARLIFE_STEPS = com.carbridge.app.HuClient.Step.values().length;
    private static final int CARPLAY_STEPS = com.carbridge.carplay.CarPlayWirelessTx.Step.values().length;
    private static final int TOTAL_STEPS = CARLIFE_STEPS + CARPLAY_STEPS;
    private static final int[] FPS_OPTIONS = {20, 24, 30, 60};
    private static final long[] RETRY_DELAYS = {3000, 6000, 12000, 30000};
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final java.text.SimpleDateFormat clock = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US);
    private java.lang.String phoneInfo = "";
    private boolean autoCarplayArmed = true;
    private java.lang.String failedReason = "";
    private final java.lang.StringBuilder sessionTimeline = new java.lang.StringBuilder();
    private java.lang.String carPlayFailedReason = "";
    private final android.view.View[] pages = new android.view.View[DEF_FPS];
    private final android.widget.TextView[] tabs = new android.widget.TextView[DEF_FPS];
    private final android.os.Handler bondPoll = new android.os.Handler(android.os.Looper.getMainLooper());
    private int encWidth = DEF_W;
    private int encHeight = DEF_H;
    private int fpsIndex = DEF_FPS;
    private boolean autoFit = true;
    private final java.util.concurrent.atomic.AtomicBoolean logUiPending = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.lang.Runnable elapsedTick = new java.lang.Runnable() { // from class: com.carbridge.app.MainActivity.25
        @Override // java.lang.Runnable
        public void run() {
            if (MainActivity.this.elapsedView == null) {
                return;
            }
            if (MainActivity.this.busySince <= 0) {
                MainActivity.this.elapsedView.setVisibility(8);
                return;
            }
            long jMax = java.lang.Math.max(0L, java.lang.System.currentTimeMillis() - MainActivity.this.busySince) / 1000;
            MainActivity.this.elapsedView.setText(java.lang.String.format(java.util.Locale.US, "%d:%02d", java.lang.Long.valueOf(jMax / 60), java.lang.Long.valueOf(jMax % 60)));
            MainActivity.this.elapsedView.setVisibility(0);
            MainActivity.this.main.postDelayed(this, 1000L);
        }
    };
    private final java.lang.Runnable bondPollTask = new java.lang.Runnable() { // from class: com.carbridge.app.MainActivity.37
        @Override // java.lang.Runnable
        public void run() {
            if (MainActivity.this.bondPolling) {
                MainActivity.this.checkBondedCar();
                if (com.carbridge.app.MainActivity.access$5604(MainActivity.this) >= 5) {
                    MainActivity.this.beaconTick = 0;
                    MainActivity.this.keepBeaconAlive("轮询");
                }
                MainActivity.this.bondPoll.postDelayed(this, 2000L);
            }
        }
    };

    /* JADX INFO: loaded from: MainActivity$OnPick.class */
    private interface OnPick {
        void onPick(int i);
    }

    static /* synthetic */ int access$5604(com.carbridge.app.MainActivity mainActivity) {
        int i = mainActivity.beaconTick + 1;
        mainActivity.beaconTick = i;
        return i;
    }

    @Override // android.app.Activity
    protected void onCreate(android.os.Bundle bundle) {
        super.onCreate(bundle);
        com.carbridge.app.MainActivity mainActivity = sAlive;
        sAlive = this;
        if (mainActivity != null && mainActivity != this) {
            mainActivity.finish();
        }
        installCrashProbe();
        com.carbridge.carplay.audio.CarAudioRouter.restoreIfMutedOnStart(this, new com.carbridge.carplay.audio.CarAudioRouter.Log() { // from class: com.carbridge.app.MainActivity.1
            public void onLog(java.lang.String str) {
                MainActivity.this.log(str);
            }
        });
        setContentView(R.layout.activity_main);
        this.mainScroll = (android.widget.ScrollView) findViewById(R.id.main_scroll);
        final android.view.View viewFindViewById = findViewById(R.id.header_divider);
        if (this.mainScroll != null && viewFindViewById != null) {
            this.mainScroll.setOnScrollChangeListener(new android.view.View.OnScrollChangeListener() { // from class: com.carbridge.app.MainActivity.2

                @Override // android.view.View.OnScrollChangeListener
                public void onScrollChange(android.view.View view, int i, int i2, int i3, int i4) {
                    viewFindViewById.setVisibility(i2 > MainActivity.this.dp(2.0f) ? 0 : 4);
                }
            });
        }
        com.carbridge.carplay.core.AirPlaySession.setIdentityStore(new com.carbridge.carplay.AndroidIdentityStore(this));
        getWindow().addFlags(128);
        applyWindowInsets();
        requestRuntimePermissions();
        startCarPlayBeacon();
        log("carbridge v0.1.1 启动，日志文件: " + logFile().getAbsolutePath());
        this.stateView = (android.widget.TextView) findViewById(R.id.state);
        installCrashLogger();
        try {
            android.content.SharedPreferences sharedPreferences = getSharedPreferences("guide", 0);
            if (!sharedPreferences.getBoolean("done", false)) {
                sharedPreferences.edit().putBoolean("done", true).apply();
                startActivity(new android.content.Intent(this, (java.lang.Class<?>) com.carbridge.app.GuideWizardActivity.class));
            }
        } catch (java.lang.Throwable th) {
            com.carbridge.carplay.util.Diag.w("guide", th);
        }
        this.detailView = (android.widget.TextView) findViewById(R.id.detail);
        this.elapsedView = (android.widget.TextView) findViewById(R.id.elapsed);
        this.logView = (android.widget.TextView) findViewById(R.id.log);
        this.logView.setOnLongClickListener(new android.view.View.OnLongClickListener() { // from class: com.carbridge.app.MainActivity.3
            @Override // android.view.View.OnLongClickListener
            public boolean onLongClick(android.view.View view) {
                android.content.ClipboardManager clipboardManager = (android.content.ClipboardManager) MainActivity.this.getSystemService("clipboard");
                java.lang.String strAll = MainActivity.this.sessionLog().all();
                if (clipboardManager != null) {
                    clipboardManager.setPrimaryClip(android.content.ClipData.newPlainText("carbridge log", strAll));
                    MainActivity.this.toastShort("日志已复制（" + strAll.length() + " 字）");
                    return true;
                }
                return true;
            }
        });
        this.btnConnect = (android.widget.TextView) findViewById(R.id.btn_connect);
        this.logHeader = (android.widget.TextView) findViewById(R.id.log_header);
        this.logBody = findViewById(R.id.log_body);
        this.logScroll = (android.widget.ScrollView) findViewById(R.id.log_scroll);
        this.badgeView = (android.widget.TextView) findViewById(R.id.badge);
        this.metricFps = (android.widget.TextView) findViewById(R.id.metric_fps);
        this.metricResolution = (android.widget.TextView) findViewById(R.id.metric_resolution);
        this.metricBitrate = (android.widget.TextView) findViewById(R.id.metric_bitrate);
        this.stepsContainer = (android.widget.LinearLayout) findViewById(R.id.steps_container);
        this.stepsProgress = (android.widget.ProgressBar) findViewById(R.id.steps_progress);
        this.stepsProgressText = (android.widget.TextView) findViewById(R.id.steps_progress_text);
        buildStepRows();
        android.widget.TextView textView = (android.widget.TextView) findViewById(R.id.about_version);
        if (textView != null) {
            try {
                textView.setText(getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
            } catch (java.lang.Throwable th2) {
                com.carbridge.carplay.util.Diag.w("MainActivity", th2);
            }
        }
        this.idBtName = (android.widget.TextView) findViewById(R.id.id_bt_name);
        this.idBtMac = (android.widget.TextView) findViewById(R.id.id_bt_mac);
        this.idSdp = (android.widget.TextView) findViewById(R.id.id_sdp);
        this.idSrcvers = (android.widget.TextView) findViewById(R.id.id_srcvers);
        this.identityHint = findViewById(R.id.identity_hint);
        android.view.View viewFindViewById2 = findViewById(R.id.row_bt_mac);
        if (viewFindViewById2 != null) {
            viewFindViewById2.setOnClickListener(new android.view.View.OnClickListener() { // from class: com.carbridge.app.MainActivity.4
                @Override // android.view.View.OnClickListener
                public void onClick(android.view.View view) {
                    MainActivity.this.showMacDialog();
                }
            });
        }
        android.view.View viewFindViewById3 = findViewById(R.id.row_srcvers);
        if (viewFindViewById3 != null) {
            viewFindViewById3.setOnClickListener(new android.view.View.OnClickListener() { // from class: com.carbridge.app.MainActivity.5
                @Override // android.view.View.OnClickListener
                public void onClick(android.view.View view) {
                    MainActivity.this.showSrcversDialog();
                }
            });
        }
        refreshIdentity();
        setupCollapse(R.id.guide_header, R.id.guide_body, R.string.guide_title);
        setupCollapse(R.id.quality_header, R.id.quality_body, R.string.quality_title);
        android.view.View viewFindViewById4 = findViewById(R.id.btn_export);
        if (viewFindViewById4 != null) {
            viewFindViewById4.setOnClickListener(new android.view.View.OnClickListener() { // from class: com.carbridge.app.MainActivity.6
                @Override // android.view.View.OnClickListener
                public void onClick(android.view.View view) {
                    MainActivity.this.exportDiagPack();
                }
            });
        }
        this.autoReconnectRow = (android.widget.TextView) findViewById(R.id.row_autoreconnect);
        if (this.autoReconnectRow != null) {
            refreshAutoReconnectRow();
            this.autoReconnectRow.setOnClickListener(new android.view.View.OnClickListener() { // from class: com.carbridge.app.MainActivity.7
                @Override // android.view.View.OnClickListener
                public void onClick(android.view.View view) {
                    boolean z = !MainActivity.this.autoReconnectEnabled();
                    MainActivity.this.getSharedPreferences(com.carbridge.app.MainActivity.PREF_NAME, 0).edit().putBoolean("auto_reconnect", z).apply();
                    MainActivity.this.refreshAutoReconnectRow();
                    MainActivity.this.log("自动重连：" + (z ? "开" : "关"));
                }
            });
        }
        setupLogCollapse();
        this.pages[0] = findViewById(R.id.page_ops);
        this.pages[1] = findViewById(R.id.page_progress);
        this.tabs[0] = (android.widget.TextView) findViewById(R.id.tab_ops);
        this.tabs[1] = (android.widget.TextView) findViewById(R.id.tab_progress);
        for (int i = 0; i < this.tabs.length; i++) {
            final int i2 = i;
            this.tabs[i].setOnClickListener(new android.view.View.OnClickListener() { // from class: com.carbridge.app.MainActivity.8

                @Override // android.view.View.OnClickListener
                public void onClick(android.view.View view) {
                    MainActivity.this.showPage(i2);
                }
            });
        }
        showPage(0);
        setupQualitySettings();
        this.stateView.setTag(findViewById(R.id.dot));
        this.btnConnect.setOnClickListener(new android.view.View.OnClickListener() { // from class: com.carbridge.app.MainActivity.9
            @Override // android.view.View.OnClickListener
            public void onClick(android.view.View view) {
                MainActivity.this.toggleConnect();
            }
        });
        this.btnCarPlay = (android.widget.TextView) findViewById(R.id.btn_carplay);
        this.btnCarPlay.setOnClickListener(new android.view.View.OnClickListener() { // from class: com.carbridge.app.MainActivity.10
            @Override // android.view.View.OnClickListener
            public void onClick(android.view.View view) {
                MainActivity.this.toggleCarPlay();
            }
        });
        getSharedPreferences(PREF_NAME, 0);
        android.widget.TextView textView2 = (android.widget.TextView) findViewById(R.id.btn_selfcheck);
        if (textView2 != null) {
            textView2.setOnClickListener(new android.view.View.OnClickListener() { // from class: com.carbridge.app.MainActivity.11
                @Override // android.view.View.OnClickListener
                public void onClick(android.view.View view) {
                    MainActivity.this.runConnectionSelfCheck();
                }
            });
        }
        android.widget.TextView textView3 = (android.widget.TextView) findViewById(R.id.btn_autostart);
        if (textView3 != null) {
            textView3.setOnClickListener(new android.view.View.OnClickListener() { // from class: com.carbridge.app.MainActivity.12
                @Override // android.view.View.OnClickListener
                public void onClick(android.view.View view) {
                    MainActivity.this.openAutostartSettings();
                }
            });
        }
        findViewById(R.id.btn_copy).setOnClickListener(new android.view.View.OnClickListener() { // from class: com.carbridge.app.MainActivity.13
            @Override // android.view.View.OnClickListener
            public void onClick(android.view.View view) {
                MainActivity.this.copyLog();
            }
        });
        findViewById(R.id.btn_share).setOnClickListener(new android.view.View.OnClickListener() { // from class: com.carbridge.app.MainActivity.14
            @Override // android.view.View.OnClickListener
            public void onClick(android.view.View view) {
                MainActivity.this.shareLog();
            }
        });
        runStartupDiagnostics();
        ensureBridge();
        try {
            com.carbridge.carplay.core.AirPlaySetup.setDeviceIdentity(com.carbridge.carplay.bt.BtIap2Server.realBluetoothAddress(this));
        } catch (java.lang.Throwable th3) {
            com.carbridge.carplay.util.Diag.w("MainActivity", th3);
        }
        try {
            com.carbridge.carplay.CarPlayCtrlCompat.apply(this);
        } catch (java.lang.Throwable th4) {
            com.carbridge.carplay.util.Diag.w("MainActivity", th4);
        }
        this.main.postDelayed(new java.lang.Runnable() { // from class: com.carbridge.app.MainActivity.15
            @Override // java.lang.Runnable
            public void run() {
                if (MainActivity.this.carPlayTx == null) {
                    MainActivity.this.autoCarplayArmed = true;
                    com.carbridge.app.KeepAliveService.start(MainActivity.this);
                    MainActivity.this.log("先启动 CarPlay 配对/鉴权（车机侧认 iPhone）");
                    MainActivity.this.toggleCarPlay();
                }
            }
        }, 100L);
        this.main.postDelayed(new java.lang.Runnable() { // from class: com.carbridge.app.MainActivity.16
            @Override // java.lang.Runnable
            public void run() {
                if (MainActivity.this.client == null || !MainActivity.this.client.isRunning()) {
                    MainActivity.this.toggleConnect();
                }
            }
        }, 3000L);
    }

    private void setupQualitySettings() {
        android.content.SharedPreferences sharedPreferences = getSharedPreferences(PREF_NAME, 0);
        this.encWidth = clampDim(sharedPreferences.getInt(KEY_W, DEF_W));
        this.encHeight = clampDim(sharedPreferences.getInt(KEY_H, DEF_H));
        this.fpsIndex = clampIndex(sharedPreferences.getInt(KEY_FPS, DEF_FPS), FPS_OPTIONS.length);
        this.autoFit = sharedPreferences.getBoolean(KEY_AUTO_FIT, true);
        this.carW = sharedPreferences.getInt(KEY_CAR_W, 0);
        this.carH = sharedPreferences.getInt(KEY_CAR_H, 0);
        this.carFps = sharedPreferences.getInt(KEY_CAR_FPS, 0);
        this.editWidth = (android.widget.EditText) findViewById(R.id.edit_width);
        this.editHeight = (android.widget.EditText) findViewById(R.id.edit_height);
        this.qualityTargetView = (android.widget.TextView) findViewById(R.id.quality_target);
        this.switchAutoFit = (android.widget.CheckBox) findViewById(R.id.switch_auto_fit);
        if (this.editWidth != null) {
            this.editWidth.setText(java.lang.String.valueOf(this.encWidth));
        }
        if (this.editHeight != null) {
            this.editHeight.setText(java.lang.String.valueOf(this.encHeight));
        }
        android.view.View.OnFocusChangeListener onFocusChangeListener = new android.view.View.OnFocusChangeListener() { // from class: com.carbridge.app.MainActivity.17
            @Override // android.view.View.OnFocusChangeListener
            public void onFocusChange(android.view.View view, boolean z) {
                if (!z) {
                    MainActivity.this.commitResolution();
                }
            }
        };
        if (this.editWidth != null) {
            this.editWidth.setOnFocusChangeListener(onFocusChangeListener);
        }
        if (this.editHeight != null) {
            this.editHeight.setOnFocusChangeListener(onFocusChangeListener);
        }
        if (this.switchAutoFit != null) {
            this.switchAutoFit.setChecked(this.autoFit);
            this.switchAutoFit.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() { // from class: com.carbridge.app.MainActivity.18
                @Override // android.widget.CompoundButton.OnCheckedChangeListener
                public void onCheckedChanged(android.widget.CompoundButton compoundButton, boolean z) {
                    if (MainActivity.this.qualitySwitchSyncing) {
                        return;
                    }
                    MainActivity.this.autoFit = z;
                    MainActivity.this.persistQuality();
                    if (MainActivity.this.client != null) {
                        MainActivity.this.client.setAutoFit(MainActivity.this.autoFit);
                        if (MainActivity.this.autoFit && MainActivity.this.carW > 0) {
                            MainActivity.this.client.onCarDisplay(MainActivity.this.carW, MainActivity.this.carH, MainActivity.this.carFps);
                        }
                    }
                    MainActivity.this.updateQualityTargetView();
                    MainActivity.this.log("画质：车机画布自动适配" + (MainActivity.this.autoFit ? "开启" : "关闭"));
                }
            });
        }
        java.lang.String[] strArr = new java.lang.String[FPS_OPTIONS.length];
        for (int i = 0; i < FPS_OPTIONS.length; i++) {
            strArr[i] = java.lang.String.valueOf(FPS_OPTIONS[i]);
        }
        buildSegment((android.widget.LinearLayout) findViewById(R.id.seg_fps), strArr, this.fpsIndex, new com.carbridge.app.MainActivity.OnPick() { // from class: com.carbridge.app.MainActivity.19
            @Override // com.carbridge.app.MainActivity.OnPick
            public void onPick(int i2) {
                if (i2 == MainActivity.this.fpsIndex) {
                    return;
                }
                MainActivity.this.fpsIndex = i2;
                MainActivity.this.autoFit = false;
                MainActivity.this.persistQuality();
                MainActivity.this.restyleSegments();
                MainActivity.this.updateQualityTargetView();
                if (MainActivity.this.client != null) {
                    MainActivity.this.client.setAutoFit(false);
                }
                MainActivity.this.log("画质：帧率改为 @" + com.carbridge.app.MainActivity.FPS_OPTIONS[i2] + "，自动适配已关闭（下次连接生效）");
            }
        });
        updateQualityTargetView();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void commitResolution() {
        if (this.editWidth == null || this.editHeight == null) {
            return;
        }
        int dim = parseDim(this.editWidth, DEF_W);
        int dim2 = parseDim(this.editHeight, DEF_H);
        if (dim == this.encWidth && dim2 == this.encHeight) {
            return;
        }
        this.encWidth = dim;
        this.encHeight = dim2;
        this.autoFit = false;
        this.editWidth.setText(java.lang.String.valueOf(dim));
        this.editHeight.setText(java.lang.String.valueOf(dim2));
        persistQuality();
        updateQualityTargetView();
        if (this.client != null) {
            this.client.setAutoFit(false);
        }
        log("画质：分辨率改为 " + dim + "×" + dim2 + "，自动适配已关闭（下次连接生效）");
    }

    private static int parseDim(android.widget.EditText editText, int i) {
        if (editText == null) {
            return i;
        }
        return com.carbridge.app.QualityPrefs.parseDim(editText.getText() == null ? "" : editText.getText().toString(), i);
    }

    private static int clampDim(int i) {
        return com.carbridge.app.QualityPrefs.clampDim(i);
    }

    private static int clampIndex(int i, int i2) {
        return com.carbridge.app.QualityPrefs.clampIndex(i, i2);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void persistQuality() {
        getSharedPreferences(PREF_NAME, 0).edit().putInt(KEY_W, this.encWidth).putInt(KEY_H, this.encHeight).putInt(KEY_FPS, this.fpsIndex).putBoolean(KEY_AUTO_FIT, this.autoFit).apply();
    }

    private int[] encoderParamsForStart() {
        return com.carbridge.app.QualityPrefs.paramsForStart(this.autoFit, this.carW, this.carH, this.carFps, this.encWidth, this.encHeight, this.fpsIndex);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateQualityTargetView() {
        if (this.switchAutoFit != null && this.switchAutoFit.isChecked() != this.autoFit) {
            this.qualitySwitchSyncing = true;
            this.switchAutoFit.setChecked(this.autoFit);
            this.qualitySwitchSyncing = false;
        }
        if (this.qualityTargetView == null) {
            return;
        }
        if (!this.autoFit) {
            this.qualityTargetView.setText(getString(R.string.quality_target_manual, new java.lang.Object[]{java.lang.Integer.valueOf(this.encWidth), java.lang.Integer.valueOf(this.encHeight), java.lang.Integer.valueOf(FPS_OPTIONS[this.fpsIndex]), java.lang.Integer.valueOf((this.client == null || this.client.encodedWidth() <= 0) ? this.encWidth : this.client.encodedWidth()), java.lang.Integer.valueOf((this.client == null || this.client.encodedHeight() <= 0) ? this.encHeight : this.client.encodedHeight()), java.lang.Integer.valueOf((this.client == null || this.client.encodedFps() <= 0) ? FPS_OPTIONS[this.fpsIndex] : this.client.encodedFps())}));
        } else {
            if (this.carW <= 0 || this.carH <= 0) {
                this.qualityTargetView.setText(R.string.quality_target_unknown);
                return;
            }
            int[] iArrFit = com.carbridge.carplay.core.DisplayGeometry.fit(this.carW, this.carH);
            if (iArrFit == null) {
                this.qualityTargetView.setText(R.string.quality_target_unknown);
                return;
            }
            int iClampFps = com.carbridge.carplay.core.DisplayGeometry.clampFps(this.carFps);
            this.qualityTargetView.setText(getString(R.string.quality_target, new java.lang.Object[]{java.lang.Integer.valueOf(iArrFit[0]), java.lang.Integer.valueOf(iArrFit[1]), java.lang.Integer.valueOf(iClampFps), java.lang.Integer.valueOf((this.client == null || this.client.encodedWidth() <= 0) ? iArrFit[0] : this.client.encodedWidth()), java.lang.Integer.valueOf((this.client == null || this.client.encodedHeight() <= 0) ? iArrFit[1] : this.client.encodedHeight()), java.lang.Integer.valueOf((this.client == null || this.client.encodedFps() <= 0) ? iClampFps : this.client.encodedFps())}));
        }
    }

    private void buildSegment(android.widget.LinearLayout linearLayout, java.lang.String[] strArr, int i, final com.carbridge.app.MainActivity.OnPick onPick) {
        if (linearLayout == null) {
            return;
        }
        linearLayout.removeAllViews();
        int i2 = 0;
        while (i2 < strArr.length) {
            final int i3 = i2;
            android.widget.TextView textView = new android.widget.TextView(this);
            textView.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, -1, 1.0f));
            textView.setGravity(17);
            textView.setText(strArr[i2]);
            textView.setTextSize(13.0f);
            styleSegment(textView, i2 == i);
            textView.setOnClickListener(new android.view.View.OnClickListener() { // from class: com.carbridge.app.MainActivity.20

                @Override // android.view.View.OnClickListener
                public void onClick(android.view.View view) {
                    if (onPick != null) {
                        onPick.onPick(i3);
                    }
                }
            });
            linearLayout.addView(textView);
            i2++;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void restyleSegments() {
        android.widget.LinearLayout linearLayout = (android.widget.LinearLayout) findViewById(R.id.seg_fps);
        if (linearLayout != null) {
            int i = 0;
            while (i < linearLayout.getChildCount()) {
                styleSegment((android.widget.TextView) linearLayout.getChildAt(i), i == this.fpsIndex);
                i++;
            }
        }
    }

    private void styleSegment(android.widget.TextView textView, boolean z) {
        if (z) {
            textView.setBackgroundResource(R.drawable.bg_seg_selected);
            textView.setTextColor(getResources().getColor(R.color.ink, getTheme()));
            textView.setTypeface(null, 1);
        } else {
            textView.setBackground(null);
            textView.setTextColor(getResources().getColor(R.color.text_dim, getTheme()));
            textView.setTypeface(null, 0);
        }
    }

    private com.carbridge.carplay.bridge.CarPlayBridge ensureBridge() {
        if (this.carPlay == null) {
            this.carPlay = new com.carbridge.carplay.bridge.CarPlayBridge(this);
            try {
                this.carPlay.setSplash(new com.carbridge.carplay.core.SplashFrame(android.graphics.BitmapFactory.decodeResource(getResources(), R.drawable.carplus_splash_bg), android.graphics.BitmapFactory.decodeResource(getResources(), R.drawable.carplus_logo), getString(R.string.splash_brand), getString(R.string.splash_subtitle)));
            } catch (java.lang.Throwable th) {
                log("开屏画面初始化失败（不影响主链路）: " + th);
            }
        }
        return this.carPlay;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void toggleConnect() {
        if (this.client != null && this.client.isRunning()) {
            this.userWantsConnected = false;
            this.retryStep = 0;
            this.client.stop();
            this.client = null;
            this.autoCarplayArmed = false;
            setConnectButton(false);
            setStateDot(C_IDLE);
            if (this.badgeView != null) {
                this.badgeView.setVisibility(8);
            }
            com.carbridge.app.KeepAliveService.stop(this);
            log("已停止连接保活");
            return;
        }
        this.userWantsConnected = true;
        resetCarlifeSteps();
        this.client = new com.carbridge.app.HuClient(this, this);
        commitResolution();
        int[] iArrEncoderParamsForStart = encoderParamsForStart();
        this.client.setAutoFit(this.autoFit);
        this.client.setEncoderSize(iArrEncoderParamsForStart[0], iArrEncoderParamsForStart[1], iArrEncoderParamsForStart[DEF_FPS]);
        if (this.carW > 0) {
            this.client.onCarDisplay(this.carW, this.carH, this.carFps);
        }
        log("编码参数目标 " + iArrEncoderParamsForStart[0] + "×" + iArrEncoderParamsForStart[1] + "@" + iArrEncoderParamsForStart[DEF_FPS] + (this.autoFit ? "（自动适配车机画布）" : "（手动）"));
        this.client.setAutoLaunch(true);
        this.autoCarplayArmed = true;
        this.carplayManualStop = false;
        ensureBridge();
        this.client.setCarPlayBridge(this.carPlay);
        setConnectButton(true);
        com.carbridge.app.KeepAliveService.start(this);
        ensureBatteryWhitelist();
        log("开始连接（会先拉起 Car+ 的车机通道）");
        this.client.start();
    }

    private void ensureBatteryWhitelist() {
        try {
            android.os.PowerManager powerManager = (android.os.PowerManager) getSystemService("power");
            if (powerManager == null) {
                return;
            }
            if (powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
                log("✓ 已在电池优化白名单（不进省电模式）");
                return;
            }
            log("申请电池优化白名单：请在弹出的对话框里选「允许」，否则锁屏后会被省电策略断开");
            android.content.Intent intent = new android.content.Intent("android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", android.net.Uri.parse("package:" + getPackageName()));
            intent.addFlags(268435456);
            startActivity(intent);
        } catch (java.lang.Throwable th) {
            log("直接拉白名单失败（" + th + "），跳到省电设置页手动添加");
            try {
                startActivity(new android.content.Intent("android.settings.BATTERY_SAVER_SETTINGS").addFlags(268435456));
            } catch (java.lang.Throwable th2) {
                com.carbridge.carplay.util.Diag.w("MainActivity", th2);
            }
        }
    }

    @Override // com.carbridge.app.HuClient.Sink
    public void onState(final com.carbridge.app.HuClient.State state, final java.lang.String str) {
        this.main.post(new java.lang.Runnable() { // from class: com.carbridge.app.MainActivity.21

            @Override // java.lang.Runnable
            public void run() {
                int i;
                boolean z;
                MainActivity.this.trackState(state);
                com.carbridge.app.StatusView statusViewOf = com.carbridge.app.StatusView.of(com.carbridge.app.MainActivity.kindOf(state));
                MainActivity.this.markBusy(statusViewOf.busy);
                java.lang.String string = MainActivity.this.getString(com.carbridge.app.MainActivity.stateTextRes(statusViewOf.kind));
                if (statusViewOf.tone == com.carbridge.app.StatusView.Tone.WORK) {
                    i = com.carbridge.app.MainActivity.C_WORK;
                } else if (statusViewOf.tone == com.carbridge.app.StatusView.Tone.OK) {
                    i = com.carbridge.app.MainActivity.C_OK;
                } else {
                    i = statusViewOf.tone == com.carbridge.app.StatusView.Tone.ERR ? com.carbridge.app.MainActivity.C_ERR : com.carbridge.app.MainActivity.C_IDLE;
                }
                int i2 = i;
                if (statusViewOf.releaseButton) {
                    MainActivity.this.setConnectButton(false);
                }
                if (statusViewOf.resetSteps) {
                    MainActivity.this.resetCarlifeSteps();
                }
                MainActivity.this.stateView.setText(string);
                MainActivity.this.setStateDot(i2);
                if (statusViewOf.manualHint) {
                    MainActivity.this.detailView.setText(MainActivity.this.getString(R.string.hint_manual));
                    z = true;
                } else {
                    boolean z2 = (str == null || str.isEmpty() || (str != null && str.equals(string))) ? false : true;
                    MainActivity.this.detailView.setText(z2 ? str : "");
                    z = z2;
                }
                MainActivity.this.detailView.setVisibility(z ? 0 : 8);
                android.view.View viewFindViewById = MainActivity.this.findViewById(R.id.detail_divider);
                if (viewFindViewById != null) {
                    viewFindViewById.setVisibility(z ? 0 : 8);
                }
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setStateDot(int i) {
        android.view.View view = (android.view.View) this.stateView.getTag();
        if (view != null && view.getBackground() != null) {
            android.graphics.drawable.GradientDrawable gradientDrawable = (android.graphics.drawable.GradientDrawable) view.getBackground().mutate();
            gradientDrawable.setColor(i);
            view.setBackground(gradientDrawable);
        }
    }

    @Override // com.carbridge.app.HuClient.Sink
    public void onLog(java.lang.String str) {
        log(str);
    }

    @Override // com.carbridge.app.HuClient.Sink
    public void onVideo(byte[] bArr, int i, int i2) {
        trackMetrics(i);
    }

    @Override // com.carbridge.app.HuClient.Sink
    public void onPhoneInfo(java.lang.String str) {
        this.phoneInfo = str;
    }

    @Override // com.carbridge.app.HuClient.Sink
    public void onMetrics(int i, long j, long j2) {
        this.firstFrameDelayMs = j2;
        this.main.post(new java.lang.Runnable() { // from class: com.carbridge.app.MainActivity.22
            @Override // java.lang.Runnable
            public void run() {
                MainActivity.this.updateMetrics();
            }
        });
    }

    @Override // com.carbridge.app.HuClient.Sink
    public void onEncoderInfo(int i, int i2, int i3, int i4, int i5, int i6) {
        this.main.post(new java.lang.Runnable() { // from class: com.carbridge.app.MainActivity.23
            @Override // java.lang.Runnable
            public void run() {
                MainActivity.this.updateQualityTargetView();
                MainActivity.this.updateMetrics();
            }
        });
    }

    @Override // com.carbridge.app.HuClient.Sink
    public void onPhoneCallState(final boolean z) {
        this.main.post(new java.lang.Runnable() { // from class: com.carbridge.app.MainActivity.24

            @Override // java.lang.Runnable
            public void run() {
                com.carbridge.carplay.CarPlayWirelessTx carPlayWirelessTx = MainActivity.this.carPlayTx;
                if (carPlayWirelessTx != null) {
                    carPlayWirelessTx.reportCallState(z);
                }
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public com.carbridge.app.SessionLog sessionLog() {
        if (this.sessionLog == null) {
            this.sessionLog = new com.carbridge.app.SessionLog(this);
        }
        return this.sessionLog;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void log(java.lang.String str) {
        java.lang.String str2;
        android.util.Log.i("carbridge", str);
        synchronized (this.clock) {
            str2 = this.clock.format(new java.util.Date()) + "  " + str;
        }
        sessionLog().append(str2);
        scheduleLogUiRefresh();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void markBusy(boolean z) {
        this.busySince = z ? java.lang.System.currentTimeMillis() : 0L;
        this.main.removeCallbacks(this.elapsedTick);
        this.main.post(this.elapsedTick);
    }

    private void installCrashLogger() {
        final java.lang.Thread.UncaughtExceptionHandler defaultUncaughtExceptionHandler = java.lang.Thread.getDefaultUncaughtExceptionHandler();
        java.lang.Thread.setDefaultUncaughtExceptionHandler(new java.lang.Thread.UncaughtExceptionHandler() { // from class: com.carbridge.app.MainActivity.26

            @Override // java.lang.Thread.UncaughtExceptionHandler
            public void uncaughtException(java.lang.Thread thread, java.lang.Throwable th) {
                try {
                    java.io.StringWriter stringWriter = new java.io.StringWriter();
                    th.printStackTrace(new java.io.PrintWriter(stringWriter));
                    MainActivity.this.log("✗ 未捕获异常 [" + thread.getName() + "] " + th + "\n" + stringWriter);
                } catch (java.lang.Throwable th2) {
                    com.carbridge.carplay.util.Diag.w("MainActivity", th2);
                }
                if (defaultUncaughtExceptionHandler != null) {
                    defaultUncaughtExceptionHandler.uncaughtException(thread, th);
                }
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void trackState(com.carbridge.app.HuClient.State state) {
        long jCurrentTimeMillis = java.lang.System.currentTimeMillis();
        if (this.lastStateAtMs > 0 && this.lastStateLabel != null) {
            long j = jCurrentTimeMillis - this.lastStateAtMs;
            if (this.sessionTimeline.length() > 0) {
                this.sessionTimeline.append(" / ");
            }
            this.sessionTimeline.append(this.lastStateLabel).append(' ').append(java.lang.String.format(java.util.Locale.US, "%.1fs", java.lang.Double.valueOf(j / 1000.0d)));
        }
        this.lastStateAtMs = jCurrentTimeMillis;
        this.lastStateLabel = stateLabel(state);
        if (state == com.carbridge.app.HuClient.State.IDLE || state == com.carbridge.app.HuClient.State.CLOSED || state == com.carbridge.app.HuClient.State.ERROR) {
            if (this.sessionTimeline.length() > 0) {
                log("会话耗时汇总：" + ((java.lang.Object) this.sessionTimeline));
            }
            this.sessionTimeline.setLength(0);
            this.lastStateAtMs = 0L;
            this.lastStateLabel = null;
        }
        if ((state == com.carbridge.app.HuClient.State.CLOSED || state == com.carbridge.app.HuClient.State.ERROR) && this.userWantsConnected && autoReconnectEnabled()) {
            scheduleAutoReconnect();
        }
        if (state == com.carbridge.app.HuClient.State.STREAMING) {
            this.retryStep = 0;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static com.carbridge.app.StatusView.Kind kindOf(com.carbridge.app.HuClient.State state) {
        if (state == null) {
            return com.carbridge.app.StatusView.Kind.IDLE;
        }
        switch (com.carbridge.app.MainActivity.AnonymousClass52.$SwitchMap$com$carbridge$app$HuClient$State[state.ordinal()]) {
            case 1:
                return com.carbridge.app.StatusView.Kind.WAITING;
            case DEF_FPS /* 2 */:
                return com.carbridge.app.StatusView.Kind.CONNECTING;
            case 3:
                return com.carbridge.app.StatusView.Kind.HANDSHAKING;
            case 4:
                return com.carbridge.app.StatusView.Kind.STREAMING;
            case 5:
                return com.carbridge.app.StatusView.Kind.ERROR;
            case 6:
                return com.carbridge.app.StatusView.Kind.CLOSED;
            default:
                return com.carbridge.app.StatusView.Kind.IDLE;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static int stateTextRes(com.carbridge.app.StatusView.Kind kind) {
        switch (com.carbridge.app.MainActivity.AnonymousClass52.$SwitchMap$com$carbridge$app$StatusView$Kind[kind.ordinal()]) {
            case 1:
                return R.string.state_waiting;
            case DEF_FPS /* 2 */:
                return R.string.state_connecting;
            case 3:
                return R.string.state_handshaking;
            case 4:
                return R.string.state_streaming;
            case 5:
                return R.string.state_error;
            case 6:
                return R.string.state_closed;
            default:
                return R.string.state_idle;
        }
    }

    private java.lang.String stateLabel(com.carbridge.app.HuClient.State state) {
        switch (com.carbridge.app.MainActivity.AnonymousClass52.$SwitchMap$com$carbridge$app$HuClient$State[state.ordinal()]) {
            case 1:
                return "等端口";
            case DEF_FPS /* 2 */:
                return "建链";
            case 3:
                return "握手";
            case 4:
                return "推流";
            case 5:
                return "失败";
            case 6:
                return "断开";
            default:
                return "空闲";
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean autoReconnectEnabled() {
        return getSharedPreferences(PREF_NAME, 0).getBoolean("auto_reconnect", true);
    }

    private void scheduleAutoReconnect() {
        if (this.retryStep >= RETRY_DELAYS.length) {
            log("自动重连：已重试 " + this.retryStep + " 次仍失败，停止自动重试（手动点「连接」可再试）");
            return;
        }
        long j = RETRY_DELAYS[this.retryStep];
        this.retryStep++;
        log("自动重连：第 " + this.retryStep + " 次，" + (j / 1000) + "s 后重试");
        this.main.postDelayed(new java.lang.Runnable() { // from class: com.carbridge.app.MainActivity.27
            @Override // java.lang.Runnable
            public void run() {
                if (MainActivity.this.userWantsConnected) {
                    if (MainActivity.this.client == null || !MainActivity.this.client.isRunning()) {
                        MainActivity.this.toggleConnect();
                    }
                }
            }
        }, j);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void refreshAutoReconnectRow() {
        if (this.autoReconnectRow == null) {
            return;
        }
        this.autoReconnectRow.setText("自动重连 · " + (autoReconnectEnabled() ? "开" : "关") + "（断开后 3/6/12/30s 退避重试）");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void exportDiagPack() {
        try {
            java.io.File file = new java.io.File(getExternalFilesDir(null), "diag");
            if (!file.isDirectory() && !file.mkdirs()) {
                throw new java.io.IOException("建目录失败 " + file);
            }
            java.io.File file2 = new java.io.File(file, "carbridge-diag-" + new java.text.SimpleDateFormat("MMdd-HHmmss", java.util.Locale.US).format(new java.util.Date()) + ".zip");
            java.util.zip.ZipOutputStream zipOutputStream = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(file2));
            try {
                put(zipOutputStream, "session.log", readAll(logFile()));
                java.lang.StringBuilder sb = new java.lang.StringBuilder();
                sb.append("时间: ").append(new java.util.Date()).append('\n');
                sb.append("机型: ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL).append(" / Android ").append(android.os.Build.VERSION.RELEASE).append('\n');
                sb.append("蓝牙名/地址: ").append(com.carbridge.carplay.bt.BtIdentity.peek(this).mac).append('\n');
                sb.append("车机识别: ").append(com.carbridge.carplay.bt.CarPlaySdpBeacon.statusLine()).append('\n');
                sb.append("广播版本 srcvers: ").append(com.carbridge.carplay.CarPlayCtrlCompat.srcvers(this)).append('\n');
                sb.append("画质自动适配: ").append(this.autoFit).append('\n');
                sb.append("车机画布: ").append(this.carW).append('x').append(this.carH).append('@').append(this.carFps).append('\n');
                sb.append("自动重连: ").append(autoReconnectEnabled()).append('\n');
                if (this.sessionTimeline.length() > 0) {
                    sb.append("本轮阶段耗时: ").append((java.lang.CharSequence) this.sessionTimeline).append('\n');
                }
                sb.append("静默异常计数: ").append(com.carbridge.carplay.util.Diag.dump()).append('\n');
                put(zipOutputStream, "device.txt", sb.toString());
                try { zipOutputStream.close(); } catch (Exception ignored) { }
                log("诊断包已导出：" + file2.getAbsolutePath());
                toastShort("已导出：" + file2.getName());
            } catch (java.lang.Throwable th) {
                try {
                    try { zipOutputStream.close(); } catch (Exception ignored) { }
                } catch (java.lang.Throwable th2) {
                    th.addSuppressed(th2);
                }
                throw th;
            }
        } catch (java.lang.Throwable th3) {
            log("✗ 诊断包导出失败：" + th3);
            toastShort("导出失败：" + th3.getMessage());
        }
    }

    private static void put(java.util.zip.ZipOutputStream zipOutputStream, java.lang.String str, java.lang.String str2) throws java.io.IOException {
        zipOutputStream.putNextEntry(new java.util.zip.ZipEntry(str));
        zipOutputStream.write(str2 == null ? new byte[0] : str2.getBytes("UTF-8"));
        zipOutputStream.closeEntry();
    }

    private static java.lang.String readAll(java.io.File file) {
        try {
            java.io.FileInputStream fileInputStream = new java.io.FileInputStream(file);
            try {
                byte[] bArr = new byte[(int) java.lang.Math.min(file.length(), 1000000L)];
                java.lang.String str = new java.lang.String(bArr, 0, java.lang.Math.max(fileInputStream.read(bArr), 0), "UTF-8");
                try { fileInputStream.close(); } catch (Exception ignored) { }
                return str;
            } catch (java.lang.Throwable th) {
                try {
                    try { fileInputStream.close(); } catch (Exception ignored) { }
                } catch (java.lang.Throwable th2) {
                    th.addSuppressed(th2);
                }
                throw th;
            }
        } catch (java.lang.Throwable th3) {
            return "(读取失败 " + th3 + ")";
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void rememberCarProfile(java.lang.String str) {
        try {
            com.carbridge.carplay.CarProfileStore.CarProfile carProfile = new com.carbridge.carplay.CarProfileStore.CarProfile();
            carProfile.srcvers = com.carbridge.carplay.CarPlayCtrlCompat.srcvers(this);
            carProfile.autoFit = this.autoFit;
            carProfile.targetW = this.carW;
            carProfile.targetH = this.carH;
            carProfile.targetFps = this.carFps;
            carProfile.lastSeenAt = java.lang.System.currentTimeMillis();
            com.carbridge.carplay.CarProfileStore.save(this, str, carProfile);
            log("已记录车机配置：" + str + " · srcvers=" + carProfile.srcvers + " · 画布 " + this.carW + "x" + this.carH + "@" + this.carFps);
        } catch (java.lang.Throwable th) {
            com.carbridge.carplay.util.Diag.w("CarProfile", th);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void toastShort(java.lang.String str) {
        try {
            android.widget.Toast.makeText(this, str, 0).show();
        } catch (java.lang.Throwable th) {
            com.carbridge.carplay.util.Diag.w("MainActivity", th);
        }
    }

    private void scheduleLogUiRefresh() {
        if (!this.logUiPending.compareAndSet(false, true)) {
            return;
        }
        this.main.postDelayed(new java.lang.Runnable() { // from class: com.carbridge.app.MainActivity.28
            @Override // java.lang.Runnable
            public void run() {
                MainActivity.this.logUiPending.set(false);
                MainActivity.this.renderLogTail();
            }
        }, 200L);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void renderLogTail() {
        this.logView.setText(sessionLog().tail(LOG_UI_TAIL));
        if (this.logOpen) {
            this.logScroll.post(new java.lang.Runnable() { // from class: com.carbridge.app.MainActivity.29
                @Override // java.lang.Runnable
                public void run() {
                    MainActivity.this.logScroll.fullScroll(130);
                }
            });
        }
    }

    private void closeLogFile() {
        if (this.sessionLog != null) {
            try { this.sessionLog.close(); } catch (Exception ignored) { }
        }
    }

    private void installCrashProbe() {
        final java.lang.Thread.UncaughtExceptionHandler defaultUncaughtExceptionHandler = java.lang.Thread.getDefaultUncaughtExceptionHandler();
        java.lang.Thread.setDefaultUncaughtExceptionHandler(new java.lang.Thread.UncaughtExceptionHandler() { // from class: com.carbridge.app.MainActivity.30

            @Override // java.lang.Thread.UncaughtExceptionHandler
            public void uncaughtException(java.lang.Thread thread, java.lang.Throwable th) {
                try {
                    java.io.FileOutputStream fileOutputStream = new java.io.FileOutputStream(MainActivity.this.logFile(), true);
                    fileOutputStream.write(("!!!! 未捕获异常，进程即将终止 thread=" + thread.getName() + " !!!!\n").getBytes("UTF-8"));
                    java.io.StringWriter stringWriter = new java.io.StringWriter();
                    th.printStackTrace(new java.io.PrintWriter(stringWriter));
                    fileOutputStream.write(stringWriter.toString().getBytes("UTF-8"));
                    fileOutputStream.write("\n".getBytes("UTF-8"));
                    fileOutputStream.flush();
                    try { fileOutputStream.close(); } catch (Exception ignored) { }
                } catch (java.lang.Throwable th2) {
                    com.carbridge.carplay.util.Diag.w("MainActivity", th2);
                }
                if (defaultUncaughtExceptionHandler != null) {
                    defaultUncaughtExceptionHandler.uncaughtException(thread, th);
                }
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public java.io.File logFile() {
        return sessionLog().file();
    }

    private java.lang.String logTailForShare() {
        try {
            java.io.File fileLogFile = logFile();
            if (fileLogFile.isFile()) {
                java.io.RandomAccessFile randomAccessFile = new java.io.RandomAccessFile(fileLogFile, "r");
                long length = randomAccessFile.length();
                int iMin = (int) java.lang.Math.min(length, 409600);
                byte[] bArr = new byte[iMin];
                randomAccessFile.seek(length - ((long) iMin));
                randomAccessFile.readFully(bArr);
                try { randomAccessFile.close(); } catch (Exception ignored) { }
                return new java.lang.String(bArr, "UTF-8");
            }
        } catch (java.lang.Throwable th) {
            com.carbridge.carplay.util.Diag.w("MainActivity", th);
        }
        return sessionLog().all();
    }

    private static java.lang.String join(java.util.List<java.lang.String> list) {
        java.lang.StringBuilder sb = new java.lang.StringBuilder();
        java.util.Iterator<java.lang.String> it = list.iterator();
        while (it.hasNext()) {
            sb.append(it.next()).append('\n');
        }
        return sb.toString();
    }

    private void setupLogCollapse() {
        if (this.logHeader == null || this.logBody == null) {
            return;
        }
        this.logHeader.setText(R.string.log_title);
        setChevron(this.logHeader, false);
        this.logHeader.setOnClickListener(new android.view.View.OnClickListener() { // from class: com.carbridge.app.MainActivity.31
            @Override // android.view.View.OnClickListener
            public void onClick(android.view.View view) {
                MainActivity.this.setLogOpen(!MainActivity.this.logOpen);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setLogOpen(boolean z) {
        this.logOpen = z;
        if (this.logBody != null) {
            this.logBody.setVisibility(z ? 0 : 8);
        }
        if (this.logHeader != null) {
            setChevron(this.logHeader, z);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void copyLog() {
        try {
            ((android.content.ClipboardManager) getSystemService("clipboard")).setPrimaryClip(android.content.ClipData.newPlainText("carbridge", logTailForShare()));
            android.widget.Toast.makeText(this, R.string.copied, 0).show();
        } catch (java.lang.Throwable th) {
            log("复制失败：" + th);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void shareLog() {
        try {
            if (!logFile().isFile()) {
                sessionLog().dumpToFile();
            }
            android.content.Intent intent = new android.content.Intent("android.intent.action.SEND");
            intent.setType("text/plain");
            intent.putExtra("android.intent.extra.STREAM", com.carbridge.app.LogProvider.uriFor(LOG_FILE_NAME));
            intent.putExtra("android.intent.extra.SUBJECT", "carbridge 会话日志");
            intent.addFlags(1);
            startActivity(android.content.Intent.createChooser(intent, getString(R.string.btn_share_log)));
        } catch (java.lang.Throwable th) {
            log("导出失败：" + th);
        }
    }

    public void onState(java.lang.String str) {
        log(str);
    }

    public void onCarPlayStreaming() {
        com.carbridge.carplay.CarPlayWirelessTx carPlayWirelessTx = this.carPlayTx;
        if (carPlayWirelessTx != null) {
            carPlayWirelessTx.markStreaming();
        }
    }

    public void onCarPlayDisplay(final int i, final int i2, final int i3) {
        this.main.post(new java.lang.Runnable() { // from class: com.carbridge.app.MainActivity.32

            @Override // java.lang.Runnable
            public void run() {
                MainActivity.this.carW = i;
                MainActivity.this.carH = i2;
                MainActivity.this.carFps = i3;
                MainActivity.this.getSharedPreferences(com.carbridge.app.MainActivity.PREF_NAME, 0).edit().putInt(com.carbridge.app.MainActivity.KEY_CAR_W, i).putInt(com.carbridge.app.MainActivity.KEY_CAR_H, i2).putInt(com.carbridge.app.MainActivity.KEY_CAR_FPS, i3).apply();
                MainActivity.this.log("车机画布已记录：" + i + "×" + i2 + "@" + i3 + (MainActivity.this.autoFit ? "（自动适配开启）" : "（自动适配关闭）"));
                MainActivity.this.updateQualityTargetView();
                com.carbridge.app.HuClient huClient = MainActivity.this.client;
                if (huClient != null) {
                    huClient.setAutoFit(MainActivity.this.autoFit);
                    huClient.onCarDisplay(i, i2, i3);
                }
            }
        });
    }

    public void onAirPlayFailed(final java.lang.String str) {
        log("CarPlay 会话失败：" + str);
        this.main.post(new java.lang.Runnable() { // from class: com.carbridge.app.MainActivity.33

            @Override // java.lang.Runnable
            public void run() {
                MainActivity.this.carPlayFailedStep = com.carbridge.carplay.CarPlayWirelessTx.Step.AIRPLAY_SESSION;
                MainActivity.this.carPlayFailedReason = str;
                MainActivity.this.renderSteps();
            }
        });
        com.carbridge.carplay.CarPlayWirelessTx carPlayWirelessTx = this.carPlayTx;
        if (carPlayWirelessTx != null) {
            carPlayWirelessTx.onAirPlaySessionFailed(str);
        }
    }

    public void onCarHardKey(int i) {
        if (this.client == null) {
            log("车机硬键：CarLife 通道还没连上，丢弃");
        } else {
            this.client.sendHardKey(i);
        }
    }

    public void onCarPlayTouch(int i, int i2, int i3) {
        if (this.client != null) {
            this.client.sendTouch(i, i2, i3);
        }
    }

    private void applyWindowInsets() {
        android.view.View viewFindViewById = findViewById(android.R.id.content);
        if (viewFindViewById == null) {
            return;
        }
        viewFindViewById.setOnApplyWindowInsetsListener(new android.view.View.OnApplyWindowInsetsListener() { // from class: com.carbridge.app.MainActivity.34
            @Override // android.view.View.OnApplyWindowInsetsListener
            public android.view.WindowInsets onApplyWindowInsets(android.view.View view, android.view.WindowInsets windowInsets) {
                int systemWindowInsetTop;
                int systemWindowInsetBottom;
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets insets = windowInsets.getInsets(android.view.WindowInsets.Type.systemBars());
                    systemWindowInsetTop = insets.top;
                    systemWindowInsetBottom = insets.bottom;
                } else {
                    // minSdk 26：getInsets(Type) 要 API 30，老设备只能走这两个已废弃的取值方法
                    @SuppressWarnings("deprecation") int top = windowInsets.getSystemWindowInsetTop();
                    @SuppressWarnings("deprecation") int bottom = windowInsets.getSystemWindowInsetBottom();
                    systemWindowInsetTop = top;
                    systemWindowInsetBottom = bottom;
                }
                view.setPadding(0, systemWindowInsetTop, 0, systemWindowInsetBottom);
                return windowInsets;
            }
        });
        viewFindViewById.requestApplyInsets();
    }

    private void requestRuntimePermissions() {
        java.util.ArrayList<java.lang.String> arrayList = new java.util.ArrayList<java.lang.String>();
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            arrayList.add("android.permission.BLUETOOTH_CONNECT");
            arrayList.add("android.permission.BLUETOOTH_SCAN");
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            arrayList.add("android.permission.NEARBY_WIFI_DEVICES");
            arrayList.add("android.permission.POST_NOTIFICATIONS");
        }
        java.util.ArrayList<String> arrayList2 = new java.util.ArrayList<String>();
        for (java.lang.String str : arrayList) {
            if (checkSelfPermission(str) != 0) {
                arrayList2.add(str);
            }
        }
        if (!arrayList2.isEmpty()) {
            requestPermissions(arrayList2.toArray(new java.lang.String[0]), 1001);
            log("申请权限：" + arrayList2);
        }
    }

    @Override // android.app.Activity
    public void onRequestPermissionsResult(int i, java.lang.String[] strArr, int[] iArr) {
        super.onRequestPermissionsResult(i, strArr, iArr);
        if (i != 1001) {
            return;
        }
        int i2 = 0;
        while (i2 < strArr.length) {
            log((i2 < iArr.length && iArr[i2] == 0 ? "✓ 已授权 " : "✗ 被拒绝 ") + strArr[i2].replace("android.permission.", ""));
            i2++;
        }
        startCarPlayBeacon();
    }

    private void startCarPlayBeacon() {
        com.carbridge.app.SdpBeaconService.start(this);
        registerBondWatcher();
        startBondPoll();
        if (com.carbridge.carplay.bt.CarPlaySdpBeacon.isStarted()) {
            log("✓ CarPlay SDP 信标已在运行（开机自启 / 前台服务常驻）");
        } else {
            android.bluetooth.BluetoothManager bluetoothManager = (android.bluetooth.BluetoothManager) getSystemService("bluetooth");
            com.carbridge.carplay.bt.CarPlaySdpBeacon.start(bluetoothManager == null ? null : bluetoothManager.getAdapter(), new com.carbridge.carplay.bt.CarPlaySdpBeacon.Log() { // from class: com.carbridge.app.MainActivity.35
                public void onLog(java.lang.String str) {
                    MainActivity.this.log(str);
                }
            });
        }
    }

    @SuppressWarnings("deprecation")   // minSdk 26：带 Class 的 getParcelableExtra 需要 API 33
    private void registerBondWatcher() {
        if (this.bondReceiver != null) {
            return;
        }
        this.bondReceiver = new android.content.BroadcastReceiver() { // from class: com.carbridge.app.MainActivity.36
            /* JADX WARN: Code duplicated, block: B:22:0x005d  */
            /* JADX WARN: Code duplicated, block: B:29:0x0085  */
            @Override // android.content.BroadcastReceiver
            public void onReceive(android.content.Context context, android.content.Intent intent) {
                java.lang.String strValueOf = null;
                java.lang.String str;
                java.lang.String address;
                int deviceClass;
                int i;
                boolean z;
                if (intent == null) {
                    return;
                }
                android.bluetooth.BluetoothDevice bluetoothDevice = (android.bluetooth.BluetoothDevice) intent.getParcelableExtra("android.bluetooth.device.extra.DEVICE");
                int intExtra = intent.getIntExtra("android.bluetooth.device.extra.BOND_STATE", -1);
                int intExtra2 = intent.getIntExtra("android.bluetooth.device.extra.PREVIOUS_BOND_STATE", -1);
                if (bluetoothDevice == null) {
                    strValueOf = "?";
                } else {
                    try {
                        strValueOf = java.lang.String.valueOf(bluetoothDevice.getName());
                    } catch (java.lang.Throwable th) {
                        str = "?";
                        address = "?";
                    }
                }
                str = strValueOf == null ? "?" : strValueOf;
                address = bluetoothDevice == null ? "?" : bluetoothDevice.getAddress();
                boolean z2 = false;
                if (bluetoothDevice == null) {
                    deviceClass = 0;
                    i = deviceClass;
                    if (((i >> 8) & 31) == 4) {
                        z = false;
                    } else {
                        z = false;
                    }
                    z2 = z;
                } else {
                    try {
                        if (bluetoothDevice.getBluetoothClass() == null) {
                            deviceClass = 0;
                        } else {
                            deviceClass = bluetoothDevice.getBluetoothClass().getDeviceClass();
                        }
                        i = deviceClass;
                        if (((i >> 8) & 31) == 4 || ((i >> com.carbridge.app.MainActivity.DEF_FPS) & 63) != com.carbridge.app.MainActivity.DEF_FPS) {
                            z = false;
                        } else {
                            z = true;
                        }
                        z2 = z;
                    } catch (java.lang.Throwable th2) {
                        com.carbridge.carplay.util.Diag.w("MainActivity", th2);
                    }
                }
                java.lang.String str2 = (z2 ? "车机" : "蓝牙设备") + " " + str + " [" + address + "]";
                if (intExtra == 11) {
                    MainActivity.this.log("蓝牙配对中：" + str2 + "（此刻信标" + (com.carbridge.carplay.bt.CarPlaySdpBeacon.isStarted() ? "已在" : "**没在**") + "跑；车机会在这次配对里对 SDP）");
                    return;
                }
                if (intExtra != 12) {
                    if (intExtra == 10) {
                        MainActivity.this.log("蓝牙配对已解除：" + str2 + "（" + com.carbridge.app.MainActivity.describeBond(intExtra2) + " → BOND_NONE）");
                        return;
                    }
                    return;
                }
                boolean zIsStarted = com.carbridge.carplay.bt.CarPlaySdpBeacon.isStarted();
                MainActivity.this.log("✓ 蓝牙配对成功：" + str2 + "（" + com.carbridge.app.MainActivity.describeBond(intExtra2) + " → BONDED）");
                if (zIsStarted) {
                    MainActivity.this.log("  信标在配对时是挂着的 → 车机已把 2d8d2466-e14d-451c-88bc-7301abea291a + 00000000-deca-fade-deca-deafdecacafe 记进它的 SDP 缓存");
                    MainActivity.this.log("  下一步：车机桌面点「Apple CarPlay」（灰色也能点），等它回连本机");
                } else {
                    MainActivity.this.log("  ✗ 但配对时信标**没在跑** → 车机这次很可能没学到那两条 UUID！");
                    MainActivity.this.log("  处理：车机蓝牙里删掉本机 → App 里点「重新配对车机」→ 再配一次");
                }
            }
        };
        android.content.IntentFilter intentFilter = new android.content.IntentFilter("android.bluetooth.device.action.BOND_STATE_CHANGED");
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(this.bondReceiver, intentFilter, 4);
            } else {
                registerReceiver(this.bondReceiver, intentFilter);
            }
        } catch (java.lang.Throwable th) {
            this.bondReceiver = null;
            log("配对状态监听注册失败: " + th);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static java.lang.String describeBond(int i) {
        switch (i) {
            case 10:
                return "NONE";
            case 11:
                return "BONDING";
            case 12:
                return "BONDED";
            default:
                return "state=" + i;
        }
    }

    private void startBondPoll() {
        if (this.bondPolling) {
            return;
        }
        this.bondPolling = true;
        this.lastBondedCarAddress = currentBondedCarAddress();
        if (this.lastBondedCarAddress != null) {
            log("车机已在配对列表里；上车后到车机桌面点「Apple CarPlay」");
        }
        this.bondPoll.postDelayed(this.bondPollTask, 1500L);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void keepBeaconAlive(java.lang.String str) {
        try {
            android.bluetooth.BluetoothManager bluetoothManager = (android.bluetooth.BluetoothManager) getSystemService("bluetooth");
            if (!com.carbridge.carplay.bt.CarPlaySdpBeacon.reviveIfDead(bluetoothManager == null ? null : bluetoothManager.getAdapter(), new com.carbridge.carplay.bt.CarPlaySdpBeacon.Log() { // from class: com.carbridge.app.MainActivity.38
                public void onLog(java.lang.String str2) {
                    MainActivity.this.log(str2);
                }
            })) {
                log("⚠ SDP 信标未能挂上（" + str + "），车机会认不出本机");
            }
        } catch (java.lang.Throwable th) {
            com.carbridge.carplay.util.Diag.w("MainActivity", th);
        }
    }

    private java.lang.String currentBondedCarAddress() {
        try {
            android.bluetooth.BluetoothDevice bluetoothDeviceFindCar = com.carbridge.carplay.bt.CarPairing.findCar();
            if (bluetoothDeviceFindCar == null) {
                return null;
            }
            return bluetoothDeviceFindCar.getAddress();
        } catch (java.lang.Throwable th) {
            return null;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void checkBondedCar() {
        java.lang.String strValueOf = null;
        try {
            android.bluetooth.BluetoothDevice bluetoothDeviceFindCar = com.carbridge.carplay.bt.CarPairing.findCar();
            if (bluetoothDeviceFindCar == null) {
                this.lastBondedCarAddress = null;
                return;
            }
            java.lang.String address = bluetoothDeviceFindCar.getAddress();
            if (address.equals(this.lastBondedCarAddress)) {
                return;
            }
            this.lastBondedCarAddress = address;
            try {
                strValueOf = java.lang.String.valueOf(bluetoothDeviceFindCar.getName());
            } catch (java.lang.Throwable th) {
                strValueOf = "车机";
            }
            log("✓ 已和车机配对：" + strValueOf + " [" + address + "]");
            log("  下一步：到车机桌面点「Apple CarPlay」图标（车机不会自己弹出来）");
            showCarPairedNotification(strValueOf);
        } catch (java.lang.Throwable th2) {
            com.carbridge.carplay.util.Diag.w("MainActivity", th2);
        }
    }

    private void showCarPairedNotification(java.lang.String str) {
        try {
            android.app.NotificationManager notificationManager = (android.app.NotificationManager) getSystemService("notification");
            if (notificationManager == null) {
                return;
            }
            if (android.os.Build.VERSION.SDK_INT >= 26 && notificationManager.getNotificationChannel("carplay_pair") == null) {
                android.app.NotificationChannel notificationChannel = new android.app.NotificationChannel("carplay_pair", "车机配对提示", 3);
                notificationChannel.setDescription("车机配对成功后提示下一步");
                notificationChannel.setShowBadge(false);
                notificationManager.createNotificationChannel(notificationChannel);
            }
            notificationManager.notify(2001, new android.app.Notification.Builder(this, "carplay_pair").setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setContentTitle("车机已配对" + (str == null ? "" : "：" + str)).setContentText("现在到车机桌面点「Apple CarPlay」").setStyle(new android.app.Notification.BigTextStyle().bigText("车机已配对。现在到车机桌面点「Apple CarPlay」图标——这个入口不会自己弹出来，必须手动点一次。")).setContentIntent(android.app.PendingIntent.getActivity(this, 0, new android.content.Intent(this, (java.lang.Class<?>) com.carbridge.app.MainActivity.class).addFlags(536870912), 201326592)).setAutoCancel(true).build());
        } catch (java.lang.Throwable th) {
            com.carbridge.carplay.util.Diag.w("MainActivity", th);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void runBtDiag() {
        log("── 蓝牙诊断开始 ──");
        com.carbridge.app.BtDiag.run(this, new com.carbridge.app.BtDiag.Log() { // from class: com.carbridge.app.MainActivity.39
            public void onLog(java.lang.String str) {
                MainActivity.this.log(str);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void runConnectionSelfCheck() {
        log("—— 连接自检 ——");
        for (java.lang.String str : com.carbridge.carplay.bt.CarPlaySdpBeacon.diagnose(com.carbridge.carplay.bt.Bt.adapter(this)).split("\n")) {
            log(str);
        }
        try {
            android.os.PowerManager powerManager = (android.os.PowerManager) getSystemService("power");
            if (powerManager != null && powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
                log("✓ 省电白名单：已免省电");
            } else {
                log("✗ 省电白名单：未豁免，锁屏可能被省电冻结 → 点「后台运行设置」授权");
            }
        } catch (java.lang.Throwable th) {
            log("省电白名单检查失败：" + th);
        }
        try {
            log(android.os.Build.VERSION.SDK_INT < 33 || ((android.app.NotificationManager) getSystemService("notification")).areNotificationsEnabled() ? "✓ 通知权限：已允许" : "✗ 通知权限：被关（前台服务通知不显示，可能被系统判为可疑）");
        } catch (java.lang.Throwable th2) {
            log("通知权限检查失败：" + th2);
        }
        log(com.carbridge.carplay.bt.CarPlaySdpBeacon.isStarted() ? "✓ 信标前台服务：在跑" : "✗ 信标前台服务：没跑");
        try {
            log(android.os.Build.VERSION.SDK_INT < 31 || ((android.app.AlarmManager) getSystemService("alarm")).canScheduleExactAlarms() ? "✓ 精确闹钟权限：已允许（看门狗准时醒）" : "✗ 精确闹钟权限：未允许（省电模式下看门狗可能延迟几分钟才醒），点「自启动设置」开「闹钟和提醒」");
        } catch (java.lang.Throwable th3) {
            log("精确闹钟权限检查失败：" + th3);
        }
        log("—— 自检结束 ——（缺项按上面提示补，或点「后台运行设置」「自启动设置」）");
        setLogOpen(true);
        android.widget.Toast.makeText(this, R.string.selfcheck_done, 0).show();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void openAutostartSettings() {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            android.app.AlarmManager alarmManager = (android.app.AlarmManager) getSystemService("alarm");
            if (alarmManager != null && !alarmManager.canScheduleExactAlarms()) {
                startActivity(new android.content.Intent("android.settings.REQUEST_SCHEDULE_EXACT_ALARM", android.net.Uri.parse("package:" + getPackageName())));
                log("已打开「闹钟和提醒」设置：请允许本 App 用闹钟（看门狗靠它准时醒）");
                return;
            }
        }
        try {
            startActivity(new android.content.Intent("android.settings.APPLICATION_DETAILS_SETTINGS", android.net.Uri.parse("package:" + getPackageName())));
            log("已打开应用详情：请在「耗电管理/自启动」里允许后台运行和自启动");
        } catch (java.lang.Throwable th) {
            try {
                startActivity(new android.content.Intent("android.settings.SETTINGS"));
                log("打开应用详情失败（" + th + "），已打开系统设置，请手动找到本 App 允许自启动");
            } catch (java.lang.Throwable th2) {
                log("打开设置失败：" + th2);
            }
        }
    }

    private void runStartupDiagnostics() {
        try {
            android.content.Intent intent = getIntent();
            if (intent == null) {
                return;
            }
            final android.content.Context applicationContext = getApplicationContext();
            java.lang.String stringExtra = intent.getStringExtra("diag");
            if (stringExtra == null || this.diagRunning) {
                return;
            }
            this.diagRunning = true;
            if ("uuid".equals(stringExtra)) {
                this.main.postDelayed(new java.lang.Runnable() { // from class: com.carbridge.app.MainActivity.40
                    @Override // java.lang.Runnable
                    public void run() {
                        MainActivity.this.runBtDiag();
                    }
                }, 1500L);
            } else if ("eir".equals(stringExtra)) {
                this.main.postDelayed(new java.lang.Runnable() { // from class: com.carbridge.app.MainActivity.41

                    @Override // java.lang.Runnable
                    public void run() {
                        com.carbridge.app.BtDiag.scanEirOnly(applicationContext, new com.carbridge.app.BtDiag.Log() { // from class: com.carbridge.app.MainActivity.41.1
                            public void onLog(java.lang.String str) {
                                com.carbridge.app.MainActivity.this.log(str);
                            }
                        });
                    }
                }, 1500L);
            }
        } catch (java.lang.Throwable th) {
            com.carbridge.carplay.util.Diag.w("MainActivity", th);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void showPage(int i) {
        this.currentTab = i;
        if (this.mainScroll != null) {
            this.mainScroll.scrollTo(0, 0);
        }
        int i2 = 0;
        while (i2 < this.pages.length) {
            if (this.pages[i2] != null) {
                this.pages[i2].setVisibility(i2 == i ? 0 : 8);
            }
            if (this.tabs[i2] != null) {
                int colorCompat = getColorCompat(i2 == i ? R.color.accent : R.color.text_muted);
                this.tabs[i2].setTextColor(colorCompat);
                this.tabs[i2].setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(colorCompat));
                this.tabs[i2].setTypeface(null, i2 == i ? 1 : 0);
            }
            i2++;
        }
    }

    private void setupCollapse(int i, int i2, int i3) {
        final android.view.View viewFindViewById = findViewById(i);
        final android.view.View viewFindViewById2 = findViewById(i2);
        if (viewFindViewById == null || viewFindViewById2 == null) {
            return;
        }
        if (viewFindViewById instanceof android.widget.TextView) {
            ((android.widget.TextView) viewFindViewById).setText(i3);
            setChevron((android.widget.TextView) viewFindViewById, false);
        }
        viewFindViewById.setOnClickListener(new android.view.View.OnClickListener() { // from class: com.carbridge.app.MainActivity.42

            @Override // android.view.View.OnClickListener
            public void onClick(android.view.View view) {
                boolean z = viewFindViewById2.getVisibility() != 0;
                viewFindViewById2.setVisibility(z ? 0 : 8);
                if (viewFindViewById instanceof android.widget.TextView) {
                    MainActivity.this.setChevron((android.widget.TextView) viewFindViewById, z);
                }
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setChevron(android.widget.TextView textView, boolean z) {
        if (textView == null) {
            return;
        }
        textView.setCompoundDrawablesWithIntrinsicBounds(0, 0, z ? R.drawable.ic_chevron_down : R.drawable.ic_chevron_right, 0);
        textView.setCompoundDrawablePadding(dp(6.0f));
        textView.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(getColorCompat(R.color.text_muted)));
    }

    private void buildStepRows() {
        boolean z = true;
        for (com.carbridge.app.HuClient.Step step : com.carbridge.app.HuClient.Step.values()) {
            if (!z) {
                addStepDivider();
            }
            z = false;
            addStepRow(step.label).setTag(step);
        }
        addStepDivider();
        android.widget.TextView textView = new android.widget.TextView(this);
        textView.setLayoutParams(new android.widget.LinearLayout.LayoutParams(-1, -2));
        textView.setPadding(0, dp(16.0f), 0, dp(4.0f));
        textView.setText(R.string.section_steps_carplay);
        textView.setTextSize(12.0f);
        textView.setLetterSpacing(0.06f);
        textView.setTypeface(null, 1);
        textView.setTextColor(getColorCompat(R.color.text_muted));
        this.stepsContainer.addView(textView);
        textView.setTag("header");
        boolean z2 = true;
        for (com.carbridge.carplay.CarPlayWirelessTx.Step step2 : com.carbridge.carplay.CarPlayWirelessTx.Step.values()) {
            if (!z2) {
                addStepDivider();
            }
            z2 = false;
            addStepRow(step2.label).setTag(step2);
        }
        renderSteps();
    }

    private void addStepDivider() {
        android.view.View view = new android.view.View(this);
        android.widget.LinearLayout.LayoutParams layoutParams = new android.widget.LinearLayout.LayoutParams(-1, 1);
        layoutParams.leftMargin = dp(22.0f);
        view.setLayoutParams(layoutParams);
        view.setBackgroundColor(getColorCompat(R.color.divider));
        this.stepsContainer.addView(view);
    }

    private android.widget.LinearLayout addStepRow(java.lang.String str) {
        android.widget.LinearLayout linearLayout = new android.widget.LinearLayout(this);
        linearLayout.setOrientation(0);
        linearLayout.setGravity(16);
        linearLayout.setPadding(0, dp(6.0f), 0, dp(6.0f));
        android.widget.TextView textView = new android.widget.TextView(this);
        textView.setLayoutParams(new android.widget.LinearLayout.LayoutParams(dp(22.0f), -2));
        textView.setTextSize(13.0f);
        textView.setGravity(17);
        textView.setText("○");
        android.widget.TextView textView2 = new android.widget.TextView(this);
        textView2.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, -2, 1.0f));
        textView2.setText(str);
        textView2.setTextSize(14.0f);
        textView2.setTextColor(getColorCompat(R.color.text_dim));
        android.widget.TextView textView3 = new android.widget.TextView(this);
        textView3.setLayoutParams(new android.widget.LinearLayout.LayoutParams(-2, -2));
        textView3.setTextSize(12.0f);
        textView3.setTextColor(getColorCompat(R.color.text_muted));
        textView3.setText(R.string.step_pending);
        linearLayout.addView(textView);
        linearLayout.addView(textView2);
        linearLayout.addView(textView3);
        this.stepsContainer.addView(linearLayout);
        return linearLayout;
    }

    /* JADX INFO: loaded from: MainActivity$RowState.class */
    private static final class RowState {
        final java.lang.String mark;
        final int color;
        final java.lang.String statusText;
        final boolean done;

        RowState(java.lang.String str, int i, java.lang.String str2, boolean z) {
            this.mark = str;
            this.color = i;
            this.statusText = str2;
            this.done = z;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void renderSteps() {
        com.carbridge.app.MainActivity.RowState rowStateCarplayRowState = null;
        int i = 0;
        for (int i2 = 0; i2 < this.stepsContainer.getChildCount(); i2++) {
            android.view.View childAt = this.stepsContainer.getChildAt(i2);
            java.lang.Object tag = childAt.getTag();
            if (!"header".equals(tag) && (childAt instanceof android.widget.LinearLayout)) {
                android.widget.LinearLayout linearLayout = (android.widget.LinearLayout) childAt;
                if (tag instanceof com.carbridge.app.HuClient.Step) {
                    rowStateCarplayRowState = carlifeRowState((com.carbridge.app.HuClient.Step) tag);
                } else if (tag instanceof com.carbridge.carplay.CarPlayWirelessTx.Step) {
                    rowStateCarplayRowState = carplayRowState((com.carbridge.carplay.CarPlayWirelessTx.Step) tag);
                }
                if (rowStateCarplayRowState != null && rowStateCarplayRowState.done) {
                    i++;
                }
                android.widget.TextView textView = (android.widget.TextView) linearLayout.getChildAt(0);
                android.widget.TextView textView2 = (android.widget.TextView) linearLayout.getChildAt(1);
                android.widget.TextView textView3 = (android.widget.TextView) linearLayout.getChildAt(DEF_FPS);
                textView.setText(rowStateCarplayRowState.mark);
                textView.setTextColor(rowStateCarplayRowState.color);
                textView2.setTextColor((rowStateCarplayRowState.done || "●".equals(rowStateCarplayRowState.mark)) ? getColorCompat(R.color.text) : getColorCompat(R.color.text_dim));
                if (rowStateCarplayRowState != null && rowStateCarplayRowState.done) {
                    textView3.setText("");
                } else {
                    textView3.setText(rowStateCarplayRowState.statusText);
                    textView3.setTextColor(rowStateCarplayRowState.color);
                }
            }
        }
        updateProgress(i);
        updateDiagram();
    }

    private com.carbridge.app.MainActivity.RowState carlifeRowState(com.carbridge.app.HuClient.Step step) {
        return rowState(com.carbridge.app.StepChain.stateOf(step.ordinal(), this.currentStep == null ? null : java.lang.Integer.valueOf(this.currentStep.ordinal()), this.failedStep == null ? null : java.lang.Integer.valueOf(this.failedStep.ordinal()), com.carbridge.app.HuClient.Step.values().length - 1));
    }

    /* JADX INFO: renamed from: com.carbridge.app.MainActivity$52, reason: invalid class name */
    /* JADX INFO: loaded from: MainActivity$52.class */
    static /* synthetic */ class AnonymousClass52 {
        static final /* synthetic */ int[] $SwitchMap$com$carbridge$app$HuClient$State;
        static final /* synthetic */ int[] $SwitchMap$com$carbridge$app$StatusView$Kind;
        static final /* synthetic */ int[] $SwitchMap$com$carbridge$app$StepChain$State = new int[com.carbridge.app.StepChain.State.values().length];

        static {
            try {
                $SwitchMap$com$carbridge$app$StepChain$State[com.carbridge.app.StepChain.State.DONE.ordinal()] = 1;
            } catch (java.lang.NoSuchFieldError e) {
            }
            try {
                $SwitchMap$com$carbridge$app$StepChain$State[com.carbridge.app.StepChain.State.ACTIVE.ordinal()] = com.carbridge.app.MainActivity.DEF_FPS;
            } catch (java.lang.NoSuchFieldError e2) {
            }
            try {
                $SwitchMap$com$carbridge$app$StepChain$State[com.carbridge.app.StepChain.State.FAILED.ordinal()] = 3;
            } catch (java.lang.NoSuchFieldError e3) {
            }
            $SwitchMap$com$carbridge$app$StatusView$Kind = new int[com.carbridge.app.StatusView.Kind.values().length];
            try {
                $SwitchMap$com$carbridge$app$StatusView$Kind[com.carbridge.app.StatusView.Kind.WAITING.ordinal()] = 1;
            } catch (java.lang.NoSuchFieldError e4) {
            }
            try {
                $SwitchMap$com$carbridge$app$StatusView$Kind[com.carbridge.app.StatusView.Kind.CONNECTING.ordinal()] = com.carbridge.app.MainActivity.DEF_FPS;
            } catch (java.lang.NoSuchFieldError e5) {
            }
            try {
                $SwitchMap$com$carbridge$app$StatusView$Kind[com.carbridge.app.StatusView.Kind.HANDSHAKING.ordinal()] = 3;
            } catch (java.lang.NoSuchFieldError e6) {
            }
            try {
                $SwitchMap$com$carbridge$app$StatusView$Kind[com.carbridge.app.StatusView.Kind.STREAMING.ordinal()] = 4;
            } catch (java.lang.NoSuchFieldError e7) {
            }
            try {
                $SwitchMap$com$carbridge$app$StatusView$Kind[com.carbridge.app.StatusView.Kind.ERROR.ordinal()] = 5;
            } catch (java.lang.NoSuchFieldError e8) {
            }
            try {
                $SwitchMap$com$carbridge$app$StatusView$Kind[com.carbridge.app.StatusView.Kind.CLOSED.ordinal()] = 6;
            } catch (java.lang.NoSuchFieldError e9) {
            }
            $SwitchMap$com$carbridge$app$HuClient$State = new int[com.carbridge.app.HuClient.State.values().length];
            try {
                $SwitchMap$com$carbridge$app$HuClient$State[com.carbridge.app.HuClient.State.WAITING_PORT.ordinal()] = 1;
            } catch (java.lang.NoSuchFieldError e10) {
            }
            try {
                $SwitchMap$com$carbridge$app$HuClient$State[com.carbridge.app.HuClient.State.CONNECTING.ordinal()] = com.carbridge.app.MainActivity.DEF_FPS;
            } catch (java.lang.NoSuchFieldError e11) {
            }
            try {
                $SwitchMap$com$carbridge$app$HuClient$State[com.carbridge.app.HuClient.State.HANDSHAKING.ordinal()] = 3;
            } catch (java.lang.NoSuchFieldError e12) {
            }
            try {
                $SwitchMap$com$carbridge$app$HuClient$State[com.carbridge.app.HuClient.State.STREAMING.ordinal()] = 4;
            } catch (java.lang.NoSuchFieldError e13) {
            }
            try {
                $SwitchMap$com$carbridge$app$HuClient$State[com.carbridge.app.HuClient.State.ERROR.ordinal()] = 5;
            } catch (java.lang.NoSuchFieldError e14) {
            }
            try {
                $SwitchMap$com$carbridge$app$HuClient$State[com.carbridge.app.HuClient.State.CLOSED.ordinal()] = 6;
            } catch (java.lang.NoSuchFieldError e15) {
            }
        }
    }

    private com.carbridge.app.MainActivity.RowState rowState(com.carbridge.app.StepChain.State state) {
        switch (com.carbridge.app.MainActivity.AnonymousClass52.$SwitchMap$com$carbridge$app$StepChain$State[state.ordinal()]) {
            case 1:
                return new com.carbridge.app.MainActivity.RowState("✓", getColorCompat(R.color.state_ok), getString(R.string.step_done), true);
            case DEF_FPS /* 2 */:
                return new com.carbridge.app.MainActivity.RowState("●", getColorCompat(R.color.state_work), getString(R.string.step_active), false);
            case 3:
                return new com.carbridge.app.MainActivity.RowState("✕", getColorCompat(R.color.state_err), getString(R.string.step_failed), false);
            default:
                return new com.carbridge.app.MainActivity.RowState("○", getColorCompat(R.color.state_idle), getString(R.string.step_pending), false);
        }
    }

    private com.carbridge.app.MainActivity.RowState carplayRowState(com.carbridge.carplay.CarPlayWirelessTx.Step step) {
        return rowState(com.carbridge.app.StepChain.stateOf(step.ordinal(), this.carPlayStep == null ? null : java.lang.Integer.valueOf(this.carPlayStep.ordinal()), this.carPlayFailedStep == null ? null : java.lang.Integer.valueOf(this.carPlayFailedStep.ordinal()), com.carbridge.carplay.CarPlayWirelessTx.Step.values().length - 1));
    }

    private void updateProgress(int i) {
        this.stepsProgress.setProgress((int) ((((long) i) * 100) / ((long) TOTAL_STEPS)));
        this.stepsProgressText.setText(i + " / " + TOTAL_STEPS);
    }

    private void updateDiagram() {
        com.carbridge.app.LinkDiagramView.Phase phase;
        if (this.linkDiagram == null) {
            return;
        }
        if (this.currentStep == com.carbridge.app.HuClient.Step.STREAMING && this.carPlayStep == com.carbridge.carplay.CarPlayWirelessTx.Step.CARPLAY_STREAM) {
            phase = com.carbridge.app.LinkDiagramView.Phase.DONE;
        } else if (this.failedStep != null || this.carPlayFailedStep != null) {
            phase = com.carbridge.app.LinkDiagramView.Phase.ERROR;
        } else if (this.currentStep != null || this.carPlayStep != null) {
            phase = com.carbridge.app.LinkDiagramView.Phase.ACTIVE;
        } else {
            phase = com.carbridge.app.LinkDiagramView.Phase.IDLE;
        }
        this.linkDiagram.setPhase(phase);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int dp(float f) {
        return java.lang.Math.round(f * getResources().getDisplayMetrics().density);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void resetCarlifeSteps() {
        this.currentStep = null;
        this.failedStep = null;
        this.failedReason = "";
        this.fpsFrames = 0;
        this.fpsWindowStart = 0L;
        this.fpsValue = 0.0f;
        this.totalBytes = 0L;
        this.firstFrameDelayMs = 0L;
        renderSteps();
        updateMetrics();
        if (this.badgeView != null) {
            this.badgeView.setVisibility(8);
        }
        log("已重置 Car+ 投屏进度");
    }

    private void resetCarPlaySteps() {
        this.carPlayStep = null;
        this.carPlayFailedStep = null;
        this.carPlayFailedReason = "";
        renderSteps();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int getColorCompat(int i) {
        return getResources().getColor(i, getTheme());
    }

    @Override // com.carbridge.app.HuClient.Sink
    public void onStep(final com.carbridge.app.HuClient.Step step) {
        this.main.post(new java.lang.Runnable() { // from class: com.carbridge.app.MainActivity.43

            @Override // java.lang.Runnable
            public void run() {
                MainActivity.this.currentStep = step;
                MainActivity.this.failedStep = null;
                MainActivity.this.renderSteps();
                if (step == com.carbridge.app.HuClient.Step.STREAMING) {
                    MainActivity.this.setBadge(R.string.badge_live, R.color.accent);
                    MainActivity.this.maybeAutoCarPlay();
                }
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void maybeAutoCarPlay() {
        if (this.autoCarplayArmed) {
            if (this.carplayManualStop) {
                this.autoCarplayArmed = false;
                return;
            }
            if (this.carPlayTx != null && this.carPlayTx.state() != com.carbridge.carplay.CarPlayWirelessTx.State.IDLE && this.carPlayTx.state() != com.carbridge.carplay.CarPlayWirelessTx.State.ERROR) {
                this.autoCarplayArmed = false;
                return;
            }
            this.autoCarplayArmed = false;
            log("Car+ 进度已走完 → 自动启动 CarPlay 推流");
            toggleCarPlay();
        }
    }

    @Override // com.carbridge.app.HuClient.Sink
    public void onStepFailed(final com.carbridge.app.HuClient.Step step, final java.lang.String str) {
        this.main.post(new java.lang.Runnable() { // from class: com.carbridge.app.MainActivity.44

            @Override // java.lang.Runnable
            public void run() {
                MainActivity.this.failedStep = step;
                MainActivity.this.failedReason = str;
                MainActivity.this.renderSteps();
                MainActivity.this.setBadge(R.string.step_failed, R.color.state_err);
                if (MainActivity.this.detailView != null) {
                    MainActivity.this.detailView.setVisibility(0);
                    android.view.View viewFindViewById = MainActivity.this.findViewById(R.id.detail_divider);
                    if (viewFindViewById != null) {
                        viewFindViewById.setVisibility(0);
                    }
                    MainActivity.this.detailView.setText("失败原因：" + str + "\n看「运行日志」最后 20 行；改完设置点「连接诊断」重试");
                }
            }
        });
    }

    private void kickIdentityProbe() {
        identity().probeInBackground();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void refreshIdentity() {
        identity().refresh();
    }

    private com.carbridge.app.IdentitySection identity() {
        if (this.identitySection == null) {
            this.identitySection = new com.carbridge.app.IdentitySection(new com.carbridge.app.IdentitySection.Host() { // from class: com.carbridge.app.MainActivity.45
                public android.app.Activity activity() {
                    return MainActivity.this;
                }

                public void log(java.lang.String str) {
                    MainActivity.this.log(str);
                }

                public int color(int i) {
                    return MainActivity.this.getColorCompat(i);
                }
            }, this.idBtName, this.idBtMac, this.idSdp, this.idSrcvers, this.identityHint);
        }
        return this.identitySection;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void showMacDialog() {
        final android.widget.EditText editText = new android.widget.EditText(this);
        editText.setSingleLine(true);
        editText.setHint("A4:50:46:1C:2D:3E");
        editText.setTextSize(16.0f);
        com.carbridge.carplay.bt.BtIdentity.Resolved resolvedPeek = com.carbridge.carplay.bt.BtIdentity.peek(this);
        if (resolvedPeek.source == com.carbridge.carplay.bt.BtIdentity.Source.MANUAL && resolvedPeek.mac != null) {
            editText.setText(resolvedPeek.mac);
            editText.setSelection(editText.getText().length());
        }
        android.widget.LinearLayout linearLayout = new android.widget.LinearLayout(this);
        linearLayout.setOrientation(1);
        int iDp = dp(20.0f);
        linearLayout.setPadding(iDp, dp(4.0f), iDp, 0);
        android.widget.TextView textView = new android.widget.TextView(this);
        textView.setText(R.string.mac_dialog_msg);
        textView.setTextSize(12.0f);
        textView.setTextColor(getColorCompat(R.color.text_muted));
        textView.setLineSpacing(dp(2.0f), 1.0f);
        linearLayout.addView(textView);
        android.widget.LinearLayout.LayoutParams layoutParams = new android.widget.LinearLayout.LayoutParams(-1, -2);
        layoutParams.topMargin = dp(12.0f);
        linearLayout.addView(editText, layoutParams);
        new android.app.AlertDialog.Builder(this).setTitle(R.string.mac_dialog_title).setView(linearLayout).setPositiveButton(android.R.string.ok, new android.content.DialogInterface.OnClickListener() { // from class: com.carbridge.app.MainActivity.47

            @Override // android.content.DialogInterface.OnClickListener
            public void onClick(android.content.DialogInterface dialogInterface, int i) {
                java.lang.String strTrim = editText.getText().toString().trim();
                if (strTrim.isEmpty()) {
                    if (com.carbridge.carplay.bt.BtIdentity.manualMac(MainActivity.this) == null) {
                        android.widget.Toast.makeText(MainActivity.this, R.string.mac_empty, 0).show();
                        return;
                    }
                    com.carbridge.carplay.bt.BtIdentity.setManualMac(MainActivity.this, (java.lang.String) null);
                    MainActivity.this.log("已清除手动填写的蓝牙地址");
                    android.widget.Toast.makeText(MainActivity.this, R.string.mac_cleared, 0).show();
                    MainActivity.this.refreshIdentity();
                    return;
                }
                if (!com.carbridge.carplay.bt.BtIdentity.setManualMac(MainActivity.this, strTrim)) {
                    android.widget.Toast.makeText(MainActivity.this, R.string.mac_invalid, 1).show();
                    return;
                }
                java.lang.String str = com.carbridge.carplay.bt.BtIdentity.peek(MainActivity.this).mac;
                MainActivity.this.log("本机蓝牙地址已保存：" + str + "（下次连接生效）");
                android.widget.Toast.makeText(MainActivity.this, MainActivity.this.getString(R.string.mac_saved, new java.lang.Object[]{str}), 0).show();
                MainActivity.this.refreshIdentity();
            }
        }).setNeutralButton(R.string.mac_dialog_read, new android.content.DialogInterface.OnClickListener() { // from class: com.carbridge.app.MainActivity.46
            @Override // android.content.DialogInterface.OnClickListener
            public void onClick(android.content.DialogInterface dialogInterface, int i) {
                com.carbridge.carplay.bt.BtIdentity.Resolved resolvedResolve = com.carbridge.carplay.bt.BtIdentity.resolve(MainActivity.this, true);
                com.carbridge.carplay.bt.BtIdentity.remember(MainActivity.this, resolvedResolve);
                MainActivity.this.log("重新读取本机蓝牙地址：" + resolvedResolve.describe());
                MainActivity.this.refreshIdentity();
            }
        }).setNegativeButton(android.R.string.cancel, (android.content.DialogInterface.OnClickListener) null).show();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void showSrcversDialog() {
        final java.util.ArrayList<String> arrayList = new java.util.ArrayList<String>();
        java.util.ArrayList<String> arrayList2 = new java.util.ArrayList<String>();
        java.lang.String strSrcvers = com.carbridge.carplay.CarPlayCtrlCompat.srcvers(this);
        for (java.lang.String str : com.carbridge.carplay.CarPlayCtrlCompat.PRESETS) {
            arrayList.add(str);
            java.lang.String strPresetLabel = com.carbridge.carplay.CarPlayCtrlCompat.presetLabel(this, str);
            arrayList2.add(str.equals(strSrcvers) ? "✓ " + strPresetLabel : "    " + strPresetLabel);
        }
        final int size = arrayList.size();
        arrayList.add(null);
        arrayList2.add("    自定义…");
        new android.app.AlertDialog.Builder(this).setTitle(getString(R.string.srcvers_dialog_title) + " · " + strSrcvers).setItems(arrayList2.toArray(new java.lang.CharSequence[0]), new android.content.DialogInterface.OnClickListener() { // from class: com.carbridge.app.MainActivity.48

            @Override // android.content.DialogInterface.OnClickListener
            public void onClick(android.content.DialogInterface dialogInterface, int i) {
                if (i == size) {
                    MainActivity.this.showSrcversCustomDialog();
                } else {
                    MainActivity.this.applySrcvers(arrayList.get(i));
                }
            }
        }).setNegativeButton(android.R.string.cancel, (android.content.DialogInterface.OnClickListener) null).show();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void showSrcversCustomDialog() {
        final android.widget.EditText editText = new android.widget.EditText(this);
        editText.setSingleLine(true);
        editText.setTextSize(16.0f);
        editText.setHint("950.7.1");
        java.lang.String strSrcvers = com.carbridge.carplay.CarPlayCtrlCompat.srcvers(this);
        if (com.carbridge.carplay.CarPlayCtrlCompat.isValidValue(strSrcvers)) {
            editText.setText(strSrcvers);
            editText.setSelection(editText.getText().length());
        }
        android.widget.LinearLayout linearLayout = new android.widget.LinearLayout(this);
        linearLayout.setOrientation(1);
        int iDp = dp(20.0f);
        linearLayout.setPadding(iDp, dp(4.0f), iDp, 0);
        android.widget.TextView textView = new android.widget.TextView(this);
        textView.setText(R.string.srcvers_custom_hint);
        textView.setTextSize(12.0f);
        textView.setTextColor(getColorCompat(R.color.text_muted));
        textView.setLineSpacing(dp(2.0f), 1.0f);
        linearLayout.addView(textView);
        android.widget.LinearLayout.LayoutParams layoutParams = new android.widget.LinearLayout.LayoutParams(-1, -2);
        layoutParams.topMargin = dp(12.0f);
        linearLayout.addView(editText, layoutParams);
        new android.app.AlertDialog.Builder(this).setTitle(R.string.srcvers_custom_title).setView(linearLayout).setPositiveButton(android.R.string.ok, new android.content.DialogInterface.OnClickListener() { // from class: com.carbridge.app.MainActivity.49

            @Override // android.content.DialogInterface.OnClickListener
            public void onClick(android.content.DialogInterface dialogInterface, int i) {
                if (com.carbridge.carplay.CarPlayCtrlCompat.setValue(MainActivity.this, editText.getText().toString().trim())) {
                    MainActivity.this.refreshIdentity();
                    java.lang.String strSrcvers2 = com.carbridge.carplay.CarPlayCtrlCompat.srcvers(MainActivity.this);
                    MainActivity.this.log("广播版本 srcvers = " + strSrcvers2 + "（下次连接生效）");
                    android.widget.Toast.makeText(MainActivity.this, MainActivity.this.getString(R.string.srcvers_changed, new java.lang.Object[]{strSrcvers2}), 0).show();
                    return;
                }
                android.widget.Toast.makeText(MainActivity.this, R.string.srcvers_invalid, 1).show();
            }
        }).setNegativeButton(android.R.string.cancel, (android.content.DialogInterface.OnClickListener) null).show();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void applySrcvers(java.lang.String str) {
        if (!com.carbridge.carplay.CarPlayCtrlCompat.setValue(this, str)) {
            android.widget.Toast.makeText(this, R.string.srcvers_invalid, 1).show();
            return;
        }
        refreshIdentity();
        java.lang.String strSrcvers = com.carbridge.carplay.CarPlayCtrlCompat.srcvers(this);
        log("广播版本 srcvers = " + strSrcvers + "（下次连接生效）");
        android.widget.Toast.makeText(this, getString(R.string.srcvers_changed, new java.lang.Object[]{strSrcvers}), 0).show();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setConnectButton(boolean z) {
        if (this.btnConnect == null) {
            return;
        }
        this.btnConnect.setText(z ? R.string.btn_disconnect : R.string.btn_connect);
        this.btnConnect.setBackgroundResource(z ? R.drawable.bg_btn_danger : R.drawable.bg_btn_primary);
        this.btnConnect.setTextColor(getColorCompat(z ? R.color.state_err : R.color.on_accent));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setBadge(int i, int i2) {
        if (this.badgeView == null) {
            return;
        }
        this.badgeView.setText(i);
        this.badgeView.setTextColor(getColorCompat(R.color.on_accent));
        this.badgeView.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getColorCompat(i2)));
        this.badgeView.setVisibility(0);
    }

    private void trackMetrics(long j) {
        this.totalBytes += j;
        this.fpsFrames++;
        long jElapsedRealtime = android.os.SystemClock.elapsedRealtime();
        if (this.fpsWindowStart == 0) {
            this.fpsWindowStart = jElapsedRealtime;
        } else if (jElapsedRealtime - this.fpsWindowStart >= 1000) {
            this.fpsValue = (this.fpsFrames * 1000.0f) / (jElapsedRealtime - this.fpsWindowStart);
            this.fpsFrames = 0;
            this.fpsWindowStart = jElapsedRealtime;
            this.main.post(new java.lang.Runnable() { // from class: com.carbridge.app.MainActivity.50
                @Override // java.lang.Runnable
                public void run() {
                    MainActivity.this.updateMetrics();
                }
            });
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateMetrics() {
        int iEncodedWidth;
        int iEncodedHeight;
        if (this.metricFps == null) {
            return;
        }
        java.lang.String strFps = com.carbridge.app.MetricsText.fps(this.fpsValue);
        java.lang.String string = getString(R.string.metric_placeholder);
        this.metricFps.setText(strFps == null ? string : strFps);
        if (this.client != null) {
            iEncodedWidth = this.client.encodedWidth() > 0 ? this.client.encodedWidth() : this.client.streamWidth();
        } else {
            iEncodedWidth = 0;
        }
        int i = iEncodedWidth;
        if (this.client != null) {
            iEncodedHeight = this.client.encodedHeight() > 0 ? this.client.encodedHeight() : this.client.streamHeight();
        } else {
            iEncodedHeight = 0;
        }
        java.lang.String strResolution = com.carbridge.app.MetricsText.resolution(i, iEncodedHeight);
        this.metricResolution.setText(strResolution == null ? string : strResolution);
        long jFrameCount = this.client == null ? 0L : this.client.frameCount();
        java.lang.String strMbpsText = com.carbridge.app.MetricsText.mbpsText(this.totalBytes, jFrameCount, this.fpsValue);
        this.metricBitrate.setText(strMbpsText == null ? string : strMbpsText);
        if (this.metricsView == null) {
            return;
        }
        java.lang.String strDetail = com.carbridge.app.MetricsText.detail(jFrameCount, this.totalBytes, this.firstFrameDelayMs);
        if (strDetail != null) {
            this.metricsView.setText(strDetail);
            this.metricsView.setVisibility(0);
        } else {
            this.metricsView.setText("");
            this.metricsView.setVisibility(8);
        }
    }

    private float totalMbps() {
        return com.carbridge.app.MetricsText.mbps(this.totalBytes, this.client == null ? 0L : this.client.frameCount(), this.fpsValue);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void toggleCarPlay() {
        if (this.carPlayTx != null && this.carPlayTx.state() != com.carbridge.carplay.CarPlayWirelessTx.State.IDLE && this.carPlayTx.state() != com.carbridge.carplay.CarPlayWirelessTx.State.ERROR) {
            this.carPlayTx.stop();
            this.carPlayTx = null;
            if (this.carPlay != null) {
                this.carPlay.disconnect();
            }
            this.carplayManualStop = true;
            this.autoCarplayArmed = false;
            this.btnCarPlay.setText(R.string.btn_carplay_start);
            resetCarPlaySteps();
            log("已停止 CarPlay 配对/鉴权");
            return;
        }
        if (this.carPlayTx != null) {
            log("上一轮 CarPlay 是 ERROR 状态，先释放再重开");
            this.carPlayTx.stop();
            this.carPlayTx = null;
            if (this.carPlay != null) {
                this.carPlay.disconnect();
            }
        }
        this.carplayManualStop = false;
        resetCarPlaySteps();
        this.carPlayTx = new com.carbridge.carplay.CarPlayWirelessTx(this, new com.carbridge.carplay.CarPlayWirelessTx.Listener() { // from class: com.carbridge.app.MainActivity.51
            public void onState(com.carbridge.carplay.CarPlayWirelessTx.State state) {
                MainActivity.this.log("CarPlay 状态: " + state);
            }

            public void onLog(java.lang.String str) {
                MainActivity.this.log(str);
            }

            public void onStep(final com.carbridge.carplay.CarPlayWirelessTx.Step step) {
                MainActivity.this.main.post(new java.lang.Runnable() { // from class: com.carbridge.app.MainActivity.51.1

                    @Override // java.lang.Runnable
                    public void run() {
                        MainActivity.this.carPlayStep = step;
                        MainActivity.this.carPlayFailedStep = null;
                        MainActivity.this.renderSteps();
                    }
                });
            }

            public void onStepFailed(final com.carbridge.carplay.CarPlayWirelessTx.Step step, final java.lang.String str) {
                MainActivity.this.main.post(new java.lang.Runnable() { // from class: com.carbridge.app.MainActivity.51.2

                    @Override // java.lang.Runnable
                    public void run() {
                        MainActivity.this.carPlayFailedStep = step;
                        MainActivity.this.carPlayFailedReason = str;
                        MainActivity.this.renderSteps();
                    }
                });
            }

            public void onCarPlayEndpoint(java.lang.String str, int i) {
                com.carbridge.carplay.bridge.CarPlayBridge carPlayBridge = MainActivity.this.carPlay;
                if (carPlayBridge == null) {
                    MainActivity.this.log("CarPlayBridge 尚未创建，先点「开始连接」再启动 CarPlay");
                    return;
                }
                MainActivity.this.log("★ 桥接到 AirPlay " + str + ":" + i);
                MainActivity.this.rememberCarProfile(str);
                carPlayBridge.connect(str, i);
            }
        });
        this.btnCarPlay.setText(R.string.btn_carplay_stop);
        log("启动 CarPlay 配对/鉴权（等车机蓝牙回连）");
        this.carPlayTx.start();
    }

    @Override // android.app.Activity
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        runStartupDiagnostics();
    }

    @Override // android.app.Activity
    protected void onResume() {
        super.onResume();
        keepBeaconAlive("打开 App");
        refreshIdentity();
        kickIdentityProbe();
    }

    @Override // android.app.Activity
    protected void onDestroy() {
        this.main.removeCallbacksAndMessages(null);
        this.bondPolling = false;
        this.bondPoll.removeCallbacksAndMessages(null);
        if (sAlive == this) {
            sAlive = null;
        }
        super.onDestroy();
        if (this.client != null) {
            this.client.stop();
        }
        if (this.carPlay != null) {
            this.carPlay.disconnect();
            this.carPlay = null;
        }
        if (this.carPlayTx != null) {
            this.carPlayTx.stop();
            this.carPlayTx = null;
        }
        if (this.bondReceiver != null) {
            try {
                unregisterReceiver(this.bondReceiver);
            } catch (java.lang.Throwable th) {
                com.carbridge.carplay.util.Diag.w("MainActivity", th);
            }
            this.bondReceiver = null;
        }
        closeLogFile();
    }
}
