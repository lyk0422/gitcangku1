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

    /**
     * 计时排除原因；null 表示正常计时、参与覆盖与排名。
     * 医疗暂停生效期间提交的计时返回 {@link TimingExclusionReason#MEDICAL_HOLD}，
     * 不覆盖检查点、不参与排名，但原始记录保留。
     */
    default TimingExclusionReason exclusionReason() {
        return null;
    }
}
