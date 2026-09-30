package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建备降机场请求。
 *
 * @param airportId 机场唯一标识
 * @param capacity  同一窗口可同时容纳的预留数量；必须 &gt;=1，否则返回 422
 * @param requestId 写操作全局唯一请求标识，用于幂等重放
 */
public record AirportCreateRequest(
        @NotBlank @Size(max = 64) String airportId,
        @NotNull Integer capacity,
        @NotBlank @Size(max = 64) String requestId) {
}
