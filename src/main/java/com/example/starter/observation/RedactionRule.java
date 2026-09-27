package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 脱敏规则版本登记记录：对应 redaction_rule 表的一行。
 *
 * @param ruleCode        规则标识（同一规则的不同版本共用）
 * @param ruleVersion     规则版本号，从 1 开始
 * @param ruleName        规则版本名称
 * @param revoked         是否已撤销登记：true 后不可再用于新脱敏
 * @param revokedAtUtc    规则版本撤销时刻（UTC）；未撤销为 null
 * @param registeredAtUtc 规则版本登记时刻（UTC）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RedactionRule(
        String ruleCode,
        int ruleVersion,
        String ruleName,
        boolean revoked,
        Instant revokedAtUtc,
        Instant registeredAtUtc) {
}
