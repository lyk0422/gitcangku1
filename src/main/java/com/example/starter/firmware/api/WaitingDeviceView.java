package com.example.starter.firmware.api;

import java.time.LocalDateTime;

/**
 * 等待中的设备视图：设备ID与最近一次被限流时刻（服务器本地时区）。
 */
public record WaitingDeviceView(String deviceId, LocalDateTime waitedAt) {
}
