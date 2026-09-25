package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 激活航线版本请求：要求该版本审查通过（CLEAR 且当前有效），
 * 按穿越单元序列与起飞时刻占用时空桶容量。
 *
 * @param routeId        航线唯一标识
 * @param expectedVersion 明确的航线版本
 * @param departureTime  起飞时刻，epoch 毫秒（UTC），需对齐 15 分钟桶
 * @param requestId      写操作全局唯一请求标识，用于幂等重放
 */
public record RouteActivateRequest(
        @NotBlank @Size(max = 64) String routeId,
        @NotNull Integer expectedVersion,
        @NotNull Long departureTime,
        @NotBlank @Size(max = 64) String requestId) {
}
