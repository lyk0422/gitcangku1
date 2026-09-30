package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 相邻读数区间条目，按起点时刻升序；gapMinutes 为相邻采集时刻间隔（分钟，保留小数）。
 * missing=true 表示间隔严格大于 30 分钟的缺测区间，missingDeviationKey 为对应缺测偏差键。
 */
public record IntervalEntry(
        String fromRequestId,
        String toRequestId,
        Instant start,
        Instant end,
        long gapSeconds,
        boolean missing,
        String missingDeviationKey
) {
}
