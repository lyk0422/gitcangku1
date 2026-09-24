package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 保养记录视图。保养完成时按当前锚点之后的扣减量结算，固化当时运行分钟与扣减合计。
 *
 * @param maintenanceId             保养记录标识
 * @param equipmentId               所属设备标识
 * @param readingId                 锚点读数标识
 * @param anchorRevisionNo          锚点读数在保养完成时的修订号
 * @param anchorSampledAt           锚点读数的 UTC 采样时刻（快照）
 * @param anchorCumulativeMinutes   锚点读数在保养完成时的累计工时快照（分钟）
 * @param settledDeductionMinutes   结算时该锚点之后生效停机区间的扣减合计（分钟，快照）
 * @param settledRunMinutes         结算时固化的本轮运行分钟（分钟，快照，不为负）
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
        long settledDeductionMinutes,
        long settledRunMinutes,
        Instant completedAt,
        long equipmentVersion) {
}
