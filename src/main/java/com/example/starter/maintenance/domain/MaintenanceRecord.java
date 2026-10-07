package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 保养记录（锚点快照，不允许删除）。完成时冻结当时两个到期原因、锚点与新日历截止。
 *
 * @param maintenanceId             保养记录主键
 * @param equipmentId               所属设备标识
 * @param readingId                 锚点读数标识
 * @param anchorRevisionNo          锚点读数在保养完成时的修订号
 * @param anchorSampledAt           锚点读数的 UTC 采样时刻（快照）
 * @param anchorCumulativeMinutes   锚点读数在保养完成时的累计工时快照（分钟）
 * @param hoursDue                  完成时冻结的工时到期原因（当时本轮运行分钟是否已达周期）
 * @param calendarDue               完成时冻结的日历到期原因（当时是否已到达日历截止）
 * @param newCalendarDeadline       完成时冻结的新日历截止（UTC）= 本次锚点时刻 + 日历周期；未启用日历为 null
 * @param completedAt               保养完成登记时刻（UTC）
 */
public record MaintenanceRecord(
        long maintenanceId,
        String equipmentId,
        String readingId,
        int anchorRevisionNo,
        Instant anchorSampledAt,
        long anchorCumulativeMinutes,
        boolean hoursDue,
        boolean calendarDue,
        Instant newCalendarDeadline,
        Instant completedAt) {
}
