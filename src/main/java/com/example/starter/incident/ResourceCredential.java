package com.example.starter.incident;

import java.time.Instant;

/**
 * 资源凭据实体，对应 resource_credentials 表。
 * 同一资源每次登记新版本，版本号自增；派发记录绑定的是登记当时的版本快照（类型/区域/有效期）。
 * credentialType 为凭据类型；region 为该凭据覆盖的区域；expiresAt 为 UTC 失效时刻，
 * 当前时刻 &lt; expiresAt 才视为在有效期内（恰好等于失效时刻即已过期）。
 * status 为 ACTIVE/REVOKED，撤销不可逆。时间均为 UTC。
 */
public record ResourceCredential(
        long id,
        String resourceKey,
        int version,
        String credentialType,
        String region,
        Instant expiresAt,
        CredentialStatus status,
        String createdBy,
        Instant revokedAt,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * 以给定当前 UTC 时刻判断凭据是否可用于指定类型与区域：
     * 状态 ACTIVE、类型一致、区域一致、有效期未结束（now &lt; expiresAt）。
     */
    public boolean covers(String requiredType, String requiredRegion, Instant now) {
        return status == CredentialStatus.ACTIVE
                && credentialType.equals(requiredType)
                && region.equals(requiredRegion)
                && now.isBefore(expiresAt);
    }
}
