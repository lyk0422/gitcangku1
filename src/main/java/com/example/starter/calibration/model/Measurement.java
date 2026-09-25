package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 测量记录。提交时按测量时刻匹配唯一有效证书，并固化未舍入计算值、不确定度与合格判定。
 * 当前行保存当前有效版本的快照；历史版本见 {@link MeasurementVersion}。
 *
 * @param id                   测量记录 ID（自增）
 * @param measurementKey       业务测量键，全局唯一（幂等键）
 * @param batchId              提交批次 ID，未指定为 null
 * @param referenceKey         幂等引用键，未提供为 null；同键重放返回已有结果，失败不占键
 * @param referenceFingerprint 引用键指纹（测量版本|证书版本|时刻|输入摘要 的 SHA-256），无引用键为 null
 * @param instrumentId         仪器 ID
 * @param measuredAt           测量时刻（UTC）
 * @param rawReading           原始读数，最多 6 位小数
 * @param lowerLimit           合格下限（含端点）
 * @param upperLimit           合格上限（含端点）
 * @param submittedBy          提交人
 * @param certificateId        当前版本引用的校准证书 ID
 * @param certVersion          当前版本引用的证书版本快照
 * @param compensationCoeff    当前版本补偿系数快照
 * @param uncertaintyVersion   当前版本不确定度版本快照
 * @param computedValue        当前版本未舍入计算值 a×读数+b
 * @param uncertainty          当前版本不确定度 |补偿系数×读数|
 * @param passed               是否合格（基于未舍入值，含端点）
 * @param version              当前测量版本号，从 1 开始
 * @param status               状态：PENDING 待放行 / RELEASED 已放行
 * @param createdAt            提交时间（UTC）
 */
public record Measurement(
        long id,
        String measurementKey,
        String batchId,
        String referenceKey,
        String referenceFingerprint,
        String instrumentId,
        Instant measuredAt,
        BigDecimal rawReading,
        BigDecimal lowerLimit,
        BigDecimal upperLimit,
        String submittedBy,
        long certificateId,
        String certVersion,
        BigDecimal compensationCoeff,
        String uncertaintyVersion,
        BigDecimal computedValue,
        BigDecimal uncertainty,
        boolean passed,
        int version,
        MeasurementStatus status,
        Instant createdAt) {

    /**
     * 显示值：未舍入计算值按 HALF_UP 保留 4 位小数；仅用于展示，不参与合格判定。
     */
    public BigDecimal displayValue() {
        return computedValue.setScale(4, java.math.RoundingMode.HALF_UP);
    }
}
