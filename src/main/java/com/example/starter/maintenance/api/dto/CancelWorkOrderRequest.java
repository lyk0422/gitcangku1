package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 取消保养工单请求：仅未开始（CREATED）工单允许取消，已开始工单返回 409。
 *
 * @param workOrderKey      工单幂等键
 * @param expectedVersion   设备期望版本号
 * @param workOrderVersion  工单期望版本号
 */
public record CancelWorkOrderRequest(
        @NotBlank String workOrderKey,
        @NotNull Long expectedVersion,
        @NotNull Long workOrderVersion) {
}
