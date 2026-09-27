package com.example.starter.maintenance;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 登记维护窗口请求：UTC 左闭右开区间 + 维护版本；同站点 ACTIVE 窗口重叠拒绝登记。
 *
 * @param requestId     全局唯一请求标识（幂等去重键）
 * @param windowId      维护窗口唯一标识
 * @param startUtc      维护开始时刻（UTC，区间左闭），不得早于站点启用时刻
 * @param endUtc        维护结束时刻（UTC，区间右开），必须晚于开始时刻
 * @param windowVersion 维护版本号
 */
public record RegisterWindowRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String windowId,
        @NotNull Instant startUtc,
        @NotNull Instant endUtc,
        @NotNull @Min(1) Integer windowVersion) {
}
