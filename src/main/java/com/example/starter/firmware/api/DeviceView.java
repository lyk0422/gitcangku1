package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.Device;

/**
 * 设备视图。status 为设备状态：ACTIVE 正常；QUARANTINED 异常隔离中。
 */
public record DeviceView(String deviceId, String model, String currentVersion, int bucketNo, String status) {

    public static DeviceView of(Device device) {
        return new DeviceView(device.deviceId(), device.model(), device.currentVersion(), device.bucketNo(),
                device.status().name());
    }
}
