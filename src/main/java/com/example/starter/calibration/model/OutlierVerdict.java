package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 离群裁决记录（不可变）。同一离群记录最多一条有效裁决；
 * 两名及以上复核人结论相同时形成。
 *
 * @param id        裁决记录 ID（自增）
 * @param outlierId 离群记录 ID
 * @param decision  裁决结论：ACCEPT 保留 / REJECT 排除
 * @param formedAt  裁决形成时间（UTC）
 */
public record OutlierVerdict(
        long id,
        long outlierId,
        ReviewDecision decision,
        Instant formedAt) {
}
