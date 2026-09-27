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
     * 批准：按段落与语言唯一；源文或译文版本改变后先前批准不再有效；
     * 命中有效利益冲突的批准标记 CONFLICTED 并排除出法定人数。
     *
     * @param segmentId          所属段落 ID
     * @param language           目标语言码，小写
     * @param reviewer           审核人，取批准时 X-Actor-Id，不得是译文作者
     * @param sourceVersion      批准时的源文版本，发布校验须等于当前源文版本
     * @param translationVersion 批准时的译文版本，发布校验须等于当前译文版本
     * @param status             评审状态：ACTIVE 有效；CONFLICTED 因命中有效利益冲突被排除
     * @param conflictId         导致排除的利益冲突声明 ID；status 为 ACTIVE 时为 null
     * @param conflictedAt       被标记 CONFLICTED 的时间（UTC，秒精度）；status 为 ACTIVE 时为 null
     */
    public record ApprovalRow(String segmentId, String language, String reviewer,
                              int sourceVersion, int translationVersion, String status,
                              Long conflictId, Instant conflictedAt) {
    }

    /**
     * 评审利益冲突声明：评审人对文档与语种登记的有效 UTC 区间（左闭右开）冲突原因。
     *
     * @param conflictId  全局唯一冲突声明 ID，自增
     * @param documentId  所属文档 ID
     * @param reviewer    被声明存在利益冲突的评审人
     * @param language    冲突范围目标语言码，小写
     * @param reason      冲突原因，非空，登记后不可改写
     * @param validFrom   有效区间起点（UTC，秒精度，左闭）
     * @param validTo     有效区间终点（UTC，秒精度，右开），大于 validFrom
     * @param status      声明状态：ACTIVE 有效；LIFTED 已解除
     * @param declaredBy  声明人，取登记时 X-Actor-Id
     * @param declaredAt  登记时间（UTC，秒精度）
     * @param liftedBy    解除确认人（合规角色，须不同于声明人）；未解除为 null
     * @param liftedAt    解除确认时间（UTC，秒精度）；未解除为 null
     * @param liftReason  解除原因；未解除为 null
     */
    public record ConflictRow(long conflictId, long documentId, String reviewer, String language,
                              String reason, Instant validFrom, Instant validTo, String status,
                              String declaredBy, Instant declaredAt, String liftedBy,
                              Instant liftedAt, String liftReason) {
    }

    /**
     * 冲突声明历史：登记与解除的追加式记录，后续操作不得改写。
     *
     * @param historyId  全局唯一历史记录 ID，自增，即历史排序
     * @param conflictId 所属冲突声明 ID
     * @param action     动作：DECLARED 登记；LIFTED 解除
     * @param actor      操作人：登记为声明人，解除为合规确认人
     * @param reason     当时的原因：登记为冲突原因，解除为解除原因
     * @param validFrom  登记时固化的有效区间起点（UTC，秒精度，左闭）
     * @param validTo    登记时固化的有效区间终点（UTC，秒精度，右开）
     * @param recordedAt 记录时间（UTC，秒精度）
     */
    public record ConflictHistoryRow(long historyId, long conflictId, String action, String actor,
                                     String reason, Instant validFrom, Instant validTo,
                                     Instant recordedAt) {
    }

    /**
     * 评审资格排除记录：批准被标记 CONFLICTED 时追加的只增证据。
     *
     * @param exclusionId        全局唯一排除记录 ID，自增
     * @param segmentId          被排除评审所属段落 ID
     * @param language           被排除评审的目标语言码，小写
     * @param reviewer           被排除的评审人
     * @param sourceVersion      被排除评审当时的源文版本
     * @param translationVersion 被排除评审当时的译文版本
     * @param conflictId         导致排除的冲突声明 ID
     * @param excludedAt         排除时间（UTC，秒精度）
     */
    public record ExclusionRow(long exclusionId, String segmentId, String language, String reviewer,
                               int sourceVersion, int translationVersion, long conflictId,
                               Instant excludedAt) {
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
