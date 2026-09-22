package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 单条依赖声明：被依赖制品名称及闭区间兼容版本 [minVersion, maxVersion]。
 */
public record DependencyRequest(
        @NotBlank String name,
        @NotNull @Positive Integer minVersion,
        @NotNull @Positive Integer maxVersion
) {
}
