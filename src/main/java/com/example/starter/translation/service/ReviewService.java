package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiDtos;
import com.example.starter.translation.api.ApiDtos.PutReviewPolicyRequest;
import com.example.starter.translation.api.ApiDtos.ReviewMatrixResponse;
import com.example.starter.translation.api.ApiDtos.ReviewPolicyView;
import com.example.starter.translation.api.ApiDtos.ReviewUnitView;
import com.example.starter.translation.api.ApiDtos.StagePolicyInput;
import com.example.starter.translation.api.ApiDtos.StagePolicyView;
import com.example.starter.translation.api.ApiDtos.StageStatusView;
import com.example.starter.translation.api.ApiDtos.VoteHistoryEntry;
import com.example.starter.translation.api.ApiDtos.VoteHistoryResponse;
import com.example.starter.translation.api.ApiDtos.VoteResponse;
import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.domain.Rows.DocumentRow;
import com.example.starter.translation.domain.Rows.ReviewPolicyRow;
import com.example.starter.translation.domain.Rows.ReviewStage;
import com.example.starter.translation.domain.Rows.ReviewVoteRow;
import com.example.starter.translation.domain.Rows.SegmentRow;
import com.example.starter.translation.domain.Rows.TranslationRow;
import com.example.starter.translation.domain.Rows.VoteDecision;
import com.example.starter.translation.repo.TranslationRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 双阶段法定人数评审服务：按语言版本化配置 LANGUAGE/COMPLIANCE 策略、投票/改票、
 * 只读评审矩阵与历史票查询。所有写操作在调用方事务内先对文档行加 FOR UPDATE 锁串行化。
 */
@Service
public class ReviewService {

    private final TranslationRepository repository;

    public ReviewService(TranslationRepository repository) {
        this.repository = repository;
    }

    /**
     * 新增并激活某语言的策略版本：expectedPolicyVersion 须为当前激活版本（无策略传 0），不符 409；
     * 候选审核人不能为空、不能重复，法定人数不小于 1 且不超过候选人数（不符 422）。
     * 策略版本不可变，切换后仅作用于新投票；草稿版本加一。
     */
    @Transactional
    public ReviewPolicyView putPolicy(long documentId, String languageRaw, PutReviewPolicyRequest request) {
        DocumentRow document = lockDocument(documentId);
        String language = normalizeLanguage(languageRaw);
        if (!document.targetLanguages().contains(language)) {
            throw ApiException.unprocessable("语言不在文档目标语言中: " + language);
        }
        int activeVersion = repository.findActivePolicyVersion(documentId, language).orElse(0);
        if (activeVersion != request.expectedPolicyVersion()) {
            throw ApiException.conflict("策略版本冲突：当前激活版本 " + activeVersion
                    + "，与期望的 " + request.expectedPolicyVersion() + " 不一致");
        }
        List<String> languageReviewers = normalizeReviewers(request.languageStage(), "语言阶段");
        List<String> complianceReviewers = normalizeReviewers(request.complianceStage(), "合规阶段");
        validateQuorum(request.languageStage(), languageReviewers, "语言阶段");
        validateQuorum(request.complianceStage(), complianceReviewers, "合规阶段");

        int policyVersion = activeVersion + 1;
        ReviewPolicyRow policy = new ReviewPolicyRow(documentId, language, policyVersion,
                languageReviewers, request.languageStage().quorum(),
                complianceReviewers, request.complianceStage().quorum());
        repository.insertReviewPolicy(policy);
        repository.upsertActiveReviewPolicy(documentId, language, policyVersion);
        bumpDraftVersion(document);
        return toView(policy);
    }

