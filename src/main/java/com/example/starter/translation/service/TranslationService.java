package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiDtos;
import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.domain.Rows.ApprovalRow;
import com.example.starter.translation.domain.Rows.ConflictDeclarationRow;
import com.example.starter.translation.domain.Rows.ConflictEventRow;
import com.example.starter.translation.domain.Rows.DocumentRow;
import com.example.starter.translation.domain.Rows.SegmentRow;
import com.example.starter.translation.domain.Rows.TermRuleRow;
import com.example.starter.translation.domain.Rows.TranslationRow;
import com.example.starter.translation.repo.TranslationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
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
 * 所有写操作先对文档行加 FOR UPDATE 行锁，保证同一文档的修改、评审、冲突登记/解除与发布串行，
 * 发布在同一事务内读取一致的有效评审集合；方法均加入调用方事务，与幂等记录原子提交。
 * 冲突区间、评审与历史时间统一 UTC，区间左闭右开，毫秒精度。
 */
@Service
public class TranslationService {

    /** 每个段落每个语种发布要求的有效批准数。 */
    public static final int REQUIRED_REVIEW_COUNT = 1;

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_RESOLVED = "RESOLVED";
    private static final String DECISION_APPROVED = "APPROVED";
    private static final String DECISION_REJECTED = "REJECTED";
    private static final String ROLE_COMPLIANCE = "COMPLIANCE";
    private static final String EVENT_DECLARED = "DECLARED";
    private static final String EVENT_RESOLVED = "RESOLVED";

    private final TranslationRepository repository;
    private final ObjectMapper objectMapper;
    private final BusinessClock clock;

