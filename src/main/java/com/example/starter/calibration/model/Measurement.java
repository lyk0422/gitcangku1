package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;

/**
 * 测量记录。提交时按测量时刻匹配唯一有效证书，冻结精确计算值（分数）与所用节点区间，
 * 查询时不改用新证书重新计算。
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
 * @param computedNum    精确计算值分子（已约分）
 * @param computedDen    精确计算值分母（正整数）
 * @param nodeLoIndex    曲线插值区间左节点序号；线性证书为 null
 * @param nodeHiIndex    曲线插值区间右节点序号；命中节点时与左序号相同；线性证书为 null
 * @param passed         是否合格（基于精确分数，含端点）
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
        BigInteger computedNum,
        BigInteger computedDen,
        Integer nodeLoIndex,
        Integer nodeHiIndex,
        boolean passed,
        MeasurementStatus status,
        Instant createdAt) {

    /**
     * 精确计算值（分数）。合格判定基于该值，不先转有限小数。
     */
    public Rational computed() {
        return new Rational(computedNum, computedDen);
    }

    /**
     * 显示值：精确计算值按 HALF_UP 保留 4 位小数；仅用于展示，不参与合格判定。
     */
    public BigDecimal displayValue() {
        return computed().toDisplay(4);
    }
}
