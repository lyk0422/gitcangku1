package com.example.starter.playout.api;

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

    /** 播出决定来源：EMERGENCY 命中紧急插播，PROGRAM 命中节目片段，FALLBACK 返回保底素材，SUBSTITUTE 命中频道屏蔽窗口返回替补素材。 */
    public enum DecisionSource {
        EMERGENCY, PROGRAM, FALLBACK, SUBSTITUTE
    }

    /** 保底原因：无已发布编排 / 处于空档 / 覆盖片段的授权已撤销。 */
    public enum FallbackReason {
        NO_PUBLISHED_SCHEDULE, GAP, GRANT_REVOKED
    }

    /**
     * 播出决定响应；source 为 FALLBACK 时 reason 非空；source 为 EMERGENCY 时 overrideKey 非空；
     * source 为 SUBSTITUTE 时 blackoutKey/replacedAssetId/replacedSource 非空，记录命中的屏蔽窗口、
     * 被替换的原素材与原来源（EMERGENCY / PROGRAM / FALLBACK）。
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
            String blackoutKey,
            String replacedAssetId,
            DecisionSource replacedSource) {

        /** 不含屏蔽信息的构造入口（未命中屏蔽替换时使用）。 */
        public PlayoutDecisionResponse(String channelId, OffsetDateTime at, String assetId,
                                       DecisionSource source, FallbackReason reason,
                                       Long publicationId, String segmentId, String overrideKey) {
            this(channelId, at, assetId, source, reason, publicationId, segmentId, overrideKey,
                    null, null, null);
        }
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

    /** 屏蔽窗口状态：创建即 ACTIVE，取消后为 CANCELLED（终态，只能取消不可改写）。 */
    public enum BlackoutStatus {
        ACTIVE, CANCELLED
    }

    /**
     * 创建频道屏蔽窗口请求。区间 [start, end) 左闭右开、同处一个 Asia/Shanghai 业务日、
     * 时长大于 0 且不超过 6 小时；blockedAssetIds 为 1～20 个去重素材，
     * substituteAssetId 不得出现在该集合中。
     */
    public record CreateBlackoutWindowRequest(
            @NotBlank String requestId,
            @NotBlank String blackoutKey,
            @NotBlank String channelId,
            @NotNull OffsetDateTime start,
            @NotNull OffsetDateTime end,
            @NotNull @Size(min = 1, max = 20) List<@NotBlank String> blockedAssetIds,
            @NotBlank String substituteAssetId) {
    }

    /** 取消屏蔽窗口请求（幂等）。 */
    public record CancelBlackoutWindowRequest(@NotBlank String requestId) {
    }

    /**
     * 屏蔽窗口明细响应：ACTIVE/CANCELLED 均返回；取消后 blockedAssetIds 仍为创建时原集合，
     * 并保留取消请求 ID 与取消时刻，历史明细不可改写。
     */
    public record BlackoutWindowResponse(
            String blackoutKey,
            String channelId,
            OffsetDateTime start,
            OffsetDateTime end,
            List<String> blockedAssetIds,
            String substituteAssetId,
            BlackoutStatus status,
            String cancelRequestId,
            OffsetDateTime cancelledAt,
            OffsetDateTime createdAt) {
    }

    /** 统一错误响应体。 */
    public record ErrorResponse(String error, String message) {
    }
}
