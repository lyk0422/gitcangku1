package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 保养记录视图。
 *
 * @param maintenanceId             保养记录标识
 * @param equipmentId               所属设备标识
 * @param readingId                 锚点读数标识
 * @param anchorRevisionNo          锚点读数在保养完成时的修订号
 * @param anchorSampledAt           锚点读数的 UTC 采样时刻（快照）
 * @param anchorCumulativeMinutes   锚点读数在保养完成时的累计工时快照（分钟）
 * @param runMinutes                保养完成时固化的本轮运行分钟（已扣减停机，为负按 0 计）
 * @param downtimeDeductionMinutes  保养完成时固化的停机扣减合计（分钟）
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
        long runMinutes,
        long downtimeDeductionMinutes,
        Instant completedAt,
        long equipmentVersion) {
}
