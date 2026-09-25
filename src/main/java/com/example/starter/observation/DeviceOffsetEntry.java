package com.example.starter.observation;

import java.time.Instant;

/**
 * 设备时钟偏移记录：对应 device_clock_offset 表的一行。
 * 同一设备的记录按生效起始时刻划分半开区间 [effectiveFromUtc, 下一条生效起始时刻)。
 *
 * @param deviceId         采集设备唯一标识
 * @param effectiveFromUtc 偏移生效起始 UTC 时刻（含）
 * @param offsetSeconds    偏移秒数（-86400 至 86400）：矫正后时刻 = 设备本地时刻 + 偏移秒数
 */
public record DeviceOffsetEntry(
        String deviceId,
        Instant effectiveFromUtc,
        int offsetSeconds) {
}
