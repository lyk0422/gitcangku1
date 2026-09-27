package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 设备保养状态视图：本轮运行分钟 = 最新读数累计工时 - 最近保养锚点工时（无保养时从 0 计算）。
 * 状态判定：未达周期为 OK；达到周期未达逾期阈值为 DUE；达到逾期阈值为 OVERDUE。
 *
 * @param equipmentId                            设备唯一标识
 * @param version                                设备当前版本号
 * @param cycleNo                                当前（最近）周期号
 * @param maintenancePeriodMinutes               保养周期（分钟，要求值）
 * @param overdueThresholdMinutes                逾期阈值（分钟）；null 表示不判定 OVERDUE
 * @param latestSampledAt                        最新读数采样时刻（无读数时为 null）
 * @param latestCumulativeMinutes                最新读数累计工时（实际值，无读数时为 0）
 * @param lastMaintenanceAnchorSampledAt         最近保养锚点时刻（无保养时为 null）
 * @param lastMaintenanceAnchorCumulativeMinutes 最近保养锚点工时快照（无保养时为 0）
 * @param runMinutes                             本轮运行分钟（实际值）
 * @param remainingMinutes                       距保养周期的差额分钟（要求值-实际值；已达到为 0 或负）
 * @param overdueByMinutes                       超出逾期阈值的差额分钟（实际值-阈值；未达或无阈值为 0）
 * @param status                                 OK / DUE / OVERDUE
 */
public record StatusResponse(
        String equipmentId,
        long version,
        int cycleNo,
        long maintenancePeriodMinutes,
        Long overdueThresholdMinutes,
        Instant latestSampledAt,
        long latestCumulativeMinutes,
        Instant lastMaintenanceAnchorSampledAt,
        long lastMaintenanceAnchorCumulativeMinutes,
        long runMinutes,
        long remainingMinutes,
        long overdueByMinutes,
        String status) {
}
