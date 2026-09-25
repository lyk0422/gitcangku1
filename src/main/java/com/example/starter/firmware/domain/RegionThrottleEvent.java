package com.example.starter.firmware.domain;

import java.time.LocalDateTime;

/**
 * 区域限流历史事件，每次返回 THROTTLED 追加一条。
 *
 * @param id          限流事件ID
 * @param releaseId   所属发布单ID
 * @param region      区域标识
 * @param deviceId    被限流的设备ID
 * @param throttledAt 限流发生时刻（服务器本地时区）
 */
public record RegionThrottleEvent(long id, long releaseId, String region, String deviceId,
                                  LocalDateTime throttledAt) {
}
