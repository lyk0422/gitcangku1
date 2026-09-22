package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 锁定请求：精确根版本与期望的仓库版本号。
 */
public record LockRequest(
        @NotBlank String rootName,
        @Positive int rootVersion,
        @NotNull @PositiveOrZero Long expectedRepositoryVersion) {
}
