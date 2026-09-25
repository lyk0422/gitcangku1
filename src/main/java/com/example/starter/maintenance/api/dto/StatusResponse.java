package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 设备保养状态视图：本轮运行分钟 = 最新读数累计工时 - 最近保养锚点工时（无保养时从 0 计算）。
 *
 * @param equipmentId                            设备唯一标识
 * @param version                                设备当前版本号
 * @param maintenancePeriodMinutes               保养周期（分钟，原始阈值）
 * @param latestSampledAt                        最新读数采样时刻（无读数时为 null）
 * @param latestCumulativeMinutes                最新读数累计工时（无读数时为 0）
 * @param lastMaintenanceAnchorSampledAt         最近保养锚点时刻（无保养时为 null）
 * @param lastMaintenanceAnchorCumulativeMinutes 最近保养锚点工时快照（无保养时为 0）
 * @param runMinutes                             本轮运行分钟
 * @param approvedDeferralMinutes                当前保养周期已批准延期累计分钟（保养完成后归零）
 * @param currentThresholdMinutes                当前生效保养阈值 = 保养周期 + 已批准延期累计分钟
 * @param pendingDeferralKey                     当前待审批延期标识（无待审批时为 null）
 * @param readingBlocked                         是否禁止新增运行读数（待审批延期或已超期时为 true）
 * @param status                                 OK / DUE / OVERDUE
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
        long approvedDeferralMinutes,
        long currentThresholdMinutes,
        String pendingDeferralKey,
        boolean readingBlocked,
        String status) {
}
