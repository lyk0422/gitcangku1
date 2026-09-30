package com.example.starter.maintenance.domain;

/**
 * 大幅读数修订判定纯逻辑（无数据库、无 Spring 依赖，便于单元测试）。
 * 差额按修订值与当前最后一条有效读数之差计算，绝对值超出设备阈值即需见证。
 */
public final class RevisionDecision {

    private RevisionDecision() {
    }

    /**
     * 是否需要另一名维护人见证。
     *
     * @param currentCumulativeMinutes  当前有效读数累计工时（分钟）
     * @param proposedCumulativeMinutes 申请的新读数累计工时（分钟）
     * @param thresholdMinutes          设备当前见证阈值（分钟，正整数）
     * @return 差额绝对值严格大于阈值时返回 true；恰好等于阈值属于阈值内修订，直接生效
     */
    public static boolean requiresWitness(long currentCumulativeMinutes, long proposedCumulativeMinutes,
                                          long thresholdMinutes) {
        return Math.abs(proposedCumulativeMinutes - currentCumulativeMinutes) > thresholdMinutes;
    }

    /**
     * 差额（分钟）= 申请值 - 当前有效值，保留正负号。
     */
    public static long delta(long currentCumulativeMinutes, long proposedCumulativeMinutes) {
        return proposedCumulativeMinutes - currentCumulativeMinutes;
    }
}
