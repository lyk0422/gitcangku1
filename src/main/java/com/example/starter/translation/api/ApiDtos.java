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

    /** 统一错误响应；violations 仅在术语违规 422 时返回，含全部违规术语。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorResponse(String error, String message, List<TermRuleView> violations) {
        public ErrorResponse(String error, String message) {
            this(error, message, null);
        }
    }

    /** 创建术语版本退役单请求：左闭右开 UTC 生效窗口（ISO-8601），同语言替代版本与文档内唯一 retirementKey。 */
    public record CreateRetirementRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @Positive(message = "termVersion 必须为正数") int termVersion,
            @NotBlank(message = "language 不能为空") String language,
            @Positive(message = "replacementVersion 必须为正数") int replacementVersion,
            @NotBlank(message = "effectiveFromUtc 不能为空") String effectiveFromUtc,
            @NotBlank(message = "effectiveToUtc 不能为空") String effectiveToUtc,
            @NotBlank(message = "retirementKey 不能为空") @Size(max = 128) String retirementKey) {
    }

    /** 激活退役单请求。 */
    public record ActivateRetirementRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId) {
    }

    /** 实际命中的术语位置：sourceTerm 在源文中的字符偏移区间（左闭右开）。 */
    public record HitTermView(String sourceTerm, int startOffset, int endOffset) {
    }

    /** 退役影响条目：DRAFT/APPROVED 按段落+语言，PUBLISHED 按发布版本+段落。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ImpactEntryView(String kind, Integer publishedVersion, String segmentId, String language,
                                  List<HitTermView> hitTerms) {
    }

    /** 退役单视图：窗口、状态与影响清单（创建为预览，激活后为冻结快照，稳定排序）。 */
    public record RetirementView(long documentId, String retirementKey, int termVersion, String language,
                                 int replacementVersion, String effectiveFromUtc, String effectiveToUtc,
                                 String status, List<ImpactEntryView> impact) {
    }

    /** 逐段替换结果：某段落某语言草稿的新正文。 */
    public record MigrationEntryInput(
            @NotBlank(message = "segmentId 不能为空") @Size(max = 64) String segmentId,
            @NotBlank(message = "language 不能为空") String language,
            @NotBlank(message = "content 不能为空") String content) {
    }

    /**
     * 草稿迁移命令：一次覆盖预览中仍受影响的全部草稿；
     * expectedVersion 为期望的文档草稿版本（乐观校验，不符 409）。
     */
    public record MigrateDraftsRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @Positive(message = "expectedVersion 必须为正数") int expectedVersion,
            @NotNull(message = "entries 不能为空") List<@Valid MigrationEntryInput> entries) {
    }

    /** 单条草稿迁移结果：增版后的译文版本、旧/新文本摘要与规则版本。 */
    public record MigrationResultView(String segmentId, String language, int translationVersion,
                                      String oldSummary, String newSummary, int ruleVersion) {
    }

    /** 草稿迁移响应。 */
    public record MigrateDraftsResponse(long documentId, String retirementKey, int migratedCount,
                                        int draftVersion, List<MigrationResultView> migrations) {
    }
}
