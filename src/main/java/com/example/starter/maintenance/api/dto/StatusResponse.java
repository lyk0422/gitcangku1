package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 设备保养状态视图。本轮运行分钟 = 最新读数累计工时 - 最近保养锚点工时
 * - 该锚点之后全部生效停机区间扣减量之和，为负按 0 计算；达到保养周期即 DUE。
 *
 * @param equipmentId                            设备唯一标识
 * @param version                                设备当前版本号
 * @param maintenancePeriodMinutes               保养周期（分钟）
 * @param latestSampledAt                        最新读数采样时刻（无读数时为 null）
 * @param latestCumulativeMinutes                最新读数累计工时（无读数时为 0）
 * @param lastMaintenanceAnchorSampledAt         最近保养锚点时刻（无保养时为 null）
 * @param lastMaintenanceAnchorCumulativeMinutes 最近保养锚点工时快照（无保养时为 0）
 * @param downtimeDeductionMinutes               当前周期内全部生效停机区间扣减量合计（分钟）
 * @param runMinutes                             本轮运行分钟（扣减停机后，不为负）
 * @param status                                 OK 或 DUE（runMinutes 达到保养周期即 DUE）
 */
public record StatusResponse(
        String equipmentId,
        long version,
        long maintenancePeriodMinutes,
        Instant latestSampledAt,
        long latestCumulativeMinutes,
        Instant lastMaintenanceAnchorSampledAt,
        long lastMaintenanceAnchorCumulativeMinutes,
        long downtimeDeductionMinutes,
        long runMinutes,
        String status) {
}
