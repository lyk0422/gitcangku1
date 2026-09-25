package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 终止保养工单请求：已开始工单不可取消，可关闭或按既有规则终止；终止仅允许进行中工单。
 *
 * @param workOrderKey      工单幂等键
 * @param expectedVersion   设备期望版本号
 * @param workOrderVersion  工单期望版本号
 * @param reason            终止原因（必填）
 */
public record TerminateWorkOrderRequest(
        @NotBlank String workOrderKey,
        @NotNull Long expectedVersion,
        @NotNull Long workOrderVersion,
        @NotBlank String reason) {
}
