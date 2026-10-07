package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 测量记录。提交时按测量时刻匹配唯一有效证书，并固化计算结果与合格判定。
 * QUADRATIC 模式下 rawReading 为信号 y，lowerLimit/upperLimit 为期望输入合格区间 [lo,hi]，
 * 提交时冻结分支方向、根夹逼区间与端点函数值（精确合格依据），历史展示不随撤证重算。
 *
 * @param id             测量记录 ID（自增）
 * @param measurementKey 业务测量键，全局唯一（幂等键）
 * @param instrumentId   仪器 ID
 * @param measuredAt     测量时刻（UTC）
 * @param mode           匹配证书模式：LINEAR / QUADRATIC
 * @param rawReading     原始读数（线性）或信号 y（二次），最多 6 位小数
 * @param lowerLimit     合格下限（含端点）；二次模式为输入区间下界 lo
 * @param upperLimit     合格上限（含端点）；二次模式为输入区间上界 hi
 * @param submittedBy    提交人
 * @param certificateId  提交时匹配到的校准证书 ID
 * @param computedValue  未舍入计算值：线性为 a×读数+b；二次为反解根夹逼中点（仅展示）
 * @param passed         是否合格（线性基于未舍入计算值；二次基于端点函数值精确比较，均含端点）
 * @param branch         二次分支单调方向；线性为 null
 * @param rootLower      二次反解根夹逼区间下界（数值解，仅展示）；线性为 null
 * @param rootUpper      二次反解根夹逼区间上界，与下界宽度不大于 0.000001；线性为 null
 * @param evalLower      二次模式 f(lo) 精确值，合格判定依据；线性为 null
 * @param evalUpper      二次模式 f(hi) 精确值，合格判定依据；线性为 null
 * @param status         状态：PENDING 待放行 / RELEASED 已放行
 * @param createdAt      提交时间（UTC）
 */
public record Measurement(
        long id,
        String measurementKey,
        String instrumentId,
        Instant measuredAt,
        CertificateMode mode,
        BigDecimal rawReading,
        BigDecimal lowerLimit,
        BigDecimal upperLimit,
        String submittedBy,
        long certificateId,
        BigDecimal computedValue,
        boolean passed,
        Branch branch,
        BigDecimal rootLower,
        BigDecimal rootUpper,
        BigDecimal evalLower,
        BigDecimal evalUpper,
        MeasurementStatus status,
        Instant createdAt) {

    /**
     * 显示值：计算值（线性）或反解根（二次）按 HALF_UP 保留 4 位小数；仅用于展示，不参与合格判定。
     */
    public BigDecimal displayValue() {
        return computedValue.setScale(4, java.math.RoundingMode.HALF_UP);
    }
}
