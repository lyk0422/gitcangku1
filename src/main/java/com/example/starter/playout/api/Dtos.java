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

    // ---------- 紧急字幕：文本版本 / 审核 / 字幕 / 黑屏 ----------

    /** 字幕文本审核状态。 */
    public enum CaptionReviewStatus {
        PENDING, APPROVED, REJECTED
    }

    /** 紧急字幕状态：创建即 ACTIVE，撤销后为 REVOKED（终态）。 */
    public enum CaptionStatus {
        ACTIVE, REVOKED
    }

    /**
     * 创建字幕文本版本请求（提交即冻结），携带 crawlKey 幂等。
     */
    public record CreateCaptionTextRequest(
            @NotBlank String crawlKey,
            @NotBlank String versionId,
            @NotBlank String content) {
    }

    /** 字幕文本版本响应。 */
    public record CaptionTextResponse(
            String versionId,
            String content,
            CaptionReviewStatus reviewStatus,
            OffsetDateTime reviewedAt,
            OffsetDateTime createdAt) {
    }

    /** 审核字幕文本版本请求；decision 为 APPROVED / REJECTED，携带 crawlKey 幂等。 */
    public record ReviewCaptionTextRequest(
            @NotBlank String crawlKey,
            @NotNull CaptionReviewStatus decision) {
    }

    /**
     * 创建紧急字幕请求。窗口 [start, end) 为 UTC 左闭右开；priority 为任意整数，越大越高；
     * regions 至少一个区域，服务端排序去重规范化。
     */
    public record CreateEmergencyCaptionRequest(
            @NotBlank String crawlKey,
            @NotBlank String captionKey,
            @NotBlank String channelId,
            @NotNull Integer priority,
            @NotBlank String textVersionId,
            @NotNull OffsetDateTime start,
            @NotNull OffsetDateTime end,
            @NotEmpty List<@NotBlank String> regions) {
    }

    /** 撤销紧急字幕请求（终态，携带 crawlKey 幂等）。 */
    public record RevokeCaptionRequest(@NotBlank String crawlKey) {
    }

    /** 紧急字幕明细响应；regions 为规范化（字典序升序去重）区域集合。 */
    public record EmergencyCaptionResponse(
            long id,
            String captionKey,
            String channelId,
            int priority,
            String textVersionId,
            OffsetDateTime start,
            OffsetDateTime end,
            List<String> regions,
            CaptionStatus status,
            String revokeCrawlKey,
            OffsetDateTime revokedAt,
            OffsetDateTime createdAt) {
    }

    /** 创建黑屏窗口请求，窗口 [start, end) 为 UTC 左闭右开，携带 crawlKey 幂等。 */
    public record CreateBlackoutRequest(
            @NotBlank String crawlKey,
            @NotBlank String blackoutKey,
            @NotBlank String channelId,
            @NotBlank String region,
            @NotNull OffsetDateTime start,
            @NotNull OffsetDateTime end) {
    }

    /** 黑屏窗口响应。 */
    public record BlackoutResponse(
            long id,
            String blackoutKey,
            String channelId,
            String region,
            OffsetDateTime start,
            OffsetDateTime end,
            OffsetDateTime createdAt) {
    }

    /** 阻断原因条目：发布 422 时稳定列出区域、窗口与原因。 */
    public record BlockingReasonResponse(
            String region,
            OffsetDateTime windowStart,
            OffsetDateTime windowEnd,
            String reason,
            String detail) {
    }

    /**
     * 字幕感知发布请求。regions 为需要解析并固化字幕决策的区域集合（非空，服务端规范化）；
     * 其余版本字段语义与 {@link PublishRequest} 一致，携带 crawlKey 幂等。
     */
    public record CaptionPublishRequest(
            @NotBlank String crawlKey,
            @NotNull @Positive Long draftVersion,
            @NotNull @PositiveOrZero Long expectedPublishedVersion,
            @NotEmpty List<@NotBlank String> regions) {
    }

    /** 发布响应扩展：成功时返回本次固化的字幕决策；失败时由 422 错误体稳定列出阻断项。 */
    public record CaptionPublishResponse(
            long publicationId,
            String channelId,
            String businessDay,
            long publishedVersion,
            long draftVersion,
            List<CaptionSnapshotResponse> captions) {
    }

    /** 发布快照中固化的单条字幕决策（只读）。 */
    public record CaptionSnapshotResponse(
            String region,
            String segmentId,
            OffsetDateTime start,
            OffsetDateTime end,
            String captionKey,
            int priority,
            String textVersionId,
            String text,
            String reason) {
    }

    /** 查询某区域某时刻字幕决策的响应。 */
    public record CaptionDecisionResponse(
            String channelId,
            String region,
            OffsetDateTime at,
            String captionKey,
            Integer priority,
            String textVersionId,
            String text,
            String reason) {
    }

    /** 播放回执确认请求，按已发布快照确认，携带 crawlKey 幂等。 */
    public record ConfirmPlayoutRequest(
            @NotBlank String crawlKey,
            @NotBlank String channelId,
            @NotBlank String region,
            @NotNull OffsetDateTime at) {
    }

    /** 播放回执响应；captionKey 为空表示该时刻无字幕覆盖（含恰为字幕结束端点）。 */
    public record PlayoutReceiptResponse(
            long receiptId,
            String crawlKey,
            String channelId,
            long publicationId,
            long publishedVersion,
            String region,
            OffsetDateTime at,
            String assetId,
            String segmentId,
            String captionKey,
            Integer priority,
            String textVersionId,
            String captionText,
            OffsetDateTime captionStart,
            OffsetDateTime captionEnd,
            String reason) {
    }

    /** 统一错误响应体；blocking 仅在发布被字幕/黑屏阻断（422）时出现，顺序稳定。 */
    public record ErrorResponse(String error, String message, List<BlockingReasonResponse> blocking) {
        public ErrorResponse(String error, String message) {
            this(error, message, List.of());
        }
    }
}
