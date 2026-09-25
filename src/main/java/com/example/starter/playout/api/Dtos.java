package com.example.starter.playout.api;

import com.example.starter.playout.Rating;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 播出编排 API 的请求与响应类型。时间字段统一为 Asia/Shanghai、毫秒精度 ISO 8601。
 */
public final class Dtos {

    private Dtos() {
    }

    /** 创建素材请求；id 为空时由系统生成稳定 ID；rating 为空表示不声明分级（校验按 MATURE 处理）。 */
    public record CreateAssetRequest(
            String id,
            @NotNull @Positive Long durationMs,
            Rating rating) {
    }

    /** 素材响应；rating 为登记时声明的分级，未声明时为 null。 */
    public record AssetResponse(String id, long durationMs, Rating rating) {
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

    /**
     * 播出决定响应；source 为 FALLBACK 时 reason 非空。
     * rating 为返回素材的有效分级（未声明按 MATURE）；controlWindow 为 at 时刻命中的管控时段，未命中为 null。
     */
    public record PlayoutDecisionResponse(
            String channelId,
            OffsetDateTime at,
            String assetId,
            DecisionSource source,
            FallbackReason reason,
            Long publicationId,
            String segmentId,
            Rating rating,
            ControlWindowInfo controlWindow) {
    }

    /** 统一错误响应体；violations 仅在分级越级（422 RATING_EXCEEDED）时携带。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorResponse(String error, String message,
                                List<RatingViolationInfo> violations) {
    }

    /** 创建管控时段请求；start/end 须落在同一运营日 businessDay 内，区间左闭右开。 */
    public record CreateRatingWindowRequest(
            @NotBlank String requestId,
            @NotNull LocalDate businessDay,
            @NotNull OffsetDateTime start,
            @NotNull OffsetDateTime end,
            @NotNull Rating maxRating) {
    }

    /** 修改管控时段请求；运营日不可变，仅调整起止时刻与最高分级。 */
    public record UpdateRatingWindowRequest(
            @NotBlank String requestId,
            @NotNull OffsetDateTime start,
            @NotNull OffsetDateTime end,
            @NotNull Rating maxRating) {
    }

    /** 管控时段响应。 */
    public record RatingWindowResponse(
            long id,
            String channelId,
            String businessDay,
            OffsetDateTime start,
            OffsetDateTime end,
            Rating maxRating) {
    }

    /** 命中时段摘要，用于播出决定与越级明细；未命中时段时为 null。 */
    public record ControlWindowInfo(
            long id,
            OffsetDateTime start,
            OffsetDateTime end,
            Rating maxRating) {
    }

    /** 单条分级越级明细：素材分级超过命中管控时段允许的最高分级。 */
    public record RatingViolationInfo(
            String segmentId,
            String assetId,
            Rating rating,
            Long windowId,
            OffsetDateTime windowStart,
            OffsetDateTime windowEnd,
            Rating windowMaxRating) {
    }

    /** 紧急插播请求；at 为插播时刻，须通过该时刻所属管控时段的分级校验。 */
    public record BreakinRequest(
            @NotBlank String requestId,
            @NotBlank String assetId,
            @NotNull OffsetDateTime at) {
    }

    /** 紧急插播响应；rating 为有效分级（未声明按 MATURE），controlWindowId 未命中时段时为 null。 */
    public record BreakinResponse(
            long id,
            String channelId,
            String assetId,
            OffsetDateTime at,
            Rating rating,
            Long controlWindowId) {
    }

    /** 发布分级校验记录；publicationId 为 null 表示该校验未通过、发布被拦截。 */
    public record RatingCheckResponse(
            long id,
            Long publicationId,
            String channelId,
            String businessDay,
            String segmentId,
            String assetId,
            Rating rating,
            Long windowId,
            Rating windowMaxRating,
            String verdict,
            OffsetDateTime checkedAt) {
    }
}
