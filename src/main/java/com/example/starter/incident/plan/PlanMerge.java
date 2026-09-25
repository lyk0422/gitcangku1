package com.example.starter.incident.plan;

import java.time.Instant;

/**
 * 合并证据行（冻结，不可变）：三方差异、全部冲突解决、最终任务集与边集、
 * 首次响应快照均落本表。mergeKey 全局唯一；requestId 全局唯一，
 * 同参重放返回 responseJson 快照，异参 409；失败事务回滚不占键。
 */
public record PlanMerge(long id, long incidentId, String mergeKey, String requestId,
                        String requestHash, int baseVersionNo, int leftVersionNo,
                        int rightVersionNo, int resultVersionNo, String diffJson,
                        String resolutionsJson, String tasksJson, String edgesJson,
                        String responseJson, String createdBy, Instant createdAt) {
}
