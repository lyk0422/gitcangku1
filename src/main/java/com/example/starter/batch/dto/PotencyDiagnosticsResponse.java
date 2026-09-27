package com.example.starter.batch.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 批次效价只读诊断：当前最新模型、按诊断时刻计算的当前效价与放行余量、历史模型版本与固化记录。
 * 查询不修改任何状态；未登记效价模型的批次 current 为 null。
 */
public record PotencyDiagnosticsResponse(
        String batchKey,
        Instant diagnosedAt,
        CurrentPotency current,
        List<PotencyView> versions,
        List<PotencyCalculationView> calculations
) {

    /**
     * 诊断时刻的效价结论：actual 为 HALF_UP 6 位当前效价，margin 为 actual - threshold。
     */
    public record CurrentPotency(
            int modelVersion,
            Instant anchorAt,
            BigDecimal dailyDecayRate,
            BigDecimal actualPotency,
            BigDecimal requiredPotency,
            BigDecimal margin,
            boolean eligible
    ) {
    }
}
