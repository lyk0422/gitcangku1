package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 管理员发布替代策略请求：policyKey 唯一；规则集合整体激活。
 *
 * @param policyKey 策略业务键，全局唯一
 * @param rules     规则列表，至少 1 条
 */
public record PublishPolicyRequest(
        @NotBlank String policyKey,
        @NotNull @Valid @Size(min = 1) List<@Valid RuleSpec> rules) {

    public PublishPolicyRequest {
        if (rules != null) {
            rules = List.copyOf(rules);
        }
    }

    /**
     * 单条规则：原坐标模式、目标平台、1～5 个替代坐标及生效 UTC 时刻。
     */
    public record RuleSpec(
            @NotBlank String sourcePattern,
            @NotBlank String targetPlatform,
            @NotNull Instant effectiveAt,
            @NotNull @Valid @Size(min = 1, max = 5) List<@Valid AlternativeSpec> alternatives) {

        public RuleSpec {
            if (alternatives != null) {
                alternatives = List.copyOf(alternatives);
            }
        }
    }

    /**
     * 替代坐标：精确制品版本。
     */
    public record AlternativeSpec(
            @NotBlank String name,
            @jakarta.validation.constraints.Positive int version) {
    }
}
