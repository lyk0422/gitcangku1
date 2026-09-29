package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.RehearsalDevice;

/**
 * 演练单台设备结果视图。result/recordedAtUtc 在结果提交前为 null（null 语义稳定）。
 */
public record DeviceResultView(String deviceId, int ordinal, String result, String recordedAtUtc) {

    public static DeviceResultView of(RehearsalDevice device) {
        return new DeviceResultView(device.deviceId(), device.ordinal(),
                device.result() == null ? null : device.result().name(), device.recordedAtUtc());
    }
}
