package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 互斥能力规则新增/撤销请求：一对能力名（无序）与期望的仓库版本号。
 */
public record ExclusionRuleRequest(
        @NotBlank @Size(max = 64) String capabilityA,
        @NotBlank @Size(max = 64) String capabilityB,
        @NotNull @PositiveOrZero Long expectedRepositoryVersion) {
}