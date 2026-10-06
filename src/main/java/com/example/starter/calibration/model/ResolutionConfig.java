package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 仪器分辨率格点配置。每台仪器最多一条当前配置；版本从 1 起，
 * 更新携带 expectedVersion 乐观并发，换版不回算既有测量。
 *
 * @param instrumentId 仪器 ID
 * @param version      配置版本，从 1 起，每次成功更新加 1
 * @param origin       格点原点 o，最多 6 位小数，可为负
 * @param step         格点步长 s，正数，最多 6 位小数
 * @param updatedBy    最近一次配置操作人
 * @param updatedAt    最近一次配置时间（UTC）
 */
public record ResolutionConfig(
        String instrumentId,
        int version,
        BigDecimal origin,
        BigDecimal step,
        String updatedBy,
        Instant updatedAt) {

    /**
     * 量化：k 取 (x-origin)/step 的最近整数，正好半格时取偶数整数（HALF_EVEN）。
     */
    public BigDecimal quantizeK(BigDecimal exact) {
        return exact.subtract(origin).divide(step, 0, java.math.RoundingMode.HALF_EVEN);
    }

    /**
     * 量化值：origin + k×step。
     */
    public BigDecimal quantize(BigDecimal k) {
        return origin.add(k.multiply(step));
    }
}
