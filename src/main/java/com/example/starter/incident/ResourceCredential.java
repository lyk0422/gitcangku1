package com.example.starter.incident;

import java.time.Instant;

/**
 * 资源凭据实体，对应 resource_credentials 表。
 * (resourceKey, version) 唯一：同一资源可登记多个版本，版本内容不可变，
 * 更新有效凭据即登记新版本；旧版本保留用于派发/执行历史的凭据依据，不用于解锁。
 * region 为该凭据覆盖的单个区域；expiresAt 为 UTC 失效时刻（时刻到达即失效）；
 * revokedAt 仅 REVOKED 有值。时间均为 UTC。
 */
public record ResourceCredential(
        long id,
        String resourceKey,
        String version,
        String credentialType,
        String region,
        CredentialStatus status,
        Instant expiresAt,
        Instant revokedAt,
        Instant createdAt,
        Instant updatedAt) {
}
