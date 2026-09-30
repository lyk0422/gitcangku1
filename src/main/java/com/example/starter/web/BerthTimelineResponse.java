package com.example.starter.web;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 泊位时间轴响应：泊位信息、当前潮高版本区间及该泊位上的申请列表。
 */
public record BerthTimelineResponse(
        String berthCode,
        BigDecimal staticDepth,
        TideVersionView currentTideVersion,
        List<ApplicationResponse> applications) {

    /**
     * 时间轴上的潮高版本摘要（含区间潮高）。
     */
    public record TideVersionView(
            Long versionNo,
            LocalDateTime rangeStart,
            LocalDateTime rangeEnd,
            LocalDateTime publishedAt,
            List<TideVersionResponse.SegmentView> segments) {
    }
}
