package com.example.starter.playout.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

    /** 租约状态：ACTIVE 生效中 / COMPLETED 全部分段已确认 / EXPIRED 已过期（惰性标记）。 */
    public enum LeaseStatus {
        ACTIVE, COMPLETED, EXPIRED
    }

    /**
     * 边缘端拉取版本租约请求。同客户端+频道+业务日最多一个 ACTIVE 租约；
     * leaseTtlMs 为空时使用默认租约时长。
     */
    public record PullLeaseRequest(
            @NotBlank String requestId,
            @NotBlank String clientKey,
            @NotBlank String channelId,
            @NotBlank String businessDay,
            @Positive Long leaseTtlMs) {
    }

    /** 续租请求：仅 ACTIVE 且未过期可续，推进 leaseEpoch、保持发布版本。 */
    public record RenewLeaseRequest(
            @NotBlank String requestId,
            @NotNull @Positive Long leaseEpoch) {
    }

    /** 租约分段快照视图；grantRevoked 为拉取时刻的授权撤销状态，不回写。 */
    public record LeaseSegmentView(
            String segmentId,
            String assetId,
            long grantId,
            boolean grantRevoked,
            OffsetDateTime start,
            OffsetDateTime end,
            boolean acked,
            String ackKey,
            OffsetDateTime playedAt) {
    }

    /** 租约插播快照视图；grantRevoked 为拉取时刻的授权撤销状态，不回写。 */
    public record LeaseOverrideView(
            String overrideKey,
            String assetId,
            long grantId,
            boolean grantRevoked,
            int priority,
            OffsetDateTime start,
            OffsetDateTime end) {
    }

    /** 租约响应：绑定发布版本、完整分段快照、授权判定与插播快照。 */
    public record LeaseResponse(
            long leaseId,
            String clientKey,
            String channelId,
            String businessDay,
            long publicationId,
            long publishedVersion,
            long leaseEpoch,
            LeaseStatus status,
            OffsetDateTime expiresAt,
            OffsetDateTime createdAt,
            List<LeaseSegmentView> segments,
            List<LeaseOverrideView> overrides) {
    }

    /**
     * 分段确认请求。leaseEpoch 须等于租约当前纪元；segmentId 须为下一个未确认分段；
     * playedAt 须落在该分段时窗 [start, end) 内；ackKey 为幂等键，失败不占键。
     */
    public record AckSegmentRequest(
            @NotBlank String ackKey,
            @NotNull @Positive Long leaseEpoch,
            @NotBlank String segmentId,
            @NotNull OffsetDateTime playedAt) {
    }

    /** 分段确认响应；leaseStatus 为本次确认后的租约状态。 */
    public record AckSegmentResponse(
            long leaseId,
            String ackKey,
            String segmentId,
            long leaseEpoch,
            OffsetDateTime playedAt,
            int ackedCount,
            int totalCount,
            LeaseStatus leaseStatus) {
    }

    /** 确认记录视图（只读查询）。 */
    public record AckView(
            String ackKey,
            String segmentId,
            long leaseEpoch,
            OffsetDateTime playedAt,
            OffsetDateTime createdAt) {
    }

    /** 发布版本引用查询响应；cleanable 为 true 表示不存在引用该版本的未过期 ACTIVE 租约。 */
    public record PublicationReferencesResponse(
            long publicationId,
            String channelId,
            String businessDay,
            long publishedVersion,
            long activeLeaseCount,
            boolean cleanable) {
    }
}
