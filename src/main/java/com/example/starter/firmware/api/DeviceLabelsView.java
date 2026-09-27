package com.example.starter.firmware.api;

import java.util.List;

/**
 * 单台设备标签修改后的最终状态。
 */
public record DeviceLabelsView(String deviceId, List<String> labels) {
}
