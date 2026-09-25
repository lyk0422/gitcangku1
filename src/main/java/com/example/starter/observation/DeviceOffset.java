package com.example.starter.observation;

import java.time.Instant;

/**
 * 设备时钟偏移记录：对应 device_offset 表的一行。
 * 同一设备的偏移区间为 [effectiveFromUtc, 下一条记录的起始时刻)，起始时刻相同即重叠。
 *
 * @param deviceId        采集设备唯一标识
 * @param effectiveFromUtc 偏移生效起始时刻（UTC，含边界）
 * @param offsetSeconds   偏移秒数（-86400 至 86400）；矫正后时刻 = 设备本地时刻 + 偏移秒数
 */
public record DeviceOffset(
        String deviceId,
        Instant effectiveFromUtc,
        int offsetSeconds) {
}
