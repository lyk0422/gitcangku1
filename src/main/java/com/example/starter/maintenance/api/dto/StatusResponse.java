package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 设备保养状态视图：同时给出物理与等效两套口径。
 * 物理口径：本轮运行分钟 = 最新读数累计工时 - 最近保养锚点工时（无保养时从 0 计算）。
 * 等效口径：等效剩余 = 等效周期 -（最新等效累计 - 最近保养冻结的等效累计）；未启用时为 null。
 * 任一口径到期（物理剩余 &lt;= 0 或等效剩余 &lt;= 0）状态即为 DUE。
 *
 * @param equipmentId                            设备唯一标识
 * @param version                                设备当前版本号
 * @param maintenancePeriodMinutes               物理保养周期（分钟）
 * @param equivalentPeriodMinutes                等效寿命周期（等效分钟）；未启用为 null
 * @param latestSampledAt                        最新读数采样时刻（无读数时为 null）
 * @param latestCumulativeMinutes                最新读数累计工时（无读数时为 0）
 * @param latestEquivalentMinutes                最新读数对应的等效累计（无读数时为 0）
 * @param lastMaintenanceAnchorSampledAt         最近保养锚点时刻（无保养时为 null）
 * @param lastMaintenanceAnchorCumulativeMinutes 最近保养锚点工时快照（无保养时为 0）
 * @param lastMaintenanceAnchorEquivalentMinutes 最近保养冻结的等效累计快照（无保养时为 0）
 * @param runMinutes                             本轮物理运行分钟
 * @param physicalRemainingMinutes               物理剩余分钟（周期 - 本轮运行分钟）
 * @param equivalentRemainingMinutes             等效剩余分钟；未启用等效寿命时为 null
 * @param status                                 OK 或 DUE（任一口径到期即 DUE）
 */
public record StatusResponse(
        String equipmentId,
        long version,
        long maintenancePeriodMinutes,
        Long equivalentPeriodMinutes,
        Instant latestSampledAt,
        long latestCumulativeMinutes,
        long latestEquivalentMinutes,
        Instant lastMaintenanceAnchorSampledAt,
        long lastMaintenanceAnchorCumulativeMinutes,
        long lastMaintenanceAnchorEquivalentMinutes,
        long runMinutes,
        long physicalRemainingMinutes,
        Long equivalentRemainingMinutes,
        String status) {
}
