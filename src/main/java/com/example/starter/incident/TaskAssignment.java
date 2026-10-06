package com.example.starter.incident;

import java.time.Instant;

/**
 * 任务执行安排实体，对应 task_assignments 表。
 * 每任务至多一条当前安排（taskId 唯一），替换即更新本行；
 * 绑定合成人员 assignee 与 UTC 整分钟左闭右开时段 [startAt, endAt)，时长 1~240 分钟。
 * 同一人员的全部安排不重叠，相邻安排至少休息 30 分钟；
 * 每 UTC 日与当日相交的作业总量不超过 480 分钟，跨午夜按交集拆日。
 * 任务未开始被取消时删除本行释放时段；已开始任务的完成/取消保留本行，不释放休息约束。
 */
public record TaskAssignment(
        long id,
        long taskId,
        String assignee,
        Instant startAt,
        Instant endAt,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * 时段长度（分钟），左闭右开。
     */
    public long durationMinutes() {
        return java.time.Duration.between(startAt, endAt).toMinutes();
    }
}
