package com.example.starter.translation.domain;

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
     * @param documentId          全局唯一文档 ID，自增
     * @param targetLanguages     目标语言列表，小写语言码，1~5 种
     * @param requiredReviewCount 每个译文当前版本所需的非冲突有效批准数（法定人数），1~5
     * @param draftVersion        文档草稿版本，从 1 开始；增段落或修改源文/译文/术语时加一
     * @param publishedVersion    已发布版本号，从 0 开始，每次成功发布加一
     * @param termVersion         当前术语版本，从 0 开始（0 表示尚未建立术语版本）
     */
    public record DocumentRow(long documentId, List<String> targetLanguages, int requiredReviewCount,
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
     * 评审：同一译文可被多名评审人各提交一行（批准或否决）。
     *
     * @param segmentId          所属段落 ID
     * @param language           目标语言码，小写
     * @param reviewer           评审人，取提交时 X-Actor-Id，不得是译文作者
     * @param decision           评审决定：APPROVED 批准 / REJECTED 否决
     * @param status             评审状态：ACTIVE 有效 / CONFLICTED 因评审人命中有效冲突被排除
     * @param reason             否决说明原文，批准为 null；随发布快照固化
     * @param sourceVersion      评审时的源文版本；发布校验须等于当前源文版本
     * @param translationVersion 评审时的译文版本；发布校验须等于当前译文版本
     * @param conflictId         最近一次排除该行的冲突声明 ID，null 表示从未被排除（历史证据）
     * @param conflictReason     排除时固化的冲突原因，null 表示从未被排除
     * @param conflictValidFrom  排除时固化的冲突区间起点（UTC 毫秒，左闭），null 表示从未被排除
     * @param conflictValidTo    排除时固化的冲突区间终点（UTC 毫秒，右开），null 表示从未被排除
     */
    public record ReviewRow(String segmentId, String language, String reviewer, String decision, String status,
                            String reason, int sourceVersion, int translationVersion, Long conflictId,
                            String conflictReason, Long conflictValidFrom, Long conflictValidTo) {
    }

    /**
     * 评审利益冲突声明。
     *
     * @param conflictId 冲突声明 ID，自增
     * @param documentId 所属文档 ID
     * @param language   受限目标语言码，小写
     * @param reviewer   受限评审人
     * @param validFrom  冲突区间起点，UTC 毫秒时间戳，左闭
     * @param validTo    冲突区间终点，UTC 毫秒时间戳，右开，必须晚于 validFrom
     * @param reason     冲突原因，非空
     * @param status     声明状态：DECLARED / LIFTED
     * @param declaredBy 声明人 X-Actor-Id
     * @param declaredAt 声明提交时刻，UTC 毫秒时间戳
     * @param liftedBy   解除确认人 X-Actor-Id，须为不同于声明人的合规角色；null 未解除
     * @param liftedAt   解除时刻，UTC 毫秒时间戳；null 未解除
     * @param liftReason 解除说明，可空
     */
    public record ConflictRow(long conflictId, long documentId, String language, String reviewer,
                              long validFrom, long validTo, String reason, String status,
                              String declaredBy, long declaredAt, String liftedBy, Long liftedAt,
                              String liftReason) {
    }

    /**
     * 冲突历史：声明与解除均只追加，固化操作人、角色、原因与区间，后续操作不得改写。
     *
     * @param historyId 历史记录 ID，自增
     * @param conflictId 关联冲突声明 ID
     * @param documentId 所属文档 ID
     * @param action     历史动作：DECLARED / LIFTED
     * @param actor      操作人 X-Actor-Id
     * @param actorRole  操作人角色，取 X-Actor-Role
     * @param reason     该动作的原因/说明原文
     * @param validFrom  关联冲突区间起点，UTC 毫秒，左闭
     * @param validTo    关联冲突区间终点，UTC 毫秒，右开
     * @param createdAt  历史记录追加时刻，UTC 毫秒
     */
    public record ConflictHistoryRow(long historyId, long conflictId, long documentId, String action,
                                     String actor, String actorRole, String reason,
                                     long validFrom, long validTo, long createdAt) {
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
