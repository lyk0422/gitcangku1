package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 替换航线请求。替换点列的同时携带（可能更新的）巡航高度与 UTC 时间窗；
 * expectedVersion 不匹配返回 409；成功后航线版本加一并使当前审查失效。
 *
 * @param routeId        航线唯一标识
 * @param expectedVersion 期望的当前航线版本（版本从 1 开始）
 * @param points         新的有序航点
 * @param cruiseAltitude 新巡航高度（米）
 * @param startUtc       新 UTC 起始时刻（含），epoch 毫秒
 * @param endUtc         新 UTC 结束时刻（不含），epoch 毫秒，须晚于 startUtc
 * @param requestId      写操作全局唯一请求标识，用于幂等重放
 */
public record RouteReplaceRequest(
        @NotBlank @Size(max = 64) String routeId,
        @NotNull Integer expectedVersion,
        @NotNull @Valid @Size(min = 2, max = 50) List<RoutePointDto> points,
        @NotNull Integer cruiseAltitude,
        @NotNull Long startUtc,
        @NotNull Long endUtc,
        @NotBlank @Size(max = 64) String requestId) {
}
