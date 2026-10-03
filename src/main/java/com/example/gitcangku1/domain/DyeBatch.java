package com.example.gitcangku1.domain;

import java.time.Instant;

/**
 * 染色批次。
 * targetVersion 修改目标值时递增；expectedRolls 为计划卷数(1-30)；
 * createdAt 为 UTC 时刻。
 */
public record DyeBatch(
        long id,
        String batchNo,
        double targetL,
        double targetA,
        double targetB,
        int targetVersion,
        int expectedRolls,
        BatchStatus status,
        Instant createdAt) {
}