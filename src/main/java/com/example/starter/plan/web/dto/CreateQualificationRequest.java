package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

/**
 * 创建乘务资质请求。requestKey 为幂等键；sections 为覆盖区段集合（换序视为同参，服务端规范化）。
 */
public record CreateQualificationRequest(
        @NotBlank String requestKey,
        @NotBlank String qualCode,
        @NotBlank String crewId,
        @NotEmpty List<@NotBlank String> sections,
        @NotNull Instant expiresUtc) {
}
