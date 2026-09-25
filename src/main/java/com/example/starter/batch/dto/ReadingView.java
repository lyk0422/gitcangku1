package com.example.starter.batch.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 温度读数查询视图。inRange 为上传时判定的越界标记，历史不可改写。
 */
public record ReadingView(
        Instant recordedAt,
        BigDecimal temperature,
        boolean inRange,
        Instant createdAt
) {
}
