package com.example.starter.batch.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 仓储段时刻查询视图：原始读数、相邻区间（标注缺测）、全部偏差（含处置人与确认记录）
 * 与当前门禁状态。历史偏差携带触发时限值快照，不随后续限值修改而回写。
 */
public record StorageTimelineResponse(
        String batchKey,
        String segmentKey,
        BigDecimal upperLimit,
        BigDecimal lowerLimit,
        List<ReadingEntry> readings,
        List<IntervalEntry> intervals,
        List<DeviationResponse> deviations,
        String gateStatus,
        int openDeviationCount
) {
}
