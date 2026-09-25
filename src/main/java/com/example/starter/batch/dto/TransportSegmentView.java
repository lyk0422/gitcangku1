package com.example.starter.batch.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 运输段查询视图：段定义、状态与全部已上传读数（按采集时刻升序）。
 */
public record TransportSegmentView(
        String segmentKey,
        Instant startAt,
        Instant endAt,
        BigDecimal minTemp,
        BigDecimal maxTemp,
        String recordedBy,
        String status,
        Instant createdAt,
        List<ReadingView> readings
) {
}
