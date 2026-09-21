package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;
import com.example.starter.batch.TestOutcome;

import java.time.Instant;

/**
 * 检验结果响应，携带提交后的批次状态。
 */
public record TestResultResponse(
        String batchKey,
        String testKey,
        String testItem,
        TestOutcome result,
        String inspector,
        BatchStatus batchStatus,
        Instant createdAt
) {
}
