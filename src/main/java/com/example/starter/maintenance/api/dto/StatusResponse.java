package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 设备保养状态视图：本轮运行分钟 = 最新读数累计工时 - 最近保养锚点工时（无保养时从 0 计算）。
 * 前 9 个字段保持历史字段顺序与 null 语义不变；能源隔离字段在末尾追加。
 *
 * @param equipmentId                            设备唯一标识
 * @param version                                设备当前版本号
 * @param maintenancePeriodMinutes               保养周期（分钟）
 * @param latestSampledAt                        最新读数采样时刻（无读数时为 null）
 * @param latestCumulativeMinutes                最新读数累计工时（无读数时为 0）
 * @param lastMaintenanceAnchorSampledAt         最近保养锚点时刻（无保养时为 null）
 * @param lastMaintenanceAnchorCumulativeMinutes 最近保养锚点工时快照（无保养时为 0）
 * @param runMinutes                             本轮运行分钟
 * @param status                                 OK 或 DUE（runMinutes 达到保养周期即 DUE）
 * @param equipmentStatus                        设备状态 ACTIVE/DEACTIVATED（追加字段）
 * @param currentIsolationOrderId                当前活动隔离令标识；无活动令为 null（追加字段）
 * @param currentIsolationStatus                 当前活动令状态 PENDING/EFFECTIVE；无活动令为 null（追加字段）
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
        String equipmentStatus,
        Long currentIsolationOrderId,
        String currentIsolationStatus) {
}
