package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 测量记录。提交时按测量时刻匹配唯一有效证书，并固化未舍入计算值与合格判定。
 *
 * @param id             测量记录 ID（自增）
 * @param measurementKey 业务测量键，全局唯一（幂等键）
 * @param instrumentId   仪器 ID
 * @param measuredAt     测量时刻（UTC）
 * @param rawReading     原始读数，最多 6 位小数
 * @param lowerLimit     合格下限（含端点）
 * @param upperLimit     合格上限（含端点）
 * @param submittedBy    提交人
 * @param certificateId  提交时匹配到的校准证书 ID
 * @param computedValue  未舍入计算值 a×读数+b
 * @param passed         是否合格（基于未舍入值，含端点）
 * @param status         状态：PENDING 待放行 / RELEASED 已放行（被期间核查隔离不改变本状态）
 * @param suspect        是否被未解除 FAIL 核查隔离；true 时立即失去“当前可用”资格，放行历史保留
 * @param createdAt      提交时间（UTC）
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
        boolean suspect,
        Instant createdAt) {

    /**
     * 显示值：未舍入计算值按 HALF_UP 保留 4 位小数；仅用于展示，不参与合格判定。
     */
    public BigDecimal displayValue() {
        return computedValue.setScale(4, java.math.RoundingMode.HALF_UP);
    }
}
