package com.example.starter.api.dto;

import java.util.List;

/**
 * 响应 DTO。时间字段为 Asia/Shanghai 时区、毫秒精度 ISO 8601 文本。
 */
public final class Responses {

    private Responses() {
    }

    public record AssetView(String id, long durationMs) {
    }

    public record ChannelView(String id, String fallbackAssetId) {
    }

    public record GrantView(String id, String channelId, String assetId,
                            String validFrom, String validTo, boolean revoked) {
    }

    public record SegmentView(String segmentId, String assetId, String start, String end) {
    }

    public record DraftView(String channelId, String businessDay, long version,
                            List<SegmentView> segments) {
    }

    public record PublishedView(String channelId, String businessDay, long version,
                                long draftVersion, List<SegmentView> segments) {
    }

    /**
     * 播出决定。decision 为 PROGRAM 或 FALLBACK；
     * FALLBACK 时 reason 为 NO_PUBLISHED_SCHEDULE / GAP / GRANT_REVOKED。
     */
    public record DecisionView(String decision, String assetId, String reason,
                               String segmentId, String start, String end) {
    }

    /** 统一错误响应。 */
    public record ErrorView(String code, String message) {
    }
}
