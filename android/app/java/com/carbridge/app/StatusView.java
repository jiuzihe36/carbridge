package com.carbridge.app;

/**
 * 连接状态 → 界面该怎么显示（纯映射，没有 Context、没有 View）。
 *
 * <p>从 MainActivity 的 {@code onState()} 里抽出来的原因：那个 switch 里同时藏着
 * 四条**行为规则**，而不只是文案：
 * <ol>
 *   <li>哪些状态要开始/停止「已用时」计时；</li>
 *   <li>哪个状态要清空进度（CLOSED 不清就会停在断开前的 n/15，看着像还在连）；</li>
 *   <li>哪个状态要显示「手动处理」提示（ERROR）；</li>
 *   <li>哪个状态要把主按钮切回「连接」（CLOSED）。</li>
 * </ol>
 * 这些规则以前只能靠读界面猜，现在被抽成字段，并由单测逐条锁住。
 */
public final class StatusView {

    /** 语义色：具体色值由界面层解析（这里不碰资源）。 */
    public enum Tone { IDLE, WORK, OK, ERR }

    /**
     * 状态对应的文案种类 + 该状态的显示规则。
     *
     * <p>规则写在枚举里（而不是 switch 里）是为了能被 JVM 单测直接覆盖：
     * 测试工程编不了 HuClient（它依赖 Android），但可以遍历 Kind 校验每条规则。
     */
    public enum Kind {
        IDLE(Tone.IDLE, false, false, false, false),
        WAITING(Tone.WORK, true, false, false, false),
        CONNECTING(Tone.WORK, true, false, false, false),
        HANDSHAKING(Tone.WORK, true, false, false, false),
        STREAMING(Tone.OK, true, false, false, false),
        ERROR(Tone.ERR, false, false, true, false),
        /** 断开：停表、清进度、按钮切回「连接」——三件事缺一界面就会骗人。 */
        CLOSED(Tone.IDLE, false, true, false, true);

        public final Tone tone;
        public final boolean busy;
        public final boolean resetSteps;
        public final boolean manualHint;
        public final boolean releaseButton;

        Kind(Tone tone, boolean busy, boolean resetSteps, boolean manualHint, boolean releaseButton) {
            this.tone = tone;
            this.busy = busy;
            this.resetSteps = resetSteps;
            this.manualHint = manualHint;
            this.releaseButton = releaseButton;
        }
    }

    public final Kind kind;
    public final Tone tone;
    /** 是否处于「正在工作」——决定已用时计时启停。 */
    public final boolean busy;
    /** 是否要清空进度链（断开时必须清）。 */
    public final boolean resetSteps;
    /** 是否显示「需要人工处理」的提示（失败时）。 */
    public final boolean manualHint;
    /** 是否把主按钮切回「连接」（断开时）。 */
    public final boolean releaseButton;

    private StatusView(Kind kind, Tone tone, boolean busy,
                       boolean resetSteps, boolean manualHint, boolean releaseButton) {
        this.kind = kind;
        this.tone = tone;
        this.busy = busy;
        this.resetSteps = resetSteps;
        this.manualHint = manualHint;
        this.releaseButton = releaseButton;
    }

    public static StatusView of(Kind k) {
        Kind kind = k == null ? Kind.IDLE : k;
        return new StatusView(kind, kind.tone, kind.busy, kind.resetSteps,
                kind.manualHint, kind.releaseButton);
    }
}
