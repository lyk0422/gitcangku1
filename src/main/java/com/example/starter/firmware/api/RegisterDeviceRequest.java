package com.example.starter.firmware.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 设备登记请求。hardwareModel 可选，缺省等于设备型号；登记后不可修改。
 */
public record RegisterDeviceRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String deviceId,
        @NotBlank @Size(max = 64) String model,
        @Size(max = 64) String hardwareModel,
        @NotBlank @Size(max = 64) String currentVersion,
        @Min(0) @Max(99) int bucketNo) {

    /**
     * 兼容旧调用方：不传 hardwareModel 时使用设备型号。
     */
    public RegisterDeviceRequest(String requestId, String deviceId, String model,
                                 String currentVersion, int bucketNo) {
        this(requestId, deviceId, model, null, currentVersion, bucketNo);
    }

    /**
     * 有效硬件型号：未显式提供（或空白）时等于设备型号。
     */
    public String effectiveHardwareModel() {
        return hardwareModel == null || hardwareModel.isBlank() ? model : hardwareModel;
    }
}
