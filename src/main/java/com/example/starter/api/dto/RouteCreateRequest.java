package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建航线请求。点列按顺序连接，需 2~50 个点且至少两个点不同。
 *
 * @param routeId        航线唯一标识
 * @param points         有序航点
 * @param envelopeRadius 水平包络半径（非负整数米）；null 表示旧无包络航线，按 0 处理
 * @param requestId      写操作全局唯一请求标识，用于幂等重放
 */
public record RouteCreateRequest(
        @NotBlank @Size(max = 64) String routeId,
        @NotNull @Valid @Size(min = 2, max = 50) List<RoutePointDto> points,
        @Min(0) @Max(1000000) Integer envelopeRadius,
        @NotBlank @Size(max = 64) String requestId) {

    /** 兼容旧无包络请求：不携带包络半径，按 0 处理。 */
    public RouteCreateRequest(String routeId, List<RoutePointDto> points, String requestId) {
        this(routeId, points, null, requestId);
    }
}
