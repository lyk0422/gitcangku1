package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.Device;

import java.util.List;

/**
 * 设备视图。failureRounds 为该设备各目标版本的失败轮次（按时间先后）：
 * status=QUARANTINED 的活动轮次即当前隔离状态，内含隔离依据与解除记录；历史轮次全部保留。
 */
public record DeviceView(String deviceId, String model, String currentVersion, int bucketNo,
                         List<FailureRoundView> failureRounds) {

    public static DeviceView of(Device device) {
        return new DeviceView(device.deviceId(), device.model(), device.currentVersion(), device.bucketNo(),
                List.of());
    }

    public static DeviceView of(Device device, List<FailureRoundView> failureRounds) {
        return new DeviceView(device.deviceId(), device.model(), device.currentVersion(), device.bucketNo(),
                failureRounds);
    }
}
