package com.example.starter.maintenance.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 保养记录（锚点快照，不允许删除）。
 *
 * @param maintenanceId             保养记录主键
 * @param equipmentId               所属设备标识
 * @param readingId                 锚点读数标识
 * @param anchorRevisionNo          锚点读数在保养完成时的修订号
 * @param anchorSampledAt           锚点读数的 UTC 采样时刻（快照）
 * @param anchorCumulativeValue     锚点读数在保养完成时的累计工时快照（设备登记单位）
 * @param anchorCumulativeMinutes   锚点读数在保养完成时的累计工时换算分钟数快照
 * @param completedAt               保养完成登记时刻（UTC）
 */
public record MaintenanceRecord(
        long maintenanceId,
        String equipmentId,
        String readingId,
        int anchorRevisionNo,
        Instant anchorSampledAt,
        BigDecimal anchorCumulativeValue,
        long anchorCumulativeMinutes,
        Instant completedAt) {
}
