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

    /** 锚点状态：LOCKED 生效中；RELEASED 已解除（行保留为证据，不物理删除）。 */
    public static final String ANCHOR_LOCKED = "LOCKED";
    public static final String ANCHOR_RELEASED = "RELEASED";

    /**
     * 法定引文锚点：锚定已批准译文段落内的引用文本；区间左闭右开，按 Java 字符偏移解释。
     *
     * @param anchorId           锚点全局 ID，自增，迁移区间时保持不变
     * @param documentId         所属文档 ID
     * @param segmentId          所属段落 ID
     * @param language           目标语言码，小写
     * @param citationKey        规范化引用标识；同一段落+语言内唯一，解除后也不得再次登记
     * @param rangeStart         区间起点（含），从 0 开始
     * @param rangeEnd           区间终点（不含）
     * @param anchorText         登记/迁移时固化的引用文本，修订后必须逐字符保留
     * @param lockReason         锁定原因，不可变
     * @param createdBy          登记人；解除人必须与之不同
     * @param translationVersion 最近一次登记/迁移时锚定的译文版本
     * @param sourceVersion      登记时批准所对应的源文版本
     * @param status             LOCKED 或 RELEASED
     * @param releasedBy         解除人法务；LOCKED 时为 null
     * @param releaseReason      不可变解除理由；LOCKED 时为 null
     * @param createdAt          登记时间，UTC
     * @param releasedAt         解除时间，UTC；LOCKED 时为 null
     */
    public record CitationAnchorRow(long anchorId, long documentId, String segmentId, String language,
                                    String citationKey, int rangeStart, int rangeEnd, String anchorText,
                                    String lockReason, String createdBy, int translationVersion,
                                    int sourceVersion, String status, String releasedBy, String releaseReason,
                                    Instant createdAt, Instant releasedAt) {
    }

    /**
     * 引文锚点事件：只追加的历史证据（REGISTERED/MIGRATED/RELEASED），字段与 null 语义稳定。
     *
     * @param eventId            事件 ID，历史按此排序
     * @param documentId         所属文档 ID
     * @param anchorId           关联锚点 ID
     * @param segmentId          所属段落 ID
     * @param language           目标语言码，小写
     * @param citationKey        事件发生时的引用标识
     * @param eventType          REGISTERED / MIGRATED / RELEASED
     * @param rangeStart         事件后区间起点（含）
     * @param rangeEnd           事件后区间终点（不含）
     * @param previousStart      迁移前区间起点；仅 MIGRATED 非 null
     * @param previousEnd        迁移前区间终点；仅 MIGRATED 非 null
     * @param anchorText         事件时的引用文本
     * @param reason             REGISTERED/MIGRATED 为锁定原因，RELEASED 为解除理由
     * @param actorId            触发事件的操作者
     * @param translationVersion 事件时译文版本
     * @param sourceVersion      事件时源文版本
     * @param occurredAt         事件发生时间，UTC
     */
    public record CitationAnchorEventRow(long eventId, long documentId, long anchorId, String segmentId,
                                         String language, String citationKey, String eventType,
                                         int rangeStart, int rangeEnd, Integer previousStart, Integer previousEnd,
                                         String anchorText, String reason, String actorId,
                                         int translationVersion, int sourceVersion, Instant occurredAt) {
    }
}
