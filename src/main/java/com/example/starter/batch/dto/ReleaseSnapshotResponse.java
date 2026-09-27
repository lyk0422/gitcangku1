package com.example.starter.batch.dto;

import java.time.Instant;
import java.util.List;

/**
 * 放行快照：第二批准生效（RELEASED）时固化，之后任何封箱/作废都不得改写。
 *
 * <p>字段、排序、时间精度与 null 含义长期稳定：
 * <ul>
 *   <li>{@code plannedQuantity} 固化登记的计划数量（要求值）；</li>
 *   <li>{@code sealedQuantity} 固化放行时刻全部活跃封箱数量之和（实际值）；</li>
 *   <li>{@code quantityDifference} 固化差额 = sealedQuantity - plannedQuantity，放行时为 0；</li>
 *   <li>{@code usedLabels} 为已用标签号的规范排序摘要：按数值升序、去重，序列不可变；</li>
 *   <li>{@code seals} 固化每个封箱版本（sealKey、version、labelNo、quantity、sealedAt），按标签号升序。</li>
 * </ul>
 */
public record ReleaseSnapshotResponse(
        String batchKey,
        long plannedQuantity,
        long sealedQuantity,
        long quantityDifference,
        long usedLabelCount,
        long sealCount,
        List<Long> usedLabels,
        List<FrozenSeal> seals,
        Instant createdAt
) {

    /**
     * 快照内固化的单个封箱版本；作废后重封会产生新版本，但快照保留放行时刻的版本。
     */
    public record FrozenSeal(
            String sealKey,
            int version,
            long labelNo,
            long quantity,
            Instant sealedAt
    ) {
    }
}
