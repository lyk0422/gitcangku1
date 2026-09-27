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

    /** 初始段落输入；placeholders 为源段声明的版式占位符集合（可空，表示无占位符）。 */
    public record SegmentInput(
            @NotBlank(message = "segmentId 不能为空") @Size(max = 64) String segmentId,
            @NotBlank(message = "sourceText 不能为空") String sourceText,
            List<String> placeholders) {
    }

    /** 建文档请求：1~5 种目标语言，可携带初始段落。 */
    public record CreateDocumentRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotNull(message = "targetLanguages 不能为空") @Size(min = 1, max = 5, message = "目标语言须为 1~5 种")
            List<@NotBlank(message = "语言码不能为空") String> targetLanguages,
            @NotNull(message = "segments 不能为空") List<@Valid SegmentInput> segments) {
    }

    /** 增加段落请求；placeholders 为规范排序占位符集合（可空）。 */
    public record AddSegmentRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotBlank(message = "segmentId 不能为空") @Size(max = 64) String segmentId,
            @NotBlank(message = "sourceText 不能为空") String sourceText,
            List<String> placeholders) {
    }

    /** 源文修订请求：可同时修订正文与占位符集合，任一变化均创建新源版本；placeholders 可空表示清空声明。 */
    public record ReviseSourceRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotBlank(message = "sourceText 不能为空") String sourceText,
            List<String> placeholders) {
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

    /** 段落相关写操作响应；placeholders 为当前源段规范排序的占位符集合。 */
    public record SegmentResponse(long documentId, String segmentId, int sourceVersion, int draftVersion,
                                  List<String> placeholders) {
    }

    /** 译文提交响应；status 为 ACTIVE 或 NEEDS_REVISION。 */
    public record TranslationResponse(long documentId, String segmentId, String language,
                                      int translationVersion, int sourceVersion, int termVersion,
                                      int draftVersion, String status) {
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

    /** 撤回批准请求；撤回者取 X-Actor-Id。 */
    public record WithdrawApprovalRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId) {
    }

    /** 批量批准单项目标：段落 + 目标语言 + 当前译文版本。 */
    public record ApprovalItem(
            @NotBlank(message = "segmentId 不能为空") @Size(max = 64) String segmentId,
            @NotBlank(message = "language 不能为空") String language,
            @Positive(message = "translationVersion 必须为正数") int translationVersion) {
    }

    /** 批量批准请求：1~50 项，任一项不满足时整批不改变批准状态。 */
    public record BatchApproveRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotNull(message = "items 不能为空") @Size(min = 1, max = 50, message = "批量批准须为 1~50 项")
            List<@Valid ApprovalItem> items) {
    }

    /** 批量批准中单项结果。 */
    public record ApprovalItemResult(String segmentId, String language, String reviewer,
                                    int translationVersion, int sourceVersion) {
    }

    /** 批量批准响应：全部成功才返回；approvedCount 为实际批准项数。 */
    public record BatchApproveResponse(long documentId, int approvedCount, List<ApprovalItemResult> results) {
    }

    /** 撤回批准响应：approved 为 false 表示原本就不存在批准（仍幂等返回成功语义由服务决定）。 */
    public record WithdrawApprovalResponse(long documentId, String segmentId, String language,
                                          String reviewer, boolean removed) {
    }

    /** 文档明细中的单个译文视图。 */
    public record TranslationDetailView(String language, String content, String author, int translationVersion,
                                        int sourceVersion, int termVersion, String status,
                                        boolean approvalValid, String reviewer) {
    }

    /** 文档明细中的单个段落视图，含占位符集合与各语言译文。 */
    public record SegmentDetailView(String segmentId, String sourceText, int sourceVersion,
                                   List<String> placeholders, List<TranslationDetailView> translations) {
    }

    /** 文档明细：版本信息与按 segmentId 稳定排序的段落列表。 */
    public record DocumentDetailResponse(long documentId, int draftVersion, int publishedVersion, int termVersion,
                                         List<String> targetLanguages, List<SegmentDetailView> segments) {
    }

    /** 单条操作历史视图；actor/segmentId/language 为 null 表示该操作无此关联。 */
    public record OperationHistoryView(long seq, String operationType, String actor, String segmentId,
                                      String language, String requestId, long occurredAtUtcMillis,
                                      String fingerprint, Object detail) {
    }

    /** 历史区间查询响应：UTC 毫秒左闭右开，按时间、序号稳定升序。 */
    public record HistoryResponse(long documentId, Long fromInclusiveUtcMillis, Long toExclusiveUtcMillis,
                                  int count, List<OperationHistoryView> operations) {
    }

    /** 发布诊断单项：实际值、要求值与差额（actual - required）。 */
    public record DiagnosisItem(String segmentId, String language, String check,
                               int actual, int required, int difference, boolean passed, String reason) {
    }

    /** 发布诊断响应：不改变任何状态，汇总发布前全部阻塞原因。 */
    public record DiagnosisResponse(long documentId, int draftVersion, int publishedVersion, int termVersion,
                                   boolean publishable, int requiredCount, int readyCount, int blockedCount,
                                   List<DiagnosisItem> items) {
    }

    /** 统一错误响应；violations 仅在术语违规 422 时返回，含全部违规术语。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorResponse(String error, String message, List<TermRuleView> violations) {
        public ErrorResponse(String error, String message) {
            this(error, message, null);
        }
    }
}
