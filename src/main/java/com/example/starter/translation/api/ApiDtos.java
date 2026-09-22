package com.example.starter.translation.api;

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

    /** 术语规则输入：目标语言内 sourceTerm 唯一，requiredTranslation 非空。 */
    public record TermRuleInput(
            @NotBlank(message = "language 不能为空") @Size(max = 16) String language,
            @NotBlank(message = "sourceTerm 不能为空") @Size(max = 512) String sourceTerm,
            @NotBlank(message = "requiredTranslation 不能为空") String requiredTranslation) {
    }

    /**
     * 新增术语版本请求：expectedTermVersion 做乐观校验，rules 为 0~100 条的完整规则集快照。
     * 成功后术语版本加一，已有版本不可覆盖。
     */
    public record UpdateTermsRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @PositiveOrZero(message = "expectedTermVersion 不能为负数") int expectedTermVersion,
            @NotNull(message = "rules 不能为空")
            @Size(max = 100, message = "单个术语版本最多 100 条规则")
            List<@Valid TermRuleInput> rules) {
    }

    /** 建文档响应。 */
    public record DocumentResponse(long documentId, int draftVersion, int publishedVersion,
                                   List<String> targetLanguages) {
    }

    /** 段落相关写操作响应。 */
    public record SegmentResponse(long documentId, String segmentId, int sourceVersion, int draftVersion) {
    }

    /** 译文提交响应；termVersion 为本次提交绑定的术语版本。 */
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

    /** 术语规则输出（查询响应中的不可变规则）。 */
    public record TermRuleResponse(String language, String sourceTerm, String requiredTranslation) {
    }

    /** 术语版本响应：版本号、规则数与完整不可变规则集，按提交顺序返回。 */
    public record TermVersionResponse(long documentId, int termVersion, int draftVersion,
                                      List<TermRuleResponse> rules) {
    }

    /** 单条违规术语：源文命中 sourceTerm 但译文缺少 requiredTranslation。 */
    public record TermViolation(String segmentId, String language,
                                String sourceTerm, String requiredTranslation) {
    }

    /**
     * 译文术语状态：译文绑定的术语版本、是否为当前版本、当前源文命中规则的校验结果。
     * current=true 且 violations 为空表示满足发布的术语条件；无译文时 translationVersion 为 0。
     */
    public record TranslationTermStatus(long documentId, String segmentId, String language,
                                        boolean hasTranslation, int translationVersion,
                                        int boundTermVersion, int currentTermVersion, boolean current,
                                        List<TermViolation> violations) {
    }

    /** 统一错误响应；违规拦截时 violations 携带全部违规术语，其余场景为 null。 */
    public record ErrorResponse(String error, String message, List<TermViolation> violations) {
        public ErrorResponse(String error, String message) {
            this(error, message, null);
        }
    }
}
