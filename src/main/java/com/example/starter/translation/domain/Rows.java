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
     * 段落：文档内唯一 segmentId，含源文、源文版本及规范排序的版式占位符集合。
     *
     * @param segmentId     文档内唯一段落 ID
     * @param sourceText    源文正文，UTF-8
     * @param sourceVersion 源文版本，从 1 开始；修订正文或占位符集合均加一
     * @param placeholders  源段声明的占位符集合，去重后按字典序排序；译文须保留完全相同的集合及出现次数
     */
    public record SegmentRow(String segmentId, String sourceText, int sourceVersion,
                             List<String> placeholders) {
    }

    /**
     * 译文：按段落与语言唯一，保存正文、作者、所依据源文版本、绑定术语版本、递增译文版本及修订状态。
     *
     * @param segmentId          所属段落 ID
     * @param language           目标语言码，小写
     * @param content            译文正文，UTF-8
     * @param author             译文作者，取提交时 X-Actor-Id
     * @param sourceVersion      译文所依据的源文版本；提交时必须等于当前源文版本
     * @param translationVersion 译文版本，从 1 开始，每次重新提交加一
     * @param termVersion        译文提交时绑定的术语版本；不等于当前术语版本时视为术语过期
     * @param status             修订状态：ACTIVE=可批准/发布；NEEDS_REVISION=源段已修订须重新提交
     */
    public record TranslationRow(String segmentId, String language, String content, String author,
                                 int sourceVersion, int translationVersion, int termVersion,
                                 String status) {

        /** 源段未发生修订：可进入批准与发布流程。 */
        public static final String ACTIVE = "ACTIVE";

        /** 源段正文或占位符集合已修订：旧译文禁止发布，须重新提交。 */
        public static final String NEEDS_REVISION = "NEEDS_REVISION";
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
     * 操作历史证据：只追加，任何后续操作不得更新或删除。
     *
     * @param seq           全局单调递增序号，与 occurredAt 共同保证稳定排序
     * @param documentId    所属文档 ID
     * @param operationType 操作类型（SOURCE_REVISED、TRANSLATION_SUBMITTED 等）
     * @param actor         操作者 X-Actor-Id；文档级无操作者操作为 null
     * @param segmentId     关联段落 ID；文档级操作为 null
     * @param language      关联目标语言码；文档级或跨语言操作为 null
     * @param requestId     写操作的幂等 requestId
     * @param occurredAt    操作发生时间，UTC 纪元毫秒
     * @param fingerprint   SHA-256 指纹，覆盖操作者、操作类型、规范化集合与全部结果字段
     * @param detailJson    结果相关字段的规范化 JSON 明细
     */
    public record OperationLogRow(long seq, long documentId, String operationType, String actor,
                                  String segmentId, String language, String requestId, long occurredAt,
                                  String fingerprint, String detailJson) {
    }
}
