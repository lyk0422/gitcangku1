package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.Device;

/**
 * 设备维护窗口视图（只读）。窗口三项为 null 表示未配置维护窗口。
 */
public record DeviceWindowView(String deviceId, int version, Integer utcOffsetMinutes,
                               Integer windowStartMinute, Integer windowEndMinute) {

    public static DeviceWindowView of(Device device) {
        return new DeviceWindowView(device.deviceId(), device.version(), device.utcOffsetMinutes(),
                device.windowStartMinute(), device.windowEndMinute());
    }
}
