package com.example.starter.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * 发芽率检测记录（对应一次取样检测，复检以追加新记录方式产生）。
 * 历史记录不可覆盖：作废仅把 status 置为 VOID 并记录 voidedAt，样本数与发芽粒数不变。
 *
 * @param id               数据库主键
 * @param testNo           检测编号，全库唯一；同一编号只能归属一个批次
 * @param batchId          所属批次主键
 * @param sampleSize       样本数，单位粒，合法范围 100-400
 * @param germinatedCount  发芽粒数，单位粒，0 <= 发芽粒数 <= 样本数
 * @param status           记录状态
 * @param testedAt         检测时刻（Asia/Shanghai），决定“最近两次”次序
 * @param voidedAt         作废时刻；未作废为 null
 * @param createdAt        入库时刻（Asia/Shanghai）
 */
public record GerminationTest(
        Long id,
        String testNo,
        Long batchId,
        int sampleSize,
        int germinatedCount,
        TestStatus status,
        LocalDateTime testedAt,
        LocalDateTime voidedAt,
        LocalDateTime createdAt
) {

    /**
     * 发芽率比例，如 86/100 = 0.8600；保留四位小数。
     */
    public BigDecimal ratio() {
        return BigDecimal.valueOf(germinatedCount)
                .divide(BigDecimal.valueOf(sampleSize), 4, RoundingMode.HALF_UP);
    }
}
