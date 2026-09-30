package com.example.starter.web.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 泊位时间轴：泊位静态水深、当前生效潮高版本，以及该泊位上全部申请的占用区间。
 */
public record BerthTimelineResponse(
        String berthCode,
        String berthName,
        BigDecimal staticDepthM,
        Integer activeTideVersionNo,
        List<TimelineItem> allocations) {

    /**
     * 时间轴上的单条申请区间及其校核/拒绝信息。
     */
    public record TimelineItem(
            String requestId,
            String vesselName,
            @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime,
            String status,
            Long tideVersionId,
            BigDecimal minTideHeightM,
            BigDecimal availableDepthM,
            BigDecimal clearanceM,
            String rejectReason,
            Integer reviewCount,
            Long reviewTideVersionId,
            BigDecimal reviewMinTideHeightM,
            BigDecimal reviewClearanceM) {
    }
}
