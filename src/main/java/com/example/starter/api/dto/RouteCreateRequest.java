package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建航线请求。点列按顺序连接，需 2~50 个点且至少两个点不同；
 * 携带单一巡航高度与 UTC 半开时间窗（[startUtc, endUtc)，epoch 毫秒）。
 *
 * @param routeId        航线唯一标识
 * @param points         有序航点
 * @param cruiseAltitude 巡航高度（米）
 * @param startUtc       UTC 起始时刻（含），epoch 毫秒
 * @param endUtc         UTC 结束时刻（不含），epoch 毫秒，须晚于 startUtc
 * @param requestId      写操作全局唯一请求标识，用于幂等重放
 */
public record RouteCreateRequest(
        @NotBlank @Size(max = 64) String routeId,
        @NotNull @Valid @Size(min = 2, max = 50) List<RoutePointDto> points,
        @NotNull Integer cruiseAltitude,
        @NotNull Long startUtc,
        @NotNull Long endUtc,
        @NotBlank @Size(max = 64) String requestId) {
}
