package com.example.starter.batch.dto;

import com.example.starter.batch.TestOutcome;

import java.time.Instant;

/**
 * 复检提交响应。rootKey/recallVersion 标识复检所属召回上下文；复检不改写批次状态。
 */
public record ReinspectionResponse(
        String batchKey,
        String rootKey,
        int recallVersion,
        String testItem,
        TestOutcome outcome,
        String inspector,
        Instant createdAt
) {
}
