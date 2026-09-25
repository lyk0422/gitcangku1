package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

/**
 * 修改乘务资质请求。expectedVersion 做乐观校验，成功后版本加一；
 * 覆盖区段集合换序视为同参（服务端规范化）。
 */
public record UpdateQualificationRequest(
        @NotBlank String requestKey,
        @NotNull Integer expectedVersion,
        @NotBlank String crewId,
        @NotEmpty List<@NotBlank String> sections,
        @NotNull Instant expiresUtc) {
}
