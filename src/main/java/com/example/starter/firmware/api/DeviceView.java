package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.Device;

import java.util.List;

/**
 * 设备视图。tags 为规范化升序标签集合，无标签时为空数组（不为 null）。
 */
public record DeviceView(String deviceId, String model, String currentVersion, int bucketNo,
                         List<String> tags) {

    public static DeviceView of(Device device, List<String> tags) {
        return new DeviceView(device.deviceId(), device.model(), device.currentVersion(),
                device.bucketNo(), tags);
    }
}
