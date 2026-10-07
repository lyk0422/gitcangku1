package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.Device;

import java.util.Map;

/**
 * 设备视图。config 为当前完整合成配置（按键排序），configVersion 从 1 开始递增。
 */
public record DeviceView(String deviceId, String model, String currentVersion, int bucketNo,
                         int configVersion, Map<String, Integer> config) {

    public static DeviceView of(Device device, Map<String, Integer> config) {
        return new DeviceView(device.deviceId(), device.model(), device.currentVersion(),
                device.bucketNo(), device.configVersion(), config);
    }
}
