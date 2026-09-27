package com.example.starter.translation.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
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

    /** 初始段落输入；placeholders 为源段声明的版式占位符集合，缺省（null）表示未声明。 */
    public record SegmentInput(
            @NotBlank(message = "segmentId 不能为空") @Size(max = 64) String segmentId,
            @NotBlank(message = "sourceText 不能为空") String sourceText,
            @Size(max = 50, message = "占位符最多声明 50 个") List<String> placeholders) {
    }

    /** 建文档请求：1~5 种目标语言，可携带初始段落。 */
    public record CreateDocumentRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotNull(message = "targetLanguages 不能为空") @Size(min = 1, max = 5, message = "目标语言须为 1~5 种")
            List<@NotBlank(message = "语言码不能为空") String> targetLanguages,
            @NotNull(message = "segments 不能为空") List<@Valid SegmentInput> segments) {
    }

    /** 增加段落请求；placeholders 缺省（null）表示未声明版式占位符集合。 */
    public record AddSegmentRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotBlank(message = "segmentId 不能为空") @Size(max = 64) String segmentId,
            @NotBlank(message = "sourceText 不能为空") String sourceText,
            @Size(max = 50, message = "占位符最多声明 50 个") List<String> placeholders) {
    }

    /** 源文修订请求：修订即新源版本，placeholders 为新版本声明的占位符集合，缺省（null）表示未声明。 */
    public record ReviseSourceRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotBlank(message = "sourceText 不能为空") String sourceText,
            @Size(max = 50, message = "占位符最多声明 50 个") List<String> placeholders) {
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

    /** 批量批准中的单条目：段落 + 语言 + 针对的译文版本。 */
    public record ApprovalItemInput(
            @NotBlank(message = "segmentId 不能为空") @Size(max = 64) String segmentId,
            @NotBlank(message = "language 不能为空") String language,
            @Positive(message = "translationVersion 必须为正数") int translationVersion) {
    }

    /**
     * 批量批准请求：任一条目不满足条件（缺译、占位符集合不一致、版本不符、作者自审等）时
     * 整次 422 且不改变任何批准状态。
     */
    public record BatchApproveRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotEmpty(message = "approvals 不能为空") @Size(max = 250, message = "批量批准最多 250 条")
            List<@Valid ApprovalItemInput> approvals) {
    }

    /** 译文批准撤回请求；审核人取 X-Actor-Id 请求头。 */
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

    /** 段落相关写操作响应；placeholders 为该源版本规范化（字典序、含重复次数）后的占位符集合。 */
    public record SegmentResponse(long documentId, String segmentId, int sourceVersion, int draftVersion,
                                  List<String> placeholders) {
    }

    /** 译文提交响应；requiredPlaceholders 为源段声明集合，actualPlaceholders 为译文中实际提取的集合。 */
    public record TranslationResponse(long documentId, String segmentId, String language,
                                      int translationVersion, int sourceVersion, int termVersion,
                                      int draftVersion, List<String> requiredPlaceholders,
                                      List<String> actualPlaceholders) {
    }

    /** 译文批准响应；placeholders 为批准时锁定的源段占位符集合。 */
    public record ApprovalResponse(long documentId, String segmentId, String language, String reviewer,
                                   int translationVersion, int sourceVersion, List<String> placeholders) {
    }

    /** 批量批准响应：全部条目成功时返回，approvedCount 与请求条目数一致。 */
    public record BatchApproveResponse(long documentId, int approvedCount, List<ApprovalResponse> approvals) {
    }

    /** 撤回响应：记录被撤回批准的段落、语言与原审核人。 */
    public record WithdrawResponse(long documentId, String segmentId, String language, String reviewer) {
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

    /** 占位符计数差额视图：占位符、要求次数、实际次数与差额。 */
    public record PlaceholderDiffView(String placeholder, int requiredCount, int actualCount, int difference) {
    }

    /** 译文占位符锁定状态视图：要求/实际规范化集合、是否一致、缺失与额外差额。 */
    public record PlaceholderStatusView(List<String> required, List<String> actual, boolean matched,
                                        List<PlaceholderDiffView> missing, List<PlaceholderDiffView> extra) {
    }

    /** 批准状态视图；尚未批准时 reviewer 为 null、valid 为 false。 */
    public record ApprovalView(String reviewer, int sourceVersion, int translationVersion, boolean valid) {
    }

    /** 单条译文明细视图，含版本、术语与占位符锁定状态及批准状态。 */
    public record TranslationDetailView(String language, String content, String author, int translationVersion,
                                        int sourceVersion, int termVersion, PlaceholderStatusView placeholders,
                                        ApprovalView approval) {
    }

    /** 段落明细视图：源段版本、占位符集合与全部目标语言译文明细。 */
    public record SegmentDetailView(String segmentId, String sourceText, int sourceVersion,
                                    List<String> placeholders, List<TranslationDetailView> translations) {
    }

    /** 文档明细视图：稳定排序（段落 segmentId、语言 targetLanguages 顺序），读取不改变任何状态。 */
    public record DocumentDetailResponse(long documentId, int draftVersion, int publishedVersion, int termVersion,
                                         List<String> targetLanguages, List<SegmentDetailView> segments) {
    }

    /** 单条历史证据视图：sequence 为提交顺序，occurredAtUtc 为 UTC ISO-8651 微秒精度，detail 为固化明细。 */
    public record HistoryEntryView(long sequence, String segmentId, String language, String requestId, String actor,
                                   String operationType, String fingerprint, String occurredAtUtc,
                                   com.fasterxml.jackson.databind.JsonNode detail) {
    }

    /** 历史查询响应：区间左闭右开，from/to 为 null 表示该方向不设界；按提交顺序稳定排序。 */
    public record HistoryResponse(long documentId, String from, String to, int count,
                                  List<HistoryEntryView> entries) {
    }

    /** 单条发布就绪诊断条目：列出不可发布原因（空列表表示该译文就绪）。 */
    public record DiagnosticItemView(String segmentId, String language, boolean ready, List<String> reasons,
                                     PlaceholderStatusView placeholders) {
    }

    /** 发布就绪诊断响应：只读取不改变状态，ready 为全部条目就绪；readyCount 为就绪条目实际数量。 */
    public record DiagnosticResponse(long documentId, int requiredCount, int readyCount, boolean ready,
                                     List<DiagnosticItemView> items) {
    }

    /** 统一错误响应；violations 仅在术语违规 422 时返回，含全部违规术语。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorResponse(String error, String message, List<TermRuleView> violations) {
        public ErrorResponse(String error, String message) {
            this(error, message, null);
        }
    }
}
