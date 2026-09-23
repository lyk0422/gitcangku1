package com.example.starter.translation.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 多语种段落发布 API 的请求与响应体。所有写操作均携带全局唯一 requestId。
 */
public final class ApiDtos {

    private ApiDtos() {
    }

    /** 初始段落输入。 */
    public record SegmentInput(
            @NotBlank(message = "segmentId 不能为空") @Size(max = 64) String segmentId,
            @NotBlank(message = "sourceText 不能为空") String sourceText) {
    }

    /** 建文档请求：1~5 种目标语言，可携带初始段落。 */
    public record CreateDocumentRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotNull(message = "targetLanguages 不能为空") @Size(min = 1, max = 5, message = "目标语言须为 1~5 种")
            List<@NotBlank(message = "语言码不能为空") String> targetLanguages,
            @NotNull(message = "segments 不能为空") List<@Valid SegmentInput> segments) {
    }

    /** 增加段落请求。 */
    public record AddSegmentRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotBlank(message = "segmentId 不能为空") @Size(max = 64) String segmentId,
            @NotBlank(message = "sourceText 不能为空") String sourceText) {
    }

    /** 源文修订请求。 */
    public record ReviseSourceRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotBlank(message = "sourceText 不能为空") String sourceText) {
    }

    /** 译文提交请求；作者取 X-Actor-Id 请求头。 */
    public record SubmitTranslationRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotBlank(message = "content 不能为空") String content,
            @Positive(message = "sourceVersion 必须为正数") int sourceVersion) {
    }

    /** 译文批准请求；审核人取 X-Actor-Id 请求头。 */
    public record ApproveTranslationRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @Positive(message = "translationVersion 必须为正数") int translationVersion) {
    }

    /** 发布请求：携带期望的草稿与发布版本做乐观校验。 */
    public record PublishRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @Positive(message = "expectedDraftVersion 必须为正数") int expectedDraftVersion,
            @PositiveOrZero(message = "expectedPublishedVersion 不能为负数") int expectedPublishedVersion) {
    }

    /** 术语规则输入：sourceTerm 区分大小写，requiredTranslation 非空。 */
    public record TermRuleInput(
            @NotBlank(message = "sourceTerm 不能为空") @Size(max = 512) String sourceTerm,
            @NotBlank(message = "language 不能为空") String language,
            @NotBlank(message = "requiredTranslation 不能为空") @Size(max = 2048) String requiredTranslation) {
    }

    /** 新增术语版本请求：携带期望的当前术语版本与完整规则集（0~100 条）。 */
    public record UpdateTermsRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @PositiveOrZero(message = "expectedTermVersion 不能为负数") int expectedTermVersion,
            @NotNull(message = "rules 不能为空") @Size(max = 100, message = "术语规则最多 100 条")
            List<@Valid TermRuleInput> rules) {
    }

    /** 建文档响应。 */
    public record DocumentResponse(long documentId, int draftVersion, int publishedVersion,
                                   List<String> targetLanguages) {
    }

    /** 段落相关写操作响应。 */
    public record SegmentResponse(long documentId, String segmentId, int sourceVersion, int draftVersion) {
    }

    /** 译文提交响应。 */
    public record TranslationResponse(long documentId, String segmentId, String language,
                                      int translationVersion, int sourceVersion, int termVersion,
                                      int draftVersion) {
    }

    /** 译文批准响应。 */
    public record ApprovalResponse(long documentId, String segmentId, String language, String reviewer,
                                   int translationVersion, int sourceVersion) {
    }

    /** 发布响应。 */
    public record PublishResponse(long documentId, int publishedVersion) {
    }

    /** 术语规则视图。 */
    public record TermRuleView(String sourceTerm, String language, String requiredTranslation) {
    }

    /** 新增术语版本响应。 */
    public record TermVersionResponse(long documentId, int termVersion, int ruleCount, int draftVersion) {
    }

    /** 术语版本查询视图：版本号与完整规则集。 */
    public record TermVersionView(long documentId, int termVersion, List<TermRuleView> rules) {
    }

    /** 单条译文的术语状态：绑定版本、是否过期及当前规则下的违规术语。 */
    public record TranslationTermStatus(String segmentId, String language, int translationVersion,
                                        int termVersion, boolean termStale, List<TermRuleView> violations) {
    }

    /** 译文术语状态查询响应。 */
    public record TermStatusResponse(long documentId, int termVersion, List<TranslationTermStatus> translations) {
    }

    /** 统一错误响应；violations 仅在术语违规 422 时返回，含全部违规术语。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorResponse(String error, String message, List<TermRuleView> violations) {
        public ErrorResponse(String error, String message) {
            this(error, message, null);
        }
    }

    /** 评审策略单阶段输入：法定人数与候选审核人集合。 */
    public record ReviewStageInput(
            @Positive(message = "quorum 必须为正数") int quorum,
            @NotNull(message = "reviewers 不能为空")
            @Size(min = 1, max = 50, message = "候选审核人须为 1~50 人")
            List<@NotBlank(message = "审核人不能为空") @Size(max = 128) String> reviewers) {
    }

    /** 配置评审策略请求：为某语言新增一个策略版本并激活，携带期望的当前策略版本做乐观校验。 */
    public record ConfigureReviewPolicyRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @PositiveOrZero(message = "expectedPolicyVersion 不能为负数") int expectedPolicyVersion,
            @NotNull(message = "languageStage 不能为空") @Valid ReviewStageInput languageStage,
            @NotNull(message = "complianceStage 不能为空") @Valid ReviewStageInput complianceStage) {
    }

    /** 配置评审策略响应。 */
    public record ReviewPolicyResponse(long documentId, String language, int policyVersion, int draftVersion) {
    }

    /** 投票/改票请求：针对精确版本组合；改票须携带 expectedVoteVersion 等于当前票版本。 */
    public record CastVoteRequest(
            @NotBlank(message = "requestId 不能为空") @Size(max = 128) String requestId,
            @NotBlank(message = "voteKey 不能为空") @Size(max = 128) String voteKey,
            @NotBlank(message = "stage 不能为空") String stage,
            @NotBlank(message = "decision 不能为空") String decision,
            @Positive(message = "sourceVersion 必须为正数") int sourceVersion,
            @Positive(message = "translationVersion 必须为正数") int translationVersion,
            @PositiveOrZero(message = "termVersion 不能为负数") int termVersion,
            @Positive(message = "policyVersion 必须为正数") int policyVersion,
            @PositiveOrZero(message = "expectedVoteVersion 不能为负数") int expectedVoteVersion) {
    }

    /** 投票/改票响应。 */
    public record VoteResponse(long documentId, String segmentId, String language, String stage,
                               String reviewer, String decision, int voteVersion) {
    }

    /** 评审矩阵单阶段视图：法定人数、当前有效票统计与阶段状态（PENDING/BLOCKED/PASSED）。 */
    public record ReviewStageMatrixView(String stage, int quorum, int approveCount, int rejectCount,
                                        String status, List<String> approvers, List<String> rejecters) {
    }

    /** 评审矩阵条目：某段落某语言的当前版本组合、生效策略版本及各阶段评审状态。 */
    public record ReviewMatrixEntry(String segmentId, String language, int sourceVersion,
                                    Integer translationVersion, int termVersion, int policyVersion,
                                    List<ReviewStageMatrixView> stages) {
    }

    /** 当前评审矩阵查询响应（只读）。 */
    public record ReviewMatrixResponse(long documentId, List<ReviewMatrixEntry> entries) {
    }

    /** 历史票视图：含被改票取代的旧版本；current 表示该审核人当前票，counting 表示计入当前法定人数。 */
    public record VoteHistoryView(String segmentId, String language, String stage, String reviewer,
                                  int voteVersion, String voteKey, String decision,
                                  int sourceVersion, int translationVersion, int termVersion,
                                  int policyVersion, boolean current, boolean counting) {
    }

    /** 历史票查询响应（只读）。 */
    public record VoteHistoryResponse(long documentId, List<VoteHistoryView> votes) {
    }
}
