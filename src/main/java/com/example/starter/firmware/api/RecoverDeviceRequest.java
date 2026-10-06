package com.example.starter.firmware.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 设备人工恢复请求：expectedHealthVersion 必须等于设备当前健康版本，成功加一。
 */
public record RecoverDeviceRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @Min(1) int expectedHealthVersion) {
}
