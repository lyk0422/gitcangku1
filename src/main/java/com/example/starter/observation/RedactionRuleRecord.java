package com.example.starter.observation;

import java.time.Instant;

/**
 * 脱敏规则版本登记记录：对应 redaction_rule 表一行。
 * 只有 ACTIVE 规则可用于新的脱敏请求；撤销（REVOKED）后既有脱敏版本不受影响，仅新请求被拒绝（422）。
 *
 * @param ruleVersion    规则版本唯一标识
 * @param description    规则版本说明
 * @param active         是否登记可用：true=ACTIVE，false=REVOKED
 * @param revokedReason  规则撤销原因；未撤销为 null
 * @param registeredAtUtc 规则登记时刻（UTC）
 * @param revokedAtUtc   规则撤销时刻（UTC）；未撤销为 null
 */
public record RedactionRuleRecord(
        String ruleVersion,
        String description,
        boolean active,
        String revokedReason,
        Instant registeredAtUtc,
        Instant revokedAtUtc) {
}
