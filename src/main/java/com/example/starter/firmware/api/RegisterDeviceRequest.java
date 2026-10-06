package com.example.starter.firmware.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 设备登记请求。totalSpaceBytes 与 currentImageBytes 为可选空间档案（版本1）：
 * 必须成对提供，均为正整数且当前镜像不超过总空间；均不提供时保留旧模式，不做暂存空间核验。
 */
public record RegisterDeviceRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String deviceId,
        @NotBlank @Size(max = 64) String model,
        @NotBlank @Size(max = 64) String currentVersion,
        @Min(0) @Max(99) int bucketNo,
        @Min(1) Long totalSpaceBytes,
        @Min(0) Long currentImageBytes) {

    /**
     * 兼容旧调用方：不登记空间档案。
     */
    public RegisterDeviceRequest(String requestId, String deviceId, String model,
                                 String currentVersion, int bucketNo) {
        this(requestId, deviceId, model, currentVersion, bucketNo, null, null);
    }
}
