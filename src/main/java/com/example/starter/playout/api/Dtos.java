package com.example.starter.playout.api;

import com.fasterxml.jackson.annotation.JsonInclude;
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

    /** 素材响应；version 随版本拉取递增，withdrawn 表示已撤回（终态）。 */
    public record AssetResponse(String id, long durationMs, long version, boolean withdrawn) {
    }

    /** 创建频道请求；fallbackAssetId 为不会被撤销的保底素材。 */
    public record CreateChannelRequest(
            String id,
            @NotBlank String fallbackAssetId) {
    }

    /** 频道响应。 */
    public record ChannelResponse(String id, String fallbackAssetId) {
    }

    /** 创建授权请求，有效区间 [validFrom, validTo) 左闭右开；regionCode 为空表示全部区域。 */
    public record CreateGrantRequest(
            @NotBlank String channelId,
            @NotBlank String assetId,
            String regionCode,
            @NotNull OffsetDateTime validFrom,
            @NotNull OffsetDateTime validTo) {
    }

    /** 授权响应；version 初始 1，撤销时递增。 */
    public record GrantResponse(
            long id,
            String channelId,
            String assetId,
            String regionCode,
            OffsetDateTime validFrom,
            OffsetDateTime validTo,
            long version,
            boolean revoked) {
    }

    /** 撤回素材请求（幂等）。 */
    public record WithdrawAssetRequest(@NotBlank String requestId) {
    }

    /** 拉取素材新版本请求（幂等）。 */
    public record PullAssetRequest(@NotBlank String requestId) {
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

    // ---------- 区域插播 ----------

    /** 插播窗口输入：UTC 左闭右开，须落在所属条目窗口内；grantId 须覆盖该区域与完整窗口。 */
    public record SpliceWindowInput(
            @NotBlank String assetId,
            @NotNull @Positive Long grantId,
            @NotNull OffsetDateTime start,
            @NotNull OffsetDateTime end) {
    }

    /** 单个区域的插播配置输入；同区域窗口不得重叠（端点相接合法）。 */
    public record SpliceRegionInput(
            @NotBlank String regionCode,
            @NotNull List<@NotNull SpliceWindowInput> windows) {
    }

    /**
     * 整份替换条目插播配置请求。spliceKey 为幂等键，指纹含节目单版本、区域、窗口、素材及授权版本；
     * 同键同参重放首次完整结果，失败不占键。expectedDraftVersion 为节目单（草稿）版本乐观锁。
     */
    public record ReplaceSplicesRequest(
            @NotBlank String spliceKey,
            @NotNull @Positive Long expectedDraftVersion,
            @NotNull List<@NotNull SpliceRegionInput> regions) {
    }

    /** 插播窗口响应，含配置时固化的授权版本。 */
    public record SpliceWindowResponse(
            String assetId,
            long grantId,
            long grantVersion,
            OffsetDateTime start,
            OffsetDateTime end) {
    }

    /** 单个区域的插播配置响应。 */
    public record SpliceRegionResponse(
            String regionCode,
            List<SpliceWindowResponse> windows) {
    }

    /** 条目插播配置响应。 */
    public record SpliceConfigResponse(
            String channelId,
            String businessDay,
            String segmentId,
            long draftVersion,
            List<SpliceRegionResponse> regions) {
    }

    // ---------- 黑屏窗口 ----------

    /** 黑屏窗口状态：创建即 ACTIVE，取消后为 CANCELLED。 */
    public enum BlackoutStatus {
        ACTIVE, CANCELLED
    }

    /** 创建区域黑屏窗口请求，UTC 左闭右开。 */
    public record CreateBlackoutRequest(
            @NotBlank String requestId,
            @NotBlank String regionCode,
            @NotNull OffsetDateTime start,
            @NotNull OffsetDateTime end) {
    }

    /** 取消黑屏窗口请求（幂等）。 */
    public record CancelBlackoutRequest(@NotBlank String requestId) {
    }

    /** 黑屏窗口响应。 */
    public record BlackoutResponse(
            long id,
            String channelId,
            String regionCode,
            OffsetDateTime start,
            OffsetDateTime end,
            BlackoutStatus status,
            String cancelRequestId,
            OffsetDateTime cancelledAt,
            OffsetDateTime createdAt) {
    }

    // ---------- 区域播放决策与回执 ----------

    /** 区域播放来源：EMERGENCY 紧急插播 / SPLICE 区域插播 / PROGRAM 主素材 / FALLBACK 保底素材。 */
    public enum RegionalDecisionSource {
        EMERGENCY, SPLICE, PROGRAM, FALLBACK
    }

    /**
     * 区域播放决策响应。基于发布快照解析，不按当前插播配置重新解析；
     * source 为 SPLICE 时 spliceStart/spliceEnd 非空；reason 仅在回退时非空。
     */
    public record RegionalPlayoutResponse(
            String channelId,
            String regionCode,
            OffsetDateTime at,
            String assetId,
            RegionalDecisionSource source,
            FallbackReason reason,
            Long publicationId,
            String segmentId,
            OffsetDateTime spliceStart,
            OffsetDateTime spliceEnd,
            Long grantId,
            Long grantVersion) {
    }

    /** 创建区域播出回执请求；窗口内回执使用发布快照素材。 */
    public record CreateReceiptRequest(
            @NotBlank String requestId,
            @NotNull OffsetDateTime at) {
    }

    /** 区域播出回执响应。 */
    public record ReceiptResponse(
            long id,
            String channelId,
            String regionCode,
            OffsetDateTime at,
            String assetId,
            RegionalDecisionSource source,
            Long publicationId,
            String segmentId,
            Long grantId,
            Long grantVersion,
            OffsetDateTime createdAt) {
    }

    // ---------- 发布快照与诊断 ----------

    /** 发布快照中的条目行。 */
    public record PublicationSegmentView(
            String segmentId,
            String assetId,
            long grantId,
            OffsetDateTime start,
            OffsetDateTime end) {
    }

    /** 发布快照中的区域解析行；source 为 MAIN 时 spliceStart/spliceEnd 为空、fallbackReason 为 NO_SPLICE。 */
    public record PublicationRegionView(
            String segmentId,
            String regionCode,
            String assetId,
            long grantId,
            long grantVersion,
            OffsetDateTime spliceStart,
            OffsetDateTime spliceEnd,
            String source,
            String fallbackReason) {
    }

    /** 发布快照完整响应：区域、条目、实际素材、授权版本、插播窗口与回退原因均为发布时固化。 */
    public record PublicationSnapshotResponse(
            long publicationId,
            String channelId,
            String businessDay,
            long publishedVersion,
            long draftVersion,
            List<PublicationSegmentView> segments,
            List<PublicationRegionView> regions) {
    }

    /** 区域级阻断明细：发布 422 与授权阻断诊断共用，按区域、条目、窗口起点稳定排序。 */
    public record SpliceViolation(
            String regionCode,
            String segmentId,
            String reason,
            String message) {
    }

    /** 授权阻断诊断响应：当前草稿中会被发布拒绝的插播明细。 */
    public record SpliceDiagnosticsResponse(
            String channelId,
            String businessDay,
            List<SpliceViolation> violations) {
    }

    /** 统一错误响应体；violations 仅在区域级 422 时非空，按区域与条目稳定排序。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorResponse(String error, String message, List<SpliceViolation> violations) {
        public ErrorResponse(String error, String message) {
            this(error, message, null);
        }
    }
}
