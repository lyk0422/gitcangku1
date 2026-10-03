package com.example.starter.aging;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 温度记录。status 取值 VALID（有效）/ VOID（已作废）；作废仅追加，原记录保留，
 * voidReason/voidedAt/voidRequestId 在未作废时为 null。
 */
public record TemperatureRecord(
        long id,
        long batchId,
        LocalDate recordedDate,
        BigDecimal celsius,
        Instant recordedAt,
        String status,
        String voidReason,
        Instant voidedAt,
        String voidRequestId,
        String requestId) {

    public static final String STATUS_VALID = "VALID";
    public static final String STATUS_VOID = "VOID";
}