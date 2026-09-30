package com.example.starter.germination.domain;

import java.time.Instant;

/**
 * 单次发芽率检测记录（复检亦为追加的新记录，历史记录不被修改）。
 *
 * @param testNo           检测编号，全库唯一，同一编号只能归属一个批次
 * @param batchNo          所属种子批次编号
 * @param sampleCount      样本数，单位粒，合法区间 100-400
 * @param germinatedCount  发芽粒数，单位粒，满足 0 ≤ germinatedCount ≤ sampleCount
 * @param status           记录状态：VALID 有效 / VOID 已作废
 * @param recordedAt       检测登记时刻（UTC 时间线），复检按追加时间排序
 * @param voidedAt         作废时刻；null 表示尚未作废
 */
public record GerminationTest(String testNo,
                              String batchNo,
                              int sampleCount,
                              int germinatedCount,
                              TestStatus status,
                              Instant recordedAt,
                              Instant voidedAt) {

    /**
     * 发芽比例（百分数），例如 86 粒 / 100 粒 = 86.00。
     *
     * @return 发芽率百分数，单位 %，保留两位小数
     */
    public java.math.BigDecimal ratioPercent() {
        return java.math.BigDecimal.valueOf(germinatedCount)
                .multiply(java.math.BigDecimal.valueOf(100))
                .divide(java.math.BigDecimal.valueOf(sampleCount), 2, java.math.RoundingMode.HALF_UP);
    }
}
