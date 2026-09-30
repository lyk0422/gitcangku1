package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 设备保养状态视图：本轮运行分钟 = 最新有效读数累计工时 - 最近保养锚点工时（无保养时从 0 计算）。
 * 待确认（PENDING）读数不参与计算，也不会触发保养放行。
 *
 * @param equipmentId                            设备唯一标识
 * @param version                                设备当前版本号
 * @param maintenancePeriodMinutes               保养周期（分钟）
 * @param witnessThresholdMinutes                大幅修订见证阈值（分钟）
 * @param latestSampledAt                        最新有效读数采样时刻（无读数时为 null）
 * @param latestCumulativeMinutes                最新有效读数累计工时（无读数时为 0）
 * @param lastMaintenanceAnchorSampledAt         最近保养锚点时刻（无保养时为 null）
 * @param lastMaintenanceAnchorCumulativeMinutes 最近保养锚点工时快照（无保养时为 0）
 * @param runMinutes                             本轮运行分钟（只按有效读数计算）
 * @param status                                 OK 或 DUE（runMinutes 达到保养周期即 DUE）
 */
public record StatusResponse(
        String equipmentId,
        long version,
        long maintenancePeriodMinutes,
        long witnessThresholdMinutes,
        Instant latestSampledAt,
        long latestCumulativeMinutes,
        Instant lastMaintenanceAnchorSampledAt,
        long lastMaintenanceAnchorCumulativeMinutes,
        long runMinutes,
        String status) {
}
