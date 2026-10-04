package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.Device;

/**
 * 设备视图。
 */
public record DeviceView(String deviceId, String model, String currentVersion, String currentDigest,
                         int imageGeneration, int bucketNo) {

    public static DeviceView of(Device device) {
        return new DeviceView(device.deviceId(), device.model(), device.currentVersion(),
                device.currentDigest(), device.imageGeneration(), device.bucketNo());
    }
}