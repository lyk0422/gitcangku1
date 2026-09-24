package com.example.starter.firmware.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 设备登记请求。维护窗口三项（utcOffsetMinutes/windowStartMinute/windowEndMinute）可选，
 * 须同时提供或同时缺省；缺省表示未配置维护窗口，不受窗口限制。旧客户端不传仍可登记。
 */
public record RegisterDeviceRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String deviceId,
        @NotBlank @Size(max = 64) String model,
        @NotBlank @Size(max = 64) String currentVersion,
        @Min(0) @Max(99) int bucketNo,
        @Min(-720) @Max(840) Integer utcOffsetMinutes,
        @Min(0) @Max(1439) Integer windowStartMinute,
        @Min(0) @Max(1439) Integer windowEndMinute) {

    /**
     * 兼容旧调用方：不配置维护窗口。
     */
    public RegisterDeviceRequest(String requestId, String deviceId, String model,
                                 String currentVersion, int bucketNo) {
        this(requestId, deviceId, model, currentVersion, bucketNo, null, null, null);
    }

    /**
     * 窗口三项是否均未提供。
     */
    public boolean windowAbsent() {
        return utcOffsetMinutes == null && windowStartMinute == null && windowEndMinute == null;
    }

    /**
     * 窗口三项是否均已提供。
     */
    public boolean windowComplete() {
        return utcOffsetMinutes != null && windowStartMinute != null && windowEndMinute != null;
    }
}
