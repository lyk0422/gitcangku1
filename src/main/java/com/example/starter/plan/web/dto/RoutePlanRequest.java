package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * 寻径发布请求：按图版本、列车、起终站与出发/最晚到达窗口直接寻径并发布。
 * departUtc 必须为 UTC 整分钟。
 */
public record RoutePlanRequest(
        @NotBlank String requestKey,
        @NotBlank String operator,
        @NotNull Integer graphVersion,
        @NotBlank String trainNo,
        @NotBlank String fromStation,
        @NotBlank String toStation,
        @NotNull Instant departUtc,
        @NotNull Instant arriveByUtc) {
}
