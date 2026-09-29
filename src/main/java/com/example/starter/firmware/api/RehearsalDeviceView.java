package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.RehearsalDevice;

/**
 * 演练单台设备结果视图（历史证据快照，后续设备状态变化不改变本视图）。
 * RUNNING 阶段 result 与 failureReason 为 null。
 */
public record RehearsalDeviceView(int ordinal, String deviceId, String result, String failureReason) {

    public static RehearsalDeviceView of(RehearsalDevice device) {
        ReceiptResult result = device.result();
        return new RehearsalDeviceView(device.ordinal(), device.deviceId(),
                result == null ? null : result.name(), device.failureReason());
    }
}
