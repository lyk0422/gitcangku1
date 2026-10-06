package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * 区段占用请求项，区间左闭右开，起止均为 UTC 时刻。
 * direction 为行车方向标识：区段存在单线配置时必填且须为正/反方向之一；
 * 区段无配置时可为空，不参与换向间隔检查。
 */
public record OccupancyRequest(
        @NotBlank String trainNo,
        @NotBlank String sectionId,
        @NotNull Instant startUtc,
        @NotNull Instant endUtc,
        @Size(max = 32) String direction) {
}
