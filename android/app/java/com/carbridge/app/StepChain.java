package com.carbridge.app;

/**
 * 步骤链的纯状态判定（从 MainActivity 的 renderSteps 里抽出来的决策部分）。
 *
 * <p>为什么单独抽：这条逻辑有过两个真 bug ——
 * <ol>
 *   <li>任意一步失败时把**所有已完成步骤**退回「待开始」，界面上出现「0/15 + 第 2 步失败」；</li>
 *   <li>到达终态步（开始推流）时没有把最后一步标完成，界面一直转圈，和真卡住长得一样。</li>
 * </ol>
 * 它没有 UI、没有 Context，是最该被测试覆盖的一段，所以先抽出来加断言，再让界面调用它。
 */
public final class StepChain {

    /** 单步的显示状态。 */
    public enum State {
        /** 已完成（✓）。 */
        DONE,
        /** 正在进行（●）。 */
        ACTIVE,
        /** 失败（✕）。 */
        FAILED,
        /** 还没开始（○）。 */
        PENDING
    }

    private StepChain() {
    }

    /**
     * 判定第 {@code index} 步该显示什么。
     *
     * @param index      当前步下标（0 起）
     * @param currentIdx 正在进行的步；null = 还没开始
     * @param failedIdx  失败步；null = 没失败
     * @param lastIdx    最后一步的下标
     */
    public static State stateOf(int index, Integer currentIdx, Integer failedIdx, int lastIdx) {
        // 失败步优先：它自己是 ✕，它之前的仍然算完成（这正是第 1 个 bug 的修法）
        if (failedIdx != null) {
            if (index == failedIdx) return State.FAILED;
            if (index < failedIdx) return State.DONE;
            return State.PENDING;
        }
        if (currentIdx == null) return State.PENDING;
        // 到达终态步 = 全部完成（第 2 个 bug 的修法）
        if (currentIdx >= lastIdx) {
            return index <= lastIdx ? State.DONE : State.PENDING;
        }
        if (index < currentIdx) return State.DONE;
        if (index == currentIdx) return State.ACTIVE;
        return State.PENDING;
    }

    /** 已经完成的步数（进度条分子）。 */
    public static int doneCount(int total, Integer currentIdx, Integer failedIdx) {
        int lastIdx = total - 1;
        int done = 0;
        for (int i = 0; i < total; i++) {
            if (stateOf(i, currentIdx, failedIdx, lastIdx) == State.DONE) done++;
        }
        return done;
    }
}
