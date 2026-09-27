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
            @Positive(message = "sourceVersion 必须为正数") int sourceVersion,
            List<@Valid AnchorMappingInput> anchorMappings) {
    }

    /** 锚点区间迁移映射：译文修订后区间变动时，按锚点 ID 给出新区间（左闭右开码点偏移）。 */
    public record AnchorMappingInput(
            @Positive(message = "anchorId 必须为正数") long anchorId,
            @PositiveOrZero(message = "startOffset 不能为负数") int startOffset,
            @Positive(message = "endOffset 必须为正数") int endOffset) {
    }

    /** 锚点登记输入：原始引用标识、左闭右开码点区间与锁定原因。 */
    public record AnchorInput(
            @NotBlank(message = "citationId 不能为空") @Size(max = 512) String citationId,
            @PositiveOrZero(message = "startOffset 不能为负数") int startOffset,
            @Positive(message = "endOffset 必须为正数") int endOffset,
            @NotBlank(message = "lockReason 不能为空") @Size(max = 2048) String lockReason) {
    }

    /** 锚点登记请求：一次登记一个或多个锚点。 */
    public record RegisterAnchorsRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotNull(message = "anchors 不能为空") @Size(min = 1, max = 100, message = "每次登记 1~100 个锚点")
            List<@Valid AnchorInput> anchors) {
    }

    /** 批量修订单条：段落+语言的新译文；区间变动的锚点须给一一映射。 */
    public record BatchTranslationItem(
            @NotBlank(message = "segmentId 不能为空") @Size(max = 64) String segmentId,
            @NotBlank(message = "language 不能为空") String language,
            @NotBlank(message = "content 不能为空") String content,
            @Positive(message = "sourceVersion 必须为正数") int sourceVersion,
            List<@Valid AnchorMappingInput> anchorMappings) {
    }

    /** 批量修订请求：先整批校验锚点映射与术语版本，全部通过后一次事务提交。 */
    public record BatchReviseRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @PositiveOrZero(message = "expectedTermVersion 不能为负数") int expectedTermVersion,
            @NotNull(message = "revisions 不能为空") @Size(min = 1, max = 100, message = "每批 1~100 条修订")
            List<@Valid BatchTranslationItem> revisions) {
    }

    /** 解除锚点请求：法务确认人写入不可变理由。 */
    public record ReleaseAnchorRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotBlank(message = "reason 不能为空") @Size(max = 2048) String reason) {
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

    /** 引文锚点视图：区间为码点偏移、左闭右开；解除字段未解除时为 null。 */
    public record AnchorView(long anchorId, String segmentId, String language, String citationId,
                             String normalizedCitationId, int startOffset, int endOffset, String quoteText,
                             int translationVersion, String lockReason, String createdBy, String createdAt,
                             String status, String migratedBy, String migratedAt,
                             String releasedBy, String releasedReason, String releasedAt) {
    }

    /** 锚点登记响应：返回全部已登记锚点及登记后的草稿版本。 */
    public record AnchorRegistrationResponse(long documentId, int draftVersion, List<AnchorView> anchors) {
    }

    /** 批量修订中单条结果。 */
    public record BatchRevisionResult(String segmentId, String language, int translationVersion,
                                      int sourceVersion, int termVersion) {
    }

    /** 批量修订响应：整批一个事务提交，返回每条的新译文版本与草稿版本。 */
    public record BatchReviseResponse(long documentId, int draftVersion, List<BatchRevisionResult> revisions) {
    }

    /** 锚点事件历史视图。 */
    public record AnchorEventView(long eventId, long anchorId, String eventType, int translationVersion,
                                  int startOffset, int endOffset, String actor, String reason, String eventAt) {
    }

    /** 锚点明细视图：当前状态加完整事件历史。 */
    public record AnchorDetailResponse(AnchorView anchor, List<AnchorEventView> history) {
    }

    /**
     * 单个段落译文的锚点一致性诊断（只读）：actualCount 为当前生效锚点数，
     * requiredCount 为内容长度下至少应有的非空区间数（等于生效锚点数），difference 为二者差额（始终为 0），
     * 其余字段列出区间越界、引用文本漂移、区间交叉与重复引用标识等问题锚点。
     */
    public record AnchorMappingDiagnostic(String segmentId, String language, int actualCount, int requiredCount,
                                          int difference, List<Long> outOfRangeAnchorIds,
                                          List<Long> quoteMismatchAnchorIds, List<String> crossingPairs,
                                          List<String> duplicateCitationKeys) {
    }

    /** 锚点诊断查询响应：逐段落语言列出生效锚点与当前译文的一致性结论（只读）。 */
    public record AnchorDiagnosticsResponse(long documentId, List<AnchorMappingDiagnostic> items) {
    }

    /** 统一错误响应；violations 仅在术语违规 422 时返回，含全部违规术语。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorResponse(String error, String message, List<TermRuleView> violations) {
        public ErrorResponse(String error, String message) {
            this(error, message, null);
        }
    }
}
