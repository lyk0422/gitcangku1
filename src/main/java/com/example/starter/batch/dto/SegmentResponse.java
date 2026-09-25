package com.example.starter.batch.dto;

import com.example.starter.batch.SegmentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 运输段响应：含段区间、温控限、录入人、异常状态，以及全部读数与（异常段的）处置闭包。
 * 读数与异常历史不可改写，解除冻结后 EXCURSION 状态与 disposition 仍原样保留。
 */
public record SegmentResponse(
        String segmentKey,
        String batchKey,
        Instant startAt,
        Instant endAt,
        BigDecimal minTemp,
        BigDecimal maxTemp,
        String recorderId,
        SegmentStatus status,
        List<ReadingResponse> readings,
        DispositionResponse disposition,
        Instant createdAt
) {
}
