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
            @Valid List<AnchorMappingInput> anchorMappings) {
    }

    /** 锚点区间迁移映射：旧锚点到新区间的一一对应；区间左闭右开，按 Java 字符偏移。 */
    public record AnchorMappingInput(
            @NotNull(message = "anchorId 不能为空") @Positive(message = "anchorId 必须为正数") Long anchorId,
            @NotNull(message = "rangeStart 不能为空") @PositiveOrZero(message = "rangeStart 不能为负数")
            Integer rangeStart,
            @NotNull(message = "rangeEnd 不能为空") @Positive(message = "rangeEnd 必须为正数") Integer rangeEnd) {
    }

    /** 锚点登记输入：引用标识、字符区间（左闭右开）与锁定原因。 */
    public record AnchorInput(
            @NotBlank(message = "citationKey 不能为空") @Size(max = 512) String citationKey,
            @NotNull(message = "rangeStart 不能为空") @PositiveOrZero(message = "rangeStart 不能为负数")
            Integer rangeStart,
            @NotNull(message = "rangeEnd 不能为空") @Positive(message = "rangeEnd 必须为正数") Integer rangeEnd,
            @NotBlank(message = "lockReason 不能为空") @Size(max = 2048) String lockReason) {
    }

    /** 锚点登记请求：一次登记一个或多个互不交叉的锚点。 */
    public record RegisterAnchorsRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotNull(message = "anchors 不能为空") @Size(min = 1, max = 100, message = "锚点须为 1~100 个")
            List<@Valid AnchorInput> anchors) {
    }

    /** 锚点解除请求：解除人须为不同于登记人的法务，理由不可变。 */
    public record ReleaseAnchorRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotBlank(message = "reason 不能为空") @Size(max = 2048) String reason) {
    }

    /** 批量译文中的单段修订项。 */
    public record BatchTranslationItem(
            @NotBlank(message = "segmentId 不能为空") @Size(max = 64) String segmentId,
            @NotBlank(message = "language 不能为空") String language,
            @NotBlank(message = "content 不能为空") String content,
            @Positive(message = "sourceVersion 必须为正数") int sourceVersion,
            @Valid List<AnchorMappingInput> anchorMappings) {
    }

    /** 批量译文修订请求：全部段落校验通过后在同一事务提交，任一失败整次回滚。 */
    public record BatchSubmitTranslationsRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotNull(message = "items 不能为空") @Size(min = 1, max = 100, message = "批量修订须为 1~100 段")
            List<@Valid BatchTranslationItem> items) {
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

    /** 译文提交响应；migratedAnchorCount 为本次实际迁移区间的生效锚点数（原位保留不计）。 */
    public record TranslationResponse(long documentId, String segmentId, String language,
                                      int translationVersion, int sourceVersion, int termVersion,
                                      int draftVersion, int migratedAnchorCount) {
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

    /** 引文锚点视图：明细查询与快照共用，字段、时间精度（UTC、微秒）与 null 语义稳定。 */
    public record AnchorView(long anchorId, String segmentId, String language, String citationKey,
                             int rangeStart, int rangeEnd, String anchorText, String lockReason,
                             String createdBy, Integer translationVersion, Integer sourceVersion,
                             String status, String releasedBy, String releaseReason,
                             String createdAt, String releasedAt) {
    }

    /** 锚点登记响应：返回登记的锚点数量与完整锚点集合。 */
    public record RegisterAnchorsResponse(long documentId, int registeredCount, List<AnchorView> anchors) {
    }

    /** 锚点解除响应：返回解除后的最终锚点状态。 */
    public record ReleaseAnchorResponse(long documentId, long anchorId, String status,
                                        String releasedBy, String releasedAt) {
    }

    /** 锚点明细查询响应：某段落某语言当前生效锚点（按区间起点排序）。 */
    public record AnchorListResponse(long documentId, String segmentId, String language,
                                     int count, List<AnchorView> anchors) {
    }

    /** 锚点事件历史视图：只追加事件按 eventId 排序。 */
    public record AnchorEventView(long eventId, long anchorId, String segmentId, String language,
                                  String citationKey, String eventType, int rangeStart, int rangeEnd,
                                  Integer previousStart, Integer previousEnd, String anchorText, String reason,
                                  String actorId, int translationVersion, int sourceVersion, String occurredAt) {
    }

    /** 锚点事件历史查询响应。 */
    public record AnchorHistoryResponse(long documentId, int count, List<AnchorEventView> events) {
    }

    /**
     * 锚点诊断项：只读诊断查询，给出锚点相对当前译文的状态与实际/要求数量，不改变任何状态。
     *
     * @param anchorId              锚点 ID
     * @param segmentId             段落 ID
     * @param language              语言码
     * @param citationKey           引用标识
     * @param status                LOCKED / RELEASED
     * @param rangeStart            当前锚点区间起点
     * @param rangeEnd              当前锚点区间终点
     * @param currentContentLength  当前译文实际字符长度
     * @param rangeInBounds         区间是否仍落在当前译文内
     * @param textPreserved         当前区间文本是否仍与固化引用文本逐字符一致
     * @param actualTextAtRange     当前区间实际文本；区间越界时为 null
     */
    public record AnchorDiagnosticView(long anchorId, String segmentId, String language, String citationKey,
                                       String status, int rangeStart, int rangeEnd, int currentContentLength,
                                       boolean rangeInBounds, boolean textPreserved, String actualTextAtRange) {
    }

    /** 锚点诊断查询响应：文档全部锚点的当前一致性诊断。 */
    public record AnchorDiagnosticsResponse(long documentId, int count, int lockedCount, int releasedCount,
                                            int inconsistentCount, List<AnchorDiagnosticView> anchors) {
    }

    /**
     * 单项校验问题：失败响应必须可区分原因与位置。
     *
     * @param code       问题类型，如 ANCHOR_MAPPING_MISSING / ANCHOR_TEXT_MISMATCH / TERM_STALE
     * @param segmentId  问题段落；文档级问题可能为 null
     * @param language   问题语言；非译文级问题为 null
     * @param anchorId   问题锚点；非锚点级问题为 null
     * @param message    可读原因，含实际值、要求值或差额
     */
    public record IssueView(String code, String segmentId, String language, Long anchorId, String message) {
    }

    /** 批量译文修订中单段结果。 */
    public record BatchTranslationItemResult(String segmentId, String language, int translationVersion,
                                             int sourceVersion, int migratedAnchorCount) {
    }

    /** 批量译文修订响应：成功段数与每段结果。 */
    public record BatchSubmitTranslationsResponse(long documentId, int itemCount,
                                                  List<BatchTranslationItemResult> results) {
    }

    /** 统一错误响应；violations 仅在术语违规 422 时返回，含全部违规术语；issues 为可区分原因的校验问题。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorResponse(String error, String message, List<TermRuleView> violations,
                                List<IssueView> issues) {
        public ErrorResponse(String error, String message) {
            this(error, message, null, null);
        }

        public ErrorResponse(String error, String message, List<TermRuleView> violations) {
            this(error, message, violations, null);
        }
    }
}
