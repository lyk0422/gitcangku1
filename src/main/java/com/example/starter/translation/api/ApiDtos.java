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

    /** 占位符声明请求：同时给出新源文与规范排序的占位符集合，整体创建新源版本。 */
    public record DeclarePlaceholdersRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotBlank(message = "sourceText 不能为空") String sourceText,
            @NotNull(message = "placeholders 不能为空") @Size(max = 100, message = "占位符最多 100 个")
            List<@NotBlank(message = "占位符不能为空") @Size(max = 128) String> placeholders) {
    }

    /** 批量批准项：段落、语言与所针对的译文版本。 */
    public record BatchApproveItem(
            @NotBlank(message = "segmentId 不能为空") @Size(max = 64) String segmentId,
            @NotBlank(message = "language 不能为空") String language,
            @Positive(message = "translationVersion 必须为正数") int translationVersion) {
    }

    /** 批量批准请求：任一段不完整时整次不改变批准状态。 */
    public record BatchApproveRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotNull(message = "items 不能为空") @Size(min = 1, max = 100, message = "批量批准项须为 1~100 条")
            List<@Valid BatchApproveItem> items) {
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

    /** 占位符声明响应：新源版本、占位符版本与规范排序后的占位符集合。 */
    public record PlaceholderResponse(long documentId, String segmentId, int sourceVersion,
                                      int placeholderVersion, List<String> placeholders, int draftVersion) {
    }

    /** 占位符违规明细：type 为 MISSING/EXTRA/COUNT_MISMATCH，含要求与实际出现次数。 */
    public record PlaceholderViolation(String placeholder, String type, int requiredCount, int actualCount) {
    }

    /** 批量批准响应：审核人、实际批准条数与逐项批准结果。 */
    public record BatchApproveResponse(long documentId, String reviewer, int approvedCount,
                                       List<ApprovalResponse> approvals) {
    }

    /** 占位符及要求出现次数视图。 */
    public record PlaceholderCountView(String placeholder, int requiredCount) {
    }

    /** 单条译文的占位符诊断：是否满足当前声明及全部违规明细（无违规为空列表）。 */
    public record TranslationPlaceholderStatus(String language, int translationVersion, int sourceVersion,
                                               boolean placeholderStale, List<PlaceholderViolation> violations) {
    }

    /** 段落占位符明细与诊断查询视图：当前声明集合及各语言译文完整性。 */
    public record PlaceholderStatusView(long documentId, String segmentId, int placeholderVersion,
                                        List<PlaceholderCountView> placeholders,
                                        List<TranslationPlaceholderStatus> translations) {
    }

    /** 统一错误响应；violations 仅在术语违规 422 时返回，placeholderViolations 仅在占位符违规 422 时返回。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorResponse(String error, String message, List<TermRuleView> violations,
                                List<PlaceholderViolation> placeholderViolations) {
        public ErrorResponse(String error, String message) {
            this(error, message, null, null);
        }

        public ErrorResponse(String error, String message, List<TermRuleView> violations) {
            this(error, message, violations, null);
        }
    }
}
