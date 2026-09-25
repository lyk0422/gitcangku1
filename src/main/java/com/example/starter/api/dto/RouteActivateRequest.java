package com.example.starter.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 激活航线版本请求：要求该版本有当前有效的 CLEAR 审查结论；
 * 激活后按起飞时刻与恒定地速计算网格穿越序列并占用容量。
 *
 * @param routeId        航线标识
 * @param expectedVersion 明确的航线版本（须为当前版本）
 * @param departureTime  计划起飞时刻，epoch 秒（UTC）
 * @param speedMps       恒定地速，米/秒，> 0
 * @param requestId      写操作全局唯一请求标识，用于幂等重放
 */
public record RouteActivateRequest(
        @NotBlank @Size(max = 64) String routeId,
        @NotNull Integer expectedVersion,
        @NotNull Long departureTime,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) Double speedMps,
        @NotBlank @Size(max = 64) String requestId) {
}
