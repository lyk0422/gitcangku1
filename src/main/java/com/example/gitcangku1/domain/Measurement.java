package com.example.gitcangku1.domain;

import java.time.Instant;

/**
 * 布卷测色记录，原测与复检均保留。
 * measuredAt 为 UTC 测量时刻；targetVersion 为录入时目标值版本；
 * requestId/requestHash 用于幂等重放与异参冲突判定。
 */
public record Measurement(
        long id,
        long batchId,
        int rollNo,
        MeasureType type,
        double l,
        double a,
        double b,
        Instant measuredAt,
        int targetVersion,
        String requestId,
        String requestHash) {
}