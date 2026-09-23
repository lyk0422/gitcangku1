package com.example.starter.incident.plan;

/**
 * 方案版本依赖边实体，对应 plan_edges 表（有向边：前置任务 → 后继任务）。
 */
public record PlanEdge(
        long id,
        long versionId,
        String fromTaskId,
        String toTaskId) {

    /**
     * 提取边键。
     */
    public EdgeKey key() {
        return new EdgeKey(fromTaskId, toTaskId);
    }
}
