package com.example.starter.translation.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.Comparator;
import java.util.List;

/**
 * 多语种段落发布 API 的请求与响应体。所有写操作均携带全局唯一 requestId。
 */
public final class ApiDtos {

    private ApiDtos() {
    }

    /** 初始段落输入；placeholders 为可选版式占位符声明，null 表示未声明。 */
    public record SegmentInput(
            @NotBlank(message = "segmentId 不能为空") @Size(max = 64) String segmentId,
            @NotBlank(message = "sourceText 不能为空") String sourceText,
            @Size(max = 100, message = "占位符最多 100 个")
            List<@NotBlank(message = "占位符不能为空") @Size(max = 128) String> placeholders) {
    }

    /** 建文档请求：1~5 种目标语言，可携带初始段落。 */
    public record CreateDocumentRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotNull(message = "targetLanguages 不能为空") @Size(min = 1, max = 5, message = "目标语言须为 1~5 种")
            List<@NotBlank(message = "语言码不能为空") String> targetLanguages,
            @NotNull(message = "segments 不能为空") List<@Valid SegmentInput> segments) {
    }

    /** 增加段落请求；placeholders 为可选版式占位符声明，null 表示未声明。 */
    public record AddSegmentRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotBlank(message = "segmentId 不能为空") @Size(max = 64) String segmentId,
            @NotBlank(message = "sourceText 不能为空") String sourceText,
            @Size(max = 100, message = "占位符最多 100 个")
            List<@NotBlank(message = "占位符不能为空") @Size(max = 128) String> placeholders) {
    }

    /** 源文修订请求。 */
    public record ReviseSourceRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotBlank(message = "sourceText 不能为空") String sourceText) {
    }

    /** 占位符修订请求：整体替换声明（可为空数组表示不允许任何占位符），源文版本加一。 */
    public record RevisePlaceholdersRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotNull(message = "placeholders 不能为空") @Size(max = 100, message = "占位符最多 100 个")
            List<@NotBlank(message = "占位符不能为空") @Size(max = 128) String> placeholders) {
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

    /** 批量批准的单项：段落、语言与期望译文版本。 */
    public record BatchApproveItem(
            @NotBlank(message = "segmentId 不能为空") @Size(max = 64) String segmentId,
            @NotBlank(message = "language 不能为空") String language,
            @Positive(message = "translationVersion 必须为正数") int translationVersion) {
    }

    /**
     * 批量批准请求：任一项不完整则整次不改变批准状态。
     * 构造时按 (segmentId, language) 规范排序 items，保证幂等指纹与处理顺序稳定，
     * 不依赖客户端传入顺序，也不通过未转义分隔符拼接集合。
     */
    public record BatchApproveRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotNull(message = "items 不能为空") @Size(min = 1, max = 100, message = "批量批准须为 1~100 项")
            List<@Valid BatchApproveItem> items) {
        public BatchApproveRequest {
            if (items != null) {
                items = items.stream()
                        .sorted(Comparator.comparing(BatchApproveItem::segmentId)
                                .thenComparing(BatchApproveItem::language))
                        .toList();
            }
        }
    }

    /** 撤回批准请求；操作者取 X-Actor-Id 请求头。 */
    public record WithdrawApprovalRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId) {
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

    /** 批量批准响应：approvedCount 为实际批准数量，approvals 按 (segmentId, language) 规范排序。 */
    public record BatchApproveResponse(long documentId, int approvedCount, List<ApprovalResponse> approvals) {
    }

    /** 撤回批准响应：携带被撤回批准的业务证据（原审核人与版本）。 */
    public record WithdrawalResponse(long documentId, String segmentId, String language, String withdrawnBy,
                                     String previousReviewer, int translationVersion, int sourceVersion) {
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

    /** 占位符计数差异：要求值与实际值；expectedCount > actualCount 为缺失，反之为额外。 */
    public record PlaceholderMismatchView(String segmentId, String language, String placeholder,
                                          int expectedCount, int actualCount) {
    }

    /** 批量批准中单项的失败原因。 */
    public record BatchItemErrorView(String segmentId, String language, String reason, String message) {
    }

    /** 操作历史记录视图：字段、排序、时间精度与 null 语义稳定；occurredAt 为 UTC ISO-8601。 */
    public record HistoryView(long historyId, String operation, String segmentId, String language,
                              String actor, JsonNode detail, String occurredAt) {
    }

    /** 操作历史查询响应：records 按 (occurredAt, historyId) 升序稳定排序。 */
    public record HistoryListView(long documentId, List<HistoryView> records) {
    }

    /** 段落明细中某语言的译文状态；无译文时译文相关字段为 null。 */
    public record TranslationStatusView(String language, boolean translationExists, String content,
                                        String author, Integer translationVersion, Integer sourceVersion,
                                        Integer termVersion, Boolean sourceStale, Boolean approved,
                                        String reviewer) {
    }

    /** 段落明细：源文、版本、占位符声明（null 表示未声明）及各目标语言译文状态。 */
    public record SegmentDetailView(long documentId, String segmentId, String sourceText, int sourceVersion,
                                    List<String> placeholders, List<TranslationStatusView> translations) {
    }

    /** 单条译文的发布就绪诊断：占位符计数给出要求值与实际值（无译文或未声明时为 null）。 */
    public record TranslationDiagnostics(String language, boolean translationExists, Integer translationVersion,
                                         Boolean sourceStale, Boolean termStale, boolean approved,
                                         boolean placeholdersDeclared, Boolean placeholdersMatch,
                                         Integer expectedPlaceholderCount, Integer actualPlaceholderCount,
                                         boolean publishReady) {
    }

    /** 单段的发布就绪诊断。 */
    public record SegmentDiagnostics(String segmentId, int sourceVersion, List<String> placeholders,
                                     List<TranslationDiagnostics> translations) {
    }

    /**
     * 文档发布就绪诊断：数量字段均返回实际值与要求值。
     * requiredApprovalCount = 段落数 × 目标语言数；readyCount 为当前可发布的段落-语言组合数。
     */
    public record DiagnosticsView(long documentId, int draftVersion, int publishedVersion, int termVersion,
                                  int segmentCount, int languageCount, int requiredApprovalCount,
                                  int approvedCount, int readyCount, List<SegmentDiagnostics> segments) {
    }

    /**
     * 统一错误响应；violations 仅在术语违规 422 时返回，placeholderMismatches 仅在占位符不一致 422 时返回，
     * items 仅在批量批准不完整 422 时返回，均含全部明细。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorResponse(String error, String message, List<TermRuleView> violations,
                                List<PlaceholderMismatchView> placeholderMismatches,
                                List<BatchItemErrorView> items) {
        public ErrorResponse(String error, String message) {
            this(error, message, null, null, null);
        }

        public ErrorResponse(String error, String message, List<TermRuleView> violations) {
            this(error, message, violations, null, null);
        }
    }
}
