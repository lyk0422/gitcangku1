package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 设备保养状态视图：本轮运行分钟 = 当前虚拟工时 - 最近保养锚点虚拟工时（无保养时从 0 计算）。
 * 虚拟工时沿工时表更换链连续；无更换时虚拟工时与原始读数一致。
 *
 * @param equipmentId                            设备唯一标识
 * @param version                                设备当前版本号
 * @param recalcVersion                          链式重算版本号
 * @param maintenancePeriodMinutes               保养周期（分钟）
 * @param activeMeterKey                         当前 ACTIVE 工时表
 * @param latestSampledAt                        当前表最新读数采样时刻（无读数时为 null）
 * @param latestCumulativeMinutes                当前表最新读数原始累计工时（无读数时为 0）
 * @param latestVirtualHours                     当前虚拟工时（当前表无读数时为该表基准虚拟工时）
 * @param lastMaintenanceAnchorSampledAt         最近保养锚点时刻（无保养时为 null）
 * @param lastMaintenanceAnchorCumulativeMinutes 最近保养锚点原始工时快照（无保养时为 0）
 * @param lastMaintenanceAnchorVirtualHours      最近保养锚点虚拟工时（无保养时为 0）
 * @param runMinutes                             本轮运行分钟 = latestVirtualHours - lastMaintenanceAnchorVirtualHours
 * @param status                                 OK 或 DUE（runMinutes 达到保养周期即 DUE）
 */
public record StatusResponse(
        String equipmentId,
        long version,
        long recalcVersion,
        long maintenancePeriodMinutes,
        String activeMeterKey,
        Instant latestSampledAt,
        long latestCumulativeMinutes,
        long latestVirtualHours,
        Instant lastMaintenanceAnchorSampledAt,
        long lastMaintenanceAnchorCumulativeMinutes,
        long lastMaintenanceAnchorVirtualHours,
        long runMinutes,
        String status) {
}
