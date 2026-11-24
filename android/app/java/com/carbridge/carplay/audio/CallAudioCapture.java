package com.carbridge.carplay.audio;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;

/**
 * 通话音频采集：把手机这一端的通话声音录下来，交给 AirPlay 的 telephony 流。
 *
 * <p>为什么走这条路而不是 HFP：CarPlay 会话期间车机通常会**主动断掉经典蓝牙的 HFP**，
 * 只剩 AirPlay 通道可用。真 iPhone 打电话就是走 AirPlay 的 {@code 100/telephony}
 * 音频流，所以 HFP 连不上不是配置问题，是方向选错了。
 *
 * <p>采集参数跟着 telephony 档走：**44100Hz / 16bit / 立体声**（
 * {@code AirPlaySession.submitTelephonyAudio} 内部再做下混成单声道）。
 *
 * <p>局限（写在代码里免得以后误会）：这里只做**下行**——把手机侧的通话声音送去车机喇叭。
 * 上行（车机麦克风 → 手机 → 对方）需要 AirPlay 的 mic 输入流，目前没接；
 * 所以对端听到的还是手机自己的麦克风。
 */
public final class CallAudioCapture {

    private static final String TAG = "carbridge";
    private static final int SAMPLE_RATE = 44100;
    /** 每次读 20ms，够小以保持低延迟，又不至于把 CPU 拉满。 */
    private static final int FRAME_MS = 20;

    public interface Sink {
        /** 收到一段 PCM（立体声 16bit 小端）。 */
        void onPcm(byte[] pcm, int len, long ptsMs);
    }

    private Thread thread;
    private volatile boolean running;
    /** ⚠️ volatile：读线程每轮解引用它，stop() 在别的线程置空/停止。 */
    private volatile AudioRecord record;
    private final Sink sink;
    private final Log log;
    private long samples;

    public interface Log {
        void onLog(String line);
    }

    public CallAudioCapture(Sink sink, Log log) {
        this.sink = sink;
        this.log = log;
    }

    /**
     * 开始采集。
     *
     * @return true = 真的采起来了；false = 没采起来（权限/格式/占用）
     *
     * <p>⚠️ 必须返回结果：以前是 void，调用方不管成败都压掉本机通话音量并
     * 「认为已在路由」，HFP 备选就永远轮不到 —— 该通电话车机没声、手机也可能没声。
     */
    public synchronized boolean start(Context ctx) {
        if (running) return true;
        final int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) {
            log.onLog("⚠ 通话采集：AudioRecord 不支持 44.1k 立体声（minBuf=" + minBuf + "）");
            return false;
        }
        final int bufSize = Math.max(minBuf, SAMPLE_RATE * 4 * FRAME_MS / 1000 * 4);
        try {
            record = buildRecord(bufSize);
            if (record == null || record.getState() != AudioRecord.STATE_INITIALIZED) {
                log.onLog("⚠ 通话采集：AudioRecord 初始化失败（可能没有 RECORD_AUDIO 权限）");
                release();
                return false;
            }
            record.startRecording();
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                log.onLog("⚠ 通话采集：startRecording 后状态不是 RECORDING（"
                        + record.getRecordingState() + "）");
                release();
                return false;
            }
        } catch (Throwable t) {
            log.onLog("⚠ 通话采集：启动失败 " + t);
            release();
            return false;
        }
        running = true;
        samples = 0;
        final AudioRecord rec = record;
        final int frameBytes = SAMPLE_RATE * 4 * FRAME_MS / 1000;
        thread = new Thread(new Runnable() {
            @Override public void run() {
                byte[] buf = new byte[frameBytes];
                long t0 = System.currentTimeMillis();
                log.onLog("通话采集：已开始（44.1k/16bit/立体声，20ms 一帧）");
                try {
                    while (running && rec != null) {
                        int n = rec.read(buf, 0, buf.length);
                        if (n > 0) {
                            samples += n / 4;
                            sink.onPcm(buf, n, Math.max(0L, System.currentTimeMillis() - t0));
                        } else if (n < 0) {
                            log.onLog("通话采集：read 返回 " + n + "（停止采集）");
                            break;
                        }
                    }
                } catch (Throwable t) {
                    // 以前异常直接逃出 run()：running 永远停在 true，
                    // 下一次 start() 因 if (running) 直接跳过 → 后续通话永久采不到音。
                    log.onLog("通话采集：读取异常，停止：" + t);
                } finally {
                    running = false;
                    releaseIfCurrent(rec);
                    log.onLog("通话采集：已停止，共 " + samples + " 帧");
                }
            }
        }, "carbridge-call-audio");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    /** 优先 VOICE_COMMUNICATION（通话语音优化），不行再退 MIC。 */
    private AudioRecord buildRecord(int bufSize) {
        AudioRecord r = tryRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, bufSize);
        if (r != null) return r;
        return tryRecord(MediaRecorder.AudioSource.MIC, bufSize);
    }

    private AudioRecord tryRecord(int source, int bufSize) {
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                return new AudioRecord.Builder()
                        .setAudioSource(source)
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                                .build())
                        .setBufferSizeInBytes(bufSize)
                        .build();
            }
            return new AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT, bufSize);
        } catch (Throwable t) {
            log.onLog("通话采集：source=" + source + " 不可用 " + t);
            return null;
        }
    }

    /** 停止采集（通话结束/会话断开都要调）。 */
    public void stop() {
        running = false;
        // 先打断阻塞中的 read：只置标志位的话它会一直等到下一次数据/超时
        AudioRecord r = record;
        if (r != null) {
            try {
                r.stop();
            } catch (Throwable tDiag) {
                com.carbridge.carplay.util.Diag.w("CallAudioCapture.stop", tDiag);
            }
        }
        Thread t = thread;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(2000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        // 读线程没能在 2 秒内退出（极端情况）也要把设备放掉，不能只等它
        release();
    }

    public boolean isRunning() {
        return running;
    }

    /** 释放 AudioRecord（幂等）。 */
    private synchronized void release() {
        AudioRecord r = record;
        record = null;
        if (r == null) return;
        try {
            if (r.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) r.stop();
            r.release();
        } catch (Throwable t) {
            com.carbridge.carplay.util.Diag.w("CallAudioCapture.release", t);
        }
    }

    /** 读线程退出时释放「自己那一份」：新会话已经换了设备就不要误放。 */
    private synchronized void releaseIfCurrent(AudioRecord r) {
        if (record != r) return;
        release();
    }
}
