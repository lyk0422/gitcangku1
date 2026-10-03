package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 批次读数。提交时按测量时刻匹配唯一有效证书并封存证书版本；
 * 被 REJECT 裁决排除后不再计入批次统计，但记录本身保留。
 *
 * @param id                 读数 ID（自增）
 * @param batchId            所属批次 ID
 * @param readingKey         业务读数键，全局唯一
 * @param batchRevision      提交时所属测量修订版本
 * @param measuredAt         测量时刻（UTC）
 * @param value              读数值，最多 6 位小数
 * @param submittedBy        读数提交人
 * @param certificateId      提交时匹配到的校准证书 ID
 * @param certificateVersion 提交时封存的证书版本
 * @param excluded           是否已被 REJECT 裁决排除
 * @param excludedAt         排除时间（UTC），未排除为 null
 * @param createdAt          提交时间（UTC）
 */
public record BatchReading(
        long id,
        long batchId,
        String readingKey,
        int batchRevision,
        Instant measuredAt,
        BigDecimal value,
        String submittedBy,
        long certificateId,
        int certificateVersion,
        boolean excluded,
        Instant excludedAt,
        Instant createdAt) {
}