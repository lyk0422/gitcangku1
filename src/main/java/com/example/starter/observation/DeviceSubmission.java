package com.example.starter.observation;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * 设备观测提交记录：对应 device_observation 表的一行。
 * 原始本地时刻不可改写；矫正后时刻仅由提交换算或偏移重建在同一事务内重算。
 *
 * @param submissionId   观测提交唯一标识（观测标识）
 * @param observationId  目标观测记录标识
 * @param deviceId       采集设备唯一标识
 * @param deviceLocalAt  设备本地时刻（原始值，无时区）
 * @param correctedAtUtc 矫正后时刻 = 设备本地时刻 + 命中偏移秒数（UTC 时标）
 * @param offsetSeconds  换算时命中的偏移秒数；无命中偏移记录时为 0
 * @param location       提交的观测地点候选值
 * @param reading        提交的观测读数候选值（十进制字符串）
 * @param note           提交的观测备注候选值
 * @param requestId      提交请求标识
 */
public record DeviceSubmission(
        String submissionId,
        String observationId,
        String deviceId,
        LocalDateTime deviceLocalAt,
        Instant correctedAtUtc,
        int offsetSeconds,
        String location,
        String reading,
        String note,
        String requestId) {
}
