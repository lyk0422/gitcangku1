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
     * 引文锚点：依附段落+语言的已批准译文，码点区间左闭右开；解除后行保留，解除字段不可变。
     *
     * @param anchorId           锚点 ID，迁移中保持不变
     * @param segmentId          所属段落 ID
     * @param language           目标语言码，小写
     * @param citationKey        规范化引用标识（NFKC、去空白、小写）
     * @param citationKeyRaw     登记时的原始引用标识
     * @param startOffset        起始码点偏移，从 0 开始，闭区间端
     * @param endOffset          结束码点偏移，开区间端
     * @param quoteText          锁定引用文本，迁移时必须保持一致
     * @param translationVersion 区间当前所在译文版本
     * @param lockReason         登记时的锁定原因，不可变
     * @param createdBy          创建人，迁移保留原创建人
     * @param createdAt          登记时间，UTC
     * @param status            ACTIVE 生效中；RELEASED 已解除（终态）
     * @param migratedBy         最近一次迁移操作人；未迁移为 null
     * @param migratedAt         最近一次迁移时间，UTC；未迁移为 null
     * @param releasedBy         解除确认人（法务、非创建人）；未解除为 null
     * @param releasedReason     不可变解除理由；未解除为 null
     * @param releasedAt         解除时间，UTC；未解除为 null
     */
    public record AnchorRow(long anchorId, String segmentId, String language, String citationKey,
                            String citationKeyRaw, int startOffset, int endOffset, String quoteText,
                            int translationVersion, String lockReason, String createdBy, Instant createdAt,
                            String status, String migratedBy, Instant migratedAt,
                            String releasedBy, String releasedReason, Instant releasedAt) {
    }

    /**
     * 引文锚点事件：登记、迁移、解除的不可变追加历史。
     *
     * @param eventId            事件 ID，即历史排序
     * @param anchorId           关联锚点 ID
     * @param segmentId          所属段落 ID
     * @param language           目标语言码，小写
     * @param eventType          REGISTERED / MIGRATED / RELEASED
     * @param translationVersion 事件时锚点所在译文版本
     * @param startOffset        该事件下区间起始码点偏移
     * @param endOffset          该事件下区间结束码点偏移
     * @param actor              事件操作人
     * @param reason             登记为锁定原因，解除为不可变解除理由，迁移可为 null
     * @param eventAt            事件时间，UTC
     */
    public record AnchorEventRow(long eventId, long anchorId, String segmentId, String language,
                                 String eventType, int translationVersion, int startOffset, int endOffset,
                                 String actor, String reason, Instant eventAt) {
    }

    /**
     * 发布快照固化的锚点引用：发布时写入，之后锚点迁移或解除均不改写。
     *
     * @param anchorId           被固化的锚点 ID（可用于追溯锚点当前状态与完整历史）
     * @param segmentId          发布时所属段落 ID
     * @param language           目标语言码，小写
     * @param citationKey        发布时的规范化引用标识
     * @param startOffset        发布时区间起始码点偏移
     * @param endOffset          发布时区间结束码点偏移
     * @param translationVersion 发布时锚点所在译文版本
     */
    public record ReleaseAnchorRefRow(long anchorId, String segmentId, String language, String citationKey,
                                      int startOffset, int endOffset, int translationVersion) {
    }
}
