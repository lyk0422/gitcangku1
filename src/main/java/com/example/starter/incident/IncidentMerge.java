package com.example.starter.incident;

import java.time.Instant;

/**
 * 重复事件合并记录实体，对应 incident_merges 表，不可变（仅插入不更新）。
 * mergeKey 全局唯一；mergedIncidentId 唯一（同一事件至多被合并一次）；
 * actor 为提交时双方共同的当前指挥人；createdAt 为合并完成 UTC 时刻。
 */
public record IncidentMerge(
        long id,
        String mergeKey,
        long survivingIncidentId,
        long mergedIncidentId,
        String actor,
        Instant createdAt) {
}
