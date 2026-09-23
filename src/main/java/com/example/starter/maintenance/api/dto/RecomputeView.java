package com.example.starter.maintenance.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 工时表跨表重算记录视图（只读审计）。
 *
 * @param recomputeNo          设备内重算序号
 * @param triggeredMeterKey    触发重算的已关闭表 meterKey
 * @param triggeredReadingId   触发重算的最后有效读数 readingId
 * @param requestId            触发重算的修订请求 requestId
 * @param fromRawHours         修订前最后有效读数原始工时（小时）
 * @param toRawHours           修订后最后有效读数原始工时（小时）
 * @param createdAt            重算完成时刻（UTC）
 */
public record RecomputeView(
        int recomputeNo,
        String triggeredMeterKey,
        String triggeredReadingId,
        String requestId,
        BigDecimal fromRawHours,
        BigDecimal toRawHours,
        Instant createdAt) {
}
