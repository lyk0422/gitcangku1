package com.example.starter.playout.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 主备播出链路租约切换 API 的请求与响应类型。
 * 时间字段统一为 Asia/Shanghai、毫秒精度 ISO 8601；sequence 为从 1 起的连续正整数。
 */
public final class FailoverDtos {

    private FailoverDtos() {
    }

    /** 链路角色：PRIMARY 主链路 / BACKUP 备链路，每频道各一条。 */
    public enum LinkRole {
        PRIMARY, BACKUP
    }

    /** 租约状态：ACTIVE 持约中 / ENDED 已结束。 */
    public enum LeaseStatus {
        ACTIVE, ENDED
    }

    /** 切换单状态：CREATED 待激活 / ACTIVATED 已激活 / REJECTED 激活被拒（终结）。 */
    public enum OrderStatus {
        CREATED, ACTIVATED, REJECTED
    }

    /** 回执裁决：SETTLED 结算推进游标 / LATE 旧世代迟到仅存档 / DUPLICATE 重复 / STANDBY 非持约链路。 */
    public enum ReceiptDisposition {
        SETTLED, LATE, DUPLICATE, STANDBY
    }

    /** 注册频道链路请求。 */
    public record RegisterLinkRequest(
            @NotBlank String linkId,
            @NotNull LinkRole role) {
    }

    /** 链路响应。 */
    public record LinkResponse(
            String channelId,
            String linkId,
            LinkRole role,
            boolean healthy,
            long cachedVersion,
            OffsetDateTime updatedAt) {
    }

    /** 链路健康与缓存编排版本上报。 */
    public record LinkHealthRequest(
            @NotBlank String channelId,
            @NotNull Boolean healthy,
            @NotNull @PositiveOrZero Long cachedVersion) {
    }

    /** 紧急插播已同步到链路的上报。 */
    public record OverrideSyncedRequest(
            @NotBlank String channelId,
            @NotBlank String overrideKey) {
    }

    /** 链路播放回执；generation 为回执声称的租约世代，seq 为已播放确认的连续序号。 */
    public record ReceiptRequest(
            @NotBlank String requestId,
            @NotBlank String channelId,
            @NotNull @Positive Long generation,
            @NotNull @Positive Long seq) {
    }

    /** 回执仲裁结果。 */
    public record ReceiptResponse(
            String channelId,
            String linkId,
            long generation,
            long seq,
            ReceiptDisposition disposition,
            String activeLinkId,
            long activeGeneration,
            long channelConfirmedSeq) {
    }

    /** 频道初始世代（generation=1）引导请求。 */
    public record BootstrapLeaseRequest(@NotBlank String linkId) {
    }

    /** 租约响应。 */
    public record LeaseResponse(
            String channelId,
            String linkId,
            long generation,
            LeaseStatus status,
            long confirmedSeq,
            Long cutoverSeq,
            long scheduleVersion,
            Long orderId,
            OffsetDateTime createdAt,
            OffsetDateTime endedAt) {
    }

    /** 安全切点预览请求（只读，不写数据）。 */
    public record FailoverPreviewRequest(
            @NotBlank String channelId,
            @NotBlank String sourceLinkId,
            @NotBlank String targetLinkId,
            @NotNull @PositiveOrZero Long maxLag) {
    }

    /** 安全切点预览（只读，不写数据）。 */
    public record FailoverPreviewResponse(
            String channelId,
            String sourceLinkId,
            String targetLinkId,
            long channelVersion,
            boolean targetHealthy,
            long targetCachedVersion,
            long sourceConfirmedSeq,
            long targetContiguousSeq,
            long commonPrefixSeq,
            long nextSeq,
            List<Long> gaps) {
    }

    /** 创建切换单请求；channelVersion 为监控员看到的频道编排版本，maxLag 为允许落后上限（条）。 */
    public record CreateFailoverOrderRequest(
            @NotBlank String requestId,
            @NotBlank String failoverKey,
            @NotBlank String channelId,
            @NotNull @PositiveOrZero Long channelVersion,
            @NotBlank String sourceLinkId,
            @NotBlank String targetLinkId,
            @NotNull @PositiveOrZero Long sourceLastSeq,
            @NotNull @PositiveOrZero Long targetLastSeq,
            @NotNull OffsetDateTime cutoverAt,
            @NotNull @PositiveOrZero Long maxLag) {
    }

    /** 激活切换单请求（幂等）。 */
    public record ActivateFailoverRequest(@NotBlank String requestId) {
    }

    /** 冻结的紧急插播栈条目（激活时快照，只读证据）。 */
    public record OverrideStackItem(
            String overrideKey,
            String assetId,
            long grantId,
            int priority,
            OffsetDateTime start,
            OffsetDateTime end) {
    }

    /** 编排证据：激活/查询时的频道与双方缓存版本及当日发布版本序列；链路缺失时缓存版本为 null。 */
    public record ScheduleEvidence(
            long channelVersion,
            Long sourceCachedVersion,
            Long targetCachedVersion,
            List<Long> publicationVersions) {
    }

    /** 迟到回执条目。 */
    public record LateReceiptItem(
            String linkId,
            long generation,
            long seq,
            OffsetDateTime receivedAt) {
    }

    /** 切换单明细响应：含租约世代、切点、迟到回执、冻结插播栈与编排证据，查询只读。 */
    public record FailoverOrderResponse(
            String failoverKey,
            String channelId,
            long channelVersion,
            String sourceLinkId,
            String targetLinkId,
            long sourceLastSeq,
            long targetLastSeq,
            OffsetDateTime cutoverAt,
            long maxLag,
            OrderStatus status,
            Long generation,
            Long safeCutSeq,
            Long scheduleVersion,
            List<OverrideStackItem> frozenStack,
            LeaseResponse activeLease,
            List<LateReceiptItem> lateReceipts,
            ScheduleEvidence scheduleEvidence,
            OffsetDateTime createdAt,
            OffsetDateTime activatedAt) {
    }
}
