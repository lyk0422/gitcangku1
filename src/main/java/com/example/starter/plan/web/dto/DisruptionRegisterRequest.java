package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * 区段封锁切换单登记请求。requestId 为登记操作的幂等键；窗口为左闭右开 UTC 区间。
 */
public record DisruptionRegisterRequest(
        @NotBlank String requestId,
        @NotBlank String switchKey,
        @NotBlank String sectionId,
        @NotNull Instant windowStartUtc,
        @NotNull Instant windowEndUtc) {
}
