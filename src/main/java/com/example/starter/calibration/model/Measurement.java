package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * 测量记录。提交时按测量时刻匹配唯一有效证书，并固化未舍入计算值与合格判定。
 * 参考漂移模式（DRIFT_REF）额外固化两端档案 ID 与校正值（证书值减插值偏移）的精确有理数。
 *
 * @param id             测量记录 ID（自增）
 * @param measurementKey 业务测量键，全局唯一（幂等键）
 * @param mode           测量模式：STANDARD 普通 / DRIFT_REF 参考漂移
 * @param instrumentId   仪器 ID
 * @param measuredAt     测量时刻（UTC）
 * @param rawReading     原始读数，最多 6 位小数
 * @param lowerLimit     合格下限（含端点）
 * @param upperLimit     合格上限（含端点）
 * @param submittedBy    提交人
 * @param certificateId  提交时匹配到的校准证书 ID
 * @param computedValue  证书阶段未舍入值 a×读数+b
 * @param refArchiveId0  参考漂移模式：下端档案 ID（t0）；普通模式为 null
 * @param refArchiveId1  参考漂移模式：上端档案 ID（t1）；普通模式为 null
 * @param correctedNum   校正值约分后的分子；普通模式为 null
 * @param correctedDen   校正值约分后的分母（恒为正）；普通模式为 null
 * @param passed         是否合格（基于未舍入最终值，含端点）
 * @param status         状态：PENDING 待放行 / RELEASED 已放行
 * @param createdAt      提交时间（UTC）
 */
public record Measurement(
        long id,
        String measurementKey,
        MeasurementMode mode,
        String instrumentId,
        Instant measuredAt,
        BigDecimal rawReading,
        BigDecimal lowerLimit,
        BigDecimal upperLimit,
        String submittedBy,
        long certificateId,
        BigDecimal computedValue,
        Long refArchiveId0,
        Long refArchiveId1,
        BigDecimal correctedNum,
        BigDecimal correctedDen,
        boolean passed,
        MeasurementStatus status,
        Instant createdAt) {

    /**
     * 最终值是否为由分子/分母表示的精确有理数（参考漂移模式）。
     */
    public boolean hasFraction() {
        return correctedNum != null && correctedDen != null;
    }

    /**
     * 最终值的精确分子：参考漂移模式为校正值分子，普通模式为证书阶段值。
     */
    public BigDecimal finalNum() {
        return hasFraction() ? correctedNum : computedValue;
    }

    /**
     * 最终值的精确分母：参考漂移模式为校正值分母，普通模式为 1。
     */
    public BigDecimal finalDen() {
        return hasFraction() ? correctedDen : BigDecimal.ONE;
    }

    /**
     * 显示值：未舍入最终值按 HALF_UP 保留 4 位小数；仅用于展示，不参与合格判定。
     */
    public BigDecimal displayValue() {
        if (hasFraction()) {
            return correctedNum.divide(correctedDen, 4, RoundingMode.HALF_UP);
        }
        return computedValue.setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * 最终值的精确字符串：分母为 1 时输出整数，否则输出 "分子/分母"。
     */
    public String finalValueString() {
        if (!hasFraction()) {
            BigDecimal stripped = computedValue.stripTrailingZeros();
            return stripped.compareTo(BigDecimal.ZERO) == 0 ? "0" : stripped.toPlainString();
        }
        if (correctedDen.compareTo(BigDecimal.ONE) == 0) {
            return correctedNum.toPlainString();
        }
        return correctedNum.toPlainString() + "/" + correctedDen.toPlainString();
    }
}
