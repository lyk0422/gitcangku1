package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 设备保养状态视图：本轮运行分钟 = 最新读数累计工时 - 最近保养锚点工时（无保养时从 0 计算）。
 * 日历截止 = 最近保养锚点时刻 + 日历周期（无保养时从投用时刻起算）；到达截止即日历到期。
 * 状态为两种到期原因的或：工时到期或日历到期任一成立即 DUE。
 *
 * @param equipmentId                            设备唯一标识
 * @param version                                设备当前版本号
 * @param maintenancePeriodMinutes               保养周期（分钟）
 * @param latestSampledAt                        最新读数采样时刻（无读数时为 null）
 * @param latestCumulativeMinutes                最新读数累计工时（无读数时为 0）
 * @param lastMaintenanceAnchorSampledAt         最近保养锚点时刻（无保养时为 null）
 * @param lastMaintenanceAnchorCumulativeMinutes 最近保养锚点工时快照（无保养时为 0）
 * @param runMinutes                             本轮运行分钟
 * @param status                                 OK 或 DUE（工时到期或日历到期任一成立即 DUE）
 * @param hoursDue                               工时到期原因：runMinutes 达到保养周期
 * @param calendarEnabled                        是否已启用日历档案
 * @param calendarPeriodDays                     日历保养周期（天；未启用为 null）
 * @param commissionedAt                         UTC 投用时刻（未启用为 null）
 * @param calendarDeadline                       当前日历截止（UTC；未启用为 null）
 * @param calendarDue                            日历到期原因：已启用且当前时刻到达日历截止
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
        boolean hoursDue,
        boolean calendarEnabled,
        Integer calendarPeriodDays,
        Instant commissionedAt,
        Instant calendarDeadline,
        boolean calendarDue) {
}
