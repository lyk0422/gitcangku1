package com.example.starter.incident;

import java.time.Instant;

/**
 * 资质风险不可变记录，对应 credential_risks 表。
 * 资源资质被提前撤销且存在未来有效的高危租约时追加一条；记录只追加，不更新不删除。
 * 已完成任务不改写，不产生风险记录。时间均为 UTC。
 */
public record CredentialRisk(
        long id,
        long leaseId,
        long taskId,
        long incidentId,
        String resourceId,
        String credentialCode,
        String reason,
        String triggeredBy,
        Instant triggeredAt,
        Instant createdAt) {
}
