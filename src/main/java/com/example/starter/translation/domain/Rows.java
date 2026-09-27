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

    /** 锚点状态：ACTIVE 生效中；RELEASED 已由异于创建人的法务角色确认解除。 */
    public enum AnchorStatus {
        ACTIVE,
        RELEASED
    }

    /**
     * 引文锚点：已批准译文段落上登记的引用锁定，按“文档+段落+引用标识”唯一（引文随原文段落，
     * 与其译文语言无关），区间按指定语言的译文正文计算。修订迁移时区间原地更新、ID 与引用文本不变。
     *
     * @param anchorId           锚点自增 ID，迁移中保持不变
     * @param documentId         所属文档 ID
     * @param segmentId          所属段落 ID
     * @param language           锚点区间所基于的目标语言码，小写
     * @param citationKey        规范化引用标识（去空白、小写），同文档同段落唯一，解除后也不可复用
     * @param startOffset        区间起点（含），左闭右开
     * @param endOffset          区间终点（不含）
     * @param anchorText         登记时固化的引用文本，迁移不改变该文本
     * @param lockReason         锁定原因，非空
     * @param createdBy          创建人（登记时 X-Actor-Id）
     * @param createdAt          登记时间，UTC
     * @param translationVersion 当前绑定的译文版本；登记时为已批准版本，随修订迁移更新
     * @param status             ACTIVE / RELEASED
     * @param releasedBy         解除确认人（法务角色，且不同于 createdBy）；未解除为 null
     * @param releaseReason      不可变解除理由；未解除为 null
     * @param releasedAt         解除时间 UTC；未解除为 null
     */
    public record CitationAnchorRow(long anchorId, long documentId, String segmentId, String language,
                                    String citationKey, int startOffset, int endOffset, String anchorText,
                                    String lockReason, String createdBy, Instant createdAt,
                                    int translationVersion, AnchorStatus status,
                                    String releasedBy, String releaseReason, Instant releasedAt) {
    }

    /**
     * 锚点不可变事件：REGISTERED 登记、MIGRATED 修订迁移（仅区间实际变动时记录）、RELEASED 解除。
     * 解除后仍保留全部事件，发布快照明细据此追溯。
     *
     * @param eventId     事件自增 ID
     * @param anchorId    锚点 ID，迁移中保持不变
     * @param documentId  所属文档 ID
     * @param segmentId   所属段落 ID
     * @param language    目标语言码
     * @param citationKey 规范化引用标识
     * @param eventType   REGISTERED / MIGRATED / RELEASED
     * @param fromStart   变动前区间起点（含）；REGISTERED/RELEASED 为 null
     * @param fromEnd     变动前区间终点（不含）；REGISTERED/RELEASED 为 null
     * @param toStart     变动后区间起点（含）；RELEASED 沿用解除前区间
     * @param toEnd       变动后区间终点（不含）；RELEASED 沿用解除前区间
     * @param anchorText  事件时引用文本
     * @param actorId     操作人
     * @param actorRole   操作人角色（解除时必须为 LEGAL）
     * @param reason      登记/迁移为锁定原因；解除为不可变解除理由
     * @param eventTime   事件时间，UTC
     */
    public record CitationAnchorEventRow(long eventId, long anchorId, long documentId, String segmentId,
                                         String language, String citationKey, String eventType,
                                         Integer fromStart, Integer fromEnd, int toStart, int toEnd,
                                         String anchorText, String actorId, String actorRole, String reason,
                                         Instant eventTime) {
    }
}