    /**
     * 投票/改票：审核人取 X-Actor-Id，对精确 sourceVersion/translationVersion/termVersion/policyVersion
     * 在指定阶段投 APPROVE/REJECT。版本与当前不一致 422；policyVersion 非激活 409；审核人非该阶段候选 422；
     * 同一人对同一译文只能在一个阶段投票（另一阶段已有当前票 422）；改票须 expectedVoteVersion 匹配（409）；
     * voteKey 全局唯一，复用返回 409；成功生成新 voteVersion，失败回滚不占 voteKey。
     */
    @Transactional
    public VoteResponse castVote(long documentId, String segmentId, String languageRaw, String actorId,
                                 ApiDtos.CastVoteRequest request) {
        DocumentRow document = lockDocument(documentId);
        String language = normalizeLanguage(languageRaw);
        if (!document.targetLanguages().contains(language)) {
            throw ApiException.unprocessable("语言不在文档目标语言中: " + language);
        }
        ReviewStage stage = parseStage(request.stage());
        VoteDecision decision = parseDecision(request.decision());

        int activePolicyVersion = repository.findActivePolicyVersion(documentId, language)
                .orElseThrow(() -> ApiException.unprocessable("该语言尚未配置评审策略: " + language));
        if (request.policyVersion() != activePolicyVersion) {
            throw ApiException.conflict("投票策略版本 " + request.policyVersion()
                    + " 不是当前激活版本 " + activePolicyVersion);
        }
        ReviewPolicyRow policy = repository.findReviewPolicy(documentId, language, activePolicyVersion)
                .orElseThrow(() -> ApiException.illegalState("激活策略版本缺失: " + activePolicyVersion));

        SegmentRow segment = repository.findSegment(documentId, segmentId)
                .orElseThrow(() -> ApiException.notFound("段落不存在: " + segmentId));
        TranslationRow translation = repository.findTranslation(documentId, segmentId, language)
                .orElseThrow(() -> ApiException.notFound("译文不存在: " + segmentId + "/" + language));
        if (request.sourceVersion() != segment.sourceVersion()
                || request.translationVersion() != translation.translationVersion()
                || request.termVersion() != document.termVersion()) {
            throw ApiException.unprocessable("投票版本与当前版本不一致：当前源文版本 " + segment.sourceVersion()
                    + "、译文版本 " + translation.translationVersion() + "、术语版本 " + document.termVersion());
        }
        if (translation.sourceVersion() != segment.sourceVersion()) {
            throw ApiException.unprocessable("译文待更新，基于源文版本 " + translation.sourceVersion()
                    + "，当前源文版本 " + segment.sourceVersion() + "，不能投票");
        }
        if (translation.termVersion() != document.termVersion()) {
            throw ApiException.unprocessable("译文术语版本过期，绑定术语版本 " + translation.termVersion()
                    + "，当前术语版本 " + document.termVersion() + "，请重新提交译文后再投票");
        }
        List<String> candidates = ReviewGate.candidates(policy, stage);
        if (!candidates.contains(actorId)) {
            throw ApiException.unprocessable("审核人不在" + stageName(stage) + "候选集合中: " + actorId);
        }

        List<ReviewVoteRow> existing = repository.listReviewVotes(documentId, segmentId, language);
        ensureNotVotedInOtherStage(existing, stage, actorId, activePolicyVersion, segment.sourceVersion(),
                translation.translationVersion(), document.termVersion());

        // 票版本序列以精确四版本（源文/译文/术语/策略）+ 阶段 + 审核人为界：
        // 版本变化后旧票保留审计，针对新版本的投票属于新序列（首次投票，expectedVoteVersion 省略）；
        // 仅对同一精确版本再次投票才视为改票，须携带当前票版本。
        int latestVersion = existing.stream()
                .filter(v -> v.stage() == stage && v.reviewer().equals(actorId)
                        && v.sourceVersion() == request.sourceVersion()
                        && v.translationVersion() == request.translationVersion()
                        && v.termVersion() == request.termVersion()
                        && v.policyVersion() == request.policyVersion())
                .mapToInt(ReviewVoteRow::voteVersion).max().orElse(0);
        if (latestVersion == 0) {
            if (request.expectedVoteVersion() != null) {
                throw ApiException.unprocessable("首次投票不能携带 expectedVoteVersion");
            }
        } else if (request.expectedVoteVersion() == null
                || request.expectedVoteVersion() != latestVersion) {
            throw ApiException.conflict("票版本冲突：当前票版本 " + latestVersion
                    + "，与期望的 " + request.expectedVoteVersion() + " 不一致");
        }

        if (repository.reviewVoteKeyExists(request.voteKey())) {
            throw ApiException.conflict("voteKey 已被占用: " + request.voteKey());
        }
        int voteVersion = latestVersion + 1;
        long voteId;
        try {
            voteId = repository.insertReviewVote(new ReviewVoteRow(0, request.voteKey(), documentId, segmentId,
                    language, stage, actorId, segment.sourceVersion(), translation.translationVersion(),
                    document.termVersion(), activePolicyVersion, decision, voteVersion, request.requestId()));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("voteKey 已被占用: " + request.voteKey());
        }

        List<ReviewVoteRow> allVotes = repository.listReviewVotes(documentId, segmentId, language);
        ReviewGate.StageState state = ReviewGate.evaluate(policy, stage, allVotes,
                segment.sourceVersion(), translation.translationVersion(), document.termVersion());
        return new VoteResponse(voteId, request.voteKey(), segmentId, language, stage.name(), actorId,
                decision.name(), voteVersion, segment.sourceVersion(), translation.translationVersion(),
                document.termVersion(), activePolicyVersion, state.view().status());
    }

