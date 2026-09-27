package com.example.starter.maintenance;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 登记维护窗口请求：UTC 左闭右开区间，生效窗口不得与站点内其他生效窗口重叠。
 *
 * @param requestId  全局唯一请求标识（幂等去重键）
 * @param windowId   维护窗口唯一标识
 * @param startAtUtc 窗口开始时刻（UTC，含）；不得早于站点启用时刻
 * @param endAtUtc   窗口结束时刻（UTC，不含）；必须严格晚于开始时刻
 */
public record RegisterWindowRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 64) String windowId,
        @NotNull Instant startAtUtc,
        @NotNull Instant endAtUtc) {
}
