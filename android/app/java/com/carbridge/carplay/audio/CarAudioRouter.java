package com.carbridge.carplay.audio;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * 把**通话音频**交给车机。
 */
public final class CarAudioRouter {

    private static final String TAG = "carbridge";

    public interface Log {
        void onLog(String line);
    }

    /** 持有 profile proxy，否则会被 GC 掉导致 HFP 断开。 */
    private static volatile BluetoothHeadset headsetProxy;
    /** HFP 代理已申请、正在等系统的 onServiceConnected 回调（Android 15 的异步形状）。 */
    private static volatile boolean headsetPending;
    /** 上次发起代理申请的时间：回调一直不来时不能永远「等」，否则再也不重试。 */
    private static volatile long headsetPendingSince;
    /** 代理申请最多等这么久，超时当作没申请过（下次重来）。 */
    private static final long HEADSET_PENDING_TIMEOUT_MS = 15_000L;
    /** 等 HFP 连上的轮询间隔与次数（合计约 8 秒）。 */
    private static final long HFP_WAIT_MS = 1000L;
    private static final int HFP_WAIT_ATTEMPTS = 8;
    /** 记住被静音前的音量，退出时还原。 */
    private static int savedRingVolume = -1;
    /** 静音是否真的生效（setStreamVolume 可能被通知策略拦掉）。 */
    private static boolean ringMuteEffective;
    /**
     * HFP 等待链的代号：每次 {@code connectCarHeadset} 自增，
     * 旧链发现自己不是最新一代就直接退出（否则两轮 8 秒轮询同时在跑）。
     */
    private static volatile int headsetWaitGen;

    private CarAudioRouter() {
    }

    /**
     * 连上车机的 HFP（耳机）配置，让通话音频走车机。
     *
     * @return true = 已发起连接
     */
    public static boolean connectCarHeadset(final Context ctx, final BluetoothDevice car,
                                            final Log log) {
        if (car == null) {
            emit(log, "通话音频：没找到车机设备，跳过 HFP");
            return false;
        }
        final BluetoothManager bm =
                (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm == null || bm.getAdapter() == null) {
            emit(log, "通话音频：拿不到蓝牙适配器");
            return false;
        }
        if (headsetProxy != null && isConnectedTo(ctx, car)) {
            emit(log, "通话音频：车机 HFP 已经是连上的");
            setCommunicationDeviceRetry(ctx, car, log, 3);
            setLocalRingMuted(ctx, true, log);
            return true;
        }
        // 新的等待链：作废之前那条（否则两轮 8 秒的轮询会同时在跑）
        int gen = ++headsetWaitGen;
        try {
            BluetoothHeadset proxy = obtainHeadsetProxy(ctx, bm, car, log, gen);
            if (proxy == null) {
                // Android 15+：proxy 是**异步**回调过来的（onServiceConnected），
                // 这里拿不到是正常的 —— 回调里会自己 connect + 等连接 ✓
                if (isHeadsetPending()) {
                    emit(log, "通话音频：HFP 代理申请已提交，等系统回调后连车机");
                    return true;
                }
                emit(log, "通话音频：拿不到 BluetoothHeadset proxy");
                return false;
            }
            emit(log, "通话音频：HFP proxy 就绪，正在连车机 " + car.getName());
            // proxy 已经有了 → 直接发起连接
            boolean ok = invokeConnect(proxy, car);
            emit(log, "通话音频：HFP connect(" + car.getName() + ") → " + ok);
            if (!ok) {
                // connect 被拒绝 → 必须回 false，否则上层把 callAudioRouted 置成 true、
                // 之后再也不重试 → 通话声永远留在手机（现场「打电话手机发音」）。
                emit(log, "通话音频：HFP connect 被拒，返回未路由（等下次机会重试）");
                return false;
            }
            waitForHeadset(ctx, car, log, 0, gen);
            return true;
        } catch (Throwable t) {
            emit(log, "通话音频：HFP 代理失败: " + t);
            return false;
        }
    }

