package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 依赖声明：闭区间 [minimumVersion, maximumVersion]，版本均为正整数。
 */
public record DependencySpec(
        @NotBlank String name,
        @Positive int minimumVersion,
        @Positive int maximumVersion) {
}
