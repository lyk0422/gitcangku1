package com.example.starter.firmware.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 设备登记请求。operator 为可选操作者（缺省 system），参与幂等指纹。
 */
public record RegisterDeviceRequest(
        @NotBlank @Size(max = 64) String requestId,
        @Size(max = 64) String operator,
        @NotBlank @Size(max = 64) String deviceId,
        @NotBlank @Size(max = 64) String model,
        @NotBlank @Size(max = 64) String currentVersion,
        @Min(0) @Max(99) int bucketNo) {

    public RegisterDeviceRequest(String requestId, String deviceId, String model,
                                 String currentVersion, int bucketNo) {
        this(requestId, null, deviceId, model, currentVersion, bucketNo);
    }

    public String effectiveOperator() {
        return operator == null || operator.isBlank() ? CreateReleaseRequest.DEFAULT_OPERATOR : operator.trim();
    }
}
