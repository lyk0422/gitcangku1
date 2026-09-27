package com.example.starter.observation;

import java.time.Instant;

/**
 * 脱敏规则版本登记记录：对应 redaction_rule 表一行。
 *
 * @param ruleVersion  规则版本唯一标识
 * @param active       是否可用：true=ACTIVE 可用于新脱敏；false=REVOKED 已撤销
 * @param description  规则版本说明
 * @param createdAtUtc 规则登记完成时刻（UTC）
 * @param revokedAtUtc 规则撤销时刻（UTC）；未撤销为 null
 */
public record RedactionRuleRecord(
        String ruleVersion,
        boolean active,
        String description,
        Instant createdAtUtc,
        Instant revokedAtUtc) {
}
