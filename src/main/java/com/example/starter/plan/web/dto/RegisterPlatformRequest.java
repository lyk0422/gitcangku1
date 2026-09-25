package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 登记站台请求，requestKey 为幂等键；有效长度单位米且必须为正。
 */
public record RegisterPlatformRequest(
        @NotBlank String requestKey,
        @NotBlank String platformCode,
        @NotNull @Positive Integer effectiveLength) {
}
