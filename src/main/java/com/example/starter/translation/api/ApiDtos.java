package com.example.starter.translation.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

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

    /** 结构修订新段输入：新段键在文档全部历史段中唯一，源文非空。 */
    public record NewSegmentInput(
            @NotBlank(message = "新段 segmentId 不能为空") @Size(max = 64) String segmentId,
            @NotBlank(message = "新段 sourceText 不能为空") String sourceText) {
    }

    /**
     * 旧译文片段输入：相对某旧段当前译文的字符区间（UTF-16 代码单元偏移，含头不含尾）。
     * 省略偏移（startOffset/endOffset 为 null）表示整段旧译文。
     */
    public record FragmentInput(
            @NotBlank(message = "片段来源 oldSegmentId 不能为空") String oldSegmentId,
            @PositiveOrZero(message = "startOffset 不能为负数") Integer startOffset,
            @PositiveOrZero(message = "endOffset 不能为负数") Integer endOffset) {
    }

    /** 一个新段在某语言下的有序来源映射。 */
    public record NewSegmentMappingInput(
            @NotBlank(message = "映射目标 newSegmentId 不能为空") String newSegmentId,
            @NotNull(message = "fragments 不能为空") List<@Valid FragmentInput> fragments) {
    }

    /**
     * 结构修订事务请求：SPLIT 把 1 个当前段拆为 2~5 段；MERGE 把 2~5 个连续当前段合为 1 段。
     * segmentIds 为旧段（合并时须按当前结构顺序提交），expectedSourceVersions 为各旧源段版本，
     * languageMappings 为每个目标语言新段到旧译文片段的有序来源映射，语言集合须与文档目标语言完全一致。
     */
    public record StructureRevisionRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotBlank(message = "changeKey 不能为空") @Size(max = 128) String changeKey,
            @NotBlank(message = "changeType 不能为空") String changeType,
            @Positive(message = "expectedDocumentVersion 必须为正数") int expectedDocumentVersion,
            @PositiveOrZero(message = "expectedTermVersion 不能为负数") int expectedTermVersion,
            @NotNull(message = "segmentIds 不能为空") @Size(min = 1, max = 5, message = "一次只能修订 1~5 个旧段")
            List<@NotBlank(message = "旧段 segmentId 不能为空") String> segmentIds,
            @NotNull(message = "expectedSourceVersions 不能为空")
            Map<@NotBlank(message = "expectedSourceVersions 键不能为空") String,
                    @Positive(message = "旧源段版本必须为正数") Integer> expectedSourceVersions,
            @NotNull(message = "newSegments 不能为空") @Size(min = 1, max = 5, message = "一次只能产生 1~5 个新段")
            List<@Valid NewSegmentInput> newSegments,
            @NotNull(message = "languageMappings 不能为空")
            @Size(min = 1, max = 5, message = "语言映射须为 1~5 种")
            Map<@NotBlank(message = "语言码不能为空") String,
                    @NotNull(message = "语言映射不能为空") List<@Valid NewSegmentMappingInput>> languageMappings) {
    }

    /** 片段边界视图：来源旧段、有序序号及字符偏移（含头不含尾）。 */
    public record FragmentBoundaryView(String oldSegmentId, int ordinal, int startOffset, int endOffset) {
    }

    /** 某语言一个新段的参考候选视图：旧译文片段拼接正文与片段边界。 */
    public record ReferenceCandidateView(String newSegmentId, String language, String content,
                                        List<FragmentBoundaryView> boundaries) {
    }

    /** 结构修订成功响应。 */
    public record StructureRevisionResponse(long documentId, String changeKey, String changeType,
                                            int documentVersion, List<String> oldSegmentIds,
                                            List<String> newSegmentIds, List<String> languages,
                                            List<ReferenceCandidateView> references) {
    }

    /** 当前结构中的一个段视图。 */
    public record StructureSegmentView(String segmentId, String sourceText, int sourceVersion, int position,
                                       String createdChangeKey) {
    }

    /** 当前结构只读视图：草稿/术语版本与按序排列的当前段。 */
    public record StructureView(long documentId, int draftVersion, int termVersion, List<String> targetLanguages,
                                List<StructureSegmentView> segments) {
    }

    /** 结构修订摘要视图。 */
    public record StructureChangeSummaryView(String changeKey, String changeType, int documentVersion,
                                             int expectedDocumentVersion, int expectedTermVersion) {
    }

    /** 源段血缘链接视图：旧段到新段及次序。 */
    public record SegmentLinkView(String oldSegmentId, String newSegmentId, int ordinal) {
    }

    /** 某语言一个新段的译文片段血缘视图。 */
    public record LanguageLineageView(String language, String newSegmentId,
                                      List<FragmentBoundaryView> fragments) {
    }

    /** 结构修订详情视图：源段血缘、跨语言片段血缘与参考候选。 */
    public record StructureChangeView(String changeKey, String changeType, int documentVersion,
                                      int expectedDocumentVersion, int expectedTermVersion,
                                      List<String> oldSegmentIds, List<String> newSegmentIds,
                                      List<SegmentLinkView> segmentLineage,
                                      List<LanguageLineageView> translationLineage,
                                      List<ReferenceCandidateView> references) {
    }

    /**
     * 单段跨语言血缘视图：direction=FORWARD 时该段为被取代旧段，BACKWARD 时为结构修订产生的新段，
     * NONE 表示该段尚未参与任何结构修订。
     */
    public record SegmentLineageView(long documentId, String segmentId, String status, String direction,
                                     String changeKey, List<SegmentLinkView> segmentLineage,
                                     List<LanguageLineageView> translationLineage) {
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
