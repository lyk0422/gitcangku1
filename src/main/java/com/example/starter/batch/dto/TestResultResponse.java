package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;
import com.example.starter.batch.TestOutcome;

import java.time.Instant;

/**
 * 检验结果响应，携带提交后的批次状态。
 * sampleKey/consumeQuantity/remainingAfter 仅当本次检验绑定了样本消耗凭证时有值，
 * 否则为 null；remainingAfter 为该次消耗落定后的批次剩余量。
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
        Integer consumeQuantity,
        Integer remainingAfter
) {
}
