package com.example.starter.observation;

import java.time.Instant;

/**
 * 设备偏移记录响应。
 *
 * @param deviceId         采集设备唯一标识
 * @param effectiveFromUtc 偏移生效起始时刻（UTC，ISO-8601）
 * @param offsetSeconds    偏移秒数
 */
public record OffsetResponse(
        String deviceId,
        Instant effectiveFromUtc,
        int offsetSeconds) {

    /**
     * 由持久化记录构造响应。
     */
    public static OffsetResponse of(DeviceOffset offset) {
        return new OffsetResponse(offset.deviceId(), offset.effectiveFromUtc(), offset.offsetSeconds());
    }
}
