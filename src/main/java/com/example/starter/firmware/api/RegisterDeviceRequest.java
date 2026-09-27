package com.example.starter.firmware.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 设备登记请求。deviceGroup 为可选设备组名，登记后不可修改；
 * 不传或为 null 表示不属于任何组，不适用任何模块豁免。
 */
public record RegisterDeviceRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String deviceId,
        @NotBlank @Size(max = 64) String model,
        @NotBlank @Size(max = 64) String currentVersion,
        @Min(0) @Max(99) int bucketNo,
        @Size(max = 64) String deviceGroup) {

    /**
     * 兼容旧调用方：不指定设备组。
     */
    public RegisterDeviceRequest(String requestId, String deviceId, String model, String currentVersion,
                                 int bucketNo) {
        this(requestId, deviceId, model, currentVersion, bucketNo, null);
    }
}
