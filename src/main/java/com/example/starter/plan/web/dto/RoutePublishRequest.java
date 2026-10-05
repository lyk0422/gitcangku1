package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * 寻径发布请求。requestId 为幂等键；graphVersion 必须等于当前图版本；
 * departUtc 须为 UTC 整分钟；latestArrivalUtc 为最晚到达时刻（含）。
 */
public record RoutePublishRequest(
        @NotBlank String requestId,
        @NotBlank String operator,
        @NotNull Integer graphVersion,
        @NotBlank String trainNo,
        @NotBlank String fromStation,
        @NotBlank String toStation,
        @NotNull Instant departUtc,
        @NotNull Instant latestArrivalUtc) {
}
