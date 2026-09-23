package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 测量记录（单修订版本）。提交时按测量时刻匹配唯一有效证书，并固化未舍入计算值与合格判定。
 * 同一 measurementKey 可有多修订版本：revision 从 1 递增，isLatest 标记唯一最新版；
 * 旧版本原始值、计算结果与放行历史保留不改写。
 *
 * @param id             测量记录 ID（自增）
 * @param measurementKey 业务测量键；与 revision 组合唯一（幂等键）
 * @param revision       修订号，从 1 开始递增
 * @param isLatest       是否最新版；同一 measurementKey 至多一版为 TRUE
 * @param requestId      修订幂等键；首版提交为 null
 * @param revisionReason 修订原因；首版提交为 null
 * @param instrumentId   仪器 ID（修订时不变）
 * @param measuredAt     测量时刻（UTC，修订时不变）
 * @param rawReading     原始读数，最多 6 位小数
 * @param lowerLimit     合格下限（含端点）
 * @param upperLimit     合格上限（含端点）
 * @param submittedBy    提交人（修订时仍为原提交人）
 * @param certificateId  提交/修订时匹配到的校准证书 ID
 * @param computedValue  未舍入计算值 a×读数+b
 * @param passed         是否合格（基于未舍入值，含端点）
 * @param status         状态：PENDING 待放行 / RELEASED 已放行
 * @param createdAt      提交/修订时间（UTC）
 */
public record Measurement(
        long id,
        String measurementKey,
        int revision,
        boolean isLatest,
        String requestId,
        String revisionReason,
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
