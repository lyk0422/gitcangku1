package com.example.starter.domain;

import java.time.Instant;

/**
 * 接收任务行：起止区间为 UTC 时刻，[startAt, endAt] 必须完全落在校准有效期内才可绑定。
 */
public record ReceiveTask(
        long id,
        String taskCode,
        long antennaId,
        Instant startAt,
        Instant endAt,
        Instant createdAt) {
}