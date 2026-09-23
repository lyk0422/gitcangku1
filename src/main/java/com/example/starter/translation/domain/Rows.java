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
     * 术语版本退役单：指定退役术语版本、同语言替代版本与左闭右开 UTC 生效窗口。
     *
     * @param retirementId       退役单 ID，自增
     * @param documentId         所属文档 ID
     * @param retirementKey      退役单业务键，全局唯一
     * @param termVersion        被退役的术语版本
     * @param language           退役适用的目标语言码，小写
     * @param replacementVersion 替代术语版本，创建与激活时均须 ACTIVE 且不形成替代环
     * @param effectiveFromUtc   窗口起（含），UTC 毫秒
     * @param effectiveToUtc     窗口止（不含），UTC 毫秒；窗口结束不自动恢复
     * @param status             退役单状态：DRAFT 已创建未激活，ACTIVE 已激活
     * @param activatedAtUtc     激活时刻 UTC 毫秒；未激活为 null
     * @param previewJson        创建时生成的只读影响预览 JSON
     * @param impactSnapshotJson 激活时冻结的影响集合 JSON；未激活为 null
     */
    public record TermRetirementRow(long retirementId, long documentId, String retirementKey,
                                    int termVersion, String language, int replacementVersion,
                                    long effectiveFromUtc, long effectiveToUtc,
                                    String status, Long activatedAtUtc,
                                    String previewJson, String impactSnapshotJson) {
        /** 退役单状态：已创建未激活。 */
        public static final String STATUS_DRAFT = "DRAFT";
        /** 退役单状态：已激活，影响集合已冻结。 */
        public static final String STATUS_ACTIVE = "ACTIVE";
    }

    /**
     * 退役影响清单条目：创建预览与激活冻结各写一份；已发布快照不可变，仅登记历史影响。
     *
     * @param documentId       所属文档 ID
     * @param retirementId     所属退役单 ID
     * @param publishedVersion PUBLISHED 条目的发布版本号；DRAFT/APPROVED 条目为 null
     * @param segmentId        条目段落 ID；仅受影响发布版本全部段落无术语命中时以 null 标记行登记
     * @param language         目标语言码，小写
     * @param kind             条目类型：DRAFT 普通草稿，APPROVED 激活时撤批，PUBLISHED 历史发布快照
     * @param hitTermsJson     实际命中的术语位置 JSON 数组（sourceTerm 与字符偏移）
     */
    public record RetirementImpactRow(long documentId, long retirementId, Integer publishedVersion,
                                      String segmentId, String language, String kind, String hitTermsJson) {
        /** 影响类型：普通草稿。 */
        public static final String KIND_DRAFT = "DRAFT";
        /** 影响类型：已批准译文（激活时撤批）。 */
        public static final String KIND_APPROVED = "APPROVED";
        /** 影响类型：历史发布快照。 */
        public static final String KIND_PUBLISHED = "PUBLISHED";
    }

    /**
     * 草稿迁移记录：成功迁移逐稿增版，保存旧/新文本摘要、哈希与规则版本。
     *
     * @param documentId      所属文档 ID
     * @param retirementId    所属退役单 ID
     * @param expectedVersion 迁移命令声明的 expectedVersion（译文版本乐观校验）
     * @param ruleVersion     替换校验使用的 replacementVersion（规则版本）
     * @param oldSummary      旧文本摘要（SHA-256 前 32 字符）
     * @param oldTextHash     旧文本完整 SHA-256
     * @param newSummary      新文本摘要（SHA-256 前 32 字符）
     * @param newTextHash     新文本完整 SHA-256
     * @param segmentId       被迁移草稿的段落 ID
     * @param language        被迁移草稿的语言码，小写
     * @param createdAtUtc    迁移成功时间 UTC 毫秒
     * @param requestLogId    关联的幂等请求 ID
     */
    public record DraftMigrationRow(long documentId, long retirementId, int expectedVersion,
                                    int ruleVersion, String oldSummary, String oldTextHash,
                                    String newSummary, String newTextHash,
                                    String segmentId, String language,
                                    long createdAtUtc, String requestLogId) {
    }
}
