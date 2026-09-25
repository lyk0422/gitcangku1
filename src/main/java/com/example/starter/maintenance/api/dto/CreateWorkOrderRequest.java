package com.example.starter.maintenance.api.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 创建保养工单请求：冻结基线读数快照与允许登记的 UTC 左闭右开窗口。
 *
 * @param workOrderKey       工单幂等键；同键同参重放，异参 409，失败不占键
 * @param expectedVersion    设备期望版本号
 * @param workOrderId        工单标识，设备内唯一
 * @param baselineReadingId  基线读数标识，须为设备当前已认证读数
 * @param windowStart        允许登记窗口起点（UTC，含）
 * @param windowEnd          允许登记窗口终点（UTC，不含），必须严格晚于起点
 */
public record CreateWorkOrderRequest(
        @NotBlank String workOrderKey,
        @NotNull Long expectedVersion,
        @NotBlank String workOrderId,
        @NotBlank String baselineReadingId,
        @NotNull Instant windowStart,
        @NotNull Instant windowEnd) {
}
