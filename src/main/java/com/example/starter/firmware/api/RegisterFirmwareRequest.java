package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 固件版本登记请求。
 */
public record RegisterFirmwareRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String model,
        @NotBlank @Size(max = 64) String version,
        @NotBlank @Size(max = 512) String packageUri) {
}
