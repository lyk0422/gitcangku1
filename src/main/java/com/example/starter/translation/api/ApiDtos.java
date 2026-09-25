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
     * 术语规则输入：sourceTerm 区分大小写。
     * suppressed=false（默认）时 requiredTranslation 非空；suppressed=true 时必须不携带必译文本，
     * 表示取消所引用全局版本中同 sourceTerm+语言的全局规则。
     */
    @ValidTermRule
    public record TermRuleInput(
            @NotBlank(message = "sourceTerm 不能为空") @Size(max = 512) String sourceTerm,
            @NotBlank(message = "language 不能为空") String language,
            @Size(max = 2048) String requiredTranslation,
            boolean suppressed) {
    }

    /** 新增文档术语版本请求：携带期望的当前文档术语版本与完整规则集（0~100 条）。 */
    public record UpdateTermsRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @PositiveOrZero(message = "expectedTermVersion 不能为负数") int expectedTermVersion,
            @NotNull(message = "rules 不能为空") @Size(max = 100, message = "术语规则最多 100 条")
            List<@Valid TermRuleInput> rules) {
    }

    /** 全局术语规则输入：全局库不属于任何文档，规则不支持 suppressed，requiredTranslation 非空。 */
    public record GlobalTermRuleInput(
            @NotBlank(message = "sourceTerm 不能为空") @Size(max = 512) String sourceTerm,
            @NotBlank(message = "language 不能为空") String language,
            @NotBlank(message = "requiredTranslation 不能为空") @Size(max = 2048) String requiredTranslation) {
    }

    /** 新增全局术语库版本请求：携带期望的当前全局版本与完整规则集（0~200 条）。 */
    public record CreateGlobalTermsRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @PositiveOrZero(message = "expectedGlobalTermVersion 不能为负数") int expectedGlobalTermVersion,
            @NotNull(message = "rules 不能为空") @Size(max = 200, message = "全局术语规则最多 200 条")
            List<@Valid GlobalTermRuleInput> rules) {
    }

    /**
     * 全局术语引用升级请求：expectedGlobalTermVersion 必须等于文档当前引用版本，
     * targetGlobalTermVersion 必须存在且严格大于当前引用版本；升级使 draftVersion 加一。
     */
    public record UpgradeGlobalReferenceRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @PositiveOrZero(message = "expectedGlobalTermVersion 不能为负数") int expectedGlobalTermVersion,
            @Positive(message = "targetGlobalTermVersion 必须为正数") int targetGlobalTermVersion) {
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
                                      int globalTermVersion, int draftVersion) {
    }

    /** 译文批准响应。 */
    public record ApprovalResponse(long documentId, String segmentId, String language, String reviewer,
                                   int translationVersion, int sourceVersion) {
    }

    /** 发布响应。 */
    public record PublishResponse(long documentId, int publishedVersion) {
    }

    /** 文档术语规则视图：版本快照中的原始规则，suppressed=true 时 requiredTranslation 为 null。 */
    public record TermRuleView(String sourceTerm, String language, String requiredTranslation, boolean suppressed) {
    }

    /** 新增文档术语版本响应。 */
    public record TermVersionResponse(long documentId, int termVersion, int ruleCount, int draftVersion) {
    }

    /** 术语版本查询视图：文档术语版本号与完整原始规则集。 */
    public record TermVersionView(long documentId, int termVersion, List<TermRuleView> rules) {
    }

    /** 单条生效规则视图：合成后规则及来源 GLOBAL/DOCUMENT/SUPPRESSED；SUPPRESSED 时 requiredTranslation 为 null。 */
    public record EffectiveTermRuleView(String sourceTerm, String language, String requiredTranslation,
                                        String source) {
    }

    /** 生效规则集查询响应：固化文档引用的全局版本、文档术语版本与逐条标明来源的合成规则。 */
    public record EffectiveTermSetResponse(long documentId, int globalTermVersion, int termVersion,
                                           List<EffectiveTermRuleView> rules) {
    }

    /** 单条术语违规明细：违规规则及来源（GLOBAL 或 DOCUMENT）。 */
    public record TermViolationView(String sourceTerm, String language, String requiredTranslation,
                                    String source) {
    }

    /** 新增全局术语库版本响应。 */
    public record GlobalTermVersionResponse(int globalTermVersion, int ruleCount) {
    }

    /** 全局术语版本查询视图：版本号与完整规则集（不可变快照）。 */
    public record GlobalTermVersionView(int globalTermVersion, List<GlobalTermRuleView> rules) {
    }

    /** 全局术语规则视图。 */
    public record GlobalTermRuleView(String sourceTerm, String language, String requiredTranslation) {
    }

    /** 文档全局术语引用升级响应。 */
    public record GlobalReferenceResponse(long documentId, int globalTermVersion, int termVersion,
                                          int draftVersion) {
    }

    /** 单条译文的术语状态：绑定的两个术语版本、是否过期及当前生效规则下的违规术语。 */
    public record TranslationTermStatus(String segmentId, String language, int translationVersion,
                                        int termVersion, int globalTermVersion, boolean termStale,
                                        List<TermViolationView> violations) {
    }

    /** 译文术语状态查询响应。 */
    public record TermStatusResponse(long documentId, int globalTermVersion, int termVersion,
                                     List<TranslationTermStatus> translations) {
    }

    /** 统一错误响应；violations 仅在术语违规 422 时返回，含全部违规术语及来源。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorResponse(String error, String message, List<TermViolationView> violations) {
        public ErrorResponse(String error, String message) {
            this(error, message, null);
        }
    }
}
