package com.example.starter.incident;

import java.time.Instant;

/**
 * 资源资质实体，对应 resource_credentials 表。
 * (resourceId, credentialCode) 唯一；有效期为 UTC 半开区间 [validFrom, validUntil)，
 * 严格覆盖任务计划完成时刻要求 validFrom <= plannedCompleteAt < validUntil。
 * revoked 为 true 表示已提前撤销（revokedAt 为撤销 UTC 时刻），
 * 撤销后该资质不再满足任何租约校验；同代码撤销后可重新登记（覆盖有效期并复位撤销标记）。
 */
public record ResourceCredential(
        long id,
        long resourceId,
        String credentialCode,
        Instant validFrom,
        Instant validUntil,
        boolean revoked,
        Instant revokedAt,
        Instant createdAt) {

    /**
     * 判断该资质在未撤销前提下是否严格覆盖指定 UTC 时刻。
     */
    public boolean covers(Instant at) {
        return !revoked && !validFrom.isAfter(at) && validUntil.isAfter(at);
    }
}
