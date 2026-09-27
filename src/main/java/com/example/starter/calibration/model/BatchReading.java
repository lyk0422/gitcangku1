package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 批次读数。提交时固化所属测量修订版本；REJECT 裁决后在同一事务内排除。
 *
 * @param id            读数 ID（自增）
 * @param batchId       所属批次 ID
 * @param readingKey    业务读数键，批次内唯一（幂等键）
 * @param readingValue  读数值
 * @param submittedBy   读数提交人
 * @param batchRevision 提交时的批次测量修订版本
 * @param excluded      是否已被 REJECT 裁决排除
 * @param excludedAt    排除时间（UTC）；未排除为 null
 * @param createdAt     提交时间（UTC）
 */
public record BatchReading(
        long id,
        long batchId,
        String readingKey,
        BigDecimal readingValue,
        String submittedBy,
        int batchRevision,
        boolean excluded,
        Instant excludedAt,
        Instant createdAt) {
}
