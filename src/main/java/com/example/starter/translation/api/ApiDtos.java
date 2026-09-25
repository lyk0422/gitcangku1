package com.example.starter.translation.api;

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

    /** 批量审核单条译文标识：段落 + 语言 + 客户端所见期望译文版本。 */
    public record BatchApprovalItemInput(
            @NotBlank(message = "segmentId 不能为空") @Size(max = 64) String segmentId,
            @NotBlank(message = "language 不能为空") @Size(max = 16) String language,
            @Positive(message = "expectedTranslationVersion 必须为正数") int expectedTranslationVersion) {
    }

    /**
     * 批量审核请求：batchKey 全局唯一并承担幂等键；expectedDraftVersion 仅固化进批量记录，不做整批前置校验；
     * 条目须为 1~50 条，同一批次内译文标识（段落+语言）不得重复。
     */
    public record BatchApprovalRequest(
            @NotBlank(message = "batchKey 不能为空") @Size(max = 128) String batchKey,
            @Positive(message = "expectedDraftVersion 必须为正数") int expectedDraftVersion,
            @NotNull(message = "items 不能为空")
            @Size(min = 1, max = 50, message = "批量审核条目须为 1~50 条")
            List<@Valid BatchApprovalItemInput> items) {
    }

    /** 发布请求：携带期望的草稿与发布版本做乐观校验。 */
    public record PublishRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @Positive(message = "expectedDraftVersion 必须为正数") int expectedDraftVersion,
            @PositiveOrZero(message = "expectedPublishedVersion 不能为负数") int expectedPublishedVersion) {
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
                                      int translationVersion, int sourceVersion, int draftVersion) {
    }

    /** 译文批准响应。 */
    public record ApprovalResponse(long documentId, String segmentId, String language, String reviewer,
                                   int translationVersion, int sourceVersion) {
    }

    /** 发布响应。 */
    public record PublishResponse(long documentId, int publishedVersion) {
    }

    /** 批量审核单条结果明细。 */
    public record BatchApprovalItemResponse(String segmentId, String language, String reviewer,
                                           int translationVersion, int sourceVersion) {
    }

    /** 批量审核成功响应：原子批准全部条目并生成不可变批量记录。 */
    public record BatchApprovalResponse(String batchKey, long documentId, int expectedDraftVersion,
                                        String reviewer, String approvedAt,
                                        List<BatchApprovalItemResponse> items) {
    }

    /** 批量审核记录查询响应：记录本身不可变，明细稳定排序。 */
    public record BatchApprovalRecordResponse(String batchKey, long documentId, int expectedDraftVersion,
                                              String reviewer, String approvedAt,
                                              List<BatchApprovalItemResponse> items) {
    }

    /** 统一错误响应。 */
    public record ErrorResponse(String error, String message) {
    }

    /** 批量审核 422 逐条原因：index 为条目在请求中的 1-based 位置。 */
    public record BatchApprovalItemError(int index, String segmentId, String language, String reason) {
    }

    /** 批量审核失败响应：整批被拒，不批准任何一条，原因逐条返回。 */
    public record BatchApprovalFailureResponse(String error, String message,
                                               List<BatchApprovalItemError> items) {
    }
}
