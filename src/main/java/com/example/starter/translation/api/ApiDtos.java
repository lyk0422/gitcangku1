package com.example.starter.translation.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
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

    /** 列车语言候选输入：候选译文版本与期望的当前发布指针。 */
    public record TrainLocaleInput(
            @NotBlank(message = "locale 不能为空") String locale,
            @Positive(message = "translationVersion 必须为正数") int translationVersion,
            @PositiveOrZero(message = "expectedVersion 不能为负数") int expectedVersion) {
    }

    /** 创建发布列车请求：2~20 个唯一语言，集合须与文档目标语言完全一致。 */
    public record CreateReleaseTrainRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotBlank(message = "trainKey 不能为空") @Size(max = 128) String trainKey,
            @Positive(message = "sourceDocumentVersion 必须为正数") int sourceDocumentVersion,
            @NotNull(message = "plannedAt 不能为空") OffsetDateTime plannedAt,
            @NotNull(message = "locales 不能为空") @Size(min = 2, max = 20, message = "列车语言须为 2~20 个")
            List<@Valid TrainLocaleInput> locales) {
    }

    /** 列车状态变更请求（READY / 取消 / 激活共用，仅携带 requestId）。 */
    public record TrainTransitionRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId) {
    }

    /** 列车语言候选视图。 */
    public record TrainLocaleView(String locale, int candidateTranslationVersion, int expectedVersion) {
    }

    /** 发布列车视图：状态、冻结的术语版本与源文摘要、激活后推进到的版本。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TrainResponse(long trainId, String trainKey, long documentId, String status,
                                int sourceDocumentVersion, OffsetDateTime plannedAt,
                                Integer termVersion, String sourceDigest, Integer releaseTrainVersion,
                                List<TrainLocaleView> locales) {
    }

    /** 列车列表响应，按 trainKey 稳定排序。 */
    public record TrainListResponse(long documentId, List<TrainResponse> trains) {
    }

    /** 源段版本差异：译文所依据源文版本与当前源文版本不一致。 */
    public record SourceVersionDiff(String segmentId, int translationSourceVersion, int currentSourceVersion) {
    }

    /** 逐语言预检结果：缺段、候选版本不符、源段版本差异、未批准、术语过期/违规与当前发布指针。 */
    public record LocalePrecheck(String locale, int candidateTranslationVersion, int expectedVersion,
                                 int currentPointer, boolean pointerMismatch,
                                 List<String> missingSegments, List<String> candidateMismatches,
                                 List<SourceVersionDiff> sourceVersionDiffs, List<String> unapprovedSegments,
                                 boolean termStale, List<TermRuleView> termViolations) {
        /** 内容侧是否全部通过（不含发布指针，指针仅在激活时强校验）。 */
        public boolean contentReady() {
            return missingSegments.isEmpty() && candidateMismatches.isEmpty()
                    && sourceVersionDiffs.isEmpty() && unapprovedSegments.isEmpty()
                    && !termStale && termViolations.isEmpty();
        }
    }

    /** 列车预检响应：逐语言明细与是否可进入 READY；预检不写数据。 */
    public record PrecheckResponse(long trainId, String trainKey, long documentId, String status,
                                   int termVersion, boolean ready, List<LocalePrecheck> locales) {
    }

    /** 发布指针视图。 */
    public record ReleasePointerView(String locale, int releasedVersion) {
    }

    /** 发布指针查询响应，按语言码稳定排序。 */
    public record ReleasePointerListResponse(long documentId, int trainReleaseVersion,
                                             List<ReleasePointerView> pointers) {
    }
}
