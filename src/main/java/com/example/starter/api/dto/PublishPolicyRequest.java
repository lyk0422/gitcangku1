package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 发布替代策略请求：规则集合整体激活、整体校验。
 */
public record PublishPolicyRequest(
        @Valid @Size(min = 1, max = 20) @NotNull List<@Valid RuleSpec> rules) {

    public PublishPolicyRequest {
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    /**
     * 单条规则：原坐标模式、目标平台、1～5 个候选及生效 UTC 时刻。
     */
    public record RuleSpec(
            @NotBlank String source,
            @NotBlank String platform,
            @Valid @Size(min = 1, max = 5) @NotNull List<@Valid CandidateSpec> candidates,
            @NotNull Instant effectiveAt) {

        public RuleSpec {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }

    /**
     * 替代候选：坐标与规则内优先级（1 最高）。
     */
    public record CandidateSpec(
            @NotBlank String coordinate,
            @Positive int priority) {
    }
}
