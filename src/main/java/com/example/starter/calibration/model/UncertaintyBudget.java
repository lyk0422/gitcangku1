package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 不确定度预算版本（不可变）。批量修改分量以“完整替换”方式产生新版本，旧版本永久保留。
 * 登记时即固化当时仪器阈值版本（id/版本号/值）与合成口径，后续阈值修订不改写本版本。
 *
 * @param id                  预算版本 ID（自增）
 * @param measurementId       所属测量记录 ID
 * @param budgetVersion       测量内预算版本号，从 1 起逐版递增
 * @param method              合成口径，当前固定 RSS_V1（平方和开根）
 * @param combinedUncertainty 未舍入合成不确定度（RSS），判定阈值使用该值
 * @param thresholdId         固化的阈值版本行 ID
 * @param thresholdVersion    固化的仪器阈值版本号
 * @param thresholdValue      固化的阈值上限值快照
 * @param createdBy           预算登记/修订人
 * @param createdAt           预算版本创建时间（UTC）
 * @param components          分量明细，按 (ordinal, id) 稳定排序
 */
public record UncertaintyBudget(
        long id,
        long measurementId,
        int budgetVersion,
        UncertaintyMethod method,
        BigDecimal combinedUncertainty,
        long thresholdId,
        int thresholdVersion,
        BigDecimal thresholdValue,
        String createdBy,
        Instant createdAt,
        List<UncertaintyComponent> components) {
}
