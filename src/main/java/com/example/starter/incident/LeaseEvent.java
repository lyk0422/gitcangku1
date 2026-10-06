package com.example.starter.incident;

import java.time.Instant;

/**
 * 资源使用证据实体，对应 resource_lease_events 表。
 * 只追加不改写：后续任务修订、续期或事件合并不改写已固化的证据；
 * 按 id 升序即发生顺序。oldAuthVersion 在 ASSIGN 时为 null，
 * newAuthVersion 在 EXPIRE/RELEASE/EXECUTION_BLOCKED 时为 null；
 * detail 仅 EXECUTION_BLOCKED（被拦截操作）与 RELEASE（回收原因）有值。时间均为 UTC。
 */
public record LeaseEvent(
        long id,
        LeaseEventType eventType,
        String resourceKey,
        long incidentId,
        long taskId,
        Long leaseId,
        String oldAuthVersion,
        String newAuthVersion,
        String actor,
        Instant occurredAt,
        String detail) {
}
