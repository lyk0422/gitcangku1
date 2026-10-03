package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 裁决记录。不可变；两名及以上复核人结论一致时生成。
 *
 * @param id        裁决记录 ID（自增）
 * @param outlierId 离群记录 ID
 * @param decision  裁决结论：ACCEPT / REJECT
 * @param decidedAt 裁决时间（UTC）
 */
public record AdjudicationRecord(
        long id,
        long outlierId,
        ReviewDecision decision,
        Instant decidedAt) {
}