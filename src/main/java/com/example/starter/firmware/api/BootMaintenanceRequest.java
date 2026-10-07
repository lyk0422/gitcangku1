package com.example.starter.firmware.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 设备启动计数维护请求：将设备启动计数维护到 bootCount（不可递减的非负十进制文本，按数值比较），
 * expectedDeviceVersion 必须等于当前档案版本，成功后档案版本加一。操作者非空。
 */
public record BootMaintenanceRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @NotBlank @Size(max = 32) String bootCount,
        @Min(1) int expectedDeviceVersion) {
}
