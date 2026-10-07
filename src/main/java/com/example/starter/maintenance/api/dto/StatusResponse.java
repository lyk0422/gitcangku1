package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 设备保养状态视图：本轮运行分钟 = 最新读数累计工时 - 最近保养锚点工时（无保养时从 0 计算）。
 * 本轮累计损伤达到疲劳阈值，或本轮运行分钟达到保养周期，即 DUE（两者任一，互不绕过）。
 *
 * @param equipmentId                            设备唯一标识
 * @param version                                设备当前版本号
 * @param maintenancePeriodMinutes               保养周期（分钟）
 * @param fatigueThreshold                       疲劳阈值（未配置为 null，此时疲劳门禁不产生 DUE）
 * @param baselineStressMpa                      基准应力（兆帕，未配置为 null）
 * @param latestSampledAt                        最新读数采样时刻（无读数时为 null）
 * @param latestCumulativeMinutes                最新读数累计工时（无读数时为 0）
 * @param lastMaintenanceAnchorSampledAt         最近保养锚点时刻（无保养时为 null）
 * @param lastMaintenanceAnchorCumulativeMinutes 最近保养锚点工时快照（无保养时为 0）
 * @param runMinutes                             本轮运行分钟
 * @param currentRoundDamage                     本轮累计损伤（最近保养锚点之后闭合循环的损伤之和）
 * @param historicalDamage                       历史轮累计损伤（截至最近保养锚点已冻结的闭合循环损伤之和）
 * @param status                                 OK 或 DUE
 */
public record StatusResponse(
        String equipmentId,
        long version,
        long maintenancePeriodMinutes,
        Long fatigueThreshold,
        Long baselineStressMpa,
        Instant latestSampledAt,
        long latestCumulativeMinutes,
        Instant lastMaintenanceAnchorSampledAt,
        long lastMaintenanceAnchorCumulativeMinutes,
        long runMinutes,
        long currentRoundDamage,
        long historicalDamage,
        String status) {
}
