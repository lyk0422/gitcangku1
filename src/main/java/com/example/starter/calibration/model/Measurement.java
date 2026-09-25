package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * 测量记录（当前生效版本）。提交时按测量时刻匹配或按显式引用解析证书，
 * 固化未舍入计算值、扩展不确定度与合格判定；替换标准器会生成新版本并更新当前字段。
 *
 * @param id                    测量记录 ID（自增）
 * @param measurementKey        业务测量键，全局唯一（幂等键）
 * @param instrumentId          仪器/被测对象 ID
 * @param standardId            当前版本显式引用的标准器 ID；按仪器自动匹配时与 instrumentId 相同
 * @param measuredAt            测量时刻（UTC）
 * @param rawReading            原始读数，最多 6 位小数
 * @param lowerLimit            合格下限（含端点）
 * @param upperLimit            合格上限（含端点）
 * @param submittedBy           提交人
 * @param certificateId         当前版本引用的校准证书 ID
 * @param certificateVersion    当前版本引用的证书版本（快照）
 * @param versionNo             当前生效测量版本号，从 1 开始
 * @param computedValue         未舍入计算值 a×读数+b
 * @param expandedUncertainty   当前版本扩展不确定度（k=2）=2×证书标准不确定度
 * @param uncertaintyVersion    当前版本不确定度版本（快照）
 * @param referenceKey          当前版本 referenceKey 指纹
 * @param passed                是否合格（基于未舍入值，含端点）
 * @param status                状态：PENDING 待放行 / RELEASED 已放行
 * @param createdAt             首次提交时间（UTC）
 */
public record Measurement(
        long id,
        String measurementKey,
        String instrumentId,
        String standardId,
        Instant measuredAt,
        BigDecimal rawReading,
        BigDecimal lowerLimit,
        BigDecimal upperLimit,
        String submittedBy,
        long certificateId,
        String certificateVersion,
        int versionNo,
        BigDecimal computedValue,
        BigDecimal expandedUncertainty,
        String uncertaintyVersion,
        String referenceKey,
        boolean passed,
        MeasurementStatus status,
        Instant createdAt) {

    /**
     * 显示值：未舍入计算值按 HALF_UP 保留 4 位小数；仅用于展示，不参与合格判定。
     */
    public BigDecimal displayValue() {
        return computedValue.setScale(4, RoundingMode.HALF_UP);
    }
}
