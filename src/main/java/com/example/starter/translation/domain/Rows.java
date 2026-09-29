package com.example.starter.translation.domain;

import java.time.Instant;
import java.util.List;

/**
 * 数据库行记录，与 schema.sql 中各表 COMMENT 保持一致。
 */
public final class Rows {

    private Rows() {
    }

    /**
     * 文档：全局唯一 documentId，含 1~5 种目标语言及草稿/发布/术语版本。
     *
     * @param documentId       全局唯一文档 ID，自增
     * @param targetLanguages  目标语言列表，小写语言码，1~5 种
     * @param draftVersion     文档草稿版本，从 1 开始；增段落或修改源文/译文/术语时加一
     * @param publishedVersion 已发布版本号，从 0 开始，每次成功发布加一
     * @param termVersion      当前术语版本，从 0 开始（0 表示尚未建立术语版本）
     */
    public record DocumentRow(long documentId, List<String> targetLanguages,
                              int draftVersion, int publishedVersion, int termVersion) {
    }

    /**
     * 段落：文档内唯一 segmentId，含源文及源文版本。
     *
     * @param segmentId     文档内唯一段落 ID
     * @param sourceText    源文正文，UTF-8
     * @param sourceVersion 源文版本，从 1 开始，每次源文修订加一
     */
    public record SegmentRow(String segmentId, String sourceText, int sourceVersion) {
    }

    /**
     * 译文：按段落与语言唯一，保存正文、作者、所依据源文版本、绑定术语版本及递增译文版本。
     *
     * @param segmentId          所属段落 ID
     * @param language           目标语言码，小写
     * @param content            译文正文，UTF-8
     * @param author             译文作者，取提交时 X-Actor-Id
     * @param sourceVersion      译文所依据的源文版本；提交时必须等于当前源文版本
     * @param translationVersion 译文版本，从 1 开始，每次重新提交加一
     * @param termVersion        译文提交时绑定的术语版本；不等于当前术语版本时视为术语过期
     */
    public record TranslationRow(String segmentId, String language, String content, String author,
                                 int sourceVersion, int translationVersion, int termVersion) {
    }

    /**
     * 术语规则：属于某术语版本的不可变规则，按 sourceTerm 与目标语言唯一。
     *
     * @param sourceTerm          源文术语，Unicode 原文、区分大小写，按连续子串匹配
     * @param language            目标语言码，小写
     * @param requiredTranslation 该术语在目标语言中的必译文本，非空
     */
    public record TermRuleRow(String sourceTerm, String language, String requiredTranslation) {
    }

    /**
     * 批准：按段落与语言唯一；源文或译文版本改变后先前批准不再有效。
     *
     * @param segmentId          所属段落 ID
     * @param language           目标语言码，小写
     * @param reviewer           审核人，取批准时 X-Actor-Id，不得是译文作者
     * @param sourceVersion      批准时的源文版本，发布校验须等于当前源文版本
     * @param translationVersion 批准时的译文版本，发布校验须等于当前译文版本
     */
    public record ApprovalRow(String segmentId, String language, String reviewer,
                              int sourceVersion, int translationVersion) {
    }

    /**
     * 写操作幂等去重记录：全局唯一 requestId，仅记录成功结果，与业务变更原子提交。
     *
     * @param requestId      全局唯一请求 ID
     * @param requestHash    请求参数规范化后的 SHA-256 摘要；同键异参返回 409
     * @param responseStatus 原成功响应的 HTTP 状态码，用于重放
     * @param responseBody   原成功响应体 JSON，用于重放
     */
    public record RequestLogRow(String requestId, String requestHash,
                                int responseStatus, String responseBody) {
    }

