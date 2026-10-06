package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 设备人工恢复请求：携带期望健康版本号，成功后将设备恢复健康且健康版本加一。
 * operator 为非空操作者标识，计入幂等指纹。
 */
public record RecoverDeviceRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @NotNull @Positive Integer expectedHealthVersion) {
}
