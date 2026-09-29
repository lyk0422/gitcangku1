package com.example.starter.translation.archive;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 译文归档包 API 的请求与响应体。所有写操作均携带全局唯一 requestId；时间字段统一为 UTC（ISO-8601 毫秒精度）。
 */
public final class ArchiveDtos {

    private ArchiveDtos() {
    }

    /** 归档创建请求：指定文档的一个已发布版本；targetLanguages 为空表示全部目标语种。 */
    public record CreateArchiveRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @Positive(message = "publishedVersion 必须为正数") int publishedVersion,
            @NotNull(message = "targetLanguages 不能为空")
            List<@NotBlank(message = "语言码不能为空") String> targetLanguages) {
    }

    /** 批量归档请求：多个发布版本分别成包；任一语言不完整则整批失败，不产生部分包。 */
    public record BatchArchiveRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotNull(message = "items 不能为空") @Size(min = 1, max = 20, message = "批量项须为 1~20 个")
            List<@Valid BatchArchiveItem> items) {
    }

    /** 批量归档单项：发布版本与目标语种集合（空表示全部）。 */
    public record BatchArchiveItem(
            @Positive(message = "publishedVersion 必须为正数") int publishedVersion,
            @NotNull(message = "targetLanguages 不能为空") List<String> targetLanguages) {
    }

    /** 发布撤回请求。 */
    public record WithdrawReleaseRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotBlank(message = "reason 不能为空") @Size(max = 512) String reason) {
    }

    /** 撤销归档请求：仅标记访问失效，证据保留。 */
    public record RevokeArchiveRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotBlank(message = "reason 不能为空") @Size(max = 512) String reason) {
    }

    /** 逐段译文版本证据视图：段落与各语种译文版本稳定排序。 */
    public record SegmentEvidenceView(String segmentId, String sourceText, int sourceVersion,
                                      List<TranslationEvidenceView> translations) {
    }

    /** 单语译文证据：含正文、作者、审核人与固化版本，发布后修订不改写。 */
    public record TranslationEvidenceView(String language, String content, String author, String reviewer,
                                          int translationVersion, int sourceVersion, int termVersion) {
    }

    /** 发布快照摘要：发布版本、生成时刻 UTC、术语版本、段落/语种/审批数量。 */
    public record ReleaseSummaryView(int publishedVersion, Instant createdAt, int termVersion,
                                     int segmentCount, int languageCount, int approvalCount,
                                     String status) {
    }

    /** 归档包明细响应：不可变证据；status=REVOKED 时该接口返回 410，请改走历史查询。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ArchiveDetailView(long archiveId, long documentId, int archiveVersion, int publishedVersion,
                                    List<String> targetLanguages, int termVersion, int segmentCount,
                                    int languageCount, int approvalCount,
                                    ReleaseSummaryView release, List<SegmentEvidenceView> segments,
                                    Instant createdAt, String status, Instant revokedAt,
                                    String revokedBy, String revokeReason) {
    }

    /** 历史记录条目：字段、排序、时间精度与 null 语义稳定。 */
    public record ArchiveHistoryItem(long eventId, String eventType, Long archiveId, Integer archiveVersion,
                                     Integer publishedVersion, String actor, String reason, Instant eventTime) {
    }

    /** 历史查询响应：按事件提交顺序（eventId 升序）稳定输出。 */
    public record ArchiveHistoryResponse(long documentId, List<ArchiveHistoryItem> events) {
    }

    /** 诊断行：归档引用版本与发布/术语/译文版本的一致性核对结果及差额。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ArchiveDiagnosticRow(long archiveId, int archiveVersion, int publishedVersion, String status,
                                       int archivedTermVersion, Integer currentReleaseTermVersion,
                                       int segmentCount, int languageCount, int approvalCount,
                                       int archivedRowCount, int missingRowCount, boolean releaseWithdrawn,
                                       String detail) {
    }

    /** 诊断查询响应：含实际值、要求值与差额，按归档版本稳定排序；requestedPublishedVersion 为 null 表示未按版本过滤。 */
    public record ArchiveDiagnosticResponse(long documentId, Integer requestedPublishedVersion,
                                            List<ArchiveDiagnosticRow> archives) {
    }

    /** 批量归档单项结果视图。 */
    public record BatchArchiveItemView(int publishedVersion, List<String> targetLanguages, long archiveId,
                                       int archiveVersion) {
    }

    /** 批量归档响应：全部成功才返回；失败整批回滚无部分包。 */
    public record BatchArchiveResponse(long documentId, int packageCount, List<BatchArchiveItemView> packages) {
    }
}
