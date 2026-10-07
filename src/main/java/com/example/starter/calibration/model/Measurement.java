package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;

/**
 * 测量记录。提交时按测量时刻匹配唯一有效证书，并固化未舍入计算值与合格判定。
 * DRIFT 模式额外引用两份漂移档案，校正值保存为精确有理数（分子/分母）。
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
 * @param mode           测量模式：STANDARD 普通 / DRIFT 参考漂移
 * @param profileT0Id    DRIFT 模式：下界漂移档案 ID；STANDARD 为 null
 * @param profileT1Id    DRIFT 模式：上界漂移档案 ID；STANDARD 为 null
 * @param computedValue  STANDARD 模式：未舍入计算值 a×读数+b；DRIFT 为 null
 * @param computedNum    DRIFT 模式：校正后精确有理值分子；STANDARD 为 null
 * @param computedDen    DRIFT 模式：校正后精确有理值分母（>0）；STANDARD 为 null
 * @param passed         是否合格（基于未舍入精确值，含端点）
 * @param status         状态：PENDING 待放行 / RELEASED 已放行
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
        MeasurementMode mode,
        Long profileT0Id,
        Long profileT1Id,
        BigDecimal computedValue,
        BigInteger computedNum,
        BigInteger computedDen,
        boolean passed,
        MeasurementStatus status,
        Instant createdAt) {

    /**
     * 未舍入精确值：STANDARD 为 a×读数+b；DRIFT 为证书值减线性插值偏移的精确有理数。
     */
    public Rational exactValue() {
        if (mode == MeasurementMode.DRIFT) {
            return new Rational(computedNum, computedDen);
        }
        return Rational.of(computedValue);
    }

    /**
     * 显示值：未舍入精确值按 HALF_UP 保留 4 位小数；仅用于展示，不参与合格判定。
     */
    public BigDecimal displayValue() {
        return exactValue().display(4);
    }
}
