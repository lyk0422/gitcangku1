package com.example.starter.incident;

import java.time.Instant;

/**
 * 资源授权证据实体，对应 resource_authorization_events 表。
 * 只增不改：后续任务修订、事件合并不改写已固化证据；按 id 升序即发生顺序。
 * oldAuthorizationVersion 仅 ASSIGN 为 null；newAuthorizationVersion 仅
 * EXPIRY_DETECTED/EXECUTION_BLOCKED 为 null；attemptedAction 仅 EXECUTION_BLOCKED 有值。
 * occurredAt 为 UTC，微秒精度。
 */
public record ResourceAuthorizationEvent(
        long id,
        ResourceEventType eventType,
        long taskId,
        long incidentId,
        String taskKey,
        String resourceKey,
        Long oldAuthorizationVersion,
        Long newAuthorizationVersion,
        String actor,
        String attemptedAction,
        Instant occurredAt) {
}
