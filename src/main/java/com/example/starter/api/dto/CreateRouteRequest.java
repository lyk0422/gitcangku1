package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建航线请求：routeId 唯一，点列 2~50 个按顺序连接，至少两个点不同。
 */
public record CreateRouteRequest(
        @NotBlank String requestId,
        @NotBlank String routeId,
        @NotNull @Size(min = 2, max = 50) List<PointDto> points) {
}
