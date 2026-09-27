package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 固件版本登记请求。登记后该固件版本纳入签名证书链管理。
 */
public record RegisterFirmwareRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String model,
        @NotBlank @Size(max = 64) String version,
        @NotBlank @Size(max = 64) String operator) {
}
