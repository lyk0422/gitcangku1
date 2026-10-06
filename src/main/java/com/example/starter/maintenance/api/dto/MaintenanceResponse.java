package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 保养记录视图。锚点快照冻结 raw、wrapCount、M 及归一值，不随后续回绕或修订变值。
 *
 * @param maintenanceId             保养记录标识
 * @param equipmentId               所属设备标识
 * @param readingId                 锚点读数标识
 * @param anchorRevisionNo          锚点读数在保养完成时的修订号
 * @param anchorSampledAt           锚点读数的 UTC 采样时刻（快照）
 * @param anchorCumulativeMinutes   锚点读数在保养完成时的归一累计工时快照（分钟）
 * @param anchorRaw                 锚点读数在保养完成时的 raw 快照；null 表示未启用量程
 * @param anchorWrapCount           锚点读数在保养完成时的回绕次数快照；null 表示未启用量程
 * @param anchorMeterRangeMinutes   保养完成时设备量程 M 快照；null 表示未启用量程
 * @param completedAt               保养完成登记时刻（UTC）
 * @param equipmentVersion          操作后的设备版本号（查询历史时为 0，无意义）
 */
public record MaintenanceResponse(
        long maintenanceId,
        String equipmentId,
        String readingId,
        int anchorRevisionNo,
        Instant anchorSampledAt,
        long anchorCumulativeMinutes,
        Long anchorRaw,
        Long anchorWrapCount,
        Long anchorMeterRangeMinutes,
        Instant completedAt,
        long equipmentVersion) {
}
