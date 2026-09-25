package com.example.starter.incident;

import java.time.Instant;

/**
 * 资质风险不可变记录，对应 credential_risk_records 表。
 * 资源资质被提前撤销时，对每个受影响的未来有效高危租约写入一条；
 * 只插入，不更新不删除。(leaseId, credentialCode) 唯一兜底重复写入。
 * 时间均为 UTC。
 */
public record CredentialRiskRecord(
        long id,
        long leaseId,
        long taskId,
        long resourceId,
        String credentialCode,
        Instant revokedAt,
        Instant detectedAt) {
}
