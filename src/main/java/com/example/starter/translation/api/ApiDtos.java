package com.example.starter.translation.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
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

    /** 译文提交请求；作者取 X-Actor-Id 请求头；存在生效锚点时可随提交携带旧锚点到新区间的一一映射。 */
    public record SubmitTranslationRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotBlank(message = "content 不能为空") String content,
            @Positive(message = "sourceVersion 必须为正数") int sourceVersion,
            List<@Valid AnchorMappingInput> anchorMappings) {
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

    /** 引文锚点登记输入：规范化引用标识、左闭右开字符区间与锁定原因。 */
    public record AnchorInput(
            @NotBlank(message = "citationKey 不能为空") @Size(max = 512) String citationKey,
            @PositiveOrZero(message = "startOffset 不能为负数") int startOffset,
            @Positive(message = "endOffset 必须为正数") int endOffset,
            @NotBlank(message = "lockReason 不能为空") @Size(max = 2048) String lockReason) {
    }

    /** 锚点登记请求：可一次登记一个或多个锚点，区间必须落在指定的已批准译文版本内。 */
    public record RegisterAnchorsRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @Positive(message = "translationVersion 必须为正数") int translationVersion,
            @NotNull(message = "anchors 不能为空") @Size(min = 1, max = 100, message = "每次登记 1~100 个锚点")
            List<@Valid AnchorInput> anchors) {
    }

    /** 旧锚点到新锚点区间的一一映射；引用标识不变，仅提供变动后的区间。 */
    public record AnchorMappingInput(
            @NotBlank(message = "citationKey 不能为空") @Size(max = 512) String citationKey,
            @PositiveOrZero(message = "startOffset 不能为负数") int startOffset,
            @Positive(message = "endOffset 必须为正数") int endOffset) {
    }

    /** 批量修订中的单条译文修订；mappings 可省略（区间未变时），但锚点文本必须全部保留。 */
    public record SegmentRevisionInput(
            @NotBlank(message = "segmentId 不能为空") @Size(max = 64) String segmentId,
            @NotBlank(message = "language 不能为空") String language,
            @NotBlank(message = "content 不能为空") String content,
            @Positive(message = "sourceVersion 必须为正数") int sourceVersion,
            List<@Valid AnchorMappingInput> anchorMappings) {
    }

    /** 批量译文修订请求：先整批校验锚点映射与术语版本，任一失败整次回滚。 */
    public record BatchReviseTranslationsRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotNull(message = "revisions 不能为空") @Size(min = 1, max = 50, message = "每批 1~50 条译文修订")
            List<@Valid SegmentRevisionInput> revisions) {
    }

    /** 锚点解除请求：须由异于创建人的法务角色确认并写入不可变理由。 */
    public record ReleaseAnchorRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotBlank(message = "reason 不能为空") @Size(max = 2048) String reason) {
    }

    /** 锚点明细视图；解除相关字段未解除时为 null。 */
    public record AnchorView(long anchorId, long documentId, String segmentId, String language,
                             String citationKey, int startOffset, int endOffset, int length,
                             String anchorText, String lockReason, String createdBy, Instant createdAt,
                             int translationVersion, String status,
                             String releasedBy, String releaseReason, Instant releasedAt) {
    }

    /** 锚点登记响应。 */
    public record RegisterAnchorsResponse(long documentId, String segmentId, String language,
                                          int draftVersion, List<AnchorView> anchors) {
    }

    /** 批量修订中单条结果：新版本与迁移后的生效锚点集合。 */
    public record SegmentRevisionResult(String segmentId, String language, int translationVersion,
                                        int sourceVersion, int termVersion, List<AnchorView> anchors) {
    }

    /** 批量译文修订响应：整批共用一个草稿版本（仅加一）。 */
    public record BatchReviseTranslationsResponse(long documentId, int draftVersion,
                                                  List<SegmentRevisionResult> results) {
    }

    /** 锚点解除响应：返回解除后的锚点终态。 */
    public record ReleaseAnchorResponse(long documentId, String segmentId, String language,
                                        int draftVersion, AnchorView anchor) {
    }

    /** 锚点不可变事件视图：REGISTERED/MIGRATED/RELEASED；from* 字段在登记/解除事件为 null。 */
    public record AnchorEventView(long eventId, long anchorId, String eventType,
                                  Integer fromStart, Integer fromEnd, int toStart, int toEnd,
                                  String anchorText, String actorId, String actorRole, String reason,
                                  Instant eventTime) {
    }

    /** 段落锚点历史响应：按事件提交顺序排列。 */
    public record AnchorHistoryResponse(long documentId, String segmentId, List<AnchorEventView> events) {
    }

    /**
     * 锚点诊断视图：在当前（可能已修订但尚未重新登记/迁移）译文状态下的只读核对结果。
     *
     * @param anchorId                   锚点 ID
     * @param citationKey                规范化引用标识
     * @param language                   目标语言码
     * @param status                     ACTIVE / RELEASED
     * @param boundTranslationVersion    登记/迁移时绑定的译文版本
     * @param currentTranslationVersion  该语言当前译文版本
     * @param currentContentLength       该语言当前译文长度，字符数（UTF-16 代码单元）
     * @param rangeInCurrentContent      区间是否仍落在当前译文长度内且非空
     * @param textIntactInCurrentContent 当前译文在该区间切片是否与固化引用文本逐字一致
     * @param drift                      是否需要随下一次译文修订迁移（版本漂移或区间/文本失效）
     */
    public record AnchorDiagnosticView(long anchorId, String segmentId, String language, String citationKey,
                                       String status, int startOffset, int endOffset, String anchorText,
                                       int boundTranslationVersion, Integer currentTranslationVersion,
                                       Integer currentContentLength, boolean rangeInCurrentContent,
                                       boolean textIntactInCurrentContent, boolean drift) {
    }

    /** 锚点诊断响应：当前生效与已解除锚点的核对明细，只读。 */
    public record AnchorDiagnosticsResponse(long documentId, int draftVersion, int termVersion,
                                            List<AnchorDiagnosticView> anchors) {
    }
}
