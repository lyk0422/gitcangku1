package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * 测量记录。提交时按测量时刻匹配唯一有效证书，并固化未舍入计算值与合格判定。
 * 线性证书：computedValue 为精确值 a×读数+b。
 * 曲线证书：computedValue 为 null，精确值以既约分数 computedNum/computedDen 保存，
 * 并冻结所用相邻节点区间（落在单节点时上下节点相同），查询时不得改用新证书。
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
 * @param certType       匹配证书类型：LINEAR / CURVE
 * @param computedValue  线性证书未舍入计算值；CURVE 为 null
 * @param computedNum    CURVE 插值精确值既约分数分子；LINEAR 为 null
 * @param computedDen    CURVE 插值精确值既约分数分母（正整数）；LINEAR 为 null
 * @param nodeLoRaw      冻结区间下节点原始值；LINEAR 为 null
 * @param nodeLoCorrected 冻结区间下节点校正值；LINEAR 为 null
 * @param nodeHiRaw      冻结区间上节点原始值；LINEAR 为 null
 * @param nodeHiCorrected 冻结区间上节点校正值；LINEAR 为 null
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
        CertificateType certType,
        BigDecimal computedValue,
        String computedNum,
        String computedDen,
        BigDecimal nodeLoRaw,
        BigDecimal nodeLoCorrected,
        BigDecimal nodeHiRaw,
        BigDecimal nodeHiCorrected,
        boolean passed,
        MeasurementStatus status,
        Instant createdAt) {

    /**
     * 显示值：精确计算值按 HALF_UP 保留 4 位小数；仅用于展示，不参与合格判定。
     */
    public BigDecimal displayValue() {
        if (certType == CertificateType.CURVE) {
            return new BigDecimal(computedNum).divide(new BigDecimal(computedDen), 4, RoundingMode.HALF_UP);
        }
        return computedValue.setScale(4, RoundingMode.HALF_UP);
    }
}
