package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 批次读数。提交时封存证书版本与批次修订版本；被 REJECT 裁决排除后不参与统计。
 *
 * @param id                 读数 ID（自增）
 * @param readingKey         业务读数键，全局唯一（幂等键）
 * @param batchId            所属批次 ID
 * @param measuredAt         测量时刻（UTC）
 * @param rawReading         原始读数，最多 6 位小数
 * @param submittedBy        提交人
 * @param certificateId      提交时匹配到的校准证书 ID
 * @param certificateVersion 提交时证书版本快照
 * @param batchRevision      提交时批次修订版本快照
 * @param excluded           是否被 REJECT 裁决排除
 * @param createdAt          提交时间（UTC）
 */
public record BatchReading(
        long id,
        String readingKey,
        long batchId,
        Instant measuredAt,
        BigDecimal rawReading,
        String submittedBy,
        long certificateId,
        int certificateVersion,
        int batchRevision,
        boolean excluded,
        Instant createdAt) {
}