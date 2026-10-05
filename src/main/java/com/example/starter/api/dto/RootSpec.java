package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 联合锁定中的单个根：精确名称与不可回退的精确版本。
 */
public record RootSpec(
        @NotBlank String name,
        @Positive int version) {
}
