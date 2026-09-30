package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.time.Instant;
import java.util.List;

/**
 * 批次仓储门禁查询响应：聚合该批次全部仓储段截至查询时刻的未处置偏差数与各段状态。
 */
public record StorageGateResponse(
        String batchKey,
        String gate,
        int openExcursionCount,
        List<SegmentGate> segments,
        Instant at
) {

    /**
     * 单个仓储段的门禁条目。
     */
    public record SegmentGate(
            String segmentKey,
            int openExcursionCount,
            String gate
    ) {
    }
}
