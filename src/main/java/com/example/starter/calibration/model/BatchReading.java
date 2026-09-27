package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 批次读数。提交时匹配唯一有效证书并固化校准计算值；
 * excluded 仅由 REJECT 裁决在同一事务内置位，置位后不计入批次统计。
 *
 * @param id            读数 ID（自增）
 * @param batchId       所属批次 ID
 * @param batchRevision 提交时的批次修订版本号
 * @param requestId     提交幂等键
 * @param measuredAt    测量时刻（UTC）
 * @param reading       原始读数，最多 6 位小数
 * @param submittedBy   读数提交人
 * @param certificateId 提交时匹配到的校准证书 ID（证书版本）
 * @param computedValue 未舍入校准计算值 a×读数+b
 * @param outlier       是否离群（计算值落在 [目标-偏差, 目标+偏差) 之外）
 * @param excluded      是否已被 REJECT 裁决排除
 * @param createdAt     提交时间（UTC）
 */
public record BatchReading(
        long id,
        long batchId,
        int batchRevision,
        String requestId,
        Instant measuredAt,
        BigDecimal reading,
        String submittedBy,
        long certificateId,
        BigDecimal computedValue,
        boolean outlier,
        boolean excluded,
        Instant createdAt) {
}
