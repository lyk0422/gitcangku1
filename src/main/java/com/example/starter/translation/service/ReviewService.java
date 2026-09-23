package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiDtos;
import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.domain.Rows.DocumentRow;
import com.example.starter.translation.domain.Rows.ReviewStageRow;
import com.example.starter.translation.domain.Rows.SegmentRow;
import com.example.starter.translation.domain.Rows.TranslationRow;
import com.example.starter.translation.domain.Rows.VoteRow;
import com.example.starter.translation.repo.TranslationRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 双阶段法定人数评审服务。
 * 每种目标语言配置版本化策略：LANGUAGE 与 COMPLIANCE 阶段各自给出候选审核人集合与法定人数，
 * 两集合可重叠，但同一审核人对同一译文只能在一个阶段投票。
 * 审核人对精确 sourceVersion/translationVersion/termVersion/policyVersion 投 APPROVE 或 REJECT；
 * 任一版本变化后旧票保留审计但不计入当前法定人数；改票须携带 expectedVoteVersion 并生成新票版本。
 * 任一当前 REJECT 票使阶段 BLOCKED；无拒绝且当前 APPROVE 数达到法定人数才 PASSED。
 * 所有写操作先对文档行加 FOR UPDATE 行锁，与同一文档的其他写操作串行。
 */
@Service
public class ReviewService {

    public static final String STAGE_LANGUAGE = "LANGUAGE";
    public static final String STAGE_COMPLIANCE = "COMPLIANCE";
    public static final String DECISION_APPROVE = "APPROVE";
    public static final String DECISION_REJECT = "REJECT";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_BLOCKED = "BLOCKED";
    public static final String STATUS_PASSED = "PASSED";

    private static final List<String> STAGES = List.of(STAGE_LANGUAGE, STAGE_COMPLIANCE);

    private final TranslationRepository repository;

    public ReviewService(TranslationRepository repository) {
        this.repository = repository;
    }

    /**
     * 配置评审策略：语言须在文档目标语言中；expectedPolicyVersion 须等于当前生效策略版本（未配置为 0，不符 409）；
     * 每阶段候选审核人非空去重且法定人数不超过人数（不符 422）。
     * 成功后生成新策略版本并激活（仅作用于新投票，不改历史发布），草稿版本加一。
     */
    @Transactional
    public ApiDtos.ReviewPolicyResponse configurePolicy(long documentId, String language,
                                                        ApiDtos.ConfigureReviewPolicyRequest request) {
        DocumentRow document = lockDocument(documentId);
        String normalizedLanguage = normalizeLanguage(language);
        if (!document.targetLanguages().contains(normalizedLanguage)) {
            throw ApiException.unprocessable("语言不在文档目标语言中: " + normalizedLanguage);
        }
        int currentVersion = repository.findCurrentPolicyVersion(documentId, normalizedLanguage).orElse(0);
        if (currentVersion != request.expectedPolicyVersion()) {
            throw ApiException.conflict("策略版本冲突：当前策略版本 " + currentVersion
                    + "，与期望的 " + request.expectedPolicyVersion() + " 不一致");
        }
        validateStage(STAGE_LANGUAGE, request.languageStage());
        validateStage(STAGE_COMPLIANCE, request.complianceStage());
        int policyVersion = currentVersion + 1;
        repository.insertReviewPolicy(documentId, normalizedLanguage, policyVersion);
        insertStage(documentId, normalizedLanguage, policyVersion, STAGE_LANGUAGE, request.languageStage());
        insertStage(documentId, normalizedLanguage, policyVersion, STAGE_COMPLIANCE, request.complianceStage());
        repository.upsertReviewPolicyCurrent(documentId, normalizedLanguage, policyVersion);
        int draftVersion = document.draftVersion() + 1;
        repository.updateDraftVersion(documentId, draftVersion);
        return new ApiDtos.ReviewPolicyResponse(documentId, normalizedLanguage, policyVersion, draftVersion);
    }

