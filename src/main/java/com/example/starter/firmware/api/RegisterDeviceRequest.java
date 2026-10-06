package com.example.starter.firmware.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 设备登记请求。securityFloor 为首次登记的安全底线（非负整数），缺省为 0，登记后只能提升。
 */
public record RegisterDeviceRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String deviceId,
        @NotBlank @Size(max = 64) String model,
        @NotBlank @Size(max = 64) String currentVersion,
        @Min(0) @Max(99) int bucketNo,
        @Min(0) Integer securityFloor) {

    /**
     * 兼容旧调用方：不登记安全底线时按 0 处理。
     */
    public RegisterDeviceRequest(String requestId, String deviceId, String model,
                                 String currentVersion, int bucketNo) {
        this(requestId, deviceId, model, currentVersion, bucketNo, null);
    }

    public int effectiveSecurityFloor() {
        return securityFloor == null ? 0 : securityFloor;
    }
}
