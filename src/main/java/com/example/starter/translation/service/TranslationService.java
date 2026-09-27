package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiDtos;
import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.domain.Rows.ConflictHistoryRow;
import com.example.starter.translation.domain.Rows.ConflictRow;
import com.example.starter.translation.domain.Rows.DocumentRow;
import com.example.starter.translation.domain.Rows.ReviewRow;
import com.example.starter.translation.domain.Rows.SegmentRow;
import com.example.starter.translation.domain.Rows.TermRuleRow;
import com.example.starter.translation.domain.Rows.TranslationRow;
import com.example.starter.translation.repo.TranslationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
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
 * 多语种段落修订、评审利益冲突与发布快照的核心业务服务。
 * 所有写操作先对文档行（或冲突行）加 FOR UPDATE 行锁，保证同一文档的修改、评审、冲突登记与发布串行，
 * 并发下对应一个一致的文档状态；方法均加入调用方事务，与幂等记录原子提交。
 *
 * <p>冲突时间语义：业务时间统一 UTC，区间左闭右开；某评审人在 t 时刻命中有效冲突，当且仅当存在
 * status=DECLARED 且 validFrom &lt;= t &lt; validTo 的同文档同语种声明。解除（LIFTED）只影响解除后的
 * 评审提交与发布重算，不追溯旧快照；区间自然到期同理，不修改任何历史证据。
 */
@Service
public class TranslationService {

    /** 评审决定：批准。 */
    public static final String DECISION_APPROVED = "APPROVED";
    /** 评审决定：否决。 */
    public static final String DECISION_REJECTED = "REJECTED";
    /** 评审状态：有效（是否计入法定人数还须版本匹配且当前未命中有效冲突）。 */
    public static final String REVIEW_ACTIVE = "ACTIVE";
    /** 评审状态：因评审人命中利益冲突登记而被排除（行内固化排除证据）。 */
    public static final String REVIEW_CONFLICTED = "CONFLICTED";
    /** 冲突声明状态：已声明。 */
    public static final String CONFLICT_DECLARED = "DECLARED";
    /** 冲突声明状态：已解除。 */
    public static final String CONFLICT_LIFTED = "LIFTED";
    /** 历史动作：声明。 */
    public static final String HISTORY_DECLARED = "DECLARED";
    /** 历史动作：解除。 */
    public static final String HISTORY_LIFTED = "LIFTED";
    /** 声明人角色：冲突由评审人本人登记。 */
    public static final String ROLE_REVIEWER = "REVIEWER";
    /** 有权解除冲突的合规角色。 */
    public static final String ROLE_COMPLIANCE = "COMPLIANCE";

    private final TranslationRepository repository;
    private final ObjectMapper objectMapper;
    private final UtcClock clock;

