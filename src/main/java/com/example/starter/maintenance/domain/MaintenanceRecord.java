package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 保养记录（锚点快照，不允许删除；快照冻结，不随后续回绕或修订变化）。
 *
 * @param maintenanceId             保养记录主键
 * @param equipmentId               所属设备标识
 * @param readingId                 锚点读数标识
 * @param anchorRevisionNo          锚点读数在保养完成时的修订号
 * @param anchorSampledAt           锚点读数的 UTC 采样时刻（快照）
 * @param anchorCumulativeMinutes   锚点读数在保养完成时的归一累计工时快照（分钟）
 * @param anchorRaw                 锚点读数在保养完成时的原始读数 raw 快照（分钟）；未启用量程为 null
 * @param anchorWrapCount           锚点读数在保养完成时的回绕次数快照；未启用量程为 null
 * @param anchorRangeM              设备在保养完成时的量程 M 快照（分钟）；未启用量程为 null
 * @param completedAt               保养完成登记时刻（UTC）
 */
public record MaintenanceRecord(
        long maintenanceId,
        String equipmentId,
        String readingId,
        int anchorRevisionNo,
        Instant anchorSampledAt,
        long anchorCumulativeMinutes,
        Long anchorRaw,
        Long anchorWrapCount,
        Long anchorRangeM,
        Instant completedAt) {
}