    /**
     * 投票/改票：审核人须在生效策略该阶段候选集合中；票针对的版本组合须全部等于当前版本（不符 422）；
     * 同一审核人对同一译文只能在一个阶段投票（不符 422）；voteKey 全局唯一（复用 409）；
     * 改票须 expectedVoteVersion 等于当前票版本（不符 409），成功后生成新票版本，旧票保留审计。
     */
    @Transactional
    public ApiDtos.VoteResponse castVote(long documentId, String segmentId, String language,
                                         String actorId, ApiDtos.CastVoteRequest request) {
        DocumentRow document = lockDocument(documentId);
        String normalizedLanguage = normalizeLanguage(language);
        String stage = normalizeStage(request.stage());
        String decision = normalizeDecision(request.decision());
        SegmentRow segment = repository.findSegment(documentId, segmentId)
                .orElseThrow(() -> ApiException.notFound("段落不存在: " + segmentId));
        TranslationRow translation = repository.findTranslation(documentId, segmentId, normalizedLanguage)
                .orElseThrow(() -> ApiException.notFound(
                        "译文不存在: " + segmentId + "/" + normalizedLanguage));
        int activePolicyVersion = repository.findCurrentPolicyVersion(documentId, normalizedLanguage)
                .orElseThrow(() -> ApiException.unprocessable(
                        "语言未配置评审策略: " + normalizedLanguage));
        if (request.policyVersion() != activePolicyVersion) {
            throw ApiException.unprocessable("投票所针对的策略版本 " + request.policyVersion()
                    + " 与当前生效策略版本 " + activePolicyVersion + " 不匹配");
        }
        ReviewStageRow stageConfig = findStageConfig(documentId, normalizedLanguage, activePolicyVersion, stage);
        if (!stageConfig.reviewers().contains(actorId)) {
            throw ApiException.unprocessable("审核人不在该阶段候选集合中: " + actorId);
        }
        if (request.sourceVersion() != segment.sourceVersion()
                || request.translationVersion() != translation.translationVersion()
                || request.termVersion() != document.termVersion()) {
            throw ApiException.unprocessable("投票所针对的版本组合 (源文 " + request.sourceVersion()
                    + "/译文 " + request.translationVersion() + "/术语 " + request.termVersion()
                    + ") 与当前版本 (源文 " + segment.sourceVersion()
                    + "/译文 " + translation.translationVersion() + "/术语 " + document.termVersion() + ") 不匹配");
        }
        String otherStage = STAGE_LANGUAGE.equals(stage) ? STAGE_COMPLIANCE : STAGE_LANGUAGE;
        if (repository.countVotes(documentId, segmentId, normalizedLanguage, otherStage, actorId) > 0) {
            throw ApiException.unprocessable("审核人已在 " + otherStage + " 阶段对该译文投票，"
                    + "同一审核人对同一译文只能在一个阶段投票: " + actorId);
        }
        if (repository.findVoteByKey(request.voteKey()).isPresent()) {
            throw ApiException.conflict("voteKey 已使用: " + request.voteKey());
        }
        int currentVoteVersion = repository
                .findLatestVote(documentId, segmentId, normalizedLanguage, stage, actorId)
                .map(VoteRow::voteVersion).orElse(0);
        if (currentVoteVersion != request.expectedVoteVersion()) {
            throw ApiException.conflict("票版本冲突：当前票版本 " + currentVoteVersion
                    + "，与期望的 " + request.expectedVoteVersion() + " 不一致");
        }
        VoteRow vote = new VoteRow(segmentId, normalizedLanguage, stage, actorId, currentVoteVersion + 1,
                request.voteKey(), decision, segment.sourceVersion(), translation.translationVersion(),
                document.termVersion(), activePolicyVersion);
        try {
            repository.insertVote(documentId, vote);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("voteKey 已使用: " + request.voteKey());
        }
        return new ApiDtos.VoteResponse(documentId, segmentId, normalizedLanguage, stage, actorId,
                decision, vote.voteVersion());
    }

    /**
     * 当前评审矩阵（只读）：全部段落 × 全部目标语言，给出当前版本组合、生效策略版本
     * 及各阶段法定人数、当前有效票统计与状态（PENDING/BLOCKED/PASSED）。
     */
    @Transactional(readOnly = true)
    public ApiDtos.ReviewMatrixResponse getReviewMatrix(long documentId) {
        DocumentRow document = repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        Map<String, TranslationRow> translations = repository.listTranslations(documentId).stream()
                .collect(Collectors.toMap(t -> key(t.segmentId(), t.language()), Function.identity()));
        Map<String, Integer> policies = repository.listCurrentPolicyVersions(documentId);
        List<ApiDtos.ReviewMatrixEntry> entries = new ArrayList<>();
        for (SegmentRow segment : repository.listSegments(documentId)) {
            for (String language : document.targetLanguages()) {
                entries.add(buildMatrixEntry(document, segment, language,
                        translations.get(key(segment.segmentId(), language)),
                        policies.getOrDefault(language, 0)));
            }
        }
        return new ApiDtos.ReviewMatrixResponse(documentId, entries);
    }

