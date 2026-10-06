package com.example.starter.firmware.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 设备安全维护请求：提升设备安全底线。
 * expectedDeviceVersion 必须等于设备当前安全档案版本，成功加一；securityFloor 低于当前底线时返回 400。
 */
public record MaintenanceRequest(
        @NotBlank @Size(max = 64) String requestId,
        @Size(max = 64) String operator,
        @Min(1) int expectedDeviceVersion,
        @Min(0) int securityFloor) {
}
