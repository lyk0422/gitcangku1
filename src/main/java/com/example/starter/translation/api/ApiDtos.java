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

    /** 译文否决请求；审核人取 X-Actor-Id 请求头，字段与批准一致。 */
    public record RejectTranslationRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @Positive(message = "translationVersion 必须为正数") int translationVersion) {
    }

    /** 利益冲突区间输入：语种 + 左闭右开 UTC 区间 + 非空原因。 */
    public record ConflictRangeInput(
            @NotBlank(message = "language 不能为空") @Size(max = 16) String language,
            @NotNull(message = "validFrom 不能为空") Instant validFrom,
            @NotNull(message = "validTo 不能为空") Instant validTo,
            @NotBlank(message = "reason 不能为空") @Size(max = 2048) String reason) {
    }

    /** 单条冲突登记请求；评审人取 X-Actor-Id 请求头。 */
    public record DeclareConflictRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotBlank(message = "language 不能为空") @Size(max = 16) String language,
            @NotNull(message = "validFrom 不能为空") Instant validFrom,
            @NotNull(message = "validTo 不能为空") Instant validTo,
            @NotBlank(message = "reason 不能为空") @Size(max = 2048) String reason) {
    }

    /** 批量冲突登记请求：任一范围非法整批不改变评审资格。 */
    public record BatchDeclareConflictRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotEmpty(message = "declarations 不能为空")
            @Size(max = 100, message = "单次最多登记 100 条冲突")
            List<@Valid ConflictRangeInput> declarations) {
    }

    /** 解除冲突请求；确认人取 X-Actor-Id，角色取 X-Actor-Role（须为 COMPLIANCE 且不同于声明人）。 */
    public record ReleaseConflictRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId) {
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

    /** 利益冲突声明视图：固化区间、原因、状态与解除证据，时间均为 UTC。 */
    public record ConflictDeclarationView(long declarationId, long documentId, String reviewer, String language,
                                          Instant validFrom, Instant validTo, String reason, String status,
                                          String declaredBy, Instant declaredAt,
                                          Instant releasedAt, String releasedBy) {
    }

    /** 批量登记响应：实际写入条数与全部声明明细。 */
    public record BatchConflictResponse(long documentId, String reviewer, int declaredCount,
                                        List<ConflictDeclarationView> declarations) {
    }

    /** 评审提交（批准/否决）响应：决定与当前资格状态。 */
    public record ReviewResponse(long documentId, String segmentId, String language, String reviewer,
                                 String decision, String reviewStatus, int translationVersion,
                                 int sourceVersion, int termVersion, Long conflictDeclarationId) {
    }

    /** 冲突生命周期历史事件视图，按事件顺序稳定返回。 */
    public record ConflictEventView(long eventId, long declarationId, String eventType, String reviewer,
                                    String language, Instant validFrom, Instant validTo, String reason,
                                    String actor, String actorRole, Instant occurredAt) {
    }

    /** 评审历史视图，按历史顺序稳定返回；时间为 UTC，未涉及冲突的字段为 null。 */
    public record ReviewHistoryView(long historyId, String segmentId, String language, String reviewer,
                                    String decision, String reviewStatus, int sourceVersion,
                                    int translationVersion, int termVersion, Long conflictDeclarationId,
                                    String actor, Instant occurredAt) {
    }

    /** 评审资格明细：某段落某语种当前评审及其是否计入法定人数的原因。 */
    public record ReviewEligibilityView(String segmentId, String language, String reviewer, String decision,
                                        String reviewStatus, int sourceVersion, int translationVersion,
                                        boolean eligible, Long blockingDeclarationId, String reason) {
    }

    /** 评审诊断：当前有效冲突声明、全部评审资格与按当前状态计算的法定人数（不改变任何状态）。 */
    public record ReviewDiagnosticsResponse(long documentId, Instant evaluatedAt,
                                            List<ConflictDeclarationView> activeConflicts,
                                            List<ReviewEligibilityView> reviews,
                                            int requiredApprovals, int effectiveApprovals, int missingApprovals) {
    }

    /** 发布法定人数不足时受影响段落的语种明细：返回实际有效批准数、要求数与差额，不让调用方猜测。 */
    public record QuorumDeficitView(String segmentId, String language, int requiredApprovals,
                                    int effectiveApprovals, int missingApprovals, String reason) {
    }

    /** 统一错误响应；violations 仅在术语违规 422 时返回，deficits 仅在法定人数不足 422 时返回。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorResponse(String error, String message, List<TermRuleView> violations,
                                List<QuorumDeficitView> deficits, Integer missingApprovals) {
        public ErrorResponse(String error, String message) {
            this(error, message, null, null, null);
        }

        public ErrorResponse(String error, String message, List<TermRuleView> violations) {
            this(error, message, violations, null, null);
        }
    }
}
