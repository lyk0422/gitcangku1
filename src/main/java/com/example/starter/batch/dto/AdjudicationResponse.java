package com.example.starter.batch.dto;

import com.example.starter.batch.ExcursionDecision;

import java.time.Instant;

/**
 * MAJOR 偏差裁决快照响应；裁决写入后不可变。
 *
 * @param reworkBatchKey 裁决 REWORK 时返回的返工子批业务键；REJECT 时为 null
 * @param snapshotJson   裁决落定时刻的不可变快照 JSON（偏差区间、温度、批次版本等）
 */
public record AdjudicationResponse(
        String batchKey,
        String excursionKey,
        ExcursionDecision decision,
        String adjudicator,
        String reworkBatchKey,
        String snapshotJson,
        Instant createdAt
) {
}
