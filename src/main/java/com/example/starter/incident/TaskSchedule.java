package com.example.starter.incident;

import java.time.Instant;

/**
 * 执行时段安排实体，对应 incident_task_schedules 表。
 * 每任务至多一条安排（替换为整行更新）；assignee 为合成人员标识，跨事件按该标识
 * 核对休息与日上限。startAt/endAt 为 UTC 整分钟左闭右开时段，长度 1~240 分钟；
 * startedAt 仅 STARTED 有值。时间均为 UTC。
 */
public record TaskSchedule(
        long id,
        long taskId,
        String assignee,
        Instant startAt,
        Instant endAt,
        TaskScheduleStatus status,
        Instant startedAt,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * 是否计入人员休息与日上限约束：SCHEDULED 与 STARTED 计入，REVOKED 不计。
     */
    public boolean countsForDuty() {
        return status == TaskScheduleStatus.SCHEDULED || status == TaskScheduleStatus.STARTED;
    }
}
