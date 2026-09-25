package com.example.starter.playout.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 播出编排 API 的请求与响应类型。时间字段统一为 Asia/Shanghai、毫秒精度 ISO 8601。
 */
public final class Dtos {

    private Dtos() {
    }

    /** 创建素材请求；id 为空时由系统生成稳定 ID。 */
    public record CreateAssetRequest(
            String id,
            @NotNull @Positive Long durationMs) {
    }

    /** 素材响应。 */
    public record AssetResponse(String id, long durationMs) {
    }

    /** 创建频道请求；fallbackAssetId 为不会被撤销的保底素材。 */
    public record CreateChannelRequest(
            String id,
            @NotBlank String fallbackAssetId) {
    }

    /** 频道响应。 */
    public record ChannelResponse(String id, String fallbackAssetId) {
    }

    /** 创建授权请求，有效区间 [validFrom, validTo) 左闭右开。 */
    public record CreateGrantRequest(
            @NotBlank String channelId,
            @NotBlank String assetId,
            @NotNull OffsetDateTime validFrom,
            @NotNull OffsetDateTime validTo) {
    }

    /** 授权响应。 */
    public record GrantResponse(
            long id,
            String channelId,
            String assetId,
            OffsetDateTime validFrom,
            OffsetDateTime validTo,
            boolean revoked) {
    }

    /** 撤销授权请求。 */
    public record RevokeGrantRequest(@NotBlank String requestId) {
    }

    /** 草稿片段输入；id 为空时由系统生成。 */
    public record SegmentInput(
            String id,
            @NotBlank String assetId,
            @NotNull OffsetDateTime start,
            @NotNull OffsetDateTime end) {
    }

    /** 整份替换草稿请求。 */
    public record ReplaceDraftRequest(
            @NotBlank String requestId,
            @NotNull @PositiveOrZero Long expectedDraftVersion,
            @NotNull List<@NotNull SegmentInput> segments) {
    }

    /** 草稿片段响应。 */
    public record SegmentResponse(
            String id,
            String assetId,
            OffsetDateTime start,
            OffsetDateTime end) {
    }

    /** 草稿响应。 */
    public record DraftResponse(
            String channelId,
            String businessDay,
            long version,
            List<SegmentResponse> segments) {
    }

    /** 发布请求。 */
    public record PublishRequest(
            @NotBlank String requestId,
            @NotNull @Positive Long draftVersion,
            @NotNull @PositiveOrZero Long expectedPublishedVersion) {
    }

    /** 发布响应。 */
    public record PublishResponse(
            long publicationId,
            String channelId,
            String businessDay,
            long publishedVersion,
            long draftVersion) {
    }

    /** 播出决定来源：EMERGENCY 命中紧急插播，PROGRAM 命中节目片段，FALLBACK 返回保底素材。 */
    public enum DecisionSource {
        EMERGENCY, PROGRAM, FALLBACK
    }

    /** 保底原因：无已发布编排 / 处于空档 / 覆盖片段的授权已撤销。 */
    public enum FallbackReason {
        NO_PUBLISHED_SCHEDULE, GAP, GRANT_REVOKED
    }

    /** 播出决定响应；source 为 FALLBACK 时 reason 非空；source 为 EMERGENCY 时 overrideKey 非空。 */
    public record PlayoutDecisionResponse(
            String channelId,
            OffsetDateTime at,
            String assetId,
            DecisionSource source,
            FallbackReason reason,
            Long publicationId,
            String segmentId,
            String overrideKey) {
    }

    /** 紧急插播状态：创建即 ACTIVE，取消后为 CANCELLED，均为终态语义（ACTIVE 只能转为 CANCELLED）。 */
    public enum OverrideStatus {
        ACTIVE, CANCELLED
    }

    /**
     * 创建限时紧急插播请求。区间 [start, end) 左闭右开、同处一个 Asia/Shanghai 业务日、
     * 时长大于 0 且不超过 30 分钟；priority 取值 1～9；grantId 必须属于该频道与素材并完整覆盖区间。
     */
    public record CreateEmergencyOverrideRequest(
            @NotBlank String requestId,
            @NotBlank String overrideKey,
            @NotBlank String channelId,
            @NotBlank String assetId,
            @NotNull @Positive Long grantId,
            @NotNull @Min(1) @Max(9) Integer priority,
            @NotNull OffsetDateTime start,
            @NotNull OffsetDateTime end) {
    }

    /** 取消紧急插播请求（幂等）。 */
    public record CancelEmergencyOverrideRequest(@NotBlank String requestId) {
    }

    /** 紧急插播明细响应，保存取消情况与原授权关联，不随授权撤销自动换绑。 */
    public record EmergencyOverrideResponse(
            String overrideKey,
            String channelId,
            String assetId,
            long grantId,
            int priority,
            OffsetDateTime start,
            OffsetDateTime end,
            OverrideStatus status,
            String cancelRequestId,
            OffsetDateTime cancelledAt,
            OffsetDateTime createdAt) {
    }

    /** 统一错误响应体。 */
    public record ErrorResponse(String error, String message) {
    }

    /** 发布门禁 422 错误响应体：除错误码与消息外稳定列出全部阻断区域与窗口。 */
    public record PublishBlockErrorResponse(
            String error,
            String message,
            List<PublishBlockDetail> items) {
    }

    // ========== 黑屏窗口 ==========

