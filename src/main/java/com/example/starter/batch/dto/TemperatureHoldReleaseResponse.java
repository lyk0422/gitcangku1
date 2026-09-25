package com.example.starter.batch.dto;

import java.time.Instant;
import java.util.List;

/**
 * 温控冻结解除响应。解除只移除运输门禁：batchStatus 为解除后的底层批次状态，
 * 异常段保持 EXCURSION，读数与处置历史不改写。
 */
public record TemperatureHoldReleaseResponse(
        String batchKey,
        String batchStatus,
        String actorId,
        String investigationNote,
        List<String> disposedSegmentKeys,
        Instant releasedAt
) {
}
