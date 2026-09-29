package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * 测量记录（版本化）。提交时按测量时刻匹配唯一有效证书，并固化未舍入计算值与合格判定。
 * 同一 measurement_key 修订后产生新版本行，旧版本置为 SUPERSEDED，历史不可回写。
 *
 * @param id             测量记录 ID（自增）
 * @param measurementKey 业务测量键；同一业务键修订后多行，以 version 区分
 * @param version        测量版本号，同一 measurement_key 内从 1 递增
 * @param instrumentId   仪器 ID
 * @param measuredAt     测量时刻（UTC）
 * @param rawReading     原始读数，最多 6 位小数
 * @param lowerLimit     合格下限（含端点）
 * @param upperLimit     合格上限（含端点）
 * @param submittedBy    提交人
 * @param certificateId  提交时匹配到的校准证书 ID
 * @param computedValue  未舍入计算值 a×读数+b
 * @param passed         是否合格（偏差判定，基于未舍入值，含端点）
 * @param status         状态：PENDING 待放行 / RELEASED 已放行 / SUPERSEDED 已被新版本过期取代
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
        return computedValue.setScale(4, RoundingMode.HALF_UP);
    }
}
