package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.Device;

/**
 * 设备视图。healthVersion 为健康版本号（人工恢复加一）；down 为停机/不健康标记。
 */
public record DeviceView(String deviceId, String model, String currentVersion, int bucketNo,
                         int healthVersion, boolean down) {

    public static DeviceView of(Device device) {
        return new DeviceView(device.deviceId(), device.model(), device.currentVersion(),
                device.bucketNo(), device.healthVersion(), device.down());
    }
}
