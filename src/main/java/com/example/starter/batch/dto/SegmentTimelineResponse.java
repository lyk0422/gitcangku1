package com.example.starter.batch.dto;

import java.time.Instant;
import java.util.List;

/**
 * 仓储段时刻查询响应：显示截至 at（含）的原始读数、超限或缺测区间、偏差处置人与批次门禁状态。
 * gate 为 OPEN（无未处置偏差）或 BLOCKED（存在未处置偏差，禁止放行/拆分/合批）。
 */
public record SegmentTimelineResponse(
        String batchKey,
        String segmentKey,
        Instant at,
        String gate,
        List<ReadingEntry> readings,
        List<ExcursionEntry> excursions
) {
}
