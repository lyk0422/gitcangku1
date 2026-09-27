package com.example.starter.playout.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

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

    /** 播出决定来源：EMERGENCY 命中紧急插播，LIVE 命中直播覆盖，PROGRAM 命中节目片段，FALLBACK 返回保底素材。 */
    public enum DecisionSource {
        EMERGENCY, LIVE, PROGRAM, FALLBACK
    }

    /** 保底原因：无已发布编排 / 处于空档 / 覆盖片段的授权已撤销 / 片段被直播覆盖错过且不补播。 */
    public enum FallbackReason {
        NO_PUBLISHED_SCHEDULE, GAP, GRANT_REVOKED, MISSED_BY_LIVE
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

    /** 直播覆盖令状态：创建即 ACTIVE；REVOKED（未开始时撤销）与 SETTLED（结束后结算）为终态，不可改写。 */
    public enum LiveOverrideStatus {
        ACTIVE, REVOKED, SETTLED
    }

    /** 受影响素材恢复结果：PENDING 待结算 / RESTORED 撤销恢复 / MISSED_BY_LIVE 已错过不补播；终态不可改写。 */
    public enum LiveSegmentDisposition {
        PENDING, RESTORED, MISSED_BY_LIVE
    }

    /** 直播覆盖令的频道维度输入；grantId 为该频道下完整覆盖区间的直播源授权。 */
    public record LiveOverrideChannelInput(
            @NotBlank String channelId,
            @NotNull @Positive Long grantId) {
    }

    /**
     * 创建直播覆盖令请求。区间 [start, end) 左闭右开、时长大于 0；channels 至少一个频道，
     * 跨频道创建全量校验，任一失败整体回滚不留部分覆盖。operator 计入幂等指纹。
     */
    public record CreateLiveOverrideRequest(
            @NotBlank String requestId,
            @NotBlank String operator,
            @NotBlank String overrideKey,
            @NotBlank String liveAssetId,
            @NotNull OffsetDateTime start,
            @NotNull OffsetDateTime end,
            @NotNull @Size(min = 1) List<@NotNull @Valid LiveOverrideChannelInput> channels) {
    }

    /** 撤销直播覆盖令请求（仅尚未开始的覆盖可撤销，幂等）。 */
    public record RevokeLiveOverrideRequest(
            @NotBlank String requestId,
            @NotBlank String operator) {
    }

    /** 结算直播覆盖令请求（仅覆盖结束后可结算，幂等）。 */
    public record SettleLiveOverrideRequest(
            @NotBlank String requestId,
            @NotBlank String operator) {
    }

    /** 直播覆盖令频道维度响应，含创建时的授权版本快照。 */
    public record LiveOverrideChannelResponse(
            String channelId,
            long grantId,
            OffsetDateTime grantValidFrom,
            OffsetDateTime grantValidTo) {
    }

    /** 受影响素材记录响应，含恢复结果。 */
    public record LiveOverrideSegmentResponse(
            long id,
            String channelId,
            long publicationId,
            String segmentId,
            String assetId,
            OffsetDateTime start,
            OffsetDateTime end,
            LiveSegmentDisposition disposition) {
    }

    /** 直播覆盖令明细响应，含频道维度、受影响素材与撤销/结算证据。 */
    public record LiveOverrideResponse(
            String overrideKey,
            String operator,
            String liveAssetId,
            OffsetDateTime start,
            OffsetDateTime end,
            LiveOverrideStatus status,
            List<LiveOverrideChannelResponse> channels,
            List<LiveOverrideSegmentResponse> affectedSegments,
            String revokeRequestId,
            OffsetDateTime revokedAt,
            String settleRequestId,
            OffsetDateTime settledAt,
            OffsetDateTime createdAt) {
    }

    /** 直播覆盖令历史摘要，按开始时间、覆盖键稳定排序。 */
    public record LiveOverrideSummary(
            String overrideKey,
            String liveAssetId,
            LiveOverrideStatus status,
            OffsetDateTime start,
            OffsetDateTime end,
            List<String> channelIds,
            OffsetDateTime createdAt) {
    }

    /** 直播覆盖令单频道诊断：受影响素材数量与覆盖内节目时长（毫秒，实际值）。 */
    public record LiveOverrideChannelDiagnostics(
            String channelId,
            int affectedSegmentCount,
            long coveredProgramDurationMs) {
    }

    /** 直播覆盖令诊断响应：数量与时长均为实际值，读取不改变状态。 */
    public record LiveOverrideDiagnostics(
            String overrideKey,
            LiveOverrideStatus status,
            int channelCount,
            long overrideDurationMs,
            int affectedSegmentCount,
            int pendingCount,
            int restoredCount,
            int missedCount,
            long coveredProgramDurationMs,
            List<LiveOverrideChannelDiagnostics> channels) {
    }

    /** 统一错误响应体。 */
    public record ErrorResponse(String error, String message) {
    }
}
