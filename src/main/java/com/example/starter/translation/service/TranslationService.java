package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiDtos;
import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.domain.Rows.ApprovalRow;
import com.example.starter.translation.domain.Rows.ConflictDeclarationRow;
import com.example.starter.translation.domain.Rows.DocumentRow;
import com.example.starter.translation.domain.Rows.SegmentRow;
import com.example.starter.translation.domain.Rows.TermRuleRow;
import com.example.starter.translation.domain.Rows.TranslationRow;
import com.example.starter.translation.repo.TranslationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 多语种段落修订与发布快照的核心业务服务。
 * 所有写操作先对文档行加 FOR UPDATE 行锁，保证同一文档的修改、审核、冲突声明与发布串行，
 * 并发下对应一个一致的文档状态；方法均加入调用方事务，与幂等记录原子提交。
 */
@Service
public class TranslationService {

    private final TranslationRepository repository;
    private final ObjectMapper objectMapper;
    private final ReviewConflictEvaluator conflictEvaluator;
    private final TimeProvider timeProvider;

    public TranslationService(TranslationRepository repository, ObjectMapper objectMapper,
                              ReviewConflictEvaluator conflictEvaluator, TimeProvider timeProvider) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.conflictEvaluator = conflictEvaluator;
        this.timeProvider = timeProvider;
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

    /**
     * 译文批准：审核人不得是作者，须同时匹配当前源文与译文版本，且当前不得命中该文档/语种的有效利益冲突。
     * 命中有效冲突（含声明登记后区间生效）返回 403，拒绝提交；提交成功固化决定、版本与 UTC 时间并追加评审历史。
     */
    @Transactional
    public ApiDtos.ReviewResponse approveTranslation(long documentId, String segmentId, String language,
                                                     String actorId, ApiDtos.ApproveTranslationRequest request) {
        return submitReview(documentId, segmentId, language, actorId, request.translationVersion(), "APPROVED");
    }

    /** 译文否决：与批准相同的资格与版本校验，决定记为 REJECTED，否决不计入法定人数。 */
    @Transactional
    public ApiDtos.ReviewResponse rejectTranslation(long documentId, String segmentId, String language,
                                                    String actorId, ApiDtos.RejectTranslationRequest request) {
        return submitReview(documentId, segmentId, language, actorId, request.translationVersion(), "REJECTED");
    }

    private ApiDtos.ReviewResponse submitReview(long documentId, String segmentId, String language,
                                                String actorId, int translationVersion, String decision) {
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
            throw ApiException.unprocessable("审核人不得是该译文作者");
        }
        if (translationVersion != translation.translationVersion()) {
            throw ApiException.unprocessable("评审所针对的译文版本 " + translationVersion
                    + " 与当前译文版本 " + translation.translationVersion() + " 不匹配");
        }
        if (translation.sourceVersion() != segment.sourceVersion()) {
            throw ApiException.unprocessable("译文基于源文版本 " + translation.sourceVersion()
                    + "，当前源文版本 " + segment.sourceVersion() + "，译文待更新，不能评审");
        }
        Instant now = timeProvider.now();
        ConflictDeclarationRow conflict = conflictEvaluator.findEffectiveConflict(
                documentId, actorId, normalizedLanguage, now);
        if (conflict != null) {
            throw ApiException.forbidden("评审人命中有效利益冲突，不得提交评审：声明 " + conflict.declarationId()
                    + "，区间 [" + conflict.validFrom() + ", " + conflict.validTo() + ")，原因："
                    + conflict.reason());
        }
        repository.upsertApproval(documentId, new ApprovalRow(segmentId, normalizedLanguage, actorId,
                segment.sourceVersion(), translationVersion, decision, "VALID", document.termVersion(),
                null, null, now));
        repository.insertReviewHistory(documentId, segmentId, normalizedLanguage, actorId, decision, "VALID",
                segment.sourceVersion(), translationVersion, document.termVersion(), null, actorId, now);
        return new ApiDtos.ReviewResponse(documentId, segmentId, normalizedLanguage, actorId, decision,
                "VALID", translationVersion, segment.sourceVersion(), document.termVersion(), null);
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
     * 发布：校验期望版本（不符 409），再在同一事务、文档行锁内按当前一致状态重算法定人数：
     * 每个段落 × 每个语种要求 1 条“有效批准”——决定 APPROVED、评审资格 VALID、未命中任何当前有效冲突，
     * 且评审版本等于当前源文/译文版本。缺译、译文过期（源文/术语）返回 422（UNPROCESSABLE），
     * 违反术语规则返回 422（TERM_VIOLATION，全部违规术语），
     * 法定人数不足返回 422（QUORUM_NOT_MET，稳定返回缺失评审总数与受影响段落语种明细，含实际值/要求值/差额），
     * 不发布任何部分语种；全部通过后原子生成完整只读快照（固化术语版本、规则集、冲突声明版本区间原因
     * 与每条评审的资格证据）并递增发布版本。
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
        Instant now = timeProvider.now();
        List<SegmentRow> segments = repository.listSegments(documentId);
        Map<String, TranslationRow> translations = repository.listTranslations(documentId).stream()
                .collect(Collectors.toMap(t -> key(t.segmentId(), t.language()), Function.identity()));
        Map<String, ApprovalRow> approvals = repository.listApprovals(documentId).stream()
                .collect(Collectors.toMap(a -> key(a.segmentId(), a.language()), Function.identity()));
        List<TermRuleRow> termRules = repository.listTermRules(documentId, document.termVersion());
        List<ConflictDeclarationRow> conflicts = repository.listConflictDeclarations(documentId);
        List<ApiDtos.TermRuleView> termViolations = new ArrayList<>();
        List<ApiDtos.QuorumDeficitView> deficits = new ArrayList<>();

