package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 脱敏规则版本响应。
 *
 * @param ruleVersion     规则版本唯一标识
 * @param description     规则版本说明
 * @param active          是否登记可用
 * @param revokedReason   撤销原因；未撤销不返回
 * @param registeredAtUtc 登记时刻（UTC，ISO-8601）
 * @param revokedAtUtc    撤销时刻（UTC，ISO-8601）；未撤销不返回
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RedactionRuleResponse(
        String ruleVersion,
        String description,
        boolean active,
        String revokedReason,
        Instant registeredAtUtc,
        Instant revokedAtUtc) {

    /**
     * 由规则记录构造响应。
     */
    public static RedactionRuleResponse of(RedactionRuleRecord record) {
        return new RedactionRuleResponse(record.ruleVersion(), record.description(), record.active(),
                record.revokedReason(), record.registeredAtUtc(), record.revokedAtUtc());
    }
}
