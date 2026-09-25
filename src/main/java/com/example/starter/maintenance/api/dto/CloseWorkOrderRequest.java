package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 关闭保养工单请求：写入不可变保养状态快照（基线、最后有效读数、关闭时刻）。
 *
 * @param workOrderKey      工单幂等键
 * @param expectedVersion   设备期望版本号
 * @param workOrderVersion  工单期望版本号
 */
public record CloseWorkOrderRequest(
        @NotBlank String workOrderKey,
        @NotNull Long expectedVersion,
        @NotNull Long workOrderVersion) {
}
