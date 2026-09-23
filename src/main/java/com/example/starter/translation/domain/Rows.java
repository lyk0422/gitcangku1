package com.example.starter.translation.domain;

import java.util.List;

/**
 * 数据库行记录，与 schema.sql 中各表 COMMENT 保持一致。
 */
public final class Rows {

    private Rows() {
    }

    /** 段状态：CURRENT 当前结构中的段；SUPERSEDED 已被结构修订取代的旧段。 */
    public static final String SEGMENT_CURRENT = "CURRENT";
    public static final String SEGMENT_SUPERSEDED = "SUPERSEDED";

    /**
     * 文档：全局唯一 documentId，含 1~5 种目标语言及草稿/发布/术语版本。
     *
     * @param documentId       全局唯一文档 ID，自增
     * @param targetLanguages  目标语言列表，小写语言码，1~5 种
     * @param draftVersion     文档草稿版本，从 1 开始；增段落或修改源文/译文/术语/结构时加一
     * @param publishedVersion 已发布版本号，从 0 开始，每次成功发布加一
     * @param termVersion      当前术语版本，从 0 开始（0 表示尚未建立术语版本）
     */
    public record DocumentRow(long documentId, List<String> targetLanguages,
                              int draftVersion, int publishedVersion, int termVersion) {
    }

    /**
     * 段落：文档内唯一 segmentId，含源文、源文版本、状态与当前结构序号。
     *
     * @param segmentId            文档内唯一段落 ID
     * @param sourceText           源文正文，UTF-8
     * @param sourceVersion        源文版本，从 1 开始，每次源文修订加一
     * @param status               段状态：CURRENT / SUPERSEDED
     * @param position             当前结构中的序号，从 1 开始连续
     * @param createdChangeKey     创建该段的结构修订 changeKey；直接建段时为 null
     * @param supersededChangeKey  取代该段的结构修订 changeKey；null 表示当前段
     */
    public record SegmentRow(String segmentId, String sourceText, int sourceVersion, String status,
                             int position, String createdChangeKey, String supersededChangeKey) {
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
     * 结构修订事务记录：changeKey 全局唯一。
     *
     * @param changeKey               结构修订幂等键
     * @param documentId              所属文档 ID
     * @param changeType              修订类型 SPLIT / MERGE
     * @param expectedDocumentVersion 提交时期望的文档草稿版本
     * @param documentVersion         成功后生成的新文档草稿版本
     * @param expectedTermVersion     提交时涉及语言的当前术语版本
     */
    public record StructureChangeRow(String changeKey, long documentId, String changeType,
                                     int expectedDocumentVersion, int documentVersion,
                                     int expectedTermVersion) {
    }

    /**
     * 源段结构血缘：旧段到新段的有序映射。
     *
     * @param changeKey    所属结构修订 changeKey
     * @param oldSegmentId 旧段 ID
     * @param newSegmentId 新段 ID
     * @param ordinal      有序序号，从 1 开始
     */
    public record SegmentLineageRow(String changeKey, String oldSegmentId, String newSegmentId, int ordinal) {
    }

    /**
     * 译文片段血缘：新段某语言到一个旧译文片段的映射及字符边界。
     *
     * @param changeKey    所属结构修订 changeKey
     * @param language     目标语言码，小写
     * @param oldSegmentId 片段来源旧段 ID
     * @param newSegmentId 片段所属新段 ID
     * @param ordinal      片段在新段参考译文中的有序序号，从 1 开始
     * @param startOffset  片段在旧译文中的起始字符偏移（含），从 0 开始
     * @param endOffset    片段在旧译文中的结束字符偏移（不含）
     */
    public record TranslationLineageRow(String changeKey, String language, String oldSegmentId,
                                        String newSegmentId, int ordinal, int startOffset, int endOffset) {
    }

    /**
     * 结构修订参考候选：旧译文片段按序拼接的候选正文及片段边界 JSON。
     *
     * @param newSegmentId 新段 ID
     * @param language      目标语言码，小写
     * @param changeKey     产生该候选的结构修订 changeKey
     * @param content       拼接参考正文；旧译文缺失时对应片段为空串
     * @param boundaryJson  片段边界 JSON
     */
    public record TranslationReferenceRow(String newSegmentId, String language, String changeKey,
                                          String content, String boundaryJson) {
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
