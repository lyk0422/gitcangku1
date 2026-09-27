package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 保养记录（锚点快照，不允许删除）。
 *
 * @param maintenanceId             保养记录主键
 * @param equipmentId               所属设备标识
 * @param cycleNo                   完成的保养周期序号
 * @param readingId                 锚点读数标识
 * @param anchorRevisionNo          锚点读数在保养完成时的修订号
 * @param templateVersion           完工时固化的保养模板版本
 * @param packageVersion            完工确认的证据包版本
 * @param anchorSampledAt           锚点读数的 UTC 采样时刻（快照）
 * @param anchorCumulativeMinutes   锚点读数在保养完成时的累计工时快照（分钟）
 * @param completedAt               保养完成登记时刻（UTC）
 */
public record MaintenanceRecord(
        long maintenanceId,
        String equipmentId,
        int cycleNo,
        String readingId,
        int anchorRevisionNo,
        int templateVersion,
        int packageVersion,
        Instant anchorSampledAt,
        long anchorCumulativeMinutes,
        Instant completedAt) {
}
