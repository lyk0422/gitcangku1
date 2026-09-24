package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.Device;

/**
 * 设备视图。维护窗口三项为 null 表示未配置维护窗口。
 */
public record DeviceView(String deviceId, String model, String currentVersion, int bucketNo, int version,
                         Integer utcOffsetMinutes, Integer windowStartMinute, Integer windowEndMinute) {

    public static DeviceView of(Device device) {
        return new DeviceView(device.deviceId(), device.model(), device.currentVersion(), device.bucketNo(),
                device.version(), device.utcOffsetMinutes(), device.windowStartMinute(),
                device.windowEndMinute());
    }
}