    /** 创建黑屏窗口请求；区域集合提交后去重排序规范化，窗口 [start,end) 左闭右开。 */
    public record CreateBlackoutRequest(
            @NotBlank String requestId,
            @NotBlank String channelId,
            @NotNull OffsetDateTime start,
            @NotNull OffsetDateTime end,
            @NotEmpty List<@NotBlank String> regions) {
    }

    /** 黑屏窗口响应。 */
    public record BlackoutResponse(
            long id,
            String channelId,
            OffsetDateTime start,
            OffsetDateTime end,
            List<String> regions,
            OffsetDateTime createdAt) {
    }

    // ========== 字幕文本与审核 ==========

    /** 创建字幕文本版本请求；版本号由调用方提供（同 text_key 下唯一），内容创建即冻结。 */
    public record CreateSubtitleTextRequest(
            @NotBlank String requestId,
            @NotBlank String textKey,
            @NotNull @Positive Integer version,
            @NotBlank String content) {
    }

    /** 字幕文本版本响应。 */
    public record SubtitleTextResponse(
            String textKey,
            int version,
            String content,
            SubtitleTextStatus status,
            OffsetDateTime createdAt,
            OffsetDateTime approvedAt) {
    }

    /** 字幕文本审核状态。 */
    public enum SubtitleTextStatus {
        PENDING, APPROVED
    }

    /** 审核字幕文本版本请求（幂等）；仅 PENDING 可审核。 */
    public record ApproveSubtitleTextRequest(@NotBlank String requestId) {
    }

    // ========== 紧急字幕 ==========

    /**
     * 创建紧急字幕请求：整数优先级（越大越高）、文本版本引用、UTC 左闭右开窗口、
     * 非空区域集合（规范化：去空白、去重、字典序排序）。
     */
    public record CreateEmergencySubtitleRequest(
            @NotBlank String requestId,
            @NotBlank String subtitleKey,
            @NotBlank String channelId,
            @NotNull Integer priority,
            @NotBlank String textKey,
            @NotNull @Positive Integer textVersion,
            @NotNull OffsetDateTime start,
            @NotNull OffsetDateTime end,
            @NotEmpty List<@NotBlank String> regions) {
    }

    /** 紧急字幕响应；regions 为规范化后的区域列表。 */
    public record EmergencySubtitleResponse(
            String subtitleKey,
            String channelId,
            int priority,
            String textKey,
            int textVersion,
            OffsetDateTime start,
            OffsetDateTime end,
            List<String> regions,
            SubtitleStatus status,
            String revokeRequestId,
            OffsetDateTime revokedAt,
            OffsetDateTime createdAt) {
    }

    /** 紧急字幕状态：创建即 ACTIVE，撤销后为 REVOKED，均为终态语义。 */
    public enum SubtitleStatus {
        ACTIVE, REVOKED
    }

    /** 撤销紧急字幕请求（幂等）。 */
    public record RevokeSubtitleRequest(@NotBlank String requestId) {
    }

    // ========== 发布快照字幕明细 ==========

    /** 发布快照中某区域某时间片的字幕决定。 */
    public record PublicationSubtitleResponse(
            String region,
            OffsetDateTime start,
            OffsetDateTime end,
            String segmentId,
            String subtitleKey,
            String textKey,
            int textVersion,
            String textContent,
            int priority,
            String reason) {
    }

    /** 发布快照扩展响应：节目素材与每区域固化的字幕子片。 */
    public record PublicationSnapshotResponse(
            long publicationId,
            String channelId,
            String businessDay,
            long publishedVersion,
            long draftVersion,
            List<SnapshotSegmentResponse> segments,
            List<PublicationSubtitleResponse> subtitles) {
    }

    /** 快照节目片段。 */
    public record SnapshotSegmentResponse(
            String segmentId,
            String assetId,
            long grantId,
            OffsetDateTime start,
            OffsetDateTime end) {
    }

    // ========== 区域字幕决策 ==========

    /** 查询某频道某区域某时刻的字幕决策；命中时各字段非空，未命中 subtitleKey 为空。 */
    public record RegionSubtitleDecisionResponse(
            String channelId,
            String region,
            OffsetDateTime at,
            String subtitleKey,
            String textKey,
            Integer textVersion,
            String textContent,
            Integer priority,
            OffsetDateTime overlayStart,
            OffsetDateTime overlayEnd) {
    }

    // ========== 播放回执 ==========

    /** 播放回执请求；crawlKey 客户端生成，同键重放，失败不占键。 */
    public record PlaybackReceiptRequest(
            @NotBlank String crawlKey,
            @NotBlank String channelId,
            @NotBlank String region,
            @NotNull OffsetDateTime at) {
    }

    /** 播放回执响应，内容严格依据发布快照。 */
    public record PlaybackReceiptResponse(
            String crawlKey,
            String channelId,
            String businessDay,
            long publicationId,
            long publishedVersion,
            String region,
            OffsetDateTime at,
            String assetId,
            String segmentId,
            String subtitleKey,
            Integer textVersion,
            Integer priority,
            OffsetDateTime overlayStart,
            OffsetDateTime overlayEnd,
            String fingerprint) {
    }

    // ========== 发布阻断 ==========

    /** 发布阻断明细：稳定排序的区域与窗口。 */
    public record PublishBlockDetail(
            String region,
            OffsetDateTime start,
            OffsetDateTime end,
            String subtitleKey,
            Integer textVersion,
            String reason) {
    }

    /** 发布阻断原因响应。 */
    public record PublishBlockResponse(
            String channelId,
            String businessDay,
            String code,
            List<PublishBlockDetail> items) {
    }
}
