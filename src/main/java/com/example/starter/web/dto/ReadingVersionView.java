package com.example.starter.web.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 读数版本视图；基线与实际并列展示，reason 非空表示该校正版本的更正缘由。
 */
public record ReadingVersionView(
        int version,
        BigDecimal baselineKwh,
        BigDecimal actualKwh,
        Instant periodStart,
        Instant periodEnd,
        String reason,
        Instant createdAt
) {
}
