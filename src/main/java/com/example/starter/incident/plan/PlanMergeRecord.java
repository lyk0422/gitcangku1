package com.example.starter.incident.plan;

import java.time.Instant;

/**
 * 方案合并证据（不可变）。mergeKey 全局唯一；requestId 为调用方幂等键；
 * diff/resolutions/finalTasks/finalEdges 为成功合并时冻结的 JSON 快照，
 * requestHash 由规范化请求（冲突解决项排序后）计算，换序等价。
 */
public record PlanMergeRecord(long id, String mergeKey, String requestId, String incidentKey,
                              long baseVersionId, long leftVersionId, long rightVersionId,
                              long resultVersionId, String requestHash,
                              String diffJson, String resolutionsJson,
                              String finalTasksJson, String finalEdgesJson,
                              String createdBy, Instant createdAt) {
}
