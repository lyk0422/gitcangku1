package com.example.starter.firmware.api;

import java.util.Map;

/**
 * 设备配置视图：完整配置项（键升序）与当前配置版本。
 */
public record DeviceConfigView(String deviceId, int configVersion, Map<String, Integer> entries) {
}
