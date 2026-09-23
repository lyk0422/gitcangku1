package com.example.starter.translation.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 发布列车 API 的请求与响应体。写操作（创建、冻结、整列取消、激活、撤批）均携带 requestId。
 */
public final class TrainDtos {

    private TrainDtos() {
    }

    /** 单语言候选：候选译文版本与期望的当前发布指针（0 表示该 locale 尚未被列车推进）。 */
    public record CandidateInput(
            @NotBlank(message = "locale 不能为空") String locale,
            @Positive(message = "translationVersion 必须为正数") int translationVersion,
            @PositiveOrZero(message = "expectedVersion 不能为负数") int expectedVersion) {
    }

    /** 创建发布列车请求：2~20 个 locale，集合须唯一且与文档目标语言完全一致。 */
    public record CreateTrainRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotBlank(message = "trainKey 不能为空") @Size(max = 128) String trainKey,
            @Positive(message = "sourceDocumentVersion 必须为正数") int sourceDocumentVersion,
            @NotNull(message = "scheduledAt 不能为空") Instant scheduledAt,
            @NotNull(message = "candidates 不能为空") @Size(min = 2, max = 20, message = "列车须声明 2~20 个 locale")
            List<@Valid CandidateInput> candidates) {
    }

    /** 仅携带 requestId 的写操作请求（冻结、整列取消、激活、撤批）。 */
    public record TrainActionRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId) {
    }

    /** 撤批请求。 */
    public record UnapproveRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId) {
    }

    /** 源段版本差异明细。 */
    public record SourceVersionDiff(String segmentId, int candidateSourceVersion, int currentSourceVersion) {
    }

    /** 批准缺失或失效明细；reason 为 MISSING_APPROVAL 或 APPROVAL_STALE。 */
    public record ApprovalIssue(String segmentId, String reason) {
    }

    /** 逐语言预检结果：缺段、源段版本差异、批准问题、术语违规与当前发布指针。 */
    public record LocalePrecheck(String locale, int translationVersion, int expectedVersion,
                                 Integer currentPointer,
                                 List<String> missingSegments,
                                 List<SourceVersionDiff> sourceVersionDiffs,
                                 List<ApprovalIssue> approvalIssues,
                                 List<ApiDtos.TermRuleView> termViolations,
                                 boolean termVersionStale) {
    }

    /** 列车预检结果（只读，不写数据）。 */
    public record PrecheckResponse(long documentId, String trainKey, int sourceDocumentVersion,
                                   String status, Instant scheduledAt, boolean clean,
                                   List<LocalePrecheck> locales) {
    }

    /** 发布列车视图：列车和列车列表查询只读、按 trainKey 稳定排序。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TrainView(String trainKey, long documentId, int sourceDocumentVersion,
                            Instant scheduledAt, String status,
                            List<CandidateInput> candidates, Integer releaseTrainVersion,
                            Instant readyAt, Instant publishedAt, Instant cancelledAt,
                            PrecheckResponse frozenPrecheck) {
    }

    /** 单语言列车快照视图；快照不可变，按 locale 稳定排序。 */
    public record TrainSnapshotView(String trainKey, long documentId, String locale,
                                    int releaseTrainVersion, int pointerBefore, int pointerAfter,
                                    String snapshotJson, Instant createdAt) {
    }

    /** 当前发布指针视图。 */
    public record ReleasePointerView(long documentId, String locale, int releaseTrainVersion) {
    }

    /** 撤批响应。 */
    public record UnapproveResponse(long documentId, String segmentId, String language) {
    }
}
