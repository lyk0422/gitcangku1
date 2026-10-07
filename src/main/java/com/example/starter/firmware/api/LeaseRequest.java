package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 领取执行租约请求：代理以非空 agentId 显式领取，requestId 用于幂等去重。
 *
 * @param requestId 全局唯一请求ID
 * @param agentId   执行代理ID，非空，最长64字符
 */
public record LeaseRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String agentId) {
}
