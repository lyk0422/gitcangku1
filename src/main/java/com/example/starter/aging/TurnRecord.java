package com.example.starter.aging;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 每日翻面确认。turnDate 为 UTC 自然日，每批次每日最多一条。
 */
public record TurnRecord(
        long id,
        long batchId,
        LocalDate turnDate,
        Instant turnedAt,
        String operatorName,
        String requestId) {
}