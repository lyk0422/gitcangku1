package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 续租请求：须本人（agentId）及当前 token，且租约未到期。
 *
 * @param requestId 全局唯一请求ID
 * @param agentId   续租代理ID，必须与当前持有人一致
 * @param token     续租令牌，必须与当前租约令牌一致
 */
public record RenewLeaseRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String agentId,
        @NotNull @Positive Integer token) {
}
