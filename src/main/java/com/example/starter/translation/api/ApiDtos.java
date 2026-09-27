package com.example.starter.translation.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 多语种段落发布 API 的请求与响应体。所有写操作均携带全局唯一 requestId。
 * 时间字段统一为 UTC ISO-8601（如 2026-10-01T00:00:00Z），区间左闭右开。
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

    /** 译文否决请求；与批准相同的版本匹配与冲突资格规则。 */
    public record RejectTranslationRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotBlank(message = "reason 不能为空") @Size(max = 1024) String reason,
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

    /** 单个冲突范围：语种 + 左闭右开 UTC 区间 + 非空原因。 */
    public record ConflictRangeInput(
            @NotBlank(message = "language 不能为空") String language,
            @NotBlank(message = "validFrom 不能为空") String validFrom,
            @NotBlank(message = "validTo 不能为空") String validTo,
            @NotBlank(message = "reason 不能为空") @Size(max = 1024) String reason) {
    }

    /**
     * 批量登记冲突请求：同一评审人的 1~50 个范围。
     * 登记前对全部范围做完整预校验，任一非法整批拒绝且不改变评审资格。
     */
    public record RegisterConflictsRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotNull(message = "ranges 不能为空") @Size(min = 1, max = 50, message = "冲突范围须为 1~50 个")
            List<@Valid ConflictRangeInput> ranges) {
    }

    /** 解除冲突请求：由合规角色确认，追加历史记录。 */
    public record ResolveConflictRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotBlank(message = "reason 不能为空") @Size(max = 1024) String reason) {
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

    /** 评审（批准/否决）响应。 */
    public record ApprovalResponse(long documentId, String segmentId, String language, String reviewer,
                                   int translationVersion, int sourceVersion, String decision,
                                   String reviewStatus) {
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

    /** 冲突历史事件视图：只追加，按 eventId 升序。 */
    public record ConflictEventView(long eventId, long conflictId, String eventType, String actor,
                                    String actorRole, String validFrom, String validTo, String reason,
                                    String createdAt) {
    }

    /** 冲突声明视图：固化登记/解除信息，未解除字段为 null。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ConflictView(long conflictId, long documentId, String reviewer, String language,
                               String validFrom, String validTo, String reason, String status,
                               String declaredBy, String declaredAt, String resolvedBy, String resolvedAt,
                               String resolveReason, List<ConflictEventView> history) {
    }

    /** 批量登记冲突中单个范围的登记结果。 */
    public record RegisteredConflictView(long conflictId, String language, String validFrom, String validTo,
                                         String reason, String status, int markedConflictReviews) {
    }

    /** 批量登记冲突响应：实际登记数与被标记 CONFLICTED 的既有评审总数。 */
    public record ConflictRegistrationResponse(long documentId, String reviewer, int registeredCount,
                                               int markedConflictReviewCount,
                                               List<RegisteredConflictView> conflicts) {
    }

    /** 评审明细视图：自增证据 ID、版本、决定、否决原因、状态与固化的冲突 ID。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReviewView(long reviewId, String segmentId, String language, String reviewer, String decision,
                             String decisionReason, String reviewStatus, Long conflictId, int sourceVersion,
                             int translationVersion, String reviewedAt) {
    }

    /** 法定人数不足时受影响段落及其缺失评审的语种。 */
    public record AffectedSegmentView(String segmentId, List<String> languages, int requiredReviewCount,
                                      int missingReviewCount) {
    }

    /** 发布诊断中否决明细。 */
    public record RejectedReviewView(String segmentId, String language, String reviewer,
                                     int translationVersion) {
    }

    /** 评审人单语种资格视图：eligible=false 时列出当前命中的有效冲突。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReviewerEligibilityView(String reviewer, String language, boolean eligible,
                                          List<ConflictView> activeConflicts) {
    }

    /** 单段落单语种评审计数与法定人数诊断。 */
    public record SegmentReviewSummaryView(String segmentId, String language, int activeApprovals,
                                           int activeRejections, int conflictedReviews,
                                           int requiredReviewCount, int missingReviewCount,
                                           boolean quorumMet, boolean hasRejection) {
    }

    /** 评审资格与法定人数诊断响应（读取不改变状态）。 */
    public record ReviewDiagnosticsResponse(long documentId, String evaluatedAt, int requiredReviewCount,
                                            List<ReviewerEligibilityView> reviewers,
                                            List<SegmentReviewSummaryView> segments) {
    }

    /**
     * 统一错误响应。
     * violations 仅在术语违规 422 时返回；法定人数不足时返回要求值、缺失数与受影响段落；
     * 存在有效否决时返回否决明细。各可选字段为 null 时不序列化。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorResponse(String error, String message, List<TermRuleView> violations,
                                Integer requiredReviewCount, Integer missingReviewCount,
                                List<AffectedSegmentView> affectedSegments,
                                List<RejectedReviewView> rejectedReviews) {
        public ErrorResponse(String error, String message) {
            this(error, message, null, null, null, null, null);
        }

        public ErrorResponse(String error, String message, List<TermRuleView> violations) {
            this(error, message, violations, null, null, null, null);
        }
    }
}
