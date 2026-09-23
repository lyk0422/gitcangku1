package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 单个保养项目的状态视图：本轮运行分钟 = 设备最新读数累计工时 - 该项目最近锚点工时
 * （该项目无保养记录时从 0 计算）；达到该项目自己的周期即 DUE。
 *
 * @param itemCode                                项目编码
 * @param maintenancePeriodMinutes               该项目独立保养周期（分钟）
 * @param latestSampledAt                        设备最新读数采样时刻（无读数时为 null），全部项目共享
 * @param latestCumulativeMinutes                设备最新读数累计工时（无读数时为 0），全部项目共享
 * @param lastMaintenanceAnchorSampledAt         该项目最近保养锚点时刻（无保养时为 null）
 * @param lastMaintenanceAnchorCumulativeMinutes 该项目最近保养锚点工时快照（无保养时为 0）
 * @param runMinutes                             该项目本轮运行分钟
 * @param status                                 OK 或 DUE（runMinutes 达到该项目周期即 DUE）
 */
public record ItemStatusResponse(
        String itemCode,
        long maintenancePeriodMinutes,
        Instant latestSampledAt,
        long latestCumulativeMinutes,
        Instant lastMaintenanceAnchorSampledAt,
        long lastMaintenanceAnchorCumulativeMinutes,
        long runMinutes,
        String status) {
}
