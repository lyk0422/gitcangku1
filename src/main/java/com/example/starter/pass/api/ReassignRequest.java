package com.example.starter.pass.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 待重新分配时隙的重新分配申请，按卫星最新预测版本重新校核。
 *
 * @param stationCode 新的接收站点编码
 * @param windowStart 新窗口起点（含），UTC
 * @param windowEnd   新窗口终点（不含），UTC
 */
public record ReassignRequest(
        @NotBlank String stationCode,
        @NotNull Instant windowStart,
        @NotNull Instant windowEnd
) {
}