    /** 当前评审矩阵：全部段落 × 目标语言的当前版本、激活策略与两阶段计票状态（只读）。 */
    @Transactional(readOnly = true)
    public ReviewMatrixResponse getMatrix(long documentId) {
        DocumentRow document = repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        List<SegmentRow> segments = repository.listSegments(documentId);
        Map<String, TranslationRow> translations = repository.listTranslations(documentId).stream()
                .collect(Collectors.toMap(t -> t.segmentId() + " " + t.language(), Function.identity()));
        Map<String, List<ReviewVoteRow>> votes = repository.listAllReviewVotes(documentId).stream()
                .collect(Collectors.groupingBy(v -> v.segmentId() + " " + v.language()));

        List<ReviewUnitView> units = new ArrayList<>();
        for (SegmentRow segment : segments) {
            for (String language : document.targetLanguages()) {
                String key = segment.segmentId() + " " + language;
                TranslationRow translation = translations.get(key);
                Optional<ReviewPolicyRow> policy = repository.findActivePolicyVersion(documentId, language)
                        .flatMap(version -> repository.findReviewPolicy(documentId, language, version));
                List<StageStatusView> stageViews = new ArrayList<>();
                int translationVersion = 0;
                int policyVersion = policy.map(ReviewPolicyRow::policyVersion).orElse(0);
                if (translation != null && policy.isPresent()) {
                    translationVersion = translation.translationVersion();
                    List<ReviewVoteRow> unitVotes = votes.getOrDefault(key, List.of());
                    for (ReviewStage stage : ReviewStage.values()) {
                        stageViews.add(ReviewGate.evaluate(policy.get(), stage, unitVotes,
                                segment.sourceVersion(), translation.translationVersion(),
                                document.termVersion()).view());
                    }
                }
                units.add(new ReviewUnitView(segment.segmentId(), language, segment.sourceVersion(),
                        translationVersion, document.termVersion(), policyVersion, List.copyOf(stageViews)));
            }
        }
        return new ReviewMatrixResponse(documentId, List.copyOf(units));
    }

