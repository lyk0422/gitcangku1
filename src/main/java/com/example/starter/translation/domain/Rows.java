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
     * 审校批注：绑定段落、语言与创建时译文版本的非空锚点区间，随定点编辑迁移或孤立，仅创建人可确认解决。
     *
     * @param annotationId       全局唯一批注 ID，自增
     * @param segmentId          所属段落 ID
     * @param language           目标语言码，小写
     * @param translationVersion 批注创建时绑定的译文版本，创建后不改写
     * @param startOffset        锚点起点，按 Unicode 码点从 0 计数，左闭；孤立后保留最后位置仅供追溯
     * @param endOffset          锚点终点，按 Unicode 码点从 0 计数，右开；startOffset < endOffset
     * @param quote              创建时锚点引用的原文，孤立后仅保留该引用，不改写
     * @param body               审校意见内容，非空
     * @param author             批注创建人，取创建时 X-Actor-Id，仅创建人可确认解决
     * @param status             批注状态：OPEN 未解决、ORPHAN 已孤立（锚点失效）、RESOLVED 已解决
     */
    public record AnnotationRow(long annotationId, String segmentId, String language,
                                int translationVersion, int startOffset, int endOffset,
                                String quote, String body, String author, String status) {
        /** 未解决状态：OPEN。 */
        public static final String STATUS_OPEN = "OPEN";
        /** 未解决状态：ORPHAN，锚点失效仅保留原引用。 */
        public static final String STATUS_ORPHAN = "ORPHAN";
        /** 已解决状态：RESOLVED。 */
        public static final String STATUS_RESOLVED = "RESOLVED";

        /** 是否为未解决批注（OPEN 或 ORPHAN）。 */
        public boolean unresolved() {
            return STATUS_OPEN.equals(status) || STATUS_ORPHAN.equals(status);
        }
    }
}