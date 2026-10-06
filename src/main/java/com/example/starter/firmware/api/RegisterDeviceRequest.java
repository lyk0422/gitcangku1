package com.example.starter.firmware.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 设备登记请求。totalSpaceBytes 与 currentImageBytes 为可选的空间档案，
 * 缺省（均不传）表示旧模式不做暂存峰值核验；传入时两者均为正整数且当前镜像不大于总空间。
 */
public record RegisterDeviceRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String deviceId,
        @NotBlank @Size(max = 64) String model,
        @NotBlank @Size(max = 64) String currentVersion,
        @Min(0) @Max(99) int bucketNo,
        @Min(1) Long totalSpaceBytes,
        @Min(1) Long currentImageBytes) {

    /**
     * 兼容旧调用方：不传空间档案字段即旧模式。
     */
    public RegisterDeviceRequest(String requestId, String deviceId, String model,
                                 String currentVersion, int bucketNo) {
        this(requestId, deviceId, model, currentVersion, bucketNo, null, null);
    }
}
