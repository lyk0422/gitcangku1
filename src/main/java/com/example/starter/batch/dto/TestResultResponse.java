package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;
import com.example.starter.batch.TestOutcome;

import java.time.Instant;

/**
 * 检验结果响应，携带提交后的批次状态。
 * sampleKey/consumeAmount/remainingQuantity 仅绑定破坏性消耗的检验有值，否则为 null；
 * remainingQuantity 为该次消耗落账后的批次剩余量。
 */
public record TestResultResponse(
        String batchKey,
        String testKey,
        String testItem,
        TestOutcome result,
        String inspector,
        BatchStatus batchStatus,
        Instant createdAt,
        String sampleKey,
        Integer consumeAmount,
        Integer remainingQuantity
) {
}
