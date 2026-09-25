package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.Device;

/**
 * 设备视图。
 */
public record DeviceView(String deviceId, String model, String currentVersion, int bucketNo, String region) {

    public static DeviceView of(Device device) {
        return new DeviceView(device.deviceId(), device.model(), device.currentVersion(), device.bucketNo(),
                device.region());
    }
}
