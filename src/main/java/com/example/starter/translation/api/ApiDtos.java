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

    /** 冻结条目输入：sourceTerm 去首尾空白后规范化，allowedTranslations 1~5 条。 */
    public record FreezeEntryInput(
            @NotBlank(message = "sourceTerm 不能为空") @Size(max = 512) String sourceTerm,
            @NotBlank(message = "language 不能为空") String language,
            @NotNull(message = "allowedTranslations 不能为空")
            @Size(min = 1, max = 5, message = "允许译法须为 1~5 条")
            List<@NotBlank(message = "允许译法不能为空") @Size(max = 2048) String> allowedTranslations) {
    }

    /** 创建术语冻结请求：freezeKey 为客户端幂等键，条目 1~100 条。 */
    public record CreateFreezeRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotBlank(message = "freezeKey 不能为空") @Size(max = 128) String freezeKey,
            @NotNull(message = "entries 不能为空") @Size(min = 1, max = 100, message = "冻结条目须为 1~100 条")
            List<@Valid FreezeEntryInput> entries) {
    }

    /** 冻结写操作响应。 */
    public record FreezeResponse(long documentId, int freezeVersion, int termVersion, String status,
                                 String fingerprint, int entryCount) {
    }

    /** 冻结条目视图：规范化术语与去重排序后的允许译法。 */
    public record FreezeEntryView(String sourceTerm, String language, List<String> allowedTranslations) {
    }

    /** 冻结查询视图：冻结版本、绑定术语版本、状态、指纹、操作者与完整条目。 */
    public record FreezeView(long documentId, int freezeVersion, int termVersion, String status,
                             String fingerprint, String createdBy, List<FreezeEntryView> entries) {
    }

    /** 撤销冻结请求。 */
    public record RevokeFreezeRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId) {
    }

    /** 冻结违规明细：命中的段落、语言、术语及允许译法。 */
    public record FreezeViolationView(String segmentId, String language, String sourceTerm,
                                      List<String> allowedTranslations) {
    }

    /** 批量译文修订中的单条修订输入。 */
    public record RevisionInput(
            @NotBlank(message = "segmentId 不能为空") @Size(max = 64) String segmentId,
            @NotBlank(message = "language 不能为空") String language,
            @NotBlank(message = "content 不能为空") String content,
            @Positive(message = "sourceVersion 必须为正数") int sourceVersion) {
    }

    /** 批量译文修订请求：1~50 条修订，整批原子提交或回滚。 */
    public record BatchRevisionsRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotNull(message = "revisions 不能为空") @Size(min = 1, max = 50, message = "批量修订须为 1~50 条")
            List<@Valid RevisionInput> revisions) {
    }

    /** 批量修订中单条修订的结果。 */
    public record RevisionResult(String segmentId, String language, int translationVersion, int sourceVersion) {
    }

    /** 批量译文修订响应：整批成功后的草稿版本与各修订结果。 */
    public record BatchRevisionsResponse(long documentId, int draftVersion, int termVersion,
                                         List<RevisionResult> results) {
    }

    /** 段落诊断中单个命中术语的合规情况。 */
    public record FreezeHitView(String sourceTerm, List<String> allowedTranslations, boolean satisfied) {
    }

    /** 单段落单语言的冻结诊断：译文版本（无译文为 null）与各命中术语的合规情况。 */
    public record SegmentFreezeDiagnostics(String segmentId, String language, Integer translationVersion,
                                           List<FreezeHitView> hits) {
    }

    /** 冻结段落诊断响应：仅含命中冻结术语的段落与语言，按段落、语言稳定排序。 */
    public record FreezeDiagnosticsResponse(long documentId, int freezeVersion,
                                            List<SegmentFreezeDiagnostics> diagnostics) {
    }

    /** 统一错误响应；violations 仅在术语违规 422 时返回，freezeViolations 仅在冻结违规 422 时返回。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorResponse(String error, String message, List<TermRuleView> violations,
                                List<FreezeViolationView> freezeViolations) {
        public ErrorResponse(String error, String message) {
            this(error, message, null, null);
        }

        public ErrorResponse(String error, String message, List<TermRuleView> violations) {
            this(error, message, violations, null);
        }
    }
}
