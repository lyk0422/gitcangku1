package com.example.starter.firmware.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 设备登记请求。登记时合成当前镜像SHA-256摘要，镜像代次从1开始。
 */
public record RegisterDeviceRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String deviceId,
        @NotBlank @Size(max = 64) String model,
        @NotBlank @Size(max = 64) String currentVersion,
        @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$", message = "当前镜像摘要须为64位小写十六进制")
        String currentDigest,
        @Min(0) @Max(99) int bucketNo) {
}