package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 脱敏规则版本视图。
 *
 * @param ruleVersion     规则版本标识
 * @param description     规则描述
 * @param status          状态：ACTIVE / REVOKED
 * @param registeredAtUtc 登记时刻（UTC，ISO-8601）
 * @param revokedAtUtc    撤销时刻（UTC），未撤销为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RuleView(
        String ruleVersion,
        String description,
        String status,
        Instant registeredAtUtc,
        Instant revokedAtUtc) {

    public static RuleView of(RedactionRule rule) {
        return new RuleView(rule.ruleVersion(), rule.description(), rule.status(),
                rule.registeredAtUtc(), rule.revokedAtUtc());
    }
}
