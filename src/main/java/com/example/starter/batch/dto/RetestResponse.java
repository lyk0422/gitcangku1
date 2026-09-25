package com.example.starter.batch.dto;

import com.example.starter.batch.TestOutcome;

import java.time.Instant;

/**
 * 召回复检响应。复检不改变批次状态，仅以该批次最新一条复检判定是否合格。
 */
public record RetestResponse(
        String batchKey,
        String retestKey,
        TestOutcome outcome,
        String inspector,
        Instant createdAt
) {
}
