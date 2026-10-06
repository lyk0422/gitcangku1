package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.Device;

/**
 * 设备视图。bootCount 为不可递减的非负启动计数；deviceVersion 为档案版本，维护成功一次加一。
 */
public record DeviceView(String deviceId, String model, String currentVersion, int bucketNo,
                         long bootCount, int deviceVersion) {

    public static DeviceView of(Device device) {
        return new DeviceView(device.deviceId(), device.model(), device.currentVersion(),
                device.bucketNo(), device.bootCount(), device.deviceVersion());
    }
}
