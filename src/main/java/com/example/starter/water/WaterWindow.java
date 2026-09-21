package com.example.starter.water;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 供水窗口实体。创建后不可修改；时间字段为 UTC 时刻，水量单位立方米（最多 3 位小数）。
 */
public record WaterWindow(
        long id,
        String windowKey,
        String channelId,
        Instant startUtc,
        Instant endUtc,
        BigDecimal plannedVolume,
        Instant createdAt) {
}
