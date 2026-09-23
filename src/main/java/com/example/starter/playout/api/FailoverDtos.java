package com.example.starter.playout.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 主备播出链路租约切换与游标回执仲裁 API 的请求与响应类型。
 * 时间字段统一为 Asia/Shanghai、毫秒精度 ISO 8601；sequence 从 1 起连续递增。
 */
public final class FailoverDtos {

    private FailoverDtos() {
    }

    /** 配置频道主备链路请求；首次配置时为主链路创建 generation=1 的 ACTIVE 租约。 */
    public record ConfigureLinksRequest(
            @NotBlank String primaryLinkId,
            @NotBlank String backupLinkId) {
    }

    /** 链路状态响应。 */
    public record LinkResponse(
            String channelId,
            String role,
            String linkId,
            boolean healthy,
            long cachedScheduleVersion,
            String cachedOverrideSignature) {
    }

    /** 上报链路运行态：健康标志、已缓存编排版本与已同步未决插播栈签名。 */
    public record LinkStateRequest(
            @NotNull Boolean healthy,
            @NotNull @PositiveOrZero Long cachedScheduleVersion,
            String cachedOverrideSignature) {
    }

    /** 链路播出回执请求；generation 为回执声称的租约世代，sequence 为已确认播出序号。 */
    public record ReceiptRequest(
            @NotBlank String channelId,
            @NotBlank String linkId,
            @NotNull @Positive Long generation,
            @NotNull @Positive Long sequence) {
    }

    /** 回执结算响应；confirmedSequence 为该频道公开连续游标（LATE/DUPLICATE 不推进）。 */
    public record ReceiptResponse(
            String channelId,
            String linkId,
            long generation,
            long sequence,
            String disposition,
            long confirmedSequence) {
    }

    /**
     * 创建切换单/预览请求。监控员提交频道版本、源/目标链路、双方最后回执 sequence 与计划切换时刻。
     */
    public record FailoverRequest(
            @NotBlank String failoverKey,
            @NotBlank String channelId,
            @NotNull @PositiveOrZero Long channelVersion,
            @NotBlank String sourceLinkId,
            @NotBlank String targetLinkId,
            @NotNull @PositiveOrZero Long sourceLastSequence,
            @NotNull @PositiveOrZero Long targetLastSequence,
            @NotNull OffsetDateTime cutoverAt) {
    }

    /** 激活切换单请求（携带幂等 requestId，业务参数同预览请求）。 */
    public record ActivateFailoverRequest(
            @NotBlank String requestId,
            @NotNull @Valid FailoverRequest order) {
    }

    /**
     * 安全切点预览响应（只读，不写数据）。nextSequence 为公共前缀之后下一条应播 sequence；
     * gaps 为目标已缓存序列中的缺口；lag 为源已确认与目标已缓存连续前缀之差。
     */
    public record FailoverPreviewResponse(
            String channelId,
            String sourceLinkId,
            String targetLinkId,
            long sourceConfirmedSequence,
            long targetCachedSequence,
            long commonPrefixSequence,
            long nextSequence,
            long lag,
            List<Long> gaps,
            boolean targetHealthy,
            boolean scheduleMatched,
            boolean overridesSynced) {
    }

    /** 切换单响应（仅激活成功才落单）。 */
    public record FailoverOrderResponse(
            String failoverKey,
            String channelId,
            long channelVersion,
            String sourceLinkId,
            String targetLinkId,
            long sourceLastSequence,
            long targetLastSequence,
            OffsetDateTime cutoverAt,
            String status,
            long generation,
            long cutSequence,
            OffsetDateTime activatedAt) {
    }

    /** 租约视图；scheduleSnapshot/overrideSnapshot 为切换时冻结的编排版本与插播栈 JSON（首代租约为 null）。 */
    public record LeaseView(
            long generation,
            String linkId,
            String role,
            String status,
            long cutSequence,
            long confirmedSequence,
            String scheduleSnapshot,
            String overrideSnapshot,
            OffsetDateTime startedAt,
            OffsetDateTime endedAt) {
    }

    /** 迟到回执视图。 */
    public record LateReceiptView(
            String linkId,
            long generation,
            long sequence,
            OffsetDateTime receivedAt) {
    }

    /** 编排与插播冻结证据。 */
    public record ScheduleEvidence(
            String businessDay,
            long publicationId,
            long publishedVersion,
            List<String> activeOverrideKeys,
            String overrideSignature) {
    }

    /** 频道租约切换状态查询响应（只读）。 */
    public record FailoverStateResponse(
            String channelId,
            long channelVersion,
            LeaseView activeLease,
            List<LeaseView> leaseHistory,
            List<LateReceiptView> lateReceipts,
            ScheduleEvidence evidence,
            List<LinkResponse> links) {
    }
}
