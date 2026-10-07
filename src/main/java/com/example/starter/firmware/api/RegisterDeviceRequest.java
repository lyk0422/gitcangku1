package com.example.starter.firmware.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 设备登记请求。config 为可选初始合成配置（至多 10 个键，值 0~1000000），缺省为空配置；
 * 配置版本从 1 开始。
 */
public record RegisterDeviceRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String deviceId,
        @NotBlank @Size(max = 64) String model,
        @NotBlank @Size(max = 64) String currentVersion,
        @Min(0) @Max(99) int bucketNo,
        @Size(max = 10) Map<@NotBlank @Size(max = 64) String, @Min(0) @Max(1000000) Integer> config) {

    /**
     * 兼容旧调用方：不带初始配置。
     */
    public RegisterDeviceRequest(String requestId, String deviceId, String model,
                                 String currentVersion, int bucketNo) {
        this(requestId, deviceId, model, currentVersion, bucketNo, null);
    }

    /**
     * 初始配置，缺省为空配置。
     */
    public Map<String, Integer> effectiveConfig() {
        return config == null ? Map.of() : config;
    }
}
