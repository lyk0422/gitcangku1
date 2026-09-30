package com.example.starter.web;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 潮高版本详情响应。
 */
public record TideVersionResponse(
        Long versionNo,
        LocalDateTime rangeStart,
        LocalDateTime rangeEnd,
        String status,
        LocalDateTime publishedAt,
        List<SegmentView> segments) {

    /**
     * 潮高区间视图。
     */
    public record SegmentView(
            LocalDateTime startTime,
            LocalDateTime endTime,
            BigDecimal height) {
    }
}
