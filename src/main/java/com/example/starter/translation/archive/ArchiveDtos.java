package com.example.starter.translation.archive;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 发布撤回、术语退役与译文归档包 API 的请求与响应体。
 * 所有时间字段均为 UTC ISO-8601 毫秒精度字符串（如 2026-09-29T08:30:00.123Z），null 表示该事件尚未发生。
 */
public final class ArchiveDtos {

    private ArchiveDtos() {
    }

    /** 撤回发布请求：reason 非空。 */
    public record WithdrawReleaseRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotBlank(message = "reason 不能为空") @Size(max = 512, message = "reason 最长 512 字符") String reason) {
    }

    /** 术语版本退役请求。 */
    public record RetireTermsRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @Positive(message = "termVersion 必须为正数") int termVersion,
            @NotBlank(message = "reason 不能为空") @Size(max = 512, message = "reason 最长 512 字符") String reason) {
    }

    /** 创建单个归档包请求：指定唯一发布版本。 */
    public record CreateArchiveRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @Positive(message = "publishedVersion 必须为正数") int publishedVersion) {
    }

    /** 批量创建归档包请求：多个发布版本，任一不可归档则整批不产生包。 */
    public record BatchArchiveRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotEmpty(message = "publishedVersions 不能为空")
            @Size(max = 100, message = "单次批量归档最多 100 个版本")
            List<@Positive(message = "publishedVersion 必须为正数") Integer> publishedVersions) {
    }

    /** 撤销归档包请求：仅标记访问失效并保留原因，不改写证据。 */
    public record RevokeArchiveRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotBlank(message = "reason 不能为空") @Size(max = 512, message = "reason 最长 512 字符") String reason) {
    }

    /** 发布撤回响应。 */
    public record WithdrawalResponse(long documentId, int publishedVersion, String withdrawnBy,
                                     String reason, String withdrawnAt) {
    }

    /** 术语退役响应。 */
    public record TermRetirementResponse(long documentId, int termVersion, String retiredBy,
                                         String reason, String retiredAt) {
    }

    /** 归档包列表中的精简视图。 */
    public record ArchiveSummary(long archiveId, int publishedVersion, String status, String createdAt) {
    }

    /** 创建归档包响应。 */
    public record ArchiveCreatedResponse(long archiveId, long documentId, int publishedVersion, String status,
                                         String createdBy, String createdAt) {
    }

    /** 批量归档响应：requestedCount 为要求值，createdCount/existingCount 为实际构成，二者之和等于要求值。 */
    public record BatchArchiveResponse(long documentId, int requestedCount, int createdCount, int existingCount,
                                       List<ArchiveSummary> packages) {
    }

    /** 归档术语规则视图（取自发布快照，退役不影响归档证据）。 */
    public record ArchiveTermView(String sourceTerm, String language, String requiredTranslation) {
    }

    /** 归档的逐语言译文证据：正文、作者、全部引用版本与审核人。 */
    public record ArchivedTranslation(String language, String content, String author, int translationVersion,
                                      int sourceVersion, int termVersion, String reviewer) {
    }

    /** 归档的逐段证据：规范排序段落下的各语言译文。 */
    public record ArchivedSegment(String segmentId, int sourceVersion, String sourceText,
                                  List<ArchivedTranslation> translations) {
    }

    /** 归档的审批记录证据。 */
    public record ArchivedApproval(String segmentId, String language, String reviewer,
                                   int sourceVersion, int translationVersion) {
    }

    /** 发布快照摘要：数量类字段均为归档时从快照统计的实际值。 */
    public record ReleaseSummary(int publishedVersion, String releasedAt, int segmentCount,
                                 int requiredLanguageCount, int translationCount, int approvalCount,
                                 int termVersion, int ruleCount) {
    }

    /** 归档包完整证据：生成后只读，撤销不删除。 */
    public record ArchiveEvidence(int publishedVersion, int termVersion, List<String> targetLanguages,
                                  List<ArchiveTermView> terms, List<ArchivedSegment> segments,
                                  List<ArchivedApproval> approvals, ReleaseSummary releaseSummary) {
    }

    /** 归档包明细：ACTIVE 时 evidence 非空；REVOKED 时证据访问失效（接口返回 410，不在此体中返回证据）。 */
    public record ArchiveDetailResponse(long archiveId, long documentId, int publishedVersion, String status,
                                        String createdBy, String createdAt, String revokedBy, String revokedAt,
                                        String revokedReason, ArchiveEvidence evidence) {
    }

    /** 撤销归档响应。 */
    public record RevokeArchiveResponse(long archiveId, String status, String revokedBy,
                                        String revokedAt, String revokedReason) {
    }

    /** 历史记录条目：字段集合固定、排序稳定，撤销信息在 ACTIVE 时为 null。 */
    public record ArchiveHistoryItem(long archiveId, int publishedVersion, String status, String createdBy,
                                     String createdAt, String revokedBy, String revokedAt, String revokedReason) {
    }

    /**
     * 历史查询响应：from/to 为归一化后的 UTC 区间（左闭右开），未传时为 null；
     * totalCount 为要求值口径下的实际返回数，activeCount/revokedCount 为差额构成。
     */
    public record ArchiveHistoryResponse(long documentId, String from, String to, int totalCount,
                                         int activeCount, int revokedCount, List<ArchiveHistoryItem> items) {
    }

    /** 单个目标语种完整性：要求段数、实际段数、缺段差额与缺段明细。 */
    public record LanguageCompleteness(String language, int requiredSegmentCount, int actualSegmentCount,
                                       int missingSegmentCount, List<String> missingSegmentIds, boolean complete) {
    }

    /**
     * 归档诊断：发布版本是否存在、是否撤回、是否已归档，以及语种/段落单元的要求值、实际值与差额和全部问题原因。
     */
    public record ArchiveDiagnosticsResponse(long documentId, int publishedVersion, boolean releaseExists,
                                             boolean withdrawn, String withdrawalReason,
                                             boolean archiveExists, Long archiveId, String archiveStatus,
                                             int requiredLanguageCount, int requiredSegmentCount,
                                             int completeLanguageCount, int incompleteLanguageCount,
                                             int requiredCellCount, int completeCellCount, int missingCellCount,
                                             List<@Valid LanguageCompleteness> languages, List<String> issues) {
    }
}
