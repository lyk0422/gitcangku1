package com.example.starter.translation.domain;

import java.util.List;

/**
 * 数据库行记录，与 schema.sql 中各表 COMMENT 保持一致。
 */
public final class Rows {

    private Rows() {
    }

    /**
     * 文档：全局唯一 documentId，含 1~5 种目标语言、草稿/发布/文档术语版本及引用的全局术语库版本。
     *
     * @param documentId        全局唯一文档 ID，自增
     * @param targetLanguages   目标语言列表，小写语言码，1~5 种
     * @param draftVersion      文档草稿版本，从 1 开始；增段落、修改源文/译文/文档术语或升级全局引用时加一；
     *                          全局术语库更新不改变该版本
     * @param publishedVersion  已发布版本号，从 0 开始，每次成功发布加一
     * @param termVersion       当前文档自身术语版本，从 0 开始（0 表示尚未建立术语版本）
     * @param globalTermVersion 引用的全局术语库版本，从 0 开始（0 表示尚未引用任何全局版本）；
     *                          落后于最新全局版本时译文视为术语过期，须显式升级
     */
    public record DocumentRow(long documentId, List<String> targetLanguages,
                              int draftVersion, int publishedVersion, int termVersion,
                              int globalTermVersion) {
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
     * 译文：按段落与语言唯一，保存正文、作者、所依据源文版本、绑定的文档术语版本与全局术语版本及递增译文版本。
     *
     * @param segmentId         所属段落 ID
     * @param language          目标语言码，小写
     * @param content           译文正文，UTF-8
     * @param author            译文作者，取提交时 X-Actor-Id
     * @param sourceVersion     译文所依据的源文版本；提交时必须等于当前源文版本
     * @param translationVersion 译文版本，从 1 开始，每次重新提交加一
     * @param termVersion       译文提交时绑定的文档术语版本；与文档当前术语版本不一致时视为术语过期
     * @param globalTermVersion 译文提交时文档引用的全局术语库版本；
     *                          与文档当前引用不一致（含引用落后于最新全局版本）时视为术语过期
     */
    public record TranslationRow(String segmentId, String language, String content, String author,
                                 int sourceVersion, int translationVersion, int termVersion,
                                 int globalTermVersion) {
    }

    /**
     * 文档术语规则：属于某文档术语版本的不可变规则，按 sourceTerm 与目标语言唯一。
     *
     * @param sourceTerm          源文术语，Unicode 原文、区分大小写，按连续子串匹配
     * @param language            目标语言码，小写，须在文档目标语言内
     * @param requiredTranslation 该术语在目标语言中的必译文本，非空；suppressed 为 TRUE 时不参与校验，仅占位保存
     * @param suppressed          是否抑制对应全局规则：TRUE 表示同 sourceTerm+语言的全局规则不参与校验，
     *                            生效集来源标为 SUPPRESSED；FALSE 表示文档规则整条覆盖全局规则
     */
    public record TermRuleRow(String sourceTerm, String language, String requiredTranslation, boolean suppressed) {
    }

    /**
     * 全局术语规则：属于某全局术语版本的不可变规则，按 sourceTerm 与目标语言唯一，语言不绑定任何文档。
     *
     * @param sourceTerm          源文术语，Unicode 原文、区分大小写，按连续子串匹配
     * @param language            目标语言码，小写，可为任意语言；合成文档生效集时仅保留文档目标语言内的规则
     * @param requiredTranslation 该术语在目标语言中的必译文本，非空
     */
    public record GlobalTermRuleRow(String sourceTerm, String language, String requiredTranslation) {
    }

    /**
     * 批准：按段落与语言唯一；源文或译文版本改变、术语过期后先前批准不再满足发布条件。
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
}
