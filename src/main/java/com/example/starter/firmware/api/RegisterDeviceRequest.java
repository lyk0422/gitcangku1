package com.example.starter.firmware.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 设备登记请求。deviceGroup 可选，缺省为 default；设备组登记后不可修改。
 */
public record RegisterDeviceRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String deviceId,
        @NotBlank @Size(max = 64) String model,
        @NotBlank @Size(max = 64) String currentVersion,
        @Min(0) @Max(99) int bucketNo,
        @Size(max = 64) String deviceGroup) {

    public static final String DEFAULT_DEVICE_GROUP = "default";

    /**
     * 兼容旧调用方：不携带设备组时使用默认组。
     */
    public RegisterDeviceRequest(String requestId, String deviceId, String model,
                                 String currentVersion, int bucketNo) {
        this(requestId, deviceId, model, currentVersion, bucketNo, null);
    }

    public String effectiveDeviceGroup() {
        return deviceGroup == null || deviceGroup.isBlank() ? DEFAULT_DEVICE_GROUP : deviceGroup;
    }
}
