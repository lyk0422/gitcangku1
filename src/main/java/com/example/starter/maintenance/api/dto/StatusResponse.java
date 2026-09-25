package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 设备保养状态视图：本轮运行分钟 = 最新已认证读数累计工时 - 最近保养锚点工时（无保养时从 0 计算）。
 * PENDING 读数不参与累计工时与保养阈值判定。
 *
 * @param equipmentId                            设备唯一标识
 * @param version                                设备当前版本号
 * @param maintenancePeriodMinutes               保养周期（分钟）
 * @param latestSampledAt                        最新已认证读数采样时刻（无已认证读数时为 null）
 * @param latestCumulativeMinutes                最新已认证读数累计工时（无已认证读数时为 0）
 * @param lastMaintenanceAnchorSampledAt         最近保养锚点时刻（无保养时为 null）
 * @param lastMaintenanceAnchorCumulativeMinutes 最近保养锚点工时快照（无保养时为 0）
 * @param runMinutes                             本轮运行分钟
 * @param status                                 OK 或 DUE（runMinutes 达到保养周期即 DUE）
 * @param retired                                是否已退役
 * @param retiredAt                              退役时刻（UTC）；在役为 null
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
        String status,
        boolean retired,
        Instant retiredAt) {
}
