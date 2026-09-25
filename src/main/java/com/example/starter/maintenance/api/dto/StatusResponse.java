package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 设备保养状态视图：本轮运行分钟 = 最新读数累计工时 - 最近保养锚点工时（无保养时从 0 计算）。
 *
 * @param equipmentId                            设备唯一标识
 * @param version                                设备当前版本号
 * @param maintenancePeriodMinutes               保养周期（分钟）
 * @param latestSampledAt                        最新读数采样时刻（无读数时为 null）
 * @param latestCumulativeMinutes                最新读数累计工时（无读数时为 0）
 * @param lastMaintenanceAnchorSampledAt         最近保养锚点时刻（无保养时为 null）
 * @param lastMaintenanceAnchorCumulativeMinutes 最近保养锚点工时快照（无保养时为 0）
 * @param runMinutes                             本轮运行分钟
 * @param approvedDeferralMinutes                本周期已累计批准延期（分钟），保养完成后归零
 * @param effectiveThresholdMinutes              当前生效阈值（分钟）= 保养周期 + 本周期累计批准延期
 * @param pendingDeferralKey                     待审批延期的 deferKey（无待审批时为 null）
 * @param overdueLocked                          是否超期封锁（OVERDUE：达到延期后新阈值仍未保养，后续读数 409）
 * @param status                                 OK、DUE 或 OVERDUE（runMinutes 达到生效阈值且有批准延期即 OVERDUE，
 *                                               否则达到保养周期即 DUE）
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
        long effectiveThresholdMinutes,
        String pendingDeferralKey,
        boolean overdueLocked,
        String status) {
}
