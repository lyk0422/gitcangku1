package com.example.starter.incident;

import java.time.Instant;

/**
 * 依赖图当前有向边实体，对应 incident_dependency_edges 表。
 * 边方向：fromIncidentId 事件的任务依赖 toIncidentId 事件（阻塞关系）。
 * source 为边来源（{@link EdgeOp#SOURCE_TASK} / {@link EdgeOp#SOURCE_PROPOSAL}），
 * refId 为来源任务或提案 id；同一条边被多个来源声明时只保留一行（结构化唯一）。
 * 提案删除边时物理删除该行；前后边集证据保存在提案快照中。
 */
public record DependencyEdge(
        long id,
        long fromIncidentId,
        long toIncidentId,
        String source,
        Long refId,
        Instant createdAt) {
}
