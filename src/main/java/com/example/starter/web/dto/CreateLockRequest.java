package com.example.starter.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 依赖锁定请求：指定精确根版本及期望的仓库版本。
 */
public record CreateLockRequest(
        @NotBlank String requestId,
        @NotBlank String rootName,
        @NotNull @Positive Integer rootVersion,
        @NotNull @Min(0) Long expectedRepositoryVersion
) {
}
