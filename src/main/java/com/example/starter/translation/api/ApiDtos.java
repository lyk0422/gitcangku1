package com.example.starter.translation.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 多语种段落发布 API 的请求与响应体。所有写操作均携带全局唯一 requestId。
 * 冲突区间与声明/解除时刻统一为 UTC 毫秒时间戳，区间左闭右开。
 */
public final class ApiDtos {

    private ApiDtos() {
    }

    /** 初始段落输入。 */
    public record SegmentInput(
            @NotBlank(message = "segmentId 不能为空") @Size(max = 64) String segmentId,
            @NotBlank(message = "sourceText 不能为空") String sourceText) {
    }

    /** 建文档请求：1~5 种目标语言，可携带初始段落；requiredReviewCount 缺省 1。 */
    public record CreateDocumentRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotNull(message = "targetLanguages 不能为空") @Size(min = 1, max = 5, message = "目标语言须为 1~5 种")
            List<@NotBlank(message = "语言码不能为空") String> targetLanguages,
            @NotNull(message = "segments 不能为空") List<@Valid SegmentInput> segments,
            @Min(value = 1, message = "requiredReviewCount 须为 1~5")
            @Max(value = 5, message = "requiredReviewCount 须为 1~5") Integer requiredReviewCount) {
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

    /** 译文批准请求；评审人取 X-Actor-Id 请求头。 */
    public record ApproveTranslationRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @Positive(message = "translationVersion 必须为正数") int translationVersion) {
    }

    /** 译文否决请求；评审人取 X-Actor-Id 请求头，否决说明可空。 */
    public record RejectTranslationRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @Positive(message = "translationVersion 必须为正数") int translationVersion,
            @Size(max = 1024) String reason) {
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

    /**
     * 单条冲突声明请求；评审人取 X-Actor-Id 请求头。
     * 区间倒置、原因空白等业务校验在服务层统一预校验（与批量一致），返回 422。
     */
    public record DeclareConflictRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotBlank(message = "language 不能为空") String language,
            @NotNull(message = "validFrom 不能为空") @PositiveOrZero(message = "validFrom 不能为负数") Long validFrom,
            @NotNull(message = "validTo 不能为空") @PositiveOrZero(message = "validTo 不能为负数") Long validTo,
            @Size(max = 1024, message = "reason 最长 1024 字符") String reason) {
    }

    /** 批量冲突声明中的一项；与单条声明同规则。 */
    public record ConflictDeclarationInput(
            @NotBlank(message = "language 不能为空") String language,
            @NotNull(message = "validFrom 不能为空") @PositiveOrZero(message = "validFrom 不能为负数") Long validFrom,
            @NotNull(message = "validTo 不能为空") @PositiveOrZero(message = "validTo 不能为负数") Long validTo,
            @Size(max = 1024, message = "reason 最长 1024 字符") String reason) {
    }

    /** 批量冲突声明请求：任一非法整批不改变评审资格。 */
    public record DeclareConflictsBatchRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotNull(message = "declarations 不能为空")
            @Size(min = 1, max = 100, message = "批量声明须为 1~100 条")
            List<@Valid ConflictDeclarationInput> declarations) {
    }

    /** 解除冲突请求；确认人取 X-Actor-Id，角色取 X-Actor-Role（须为 COMPLIANCE 且不同于声明人）。 */
    public record LiftConflictRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @Size(max = 1024, message = "liftReason 最长 1024 字符") String liftReason) {
    }

    /** 建文档响应。 */
    public record DocumentResponse(long documentId, int draftVersion, int publishedVersion,
                                   List<String> targetLanguages, int requiredReviewCount) {
    }

    /** 段落相关写操作响应。 */
    public record SegmentResponse(long documentId, String segmentId, int sourceVersion, int draftVersion) {
    }

    /** 译文提交响应。 */
    public record TranslationResponse(long documentId, String segmentId, String language,
                                      int translationVersion, int sourceVersion, int termVersion,
                                      int draftVersion) {
    }

    /** 评审（批准/否决）提交响应。 */
    public record ReviewResponse(long documentId, String segmentId, String language, String reviewer,
                                 String decision, String status, int translationVersion, int sourceVersion) {
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

    /**
     * 冲突声明视图：固化区间、原因、声明与解除信息；durationMillis 为区间实际时长（UTC 毫秒），
     * active 表示当前时刻命中有效区间（DECLARED 且左闭右开覆盖当前时刻）。
     */
    public record ConflictView(long conflictId, long documentId, String language, String reviewer,
                               long validFrom, long validTo, long durationMillis, String reason, String status,
                               String declaredBy, long declaredAt, String liftedBy, Long liftedAt,
                               String liftReason, boolean active) {
    }

    /** 批量冲突声明响应：整批成功后的全部声明视图，顺序与请求一致。 */
    public record DeclareConflictsResponse(long documentId, List<ConflictView> conflicts) {
    }

    /** 冲突明细查询响应。 */
    public record ConflictListResponse(long documentId, List<ConflictView> conflicts) {
    }

    /** 冲突历史视图：声明与解除的只追加记录。 */
    public record ConflictHistoryView(long historyId, long conflictId, long documentId, String action,
                                      String actor, String actorRole, String reason,
                                      long validFrom, long validTo, long createdAt) {
    }

    /** 冲突历史查询响应。 */
    public record ConflictHistoryResponse(long documentId, List<ConflictHistoryView> history) {
    }

    /**
     * 评审明细视图（诊断用）：effective 表示当前是否计入法定人数
     * （ACTIVE、未命中当前有效冲突、且版本与当前译文/源文一致）。
     */
    public record ReviewDiagnosticsView(String segmentId, String language, String reviewer, String decision,
                                        String status, String reason, int sourceVersion, int translationVersion,
                                        Long conflictId, String conflictReason,
                                        Long conflictValidFrom, Long conflictValidTo, boolean effective) {
    }

    /** 单条译文的评审诊断：法定人数要求值、当前有效批准实际值与缺失差额。 */
    public record TranslationReviewDiagnostics(String segmentId, String language, int translationVersion,
                                               int sourceVersion, int requiredReviewCount,
                                               int effectiveApprovals, int missingApprovals,
                                               List<ReviewDiagnosticsView> reviews) {
    }

    /** 评审诊断查询响应。 */
    public record ReviewDiagnosticsResponse(long documentId, int requiredReviewCount,
                                            List<TranslationReviewDiagnostics> translations) {
    }

    /**
     * 统一错误响应；violations 仅在术语违规 422 时返回，含全部违规术语；
     * missingReviews/affectedSegments 仅在法定人数不足 422 时返回，分别为缺失评审总数与受影响段落（segmentId/language）。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorResponse(String error, String message, List<TermRuleView> violations,
                                Integer missingReviews, List<String> affectedSegments) {
        public ErrorResponse(String error, String message) {
            this(error, message, null, null, null);
        }

        public ErrorResponse(String error, String message, List<TermRuleView> violations) {
            this(error, message, violations, null, null);
        }
    }
}
