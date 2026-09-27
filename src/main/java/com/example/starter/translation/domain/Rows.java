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
     * 评审（只追加证据）：同一评审人每次提交产生新行；法定人数按评审人最新行且版本匹配计算。
     *
     * @param reviewId           评审记录 ID，自增，越大越新；旧行不可修改
     * @param segmentId          所属段落 ID
     * @param language           目标语言码，小写
     * @param reviewer           评审人，取提交时 X-Actor-Id，不得是译文作者
     * @param decision           评审决定：APPROVED 批准、REJECTED 否决
     * @param decisionReason     否决原因（仅 REJECTED 非空），提交时固化，批准时为 null
     * @param reviewStatus       评审状态：ACTIVE 计入法定人数；CONFLICTED 被有效冲突排除，不计入
     * @param conflictId         置为 CONFLICTED 时固化的冲突声明 ID；ACTIVE 时为 null
     * @param sourceVersion      评审时的源文版本，发布校验须等于当前源文版本
     * @param translationVersion 评审时的译文版本，发布校验须等于当前译文版本
     * @param reviewedAt         评审提交时间，UTC，毫秒精度
     */
    public record ApprovalRow(long reviewId, String segmentId, String language, String reviewer,
                              String decision, String decisionReason, String reviewStatus, Long conflictId,
                              int sourceVersion, int translationVersion, Instant reviewedAt) {

        /** 是否计入当前法定人数：状态为 ACTIVE。 */
        public boolean active() {
            return "ACTIVE".equals(reviewStatus);
        }

        /** 是否为批准决定。 */
        public boolean approved() {
            return "APPROVED".equals(decision);
        }
    }

    /**
     * 评审利益冲突声明：评审人对文档与语种声明左闭右开 UTC 有效区间及原因。
     *
     * @param conflictId    冲突声明 ID，自增
     * @param documentId    所属文档 ID
     * @param reviewer      存在利益冲突的评审人
     * @param language      受限语种，小写语言码
     * @param validFrom     有效区间起点（含），UTC
     * @param validTo       有效区间终点（不含），UTC，左闭右开
     * @param reason        冲突原因，非空，登记后不可修改
     * @param conflictStatus 声明状态：ACTIVE 有效中、RESOLVED 已由合规角色解除
     * @param declaredBy    声明登记操作人
     * @param declaredAt    声明登记时间 UTC
     * @param resolvedBy    解除确认人（合规角色且不同于声明人），未解除为 null
     * @param resolvedAt    解除时间 UTC，未解除为 null
     * @param resolveReason 解除原因，未解除为 null
     */
    public record ConflictDeclarationRow(long conflictId, long documentId, String reviewer, String language,
                                         Instant validFrom, Instant validTo, String reason, String conflictStatus,
                                         String declaredBy, Instant declaredAt, String resolvedBy,
                                         Instant resolvedAt, String resolveReason) {

        /** 声明是否未解除。 */
        public boolean active() {
            return "ACTIVE".equals(conflictStatus);
        }

        /** 给定 UTC 时刻是否落在左闭右开有效区间内。 */
        public boolean covers(Instant at) {
            return !at.isBefore(validFrom) && at.isBefore(validTo);
        }
    }

    /**
     * 冲突历史事件：只追加，固化登记/解除时的区间与原因。
     *
     * @param eventId    历史事件 ID，自增，按其排序
     * @param conflictId 所属冲突声明 ID
     * @param documentId 所属文档 ID
     * @param eventType  事件类型：DECLARED 登记、RESOLVED 解除
     * @param actor      事件操作人
     * @param actorRole  事件操作人角色：REVIEWER、COMPLIANCE
     * @param validFrom  事件时固化的区间起点 UTC（含）
     * @param validTo    事件时固化的区间终点 UTC（不含）
     * @param reason     事件原因：冲突原因或解除原因
     * @param createdAt  事件追加时间 UTC，毫秒精度
     */
    public record ConflictEventRow(long eventId, long conflictId, long documentId, String eventType,
                                   String actor, String actorRole, Instant validFrom, Instant validTo,
                                   String reason, Instant createdAt) {
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
