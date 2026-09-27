package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 维护窗口登记请求：登记一条 UTC 左闭右开维护区间 [startAtUtc, endAtUtc)，与同站有效窗口重叠则拒绝。
 *
 * @param requestId  全局唯一请求标识（幂等去重键）
 * @param windowId   维护窗口唯一标识
 * @param stationId  所属观测站标识
 * @param startAtUtc 窗口开始时刻（UTC，左闭）；不得早于站点启用时刻
 * @param endAtUtc   窗口结束时刻（UTC，右开）；必须严格晚于开始时刻
 */
public record RegisterMaintenanceWindowRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String windowId,
        @NotBlank @Size(max = 64) String stationId,
        @NotNull Instant startAtUtc,
        @NotNull Instant endAtUtc) {
}
