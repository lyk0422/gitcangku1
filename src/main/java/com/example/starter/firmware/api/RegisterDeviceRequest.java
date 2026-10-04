package com.example.starter.firmware.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 设备登记请求。currentDigest 可选：不传时服务端合成当前镜像 SHA-256 摘要；
 * 传入时须为 64 位十六进制（大小写均可，统一落库为小写）。
 */
public record RegisterDeviceRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String deviceId,
        @NotBlank @Size(max = 64) String model,
        @NotBlank @Size(max = 64) String currentVersion,
        @Min(0) @Max(99) int bucketNo,
        @Size(max = 64) String currentDigest) {

    /**
     * 兼容旧调用方：不显式声明摘要时由服务端合成。
     */
    public RegisterDeviceRequest(String requestId, String deviceId, String model, String currentVersion,
                                 int bucketNo) {
        this(requestId, deviceId, model, currentVersion, bucketNo, null);
    }
}