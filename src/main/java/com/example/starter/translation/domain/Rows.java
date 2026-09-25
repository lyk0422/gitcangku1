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
     * 区域译文变体：按段落+语言+区域唯一；区域代码为 DEFAULT 或具体区域，同一译文版本仅一条有效变体。
     *
     * @param segmentId          所属段落 ID
     * @param language           目标语言码，小写；变体不得跨语言生效
     * @param regionCode         适用区域代码，大写；DEFAULT 表示全局默认，其余为具体区域
     * @param content            区域变体译文正文，UTF-8
     * @param author             变体作者，取创建时 X-Actor-Id
     * @param translationVersion 变体绑定的译文版本；不等于当前译文版本时变体失效
     * @param termVersion        变体创建时绑定的术语版本；不等于当前术语版本时变体失效
     * @param variantVersion     变体版本，按段落+语言+区域从 1 开始单调递增，重建加一
     * @param status             变体状态：PENDING 待批准、ACTIVE 有效、REVOKED 已撤销；仅 ACTIVE 参与区域解析
     */
    public record RegionalVariantRow(String segmentId, String language, String regionCode, String content,
                                     String author, int translationVersion, int termVersion,
                                     int variantVersion, String status) {
    }

    /**
     * 发布区域解析记录：每次发布逐段落固化请求区域、实际选用区域、译文版本与回退来源，不可修改。
     *
     * @param publishedVersion   发布版本号，从 1 开始
     * @param segmentId          所属段落 ID
     * @param language           目标语言码，小写
     * @param requestedRegion    发布请求的区域代码，大写；未指定区域时为 DEFAULT
     * @param resolvedRegion     实际选用的区域代码
     * @param translationVersion 最终选用的译文版本
     * @param fallbackSource     回退来源：EXACT 命中请求区域变体、FALLBACK_DEFAULT 回退 DEFAULT 变体、
     *                           BASE 未使用变体取基础译文
     */
    public record ReleaseResolutionRow(int publishedVersion, String segmentId, String language,
                                       String requestedRegion, String resolvedRegion,
                                       int translationVersion, String fallbackSource) {
    }
}
