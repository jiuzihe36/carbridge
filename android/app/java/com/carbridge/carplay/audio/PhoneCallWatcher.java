package com.carbridge.carplay.audio;

import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

/**
 * 通话检测：直接看 {@link AudioManager} 的模式，不依赖 Car+ 上报。
 *
 * <p>为什么要自己看：实测 Car+ 在部分机型/场景下**不上报**通话状态，
 * 于是「通话开始」分支永远不执行——日志里只有「通话结束」，声音自然留在手机上。
 * 而系统音频模式在通话时一定会变成 {@code MODE_IN_CALL}（或
 * {@code MODE_IN_COMMUNICATION}），这是最可靠的信号。
 *
 * <p>两路一起用：API 31+ 注册 {@code OnModeChangedListener} 走回调，
 * 同时保留 800ms 轮询兜底（部分 ROM 不回调）。
 */
public final class PhoneCallWatcher {

    public interface Listener {
        void onCallActive(boolean active);
    }

    private static final long POLL_MS = 800L;

    private final Context ctx;
    private final Listener listener;
    private final Log log;
    private boolean known;
    private boolean active;
    private boolean running;

    private final Handler main = new Handler(Looper.getMainLooper());
    private AudioManager.OnModeChangedListener modeListener;

    public interface Log {
        void onLog(String line);
    }

    public PhoneCallWatcher(Context ctx, Listener listener, Log log) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
        this.log = log;
    }

    public void start() {
        if (running) return;
        running = true;
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                modeListener = new AudioManager.OnModeChangedListener() {
                    @Override public void onModeChanged(int mode) {
                        evaluate(mode);
                    }
                };
                // 不用方法引用（-source 8 + android.jar 下编不过），显式给个 Executor
                am().addOnModeChangedListener(new java.util.concurrent.Executor() {
                    @Override public void execute(Runnable r) {
                        main.post(r);
                    }
                }, modeListener);
                log.onLog("通话检测：已注册音频模式监听");
            } catch (Throwable t) {
                log.onLog("通话检测：注册模式监听失败（改用轮询）：" + t);
            }
        }
        main.post(poll);
    }

    public void stop() {
        running = false;
        main.removeCallbacks(poll);
        if (modeListener != null && Build.VERSION.SDK_INT >= 31) {
            try {
                am().removeOnModeChangedListener(modeListener);
            } catch (Throwable t) {
                com.carbridge.carplay.util.Diag.w("PhoneCallWatcher.remove", t);
            }
            modeListener = null;
        }
    }

    /** 当前是否判定为「通话中」。 */
    public boolean isActive() {
        return active;
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!running) return;
            evaluate(am().getMode());
            main.postDelayed(this, POLL_MS);
        }
    };

    private void evaluate(int mode) {
        boolean now = mode == AudioManager.MODE_IN_CALL
                || mode == AudioManager.MODE_IN_COMMUNICATION
                || mode == AudioManager.MODE_RINGTONE;
        if (known && now == active) return;
        known = true;
        active = now;
        log.onLog("通话检测：mode=" + mode + " → " + (now ? "通话中" : "空闲"));
        listener.onCallActive(now);
    }

    private AudioManager am() {
        return (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
    }
}