        /**
     * 拿车机的 HFP（BluetoothHeadset）代理。
     */
    private static BluetoothHeadset obtainHeadsetProxy(final Context ctx,
                                                       BluetoothManager bm,
                                                       final BluetoothDevice car,
                                                       final Log log, final int gen) {
        // 已有 proxy 直接复用（getProfileProxy 会替换旧 proxy，导致已建立的 HFP 掉线）
        if (headsetProxy != null) return headsetProxy;

        // 上一轮申请如果一直没回调，别把它当「还在等」——清掉重来
        if (headsetPending && !isHeadsetPending()) {
            emit(log, "通话音频：上次 HFP 代理申请超时（系统没回调），重新申请");
            headsetPending = false;
        }

        // ① Android 15+：BluetoothProfile$ServiceListener
        //    （proxy 是**异步**回调过来的，这里拿不到是正常的）
        if (tryServiceListenerProxy(ctx, bm, car, log, gen)) {
            emit(log, "通话音频：HFP 代理申请已提交，等 onServiceConnected 回调");
            return null;      // proxy 由回调填（onHeadsetReady）
        }

        // ② 老系统：BluetoothProfile$Listener
        try {
            final Class<?> listenerClass =
                    Class.forName("android.bluetooth.BluetoothProfile$Listener");
            Object listener = Proxy.newProxyInstance(
                    listenerClass.getClassLoader(), new Class<?>[]{listenerClass},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            if ("onProfileConnected".equals(method.getName())
                                    && args != null && args.length >= 2
                                    && args[0] instanceof Integer
                                    && (Integer) args[0] == BluetoothProfile.HEADSET) {
                                onHeadsetReady(ctx, car, (BluetoothProfile) args[1], log, gen);
                            }
                            return null;
                        }
                    });
            Method get = findGetProfileProxy(BluetoothAdapter.class, listenerClass);
            return (BluetoothHeadset) get.invoke(bm.getAdapter(), ctx, listener);
        } catch (Throwable t) {
            emit(log, "通话音频：老版 HFP 代理也不可用: " + t);
        }

        // ③ 最终兜底：**运行时扫描** BluetoothHeadset 的构造器，
        //    逐个填参数（Context / BluetoothAdapter / 其它 null）。
        //    为什么不能写死签名：Android 15 把 BluetoothProfile$Listener 整个删了，
        //    新的代理入口是 getProfile(int, IBluetoothProfileCallback)（私有 AIDL 回调），
        //    各家 ROM 的形状还会再变 → 扫一遍最稳。
        try {
            for (java.lang.reflect.Constructor<?> ctor
                    : BluetoothHeadset.class.getDeclaredConstructors()) {
                Object[] args = new Object[ctor.getParameterTypes().length];
                boolean ok = true;
                for (int i = 0; i < args.length; i++) {
                    Class<?> t = ctor.getParameterTypes()[i];
                    if (t == Context.class) args[i] = ctx;
                    else if (t == BluetoothAdapter.class) args[i] = bm.getAdapter();
                    else if (t.isPrimitive()) { ok = false; break; }
                    else args[i] = null;
                }
                if (!ok) continue;
                ctor.setAccessible(true);
                Object hs = ctor.newInstance(args);
                if (hs instanceof BluetoothHeadset) {
                    emit(log, "通话音频：HFP proxy（直接构造 BluetoothHeadset）就绪");
                    return (BluetoothHeadset) hs;
                }
            }
            emit(log, "通话音频：BluetoothHeadset 构造器都不可用");
        } catch (Throwable t) {
            emit(log, "通话音频：直接构造 BluetoothHeadset 也失败: " + t);
        }
        return null;
    }

    /**
     * 找 {@code BluetoothAdapter.getProfileProxy}。
     *
     * <p>⚠️ Android 15 上这个方法是 **private**（老版本是 public），
     * {@code getMethod} 只查 public 方法 → 必须用 {@code getDeclaredMethod}
     * + {@code setAccessible(true)}，否则报 {@code NoSuchMethodException}。
     */
    private static Method findGetProfileProxy(Class<?> adapterClass,
                                              Class<?> listenerClass,
                                              Class<?>... extra) throws NoSuchMethodException {
        Class<?>[] sig;
        if (extra.length == 0) {
            sig = new Class<?>[]{Context.class, listenerClass};
        } else {
            // (Context, listener, <extra...>)
            sig = new Class<?>[2 + extra.length];
            sig[0] = Context.class;
            sig[1] = listenerClass;
            System.arraycopy(extra, 0, sig, 2, extra.length);
        }
        try {
            Method m = adapterClass.getDeclaredMethod("getProfileProxy", sig);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            // 兜底：再按 public 找一次
            Method m = adapterClass.getMethod("getProfileProxy", sig);
            m.setAccessible(true);
            return m;
        }
    }

    /** Android 15+ 的 {@code BluetoothProfile$ServiceListener} 版本。 */
    private static boolean tryServiceListenerProxy(final Context ctx,
                                                    BluetoothManager bm,
                                                    final BluetoothDevice car,
                                                    final Log log, final int gen) {
        try {
            final Class<?> svcClass =
                    Class.forName("android.bluetooth.BluetoothProfile$ServiceListener");
            Object listener = Proxy.newProxyInstance(
                    svcClass.getClassLoader(), new Class<?>[]{svcClass},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            if ("onServiceConnected".equals(method.getName())
                                    && args != null && args.length >= 2
                                    && args[0] instanceof Integer
                                    && (Integer) args[0] == BluetoothProfile.HEADSET
                                    && args[1] instanceof BluetoothProfile) {
                                onHeadsetReady(ctx, car, (BluetoothProfile) args[1], log, gen);
                            }
                            return null;
                        }
                    });
            // ⚠️ Android 15 上真实签名是
            //    getProfileProxy(Context, BluetoothProfile$ServiceListener, int) → boolean
            //    （3 个参数，**没有** Executor；proxy 在 onServiceConnected 回调里给）
            Method get = findGetProfileProxy(BluetoothAdapter.class, svcClass, int.class);
            Object r = get.invoke(bm.getAdapter(), ctx, listener, BluetoothProfile.HEADSET);
            boolean pending = Boolean.TRUE.equals(r);
            headsetPending = pending;
            if (pending) headsetPendingSince = System.currentTimeMillis();
            emit(log, "通话音频：getProfileProxy(HEADSET) → " + r);
            // 返回的是 boolean；真正的 proxy 走 onServiceConnected 回调给（**异步**）
            // ⇒ 这里只能回「已发起」，proxy 由回调补上（见 onHeadsetReady）
            return Boolean.TRUE.equals(r);
        } catch (Throwable t) {
            emit(log, "通话音频：ServiceListener 路径不可用: " + t);
            return false;
        }
    }

    /** HFP profile 就绪 → 连上车机 → 设为通话设备 → 静音本机铃声。 */
    private static void onHeadsetReady(final Context ctx, final BluetoothDevice car,
                                        BluetoothProfile profile, final Log log, int gen) {
        if (!(profile instanceof BluetoothHeadset)) return;
        final BluetoothHeadset hs = (BluetoothHeadset) profile;
        headsetProxy = hs;
        headsetPending = false;
        boolean ok = invokeConnect(hs, car);
        emit(log, "通话音频：HFP connect(" + car.getName() + ") → " + ok);
        // ⚠️ HFP 是**异步**连上的：此刻通常还没连上，
        //    所以「设为通话设备 / 静音铃声」必须等连接真正建立后再做。
        waitForHeadset(ctx, car, log, 0, gen);
    }

    /**
     * 代理申请还在有效等待期内吗？
     *
     * <p>系统偶尔不回调（蓝牙栈忙 / 权限刚变）。没有这个超时的话
     * {@code headsetPending} 会永远为 true，后续每次 {@code connectCarHeadset}
     * 都会直接返回「等回调」，**再也不发起新的申请**。
     */
    private static boolean isHeadsetPending() {
        return headsetPending
                && System.currentTimeMillis() - headsetPendingSince < HEADSET_PENDING_TIMEOUT_MS;
    }

    /**
     * 等车机 HFP 连上（最多 {@link #HFP_WAIT_ATTEMPTS} 次，每次间隔 {@link #HFP_WAIT_MS}）。
     *
     * <p>连上后才：设通话设备（通话声去车机）+ 静音本机铃声。
     * 连不上就**保留手机响**（宁可吵也不能漏接）。
     */
    private static void waitForHeadset(final Context ctx, final BluetoothDevice car,
                                        final Log log, final int attempt, final int gen) {
        if (gen != headsetWaitGen) return;     // 已被新的一轮取代
        final Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (gen != headsetWaitGen) return;     // 已被新的一轮取代
                if (isConnectedTo(ctx, car)) {
                    emit(log, "通话音频：车机 HFP 已连上 ✓");
                    setCommunicationDeviceRetry(ctx, car, log, 3);
                    setLocalRingMuted(ctx, true, log);
                    return;
                }
                if (attempt + 1 >= HFP_WAIT_ATTEMPTS) {
                    emit(log, "通话音频：车机 HFP 一直没连上，**保留本机铃声**"
                            + "（通话声可能仍在手机）");
                    return;
                }
                waitForHeadset(ctx, car, log, attempt + 1, gen);
            }
        }, HFP_WAIT_MS);
    }

    /** {@code BluetoothHeadset.connect(BluetoothDevice)}（新 SDK 已删，走反射）。 */
    private static boolean invokeConnect(BluetoothHeadset hs, BluetoothDevice car) {
        try {
            Method m = hs.getClass().getMethod("connect", BluetoothDevice.class);
            return Boolean.TRUE.equals(m.invoke(hs, car));
        } catch (NoSuchMethodException e) {
            // 新版 API：connect()（连上次用过的设备）
            try {
                Method m2 = hs.getClass().getMethod("connect");
                return Boolean.TRUE.equals(m2.invoke(hs));
            } catch (Throwable t2) {
                return false;
            }
        } catch (Throwable t) {
            return false;
        }
    }

        /**
     * 车机的 HFP 现在是不是连着的。
     */
    public static boolean isConnectedTo(Context ctx, BluetoothDevice car) {
        if (car == null) return false;
        try {
            BluetoothManager bm =
                    (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            if (bm != null) {
                java.util.List<BluetoothDevice> devs =
                        bm.getConnectedDevices(BluetoothProfile.HEADSET);
                if (devs != null) {
                    for (BluetoothDevice d : devs) {
                        if (d != null && d.getAddress().equalsIgnoreCase(car.getAddress())) {
                            return true;
                        }
                    }
                }
                return false;   // 查询成功但没有这台 → 确实没连上
            }
        } catch (Throwable t) {
            emit(null, "通话音频：查 HFP 连接状态失败（退回代理查询）: " + t);
        }
        // 兜底（老系统 / 个别 ROM 的 BluetoothManager 有问题）
        try {
            BluetoothHeadset hs = headsetProxy;
            if (hs == null) hs = getHeadsetProxy(ctx);
            if (hs == null) return false;
            for (BluetoothDevice d : hs.getConnectedDevices()) {
                if (d != null && d.getAddress().equalsIgnoreCase(car.getAddress())) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

        /**
     * 取 BluetoothHeadset profile 代理。
     */
    private static BluetoothHeadset getHeadsetProxy(Context ctx) {
        try {
            BluetoothManager bm =
                    (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            if (bm == null || bm.getAdapter() == null) return null;
            Class<?> listenerClass =
                    Class.forName("android.bluetooth.BluetoothProfile$Listener");
            Object listener = Proxy.newProxyInstance(
                    listenerClass.getClassLoader(), new Class<?>[]{listenerClass},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            return null;
                        }
                    });
            Method m = BluetoothAdapter.class.getMethod(
                    "getProfileProxy", Context.class, listenerClass);
            return (BluetoothHeadset) m.invoke(bm.getAdapter(), ctx, listener);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 把车机设为「通话设备」。
     *
     * <p>只有一台 HFP 设备时系统本来就会自动用它；显式设一次更稳。
     * API 31+ 用 {@code setCommunicationDevice(AudioDeviceInfo)}（新 SDK 里
     * {@code BluetoothDevice} 那个重载已被删）。
     */
    public static void setCommunicationDevice(Context ctx, BluetoothDevice car, Log log) {
        if (Build.VERSION.SDK_INT < 31) return;
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null || car == null) return;
            for (AudioDeviceInfo info : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                if (info.getType() != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) continue;
                String addr = info.getAddress();
                if (addr != null && addr.equalsIgnoreCase(car.getAddress())) {
                    am.setCommunicationDevice(info);
                    emit(log, "通话音频：已把车机设为通话设备（通话声走车机，手机扬声器静音）");
                    return;
                }
            }
            emit(log, "通话音频：还没找到车机的 HFP 音频设备（HFP 可能没连上）");
        } catch (Throwable t) {
            emit(log, "通话音频：setCommunicationDevice 失败: " + t);
        }
    }

    
    /**
     * 把当前音频路由打出来（通话诊断用）。
     *
     * <p>现场最常见的一句是「打电话还是手机在响」，但仅凭这句话没法判断是
     * HFP 没连上、SCO 没建立，还是系统把通话设备选成了听筒。把这三件事一次打全，
     * 下次拿到日志就能直接定位，不用再猜。
     */
    public static void describeRoute(Context ctx, Log log) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) { emit(log, "音频诊断：拿不到 AudioManager"); return; }
            StringBuilder sb = new StringBuilder("音频诊断：mode=").append(am.getMode());
            if (Build.VERSION.SDK_INT >= 31) {
                AudioDeviceInfo cd = am.getCommunicationDevice();
                sb.append("；通话设备=").append(cd == null ? "无" : (cd.getType() + "@" + cd.getAddress()));
            }
            sb.append("；输出设备=[");
            for (AudioDeviceInfo info : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                if (info.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                        || info.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
                    sb.append(info.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ? "SCO:" : "A2DP:")
                            .append(info.getAddress()).append(' ');
                }
            }
            sb.append("]；HFP代理=").append(headsetProxy == null ? "未拿到" : "已持有");
            if (headsetProxy != null) {
                // ⚠️ getConnectedDevices() 在「没连」时返回**空列表**而不是 null，
                // 之前写成 != null 于是永远显示「已连接」（真机日志里就是这么骗人的）。
                java.util.List<BluetoothDevice> hs = headsetProxy.getConnectedDevices();
                int n = hs == null ? 0 : hs.size();
                sb.append("；HFP连接=").append(n == 0 ? "无" : (n + "台"));
                if (n > 0 && hs.get(0) != null) sb.append('(').append(hs.get(0).getName()).append(')');
            }
            emit(log, sb.toString());
        } catch (Throwable t) {
            emit(log, "音频诊断失败: " + t);
        }
    }

    /** 通话结束：把通话设备还原成系统默认，避免路由被我们钉死。 */
    public static void clearCommunicationDevice(Context ctx, Log log) {
        if (Build.VERSION.SDK_INT < 31) return;
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            AudioDeviceInfo cur = am.getCommunicationDevice();
            am.clearCommunicationDevice();
            emit(log, "通话音频：通话结束，已还原默认通话设备（之前是 "
                    + (cur == null ? "无" : cur.getType() + "@" + cur.getAddress()) + "）");
        } catch (Throwable t) {
            emit(log, "通话音频：还原默认设备失败: " + t);
        }
    }


    /**
     * 带重试地把车机设为通话设备。
     *
     * <p>SCO 链路在通话刚建立时往往还没出现在设备列表里（实测要几百毫秒），
     * 只试一次会失败并落回手机出声。这里按 800ms 间隔重试，最多 3 次。
     */
    public static void setCommunicationDeviceRetry(final Context ctx, final BluetoothDevice car,
                                                   final Log log, final int attemptsLeft) {
        if (Build.VERSION.SDK_INT < 31 || car == null) return;
        if (isCommunicationDevice(ctx, car)) return;   // 已经是车机，不用再试
        setCommunicationDevice(ctx, car, log);
        if (attemptsLeft <= 0) return;
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                setCommunicationDeviceRetry(ctx, car, log, attemptsLeft - 1);
            }
        }, 800);
    }

    /** 当前通话设备是否就是这台车机。 */
    private static boolean isCommunicationDevice(Context ctx, BluetoothDevice car) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return false;
            AudioDeviceInfo cur = am.getCommunicationDevice();
            return cur != null && cur.getAddress() != null
                    && cur.getAddress().equalsIgnoreCase(car.getAddress());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 静音/恢复本机来电铃声。
     */
    public static void setLocalRingMuted(Context ctx, boolean muted, Log log) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            if (muted) {
                if (savedRingVolume >= 0) return;         // 已经静音过
                int v = am.getStreamVolume(AudioManager.STREAM_RING);
                // 新 SDK 只剩 setStreamVolume(int,int,int)
                am.setStreamVolume(AudioManager.STREAM_RING, 0, 0);
                int now = am.getStreamVolume(AudioManager.STREAM_RING);
                savedRingVolume = v;
                ringMuteEffective = (now == 0);
                saveRingVolume(ctx, v);
                emit(log, "通话音频：本机铃声 " + v + " → " + now
                        + (ringMuteEffective ? "（已静音，车机会响）" : "（**静音没生效**，手机可能还会响）"));
            } else if (savedRingVolume >= 0) {
                am.setStreamVolume(AudioManager.STREAM_RING, savedRingVolume, 0);
                emit(log, "通话音频：已恢复本机铃声音量 " + savedRingVolume);
                savedRingVolume = -1;
                ringMuteEffective = false;
                clearRingVolume(ctx);
            }
        } catch (Throwable t) {
            emit(log, "通话音频：铃声静音失败: " + t);
        }
    }

    /** 退出时把本机铃声恢复（防止 App 被杀后手机一直哑掉）。 */
    public static void restoreRingVolume(Context ctx, Log log) {
        setLocalRingMuted(ctx, false, log);
    }

    private static final String PREF_RING = "carbridge_ring";
    private static final String KEY_RING_VOLUME = "ring_volume";

    private static void saveRingVolume(Context ctx, int v) {
        try {
            ctx.getApplicationContext().getSharedPreferences(PREF_RING, Context.MODE_PRIVATE)
                    .edit().putInt(KEY_RING_VOLUME, v).apply();
        } catch (Throwable ignored) {
        }
    }

    private static void clearRingVolume(Context ctx) {
        try {
            ctx.getApplicationContext().getSharedPreferences(PREF_RING, Context.MODE_PRIVATE)
                    .edit().remove(KEY_RING_VOLUME).apply();
        } catch (Throwable ignored) {
        }
    }

    /**
     * App 启动时调用：如果上次是被系统杀掉、没来得及恢复铃声，这里补一次。
     *
     * <p>没有这一步，用户会碰到「手机突然没声音了，怎么都调不出来」。
     */
    public static void restoreIfMutedOnStart(Context ctx, Log log) {
        try {
            int v = ctx.getApplicationContext().getSharedPreferences(PREF_RING, Context.MODE_PRIVATE)
                    .getInt(KEY_RING_VOLUME, -1);
            if (v < 0) return;
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            if (am.getStreamVolume(AudioManager.STREAM_RING) == 0) {
                am.setStreamVolume(AudioManager.STREAM_RING, v, 0);
                emit(log, "通话音频：上次退出时没恢复铃声，已补恢复到 " + v);
            }
            clearRingVolume(ctx);
        } catch (Throwable t) {
            emit(log, "通话音频：补恢复铃声失败: " + t);
        }
    }

    private static void emit(Log log, String line) {
        android.util.Log.i(TAG, line);
        if (log != null) log.onLog(line);
    }
}
