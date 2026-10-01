package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 批次读数（表 batch_reading）。提交时绑定批次当前测量修订版本与测量时刻匹配的有效证书；
 * 被 REJECT 裁决后仅标记排除，不物理删除，历史证据保留。
 *
 * @param id                  读数 ID（自增）
 * @param batchId             所属批次 ID
 * @param readingKey          业务读数键，全局唯一（幂等键）
 * @param measuredAt          测量时刻（UTC）
 * @param readingValue        原始读数，最多 6 位小数
 * @param computedValue       未舍入计算值 a×读数+b，最多 12 位小数
 * @param submittedBy         提交人
 * @param certificateId       提交时匹配到的校准证书 ID（证书版本）
 * @param measurementRevision 提交时批次的测量修订版本
 * @param excluded            是否已被 REJECT 裁决排除；true 表示不计入批次统计
 * @param excludedAt          排除时间（UTC）；未排除时为 null
 * @param createdAt           提交时间（UTC）
 */
public record BatchReading(
        long id,
        long batchId,
        String readingKey,
        Instant measuredAt,
        BigDecimal readingValue,
        BigDecimal computedValue,
        String submittedBy,
        long certificateId,
        int measurementRevision,
        boolean excluded,
        Instant excludedAt,
        Instant createdAt) {
}