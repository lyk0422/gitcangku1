package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 测量记录。提交时按测量时刻匹配唯一有效证书，并固化未舍入计算值与合格判定。
 * 批次读数还携带批次与修订号；被离群 REJECT 裁决排除后 {@code excluded=true}、状态为 EXCLUDED。
 *
 * @param id                 测量记录 ID（自增）
 * @param measurementKey     业务测量键，全局唯一（幂等键）
 * @param instrumentId       仪器 ID
 * @param measuredAt         测量时刻（UTC）
 * @param rawReading         原始读数，最多 6 位小数
 * @param lowerLimit         合格下限（含端点）；批次读数为目标值-允许偏差
 * @param upperLimit         合格上限（含端点）；批次读数为目标值+允许偏差
 * @param submittedBy        提交人；不得复核本读数的离群记录
 * @param certificateId      提交时匹配到的校准证书 ID（证书版本）
 * @param computedValue      未舍入计算值 a×读数+b
 * @param passed             是否合格（基于未舍入值，含端点）；批次场景 FALSE 即离群
 * @param status             状态：PENDING 待放行 / RELEASED 已放行 / EXCLUDED 已排除
 * @param batchId            所属测量批次 ID；独立测量为 null
 * @param revisionNo         提交时的测量修订版本号；独立测量为 null
 * @param excluded           是否已被离群 REJECT 裁决排除统计与放行
 * @param excludedAt         排除时间（UTC）；未排除为 null
 * @param excludedByOutlierId 导致排除的离群记录 ID；未排除为 null
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
        boolean passed,
        MeasurementStatus status,
        Long batchId,
        Integer revisionNo,
        boolean excluded,
        Instant excludedAt,
        Long excludedByOutlierId,
        Instant createdAt) {

    /**
     * 显示值：未舍入计算值按 HALF_UP 保留 4 位小数；仅用于展示，不参与合格判定。
     */
    public BigDecimal displayValue() {
        return computedValue.setScale(4, java.math.RoundingMode.HALF_UP);
    }
}
