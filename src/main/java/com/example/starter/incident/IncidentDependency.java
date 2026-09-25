package com.example.starter.incident;

import java.time.Instant;

/**
 * 处置任务依赖（阻塞）边实体，对应 incident_dependencies 表。
 * 表示 incidentId 的处置任务被 blockedByIncidentId 阻塞；
 * 两端必须同域，跨域引用在服务层拒绝（422）。
 * (incidentId, blockedByIncidentId) 唯一。
 */
public record IncidentDependency(
        long id,
        long incidentId,
        long blockedByIncidentId,
        Instant createdAt) {
}
