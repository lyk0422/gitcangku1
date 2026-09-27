package com.example.starter.playout.api;

import jakarta.validation.Valid;
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

    /** 播出决定来源：LIVE 命中直播覆盖，EMERGENCY 命中紧急插播，PROGRAM 命中节目片段，FALLBACK 返回保底素材。 */
    public enum DecisionSource {
        LIVE, EMERGENCY, PROGRAM, FALLBACK
    }

    /** 保底原因：无已发布编排 / 处于空档 / 覆盖片段的授权已撤销 / 原素材已被直播错过且不补播。 */
    public enum FallbackReason {
        NO_PUBLISHED_SCHEDULE, GAP, GRANT_REVOKED, LIVE_PREEMPTED
    }

    /**
     * 播出决定响应；source 为 FALLBACK 时 reason 非空；source 为 EMERGENCY 时 overrideKey 非空；
     * source 为 LIVE 时 liveOverrideKey 非空。
     */
    public record PlayoutDecisionResponse(
            String channelId,
            OffsetDateTime at,
            String assetId,
            DecisionSource source,
            FallbackReason reason,
            Long publicationId,
            String segmentId,
            String overrideKey,
            String liveOverrideKey) {
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

    /** 直播覆盖令状态：SCHEDULED 未开始 / ACTIVE 覆盖中 / ENDED 已结束并裁决 / REVOKED 开始前撤销。 */
    public enum LiveOverrideStatus {
        SCHEDULED, ACTIVE, ENDED, REVOKED
    }

    /**
     * 创建直播覆盖令条目（批量中的一项，支持跨频道）。区间 [start, end) 为 UTC 左闭右开；
     * 直播源 sourceId 必须是频道已授权素材，grantId 必须属于该频道与直播源、未撤销且完整覆盖区间。
     */
    public record CreateLiveOverrideItem(
            @NotBlank String overrideKey,
            @NotBlank String channelId,
            @NotBlank String sourceId,
            @NotNull @Positive Long grantId,
            @NotNull OffsetDateTime start,
            @NotNull OffsetDateTime end) {
    }

    /**
     * 批量创建直播覆盖令请求。operator 为操作者标识，对全部条目生效并参与幂等指纹；
     * 任一条目校验失败则整体拒绝，不留部分覆盖。
     */
    public record CreateLiveOverridesRequest(
            @NotBlank String requestId,
            @NotBlank String operator,
            @NotNull List<@NotNull @Valid CreateLiveOverrideItem> items) {
    }

    /** 撤销直播覆盖令请求；仅 SCHEDULED（尚未开始覆盖）可撤销。 */
    public record RevokeLiveOverrideRequest(
            @NotBlank String requestId,
            @NotBlank String operator) {
    }

    /** 受影响素材明细；result 为 SUPPRESSED / RESUMED / MISSED_BY_LIVE，decidedAt 未裁决时为 null。 */
    public record LiveAffectedItem(
            long publicationId,
            String businessDay,
            String segmentId,
            String assetId,
            OffsetDateTime scheduledStart,
            OffsetDateTime scheduledEnd,
            String result,
            OffsetDateTime decidedAt) {
    }

    /** 覆盖结束后的不可变恢复裁决明细。 */
    public record LiveRecoveryItem(
            long publicationId,
            String businessDay,
            String segmentId,
            String result,
            OffsetDateTime scheduledStart,
            OffsetDateTime scheduledEnd,
            OffsetDateTime recoveredAt) {
    }

    /** 创建覆盖令时的授权版本快照（不可变证据）。 */
    public record LiveGrantSnapshot(
            long grantId,
            String channelId,
            String assetId,
            OffsetDateTime validFrom,
            OffsetDateTime validTo,
            boolean revoked,
            OffsetDateTime capturedAt) {
    }

    /** 直播覆盖令明细响应，含受影响素材、授权快照与恢复结果；历史字段创建后不再改写。 */
    public record LiveOverrideResponse(
            long id,
            String overrideKey,
            String channelId,
            String sourceId,
            long grantId,
            OffsetDateTime start,
            OffsetDateTime end,
            LiveOverrideStatus status,
            String operator,
            OffsetDateTime startedAt,
            OffsetDateTime endedAt,
            OffsetDateTime createdAt,
            String revokeRequestId,
            String revokeOperator,
            OffsetDateTime revokedAt,
            long overrideDurationMs,
            int affectedCount,
            long suppressedDurationMs,
            int resumedCount,
            int missedCount,
            List<LiveAffectedItem> affectedItems,
            List<LiveRecoveryItem> recoveryItems,
            LiveGrantSnapshot grantSnapshot) {
    }

    /** 直播覆盖令历史列表项（不含明细集合），排序稳定：开始时间升序、ID 升序。 */
    public record LiveOverrideSummary(
            long id,
            String overrideKey,
            String channelId,
            String sourceId,
            long grantId,
            OffsetDateTime start,
            OffsetDateTime end,
            LiveOverrideStatus status,
            String operator,
            OffsetDateTime startedAt,
            OffsetDateTime endedAt,
            OffsetDateTime createdAt,
            String revokeRequestId,
            OffsetDateTime revokedAt,
            int affectedCount,
            int resumedCount,
            int missedCount) {
    }

    /**
     * 批量创建直播覆盖令响应：返回实际创建数量、涉及频道、实际覆盖总时长、受影响素材总数与
     * 实际抑制总毫秒数，以及每条覆盖令的完整明细（成功重放返回首次响应快照）。
     */
    public record LiveOverrideBatchResponse(
            String requestId,
            int createdCount,
            List<String> channelIds,
            long totalOverrideDurationMs,
            int affectedCount,
            long suppressedDurationMs,
            int resumedCount,
            int missedCount,
            List<LiveOverrideResponse> items) {
    }

    /** 某频道某时刻的直播覆盖诊断；数量与时长均为实际值，单位毫秒。 */
    public record LiveDiagnosticsResponse(
            String channelId,
            OffsetDateTime at,
            boolean liveOnAir,
            String activeOverrideKey,
            String sourceId,
            long activeOverrideCount,
            long suppressedSegmentCount,
            long missedSegmentCount,
            long resumedSegmentCount,
            long liveOnAirMs,
            List<LiveOverrideSummary> overrides) {
    }
}