        // 段落与语种均按稳定顺序遍历，保证失败明细排序稳定。
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
                termViolations.addAll(findViolations(
                        segment.sourceText(), language, translation.content(), termRules));

                ApprovalRow approval = approvals.get(key(segment.segmentId(), language));
                String deficitReason = quorumDeficitReason(documentId, segment, language, translation,
                        approval, conflicts, now);
                if (deficitReason != null) {
                    // 每个段落语种要求 1 条有效批准；不满足时实际有效批准数为 0、差额为 1。
                    deficits.add(new ApiDtos.QuorumDeficitView(segment.segmentId(), language, 1, 0, 1,
                            deficitReason));
                }
            }
        }
        if (!termViolations.isEmpty()) {
            throw ApiException.termViolation("译文违反 " + termViolations.size() + " 条术语规则", termViolations);
        }
        if (!deficits.isEmpty()) {
            int missing = deficits.stream().mapToInt(ApiDtos.QuorumDeficitView::missingApprovals).sum();
            throw ApiException.quorumDeficit(
                    "评审法定人数不足：要求 " + segments.size() * document.targetLanguages().size()
                            + " 条有效批准，缺失 " + missing + " 条，受影响段落语种 " + deficits.size() + " 个",
                    List.copyOf(deficits), missing);
        }
        int publishedVersion = document.publishedVersion() + 1;
        repository.insertSnapshot(documentId, publishedVersion,
                buildSnapshotJson(document, publishedVersion, segments, translations, approvals, termRules,
                        conflicts, now));
        repository.updatePublishedVersion(documentId, publishedVersion);
        return new ApiDtos.PublishResponse(documentId, publishedVersion);
    }

    /**
     * 计算某段落语种在发布时刻的法定人数不足原因；返回 null 表示存在一条有效批准、计入法定人数。
     * 有效批准须：决定 APPROVED、资格 VALID、未命中当前有效冲突、评审版本等于当前源文/译文版本。
     */
    private String quorumDeficitReason(long documentId, SegmentRow segment, String language,
                                       TranslationRow translation, ApprovalRow approval,
                                       List<ConflictDeclarationRow> conflicts, Instant at) {
        if (approval == null) {
            return "缺少评审: " + segment.segmentId() + "/" + language;
        }
        if ("REJECTED".equals(approval.decision())) {
            return "当前评审为否决（REJECTED），不计入法定人数: " + segment.segmentId() + "/" + language;
        }
        if (approval.translationVersion() != translation.translationVersion()
                || approval.sourceVersion() != segment.sourceVersion()) {
            return "评审已失效：源文或译文版本已改变: " + segment.segmentId() + "/" + language;
        }
        ConflictDeclarationRow effective = conflicts.stream()
                .filter(declaration -> "ACTIVE".equals(declaration.status()))
                .filter(declaration -> declaration.reviewer().equals(approval.reviewer()))
                .filter(declaration -> declaration.language().equals(language))
                .filter(declaration -> ReviewConflictEvaluator.contains(declaration, at))
                .findFirst().orElse(null);
        if (effective != null) {
            return "评审人命中有效利益冲突，已排除出法定人数：声明 " + effective.declarationId()
                    + "，评审人 " + approval.reviewer();
        }
        return null;
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

    private static String key(String segmentId, String language) {
        return segmentId + " " + language;
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

    /**
     * 生成完整只读快照 JSON：全部段落源文及各语言译文、作者、评审人、决定与资格、版本号，
     * 固化术语版本及规则集、发布评估 UTC 时刻，以及当时全部冲突声明（含区间、原因、状态与解除证据）。
     * 快照只增不改：后续解除冲突或重新评审不追溯改写历史快照。
     */
    private String buildSnapshotJson(DocumentRow document, int publishedVersion, List<SegmentRow> segments,
                                     Map<String, TranslationRow> translations,
                                     Map<String, ApprovalRow> approvals, List<TermRuleRow> termRules,
                                     List<ConflictDeclarationRow> conflicts, Instant evaluatedAt) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("documentId", document.documentId());
        snapshot.put("publishedVersion", publishedVersion);
        snapshot.put("draftVersion", document.draftVersion());
        snapshot.put("termVersion", document.termVersion());
        snapshot.put("evaluatedAt", evaluatedAt.toString());
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
        // 固化发布时全部冲突声明：有效冲突决定评审排除；声明 ID/区间/原因/状态/解除证据均为当时业务证据。
        List<Map<String, Object>> conflictList = new ArrayList<>();
        for (ConflictDeclarationRow conflict : conflicts) {
            Map<String, Object> conflictJson = new LinkedHashMap<>();
            conflictJson.put("declarationId", conflict.declarationId());
            conflictJson.put("reviewer", conflict.reviewer());
            conflictJson.put("language", conflict.language());
            conflictJson.put("validFrom", conflict.validFrom().toString());
            conflictJson.put("validTo", conflict.validTo().toString());
            conflictJson.put("reason", conflict.reason());
            conflictJson.put("status", conflict.status());
            conflictJson.put("declaredBy", conflict.declaredBy());
            conflictJson.put("declaredAt", conflict.declaredAt().toString());
            conflictJson.put("releasedAt", conflict.releasedAt() == null ? null : conflict.releasedAt().toString());
            conflictJson.put("releasedBy", conflict.releasedBy());
            boolean effective = "ACTIVE".equals(conflict.status())
                    && ReviewConflictEvaluator.contains(conflict, evaluatedAt);
            conflictJson.put("effectiveAtPublish", effective);
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
                TranslationRow translation = translations.get(key(segment.segmentId(), language));
                ApprovalRow approval = approvals.get(key(segment.segmentId(), language));
                Map<String, Object> translationJson = new LinkedHashMap<>();
                translationJson.put("language", language);
                translationJson.put("content", translation.content());
                translationJson.put("author", translation.author());
                translationJson.put("translationVersion", translation.translationVersion());
                translationJson.put("sourceVersion", translation.sourceVersion());
                translationJson.put("termVersion", translation.termVersion());
                translationJson.put("reviewer", approval.reviewer());
                translationJson.put("decision", approval.decision());
                translationJson.put("reviewStatus", approval.reviewStatus());
                translationJson.put("reviewSourceVersion", approval.sourceVersion());
                translationJson.put("reviewTranslationVersion", approval.translationVersion());
                translationJson.put("reviewTermVersion", approval.termVersion());
                translationJson.put("conflictDeclarationId", approval.conflictDeclarationId());
                // 发布时刻按有效冲突重新计算的排除结果（区别于登记时留存的 reviewStatus 标记）：
                // 成功发布的快照中该值必为 null，表示该评审计入了本次法定人数。
                ConflictDeclarationRow effectiveAtPublish = conflicts.stream()
                        .filter(declaration -> "ACTIVE".equals(declaration.status()))
                        .filter(declaration -> declaration.reviewer().equals(approval.reviewer()))
                        .filter(declaration -> declaration.language().equals(language))
                        .filter(declaration -> ReviewConflictEvaluator.contains(declaration, evaluatedAt))
                        .findFirst().orElse(null);
                translationJson.put("effectiveConflictDeclarationId",
                        effectiveAtPublish == null ? null : effectiveAtPublish.declarationId());
                translationJson.put("reviewedAt",
                        approval.reviewedAt() == null ? null : approval.reviewedAt().toString());
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
