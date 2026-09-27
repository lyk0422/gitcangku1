package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 登记脱敏规则版本请求。规则版本登记后才可用于脱敏；规则定义不可变。
 *
 * @param requestId        全局唯一请求标识（幂等去重键）
 * @param role             请求角色：登记规则需要 REDACTOR 或 ADMIN
 * @param ruleVersion      规则版本标识
 * @param description      规则版本说明；可为 null
 * @param maskTerms        敏感词条列表；可为空列表（恒等规则）
 * @param replacementToken 命中敏感词条时的替换标记
 */
public record RegisterRuleRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 16) String role,
        @NotBlank @Size(max = 128) String ruleVersion,
        @Size(max = 1024) String description,
        @NotNull List<@NotBlank @Size(max = 128) String> maskTerms,
        @NotBlank @Size(max = 128) String replacementToken) {
}
