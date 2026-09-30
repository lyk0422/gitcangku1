package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 创建依赖风险豁免申请：精确到依赖名称与单一版本，附 UTC 到期时刻与业务原因。
 *
 * @param expiresAt 到期 UTC 时刻；恰好到达该时刻即失效
 */
public record ExceptionCreateRequest(
        @NotBlank @Size(max = 128) String dependencyName,
        @Positive int dependencyVersion,
        @NotNull Instant expiresAt,
        @NotBlank @Size(max = 1024) String reason) {
}
