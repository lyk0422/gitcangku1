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

    /** 素材响应；withdrawn 表示素材已撤回（终态），撤回素材不得用于新插播发布。 */
    public record AssetResponse(String id, long durationMs, boolean withdrawn) {
    }

    /** 撤回素材请求（幂等）。 */
    public record WithdrawAssetRequest(@NotBlank String requestId) {
    }

    /** 创建频道请求；fallbackAssetId 为不会被撤销的保底素材。 */
    public record CreateChannelRequest(
            String id,
            @NotBlank String fallbackAssetId) {
    }

    /** 频道响应。 */
    public record ChannelResponse(String id, String fallbackAssetId) {
    }

    /** 创建授权请求，有效区间 [validFrom, validTo) 左闭右开；regionCode 为空时表示 *（全部区域）。 */
    public record CreateGrantRequest(
            @NotBlank String channelId,
            @NotBlank String assetId,
            String regionCode,
            @NotNull OffsetDateTime validFrom,
            @NotNull OffsetDateTime validTo) {
    }

    /** 授权响应；regionCode 为 * 表示全部区域。 */
    public record GrantResponse(
            long id,
            String channelId,
            String assetId,
            String regionCode,
            OffsetDateTime validFrom,
            OffsetDateTime validTo,
            boolean revoked) {
    }

    /** 撤销授权请求。 */
    public record RevokeGrantRequest(@NotBlank String requestId) {
    }

    /**
     * 区域插播输入：为条目指定 regionCode 配置插播素材与 UTC 左闭右开窗口；
     * 窗口须落在条目窗口内，同条目同区域窗口不得重叠（端点相接合法）。
     */
    public record SpliceInput(
            @NotBlank String regionCode,
            @NotBlank String assetId,
            @NotNull OffsetDateTime start,
            @NotNull OffsetDateTime end) {
    }

    /** 草稿片段输入；id 为空时由系统生成，splices 为空表示无区域插播。 */
    public record SegmentInput(
            String id,
            @NotBlank String assetId,
            @NotNull OffsetDateTime start,
            @NotNull OffsetDateTime end,
            List<@NotNull SpliceInput> splices) {
    }

    /** 整份替换草稿请求。 */
    public record ReplaceDraftRequest(
            @NotBlank String requestId,
            @NotNull @PositiveOrZero Long expectedDraftVersion,
            @NotNull List<@NotNull SegmentInput> segments) {
    }

    /** 区域插播响应。 */
    public record SpliceResponse(
            String id,
            String regionCode,
            String assetId,
            OffsetDateTime start,
            OffsetDateTime end) {
    }

    /** 草稿片段响应；splices 为该条目已配置的区域插播。 */
    public record SegmentResponse(
            String id,
            String assetId,
            OffsetDateTime start,
            OffsetDateTime end,
            List<SpliceResponse> splices) {
    }

    /** 草稿响应。 */
    public record DraftResponse(
            String channelId,
            String businessDay,
            long version,
            List<SegmentResponse> segments) {
    }

    /**
     * 发布请求。spliceKey 可选：提供时作为发布幂等键，指纹含节目单版本、区域、窗口、素材及
     * 授权版本；同键同内容重放首次完整快照，同键不同内容 409，发布失败不占键。
     */
    public record PublishRequest(
            @NotBlank String requestId,
            @NotNull @Positive Long draftVersion,
            @NotNull @PositiveOrZero Long expectedPublishedVersion,
            String spliceKey) {
    }

    /** 发布快照区域行：固化区域、条目、实际素材、授权版本、插播窗口与回退原因。 */
    public record PublicationRegionRowResponse(
            String regionCode,
            String segmentId,
            String assetId,
            long grantId,
            OffsetDateTime spliceStart,
            OffsetDateTime spliceEnd,
            String fallbackReason) {
    }

    /** 发布响应；regions 为本次发布固化的区域解析快照（无区域插播配置时为空列表）。 */
    public record PublishResponse(
            long publicationId,
            String channelId,
            String businessDay,
            long publishedVersion,
            long draftVersion,
            String spliceKey,
            List<PublicationRegionRowResponse> regions) {
    }

    /** 播出决定来源：EMERGENCY 命中紧急插播，SPLICE 命中区域插播，PROGRAM 命中节目片段，FALLBACK 返回保底素材。 */
    public enum DecisionSource {
        EMERGENCY, SPLICE, PROGRAM, FALLBACK
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

    /** 创建黑屏窗口请求（幂等）；regionCode 为空表示 *（全部区域），窗口为 UTC 左闭右开。 */
    public record CreateBlackoutWindowRequest(
            @NotBlank String requestId,
            String regionCode,
            @NotNull OffsetDateTime start,
            @NotNull OffsetDateTime end) {
    }

    /** 黑屏窗口响应。 */
    public record BlackoutWindowResponse(
            long id,
            String channelId,
            String regionCode,
            OffsetDateTime start,
            OffsetDateTime end,
            OffsetDateTime createdAt) {
    }

    /** 区域回退原因：该区域未配置插播 / 当前时刻不在任何插播窗口内 / 插播授权已撤销回退主素材。 */
    public enum SpliceFallbackReason {
        NO_SPLICE_CONFIG, NO_SPLICE_WINDOW, SPLICE_GRANT_REVOKED
    }

    /**
     * 区域播放决策响应。source 为 SPLICE 时命中发布快照中的插播窗口（grantId、spliceStart/spliceEnd
     * 非空）；source 为 PROGRAM 时 spliceFallbackReason 说明未命中插播的原因；窗口内决策使用发布
     * 快照素材，不按当前插播配置重新解析。
     */
    public record RegionPlayoutDecisionResponse(
            String channelId,
            String regionCode,
            OffsetDateTime at,
            String assetId,
            DecisionSource source,
            FallbackReason reason,
            SpliceFallbackReason spliceFallbackReason,
            Long publicationId,
            String segmentId,
            String overrideKey,
            Long grantId,
            OffsetDateTime spliceStart,
            OffsetDateTime spliceEnd) {
    }

    /** 发布快照片段行。 */
    public record SnapshotSegmentResponse(
            String segmentId,
            String assetId,
            long grantId,
            OffsetDateTime start,
            OffsetDateTime end) {
    }

    /** 发布快照查询响应：快照只读，后续插播修改、授权撤销或素材版本拉取不改写。 */
    public record PublicationSnapshotResponse(
            long publicationId,
            String channelId,
            String businessDay,
            long publishedVersion,
            long draftVersion,
            String spliceKey,
            List<SnapshotSegmentResponse> segments,
            List<PublicationRegionRowResponse> regions) {
    }

    /** 授权阻断明细：发布前诊断某区域某条目插播被阻断的原因。 */
    public record SpliceBlock(
            String regionCode,
            String segmentId,
            String assetId,
            String reason,
            String message) {
    }

    /** 插播阻断诊断响应；blocks 为空表示当前草稿的区域插播可发布。 */
    public record SpliceDiagnosticsResponse(
            String channelId,
            String businessDay,
            long draftVersion,
            List<SpliceBlock> blocks) {
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

    /** 统一错误响应体；details 仅整次发布被区域插播阻断（422 SPLICE_BLOCKED）时携带，按区域与条目稳定排序。 */
    public record ErrorResponse(String error, String message, List<SpliceBlock> details) {
        public ErrorResponse(String error, String message) {
            this(error, message, null);
        }
    }
}
