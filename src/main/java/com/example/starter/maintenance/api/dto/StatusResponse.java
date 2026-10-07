package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 设备保养状态视图：本轮运行分钟 = 最新读数累计工时 - 最近保养锚点工时（无保养时从 0 计算）。
 * 日历周期启用时，当前日历截止 = 最近保养锚点时刻 + 日历周期（无保养时从投用时刻起算），
 * 达到截止即日历到期；工时到期与日历到期任一为真则整体 DUE。
 *
 * @param equipmentId                            设备唯一标识
 * @param version                                设备当前版本号
 * @param maintenancePeriodMinutes               保养周期（分钟）
 * @param latestSampledAt                        最新读数采样时刻（无读数时为 null）
 * @param latestCumulativeMinutes                最新读数累计工时（无读数时为 0）
 * @param lastMaintenanceAnchorSampledAt         最近保养锚点时刻（无保养时为 null）
 * @param lastMaintenanceAnchorCumulativeMinutes 最近保养锚点工时快照（无保养时为 0）
 * @param runMinutes                             本轮运行分钟
 * @param status                                 OK 或 DUE（工时或日历任一到期即 DUE）
 * @param commissionedAt                         日历档案投用时刻（UTC，未启用日历为 null）
 * @param calendarPeriodDays                     日历保养周期（天，未启用日历为 null）
 * @param calendarDeadline                       当前日历截止（UTC，未启用日历为 null）
 * @param workHoursDue                           工时到期原因：runMinutes 是否已达保养周期
 * @param calendarDue                            日历到期原因：当前时刻是否已达日历截止（未启用日历恒为 false）
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
        Instant commissionedAt,
        Integer calendarPeriodDays,
        Instant calendarDeadline,
        boolean workHoursDue,
        boolean calendarDue) {
}
