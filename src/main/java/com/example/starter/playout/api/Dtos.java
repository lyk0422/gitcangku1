package com.example.starter.playout.api;

import jakarta.validation.constraints.NotBlank;
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

    /** 创建素材请求；id 为空时由系统生成稳定 ID；rating 为空表示历史素材未声明分级，校验时按 MATURE 处理。 */
    public record CreateAssetRequest(
            String id,
            @NotNull @Positive Long durationMs,
            ContentRating rating) {
    }

    /** 素材响应；rating 为 NULL 表示未声明分级。 */
    public record AssetResponse(String id, long durationMs, ContentRating rating) {
    }

    /** 内容分级，严格程度 G < PG < MATURE。 */
    public enum ContentRating {
        G, PG, MATURE;

        /** 判断当前分级是否超出允许的最高分级。 */
        public boolean exceeds(ContentRating maxAllowed) {
            return this.ordinal() > maxAllowed.ordinal();
        }
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

    /** 播出决定来源：PROGRAM 命中节目片段，FALLBACK 返回保底素材。 */
    public enum DecisionSource {
        PROGRAM, FALLBACK
    }

    /** 保底原因：无已发布编排 / 处于空档 / 覆盖片段的授权已撤销。 */
    public enum FallbackReason {
        NO_PUBLISHED_SCHEDULE, GAP, GRANT_REVOKED
    }

    /** 播出决定响应；source 为 FALLBACK 时 reason 非空。返回素材时附带其有效分级与命中管控时段（如有）。 */
    public record PlayoutDecisionResponse(
            String channelId,
            OffsetDateTime at,
            String assetId,
            DecisionSource source,
            FallbackReason reason,
            Long publicationId,
            String segmentId,
            ContentRating assetRating,
            Long ratingWindowId,
            Integer windowStartMinute,
            Integer windowEndMinute,
            ContentRating windowMaxRating) {
    }

    /** 创建/修改频道管控时段请求；起止为自运营日 00:00 起的分钟数，左闭右开。 */
    public record UpsertRatingWindowRequest(
            @NotBlank String requestId,
            @NotNull @PositiveOrZero Integer startMinute,
            @NotNull @Positive Integer endMinute,
            @NotNull ContentRating maxRating) {
    }

    /** 管控时段响应。 */
    public record RatingWindowResponse(
            long id,
            String channelId,
            int startMinute,
            int endMinute,
            ContentRating maxRating,
            long version,
            boolean revoked) {
    }

    /** 紧急插播请求；at 为插播时刻，按其所属运营日管控时段判定分级。 */
    public record CreateInterruptionRequest(
            @NotBlank String requestId,
            @NotBlank String assetId,
            @NotNull OffsetDateTime at) {
    }

    /** 紧急插播响应；windowId 为 NULL 表示插播时刻未落入任何管控时段。 */
    public record InterruptionResponse(
            long id,
            String channelId,
            String assetId,
            OffsetDateTime at,
            ContentRating assetRating,
            Long windowId) {
    }

    /** 历史发布分级校验记录。 */
    public record RatingCheckRecordResponse(
            long publicationId,
            long publishedVersion,
            String segmentId,
            String assetId,
            ContentRating assetRating,
            OffsetDateTime start,
            OffsetDateTime end,
            Long windowId,
            Integer windowStartMinute,
            Integer windowEndMinute,
            ContentRating allowedRating) {
    }

    /** 单条越级素材明细：命中的管控时段与允许最高分级。 */
    public record RatingViolation(
            String segmentId,
            String assetId,
            ContentRating assetRating,
            long windowId,
            int windowStartMinute,
            int windowEndMinute,
            ContentRating allowedRating) {
    }

    /** 统一错误响应体；violations 仅在分级越权 422 时携带全部越级明细。 */
    public record ErrorResponse(String error, String message, List<RatingViolation> violations) {
        public ErrorResponse(String error, String message) {
            this(error, message, null);
        }
    }
}
