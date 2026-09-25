package com.example.starter.batch.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 温度读数响应：时刻为 UTC instant，温度为摄氏度两位小数，seq 为段内上传顺序。
 */
public record ReadingResponse(
        Instant readAt,
        BigDecimal temperature,
        int seq
) {
}
