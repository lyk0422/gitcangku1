package com.example.starter.aging.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 测温记录（仅追加，不物理删除）。tempDate 为 UTC 自然日；celsius 单位摄氏度，
 * 放行有效区间为 8 至 12（含边界）；status：VALID 有效、VOID 已作废；
 * voidReason/voidedAt 仅作废时有值，未作废为空；replacedById 指向替代记录，无替代为空。
 */
public record TemperatureRecord(long id, long batchId, LocalDate tempDate, BigDecimal celsius,
                                TempStatus status, String recordedBy, String voidReason,
                                Instant voidedAt, Long replacedById, Instant createdAt) {
}