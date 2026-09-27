package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 设备保养状态视图：本轮运行分钟 = 最新读数累计工时 - 最近保养锚点工时（无保养时从 0 计算）。
 * 状态区间左闭右开：[0, 周期) 为 OK，[周期, OVERDUE阈值) 为 DUE，达到阈值为 OVERDUE。
 *
 * @param equipmentId                            设备唯一标识
 * @param version                                设备当前版本号
 * @param maintenancePeriodMinutes               保养周期（分钟）
 * @param overdueThresholdMinutes                OVERDUE 阈值（分钟，无模板时为 null）
 * @param latestSampledAt                        最新读数采样时刻（无读数时为 null）
 * @param latestCumulativeMinutes                最新读数累计工时（无读数时为 0）
 * @param lastMaintenanceAnchorSampledAt         最近保养锚点时刻（无保养时为 null）
 * @param lastMaintenanceAnchorCumulativeMinutes 最近保养锚点工时快照（无保养时为 0）
 * @param runMinutes                             本轮运行分钟（实际值）
 * @param remainingMinutes                       距下次保养差额（分钟）= 周期 - 本轮运行分钟，已 DUE 时为 0 或负值
 * @param overdueByMinutes                       超出 OVERDUE 阈值的差额（分钟，未 OVERDUE 或无阈值时为 0）
 * @param currentCycleNo                         当前（在途）保养周期序号，已完成次数 + 1
 * @param status                                 OK / DUE / OVERDUE
 */
public record StatusResponse(
        String equipmentId,
        long version,
        long maintenancePeriodMinutes,
        Long overdueThresholdMinutes,
        Instant latestSampledAt,
        long latestCumulativeMinutes,
        Instant lastMaintenanceAnchorSampledAt,
        long lastMaintenanceAnchorCumulativeMinutes,
        long runMinutes,
        long remainingMinutes,
        long overdueByMinutes,
        int currentCycleNo,
        String status) {
}
