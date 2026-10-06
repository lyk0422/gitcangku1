package com.example.starter.firmware.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 设备登记请求。activePartition 可选，缺省为 A。
 */
public record RegisterDeviceRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String deviceId,
        @NotBlank @Size(max = 64) String model,
        @NotBlank @Size(max = 64) String currentVersion,
        @Min(0) @Max(99) int bucketNo,
        @Pattern(regexp = "A|B") String activePartition) {

    /**
     * 兼容旧调用方：不登记分区时默认当前分区为 A。
     */
    public RegisterDeviceRequest(String requestId, String deviceId, String model, String currentVersion,
                                 int bucketNo) {
        this(requestId, deviceId, model, currentVersion, bucketNo, null);
    }

    public String effectiveActivePartition() {
        return activePartition == null ? "A" : activePartition;
    }
}