    public TranslationService(TranslationRepository repository, ObjectMapper objectMapper, BusinessClock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** 建文档：1~5 种目标语言，可携带初始段落，初始草稿版本 1、发布版本 0。 */
    @Transactional
    public ApiDtos.DocumentResponse createDocument(ApiDtos.CreateDocumentRequest request) {
        List<String> languages = normalizeLanguages(request.targetLanguages());
        List<ApiDtos.SegmentInput> segments = request.segments();
        long distinct = segments.stream().map(ApiDtos.SegmentInput::segmentId).distinct().count();
        if (distinct != segments.size()) {
            throw ApiException.unprocessable("初始段落 segmentId 重复");
        }
        long documentId = repository.insertDocument(languages);
        for (ApiDtos.SegmentInput segment : segments) {
            repository.insertSegment(documentId, segment.segmentId(), segment.sourceText());
        }
        return new ApiDtos.DocumentResponse(documentId, 1, 0, languages);
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
     * 译文版本递增，草稿版本加一。
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

    /** 译文批准：审核人不得是作者，须匹配当前源文与译文版本，且不得命中有效利益冲突。 */
    @Transactional
    public ApiDtos.ApprovalResponse approveTranslation(long documentId, String segmentId, String language,
                                                       String actorId, ApiDtos.ApproveTranslationRequest request) {
        return submitReview(documentId, segmentId, language, actorId, request.translationVersion(),
                DECISION_APPROVED, null);
    }

    /** 译文否决：规则同批准，另需固化非空否决原因；有效否决阻断发布。 */
    @Transactional
    public ApiDtos.ApprovalResponse rejectTranslation(long documentId, String segmentId, String language,
                                                      String actorId, ApiDtos.RejectTranslationRequest request) {
        return submitReview(documentId, segmentId, language, actorId, request.translationVersion(),
                DECISION_REJECTED, request.reason());
    }

    private ApiDtos.ApprovalResponse submitReview(long documentId, String segmentId, String language,
                                                  String actorId, int translationVersion, String decision,
                                                  String decisionReason) {
        DocumentRow document = lockDocument(documentId);
        String normalizedLanguage = normalizeLanguage(language);
        if (!document.targetLanguages().contains(normalizedLanguage)) {
            throw ApiException.unprocessable("语言不在文档目标语言中: " + normalizedLanguage);
        }
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
        Instant now = clock.now();
        List<ConflictDeclarationRow> blocking = effectiveConflicts(
                repository.listConflicts(documentId), actorId, normalizedLanguage, now);
        if (!blocking.isEmpty()) {
            String ids = blocking.stream().map(c -> String.valueOf(c.conflictId())).collect(Collectors.joining(","));
            throw ApiException.reviewerConflict("评审人 " + actorId + " 在语种 " + normalizedLanguage
                    + " 命中有效利益冲突，冲突声明 ID: " + ids + "，不得提交批准或否决");
        }
        repository.insertReview(documentId, new ApprovalRow(0, segmentId, normalizedLanguage, actorId, decision,
                decisionReason, STATUS_ACTIVE, null, segment.sourceVersion(), translationVersion, now));
        return new ApiDtos.ApprovalResponse(documentId, segmentId, normalizedLanguage, actorId,
                translationVersion, segment.sourceVersion(), decision, STATUS_ACTIVE);
    }

    /**
     * 批量登记利益冲突：评审人与声明人取 X-Actor-Id。写入前对全部范围做完整预校验
     * （时间格式、区间不得倒置、语种须在文档内、批次内同语种区间不得重叠），
     * 任一非法整批拒绝（422），不写入任何声明、不改变评审资格；
     * 与同一评审人同语种既有有效声明区间重叠返回 409。
     * 全部通过后逐范围固化声明与 DECLARED 历史，并把该评审人该语种既有 ACTIVE 评审标记 CONFLICTED。
     */
    @Transactional
    public ApiDtos.ConflictRegistrationResponse registerConflicts(
            long documentId, String actorId, ApiDtos.RegisterConflictsRequest request) {
        DocumentRow document = lockDocument(documentId);
        Instant now = clock.now();

        // 第一阶段：完整预校验全部范围的形状（时间、区间方向、语种、批次内重叠），不做任何写入。
        record ValidatedRange(String language, Instant from, Instant to, String reason) {
        }
        List<ValidatedRange> validated = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<ApiDtos.ConflictRangeInput> ranges = request.ranges();
        for (int i = 0; i < ranges.size(); i++) {
            ApiDtos.ConflictRangeInput input = ranges.get(i);
            String prefix = "范围[" + i + "] ";
            String lang = normalizeLanguage(input.language());
            if (!document.targetLanguages().contains(lang)) {
                errors.add(prefix + "语言不在文档目标语言中: " + lang);
            }
            Instant from = parseInstant(input.validFrom(), prefix + "validFrom 非法 UTC 时间: ");
            Instant to = parseInstant(input.validTo(), prefix + "validTo 非法 UTC 时间: ");
            if (from != null && to != null && !from.isBefore(to)) {
                errors.add(prefix + "区间不得倒置或零长：validFrom " + from + " 必须早于 validTo " + to);
            }
            if (from != null && to != null && from.isBefore(to)) {
                validated.add(new ValidatedRange(lang, from, to, input.reason()));
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.unprocessable("冲突范围预校验失败，整批未登记: " + String.join("; ", errors));
        }

        // 批次内同语种区间重叠（含完全相同）属于整批非法，排序后扫描相邻区间。
        Map<String, List<ValidatedRange>> byLanguage = validated.stream()
                .collect(Collectors.groupingBy(ValidatedRange::language, LinkedHashMap::new, Collectors.toList()));
        for (Map.Entry<String, List<ValidatedRange>> entry : byLanguage.entrySet()) {
            List<ValidatedRange> sorted = entry.getValue().stream()
                    .sorted(Comparator.comparing(ValidatedRange::from).thenComparing(ValidatedRange::to)).toList();
            for (int i = 1; i < sorted.size(); i++) {
                ValidatedRange prev = sorted.get(i - 1);
                ValidatedRange cur = sorted.get(i);
                if (cur.from.isBefore(prev.to)) {
                    throw ApiException.unprocessable("冲突范围预校验失败，整批未登记: 语种 " + entry.getKey()
                            + " 批次内区间重叠 [" + prev.from + "," + prev.to + ") 与 ["
                            + cur.from + "," + cur.to + ")");
                }
            }
        }

        // 与既有未解除声明重叠：同评审人同范围重叠声明须合并，返回 409。
        List<ConflictDeclarationRow> existing = repository.listConflicts(documentId).stream()
                .filter(c -> c.active() && c.reviewer().equals(actorId)).toList();
        for (ValidatedRange range : validated) {
            for (ConflictDeclarationRow old : existing) {
                if (old.language().equals(range.language()) && overlaps(range.from(), range.to(),
                        old.validFrom(), old.validTo())) {
                    throw ApiException.conflict("评审人 " + actorId + " 在语种 " + range.language()
                            + " 与既有有效冲突声明 ID " + old.conflictId() + " 区间 [" + old.validFrom() + ","
                            + old.validTo() + ") 重叠，请合并为一条声明后再登记");
                }
            }
        }

        // 第二阶段：全部范围合法且无重叠，逐范围写声明、追加历史，并把该评审人该语种既有 ACTIVE
        // 评审立即标记 CONFLICTED（冲突登记后既有评审一律固化排除，区间是否已生效都登记留痕）；
        // 区间生效后的新提交则由提交/发布的 UTC 有效冲突判定动态拦截。同一事务提交。
        List<ApiDtos.RegisteredConflictView> views = new ArrayList<>();
        int markedTotal = 0;
        for (ValidatedRange range : validated) {
            long conflictId = repository.insertConflictDeclaration(new ConflictDeclarationRow(
                    0, documentId, actorId, range.language(), range.from(), range.to(), range.reason(),
                    STATUS_ACTIVE, actorId, now, null, null, null));
            repository.insertConflictEvent(new ConflictEventRow(
                    0, conflictId, documentId, EVENT_DECLARED, actorId, "REVIEWER",
                    range.from(), range.to(), range.reason(), now));
            int marked = repository.markReviewsConflicted(documentId, actorId, range.language(), conflictId);
            markedTotal += marked;
            views.add(new ApiDtos.RegisteredConflictView(conflictId, range.language(), iso(range.from()),
                    iso(range.to()), range.reason(), STATUS_ACTIVE, marked));
        }
        return new ApiDtos.ConflictRegistrationResponse(documentId, actorId, validated.size(),
                markedTotal, views);
    }

    /**
     * 解除冲突：须由不同于声明人的合规角色（X-Actor-Role: COMPLIANCE）确认，
     * 置 RESOLVED 并追加 RESOLVED 历史（固化区间与解除原因）；不改动任何既有评审证据，
     * 只影响后续评审（可重新提交）与发布。重复解除返回 409。
     */
    @Transactional
    public ApiDtos.ConflictView resolveConflict(long documentId, long conflictId, String actorId,
                                                String actorRole, ApiDtos.ResolveConflictRequest request) {
        if (!ROLE_COMPLIANCE.equalsIgnoreCase(actorRole == null ? "" : actorRole.trim())) {
            throw ApiException.forbidden("解除冲突须由合规角色确认（请求头 X-Actor-Role: COMPLIANCE）");
        }
        lockDocument(documentId);
        ConflictDeclarationRow declaration = repository.findConflictForUpdate(conflictId)
                .orElseThrow(() -> ApiException.notFound("冲突声明不存在: " + conflictId));
        if (declaration.documentId() != documentId) {
            throw ApiException.notFound("冲突声明不属于该文档: " + conflictId);
        }
        if (!declaration.active()) {
            throw ApiException.conflict("冲突声明已解除，不能重复解除: " + conflictId);
        }
        if (actorId.equals(declaration.declaredBy())) {
            throw ApiException.forbidden("解除确认人不得是冲突声明人: " + actorId);
        }
        Instant now = clock.now();
        repository.resolveConflict(conflictId, actorId, request.reason(), now);
        repository.insertConflictEvent(new ConflictEventRow(
                0, conflictId, documentId, EVENT_RESOLVED, actorId, ROLE_COMPLIANCE,
                declaration.validFrom(), declaration.validTo(), request.reason(), now));
        ConflictDeclarationRow resolved = repository.findConflict(conflictId).orElseThrow();
        return toConflictView(resolved, repository.listConflictEvents(documentId));
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
     * 缺译/待更新/术语过期 422；存在版本匹配的有效否决返回 REVIEW_REJECTED 422（含否决明细）；
     * 有效批准数不足返回 QUORUM_NOT_MET 422（稳定给出要求值、缺失评审数与受影响段落，全部语种一起失败）；
     * 译文还须满足当前术语规则（违规 422 并返回全部违规术语）。
     * 全部通过后原子生成完整只读快照（固化术语版本、评审版本/决定/状态/冲突、冲突声明区间与原因）。
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
        Instant now = clock.now();
        List<SegmentRow> segments = repository.listSegments(documentId);
        Map<String, TranslationRow> translations = repository.listTranslations(documentId).stream()
                .collect(Collectors.toMap(t -> key(t.segmentId(), t.language()), Function.identity()));
        List<ApprovalRow> allReviews = repository.listApprovals(documentId);
        List<ConflictDeclarationRow> conflicts = repository.listConflicts(documentId);
        List<TermRuleRow> termRules = repository.listTermRules(documentId, document.termVersion());

        // 评审只追加：同一评审人同一目标以最大 review_id 行为最新行，先按评审人折叠再判版本与资格。
        Map<String, List<ApprovalRow>> allByKey = new LinkedHashMap<>();
        for (ApprovalRow review : allReviews) {
            allByKey.computeIfAbsent(key(review.segmentId(), review.language()), k -> new ArrayList<>()).add(review);
        }

        List<ApiDtos.TermRuleView> termViolations = new ArrayList<>();
        List<ApiDtos.RejectedReviewView> rejections = new ArrayList<>();
        int missingTotal = 0;
        Map<String, List<String>> missingBySegment = new LinkedHashMap<>();
        Map<String, Integer> missingCountBySegment = new LinkedHashMap<>();

        for (SegmentRow segment : segments) {
            missingBySegment.put(segment.segmentId(), new ArrayList<>());
            missingCountBySegment.put(segment.segmentId(), 0);
            for (String language : document.targetLanguages()) {
                String k = key(segment.segmentId(), language);
                TranslationRow translation = translations.get(k);
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
                List<ApprovalRow> matched = latestByReviewer(allByKey.getOrDefault(k, List.of())).stream()
                        .filter(r -> r.translationVersion() == translation.translationVersion()
                                && r.sourceVersion() == segment.sourceVersion())
                        .sorted(Comparator.comparing(ApprovalRow::reviewer))
                        .toList();

                List<ApprovalRow> effective = matched.stream()
                        .filter(r -> r.active() && effectiveConflicts(conflicts, r.reviewer(), language, now)
                                .isEmpty())
                        .toList();
                for (ApprovalRow review : effective) {
                    if (!review.approved()) {
                        rejections.add(new ApiDtos.RejectedReviewView(
                                segment.segmentId(), language, review.reviewer(), review.translationVersion()));
                    }
                }
                long approvalCount = effective.stream().filter(ApprovalRow::approved).count();
                long missing = Math.max(0, REQUIRED_REVIEW_COUNT - approvalCount);
                if (missing > 0) {
                    missingTotal += (int) missing;
                    missingBySegment.get(segment.segmentId()).add(language);
                    missingCountBySegment.merge(segment.segmentId(), (int) missing, Integer::sum);
                }
                termViolations.addAll(findViolations(
                        segment.sourceText(), language, translation.content(), termRules));
            }
        }

        // 存在有效否决时优先返回可区分的 REVIEW_REJECTED；否则法定人数不足返回缺失数与受影响段落。
        if (!rejections.isEmpty()) {
            throw ApiException.rejectedFailure(
                    "存在 " + rejections.size() + " 条非冲突有效否决，发布被阻断", rejections);
        }
        if (missingTotal > 0) {
            List<ApiDtos.AffectedSegmentView> affected = new ArrayList<>();
            for (SegmentRow segment : segments) {
                List<String> languages = missingBySegment.get(segment.segmentId());
                if (!languages.isEmpty()) {
                    affected.add(new ApiDtos.AffectedSegmentView(segment.segmentId(), languages,
                            REQUIRED_REVIEW_COUNT, missingCountBySegment.get(segment.segmentId())));
                }
            }
            throw ApiException.quorumFailure(
                    "法定人数不足：要求每段落每语种 " + REQUIRED_REVIEW_COUNT + " 个非冲突有效批准，尚缺 "
                            + missingTotal + " 个评审",
                    REQUIRED_REVIEW_COUNT, missingTotal, affected);
        }
        if (!termViolations.isEmpty()) {
            throw ApiException.termViolation("译文违反 " + termViolations.size() + " 条术语规则", termViolations);
        }

        int publishedVersion = document.publishedVersion() + 1;
        repository.insertSnapshot(documentId, publishedVersion,
                buildSnapshotJson(document, publishedVersion, segments, translations, allByKey,
                        conflicts, termRules, now));
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

    /**
     * 评审资格与法定人数诊断（只读）：按当前 UTC 时刻计算评审人在各语种是否命中有效冲突，
     * 以及每段落每语种的有效批准/否决/冲突评审数与缺失数；读取不改变状态。
     */
    @Transactional(readOnly = true)
    public ApiDtos.ReviewDiagnosticsResponse getReviewDiagnostics(long documentId) {
        DocumentRow document = requireDocument(documentId);
        Instant now = clock.now();
        List<SegmentRow> segments = repository.listSegments(documentId);
        Map<String, TranslationRow> translations = repository.listTranslations(documentId).stream()
                .collect(Collectors.toMap(t -> key(t.segmentId(), t.language()), Function.identity()));
        List<ApprovalRow> reviews = repository.listApprovals(documentId);
        List<ConflictDeclarationRow> conflicts = repository.listConflicts(documentId);
        List<ConflictEventRow> events = repository.listConflictEvents(documentId);

        Set<String> reviewerLanguagePairs = new java.util.TreeSet<>();
        for (ConflictDeclarationRow conflict : conflicts) {
            reviewerLanguagePairs.add(conflict.reviewer() + "\n" + conflict.language());
        }
        for (ApprovalRow review : reviews) {
            reviewerLanguagePairs.add(review.reviewer() + "\n" + review.language());
        }
        List<ApiDtos.ReviewerEligibilityView> reviewerViews = new ArrayList<>();
        for (String pair : reviewerLanguagePairs) {
            String[] parts = pair.split("\n", 2);
            String reviewer = parts[0];
            String language = parts[1];
            List<ConflictDeclarationRow> active = effectiveConflicts(conflicts, reviewer, language, now);
            reviewerViews.add(new ApiDtos.ReviewerEligibilityView(reviewer, language, active.isEmpty(),
                    active.stream().map(c -> toConflictView(c, events)).toList()));
        }

        Map<String, List<ApprovalRow>> byKey = reviews.stream()
                .collect(Collectors.groupingBy(r -> key(r.segmentId(), r.language())));
        List<ApiDtos.SegmentReviewSummaryView> summaries = new ArrayList<>();
        for (SegmentRow segment : segments) {
            for (String language : document.targetLanguages()) {
                TranslationRow translation = translations.get(key(segment.segmentId(), language));
                List<ApprovalRow> latestReviews = latestByReviewer(
                        byKey.getOrDefault(key(segment.segmentId(), language), List.of()));
                int conflicted = 0;
                int approvals = 0;
                int rejectionsCount = 0;
                for (ApprovalRow review : latestReviews) {
                    if ("CONFLICTED".equals(review.reviewStatus())) {
                        conflicted++;
                    }
                    boolean versionMatched = translation != null
                            && review.translationVersion() == translation.translationVersion()
                            && review.sourceVersion() == segment.sourceVersion();
                    boolean eligible = review.active()
                            && effectiveConflicts(conflicts, review.reviewer(), language, now).isEmpty();
                    if (versionMatched && eligible) {
                        if (review.approved()) {
                            approvals++;
                        } else {
                            rejectionsCount++;
                        }
                    }
                }
                int missing = Math.max(0, REQUIRED_REVIEW_COUNT - approvals);
                summaries.add(new ApiDtos.SegmentReviewSummaryView(
                        segment.segmentId(), language, approvals, rejectionsCount, conflicted,
                        REQUIRED_REVIEW_COUNT, missing, missing == 0, rejectionsCount > 0));
            }
        }
        return new ApiDtos.ReviewDiagnosticsResponse(documentId, iso(now), REQUIRED_REVIEW_COUNT,
                reviewerViews, summaries);
    }

    /** 冲突明细查询（只读）：可按评审人、语种、状态过滤，每个声明附带按事件 ID 升序的历史。 */
    @Transactional(readOnly = true)
    public List<ApiDtos.ConflictView> listConflicts(long documentId, String reviewer, String language,
                                                    String status) {
        requireDocument(documentId);
        List<ConflictEventRow> events = repository.listConflictEvents(documentId);
        String normalizedLanguage = language == null || language.isBlank() ? null : normalizeLanguage(language);
        String normalizedStatus = status == null || status.isBlank() ? null
                : status.trim().toUpperCase(Locale.ROOT);
        return repository.listConflicts(documentId).stream()
                .filter(c -> reviewer == null || reviewer.isBlank() || c.reviewer().equals(reviewer.trim()))
                .filter(c -> normalizedLanguage == null || c.language().equals(normalizedLanguage))
                .filter(c -> normalizedStatus == null || c.conflictStatus().equals(normalizedStatus))
                .map(c -> toConflictView(c, events))
                .toList();
    }

    /** 单条冲突明细（含历史），不存在 404。 */
    @Transactional(readOnly = true)
    public ApiDtos.ConflictView getConflict(long documentId, long conflictId) {
        requireDocument(documentId);
        ConflictDeclarationRow declaration = repository.findConflict(conflictId)
                .orElseThrow(() -> ApiException.notFound("冲突声明不存在: " + conflictId));
        if (declaration.documentId() != documentId) {
            throw ApiException.notFound("冲突声明不属于该文档: " + conflictId);
        }
        return toConflictView(declaration, repository.listConflictEvents(documentId));
    }

    /** 冲突历史查询（只读）：全文档事件按 eventId 升序，可按冲突声明过滤。 */
    @Transactional(readOnly = true)
    public List<ApiDtos.ConflictEventView> listConflictHistory(long documentId, Long conflictId) {
        requireDocument(documentId);
        return repository.listConflictEvents(documentId).stream()
                .filter(e -> conflictId == null || e.conflictId() == conflictId)
                .map(TranslationService::toEventView)
                .toList();
    }

    /** 评审明细查询（只读）：可按段落、语种、评审人过滤，保留 ACTIVE/CONFLICTED 与版本失效全部证据。 */
    @Transactional(readOnly = true)
    public List<ApiDtos.ReviewView> listReviews(long documentId, String segmentId, String language,
                                                String reviewer) {
        requireDocument(documentId);
        String normalizedLanguage = language == null || language.isBlank() ? null : normalizeLanguage(language);
        return repository.listApprovals(documentId).stream()
                .filter(r -> segmentId == null || segmentId.isBlank() || r.segmentId().equals(segmentId))
                .filter(r -> normalizedLanguage == null || r.language().equals(normalizedLanguage))
                .filter(r -> reviewer == null || reviewer.isBlank() || r.reviewer().equals(reviewer.trim()))
                .map(TranslationService::toReviewView)
                .toList();
    }

    private DocumentRow requireDocument(long documentId) {
        return repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
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

    /** 同一目标下按评审人折叠出最新评审行（最大 review_id），保持评审人名字典序稳定。 */
    private static List<ApprovalRow> latestByReviewer(List<ApprovalRow> reviews) {
        Map<String, ApprovalRow> latest = new LinkedHashMap<>();
        for (ApprovalRow review : reviews.stream()
                .sorted(Comparator.comparingLong(ApprovalRow::reviewId)).toList()) {
            latest.put(review.reviewer(), review);
        }
        return new ArrayList<>(latest.values());
    }

    /** 未解除且在给定 UTC 时刻命中（左闭右开）指定评审人与语种的冲突声明。 */
    private static List<ConflictDeclarationRow> effectiveConflicts(List<ConflictDeclarationRow> all,
                                                                   String reviewer, String language, Instant at) {
        return all.stream()
                .filter(c -> c.active() && c.reviewer().equals(reviewer) && c.language().equals(language)
                        && c.covers(at))
                .sorted(Comparator.comparingLong(ConflictDeclarationRow::conflictId))
                .toList();
    }

    /** 两个左闭右开区间是否重叠（端点相接不算重叠）。 */
    private static boolean overlaps(Instant from1, Instant to1, Instant from2, Instant to2) {
        return from1.isBefore(to2) && from2.isBefore(to1);
    }

    private Instant parseInstant(String value, String errorPrefix) {
        try {
            return truncateMillis(Instant.parse(value));
        } catch (DateTimeParseException e) {
            throw ApiException.unprocessable(errorPrefix + value);
        }
    }

    private static Instant truncateMillis(Instant instant) {
        return Instant.ofEpochMilli(instant.toEpochMilli());
    }

    private static String iso(Instant instant) {
        return DateTimeFormatter.ISO_INSTANT.format(instant);
    }

    private static String key(String segmentId, String language) {
        return segmentId + " " + language;
    }

    private static String normalizeLanguage(String language) {
        return language.trim().toLowerCase(Locale.ROOT);
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

    private static ApiDtos.ConflictEventView toEventView(ConflictEventRow event) {
        return new ApiDtos.ConflictEventView(event.eventId(), event.conflictId(), event.eventType(),
                event.actor(), event.actorRole(), iso(event.validFrom()), iso(event.validTo()),
                event.reason(), iso(event.createdAt()));
    }

    private static ApiDtos.ReviewView toReviewView(ApprovalRow review) {
        return new ApiDtos.ReviewView(review.reviewId(), review.segmentId(), review.language(), review.reviewer(),
                review.decision(), review.decisionReason(), review.reviewStatus(), review.conflictId(),
                review.sourceVersion(), review.translationVersion(), iso(review.reviewedAt()));
    }

    private static ApiDtos.ConflictView toConflictView(ConflictDeclarationRow c, List<ConflictEventRow> events) {
        List<ApiDtos.ConflictEventView> history = events.stream()
                .filter(e -> e.conflictId() == c.conflictId())
                .map(TranslationService::toEventView)
                .toList();
        return new ApiDtos.ConflictView(c.conflictId(), c.documentId(), c.reviewer(), c.language(),
                iso(c.validFrom()), iso(c.validTo()), c.reason(), c.conflictStatus(),
                c.declaredBy(), iso(c.declaredAt()),
                c.resolvedBy(), c.resolvedAt() == null ? null : iso(c.resolvedAt()),
                c.resolveReason(), history);
    }

    /**
     * 生成完整只读快照 JSON：全部段落源文及各语言译文、作者、评审（含决定/否决原因/状态/固化冲突 ID）、
     * 冲突声明（区间、原因与历史）、版本号与固化的术语版本及规则集。发布后不可变。
     */
    private String buildSnapshotJson(DocumentRow document, int publishedVersion, List<SegmentRow> segments,
                                     Map<String, TranslationRow> translations,
                                     Map<String, List<ApprovalRow>> allReviewsByKey,
                                     List<ConflictDeclarationRow> conflicts, List<TermRuleRow> termRules,
                                     Instant publishedAt) {
        List<ConflictEventRow> events = repository.listConflictEvents(document.documentId());
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("documentId", document.documentId());
        snapshot.put("publishedVersion", publishedVersion);
        snapshot.put("draftVersion", document.draftVersion());
        snapshot.put("termVersion", document.termVersion());
        snapshot.put("publishedAt", iso(publishedAt));
        snapshot.put("requiredReviewCount", REQUIRED_REVIEW_COUNT);
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
        List<Map<String, Object>> conflictList = new ArrayList<>();
        for (ConflictDeclarationRow c : conflicts) {
            Map<String, Object> conflictJson = new LinkedHashMap<>();
            conflictJson.put("conflictId", c.conflictId());
            conflictJson.put("reviewer", c.reviewer());
            conflictJson.put("language", c.language());
            conflictJson.put("validFrom", iso(c.validFrom()));
            conflictJson.put("validTo", iso(c.validTo()));
            conflictJson.put("reason", c.reason());
            conflictJson.put("status", c.conflictStatus());
            conflictJson.put("declaredBy", c.declaredBy());
            conflictJson.put("declaredAt", iso(c.declaredAt()));
            conflictJson.put("resolvedBy", c.resolvedBy());
            conflictJson.put("resolvedAt", c.resolvedAt() == null ? null : iso(c.resolvedAt()));
            conflictJson.put("resolveReason", c.resolveReason());
            conflictJson.put("history", events.stream()
                    .filter(e -> e.conflictId() == c.conflictId())
                    .map(e -> {
                        Map<String, Object> eventJson = new LinkedHashMap<>();
                        eventJson.put("eventId", e.eventId());
                        eventJson.put("eventType", e.eventType());
                        eventJson.put("actor", e.actor());
                        eventJson.put("actorRole", e.actorRole());
                        eventJson.put("validFrom", iso(e.validFrom()));
                        eventJson.put("validTo", iso(e.validTo()));
                        eventJson.put("reason", e.reason());
                        eventJson.put("createdAt", iso(e.createdAt()));
                        return eventJson;
                    }).toList());
            conflictList.add(conflictJson);
        }
        snapshot.put("conflicts", conflictList);
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
                Map<String, Object> translationJson = new LinkedHashMap<>();
                translationJson.put("language", language);
                translationJson.put("content", translation.content());
                translationJson.put("author", translation.author());
                translationJson.put("translationVersion", translation.translationVersion());
                translationJson.put("sourceVersion", translation.sourceVersion());
                translationJson.put("termVersion", translation.termVersion());
                List<ApprovalRow> allForKey = allReviewsByKey.getOrDefault(k, List.of());
                List<ApprovalRow> effective = latestByReviewer(allForKey).stream()
                        .filter(r -> r.translationVersion() == translation.translationVersion()
                                && r.sourceVersion() == segment.sourceVersion())
                        .filter(r -> r.active() && effectiveConflicts(conflicts, r.reviewer(), language,
                                publishedAt).isEmpty())
                        .toList();
                List<Map<String, Object>> reviewList = new ArrayList<>();
                for (ApprovalRow review : allForKey) {
                    Map<String, Object> reviewJson = new LinkedHashMap<>();
                    reviewJson.put("reviewId", review.reviewId());
                    reviewJson.put("reviewer", review.reviewer());
                    reviewJson.put("decision", review.decision());
                    reviewJson.put("decisionReason", review.decisionReason());
                    reviewJson.put("reviewStatus", review.reviewStatus());
                    if (review.conflictId() != null) {
                        reviewJson.put("conflictId", review.conflictId());
                    }
                    reviewJson.put("sourceVersion", review.sourceVersion());
                    reviewJson.put("translationVersion", review.translationVersion());
                    reviewJson.put("reviewedAt", iso(review.reviewedAt()));
                    reviewList.add(reviewJson);
                }
                    translationJson.put("reviews", reviewList);
                    // 兼容字段：发布成功时每语种至少一个版本匹配的有效批准，取排序后首位评审人。
                    translationJson.put("reviewer", effective.stream()
                            .filter(ApprovalRow::approved)
                            .map(ApprovalRow::reviewer)
                            .findFirst().orElse(null));
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
}
