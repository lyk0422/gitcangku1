package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 脱敏规则版本明细。
 *
 * @param ruleVersion      规则版本标识
 * @param description      规则版本说明；可为 null
 * @param maskTerms        敏感词条列表（JSON 数组原文）
 * @param replacementToken 替换标记
 * @param status           状态：ACTIVE / REVOKED
 * @param registeredAtUtc  登记完成时刻（UTC，ISO-8601）
 * @param revokedAtUtc     撤销时刻（UTC，ISO-8601）；未撤销为 null
 * @param revokeReason     撤销理由；未撤销为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RuleResponse(
        String ruleVersion,
        String description,
        String maskTerms,
        String replacementToken,
        RuleStatus status,
        Instant registeredAtUtc,
        Instant revokedAtUtc,
        String revokeReason) {
}
