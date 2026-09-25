package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 测量记录（某一版本的不可变快照）。提交时按测量时刻匹配唯一有效证书，并固化未舍入计算值与合格判定。
 * 测量修订会产生新版本行（version 递增），旧版本转为 SUPERSEDED 仅保留历史，复核不随版本迁移。
 *
 * @param id             测量版本记录 ID（自增）
 * @param measurementKey 业务测量键，同一测量的各版本共享
 * @param version        版本号，从 1 开始，同一 measurementKey 下递增
 * @param instrumentId   仪器 ID
 * @param measuredAt     测量时刻（UTC）
 * @param rawReading     原始读数，最多 6 位小数
 * @param lowerLimit     合格下限（含端点）
 * @param upperLimit     合格上限（含端点）
 * @param submittedBy    提交人
 * @param certificateId  提交时匹配到的校准证书 ID
 * @param computedValue  未舍入计算值 a×读数+b
 * @param passed         是否合格（基于未舍入值，含端点）
 * @param status         当前版本状态：PENDING 待放行 / RETURNED 待修订 / RELEASED 已放行；历史版本为 SUPERSEDED
 * @param createdAt      提交时间（UTC）
 */
public record Measurement(
        long id,
        String measurementKey,
        int version,
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
        Instant createdAt) {

    /**
     * 显示值：未舍入计算值按 HALF_UP 保留 4 位小数；仅用于展示，不参与合格判定。
     */
    public BigDecimal displayValue() {
        return computedValue.setScale(4, java.math.RoundingMode.HALF_UP);
    }
}
