package com.example.starter.incident;

import java.time.Instant;

/**
 * 资源资质实体，对应 resource_credentials 表。
 * (resourceId, credentialCode) 唯一；资源可登记多个资质代码。
 * validUntil 为 UTC 有效期截止时刻，租约要求其严格晚于任务计划完成时刻；
 * status=REVOKED 表示资质被提前撤销，撤销后未来有效的高危租约进入资质风险。
 * version 为资源资质版本号，同键重登/撤销单调递增并参与 leaseKey 指纹。
 */
public record ResourceCredential(
        long id,
        String resourceId,
        String credentialCode,
        Instant validFrom,
        Instant validUntil,
        CredentialStatus status,
        long version,
        String revokedBy,
        Instant revokedAt,
        String revokeReason,
        Instant createdAt,
        Instant updatedAt) {
}
