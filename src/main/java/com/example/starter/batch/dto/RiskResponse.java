package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * 过敏原风险查询响应。riskActive 为 true 时批次处于 ALLERGEN_RISK；
 * releaseSnapshot 为进入风险时保留的原放行快照（状态与全部批准记录），解除后仍保留。
 */
public record RiskResponse(
        String batchKey,
        BatchStatus status,
        boolean riskActive,
        Instant enteredAt,
        Instant clearedAt,
        JsonNode releaseSnapshot
) {
}