    /**
     * 历史票查询（只读）：含被改票取代的旧版本票；current 标记该审核人当前票，
     * counting 标记计入当前法定人数的票（当前票且版本组合与当前一致）。
     */
    @Transactional(readOnly = true)
    public ApiDtos.VoteHistoryResponse getVoteHistory(long documentId, String segmentId, String language) {
        DocumentRow document = repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        String normalizedLanguage = language == null ? null : normalizeLanguage(language);
        List<VoteRow> votes = repository.listVotes(documentId, segmentId, normalizedLanguage);
        Map<String, SegmentRow> segments = repository.listSegments(documentId).stream()
                .collect(Collectors.toMap(SegmentRow::segmentId, Function.identity()));
        Map<String, TranslationRow> translations = repository.listTranslations(documentId).stream()
                .collect(Collectors.toMap(t -> key(t.segmentId(), t.language()), Function.identity()));
        Map<String, Integer> policies = repository.listCurrentPolicyVersions(documentId);
        Map<String, Integer> latestVersions = new HashMap<>();
        for (VoteRow vote : votes) {
            String groupKey = groupKey(vote);
            latestVersions.merge(groupKey, vote.voteVersion(), Math::max);
        }
        List<ApiDtos.VoteHistoryView> views = new ArrayList<>();
        for (VoteRow vote : votes) {
            boolean current = vote.voteVersion() == latestVersions.get(groupKey(vote));
            boolean counting = current && isCounting(vote, document, segments, translations, policies);
            views.add(new ApiDtos.VoteHistoryView(vote.segmentId(), vote.language(), vote.stage(), vote.reviewer(),
                    vote.voteVersion(), vote.voteKey(), vote.decision(), vote.sourceVersion(),
                    vote.translationVersion(), vote.termVersion(), vote.policyVersion(), current, counting));
        }
        return new ApiDtos.VoteHistoryResponse(documentId, views);
    }

    /**
     * 发布门禁：对配置了生效策略的语言，要求全部段落两个阶段均 PASSED（无当前 REJECT 且当前
     * APPROVE 数达到法定人数，票版本组合与当前完全一致）；未配置策略的语言走原有批准校验。
     * 返回发布需冻结的当前有效票集合（按段落+语言分组），失败抛 422 且不产生任何冻结记录。
     */
    public Map<String, List<VoteRow>> verifyReviewGate(DocumentRow document, List<SegmentRow> segments,
                                                       Map<String, TranslationRow> translations) {
        Map<String, Integer> policies = repository.listCurrentPolicyVersions(document.documentId());
        Map<String, List<VoteRow>> adopted = new LinkedHashMap<>();
        for (SegmentRow segment : segments) {
            for (String language : document.targetLanguages()) {
                int policyVersion = policies.getOrDefault(language, 0);
                if (policyVersion == 0) {
                    continue;
                }
                TranslationRow translation = translations.get(key(segment.segmentId(), language));
                if (translation == null) {
                    throw ApiException.unprocessable("缺少译文: " + segment.segmentId() + "/" + language);
                }
                List<VoteRow> countingVotes = new ArrayList<>();
                for (String stage : STAGES) {
                    ReviewStageRow config = findStageConfig(
                            document.documentId(), language, policyVersion, stage);
                    StageEvaluation evaluation = evaluate(
                            repository.listLatestVotes(document.documentId(), segment.segmentId(),
                                    language, stage),
                            config.quorum(), segment.sourceVersion(), translation.translationVersion(),
                            document.termVersion(), policyVersion);
                    if (!STATUS_PASSED.equals(evaluation.status())) {
                        throw ApiException.unprocessable("评审未通过: " + segment.segmentId() + "/" + language
                                + " " + stage + " 阶段状态 " + evaluation.status()
                                + "（当前有效 APPROVE " + evaluation.approveCount() + "/法定人数 "
                                + config.quorum() + "，当前有效 REJECT " + evaluation.rejectCount() + "）");
                    }
                    countingVotes.addAll(evaluation.countingVotes());
                }
                adopted.put(key(segment.segmentId(), language), countingVotes);
            }
        }
        return adopted;
    }

    private ApiDtos.ReviewMatrixEntry buildMatrixEntry(DocumentRow document, SegmentRow segment, String language,
                                                       TranslationRow translation, int policyVersion) {
        Integer translationVersion = translation == null ? null : translation.translationVersion();
        List<ApiDtos.ReviewStageMatrixView> stages = new ArrayList<>();
        if (policyVersion > 0 && translation != null) {
            for (String stage : STAGES) {
                ReviewStageRow config = findStageConfig(document.documentId(), language, policyVersion, stage);
                StageEvaluation evaluation = evaluate(
                        repository.listLatestVotes(document.documentId(), segment.segmentId(), language, stage),
                        config.quorum(), segment.sourceVersion(), translation.translationVersion(),
                        document.termVersion(), policyVersion);
                stages.add(new ApiDtos.ReviewStageMatrixView(stage, config.quorum(), evaluation.approveCount(),
                        evaluation.rejectCount(), evaluation.status(), evaluation.approvers(),
                        evaluation.rejecters()));
            }
        }
        return new ApiDtos.ReviewMatrixEntry(segment.segmentId(), language, segment.sourceVersion(),
                translationVersion, document.termVersion(), policyVersion, stages);
    }

