package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 测量记录。提交时按测量时刻匹配唯一有效证书，并固化计算结果与合格判定。
 * 线性模式：rawReading 为原始读数，computedValue 为 a×读数+b。
 * 二次反解模式：rawReading 为校准后信号 y，computedValue 为分支内反解根的近似值
 * （不参与合格判定）；合格判定仅依赖 evalLo/evalHi（期望输入区间端点的精确函数值）
 * 与信号的精确比较，提交时一并冻结。
 *
 * @param id             测量记录 ID（自增）
 * @param measurementKey 业务测量键，全局唯一（幂等键）
 * @param instrumentId   仪器 ID
 * @param measuredAt     测量时刻（UTC）
 * @param rawReading     原始读数（线性）或校准后信号 y（二次反解），最多 6 位小数
 * @param lowerLimit     合格下限（含端点）；二次模式为期望输入下界 lo
 * @param upperLimit     合格上限（含端点）；二次模式为期望输入上界 hi
 * @param submittedBy    提交人
 * @param certificateId  提交时匹配到的校准证书 ID
 * @param computedValue  未舍入计算值（线性 a×读数+b；二次为反解根近似值）
 * @param passed         是否合格（基于精确值，含端点）
 * @param status         状态：PENDING 待放行 / RELEASED 已放行
 * @param evalLo         二次模式：f(lo) 精确函数值（合格依据）；线性为 null
 * @param evalHi         二次模式：f(hi) 精确函数值（合格依据）；线性为 null
 * @param rootLower      二次模式：根夹逼区间下界；线性为 null
 * @param rootUpper      二次模式：根夹逼区间上界（宽度 ≤ 0.000001）；线性为 null
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
        BigDecimal evalLo,
        BigDecimal evalHi,
        BigDecimal rootLower,
        BigDecimal rootUpper,
        Instant createdAt) {

    /**
     * 显示值：计算值（线性为精确值，二次为根的近似值）按 HALF_UP 保留 4 位小数；
     * 仅用于展示，不参与合格判定。
     */
    public BigDecimal displayValue() {
        return computedValue.setScale(4, java.math.RoundingMode.HALF_UP);
    }
}
