package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 测量记录。提交时按测量时刻匹配唯一有效证书，并固化未舍入精确值与合格判定；
 * 已配置分辨率的仪器同时冻结配置版本、格点整数 k 与量化值，配置换版不回算。
 *
 * @param id                 测量记录 ID（自增）
 * @param measurementKey     业务测量键，全局唯一（幂等键）
 * @param instrumentId       仪器 ID
 * @param measuredAt         测量时刻（UTC）
 * @param rawReading         原始读数，最多 6 位小数
 * @param lowerLimit         合格下限（含端点）
 * @param upperLimit         合格上限（含端点）
 * @param submittedBy        提交人
 * @param certificateId      提交时匹配到的校准证书 ID
 * @param computedValue      未舍入精确计算值 a×读数+b；越界（超精度）时为 null，测量进入待审
 * @param resolutionVersion  提交时冻结的分辨率配置版本；未配置仪器或越界时为 null
 * @param gridK              冻结的格点整数 k；未量化时为 null
 * @param quantizedValue     冻结的量化值 o+k×s；未量化或越界时为 null
 * @param passed             是否合格（已配置仪器需精确值与量化值均在闭区间；越界时为 false）
 * @param status             状态：PENDING 待放行（含越界待审）/ RELEASED 已放行
 * @param createdAt          提交时间（UTC）
 */
public record Measurement(
        long id,
        String measurementKey,
        String instrumentId,
        Instant measuredAt,
        BigDecimal rawReading,
        BigDecimal lowerLimit,
        BigDecimal upperLimit,
        String submittedBy,
        long certificateId,
        BigDecimal computedValue,
        Integer resolutionVersion,
        BigDecimal gridK,
        BigDecimal quantizedValue,
        boolean passed,
        MeasurementStatus status,
        Instant createdAt) {

    /**
     * 显示值：未舍入计算值按 HALF_UP 保留 4 位小数；仅用于展示，不参与任何判定。
     * 越界待审（无精确值）时返回 null。
     */
    public BigDecimal displayValue() {
        return computedValue == null ? null : computedValue.setScale(4, java.math.RoundingMode.HALF_UP);
    }
}
