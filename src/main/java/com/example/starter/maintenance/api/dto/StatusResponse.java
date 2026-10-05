package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 设备保养状态视图：同时展示物理工时与负荷加权等效寿命两套口径。
 * 物理本轮运行分钟 = 最新读数累计工时 - 最近保养锚点工时（无保养时从 0 计算）；
 * 等效本轮运行分钟 = 当前等效累计 - 最近保养冻结的等效累计（无保养时从 0 计算）。
 * 等效剩余小于等于 0 时 equivalentStatus 提示 DUE，物理分钟规则（status）不受影响。
 *
 * @param equipmentId                            设备唯一标识
 * @param version                                设备当前版本号
 * @param maintenancePeriodMinutes               物理保养周期（分钟）
 * @param latestSampledAt                        最新读数采样时刻（无读数时为 null）
 * @param latestCumulativeMinutes                最新读数累计工时（无读数时为 0）
 * @param lastMaintenanceAnchorSampledAt         最近保养锚点时刻（无保养时为 null）
 * @param lastMaintenanceAnchorCumulativeMinutes 最近保养锚点工时快照（无保养时为 0）
 * @param runMinutes                             本轮物理运行分钟
 * @param remainingMinutes                       物理剩余分钟（周期 - 本轮运行，可为负）
 * @param status                                 物理口径状态：OK 或 DUE（runMinutes 达到保养周期即 DUE）
 * @param equivalentPeriodMinutes                等效寿命周期（等效分钟；未启用为 null）
 * @param latestEquivalentCumulativeMinutes      当前等效累计（等效分钟；未启用为 null）
 * @param equivalentRunMinutes                   本轮等效运行分钟（未启用为 null）
 * @param equivalentRemainingMinutes             等效剩余分钟（未启用为 null，可为负）
 * @param equivalentStatus                       等效口径状态：OK 或 DUE（剩余 ≤ 0 即 DUE；未启用为 null）
 */
public record StatusResponse(
        String equipmentId,
        long version,
        long maintenancePeriodMinutes,
        Instant latestSampledAt,
        long latestCumulativeMinutes,
        Instant lastMaintenanceAnchorSampledAt,
        long lastMaintenanceAnchorCumulativeMinutes,
        long runMinutes,
        long remainingMinutes,
        String status,
        Long equivalentPeriodMinutes,
        Long latestEquivalentCumulativeMinutes,
        Long equivalentRunMinutes,
        Long equivalentRemainingMinutes,
        String equivalentStatus) {
}
