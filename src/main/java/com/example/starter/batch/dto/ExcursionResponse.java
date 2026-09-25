package com.example.starter.batch.dto;

import com.example.starter.batch.ExcursionSeverity;
import com.example.starter.batch.ExcursionStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 储运偏差条目响应：区间 UTC 左闭右开，温度单位摄氏度。
 *
 * @param registeredBatchVersion 登记时批次版本号（用于 excursionKey 指纹与并发裁决）
 * @param confirmedBy 质控确认人（仅 CONFIRMED 的 MINOR 偏差非空，其余为 null）
 * @param confirmedAt 质控确认时间 ISO-8601 UTC（未确认为 null）
 */
public record ExcursionResponse(
        String batchKey,
        String excursionKey,
        Instant startUtc,
        Instant endUtc,
        BigDecimal minTempC,
        BigDecimal maxTempC,
        ExcursionSeverity severity,
        ExcursionStatus status,
        long registeredBatchVersion,
        String confirmedBy,
        Instant confirmedAt,
        Instant createdAt
) {
}
