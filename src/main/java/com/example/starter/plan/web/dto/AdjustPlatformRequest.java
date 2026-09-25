package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 调整站台有效长度请求，expectedVersion 做乐观校验；下调会在同一事务回查未来已发布计划。
 */
public record AdjustPlatformRequest(
        @NotBlank String requestKey,
        @NotNull Integer expectedVersion,
        @NotNull @Positive Integer effectiveLength) {
}
