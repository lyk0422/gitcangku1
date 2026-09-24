package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 保养记录（锚点快照，不允许删除）。
 *
 * @param maintenanceId             保养记录主键
 * @param equipmentId               所属设备标识
 * @param readingId                 锚点读数标识
 * @param anchorRevisionNo          锚点读数在保养完成时的修订号
 * @param anchorSampledAt           锚点读数的 UTC 采样时刻（快照）
 * @param anchorCumulativeMinutes   锚点读数在保养完成时的累计工时快照（分钟）
 * @param settledDeductionMinutes   结算时该锚点之后生效停机区间扣减合计快照（分钟）
 * @param settledRunMinutes         结算时固化的本轮运行分钟快照（分钟，不为负）
 * @param completedAt               保养完成登记时刻（UTC）
 */
public record MaintenanceRecord(
        long maintenanceId,
        String equipmentId,
        String readingId,
        int anchorRevisionNo,
        Instant anchorSampledAt,
        long anchorCumulativeMinutes,
        long settledDeductionMinutes,
        long settledRunMinutes,
        Instant completedAt) {
}
