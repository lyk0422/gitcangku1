package com.example.starter.observation;

import java.time.Instant;
import java.util.List;

/**
 * 脱敏规则版本：对应 redaction_rule 表的一行。登记后规则定义不可变；撤销不可逆。
 *
 * @param ruleVersion      规则版本标识
 * @param description      规则版本说明；可为 null
 * @param maskTerms        敏感词条列表（按长度降序替换）
 * @param replacementToken 命中敏感词条时的替换标记
 * @param status           状态：ACTIVE / REVOKED
 * @param registeredAtUtc  登记完成时刻（UTC）
 * @param revokedAtUtc     规则撤销时刻（UTC）；未撤销为 null
 * @param revokeReason     规则撤销理由；未撤销为 null
 */
public record RedactionRuleRecord(
        String ruleVersion,
        String description,
        List<String> maskTerms,
        String replacementToken,
        RuleStatus status,
        Instant registeredAtUtc,
        Instant revokedAtUtc,
        String revokeReason) {
}
