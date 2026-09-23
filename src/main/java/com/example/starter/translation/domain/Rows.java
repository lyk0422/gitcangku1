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

    /** 评审阶段：语言阶段与合规阶段。 */
    public enum ReviewStage {
        LANGUAGE,
        COMPLIANCE
    }

    /** 票决定：批准或拒绝。 */
    public enum VoteDecision {
        APPROVE,
        REJECT
    }

    /**
     * 双阶段评审策略的不可变版本。
     *
     * @param documentId          所属文档 ID
     * @param language            目标语言码，小写
     * @param policyVersion       策略版本，从 1 开始单调递增
     * @param languageReviewers   语言阶段候选审核人集合（有序去重）
     * @param languageQuorum      语言阶段通过所需 APPROVE 人数
     * @param complianceReviewers 合规阶段候选审核人集合（有序去重）
     * @param complianceQuorum    合规阶段通过所需 APPROVE 人数
     */
    public record ReviewPolicyRow(long documentId, String language, int policyVersion,
                                  List<String> languageReviewers, int languageQuorum,
                                  List<String> complianceReviewers, int complianceQuorum) {
    }

    /**
     * 评审票记录；历史票全部保留审计。
     *
     * @param voteId             票记录自增主键
     * @param voteKey            调用方提供的全局唯一票键
     * @param documentId         所属文档 ID
     * @param segmentId          所属段落 ID
     * @param language           目标语言码，小写
     * @param stage              评审阶段
     * @param reviewer           投票审核人
     * @param sourceVersion      票针对的源文版本
     * @param translationVersion 票针对的译文版本
     * @param termVersion        票针对的术语版本
     * @param policyVersion      票投出时激活的策略版本
     * @param decision           票决定
     * @param voteVersion        同系列票的单调递增版本
     * @param requestId          产生该票的写操作 requestId
     */
    public record ReviewVoteRow(long voteId, String voteKey, long documentId, String segmentId, String language,
                                ReviewStage stage, String reviewer, int sourceVersion, int translationVersion,
                                int termVersion, int policyVersion, VoteDecision decision, int voteVersion,
                                String requestId) {
    }
}
