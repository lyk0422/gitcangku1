package com.example.starter.observation;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * 设备观测提交响应：返回本次提交生成的版本及提交后的当前胜出版本。
 *
 * @param observationId   观测标识
 * @param version         本次提交生成的版本号
 * @param deviceId        提交设备标识
 * @param deviceLocalTime 设备本地时刻原始值（不可改写）
 * @param correctedAtUtc  矫正后时刻（UTC，ISO-8601）
 * @param mergeSeq        本次提交版本的全局合并顺序
 * @param currentVersion  提交后该观测的当前胜出版本号
 */
public record SubmitObservationResponse(
        String observationId,
        int version,
        String deviceId,
        LocalDateTime deviceLocalTime,
        Instant correctedAtUtc,
        long mergeSeq,
        int currentVersion) {
}
