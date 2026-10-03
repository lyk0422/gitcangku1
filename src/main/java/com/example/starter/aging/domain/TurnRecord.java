package com.example.starter.aging.domain;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 每日翻面记录。turnDate 为 UTC 自然日；requestId 为幂等键；
 * 同一批次同一自然日最多确认一次；同 requestId 同参数重放返回原记录，异参数冲突；
 * note 备注可为空；createdAt 为记录创建 UTC 时刻。
 */
public record TurnRecord(long id, long batchId, LocalDate turnDate, String requestId,
                         String operatorName, String note, Instant createdAt) {
}