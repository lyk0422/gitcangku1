package com.example.starter.firmware.api;

import java.time.LocalDateTime;

/**
 * 限流历史事件视图：被限流的设备ID与限流发生时刻（服务器本地时区）。
 */
public record ThrottleEventView(String deviceId, LocalDateTime throttledAt) {
}
