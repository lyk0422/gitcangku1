package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.time.Instant;

/**
 * 条件子项核销响应：核销后实时返回剩余未核销子项数与批次状态；
 * 最后一个子项核销的同一事务内批次转为 RELEASED。
 */
public record CloseConditionResponse(
        String batchKey,
        String conditionKey,
        String itemKey,
        String closerId,
        String closerRole,
        String evidence,
        int remainingOpen,
        BatchStatus batchStatus,
        Instant closedAt
) {
}
