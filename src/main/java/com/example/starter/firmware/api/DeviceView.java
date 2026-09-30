package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.Device;

import java.util.List;

/**
 * 设备视图。quarantineRounds 为该设备按目标固件版本的全部连续失败/隔离轮次（含历史）：
 * 最新轮次即“本轮”，其中 consecutiveFailures 为本轮连续失败次数，status 为当前隔离状态，
 * 两次失败任务/原因为隔离依据，released* 字段为解除记录。
 */
public record DeviceView(String deviceId, String model, String currentVersion, int bucketNo,
                         List<QuarantineRoundView> quarantineRounds) {

    public static DeviceView of(Device device) {
        return new DeviceView(device.deviceId(), device.model(), device.currentVersion(), device.bucketNo(),
                List.of());
    }

    public static DeviceView of(Device device, List<QuarantineRoundView> quarantineRounds) {
        return new DeviceView(device.deviceId(), device.model(), device.currentVersion(), device.bucketNo(),
                quarantineRounds);
    }
}
