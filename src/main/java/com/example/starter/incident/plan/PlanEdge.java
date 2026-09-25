package com.example.starter.incident.plan;

import java.time.Instant;

/**
 * 方案依赖边（有向：fromTaskId 依赖方 → toIncidentKey/toTaskId 前置任务）。
 * toIncidentKey 等于本事件键时为内部边，否则为跨事件边（引用目标事件当前活动版本任务）。
 */
public record PlanEdge(long id, long versionId, String fromTaskId, String toIncidentKey,
                       String toTaskId, Instant createdAt) {
}
