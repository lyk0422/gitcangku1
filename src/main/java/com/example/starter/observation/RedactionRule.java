package com.example.starter.observation;

import java.time.Instant;

/**
 * 脱敏规则版本：对应 redaction_rule 表的一行。规则版本须先登记才能用于脱敏；
 * 撤销后不可再用于新脱敏，但不改写既有脱敏版本。
 *
 * @param ruleVersion     规则版本标识
 * @param description     规则描述
 * @param status          状态：ACTIVE / REVOKED
 * @param registeredAtUtc 登记时刻（UTC）
 * @param revokedAtUtc    撤销时刻（UTC），未撤销为 null
 */
public record RedactionRule(
        String ruleVersion,
        String description,
        String status,
        Instant registeredAtUtc,
        Instant revokedAtUtc) {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_REVOKED = "REVOKED";
}
