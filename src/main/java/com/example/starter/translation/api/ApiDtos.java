package com.example.starter.translation.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 多语种段落发布 API 的请求与响应体。所有写操作均携带全局唯一 requestId。
 */
public final class ApiDtos {

    private ApiDtos() {
    }

    /** 初始段落输入。 */
    public record SegmentInput(
            @NotBlank(message = "segmentId 不能为空") @Size(max = 64) String segmentId,
            @NotBlank(message = "sourceText 不能为空") String sourceText) {
    }

    /** 建文档请求：1~5 种目标语言，可携带初始段落。 */
    public record CreateDocumentRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotNull(message = "targetLanguages 不能为空") @Size(min = 1, max = 5, message = "目标语言须为 1~5 种")
            List<@NotBlank(message = "语言码不能为空") String> targetLanguages,
            @NotNull(message = "segments 不能为空") List<@Valid SegmentInput> segments) {
    }

    /** 增加段落请求。 */
    public record AddSegmentRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotBlank(message = "segmentId 不能为空") @Size(max = 64) String segmentId,
            @NotBlank(message = "sourceText 不能为空") String sourceText) {
    }

    /** 源文修订请求。 */
    public record ReviseSourceRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotBlank(message = "sourceText 不能为空") String sourceText) {
    }

    /** 译文提交请求；作者取 X-Actor-Id 请求头。 */
    public record SubmitTranslationRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotBlank(message = "content 不能为空") String content,
            @Positive(message = "sourceVersion 必须为正数") int sourceVersion) {
    }

    /** 译文批准请求；审核人取 X-Actor-Id 请求头。 */
    public record ApproveTranslationRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @Positive(message = "translationVersion 必须为正数") int translationVersion) {
    }

    /** 发布请求：携带期望的草稿与发布版本做乐观校验。 */
    public record PublishRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @Positive(message = "expectedDraftVersion 必须为正数") int expectedDraftVersion,
            @PositiveOrZero(message = "expectedPublishedVersion 不能为负数") int expectedPublishedVersion) {
    }

    /** 术语规则输入：sourceTerm 区分大小写，requiredTranslation 非空。 */
    public record TermRuleInput(
            @NotBlank(message = "sourceTerm 不能为空") @Size(max = 512) String sourceTerm,
            @NotBlank(message = "language 不能为空") String language,
            @NotBlank(message = "requiredTranslation 不能为空") @Size(max = 2048) String requiredTranslation) {
    }

    /** 新增术语版本请求：携带期望的当前术语版本与完整规则集（0~100 条）。 */
    public record UpdateTermsRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @PositiveOrZero(message = "expectedTermVersion 不能为负数") int expectedTermVersion,
            @NotNull(message = "rules 不能为空") @Size(max = 100, message = "术语规则最多 100 条")
            List<@Valid TermRuleInput> rules) {
    }

    /** 建文档响应。 */
    public record DocumentResponse(long documentId, int draftVersion, int publishedVersion,
                                   List<String> targetLanguages) {
    }

    /** 段落相关写操作响应。 */
    public record SegmentResponse(long documentId, String segmentId, int sourceVersion, int draftVersion) {
    }

    /** 译文提交响应。 */
    public record TranslationResponse(long documentId, String segmentId, String language,
                                      int translationVersion, int sourceVersion, int termVersion,
                                      int draftVersion) {
    }

    /** 译文批准响应。 */
    public record ApprovalResponse(long documentId, String segmentId, String language, String reviewer,
                                   int translationVersion, int sourceVersion) {
    }

    /** 发布响应。 */
    public record PublishResponse(long documentId, int publishedVersion) {
    }

    /** 术语规则视图。 */
    public record TermRuleView(String sourceTerm, String language, String requiredTranslation) {
    }

    /** 新增术语版本响应。 */
    public record TermVersionResponse(long documentId, int termVersion, int ruleCount, int draftVersion) {
    }

    /** 术语版本查询视图：版本号与完整规则集。 */
    public record TermVersionView(long documentId, int termVersion, List<TermRuleView> rules) {
    }

    /** 单条译文的术语状态：绑定版本、是否过期及当前规则下的违规术语。 */
    public record TranslationTermStatus(String segmentId, String language, int translationVersion,
                                        int termVersion, boolean termStale, List<TermRuleView> violations) {
    }

    /** 译文术语状态查询响应。 */
    public record TermStatusResponse(long documentId, int termVersion, List<TranslationTermStatus> translations) {
    }

    /** 冲突声明输入：评审人、语种、有效 UTC 区间（左闭右开，秒精度）与原因。 */
    public record ConflictDeclarationInput(
            @NotBlank(message = "reviewer 不能为空") @Size(max = 128) String reviewer,
            @NotBlank(message = "language 不能为空") String language,
            @NotNull(message = "validFrom 不能为空") Instant validFrom,
            @NotNull(message = "validTo 不能为空") Instant validTo,
            @NotBlank(message = "reason 不能为空") @Size(max = 1024) String reason) {
    }

    /** 批量登记冲突声明请求：任一非法整批不生效。 */
    public record DeclareConflictsRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotEmpty(message = "declarations 不能为空") @Size(max = 100, message = "单次最多 100 条声明")
            List<@Valid ConflictDeclarationInput> declarations) {
    }

    /** 解除冲突声明请求：须由不同于声明人的合规角色（X-Actor-Role: COMPLIANCE）确认。 */
    public record LiftConflictRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotBlank(message = "reason 不能为空") @Size(max = 1024) String reason) {
    }

    /** 冲突声明视图：区间与原因固化；未解除时 liftedBy/liftedAt/liftReason 为 null。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ConflictView(long conflictId, long documentId, String reviewer, String language,
                               String reason, Instant validFrom, Instant validTo, String status,
                               String declaredBy, Instant declaredAt, String liftedBy,
                               Instant liftedAt, String liftReason) {
    }

    /** 批量登记响应：含每条声明及本次排除的评审数。 */
    public record DeclareConflictsResponse(long documentId, int declaredCount, int excludedReviewCount,
                                           List<ConflictView> conflicts) {
    }

    /** 冲突历史记录视图：登记与解除按 historyId 升序，字段与时间精度固定。 */
    public record ConflictHistoryView(long historyId, long conflictId, String action, String actor,
                                      String reason, Instant validFrom, Instant validTo,
                                      Instant recordedAt) {
    }

    /** 冲突历史查询响应。 */
    public record ConflictHistoryResponse(long documentId, List<ConflictHistoryView> records) {
    }

    /** 冲突声明明细查询响应。 */
    public record ConflictListResponse(long documentId, List<ConflictView> conflicts) {
    }

    /** 评审范围：段落与语言。 */
    public record ScopeView(String segmentId, String language) {
    }

    /** 冲突诊断视图：当前有效冲突、被排除评审与按当前状态重算的法定人数缺口；只读不改状态。 */
    public record ConflictDiagnosticsView(long documentId, Instant asOf, int requiredReviews,
                                          int effectiveReviews, int missingReviews,
                                          int conflictedReviewCount, List<ScopeView> affectedScopes,
                                          List<ConflictView> effectiveConflicts) {
    }

    /** 统一错误响应；violations 仅在术语违规 422 时返回，含全部违规术语；
     *  法定人数不足 422 时返回缺失/要求/实际评审数与受影响范围。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorResponse(String error, String message, List<TermRuleView> violations,
                                Integer missingReviews, Integer requiredReviews, Integer actualReviews,
                                List<ScopeView> affectedScopes) {
        public ErrorResponse(String error, String message) {
            this(error, message, null, null, null, null, null);
        }

        public ErrorResponse(String error, String message, List<TermRuleView> violations) {
            this(error, message, violations, null, null, null, null);
        }
    }
}
