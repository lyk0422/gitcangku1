package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 脱敏规则版本响应。
 *
 * @param ruleVersion  规则版本唯一标识
 * @param status       规则状态：ACTIVE / REVOKED
 * @param description  规则版本说明
 * @param createdAtUtc 登记完成时刻（UTC，ISO-8601）
 * @param revokedAtUtc 撤销时刻（UTC）；未撤销不返回
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RedactionRuleResponse(
        String ruleVersion,
        String status,
        String description,
        Instant createdAtUtc,
        Instant revokedAtUtc) {

    public static RedactionRuleResponse of(RedactionRuleRecord record) {
        return new RedactionRuleResponse(record.ruleVersion(), record.active() ? "ACTIVE" : "REVOKED",
                record.description(), record.createdAtUtc(), record.revokedAtUtc());
    }
}
