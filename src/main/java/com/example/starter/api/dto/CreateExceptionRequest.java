package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 豁免创建请求：针对精确锁定图版本与漏洞编号，由第一审核人发起；
 * expiresAt 为 UTC 到期时刻，必须在未来。
 */
public record CreateExceptionRequest(
        @NotBlank String vulnerabilityId,
        @NotBlank String reviewer,
        @NotNull Instant expiresAt,
        @NotBlank String reason) {
}
