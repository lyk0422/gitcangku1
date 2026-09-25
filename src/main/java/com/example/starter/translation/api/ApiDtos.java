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

    /**
     * 术语规则输入：sourceTerm 区分大小写，requiredTranslation 非空。
     * suppressed 为 true 时该文档规则取消同 sourceTerm+语言的全局规则（requiredTranslation 不参与校验）；
     * 缺省或 false 表示普通规则，整条覆盖同键全局规则。
     */
    public record TermRuleInput(
            @NotBlank(message = "sourceTerm 不能为空") @Size(max = 512) String sourceTerm,
            @NotBlank(message = "language 不能为空") String language,
            @NotBlank(message = "requiredTranslation 不能为空") @Size(max = 2048) String requiredTranslation,
            Boolean suppressed) {
    }

    /** 新增文档术语版本请求：携带期望的当前术语版本与完整规则集（0~100 条）。 */
    public record UpdateTermsRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @PositiveOrZero(message = "expectedTermVersion 不能为负数") int expectedTermVersion,
            @NotNull(message = "rules 不能为空") @Size(max = 100, message = "术语规则最多 100 条")
            List<@Valid TermRuleInput> rules) {
    }

    /** 提交全局术语库新版本请求：携带期望的当前全局版本与完整规则集（0~200 条），已有版本不可覆盖。 */
    public record UpdateGlobalTermsRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @PositiveOrZero(message = "expectedGlobalTermVersion 不能为负数") int expectedGlobalTermVersion,
            @NotNull(message = "rules 不能为空") @Size(max = 200, message = "全局术语规则最多 200 条")
            List<@Valid TermRuleInput> rules) {
    }

    /** 升级文档引用的全局术语库版本请求：须同时携带期望的文档术语版本与全局引用版本。 */
    public record UpgradeGlobalTermReferenceRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @PositiveOrZero(message = "expectedTermVersion 不能为负数") int expectedTermVersion,
            @PositiveOrZero(message = "expectedGlobalTermVersion 不能为负数") int expectedGlobalTermVersion) {
    }

    /** 建文档响应。 */
    public record DocumentResponse(long documentId, int draftVersion, int publishedVersion,
                                   List<String> targetLanguages) {
    }

    /** 段落相关写操作响应。 */
    public record SegmentResponse(long documentId, String segmentId, int sourceVersion, int draftVersion) {
    }

    /** 译文提交响应：含提交时绑定的文档术语版本与全局术语版本。 */
    public record TranslationResponse(long documentId, String segmentId, String language,
                                      int translationVersion, int sourceVersion, int termVersion,
                                      int globalTermVersion, int draftVersion) {
    }

    /** 译文批准响应。 */
    public record ApprovalResponse(long documentId, String segmentId, String language, String reviewer,
                                   int translationVersion, int sourceVersion) {
    }

    /** 发布响应。 */
    public record PublishResponse(long documentId, int publishedVersion) {
    }

    /** 文档术语规则视图：suppressed 为 true 表示抑制对应全局规则。 */
    public record TermRuleView(String sourceTerm, String language, String requiredTranslation,
                               boolean suppressed) {
    }

    /** 新增文档术语版本响应。 */
    public record TermVersionResponse(long documentId, int termVersion, int ruleCount, int draftVersion) {
    }

    /** 文档术语版本查询视图：版本号与完整规则集。 */
    public record TermVersionView(long documentId, int termVersion, List<TermRuleView> rules) {
    }

    /** 提交全局术语库新版本响应。 */
    public record GlobalTermVersionResponse(int globalTermVersion, int ruleCount) {
    }

    /** 全局术语库版本查询视图：版本号与完整规则集。 */
    public record GlobalTermVersionView(int globalTermVersion, List<TermRuleView> rules) {
    }

    /** 生效规则视图：逐条标明来源 GLOBAL、DOCUMENT 或 SUPPRESSED。 */
    public record EffectiveTermRuleView(String sourceTerm, String language, String requiredTranslation,
                                        String source) {
    }

    /** 文档生效规则集查询响应：固化的两个术语版本与按 sourceTerm+语言升序的完整生效集。 */
    public record EffectiveTermsResponse(long documentId, int termVersion, int globalTermVersion,
                                         List<EffectiveTermRuleView> rules) {
    }

    /** 升级全局术语引用响应。 */
    public record UpgradeGlobalTermReferenceResponse(long documentId, int termVersion,
                                                     int globalTermVersion, int draftVersion) {
    }

    /** 单条译文的术语状态：绑定的两个术语版本、是否过期及当前生效规则下的违规术语。 */
    public record TranslationTermStatus(String segmentId, String language, int translationVersion,
                                        int termVersion, int globalTermVersion, boolean termStale,
                                        List<EffectiveTermRuleView> violations) {
    }

    /** 译文术语状态查询响应：含文档当前引用的两个术语版本。 */
    public record TermStatusResponse(long documentId, int termVersion, int globalTermVersion,
                                     List<TranslationTermStatus> translations) {
    }

    /** 统一错误响应；violations 仅在术语违规 422 时返回，含全部违规术语及来源。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorResponse(String error, String message, List<EffectiveTermRuleView> violations) {
        public ErrorResponse(String error, String message) {
            this(error, message, null);
        }
    }
}
