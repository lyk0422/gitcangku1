package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * 寻径发布请求：按图版本寻径并一次发布整张日计划。
 * requestId 为幂等键；departUtc 必须为 UTC 整分钟；latestArrivalUtc 为最晚到达（含）。
 */
public record RoutePublishRequest(
        @NotBlank String requestId,
        @NotBlank String operator,
        @NotBlank String scheduleKey,
        @NotNull Integer graphVersion,
        @NotBlank String trainNo,
        @NotBlank String fromStation,
        @NotBlank String toStation,
        @NotNull Instant departUtc,
        @NotNull Instant latestArrivalUtc) {
}