    /** 阶段评估：仅统计版本组合与当前完全一致的当前票；任一当前 REJECT 则 BLOCKED，否则 APPROVE 达法定人数才 PASSED。 */
    private StageEvaluation evaluate(List<VoteRow> latestVotes, int quorum, int sourceVersion,
                                     int translationVersion, int termVersion, int policyVersion) {
        List<VoteRow> counting = new ArrayList<>();
        List<String> approvers = new ArrayList<>();
        List<String> rejecters = new ArrayList<>();
        for (VoteRow vote : latestVotes) {
            if (vote.sourceVersion() == sourceVersion && vote.translationVersion() == translationVersion
                    && vote.termVersion() == termVersion && vote.policyVersion() == policyVersion) {
                counting.add(vote);
                if (DECISION_REJECT.equals(vote.decision())) {
                    rejecters.add(vote.reviewer());
                } else {
                    approvers.add(vote.reviewer());
                }
            }
        }
        String status;
        if (!rejecters.isEmpty()) {
            status = STATUS_BLOCKED;
        } else if (approvers.size() >= quorum) {
            status = STATUS_PASSED;
        } else {
            status = STATUS_PENDING;
        }
        return new StageEvaluation(status, counting, approvers.size(), rejecters.size(), approvers, rejecters);
    }

    private boolean isCounting(VoteRow vote, DocumentRow document, Map<String, SegmentRow> segments,
                               Map<String, TranslationRow> translations, Map<String, Integer> policies) {
        SegmentRow segment = segments.get(vote.segmentId());
        TranslationRow translation = translations.get(key(vote.segmentId(), vote.language()));
        return segment != null && translation != null
                && vote.sourceVersion() == segment.sourceVersion()
                && vote.translationVersion() == translation.translationVersion()
                && vote.termVersion() == document.termVersion()
                && vote.policyVersion() == policies.getOrDefault(vote.language(), 0);
    }

    private ReviewStageRow findStageConfig(long documentId, String language, int policyVersion, String stage) {
        return repository.listReviewStages(documentId, language, policyVersion).stream()
                .filter(row -> row.stage().equals(stage))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "策略版本缺少阶段配置: " + language + "/" + policyVersion + "/" + stage));
    }

    private void validateStage(String stage, ApiDtos.ReviewStageInput input) {
        Set<String> distinct = new HashSet<>(input.reviewers());
        if (distinct.size() != input.reviewers().size()) {
            throw ApiException.unprocessable(stage + " 阶段候选审核人重复");
        }
        if (input.quorum() > distinct.size()) {
            throw ApiException.unprocessable(stage + " 阶段法定人数 " + input.quorum()
                    + " 超过候选审核人数量 " + distinct.size());
        }
    }

    private void insertStage(long documentId, String language, int policyVersion, String stage,
                             ApiDtos.ReviewStageInput input) {
        repository.insertReviewPolicyStage(documentId, language, policyVersion, stage, input.quorum());
        for (String reviewer : input.reviewers()) {
            repository.insertReviewPolicyReviewer(documentId, language, policyVersion, stage, reviewer);
        }
    }

    private DocumentRow lockDocument(long documentId) {
        return repository.findDocumentForUpdate(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
    }

    private static String normalizeStage(String stage) {
        String normalized = stage.trim().toUpperCase(Locale.ROOT);
        if (!STAGES.contains(normalized)) {
            throw ApiException.unprocessable("未知评审阶段: " + stage + "（须为 LANGUAGE 或 COMPLIANCE）");
        }
        return normalized;
    }

    private static String normalizeDecision(String decision) {
        String normalized = decision.trim().toUpperCase(Locale.ROOT);
        if (!DECISION_APPROVE.equals(normalized) && !DECISION_REJECT.equals(normalized)) {
            throw ApiException.unprocessable("未知投票决定: " + decision + "（须为 APPROVE 或 REJECT）");
        }
        return normalized;
    }

    private static String normalizeLanguage(String language) {
        return language.trim().toLowerCase(Locale.ROOT);
    }

    /** 段落+语言复合键，与 TranslationService 共享同一规则。 */
    private static String key(String segmentId, String language) {
        return TranslationService.key(segmentId, language);
    }

    private static String groupKey(VoteRow vote) {
        return vote.segmentId() + " " + vote.language() + " " + vote.stage() + " " + vote.reviewer();
    }

    /** 阶段评估结果：状态、计入法定人数的当前有效票及统计。 */
    private record StageEvaluation(String status, List<VoteRow> countingVotes, int approveCount, int rejectCount,
                                   List<String> approvers, List<String> rejecters) {
    }
}
