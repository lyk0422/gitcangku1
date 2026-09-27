package com.example.starter.firmware.api;

import java.util.List;

/**
 * 设备信任集合视图：设备当前信任的全部证书链ID。
 */
public record DeviceTrustView(String deviceId, List<Long> chainIds) {
}
