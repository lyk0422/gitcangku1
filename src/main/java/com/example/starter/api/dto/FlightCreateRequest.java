package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 登记航班起降段请求。
 *
 * @param flightId    航班唯一标识
 * @param routeId     关联航线标识（须已存在）
 * @param routeType   航线类型：NORMAL / EMERGENCY
 * @param eventNo     紧急事件号；EMERGENCY 经关闭窗口例外通过时必填，其他情况可空
 * @param depRunwayId 起飞跑道标识
 * @param depTimeUtc  计划起飞时刻，epoch 毫秒（UTC）
 * @param arrRunwayId 落地跑道标识
 * @param arrTimeUtc  计划落地时刻，epoch 毫秒（UTC），不早于起飞时刻
 * @param requestId   写操作全局唯一请求标识，用于幂等重放
 */
public record FlightCreateRequest(
        @NotBlank @Size(max = 64) String flightId,
        @NotBlank @Size(max = 64) String routeId,
        @NotBlank @Size(max = 16) String routeType,
        @Size(max = 64) String eventNo,
        @NotBlank @Size(max = 64) String depRunwayId,
        @NotNull Long depTimeUtc,
        @NotBlank @Size(max = 64) String arrRunwayId,
        @NotNull Long arrTimeUtc,
        @NotBlank @Size(max = 64) String requestId) {
}
