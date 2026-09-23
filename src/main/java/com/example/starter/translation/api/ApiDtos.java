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

    /** 新段输入：新段键全局唯一，源文非空。 */
    public record NewSegmentInput(
            @NotBlank(message = "newSegmentId 不能为空") @Size(max = 64) String newSegmentId,
            @NotBlank(message = "sourceText 不能为空") String sourceText) {
    }

    /** 旧源段版本校验项。 */
    public record OldSegmentVersion(
            @NotBlank(message = "segmentId 不能为空") @Size(max = 64) String segmentId,
            @Positive(message = "sourceVersion 必须为正数") int sourceVersion) {
    }

    /** 某语言当前术语版本校验项。 */
    public record LanguageTermVersion(
            @NotBlank(message = "language 不能为空") String language,
            @PositiveOrZero(message = "termVersion 不能为负数") int termVersion) {
    }

    /** 某语言下一个新段到旧译文片段的有序来源映射。 */
    public record NewSegmentMapping(
            @NotBlank(message = "newSegmentId 不能为空") @Size(max = 64) String newSegmentId,
            @NotNull(message = "oldSegmentIds 不能为空") @Size(min = 1, max = 5)
            List<@NotBlank(message = "旧段 ID 不能为空") String> oldSegmentIds) {
    }

    /** 某语言全部新段的有序映射集合。 */
    public record LanguageMapping(
            @NotBlank(message = "language 不能为空") String language,
            @NotNull(message = "mappings 不能为空") @Size(min = 1, max = 5)
            List<@Valid NewSegmentMapping> mappings) {
    }

    /**
     * 结构修订请求：一次 changeKey 执行拆分（1→2~5）或合并（2~5→1），不允许混合。
     * 携带期望文档版本、各旧源段版本、涉及语言当前术语版本，以及每个目标语言的完整有序映射。
     */
    public record StructureChangeRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotBlank(message = "changeKey 不能为空") @Size(max = 128) String changeKey,
            @NotNull(message = "newSegments 不能为空") @Size(min = 1, max = 5)
            List<@Valid NewSegmentInput> newSegments,
            @NotNull(message = "oldSegments 不能为空") @Size(min = 1, max = 5)
            List<@Valid OldSegmentVersion> oldSegments,
            @NotNull(message = "languageTermVersions 不能为空") @Size(min = 1, max = 5)
            List<@Valid LanguageTermVersion> languageTermVersions,
            @NotNull(message = "mappings 不能为空") @Size(min = 1, max = 5)
            List<@Valid LanguageMapping> mappings,
            @Positive(message = "expectedDocumentVersion 必须为正数") int expectedDocumentVersion) {
    }

    /** 结构修订成功响应。 */
    public record StructureChangeResponse(long documentId, String changeKey, String changeType,
                                          int draftVersion, List<String> newSegmentIds) {
    }

    /** 当前结构中的段落视图。 */
    public record StructureSegmentView(String segmentId, String sourceText, int sourceVersion, int position) {
    }

    /** 当前结构只读视图。 */
    public record StructureResponse(long documentId, int draftVersion, List<String> targetLanguages,
                                    List<StructureSegmentView> segments) {
    }

    /** 一条有序血缘边视图。 */
    public record LineageEdgeView(String newSegmentId, String oldSegmentId, int ordinal) {
    }

    /** 某语言下新段的 REFERENCE 候选与片段边界视图。 */
    public record ReferenceCandidateView(String segmentId, String language, String content,
                                         List<Integer> fragmentBoundaries, List<LineageEdgeView> sources) {
    }

    /** 跨语言血缘只读视图：源段血缘 + 每语言译文血缘与 REFERENCE 候选。 */
    public record LineageResponse(long documentId, List<LineageEdgeView> sourceLineage,
                                  List<LanguageLineageView> languages) {
    }

    /** 单语言的译文血缘视图。 */
    public record LanguageLineageView(String language, List<ReferenceCandidateView> candidates) {
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
}