    public TranslationService(TranslationRepository repository, ObjectMapper objectMapper, UtcClock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** 建文档：1~5 种目标语言，可携带初始段落，初始草稿版本 1、发布版本 0；法定人数缺省 1。 */
    @Transactional
    public ApiDtos.DocumentResponse createDocument(ApiDtos.CreateDocumentRequest request) {
        List<String> languages = normalizeLanguages(request.targetLanguages());
        List<ApiDtos.SegmentInput> segments = request.segments();
        long distinct = segments.stream().map(ApiDtos.SegmentInput::segmentId).distinct().count();
        if (distinct != segments.size()) {
            throw ApiException.unprocessable("初始段落 segmentId 重复");
        }
        int requiredReviewCount = request.requiredReviewCount() == null ? 1 : request.requiredReviewCount();
        long documentId = repository.insertDocument(languages, requiredReviewCount);
        for (ApiDtos.SegmentInput segment : segments) {
            repository.insertSegment(documentId, segment.segmentId(), segment.sourceText());
        }
        return new ApiDtos.DocumentResponse(documentId, 1, 0, languages, requiredReviewCount);
    }

    /** 增加段落：segmentId 文档内唯一，草稿版本加一。 */
    @Transactional
    public ApiDtos.SegmentResponse addSegment(long documentId, ApiDtos.AddSegmentRequest request) {
        DocumentRow document = lockDocument(documentId);
        if (repository.findSegment(documentId, request.segmentId()).isPresent()) {
            throw ApiException.conflict("段落已存在: " + request.segmentId());
        }
        repository.insertSegment(documentId, request.segmentId(), request.sourceText());
        int draftVersion = bumpDraftVersion(document);
        return new ApiDtos.SegmentResponse(documentId, request.segmentId(), 1, draftVersion);
    }

    /** 源文修订：源文版本加一、草稿版本加一；相关译文因源文版本落后而待更新。 */
    @Transactional
    public ApiDtos.SegmentResponse reviseSource(long documentId, String segmentId,
                                                ApiDtos.ReviseSourceRequest request) {
        DocumentRow document = lockDocument(documentId);
        SegmentRow segment = findSegmentOrThrow(documentId, segmentId);
        int sourceVersion = segment.sourceVersion() + 1;
        repository.updateSegmentSource(documentId, segmentId, request.sourceText(), sourceVersion);
        int draftVersion = bumpDraftVersion(document);
        return new ApiDtos.SegmentResponse(documentId, segmentId, sourceVersion, draftVersion);
    }

    /**
     * 译文提交：所依据源文版本必须等于当前源文版本；绑定当前术语版本，
     * 当前源文命中的术语规则要求译文包含对应必译文本，否则 422 返回全部违规术语且不写译文；
     * 译文版本递增，草稿版本加一。重新提交不删除旧评审行，版本不匹配的旧评审自动退出法定人数。
     */
    @Transactional
    public ApiDtos.TranslationResponse submitTranslation(long documentId, String segmentId, String language,
                                                         String actorId, ApiDtos.SubmitTranslationRequest request) {
        DocumentRow document = lockDocument(documentId);
        String normalizedLanguage = normalizeLanguage(language);
        if (!document.targetLanguages().contains(normalizedLanguage)) {
            throw ApiException.unprocessable("语言不在文档目标语言中: " + normalizedLanguage);
        }
        SegmentRow segment = findSegmentOrThrow(documentId, segmentId);
        if (request.sourceVersion() != segment.sourceVersion()) {
            throw ApiException.unprocessable("译文所依据的源文版本 " + request.sourceVersion()
                    + " 与当前源文版本 " + segment.sourceVersion() + " 不匹配");
        }
        List<TermRuleRow> termRules = repository.listTermRules(documentId, document.termVersion());
        List<ApiDtos.TermRuleView> violations = findViolations(
                segment.sourceText(), normalizedLanguage, request.content(), termRules);
        if (!violations.isEmpty()) {
            throw ApiException.termViolation("译文违反 " + violations.size() + " 条术语规则", violations);
        }
        int translationVersion = repository.findTranslation(documentId, segmentId, normalizedLanguage)
                .map(TranslationRow::translationVersion).orElse(0) + 1;
        repository.upsertTranslation(documentId, new TranslationRow(segmentId, normalizedLanguage,
                request.content(), actorId, segment.sourceVersion(), translationVersion, document.termVersion()));
        int draftVersion = bumpDraftVersion(document);
        return new ApiDtos.TranslationResponse(documentId, segmentId, normalizedLanguage,
                translationVersion, segment.sourceVersion(), document.termVersion(), draftVersion);
    }

    /** 译文批准：审核人不得是作者、当前未命中有效冲突，且须同时匹配当前源文与译文版本。 */
    @Transactional
    public ApiDtos.ReviewResponse approveTranslation(long documentId, String segmentId, String language,
                                                     String actorId, ApiDtos.ApproveTranslationRequest request) {
        return submitReview(documentId, segmentId, language, actorId,
                DECISION_APPROVED, request.translationVersion(), null);
    }

    /** 译文否决：校验规则同批准，可附否决说明；有效否决与批准一样进入发布快照并阻断发布。 */
    @Transactional
    public ApiDtos.ReviewResponse rejectTranslation(long documentId, String segmentId, String language,
                                                    String actorId, ApiDtos.RejectTranslationRequest request) {
        return submitReview(documentId, segmentId, language, actorId,
                DECISION_REJECTED, request.translationVersion(), request.reason());
    }

    private ApiDtos.ReviewResponse submitReview(long documentId, String segmentId, String language, String actorId,
                                                String decision, int translationVersion, String reason) {
        DocumentRow document = lockDocument(documentId);
        String normalizedLanguage = normalizeLanguage(language);
        SegmentRow segment = findSegmentOrThrow(documentId, segmentId);
        TranslationRow translation = repository.findTranslation(documentId, segmentId, normalizedLanguage)
                .orElseThrow(() -> ApiException.notFound(
                        "译文不存在: " + segmentId + "/" + normalizedLanguage));
        if (translation.author().equals(actorId)) {
            throw ApiException.unprocessable("评审人不得是该译文作者");
        }
        if (translationVersion != translation.translationVersion()) {
            throw ApiException.unprocessable("评审所针对的译文版本 " + translationVersion
                    + " 与当前译文版本 " + translation.translationVersion() + " 不匹配");
        }
        if (translation.sourceVersion() != segment.sourceVersion()) {
            throw ApiException.unprocessable("译文基于源文版本 " + translation.sourceVersion()
                    + "，当前源文版本 " + segment.sourceVersion() + "，译文待更新，不能评审");
        }
        long now = clock.nowMillis();
        List<ConflictRow> activeConflicts =
                repository.findActiveConflicts(documentId, actorId, normalizedLanguage, now);
        if (!activeConflicts.isEmpty()) {
            ConflictRow hit = activeConflicts.get(0);
            throw ApiException.unprocessable("评审人 " + actorId + " 在语种 " + normalizedLanguage
                    + " 命中有效利益冲突（冲突 ID " + hit.conflictId() + "，UTC 区间 [" + hit.validFrom()
                    + ", " + hit.validTo() + ")，要求区间不覆盖当前时刻 " + now + "，原因: " + hit.reason());
        }
        repository.upsertReview(documentId, new ReviewRow(segmentId, normalizedLanguage, actorId, decision,
                REVIEW_ACTIVE, reason, segment.sourceVersion(), translationVersion, null, null, null, null));
        return new ApiDtos.ReviewResponse(documentId, segmentId, normalizedLanguage, actorId, decision,
                REVIEW_ACTIVE, translationVersion, segment.sourceVersion());
    }

    /**
     * 单条冲突声明：校验区间左闭右开不得倒置（validFrom &lt; validTo）、原因非空、语种属于文档；
     * 同一评审人同语种与既有 DECLARED 声明区间重叠返回 409。成功后固化声明与历史，
     * 并把该评审人在文档+语种范围内的已有评审全部标记 CONFLICTED、固化排除证据。
     */
    @Transactional
    public ApiDtos.ConflictView declareConflict(long documentId, String actorId,
                                                ApiDtos.DeclareConflictRequest request) {
        DocumentRow document = lockDocument(documentId);
        Scope scope = validateScope(document, request.language(), request.validFrom(), request.validTo(),
                request.reason(), 0);
        ensureNoOverlap(documentId, actorId, scope, List.of(), 0);
        ConflictViewWithId inserted = insertConflict(document, actorId, scope, nowMillis());
        return inserted.view();
    }

    /**
     * 批量冲突声明：先对全部范围做完整预校验（区间、原因、语种、批内互重叠、与既有声明重叠），
     * 任一非法整批回滚、不改变任何评审资格；全部合法后按请求顺序落库并标记已有评审。
     */
    @Transactional
    public ApiDtos.DeclareConflictsResponse declareConflicts(long documentId, String actorId,
                                                             ApiDtos.DeclareConflictsBatchRequest request) {
        DocumentRow document = lockDocument(documentId);
        List<Scope> scopes = new ArrayList<>();
        for (int i = 0; i < request.declarations().size(); i++) {
            ApiDtos.ConflictDeclarationInput input = request.declarations().get(i);
            scopes.add(validateScope(document, input.language(), input.validFrom(), input.validTo(),
                    input.reason(), i));
        }
        for (int i = 0; i < scopes.size(); i++) {
            ensureNoOverlap(documentId, actorId, scopes.get(i), scopes, i);
        }
        long now = nowMillis();
        List<ApiDtos.ConflictView> views = new ArrayList<>();
        for (Scope scope : scopes) {
            views.add(insertConflict(document, actorId, scope, now).view());
        }
        return new ApiDtos.DeclareConflictsResponse(documentId, views);
    }

    /**
     * 解除冲突：确认人须持有 COMPLIANCE 角色且不同于声明人（否则 403），冲突须处于 DECLARED（重复解除 409）。
     * 解除追加 LIFTED 历史并把因该声明排除的评审行恢复 ACTIVE；声明本身的区间、原因与旧快照均不改写。
     */
    @Transactional
    public ApiDtos.ConflictView liftConflict(long conflictId, String actorId, String actorRole,
                                             String liftReason) {
        ConflictRow fetched = repository.findConflict(conflictId)
                .orElseThrow(() -> ApiException.notFound("冲突声明不存在: " + conflictId));
        // 先锁文档行再锁冲突行，与声明/评审/发布的加锁顺序一致，保证发布读到一致的有效评审集合。
        lockDocument(fetched.documentId());
        ConflictRow conflict = repository.findConflictForUpdate(conflictId).orElseThrow();
        if (!ROLE_COMPLIANCE.equals(normalizeRole(actorRole))) {
            throw ApiException.forbidden("FORBIDDEN_ROLE",
                    "解除冲突须由合规角色 " + ROLE_COMPLIANCE + " 确认，当前角色: " + actorRole);
        }
        if (actorId.equals(conflict.declaredBy())) {
            throw ApiException.forbidden("FORBIDDEN_SELF_LIFT",
                    "解除人不得与声明人为同一人: " + actorId);
        }
        if (CONFLICT_LIFTED.equals(conflict.status())) {
            throw ApiException.conflict("冲突声明已解除，不能重复解除: " + conflictId
                    + "，解除人 " + conflict.liftedBy() + "，解除时刻 " + conflict.liftedAt());
        }
        long now = nowMillis();
        repository.liftConflict(conflictId, actorId, now, liftReason);
        repository.insertConflictHistory(new ConflictHistoryRow(0, conflictId, conflict.documentId(),
                HISTORY_LIFTED, actorId, ROLE_COMPLIANCE,
                liftReason == null ? "" : liftReason, conflict.validFrom(), conflict.validTo(), now));
        // 因该声明被排除的评审行：若评审人仍被同语种其他生效声明排除则保持 CONFLICTED 并刷新证据，
        // 否则恢复 ACTIVE；行内历史证据列均保留。
        for (ReviewRow review : repository.listConflictedReviewsByConflict(conflictId)) {
            List<ConflictRow> stillActive = repository.findActiveConflicts(
                    conflict.documentId(), review.reviewer(), review.language(), now);
            if (stillActive.isEmpty()) {
                repository.reactivateReviewsByConflict(conflictId);
            } else {
                ConflictRow other = stillActive.get(0);
                repository.markReviewsConflicted(conflict.documentId(), review.reviewer(), review.language(),
                        other.conflictId(), other.reason(), other.validFrom(), other.validTo());
            }
        }
        return toView(new ConflictRow(conflictId, conflict.documentId(), conflict.language(), conflict.reviewer(),
                conflict.validFrom(), conflict.validTo(), conflict.reason(), CONFLICT_LIFTED,
                conflict.declaredBy(), conflict.declaredAt(), actorId, now, liftReason), false);
    }

    /**
     * 新增术语版本：expectedTermVersion 必须等于当前术语版本（不符 409）；
     * 规则 0~100 条、按 sourceTerm 与目标语言唯一、语言须在文档目标语言中（不符 422）。
     * 成功后术语版本加一（已有版本不可覆盖），草稿版本加一；术语快照与草稿版本同一事务提交。
     */
    @Transactional
    public ApiDtos.TermVersionResponse updateTerms(long documentId, ApiDtos.UpdateTermsRequest request) {
        DocumentRow document = lockDocument(documentId);
        if (document.termVersion() != request.expectedTermVersion()) {
            throw ApiException.conflict("术语版本冲突：当前术语版本 " + document.termVersion()
                    + "，与期望的 " + request.expectedTermVersion() + " 不一致");
        }
        List<TermRuleRow> rules = new ArrayList<>();
        Set<List<String>> seen = new HashSet<>();
        for (ApiDtos.TermRuleInput input : request.rules()) {
            String language = normalizeLanguage(input.language());
            if (!document.targetLanguages().contains(language)) {
                throw ApiException.unprocessable("术语规则语言不在文档目标语言中: " + language);
            }
            if (!seen.add(List.of(input.sourceTerm(), language))) {
                throw ApiException.unprocessable(
                        "术语规则重复: " + input.sourceTerm() + "/" + language);
            }
            rules.add(new TermRuleRow(input.sourceTerm(), language, input.requiredTranslation()));
        }
        int termVersion = document.termVersion() + 1;
        repository.insertTermVersion(documentId, termVersion);
        for (TermRuleRow rule : rules) {
            repository.insertTermRule(documentId, termVersion, rule);
        }
        repository.updateTermVersion(documentId, termVersion);
        int draftVersion = bumpDraftVersion(document);
        return new ApiDtos.TermVersionResponse(documentId, termVersion, rules.size(), draftVersion);
    }

    /**
     * 发布：校验期望版本（不符 409），再按当前译文版本、术语版本与非冲突有效评审重算法定人数：
     * 缺译/待更新/术语过期 422；存在版本匹配且当前未命中有效冲突的否决 422（REVIEW_REJECTED）；
     * 有效批准数不足法定人数 422（QUORUM_INSUFFICIENT，稳定返回缺失评审总数与受影响段落，不发布部分语种）；
     * 译文绑定当前术语版本且满足当前术语规则（违规 422 并返回全部违规术语）。
     * 全部通过后原子生成完整只读快照（固化评审明细、冲突声明、区间、原因、术语版本与规则集）并递增发布版本。
     */
    @Transactional
    public ApiDtos.PublishResponse publish(long documentId, ApiDtos.PublishRequest request) {
        DocumentRow document = lockDocument(documentId);
        if (document.draftVersion() != request.expectedDraftVersion()
                || document.publishedVersion() != request.expectedPublishedVersion()) {
            throw ApiException.conflict("版本冲突：当前草稿版本 " + document.draftVersion()
                    + "、发布版本 " + document.publishedVersion() + "，与期望的 "
                    + request.expectedDraftVersion() + "/" + request.expectedPublishedVersion() + " 不一致");
        }
        long now = nowMillis();
        List<SegmentRow> segments = repository.listSegments(documentId);
        Map<String, TranslationRow> translations = repository.listTranslations(documentId).stream()
                .collect(Collectors.toMap(t -> key(t.segmentId(), t.language()), Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));
        List<ReviewRow> reviews = repository.listReviews(documentId);
        List<ConflictRow> conflicts = repository.listConflicts(documentId);
        List<TermRuleRow> termRules = repository.listTermRules(documentId, document.termVersion());

        // 结构性校验：每个段落每个语种都必须有基于当前源文、绑定当前术语版本的译文。
        for (SegmentRow segment : segments) {
            for (String language : document.targetLanguages()) {
                TranslationRow translation = translations.get(key(segment.segmentId(), language));
                if (translation == null) {
                    throw ApiException.unprocessable(
                            "缺少译文: " + segment.segmentId() + "/" + language);
                }
                if (translation.sourceVersion() != segment.sourceVersion()) {
                    throw ApiException.unprocessable("译文待更新: " + segment.segmentId() + "/" + language
                            + " 基于源文版本 " + translation.sourceVersion()
                            + "，当前源文版本 " + segment.sourceVersion());
                }
                if (translation.termVersion() != document.termVersion()) {
                    throw ApiException.unprocessable("译文术语版本过期: " + segment.segmentId() + "/" + language
                            + " 绑定术语版本 " + translation.termVersion()
                            + "，当前术语版本 " + document.termVersion());
                }
            }
        }

        Map<String, List<ReviewRow>> reviewsByKey = reviews.stream()
                .collect(Collectors.groupingBy(r -> key(r.segmentId(), r.language()), LinkedHashMap::new,
                        Collectors.toList()));
        // 区间在登记时尚未生效、或排除证据指向已失效声明的评审行：发布时按当前命中冲突重算状态与证据。
        boolean reviewsChanged = false;
        for (ReviewRow review : reviews) {
            List<ConflictRow> hits = repository.findActiveConflicts(
                    documentId, review.reviewer(), review.language(), now);
            ConflictRow hit = hits.isEmpty() ? null : hits.get(0);
            boolean markedByHit = hit != null && REVIEW_CONFLICTED.equals(review.status())
                    && hit.conflictId() == (review.conflictId() == null ? -1L : review.conflictId());
            if (hit != null && !markedByHit) {
                repository.markReviewsConflicted(documentId, review.reviewer(), review.language(),
                        hit.conflictId(), hit.reason(), hit.validFrom(), hit.validTo());
                reviewsChanged = true;
            } else if (hit == null && REVIEW_CONFLICTED.equals(review.status())
                    && !conflictStillCovers(review.conflictId(), conflicts, now)) {
                repository.reactivateReviewsByConflict(review.conflictId() == null ? -1L : review.conflictId());
                reviewsChanged = true;
            }
        }
        if (reviewsChanged) {
            reviews = repository.listReviews(documentId);
            reviewsByKey = reviews.stream()
                    .collect(Collectors.groupingBy(r -> key(r.segmentId(), r.language()), LinkedHashMap::new,
                            Collectors.toList()));
        }
        Set<Long> activeConflictIds = conflicts.stream()
                .filter(c -> CONFLICT_DECLARED.equals(c.status()) && c.validFrom() <= now && c.validTo() > now)
                .map(ConflictRow::conflictId)
                .collect(Collectors.toCollection(HashSet::new));
        Map<Long, ConflictRow> conflictById = conflicts.stream()
                .collect(Collectors.toMap(ConflictRow::conflictId, Function.identity()));

        List<ApiDtos.TermRuleView> termViolations = new ArrayList<>();
        List<String> affectedSegments = new ArrayList<>();
        int missingReviews = 0;
        String rejectedKey = null;
        String rejectedReviewer = null;
        Map<String, Integer> effectiveApprovalsByKey = new HashMap<>();
        Map<String, List<ReviewRow>> effectiveReviewsByKey = new HashMap<>();
        for (SegmentRow segment : segments) {
            for (String language : document.targetLanguages()) {
                String k = key(segment.segmentId(), language);
                TranslationRow translation = translations.get(k);
                termViolations.addAll(findViolations(
                        segment.sourceText(), language, translation.content(), termRules));
                List<ReviewRow> keyReviews = reviewsByKey.getOrDefault(k, List.of());
                List<ReviewRow> effective = new ArrayList<>();
                int approvals = 0;
                for (ReviewRow review : keyReviews) {
                    if (review.translationVersion() != translation.translationVersion()
                            || review.sourceVersion() != segment.sourceVersion()) {
                        continue;
                    }
                    if (isReviewerConflictedNow(conflicts, review.reviewer(), language, now)) {
                        continue;
                    }
                    effective.add(review);
                    if (DECISION_APPROVED.equals(review.decision())) {
                        approvals++;
                    } else if (rejectedKey == null) {
                        rejectedKey = k;
                        rejectedReviewer = review.reviewer();
                    }
                }
                effectiveReviewsByKey.put(k, effective);
                effectiveApprovalsByKey.put(k, approvals);
                int missing = Math.max(0, document.requiredReviewCount() - approvals);
                if (missing > 0) {
                    missingReviews += missing;
                    affectedSegments.add(segment.segmentId() + "/" + language + "（实际有效批准 "
                            + approvals + "，要求 " + document.requiredReviewCount() + "，差额 " + missing + "）");
                }
            }
        }
        if (!termViolations.isEmpty()) {
            throw ApiException.termViolation("译文违反 " + termViolations.size() + " 条术语规则", termViolations);
        }
        if (rejectedKey != null) {
            throw ApiException.reviewRejected(
                    "存在有效否决评审: " + rejectedKey + "，评审人 " + rejectedReviewer);
        }
        if (missingReviews > 0) {
            throw ApiException.quorumFailure(
                    "法定人数不足：缺失有效批准 " + missingReviews + " 个（要求每个译文 "
                            + document.requiredReviewCount() + " 个非冲突有效批准），受影响段落 "
                            + affectedSegments.size() + " 个",
                    missingReviews, affectedSegments);
        }
        int publishedVersion = document.publishedVersion() + 1;
        repository.insertSnapshot(documentId, publishedVersion,
                buildSnapshotJson(document, publishedVersion, segments, translations, reviewsByKey,
                        effectiveApprovalsByKey, conflicts, activeConflictIds, conflictById, termRules, now));
        repository.updatePublishedVersion(documentId, publishedVersion);
        return new ApiDtos.PublishResponse(documentId, publishedVersion);
    }

    /** 查询指定发布版本的只读快照 JSON；不存在返回 404。 */
    @Transactional(readOnly = true)
    public String getRelease(long documentId, int publishedVersion) {
        repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        return repository.findSnapshot(documentId, publishedVersion)
                .orElseThrow(() -> ApiException.notFound(
                        "发布版本不存在: " + documentId + "/" + publishedVersion));
    }

    /** 查询当前术语版本及完整规则集；尚未建立术语版本时返回版本 0 与空规则。 */
    @Transactional(readOnly = true)
    public ApiDtos.TermVersionView getCurrentTerms(long documentId) {
        DocumentRow document = repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        return new ApiDtos.TermVersionView(documentId, document.termVersion(),
                toRuleViews(repository.listTermRules(documentId, document.termVersion())));
    }

    /** 查询指定术语版本的不可变规则集；版本不存在返回 404。 */
    @Transactional(readOnly = true)
    public ApiDtos.TermVersionView getTerms(long documentId, int termVersion) {
        repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        if (termVersion < 1 || !repository.termVersionExists(documentId, termVersion)) {
            throw ApiException.notFound("术语版本不存在: " + documentId + "/" + termVersion);
        }
        return new ApiDtos.TermVersionView(documentId, termVersion,
                toRuleViews(repository.listTermRules(documentId, termVersion)));
    }

    /**
     * 查询全部译文的术语状态：绑定术语版本、是否相对当前术语版本过期，
     * 以及按当前源文与当前术语规则判定的违规术语（无违规为空列表）。
     */
    @Transactional(readOnly = true)
    public ApiDtos.TermStatusResponse getTermStatus(long documentId) {
        DocumentRow document = repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        Map<String, SegmentRow> segments = repository.listSegments(documentId).stream()
                .collect(Collectors.toMap(SegmentRow::segmentId, Function.identity()));
        List<TermRuleRow> termRules = repository.listTermRules(documentId, document.termVersion());
        List<ApiDtos.TranslationTermStatus> statuses = new ArrayList<>();
        for (TranslationRow translation : repository.listTranslations(documentId)) {
            SegmentRow segment = segments.get(translation.segmentId());
            List<ApiDtos.TermRuleView> violations = segment == null ? List.of()
                    : findViolations(segment.sourceText(), translation.language(),
                            translation.content(), termRules);
            statuses.add(new ApiDtos.TranslationTermStatus(translation.segmentId(), translation.language(),
                    translation.translationVersion(), translation.termVersion(),
                    translation.termVersion() != document.termVersion(), violations));
        }
        return new ApiDtos.TermStatusResponse(documentId, document.termVersion(), statuses);
    }

    /** 冲突明细查询：文档内全部声明及当前是否命中，按冲突 ID 升序；读取不改变状态。 */
    @Transactional(readOnly = true)
    public ApiDtos.ConflictListResponse getConflicts(long documentId) {
        DocumentRow document = repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        long now = nowMillis();
        List<ApiDtos.ConflictView> views = repository.listConflicts(documentId).stream()
                .map(c -> toView(c, isActiveAt(c, now)))
                .toList();
        return new ApiDtos.ConflictListResponse(documentId, views);
    }

    /** 冲突历史查询：声明与解除的只追加记录，按追加顺序返回；读取不改变状态。 */
    @Transactional(readOnly = true)
    public ApiDtos.ConflictHistoryResponse getConflictHistory(long documentId) {
        repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        List<ApiDtos.ConflictHistoryView> views = repository.listConflictHistory(documentId).stream()
                .map(h -> new ApiDtos.ConflictHistoryView(h.historyId(), h.conflictId(), h.documentId(),
                        h.action(), h.actor(), h.actorRole(), h.reason(), h.validFrom(), h.validTo(),
                        h.createdAt()))
                .toList();
        return new ApiDtos.ConflictHistoryResponse(documentId, views);
    }

    /**
     * 评审诊断查询：按段落/语种给出评审明细、各评审当前是否计入法定人数、
     * 法定人数要求值、有效批准实际值与缺失差额；读取不改变状态。
     */
    @Transactional(readOnly = true)
    public ApiDtos.ReviewDiagnosticsResponse getReviewDiagnostics(long documentId) {
        DocumentRow document = repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        long now = nowMillis();
        List<ConflictRow> conflicts = repository.listConflicts(documentId);
        Map<String, SegmentRow> segments = repository.listSegments(documentId).stream()
                .collect(Collectors.toMap(SegmentRow::segmentId, Function.identity()));
        Map<String, TranslationRow> translations = repository.listTranslations(documentId).stream()
                .collect(Collectors.toMap(t -> key(t.segmentId(), t.language()), Function.identity()));
        Map<String, List<ReviewRow>> reviewsByKey = repository.listReviews(documentId).stream()
                .collect(Collectors.groupingBy(r -> key(r.segmentId(), r.language())));
        List<ApiDtos.TranslationReviewDiagnostics> result = new ArrayList<>();
        for (SegmentRow segment : repository.listSegments(documentId)) {
            for (String language : document.targetLanguages()) {
                String k = key(segment.segmentId(), language);
                TranslationRow translation = translations.get(k);
                List<ApiDtos.ReviewDiagnosticsView> reviewViews = new ArrayList<>();
                int effectiveApprovals = 0;
                List<ReviewRow> keyReviews = reviewsByKey.getOrDefault(k, List.of());
                for (ReviewRow review : keyReviews) {
                    boolean versionMatch = translation != null
                            && review.translationVersion() == translation.translationVersion()
                            && review.sourceVersion() == segment.sourceVersion();
                    boolean conflictedNow = isReviewerConflictedNow(conflicts, review.reviewer(), language, now);
                    boolean effective = versionMatch && !conflictedNow
                            && DECISION_APPROVED.equals(review.decision());
                    if (effective) {
                        effectiveApprovals++;
                    }
                    reviewViews.add(new ApiDtos.ReviewDiagnosticsView(review.segmentId(), review.language(),
                            review.reviewer(), review.decision(), review.status(), review.reason(),
                            review.sourceVersion(), review.translationVersion(), review.conflictId(),
                            review.conflictReason(), review.conflictValidFrom(), review.conflictValidTo(),
                            versionMatch && !conflictedNow));
                }
                int translationVersion = translation == null ? 0 : translation.translationVersion();
                int sourceVersion = translation == null ? segment.sourceVersion() : translation.sourceVersion();
                result.add(new ApiDtos.TranslationReviewDiagnostics(segment.segmentId(), language,
                        translationVersion, sourceVersion, document.requiredReviewCount(), effectiveApprovals,
                        Math.max(0, document.requiredReviewCount() - effectiveApprovals), reviewViews));
            }
        }
        return new ApiDtos.ReviewDiagnosticsResponse(documentId, document.requiredReviewCount(), result);
    }

    private DocumentRow lockDocument(long documentId) {
        return repository.findDocumentForUpdate(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
    }

    private SegmentRow findSegmentOrThrow(long documentId, String segmentId) {
        return repository.findSegment(documentId, segmentId)
                .orElseThrow(() -> ApiException.notFound("段落不存在: " + segmentId));
    }

    private int bumpDraftVersion(DocumentRow document) {
        int draftVersion = document.draftVersion() + 1;
        repository.updateDraftVersion(document.documentId(), draftVersion);
        return draftVersion;
    }

    private long nowMillis() {
        return clock.nowMillis();
    }

    /** 校验单个冲突范围：语种属于文档、原因非空白、UTC 区间左闭右开不得倒置。 */
    private Scope validateScope(DocumentRow document, String language, Long validFrom, Long validTo,
                                String reason, int index) {
        String prefix = "批量声明第 " + index + " 条（从 0 开始）";
        String normalizedLanguage = normalizeLanguage(language);
        if (!document.targetLanguages().contains(normalizedLanguage)) {
            throw ApiException.unprocessable(prefix + "语言不在文档目标语言中: " + normalizedLanguage);
        }
        if (reason == null || reason.isBlank()) {
            throw ApiException.unprocessable(prefix + "冲突原因不能为空；实际值: " + reason);
        }
        if (validFrom == null || validTo == null || validFrom >= validTo) {
            throw ApiException.unprocessable(prefix + "冲突区间不得倒置，要求 validFrom < validTo（UTC，左闭右开）；"
                    + "实际 validFrom=" + validFrom + "、validTo=" + validTo + "，实际时长 "
                    + (validFrom == null || validTo == null ? "null" : String.valueOf(validTo - validFrom))
                    + " 毫秒，要求大于 0");
        }
        return new Scope(normalizedLanguage, validFrom, validTo, reason);
    }

    /**
     * 重叠校验：与同评审人同语种的既有 DECLARED 声明（含已自然到期但未解除的历史声明）以及批内其他范围
     * 均不得时间重叠（相邻不算重叠）；已 LIFTED 的声明不再占用范围，允许新声明覆盖。
     */
    private void ensureNoOverlap(long documentId, String actorId, Scope scope, List<Scope> batch, int selfIndex) {
        for (ConflictRow existing : repository.listDeclaredConflicts(documentId, actorId, scope.language())) {
            if (overlaps(scope.validFrom(), scope.validTo(), existing.validFrom(), existing.validTo())) {
                throw ApiException.conflict("评审人 " + actorId + " 在语种 " + scope.language()
                        + " 的声明与既有有效声明 " + existing.conflictId() + " 区间 [" + existing.validFrom()
                        + ", " + existing.validTo() + ") 重叠；新声明实际区间 [" + scope.validFrom() + ", "
                        + scope.validTo() + ")，要求区间互不相交");
            }
        }
        for (int i = 0; i < batch.size(); i++) {
            if (i == selfIndex) {
                continue;
            }
            Scope other = batch.get(i);
            if (other.language().equals(scope.language())
                    && overlaps(scope.validFrom(), scope.validTo(), other.validFrom(), other.validTo())) {
                throw ApiException.conflict("批量声明第 " + selfIndex + " 条与第 " + i
                        + " 条在语种 " + scope.language() + " 的区间重叠；实际区间 [" + scope.validFrom() + ", "
                        + scope.validTo() + ") 与 [" + other.validFrom() + ", " + other.validTo()
                        + ")，要求批内区间互不相交");
            }
        }
    }

    /**
     * 落库一条声明与历史；当声明区间在登记时刻已生效（左闭右开覆盖 now），
     * 标记该评审人在文档+语种范围内的全部已有评审为 CONFLICTED 并固化证据；
     * 未来区间登记时不改既有评审状态，待区间生效后由发布重算同步。
     */
    private ConflictViewWithId insertConflict(DocumentRow document, String actorId, Scope scope, long now) {
        long conflictId = repository.insertConflict(new ConflictRow(0, document.documentId(), scope.language(),
                actorId, scope.validFrom(), scope.validTo(), scope.reason(), CONFLICT_DECLARED, actorId, now,
                null, null, null));
        repository.insertConflictHistory(new ConflictHistoryRow(0, conflictId, document.documentId(),
                HISTORY_DECLARED, actorId, ROLE_REVIEWER, scope.reason(),
                scope.validFrom(), scope.validTo(), now));
        if (scope.validFrom() <= now && scope.validTo() > now) {
            repository.markReviewsConflicted(document.documentId(), actorId, scope.language(), conflictId,
                    scope.reason(), scope.validFrom(), scope.validTo());
        }
        ConflictRow row = new ConflictRow(conflictId, document.documentId(), scope.language(), actorId,
                scope.validFrom(), scope.validTo(), scope.reason(), CONFLICT_DECLARED, actorId, now,
                null, null, null);
        return new ConflictViewWithId(conflictId, toView(row, isActiveAt(row, now)));
    }

    private static boolean overlaps(long fromA, long toA, long fromB, long toB) {
        return fromA < toB && fromB < toA;
    }

    private static boolean isActiveAt(ConflictRow conflict, long now) {
        return CONFLICT_DECLARED.equals(conflict.status())
                && conflict.validFrom() <= now && conflict.validTo() > now;
    }

    /** 评审行上固化的冲突声明在 t 时刻是否仍覆盖该评审（DECLARED 且左闭右开）。 */
    private static boolean conflictStillCovers(Long conflictId, List<ConflictRow> conflicts, long now) {
        if (conflictId == null) {
            return false;
        }
        for (ConflictRow conflict : conflicts) {
            if (conflict.conflictId() == conflictId) {
                return isActiveAt(conflict, now);
            }
        }
        return false;
    }

    /** 评审人在 t 时刻是否被任一 DECLARED 且覆盖 t 的同语种声明排除。 */
    private static boolean isReviewerConflictedNow(List<ConflictRow> conflicts, String reviewer, String language,
                                                   long now) {
        for (ConflictRow conflict : conflicts) {
            if (conflict.reviewer().equals(reviewer) && conflict.language().equals(language)
                    && isActiveAt(conflict, now)) {
                return true;
            }
        }
        return false;
    }

    private static ApiDtos.ConflictView toView(ConflictRow c, boolean active) {
        return new ApiDtos.ConflictView(c.conflictId(), c.documentId(), c.language(), c.reviewer(),
                c.validFrom(), c.validTo(), c.validTo() - c.validFrom(), c.reason(), c.status(),
                c.declaredBy(), c.declaredAt(), c.liftedBy(), c.liftedAt(), c.liftReason(), active);
    }

    private static String key(String segmentId, String language) {
        return segmentId + " " + language;
    }

    private static String normalizeLanguage(String language) {
        return language.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeRole(String role) {
        return role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
    }

    private static List<String> normalizeLanguages(List<String> languages) {
        List<String> normalized = languages.stream().map(TranslationService::normalizeLanguage).distinct().toList();
        if (normalized.size() != languages.size()) {
            throw ApiException.unprocessable("目标语言重复");
        }
        return normalized;
    }

    /**
     * 术语违规判定：源文按 Unicode 原文、区分大小写做连续子串匹配；仅源文命中 sourceTerm 的规则参与校验，
     * 译文正文（同样区分大小写）不含 requiredTranslation 即为违规。返回全部违规规则。
     */
    private static List<ApiDtos.TermRuleView> findViolations(String sourceText, String language, String content,
                                                             List<TermRuleRow> rules) {
        List<ApiDtos.TermRuleView> violations = new ArrayList<>();
        for (TermRuleRow rule : rules) {
            if (rule.language().equals(language) && sourceText.contains(rule.sourceTerm())
                    && !content.contains(rule.requiredTranslation())) {
                violations.add(toRuleView(rule));
            }
        }
        return violations;
    }

    private static List<ApiDtos.TermRuleView> toRuleViews(List<TermRuleRow> rules) {
        return rules.stream().map(TranslationService::toRuleView).toList();
    }

    private static ApiDtos.TermRuleView toRuleView(TermRuleRow rule) {
        return new ApiDtos.TermRuleView(rule.sourceTerm(), rule.language(), rule.requiredTranslation());
    }

    /** 生成完整只读快照 JSON：固化全部段落、译文、评审明细与冲突声明/排除证据、法定人数、版本号与术语规则集。 */
    private String buildSnapshotJson(DocumentRow document, int publishedVersion, List<SegmentRow> segments,
                                     Map<String, TranslationRow> translations,
                                     Map<String, List<ReviewRow>> reviewsByKey,
                                     Map<String, Integer> effectiveApprovalsByKey,
                                     List<ConflictRow> conflicts, Set<Long> activeConflictIds,
                                     Map<Long, ConflictRow> conflictById, List<TermRuleRow> termRules, long now) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("documentId", document.documentId());
        snapshot.put("publishedVersion", publishedVersion);
        snapshot.put("draftVersion", document.draftVersion());
        snapshot.put("termVersion", document.termVersion());
        snapshot.put("requiredReviewCount", document.requiredReviewCount());
        snapshot.put("quorumEvaluatedAt", now);
        snapshot.put("targetLanguages", document.targetLanguages());
        List<Map<String, Object>> termList = new ArrayList<>();
        for (TermRuleRow rule : termRules) {
            Map<String, Object> termJson = new LinkedHashMap<>();
            termJson.put("sourceTerm", rule.sourceTerm());
            termJson.put("language", rule.language());
            termJson.put("requiredTranslation", rule.requiredTranslation());
            termList.add(termJson);
        }
        snapshot.put("terms", termList);
        snapshot.put("conflicts", buildConflictSnapshot(conflicts, now));
        List<Map<String, Object>> segmentList = new ArrayList<>();
        for (SegmentRow segment : segments) {
            Map<String, Object> segmentJson = new LinkedHashMap<>();
            segmentJson.put("segmentId", segment.segmentId());
            segmentJson.put("sourceText", segment.sourceText());
            segmentJson.put("sourceVersion", segment.sourceVersion());
            List<Map<String, Object>> translationList = new ArrayList<>();
            for (String language : document.targetLanguages()) {
                String k = key(segment.segmentId(), language);
                TranslationRow translation = translations.get(k);
                List<ReviewRow> keyReviews = reviewsByKey.getOrDefault(k, List.of()).stream()
                        .sorted(Comparator.comparing(ReviewRow::reviewer))
                        .toList();
                String firstApprover = keyReviews.stream()
                        .filter(r -> DECISION_APPROVED.equals(r.decision())
                                && r.translationVersion() == translation.translationVersion()
                                && r.sourceVersion() == segment.sourceVersion()
                                && !isReviewerConflictedNow(conflicts, r.reviewer(), language, now))
                        .map(ReviewRow::reviewer)
                        .findFirst().orElse(null);
                Map<String, Object> translationJson = new LinkedHashMap<>();
                translationJson.put("language", language);
                translationJson.put("content", translation.content());
                translationJson.put("author", translation.author());
                translationJson.put("translationVersion", translation.translationVersion());
                translationJson.put("sourceVersion", translation.sourceVersion());
                translationJson.put("termVersion", translation.termVersion());
                translationJson.put("requiredReviewCount", document.requiredReviewCount());
                translationJson.put("effectiveApprovals", effectiveApprovalsByKey.getOrDefault(k, 0));
                translationJson.put("reviewer", firstApprover);
                translationJson.put("reviews", buildReviewSnapshot(keyReviews, translation, segment,
                        conflicts, language, now));
                translationList.add(translationJson);
            }
            segmentJson.put("translations", translationList);
            segmentList.add(segmentJson);
        }
        snapshot.put("segments", segmentList);
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            throw new IllegalStateException("快照序列化失败", e);
        }
    }

    /** 评审明细快照：决定、状态、版本匹配与是否计入法定人数，以及最近一次排除的冲突 ID、区间与原因。 */
    private List<Map<String, Object>> buildReviewSnapshot(List<ReviewRow> keyReviews, TranslationRow translation,
                                                          SegmentRow segment, List<ConflictRow> conflicts,
                                                          String language, long now) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ReviewRow review : keyReviews) {
            boolean versionMatch = review.translationVersion() == translation.translationVersion()
                    && review.sourceVersion() == segment.sourceVersion();
            boolean conflictedNow = isReviewerConflictedNow(conflicts, review.reviewer(), language, now);
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("reviewer", review.reviewer());
            json.put("decision", review.decision());
            json.put("status", review.status());
            json.put("reason", review.reason());
            json.put("sourceVersion", review.sourceVersion());
            json.put("translationVersion", review.translationVersion());
            json.put("versionMatch", versionMatch);
            json.put("conflictedNow", conflictedNow);
            json.put("effective", versionMatch && !conflictedNow);
            json.put("conflictId", review.conflictId());
            json.put("conflictReason", review.conflictReason());
            json.put("conflictValidFrom", review.conflictValidFrom());
            json.put("conflictValidTo", review.conflictValidTo());
            list.add(json);
        }
        return list;
    }

    /** 冲突声明快照：固化区间、原因、声明/解除信息与发布时刻是否命中。 */
    private List<Map<String, Object>> buildConflictSnapshot(List<ConflictRow> conflicts, long now) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ConflictRow c : conflicts) {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("conflictId", c.conflictId());
            json.put("language", c.language());
            json.put("reviewer", c.reviewer());
            json.put("validFrom", c.validFrom());
            json.put("validTo", c.validTo());
            json.put("durationMillis", c.validTo() - c.validFrom());
            json.put("reason", c.reason());
            json.put("status", c.status());
            json.put("declaredBy", c.declaredBy());
            json.put("declaredAt", c.declaredAt());
            json.put("liftedBy", c.liftedBy());
            json.put("liftedAt", c.liftedAt());
            json.put("liftReason", c.liftReason());
            json.put("activeAtPublish", isActiveAt(c, now));
            list.add(json);
        }
        return list;
    }

    /** 预校验通过的冲突范围。 */
    private record Scope(String language, long validFrom, long validTo, String reason) {
    }

    /** 落库后的声明 ID 与视图。 */
    private record ConflictViewWithId(long conflictId, ApiDtos.ConflictView view) {
    }
}
