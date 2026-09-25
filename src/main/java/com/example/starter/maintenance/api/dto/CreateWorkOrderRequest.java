package com.example.starter.maintenance.api.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 创建保养工单请求。建单时冻结当前已认证读数（设备最新生效读数）为基线，
 * 并锁定允许登记的 UTC 左闭右开窗口 [windowStart, windowEnd)。
 *
 * @param workOrderKey    工单标识（全局唯一，兼作幂等键；同键同参重放，异参 409，失败不占键）
 * @param expectedVersion 设备期望版本号，与当前版本不一致时返回 409
 * @param windowStart     允许登记窗口起始时刻（UTC，含）
 * @param windowEnd       允许登记窗口结束时刻（UTC，不含），必须晚于 windowStart，否则 422
 */
public record CreateWorkOrderRequest(
        @NotBlank String workOrderKey,
        @NotNull Long expectedVersion,
        @NotNull Instant windowStart,
        @NotNull Instant windowEnd) {
}
