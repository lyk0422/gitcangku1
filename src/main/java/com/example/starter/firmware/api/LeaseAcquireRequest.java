package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 领取（或到期接管）执行租约请求：代理以非空 agentId 显式领取。
 */
public record LeaseAcquireRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String agentId) {
}
