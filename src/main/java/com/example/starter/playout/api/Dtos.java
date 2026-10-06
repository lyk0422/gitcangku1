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

    /** 播出决定来源：EMERGENCY 命中紧急插播，PROGRAM 命中节目片段，MIX 命中交叉淡化重叠区，FALLBACK 返回保底素材。 */
    public enum DecisionSource {
        EMERGENCY, PROGRAM, MIX, FALLBACK
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
     * 混音条目输入：按列表顺序登记素材；id 为空时由系统生成；
     * fadeInMs 为与前一条目的交叉淡化时长（毫秒，0～10000），首条目必须为 0 或缺省。
     */
    public record MixItemInput(
            String id,
            @NotBlank String assetId,
            Long fadeInMs) {
    }

    /**
     * 整份替换混音草稿请求。startAt 为首个素材的播出起点（须位于业务日内）；
     * 各条目时长取素材登记的整秒时长，下一条目开始等于前一条目结束减去淡化时长。
     */
    public record ReplaceMixDraftRequest(
            @NotBlank String requestId,
            @NotBlank String operator,
            @NotNull @PositiveOrZero Long expectedDraftVersion,
            @NotNull OffsetDateTime startAt,
            @NotEmpty List<@NotNull MixItemInput> items) {
    }

    /** 混音条目响应；start/end 为合成后的实际播出区间（含与相邻条目的重叠段）。 */
    public record MixItemResponse(
            String id,
            String assetId,
            long fadeInMs,
            OffsetDateTime start,
            OffsetDateTime end) {
    }

    /** 混音草稿响应。 */
    public record MixDraftResponse(
            String channelId,
            String businessDay,
            long version,
            List<MixItemResponse> items) {
    }

    /** 发布混音草稿请求。 */
    public record PublishMixRequest(
            @NotBlank String requestId,
            @NotBlank String operator,
            @NotNull @Positive Long draftVersion,
            @NotNull @PositiveOrZero Long expectedPublishedVersion) {
    }

    /**
     * 混音分量：重叠区内前条目增益为 1-u、后条目增益为 u（u 为重叠进度，左端 u=0）；
     * 非重叠区单条目增益恒为 1.0。
     */
    public record MixComponent(
            String itemId,
            String assetId,
            long grantId,
            double gain) {
    }

    /**
     * 混音播出决策响应。source 为 MIX 时 components 含两个分量；为 PROGRAM 时含一个分量；
     * 为 EMERGENCY 时 assetId/overrideKey 非空；为 FALLBACK 时 assetId 为保底素材且 reason 非空。
     */
    public record MixPlayoutDecisionResponse(
            String channelId,
            OffsetDateTime at,
            DecisionSource source,
            FallbackReason reason,
            String assetId,
            String overrideKey,
            Long publicationId,
            List<MixComponent> components) {
    }

    /** 统一错误响应体。 */
    public record ErrorResponse(String error, String message) {
    }
}
