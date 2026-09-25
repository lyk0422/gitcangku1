package com.example.starter.batch.dto;

import java.time.Instant;
import java.util.List;

/**
 * 温控冻结状态查询响应：冻结门禁标记、异常段/未处置段数量、逐段闭包及解除记录。
 * temperatureHold=true 表示批次不得到货放行、拆分、合批或继续移交。
 */
public record TemperatureStatusResponse(
        String batchKey,
        boolean temperatureHold,
        int segmentCount,
        int excursionCount,
        List<String> undisposedExcursionKeys,
        ReleaseHoldResponse release,
        List<SegmentResponse> segments
) {

    /**
     * 冻结解除记录；不存在未解除以外的改写语义，解除后原始异常状态仍保留在各段上。
     */
    public record ReleaseHoldResponse(
            String investigatorId,
            String investigationNote,
            List<String> disposedSegmentKeys,
            Instant createdAt
    ) {
    }
}
