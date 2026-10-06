package com.example.starter.firmware.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 设备空间维护请求：调整总空间。expectedDeviceVersion 必须等于当前空间档案版本，成功加一；
 * 调小后低于当前镜像加活跃暂存预留时返回 409 并携带当前 expectedDeviceVersion。
 */
public record UpdateDeviceSpaceRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull @Min(1) Long totalSpaceBytes,
        @Min(1) int expectedDeviceVersion) {
}
