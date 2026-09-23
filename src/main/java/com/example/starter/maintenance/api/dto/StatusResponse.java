package com.example.starter.maintenance.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 设备保养状态视图：本轮运行分钟 = 最新读数虚拟分钟 - 最近保养锚点虚拟分钟（无保养时从 0 计算）。
 *
 * @param equipmentId                            设备唯一标识
 * @param version                                设备当前版本号
 * @param maintenancePeriodMinutes               保养周期（分钟）
 * @param activeMeterKey                         当前 ACTIVE 工时表标识
 * @param latestSampledAt                        最新读数采样时刻（无读数时为 null）
 * @param latestRawHours                         最新读数在其表内的原始工时（小时，无读数时为 0）
 * @param latestVirtualHours                     最新读数的跨表虚拟工时（小时，无读数时为 0）
 * @param latestVirtualMinutes                   最新读数虚拟工时换算分钟（无读数时为 0）
 * @param lastMaintenanceAnchorSampledAt         最近保养锚点时刻（无保养时为 null）
 * @param lastMaintenanceAnchorVirtualHours      最近保养锚点虚拟工时快照（小时，无保养时为 0）
 * @param lastMaintenanceAnchorCumulativeMinutes 最近保养锚点虚拟分钟快照（无保养时为 0）
 * @param runMinutes                             本轮运行分钟
 * @param status                                 OK 或 DUE（runMinutes 达到保养周期即 DUE）
 */
public record StatusResponse(
        String equipmentId,
        long version,
        long maintenancePeriodMinutes,
        String activeMeterKey,
        Instant latestSampledAt,
        BigDecimal latestRawHours,
        BigDecimal latestVirtualHours,
        long latestVirtualMinutes,
        Instant lastMaintenanceAnchorSampledAt,
        BigDecimal lastMaintenanceAnchorVirtualHours,
        long lastMaintenanceAnchorCumulativeMinutes,
        long runMinutes,
        String status) {
}
