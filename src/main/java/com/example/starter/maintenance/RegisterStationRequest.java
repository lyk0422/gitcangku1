package com.example.starter.maintenance;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 登记观测站请求。
 *
 * @param requestId    全局唯一请求标识（幂等去重键）
 * @param stationId    观测站唯一标识
 * @param enabledAtUtc 站点启用时刻（UTC，ISO-8601）；维护窗口开始时刻不得早于该时刻
 */
public record RegisterStationRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 64) String stationId,
        @NotNull Instant enabledAtUtc) {
}
