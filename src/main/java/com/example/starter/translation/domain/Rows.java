package com.example.starter.translation.domain;

import java.util.List;

/**
 * 数据库行记录，与 schema.sql 中各表 COMMENT 保持一致。
 */
public final class Rows {

    private Rows() {
    }

    /**
     * 文档：全局唯一 documentId，含 1~5 种目标语言及草稿/发布版本。
     *
     * @param documentId       全局唯一文档 ID，自增
     * @param targetLanguages  目标语言列表，小写语言码，1~5 种
     * @param draftVersion     文档草稿版本，从 1 开始；增段落或修改源文/译文时加一
     * @param publishedVersion 已发布版本号，从 0 开始，每次成功发布加一
     */
    public record DocumentRow(long documentId, List<String> targetLanguages,
                              int draftVersion, int publishedVersion) {
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
     * 译文：按段落与语言唯一，保存正文、作者、所依据源文版本及递增译文版本。
     *
     * @param segmentId          所属段落 ID
     * @param language           目标语言码，小写
     * @param content            译文正文，UTF-8
     * @param author             译文作者，取提交时 X-Actor-Id
     * @param sourceVersion      译文所依据的源文版本；提交时必须等于当前源文版本
     * @param translationVersion 译文版本，从 1 开始，每次重新提交加一
     */
    public record TranslationRow(String segmentId, String language, String content, String author,
                                 int sourceVersion, int translationVersion) {
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
     * 批量审核记录：batchKey 全局唯一、不可变；固化提交时的文档草稿版本（仅记录用，不做前置校验）、
     * 审核人与审核时刻。批量审核不改变文档 draftVersion。
     *
     * @param batchKey             全局唯一批次键，承担批量审核的幂等键
     * @param documentId           所属文档 ID
     * @param expectedDraftVersion 提交批次时客户端所见文档草稿版本，仅固化进记录
     * @param reviewer             审核人，取批量审核时 X-Actor-Id，不得是任一条译文作者
     * @param approvedAt           审核时刻，数据库默认时区时间戳字符串
     */
    public record BatchApprovalRow(String batchKey, long documentId, int expectedDraftVersion,
                                   String reviewer, String approvedAt) {
    }

    /**
     * 批量审核明细：不可变，固化批内每条译文在审核时刻的段落、语言与译文版本，按位置稳定排序。
     *
     * @param batchKey            所属批次键
     * @param lineNo              批内行号，从 1 开始，用于稳定排序与逐条原因定位
     * @param segmentId           所属段落 ID
     * @param language            目标语言码，小写
     * @param translationVersion  批准时的译文版本
     * @param sourceVersion       批准时译文所依据的源文版本
     */
    public record BatchApprovalItemRow(String batchKey, int lineNo, long documentId, String segmentId,
                                       String language, int translationVersion, int sourceVersion) {
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
