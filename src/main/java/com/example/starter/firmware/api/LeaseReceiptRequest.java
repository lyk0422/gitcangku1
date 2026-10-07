package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReceiptResult;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 租约模式回执请求：除回执结果外必须携带非空 agentId 与正数 token，
 * 服务端校验其为当前有效持有人，旧代理或到期令牌一律 409。
 */
public record LeaseReceiptRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String agentId,
        @NotNull @Positive Integer token,
        @NotNull ReceiptResult result) {
}
