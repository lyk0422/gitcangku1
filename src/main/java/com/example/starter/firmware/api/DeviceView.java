package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.Device;

import java.util.List;

/**
 * 设备视图。labels 为规范化排序后的标签集合；无标签为空数组（不为 null）。
 */
public record DeviceView(String deviceId, String model, String currentVersion, int bucketNo,
                         List<String> labels) {

    public static DeviceView of(Device device) {
        return new DeviceView(device.deviceId(), device.model(), device.currentVersion(), device.bucketNo(),
                List.copyOf(device.labels()));
    }
}
