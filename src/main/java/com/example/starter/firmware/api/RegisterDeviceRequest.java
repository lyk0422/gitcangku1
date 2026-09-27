package com.example.starter.firmware.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 设备登记请求。labels 可缺省（旧客户端按无标签登记）；提供时由服务端规范化
 * （trim、去重、字典序排序，空标签 422）。operator 为操作者标识，可缺省。
 */
public record RegisterDeviceRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String deviceId,
        @NotBlank @Size(max = 64) String model,
        @NotBlank @Size(max = 64) String currentVersion,
        @Min(0) @Max(99) int bucketNo,
        List<@Size(max = 64) String> labels,
        @Size(max = 64) String operator) {

    public RegisterDeviceRequest(String requestId, String deviceId, String model, String currentVersion,
                                 int bucketNo) {
        this(requestId, deviceId, model, currentVersion, bucketNo, null, null);
    }

    /**
     * 携带标签登记、不指定操作者。
     */
    public RegisterDeviceRequest(String requestId, String deviceId, String model, String currentVersion,
                                 int bucketNo, List<String> labels) {
        this(requestId, deviceId, model, currentVersion, bucketNo, labels, null);
    }
}
