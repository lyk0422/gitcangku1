package com.example.starter.firmware.api;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 区域限流历史：每次返回 THROTTLED 追加一条，按发生顺序排列。
 */
public record ThrottleHistoryResponse(List<ThrottleEventView> throttles) {

    /**
     * 一次限流事件。
     *
     * @param deviceId    设备ID
     * @param throttledAt 限流发生时刻（服务器本地时区）
     */
    public record ThrottleEventView(String deviceId, LocalDateTime throttledAt) {
    }
}