    /** 历史票查询：含因任一版本变化而不计入当前法定人数的旧票，按阶段/审核人/票版本排序，只读。 */
    @Transactional(readOnly = true)
    public VoteHistoryResponse getVoteHistory(long documentId, String segmentId, String languageRaw) {
        DocumentRow document = repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        String language = normalizeLanguage(languageRaw);
        if (!document.targetLanguages().contains(language)) {
            throw ApiException.unprocessable("语言不在文档目标语言中: " + language);
        }
        SegmentRow segment = repository.findSegment(documentId, segmentId)
                .orElseThrow(() -> ApiException.notFound("段落不存在: " + segmentId));
        TranslationRow translation = repository.findTranslation(documentId, segmentId, language).orElse(null);
        int activePolicyVersion = repository.findActivePolicyVersion(documentId, language).orElse(0);
        int translationVersion = translation == null ? 0 : translation.translationVersion();

        List<VoteHistoryEntry> entries = new ArrayList<>();
        for (ReviewVoteRow vote : repository.listReviewVotes(documentId, segmentId, language)) {
            boolean current = translation != null
                    && ReviewGate.isCurrent(vote, activePolicyVersion, segment.sourceVersion(),
                            translationVersion, document.termVersion());
            entries.add(ReviewGate.toEntry(vote, current));
        }
        return new VoteHistoryResponse(documentId, segmentId, language, List.copyOf(entries));
    }

    private void ensureNotVotedInOtherStage(List<ReviewVoteRow> votes, ReviewStage stage, String reviewer,
                                            int policyVersion, int sourceVersion, int translationVersion,
                                            int termVersion) {
        ReviewStage other = stage == ReviewStage.LANGUAGE ? ReviewStage.COMPLIANCE : ReviewStage.LANGUAGE;
        boolean voted = votes.stream().anyMatch(v -> v.stage() == other && v.reviewer().equals(reviewer)
                && ReviewGate.isCurrent(v, policyVersion, sourceVersion, translationVersion, termVersion));
        if (voted) {
            throw ApiException.unprocessable("同一审核人对同一译文只能在一个阶段投票，已在"
                    + stageName(other) + "投票: " + reviewer);
        }
    }

    private List<String> normalizeReviewers(StagePolicyInput input, String stageLabel) {
        Set<String> reviewers = new LinkedHashSet<>();
        for (String reviewer : input.reviewers()) {
            String trimmed = reviewer.trim();
            if (!reviewers.add(trimmed)) {
                throw ApiException.unprocessable(stageLabel + "候选审核人重复: " + trimmed);
            }
        }
        if (reviewers.isEmpty()) {
            throw ApiException.unprocessable(stageLabel + "候选审核人不能为空");
        }
        return List.copyOf(reviewers);
    }

    private void validateQuorum(StagePolicyInput input, List<String> reviewers, String stageLabel) {
        if (input.quorum() > reviewers.size()) {
            throw ApiException.unprocessable(stageLabel + "法定人数 " + input.quorum()
                    + " 超过候选审核人数 " + reviewers.size());
        }
    }

    private DocumentRow lockDocument(long documentId) {
        return repository.findDocumentForUpdate(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
    }

    private void bumpDraftVersion(DocumentRow document) {
        repository.updateDraftVersion(document.documentId(), document.draftVersion() + 1);
    }

    private static ReviewStage parseStage(String value) {
        try {
            return ReviewStage.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable("非法评审阶段: " + value);
        }
    }

    private static VoteDecision parseDecision(String value) {
        try {
            return VoteDecision.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable("非法票决定: " + value);
        }
    }

    private static String normalizeLanguage(String language) {
        return language.trim().toLowerCase(Locale.ROOT);
    }

    private static String stageName(ReviewStage stage) {
        return stage == ReviewStage.LANGUAGE ? "语言阶段" : "合规阶段";
    }

    private static ReviewPolicyView toView(ReviewPolicyRow policy) {
        return new ReviewPolicyView(policy.policyVersion(),
                new StagePolicyView(policy.languageReviewers(), policy.languageQuorum()),
                new StagePolicyView(policy.complianceReviewers(), policy.complianceQuorum()));
    }
}
