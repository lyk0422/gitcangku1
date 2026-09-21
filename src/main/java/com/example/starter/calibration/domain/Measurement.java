package com.example.starter.calibration.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 测量记录。原始读数、计算值与放行历史一经写入不可改写。
 *
 * @param id             测量主键 ID
 * @param measurementKey 业务幂等键，全局唯一
 * @param instrumentId   仪器 ID
 * @param measuredAt     测量时刻（UTC）
 * @param rawReading     原始读数
 * @param lowerLimit     合格下限（含端点）
 * @param upperLimit     合格上限（含端点）
 * @param submittedBy    提交人
 * @param certificateId  提交时匹配到的证书 ID
 * @param computedValue  未舍入计算值 a × 读数 + b，用于合格判断
 * @param displayValue   显示值，HALF_UP 保留 4 位小数，仅用于展示
 * @param passed         是否合格（基于未舍入值，含上下限端点）
 * @param status         放行状态
 * @param releasedBy     放行人，未放行时为 null
 * @param releasedAt     放行时刻（UTC），未放行时为 null
 * @param createdAt      创建时刻（UTC）
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
        BigDecimal displayValue,
        boolean passed,
        MeasurementStatus status,
        String releasedBy,
        Instant releasedAt,
        Instant createdAt) {
}