    /**
     * 发布快照状态行：撤回只改状态字段，绝不改写 snapshot_json 证据。
     *
     * @param status          发布状态：PUBLISHED 有效；WITHDRAWN 已撤回
     * @param withdrawnAt     撤回时刻，UTC；null 表示未撤回
     * @param withdrawnBy     撤回操作者；null 表示未撤回
     * @param withdrawReason  撤回原因；null 表示未撤回
     */
    public record ReleaseStatusRow(String status, Instant withdrawnAt,
                                   String withdrawnBy, String withdrawReason) {
    }

    /**
     * 发布快照元数据：快照 JSON、生成时刻与撤回状态；证据 JSON 读出后不依赖可变业务表。
     *
     * @param snapshotJson    发布时固化的完整快照 JSON
     * @param createdAt       发布快照生成时刻，UTC
     * @param status          发布状态：PUBLISHED / WITHDRAWN
     * @param withdrawnAt     撤回时刻，UTC；null 表示未撤回
     * @param withdrawnBy     撤回操作者；null 表示未撤回
     * @param withdrawReason  撤回原因；null 表示未撤回
     */
    public record ReleaseSnapshotMeta(String snapshotJson, Instant createdAt, String status,
                                      Instant withdrawnAt, String withdrawnBy, String withdrawReason) {
    }

    /**
     * 归档包主记录：生成后证据不可变，撤销仅置访问失效状态并保留原因。
     *
     * @param archiveId        全局唯一归档包 ID
     * @param documentId       所属文档 ID
     * @param archiveVersion   文档内归档包序号，从 1 开始
     * @param publishedVersion 引用的发布版本号
     * @param targetLanguages  规范排序的目标语言码列表
     * @param termVersion      固化的术语版本
     * @param segmentCount     每语种段落数
     * @param languageCount    语种数
     * @param approvalCount    审批记录条数
     * @param evidenceJson     归档证据 JSON
     * @param status           访问状态：ACTIVE / REVOKED
     * @param revokedAt        撤销时刻，UTC；null 表示未撤销
     * @param revokedBy        撤销操作者；null 表示未撤销
     * @param revokeReason     撤销原因；null 表示未撤销
     * @param createdAt        生成时刻，UTC，不可变
     */
    public record ArchivePackageRow(long archiveId, long documentId, int archiveVersion,
                                    int publishedVersion, List<String> targetLanguages, int termVersion,
                                    int segmentCount, int languageCount, int approvalCount,
                                    String evidenceJson, String status, Instant revokedAt, String revokedBy,
                                    String revokeReason, Instant createdAt) {
    }

    /**
     * 归档逐段译文版本证据。
     *
     * @param archiveId          所属归档包 ID
     * @param segmentId          段落 ID
     * @param language           目标语言码，小写
     * @param translationVersion 固化的译文版本
     * @param sourceVersion      固化的源文版本
     * @param termVersion        固化的术语版本
     */
    public record ArchiveSegmentRow(long archiveId, String segmentId, String language,
                                    int translationVersion, int sourceVersion, int termVersion) {
    }

    /**
     * 归档领域事件：自增序列表示提交顺序。
     *
     * @param eventId          事件自增序列
     * @param documentId       所属文档 ID
     * @param archiveId        关联归档包 ID；发布撤回事件为 null
     * @param eventType        事件类型
     * @param publishedVersion 关联发布版本；不涉及为 null
     * @param actor            操作者；系统事件为 null
     * @param reason           撤回/撤销原因；归档生成事件为 null
     * @param eventTime        事件时刻，UTC
     */
    public record ArchiveEventRow(long eventId, long documentId, Long archiveId, String eventType,
                                  Integer publishedVersion, String actor, String reason, Instant eventTime) {
    }

    /**
     * 历史查询行：事件关联归档包时附带归档版本，字段与 null 语义对外稳定。
     *
     * @param archiveVersion 关联归档包版本；发布撤回事件为 null
     */
    public record ArchiveHistoryRow(long eventId, long documentId, Long archiveId, Integer archiveVersion,
                                    String eventType, Integer publishedVersion, String actor, String reason,
                                    Instant eventTime) {
    }
}
