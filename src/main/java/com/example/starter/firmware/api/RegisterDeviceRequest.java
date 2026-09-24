package com.example.starter.firmware.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 设备登记请求。UTC 偏移与维护窗口可选：旧客户端不传时偏移为 0、窗口为全天（0,0）。
 * 一旦显式传入窗口起止，必须成对出现且 0~1439、起止不同（起大于止表示跨零点）；
 * 仅传偏移而不传窗口时按全天窗口登记。
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
     * 兼容旧调用方：不登记偏移与窗口（偏移0，全天窗口 0,0）。
     */
    public RegisterDeviceRequest(String requestId, String deviceId, String model,
                                 String currentVersion, int bucketNo) {
        this(requestId, deviceId, model, currentVersion, bucketNo, null, null, null);
    }

    public int effectiveUtcOffsetMinutes() {
        return utcOffsetMinutes == null ? 0 : utcOffsetMinutes;
    }

    public int effectiveWindowStartMinute() {
        return windowStartMinute == null ? 0 : windowStartMinute;
    }

    public int effectiveWindowEndMinute() {
        return windowEndMinute == null ? 0 : windowEndMinute;
    }

    /**
     * 是否显式给出了窗口起止（二者必须同时提供）。
     */
    public boolean hasWindowBounds() {
        return windowStartMinute != null || windowEndMinute != null;
    }
}
