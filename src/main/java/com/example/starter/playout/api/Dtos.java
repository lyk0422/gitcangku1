package com.example.starter.playout.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

    /** 创建素材请求；id 为空时由系统生成稳定 ID；sponsorKey 为空表示无赞助素材。 */
    public record CreateAssetRequest(
            String id,
            String sponsorKey,
            @NotNull @Positive Long durationMs) {
    }

    /** 素材响应；sponsorKey 为 null 表示无赞助素材，不参与排他冲突。 */
    public record AssetResponse(String id, String sponsorKey, long durationMs) {
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

    /**
     * 统一错误响应体。details 仅在排他冲突等需要结构化明细时出现，为 null 时不序列化，
     * 保持既有错误响应字段与 null 语义不变。
     */
    public record ErrorResponse(String error, String message,
                                @JsonInclude(JsonInclude.Include.NON_NULL)
                                List<SponsorConflictDetail> details) {
        public ErrorResponse(String error, String message) {
            this(error, message, null);
        }
    }

    /** 赞助排他冲突明细：频道、时段（左闭右开）、冲突素材与命中窗口。 */
    public record SponsorConflictDetail(
            String channelId,
            OffsetDateTime start,
            OffsetDateTime end,
            String assetId,
            String assetSponsorKey,
            String windowKey,
            String windowSponsorKey) {
    }

    /** 排他窗状态：ACTIVE 生效；SUPERSEDED 已被修订取代；DELETED 已删除。仅 ACTIVE 参与校验。 */
    public enum WindowStatus {
        ACTIVE, SUPERSEDED, DELETED
    }

    /**
     * 登记赞助排他窗请求。windowKey 为空时由系统生成；channels 为频道范围，不得为空；
     * 区间 [start, end) 为 UTC 左闭右开（API 边界为 Asia/Shanghai 毫秒精度），不得跨 businessDay。
     */
    public record CreateSponsorWindowRequest(
            @NotBlank String requestId,
            String windowKey,
            @NotBlank String sponsorKey,
            @NotNull LocalDate businessDay,
            @NotNull List<@NotBlank String> channels,
            @NotNull OffsetDateTime start,
            @NotNull OffsetDateTime end) {
    }

    /** 修订排他窗请求：对同一 windowKey 生成新版本，旧版本转为 SUPERSEDED。 */
    public record ReviseSponsorWindowRequest(
            @NotBlank String requestId,
            @NotBlank String sponsorKey,
            @NotNull LocalDate businessDay,
            @NotNull List<@NotBlank String> channels,
            @NotNull OffsetDateTime start,
            @NotNull OffsetDateTime end) {
    }

    /** 删除排他窗请求（幂等）。 */
    public record DeleteSponsorWindowRequest(@NotBlank String requestId) {
    }

    /** 排他窗响应；channels 按字典序排序，时间为 Asia/Shanghai 毫秒精度。 */
    public record SponsorWindowResponse(
            String windowKey,
            long version,
            String sponsorKey,
            String businessDay,
            List<String> channels,
            OffsetDateTime start,
            OffsetDateTime end,
            WindowStatus status,
            OffsetDateTime createdAt) {
    }

    /** 跨频道批量发布请求：先验证完整后态，再在一个事务内提交，任一频道冲突则整次失败。 */
    public record BatchPublishRequest(
            @NotBlank String requestId,
            @NotNull List<@NotNull BatchPublishItem> items) {
    }

    /** 批量发布单项：一个频道一个业务日的草稿发布。 */
    public record BatchPublishItem(
            @NotBlank String channelId,
            @NotNull LocalDate businessDay,
            @NotNull @Positive Long draftVersion,
            @NotNull @PositiveOrZero Long expectedPublishedVersion) {
    }

    /** 批量发布响应；publications 顺序与请求 items 一致。 */
    public record BatchPublishResponse(List<PublishResponse> publications) {
    }

    /** 撤销发布请求（幂等）；仅尚未开始的发布可撤销。 */
    public record RevokePublicationRequest(@NotBlank String requestId) {
    }

    /** 发布状态：ACTIVE 生效；REVOKED 已撤销（仅未开始的发布可撤销，撤销为终态）。 */
    public enum PublicationStatus {
        ACTIVE, REVOKED
    }

    /** 发布快照片段视图；sponsorKey 为发布时固化快照，null 表示无赞助素材。 */
    public record PublicationSegmentView(
            String segmentId,
            String assetId,
            String sponsorKey,
            long grantId,
            OffsetDateTime start,
            OffsetDateTime end) {
    }

    /** 发布命中排他窗历史视图；windowVersion 为发布时固化的窗口版本，不随窗口修订或删除改写。 */
    public record WindowHitView(
            String windowKey,
            long windowVersion,
            String sponsorKey,
            String channelId,
            OffsetDateTime start,
            OffsetDateTime end) {
    }

    /**
     * 发布明细响应：快照片段（含固化 sponsorKey）与命中窗口历史。
     * 撤销后保留可查，revokeRequestId/revokedAt 未撤销时为 null。
     */
    public record PublicationDetailResponse(
            long publicationId,
            String channelId,
            String businessDay,
            long publishedVersion,
            long draftVersion,
            PublicationStatus status,
            List<PublicationSegmentView> segments,
            List<WindowHitView> windowHits,
            OffsetDateTime createdAt,
            String revokeRequestId,
            OffsetDateTime revokedAt) {
    }
}
