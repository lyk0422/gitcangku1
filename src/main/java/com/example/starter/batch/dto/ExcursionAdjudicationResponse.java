package com.example.starter.batch.dto;

import com.example.starter.batch.ExcursionDisposition;

import java.time.Instant;

/**
 * 偏差裁决不可变快照：MAJOR 裁决结论 REWORK/REJECT，MINOR 质控确认 CONFIRM；
 * 快照在裁决提交时写入，之后不因批次状态变化而改写或删除。
 * reworkBatchKey 仅 REWORK 非空，为沿返工链产生的新返工批业务键。
 */
public record ExcursionAdjudicationResponse(
        ExcursionDisposition disposition,
        String actorId,
        String reason,
        String reworkBatchKey,
        Instant adjudicatedAt
) {
}
