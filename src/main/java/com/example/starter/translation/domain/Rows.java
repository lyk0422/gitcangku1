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
     * 当前评审：按段落与语言唯一，保存最近一次评审提交（批准或否决）及其资格状态。
     *
     * @param segmentId             所属段落 ID
     * @param language              目标语言码，小写
     * @param reviewer              审核人，取评审提交时 X-Actor-Id，不得是译文作者
     * @param sourceVersion         评审时的源文版本，发布校验须等于当前源文版本
     * @param translationVersion    评审时的译文版本，发布校验须等于当前译文版本
     * @param decision              评审决定：APPROVED 批准 / REJECTED 否决
     * @param reviewStatus          当前资格状态：VALID 有效 / CONFLICTED 命中有效利益冲突被排除
     * @param termVersion           评审提交时文档当前术语版本，作为评审证据固化
     * @param conflictDeclarationId 导致资格变为 CONFLICTED 的冲突声明 ID；VALID 时为 null
     * @param conflictedAt          被标记 CONFLICTED 的 UTC 时间；VALID 时为 null
     * @param reviewedAt            评审提交时间，UTC
     */
    public record ApprovalRow(String segmentId, String language, String reviewer,
                              int sourceVersion, int translationVersion,
                              String decision, String reviewStatus, int termVersion,
                              Long conflictDeclarationId, Instant conflictedAt, Instant reviewedAt) {

        /** 兼容旧调用：构造一条有效批准。 */
        public ApprovalRow(String segmentId, String language, String reviewer,
                           int sourceVersion, int translationVersion) {
            this(segmentId, language, reviewer, sourceVersion, translationVersion,
                    "APPROVED", "VALID", 0, null, null, null);
        }
    }

    /**
     * 利益冲突声明：评审人对文档、语种与有效 UTC 区间登记的不可变声明；解除以状态与时间记录，不改区间与原因。
     *
     * @param declarationId 声明 ID，自增全局唯一
     * @param documentId    所属文档 ID
     * @param reviewer      评审人标识
     * @param language      目标语言码，小写
     * @param validFrom     冲突生效起点（含），UTC
     * @param validTo       冲突生效终点（不含），UTC，严格晚于 validFrom
     * @param reason        冲突原因，非空
     * @param status        声明状态：ACTIVE / RELEASED
     * @param declaredBy    登记操作者
     * @param declaredAt    登记时间，UTC
     * @param releasedAt    解除时间，UTC；未解除为 null
     * @param releasedBy    解除确认人（不同于声明人的合规角色）；未解除为 null
     */
    public record ConflictDeclarationRow(long declarationId, long documentId, String reviewer, String language,
                                         Instant validFrom, Instant validTo, String reason, String status,
                                         String declaredBy, Instant declaredAt,
                                         Instant releasedAt, String releasedBy) {
    }

    /**
     * 冲突生命周期历史事件，只追加不改写。
     *
     * @param eventId       事件 ID，自增，决定历史顺序
     * @param declarationId 所属声明 ID
     * @param documentId    所属文档 ID
     * @param eventType     事件类型：DECLARED / RELEASED
     * @param reviewer      评审人（事件证据）
     * @param language      语言码（事件证据）
     * @param validFrom     区间起点（含），UTC（事件证据）
     * @param validTo       区间终点（不含），UTC（事件证据）
     * @param reason        冲突原因（事件证据）
     * @param actor         触发事件的操作者
     * @param actorRole     操作者角色；解除为 COMPLIANCE，登记为 null
     * @param occurredAt    事件发生时间，UTC
     */
    public record ConflictEventRow(long eventId, long declarationId, long documentId, String eventType,
                                   String reviewer, String language, Instant validFrom, Instant validTo,
                                   String reason, String actor, String actorRole, Instant occurredAt) {
    }

    /**
     * 评审历史记录：评审提交或资格变化的不可变证据，只追加。
     *
     * @param historyId             历史 ID，自增，决定历史顺序
     * @param documentId            所属文档 ID
     * @param segmentId             所属段落 ID
     * @param language              语言码
     * @param reviewer              评审人
     * @param decision              评审决定：APPROVED / REJECTED
     * @param reviewStatus          事件后资格状态：VALID / CONFLICTED
     * @param sourceVersion         评审时源文版本
     * @param translationVersion    评审时译文版本
     * @param termVersion           评审时术语版本
     * @param conflictDeclarationId 资格变为 CONFLICTED 时的声明 ID；否则 null
     * @param actor                 触发事件的操作者
     * @param occurredAt            事件发生时间，UTC
     */
    public record ReviewHistoryRow(long historyId, long documentId, String segmentId, String language,
                                   String reviewer, String decision, String reviewStatus,
                                   int sourceVersion, int translationVersion, int termVersion,
                                   Long conflictDeclarationId, String actor, Instant occurredAt) {
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
}
