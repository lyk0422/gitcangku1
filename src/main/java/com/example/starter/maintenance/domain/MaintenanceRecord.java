package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 保养记录（锚点快照，不允许删除）。固化周期、模板版本与证据包版本。
 *
 * @param maintenanceId             保养记录主键
 * @param equipmentId               所属设备标识
 * @param readingId                 锚点读数标识
 * @param anchorRevisionNo          锚点读数在保养完成时的修订号
 * @param anchorSampledAt           锚点读数的 UTC 采样时刻（快照）
 * @param anchorCumulativeMinutes   锚点读数在保养完成时的累计工时快照（分钟）
 * @param cycleNo                   本次保养完工的周期号（固化）
 * @param templateVersion           完工证据包固化的模板版本（固化）
 * @param evidencePackageVersion    完工确认指向的证据包版本（固化）
 * @param confirmedBy               完工确认人（另一维护角色，固化）
 * @param completedAt               保养完成登记时刻（UTC）
 */
public record MaintenanceRecord(
        long maintenanceId,
        String equipmentId,
        String readingId,
        int anchorRevisionNo,
        Instant anchorSampledAt,
        long anchorCumulativeMinutes,
        int cycleNo,
        int templateVersion,
        int evidencePackageVersion,
        String confirmedBy,
        Instant completedAt) {
}
