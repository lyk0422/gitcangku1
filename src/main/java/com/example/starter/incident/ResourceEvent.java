package com.example.starter.incident;

import java.time.Instant;

/**
 * 资源授权证据事件实体，对应 incident_resource_events 表。
 * 只追加不改写：分配、到期判定、续期、执行与执行拦截、回收均固化资源、
 * 旧新授权版本、UTC 时刻与操作人；后续任务修订或事件合并不改写本表。
 * oldVersion/newVersion 为空含义见 schema COMMENT。时间均为 UTC。
 */
public record ResourceEvent(
        long id,
        long incidentId,
        long taskId,
        String resourceKey,
        ResourceEventType eventType,
        Integer oldVersion,
        Integer newVersion,
        String actor,
        Instant occurredAt,
        String detail,
        Instant createdAt) {
}
