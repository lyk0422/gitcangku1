package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 危险品互斥类别隔离规则配置项。类别对无序，提交时按字典序规范化。
 */
public record SeparationRuleRequest(
        @NotBlank @Size(max = 64) String categoryA,
        @NotBlank @Size(max = 64) String categoryB,
        @NotNull @Min(0) @Max(9999) Integer minSeparation) {
}
