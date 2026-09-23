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
     * 段落：文档内唯一 segmentId，含源文、源文版本、当前结构位置与状态。
     *
     * @param segmentId     文档内唯一段落 ID（含已废止段）
     * @param sourceText    源文正文，UTF-8
     * @param sourceVersion 源文版本，从 1 开始，每次源文修订加一；结构修订新段从 1 开始
     * @param position      当前结构中的从 0 开始的连续位置序号；SUPERSEDED 段保留被替换时的位置
     * @param current       是否为当前结构中的段；false 表示已被结构修订废止（SUPERSEDED）
     */
    public record SegmentRow(String segmentId, String sourceText, int sourceVersion,
                             int position, boolean current) {
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
     * 结构修订事务记录。
     *
     * @param changeKey                 全局唯一结构修订键
     * @param operation                 操作类型：SPLIT=拆分，MERGE=合并
     * @param documentVersion           修订成功后生成的文档草稿版本
     * @param expectedDocumentVersion   提交时携带的期望文档草稿版本
     */
    public record StructureChangeRow(String changeKey, String operation,
                                     int documentVersion, int expectedDocumentVersion) {
    }

    /**
     * 源段结构血缘行：旧源段与新源段的有序对应。
     *
     * @param changeKey        所属结构修订键
     * @param oldSegmentId     旧源段 ID
     * @param oldSourceVersion 修订时旧源段的源文版本
     * @param newSegmentId     新源段 ID
     * @param ordinal          有序序号：拆分时为新段顺序，合并时为旧段连续顺序
     */
    public record SegmentLineageRow(String changeKey, String oldSegmentId, int oldSourceVersion,
                                    String newSegmentId, int ordinal) {
    }

    /**
     * 新段旧译文 REFERENCE 候选：按有序映射直接拼接的旧译文正文。
     *
     * @param changeKey    生成该候选的结构修订键
     * @param newSegmentId 候选所属新源段 ID
     * @param language     目标语言码，小写
     * @param content      按映射顺序直接拼接的旧译文正文（无分隔符）
     */
    public record TranslationReferenceRow(String changeKey, String newSegmentId,
                                          String language, String content) {
    }

    /**
     * 跨语言译文血缘片段：新段到旧译文片段的有序来源映射及字符边界。
     *
     * @param changeKey              所属结构修订键
     * @param newSegmentId           新源段 ID
     * @param language               目标语言码，小写
     * @param oldSegmentId           旧译文所属旧源段 ID
     * @param oldTranslationVersion  修订时旧译文的译文版本
     * @param ordinal                同一新段同一语言内从 0 开始的有序片段序号
     * @param startOffset            片段在拼接候选中的起始字符偏移（含）
     * @param endOffset              片段在拼接候选中的结束字符偏移（不含）
     */
    public record LineageFragmentRow(String changeKey, String newSegmentId, String language,
                                     String oldSegmentId, int oldTranslationVersion,
                                     int ordinal, int startOffset, int endOffset) {
    }
}
