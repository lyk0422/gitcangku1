package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 开始保养工单请求：仅未开始（CREATED）工单可开始。
 *
 * @param workOrderKey      工单幂等键
 * @param expectedVersion   设备期望版本号
 * @param workOrderVersion  工单期望版本号（未开始工单为 1）
 */
public record StartWorkOrderRequest(
        @NotBlank String workOrderKey,
        @NotNull Long expectedVersion,
        @NotNull Long workOrderVersion) {
}
