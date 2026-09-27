package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 设备最终信任状态登记/变更请求：trusted=false 即不信任，批量启动遇此设备整批 422。
 */
public record DeviceTrustRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 96) String anchorFingerprint,
        @NotNull Boolean trusted,
        @NotBlank @Size(max = 64) String operator) {
}
