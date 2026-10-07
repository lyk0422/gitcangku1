package com.example.starter.incident;

import java.time.Instant;

/**
 * 双人监护限时在场凭证实体，对应 task_presence_credentials 表。
 * 每人（taskId, role）代次从 1 开始，重新签到推进 +1 并使前代失效；
 * 有效期为 [issuedAt, expiresAt) 左闭右开，固定签发后 60 秒；
 * revokedAt 仅被当前指挥人撤销后有值。时间均为 UTC。
 */
public record PresenceCredential(
        long id,
        long taskId,
        String role,
        String operator,
        int generation,
        Instant issuedAt,
        Instant expiresAt,
        Instant revokedAt,
        Instant createdAt) {

    /** 在场角色：执行人。 */
    public static final String ROLE_EXECUTOR = "EXECUTOR";
    /** 在场角色：监护人。 */
    public static final String ROLE_GUARDIAN = "GUARDIAN";

    /**
     * 判断该凭证在指定 UTC 时刻是否有效：未撤销且时刻落在 [issuedAt, expiresAt) 内。
     */
    public boolean validAt(Instant at) {
        return revokedAt == null && !at.isBefore(issuedAt) && at.isBefore(expiresAt);
    }
}
