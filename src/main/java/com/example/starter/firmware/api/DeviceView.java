package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.Device;

/**
 * 设备视图。windowStartMinute 与 windowEndMinute 相等（均为0）表示全天窗口。
 */
public record DeviceView(String deviceId, String model, String currentVersion, int bucketNo,
                         int utcOffsetMinutes, int windowStartMinute, int windowEndMinute,
                         int deviceVersion) {

    public static DeviceView of(Device device) {
        return new DeviceView(device.deviceId(), device.model(), device.currentVersion(), device.bucketNo(),
                device.utcOffsetMinutes(), device.windowStartMinute(), device.windowEndMinute(),
                device.deviceVersion());
    }
}
