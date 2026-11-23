package com.carbridge.app;

import java.util.Locale;

/**
 * 首个断点：把两条步骤链（CarLife + CarPlay）和停滞时长压成**一屏结论**。
 *
 * <p>为什么要有它：现在诊断页是一列步骤 + 几千行日志，用户（和我）要自己找
 * 「第一个不对的地方」。规则本身（失败优先、终态算完成、卡住多久算没进展）是纯逻辑，
 * 放在这里由 JVM 单测锁住；界面只负责显示一行字。
 *
 * <p>规则：
 * <ol>
 *   <li>任何一条链有失败步 → 失败优先，CarPlay 链优先于 CarLife 链（车机认不认我们在前）；</li>
 *   <li>否则看正在进行的链：CarPlay 先于 CarLife（链路顺序）；</li>
 *   <li>当前步停住超过 {@link #STALL_MS} 才算「没进展」，短于它只报进度；</li>
 *   <li>两条链都到最后一步 → 全链路已通。</li>
 * </ol>
 */
public final class StepDiagnosis {

    /** 当前步停住多久算「没进展」。 */
    public static final long STALL_MS = 8000L;

    public enum Level { IDLE, RUNNING, FAILED, DONE }

    /** 一屏结论：一行标题 + 一行提示。 */
    public static final class Result {
        public final Level level;
        public final String headline;
        public final String hint;

        Result(Level level, String headline, String hint) {
            this.level = level;
            this.headline = headline;
            this.hint = hint;
        }

        /** 界面直接显示这一行（没有提示时只显示标题）。 */
        public String text() {
            return hint == null || hint.length() == 0 ? headline : headline + " · " + hint;
        }
    }

    private StepDiagnosis() {
    }

    /**
     * @param clLabels CarLife 链的步骤名（HuClient.Step）
     * @param clCur    当前步下标；null = 没开始
     * @param clFail   失败步下标；null = 没失败
     * @param cpLabels CarPlay 链的步骤名（CarPlayWirelessTx.Step）
     * @param cpCur    当前步下标；null = 没开始
     * @param cpFail   失败步下标；null = 没失败
     * @param stalledMs 当前步已经停住多久（毫秒；0 = 不知道）
     */
    public static Result of(String[] clLabels, Integer clCur, Integer clFail,
                            String[] cpLabels, Integer cpCur, Integer cpFail,
                            long stalledMs) {
        if (valid(cpLabels, cpFail)) return failed("CarPlay", cpLabels, cpFail, stalledMs);
        if (valid(clLabels, clFail)) return failed("CarLife", clLabels, clFail, stalledMs);

        boolean cpRunning = running(cpLabels, cpCur);
        boolean clRunning = running(clLabels, clCur);
        if (cpRunning) return running("CarPlay", cpLabels, cpCur, stalledMs);
        if (clRunning) return running("CarLife", clLabels, clCur, stalledMs);

        if (atEnd(cpLabels, cpCur) || atEnd(clLabels, clCur)) {
            return new Result(Level.DONE, "全链路已通：画面应在车机上",
                    "若车机黑屏，先看会话日志最后一行，再导出诊断包");
        }
        return new Result(Level.IDLE, "未开始",
                "点「连接」后，这里会显示第一个卡住的阶段");
    }

    private static boolean valid(String[] labels, Integer idx) {
        return labels != null && idx != null && idx >= 0 && idx < labels.length;
    }

    private static boolean running(String[] labels, Integer cur) {
        return valid(labels, cur) && cur < labels.length - 1;
    }

    private static boolean atEnd(String[] labels, Integer cur) {
        return valid(labels, cur) && cur >= labels.length - 1;
    }

    private static Result failed(String chain, String[] labels, int idx, long stalledMs) {
        String label = labels[idx];
        String head = String.format(Locale.US, "首个断点：%s · 第 %d/%d 步「%s」",
                chain, idx + 1, labels.length, label);
        String hint = hintFor(label);
        if (stalledMs > 0) {
            hint = String.format(Locale.US, "已停住 %s%s", seconds(stalledMs),
                    hint.isEmpty() ? "" : " · " + hint);
        }
        return new Result(Level.FAILED, head, hint);
    }

    private static Result running(String chain, String[] labels, int idx, long stalledMs) {
        String label = labels[idx];
        String head = String.format(Locale.US, "进行中：%s · 第 %d/%d 步「%s」",
                chain, idx + 1, labels.length, label);
        String hint;
        if (stalledMs >= STALL_MS) {
            hint = String.format(Locale.US, "已 %s 无进展，先查这一步%s",
                    seconds(stalledMs), hintTail(label));
        } else {
            hint = stalledMs > 0 ? seconds(stalledMs) : "";
        }
        return new Result(Level.RUNNING, head, hint);
    }

    private static String hintTail(String label) {
        String h = hintFor(label);
        return h.isEmpty() ? "" : "：" + h;
    }

    private static String seconds(long ms) {
        return String.format(Locale.US, "%.1fs", ms / 1000.0);
    }

    /**
     * 每个阶段的「下一步看哪里」。
     *
     * <p>只写有排查价值的方向，不写泛泛的「检查网络」——那些对现场没用。
     */
    public static String hintFor(String label) {
        if (label == null) return "";
        if (label.contains("蓝牙监听")) return "车机没回连：车机删本机→重配，先确认信标 4 条在跑";
        if (label.contains("iAP2 链路")) return "RFCOMM 已连但链路没同步，看车机在等哪条消息";
        if (label.contains("识别") || label.contains("MFi")) return "看 MFi 宽松/严格模式与 0x1D01 应答";
        if (label.contains("热点")) return "0x5703/0x4301 没给凭据或密码错：确认车机热点开着";
        if (label.contains("mDNS")) return "可能连到旧 AP：看 BSSID 对账与车机 CarPlay 窗口";
        if (label.contains("AirPlay")) return "看 pair-verify 与车机是否已关闭邀请窗口";
        if (label.contains("推流")) return "链路已建立但没帧：看 Car+ 是否在输出";
        if (label.contains("Car+ 打开通道")) return "Car+ 没起：锁屏先 startService；确认 Car+ 帧率/连接设置";
        if (label.contains("6 条通道")) return "通道窗口很短：确认 7240/8240/9240 等端口已 LISTEN";
        if (label.contains("版本")) return "协议版本应为 4.0，不要发 1.1";
        if (label.contains("设备信息")) return "98307 的 display 字段是必填";
        if (label.contains("特征配置")) return "看 USB_MTU / I_FRAME_INTERVAL / CONNECT_TYPE";
        if (label.contains("鉴权")) return "CarLife 鉴权失败：看 65540 之后的应答";
        if (label.contains("编码器")) return "看请求的 fps/分辨率与车机回报";
        if (label.contains("开始推流")) return "首帧未到：Car+ 可能已退出 USB_ADB 状态";
        return "";
    }
}
