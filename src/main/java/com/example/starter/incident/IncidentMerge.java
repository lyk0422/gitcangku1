package com.example.starter.incident;

import java.time.Instant;

/**
 * 重复事件合并记录实体，对应 incident_merges 表，落库后不可变。
 * mergeKey 全局唯一；survivingIncidentId 为存续事件，mergedIncidentId 为被并入事件；
 * actor 为提交合并时的双方共同当前指挥人；createdAt 为合并完成 UTC 时刻。
 */
public record IncidentMerge(
        long id,
        String mergeKey,
        long survivingIncidentId,
        long mergedIncidentId,
        String actor,
        Instant createdAt) {
}
