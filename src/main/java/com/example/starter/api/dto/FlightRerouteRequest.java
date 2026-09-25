package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 航班改航请求。仅 RUNWAY_RISK 状态航班可改航；改航后回到 PENDING 等待重新审查。
 *
 * @param flightId    航班唯一标识
 * @param depRunwayId 新起飞跑道标识
 * @param depTimeUtc  新计划起飞时刻，epoch 毫秒（UTC）
 * @param arrRunwayId 新落地跑道标识
 * @param arrTimeUtc  新计划落地时刻，epoch 毫秒（UTC），不早于起飞时刻
 * @param requestId   写操作全局唯一请求标识，用于幂等重放
 */
public record FlightRerouteRequest(
        @NotBlank @Size(max = 64) String flightId,
        @NotBlank @Size(max = 64) String depRunwayId,
        @NotNull Long depTimeUtc,
        @NotBlank @Size(max = 64) String arrRunwayId,
        @NotNull Long arrTimeUtc,
        @NotBlank @Size(max = 64) String requestId) {
}
