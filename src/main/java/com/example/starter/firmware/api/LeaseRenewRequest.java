package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 续租请求：须本人（agentId）凭当前未到期 token 续租，期限顺延为当前时刻后 30 秒。
 */
public record LeaseRenewRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String agentId,
        @NotNull @Positive Integer token) {
}
