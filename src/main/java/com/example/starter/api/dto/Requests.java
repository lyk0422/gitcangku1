package com.example.starter.api.dto;

import java.util.List;

/**
 * 写请求 DTO。时间字段为 Asia/Shanghai 时区、毫秒精度 ISO 8601 文本。
 */
public final class Requests {

    private Requests() {
    }

    /** 创建素材请求。durationMs 为正整数毫秒。 */
    public record CreateAsset(String id, Long durationMs) {
    }

    /** 创建频道请求，指定不可撤销的保底素材。 */
    public record CreateChannel(String id, String fallbackAssetId) {
    }

    /** 创建授权请求，有效区间左闭右开。 */
    public record CreateGrant(String assetId, String validFrom, String validTo) {
    }

    /** 整份替换草稿请求。expectedDraftVersion 为 0 表示草稿尚不存在。 */
    public record ReplaceDraft(String requestId, Long expectedDraftVersion,
                               List<SegmentInput> segments) {
    }

    /** 草稿片段输入。start/end 为毫秒精度 ISO 8601 文本。 */
    public record SegmentInput(String segmentId, String assetId, String start, String end) {
    }

    /** 发布请求：携带草稿版本与期望发布版本。 */
    public record Publish(String requestId, Long draftVersion, Long expectedPublishedVersion) {
    }

    /** 撤销授权请求。 */
    public record Revoke(String requestId) {
    }
}
