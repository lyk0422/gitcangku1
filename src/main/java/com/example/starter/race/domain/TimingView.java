package com.example.starter.race.domain;

/**
 * 选手分段通过记录视图。
 */
public interface TimingView {

    /** 全局唯一分段记录ID。 */
    String timingId();

    /** 选手参赛号。 */
    String bib();

    /** 检查点代码。 */
    String checkpointCode();

    /** 检查点顺序，从1连续递增。 */
    int position();

    /** 通过该检查点的累计耗时（毫秒，1~86400000）。 */
    long elapsedMillis();

    /** 计时资格；默认 COUNTED（历史记录与纯逻辑测试视图无医疗暂停概念）。 */
    default TimingHoldStatus holdStatus() {
        return TimingHoldStatus.COUNTED;
    }
}
