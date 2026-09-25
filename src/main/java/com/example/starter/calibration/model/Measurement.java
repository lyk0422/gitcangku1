package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * 测量记录（逻辑实体，当前版本冗余于主表）。提交时按测量时刻匹配唯一有效证书，
 * 并固化证书计算值；记录环境时同时固化环境补偿系数版本、补偿后值与合格判定。
 *
 * @param id                    测量记录 ID（自增）
 * @param measurementKey        业务测量键，全局唯一（幂等键）
 * @param instrumentId          仪器 ID
 * @param instrumentModel       仪器型号，用于匹配补偿系数版本；未提供为 null
 * @param measuredAt            测量时刻（UTC）
 * @param rawReading            原始读数，最多 6 位小数
 * @param lowerLimit            合格下限（含端点）
 * @param upperLimit            合格上限（含端点）
 * @param submittedBy           提交人
 * @param certificateId         当前版本匹配到的校准证书 ID
 * @param computedValue         当前版本未舍入证书计算值 a×读数+b
 * @param passed                当前版本证书计算值是否合格（基于未舍入值，含端点）
 * @param temperature           当前版本环境温度（摄氏度）；未记录环境为 null
 * @param humidity              当前版本环境相对湿度（%RH）；未记录环境为 null
 * @param uncertainty           当前版本测量不确定度（与读数同量纲）；未提供为 null
 * @param compensationProfileId 当前版本固化的补偿系数版本 ID；未补偿为 null
 * @param compensatedValue      当前版本补偿后测量值（HALF_UP 6 位小数）；未补偿为 null
 * @param compensatedPassed     当前版本补偿后是否合格（含端点）；未补偿为 null
 * @param currentVersion        当前版本号，从 1 起；重算一次加 1
 * @param status                状态：PENDING / RELEASED / REJECTED
 * @param createdAt             提交时间（UTC）
 */
public record Measurement(
        long id,
        String measurementKey,
        String instrumentId,
        String instrumentModel,
        Instant measuredAt,
        BigDecimal rawReading,
        BigDecimal lowerLimit,
        BigDecimal upperLimit,
        String submittedBy,
        long certificateId,
        BigDecimal computedValue,
        boolean passed,
        BigDecimal temperature,
        BigDecimal humidity,
        BigDecimal uncertainty,
        Long compensationProfileId,
        BigDecimal compensatedValue,
        Boolean compensatedPassed,
        int currentVersion,
        MeasurementStatus status,
        Instant createdAt) {

    /**
     * 显示值：未舍入证书计算值按 HALF_UP 保留 4 位小数；仅用于展示，不参与合格判定。
     */
    public BigDecimal displayValue() {
        return computedValue.setScale(4, RoundingMode.HALF_UP);
    }

    /** 是否记录了环境（温湿度齐全）；环境补偿门禁据此判定。 */
    public boolean hasEnvironment() {
        return temperature != null && humidity != null;
    }
}
