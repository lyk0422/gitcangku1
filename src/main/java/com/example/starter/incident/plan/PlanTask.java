package com.example.starter.incident.plan;

import java.time.Instant;

/**
 * 方案版本内的计划任务行。taskId 为跨版本稳定标识（三方合并按它对齐）；
 * 执行状态不落本表（见 plan_task_state），版本行发布后不可变。
 */
public record PlanTask(long id, long versionId, String taskId, String groupCode, String title,
                       String assignee, Instant createdAt) {

    /**
     * 计划字段内容比对（三方合并用）：groupCode/title/assignee 全同视为未修改。
     */
    public boolean sameContent(PlanTask other) {
        return other != null && groupCode.equals(other.groupCode)
                && title.equals(other.title) && assignee.equals(other.assignee);
    }
}
