package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.Device;

/**
 * 设备视图。deviceGroup 为设备组名，不属于任何组时为 null（null 语义稳定）。
 */
public record DeviceView(String deviceId, String model, String currentVersion, int bucketNo,
                         String deviceGroup) {

    public static DeviceView of(Device device) {
        return new DeviceView(device.deviceId(), device.model(), device.currentVersion(), device.bucketNo(),
                device.deviceGroup());
    }
}
