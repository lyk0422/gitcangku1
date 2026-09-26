package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiDtos;
import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.domain.Rows.ApprovalRow;
import com.example.starter.translation.domain.Rows.CitationAnchorRow;
import com.example.starter.translation.domain.Rows.DocumentRow;
import com.example.starter.translation.domain.Rows.SegmentRow;
import com.example.starter.translation.domain.Rows.TermRuleRow;
import com.example.starter.translation.domain.Rows.TranslationRow;
import com.example.starter.translation.repo.TranslationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
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
 * 所有写操作先对文档行加 FOR UPDATE 行锁，保证同一文档的修改、审核与发布串行，
 * 并发下对应一个一致的文档状态；方法均加入调用方事务，与幂等记录原子提交。
 */
@Service
public class TranslationService {

    private final TranslationRepository repository;
    private final ObjectMapper objectMapper;
    private final CitationLockService citationLockService;
    private final Clock clock;

    public TranslationService(TranslationRepository repository, ObjectMapper objectMapper,
                              CitationLockService citationLockService, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.citationLockService = citationLockService;
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
     * 段落存在生效引文锚点时必须保留全部锚点引用文本，区间变动须提供一一映射（缺失/重复/文本不一致 422）；
     * 译文版本递增，锚点区间在同一事务内迁移，草稿版本加一。
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
        // 先校验锚点映射的完整最终状态（任何问题抛 422，不写译文、不迁锚点）。
        List<CitationLockService.AnchorMigration> migrationPlan = citationLockService.planRevision(
                documentId, segmentId, normalizedLanguage, request.content(), request.anchorMappings());
        int translationVersion = repository.findTranslation(documentId, segmentId, normalizedLanguage)
                .map(TranslationRow::translationVersion).orElse(0) + 1;
        repository.upsertTranslation(documentId, new TranslationRow(segmentId, normalizedLanguage,
                request.content(), actorId, segment.sourceVersion(), translationVersion, document.termVersion()));
        citationLockService.applyMigrations(documentId, migrationPlan, actorId, translationVersion,
                clock.instant());
        int draftVersion = bumpDraftVersion(document);
        return new ApiDtos.TranslationResponse(documentId, segmentId, normalizedLanguage,
                translationVersion, segment.sourceVersion(), document.termVersion(), draftVersion,
                (int) migrationPlan.stream().filter(m ->
                        m.newStart() != m.anchor().rangeStart() || m.newEnd() != m.anchor().rangeEnd()).count());
    }

    /**
     * 批量译文修订：先校验所有段落的源文版本、语言、术语规则与引文锚点映射（收集全部问题），
     * 任一失败整次 422 回滚，既有译文、锚点与已发布快照不变；全部通过后在同一事务提交全部段落，
     * 成功段数、每段译文版本与实际迁移锚点数均在响应中给出。
     */
    @Transactional
    public ApiDtos.BatchSubmitTranslationsResponse submitTranslationsBatch(
            long documentId, String actorId, ApiDtos.BatchSubmitTranslationsRequest request) {
        DocumentRow document = lockDocument(documentId);
        List<ApiDtos.BatchTranslationItem> items = request.items();
        Set<List<String>> seen = new HashSet<>();
        List<ApiDtos.IssueView> issues = new ArrayList<>();
        List<TermRuleRow> termRules = repository.listTermRules(documentId, document.termVersion());

        record PlannedItem(ApiDtos.BatchTranslationItem item, SegmentRow segment, String language,
                          int translationVersion, List<CitationLockService.AnchorMigration> plan) {
        }
        List<PlannedItem> planned = new ArrayList<>();

        for (ApiDtos.BatchTranslationItem rawItem : items) {
            String normalizedLanguage = normalizeLanguage(rawItem.language());
            if (!seen.add(List.of(rawItem.segmentId(), normalizedLanguage))) {
                issues.add(new ApiDtos.IssueView("BATCH_ITEM_DUPLICATE", rawItem.segmentId(),
                        normalizedLanguage, null, "批量请求内段落+语言重复: "
                        + rawItem.segmentId() + "/" + normalizedLanguage));
                continue;
            }
            if (!document.targetLanguages().contains(normalizedLanguage)) {
                issues.add(new ApiDtos.IssueView("LANGUAGE_NOT_SUPPORTED", rawItem.segmentId(),
                        normalizedLanguage, null,
                        "语言不在文档目标语言中: " + normalizedLanguage));
                continue;
            }
            SegmentRow segment = repository.findSegment(documentId, rawItem.segmentId()).orElse(null);
            if (segment == null) {
                issues.add(new ApiDtos.IssueView("SEGMENT_NOT_FOUND", rawItem.segmentId(),
                        normalizedLanguage, null, "段落不存在: " + rawItem.segmentId()));
                continue;
            }
            if (rawItem.sourceVersion() != segment.sourceVersion()) {
                issues.add(new ApiDtos.IssueView("SOURCE_VERSION_MISMATCH", rawItem.segmentId(),
                        normalizedLanguage, null, "译文所依据的源文版本 " + rawItem.sourceVersion()
                        + " 与当前源文版本 " + segment.sourceVersion() + " 不匹配（要求值 "
                        + segment.sourceVersion() + "，差额 "
                        + (segment.sourceVersion() - rawItem.sourceVersion()) + "）"));
            }
            List<ApiDtos.TermRuleView> itemViolations = findViolations(
                    segment.sourceText(), normalizedLanguage, rawItem.content(), termRules);
            for (ApiDtos.TermRuleView violation : itemViolations) {
                issues.add(new ApiDtos.IssueView("TERM_VIOLATION", rawItem.segmentId(),
                        normalizedLanguage, null, "术语 '" + violation.sourceTerm() + "' 要求译文包含 '"
                        + violation.requiredTranslation() + "'，实际译文缺失"));
            }
            List<CitationLockService.AnchorMigration> plan;
            try {
                plan = citationLockService.planRevision(documentId, rawItem.segmentId(), normalizedLanguage,
                        rawItem.content(), rawItem.anchorMappings());
            } catch (ApiException e) {
                if (e.issues() != null) {
                    issues.addAll(e.issues());
                } else {
                    issues.add(new ApiDtos.IssueView("ANCHOR_VALIDATION_FAILED", rawItem.segmentId(),
                            normalizedLanguage, null, e.getMessage()));
                }
                plan = List.of();
            }
            int translationVersion = repository.findTranslation(
                    documentId, rawItem.segmentId(), normalizedLanguage)
                    .map(TranslationRow::translationVersion).orElse(0) + 1;
            planned.add(new PlannedItem(rawItem, segment, normalizedLanguage, translationVersion, plan));
        }
        if (!issues.isEmpty()) {
            throw ApiException.validationIssues(
                    "批量修订校验失败，共 " + issues.size() + " 项问题，整次回滚", issues);
        }

        Instant now = clock.instant();
        List<ApiDtos.BatchTranslationItemResult> results = new ArrayList<>();
        for (PlannedItem plannedItem : planned) {
            ApiDtos.BatchTranslationItem item = plannedItem.item();
            repository.upsertTranslation(documentId, new TranslationRow(item.segmentId(),
                    plannedItem.language(), item.content(), actorId, plannedItem.segment().sourceVersion(),
                    plannedItem.translationVersion(), document.termVersion()));
            citationLockService.applyMigrations(documentId, plannedItem.plan(), actorId,
                    plannedItem.translationVersion(), now);
            int migrated = (int) plannedItem.plan().stream().filter(m ->
                    m.newStart() != m.anchor().rangeStart() || m.newEnd() != m.anchor().rangeEnd()).count();
            results.add(new ApiDtos.BatchTranslationItemResult(item.segmentId(), plannedItem.language(),
                    plannedItem.translationVersion(), plannedItem.segment().sourceVersion(), migrated));
        }
        bumpDraftVersion(document);
        return new ApiDtos.BatchSubmitTranslationsResponse(documentId, results.size(), results);
    }

    /** 译文批准：审核人不得是作者，且必须同时匹配当前源文与译文版本。 */
    @Transactional
    public ApiDtos.ApprovalResponse approveTranslation(long documentId, String segmentId, String language,
                                                       String actorId, ApiDtos.ApproveTranslationRequest request) {
        lockDocument(documentId);
        String normalizedLanguage = normalizeLanguage(language);
        SegmentRow segment = findSegmentOrThrow(documentId, segmentId);
        TranslationRow translation = repository.findTranslation(documentId, segmentId, normalizedLanguage)
                .orElseThrow(() -> ApiException.notFound(
                        "译文不存在: " + segmentId + "/" + normalizedLanguage));
        if (translation.author().equals(actorId)) {
            throw ApiException.unprocessable("审核人不得是该译文作者");
        }
        if (translation.translationVersion() != request.translationVersion()) {
            throw ApiException.unprocessable("批准所针对的译文版本 " + request.translationVersion()
                    + " 与当前译文版本 " + translation.translationVersion() + " 不匹配");
        }
        if (translation.sourceVersion() != segment.sourceVersion()) {
            throw ApiException.unprocessable("译文基于源文版本 " + translation.sourceVersion()
                    + "，当前源文版本 " + segment.sourceVersion() + "，译文待更新，不能批准");
        }
        repository.upsertApproval(documentId, new ApprovalRow(segmentId, normalizedLanguage, actorId,
                segment.sourceVersion(), translation.translationVersion()));
        return new ApiDtos.ApprovalResponse(documentId, segmentId, normalizedLanguage, actorId,
                translation.translationVersion(), segment.sourceVersion());
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
     * 发布：校验期望版本（不符 409），再校验全部段落在全部目标语言均有有效批准（缺译或审核失效 422）、
     * 译文绑定当前术语版本（过期 422）且满足当前术语规则（违规 422 并返回全部违规术语），
     * 全部通过后原子生成完整只读快照（固化术语版本与实际规则集）并递增发布版本；
     * 任何失败回滚，不产生部分快照。
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
        List<SegmentRow> segments = repository.listSegments(documentId);
        Map<String, TranslationRow> translations = repository.listTranslations(documentId).stream()
                .collect(Collectors.toMap(t -> key(t.segmentId(), t.language()), Function.identity()));
        Map<String, ApprovalRow> approvals = repository.listApprovals(documentId).stream()
                .collect(Collectors.toMap(a -> key(a.segmentId(), a.language()), Function.identity()));
        List<TermRuleRow> termRules = repository.listTermRules(documentId, document.termVersion());
        List<ApiDtos.TermRuleView> termViolations = new ArrayList<>();
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
                ApprovalRow approval = approvals.get(key(segment.segmentId(), language));
                if (approval == null) {
                    throw ApiException.unprocessable(
                            "缺少批准: " + segment.segmentId() + "/" + language);
                }
                if (approval.translationVersion() != translation.translationVersion()
                        || approval.sourceVersion() != segment.sourceVersion()) {
                    throw ApiException.unprocessable("批准已失效: " + segment.segmentId() + "/" + language
                            + "，源文或译文版本已改变");
                }
                termViolations.addAll(findViolations(
                        segment.sourceText(), language, translation.content(), termRules));
            }
        }
        if (!termViolations.isEmpty()) {
            throw ApiException.termViolation("译文违反 " + termViolations.size() + " 条术语规则", termViolations);
        }
        // 发布读取一致的锚点集合：快照固化当时全部锚点（含已解除），之后新增/解除/迁移不改写旧快照。
        List<CitationAnchorRow> anchorsAtPublish = repository.listAllAnchors(documentId);
        int publishedVersion = document.publishedVersion() + 1;
        repository.insertSnapshot(documentId, publishedVersion,
                buildSnapshotJson(document, publishedVersion, segments, translations, approvals, termRules,
                        anchorsAtPublish));
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
     * 生成完整只读快照 JSON：全部段落源文及各语言译文、作者、审核人、版本号、固化的术语版本及规则集，
     * 以及发布时各译文的引文锚点集合（含已解除锚点，字段与 null 语义与明细查询一致）。
     * 快照生成后锚点的新增、解除或迁移不再改写本快照。
     */
    private String buildSnapshotJson(DocumentRow document, int publishedVersion, List<SegmentRow> segments,
                                     Map<String, TranslationRow> translations,
                                     Map<String, ApprovalRow> approvals, List<TermRuleRow> termRules,
                                     List<CitationAnchorRow> anchors) {
        Map<String, List<CitationAnchorRow>> anchorsByTranslation = anchors.stream()
                .collect(Collectors.groupingBy(a -> key(a.segmentId(), a.language())));
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("documentId", document.documentId());
        snapshot.put("publishedVersion", publishedVersion);
        snapshot.put("draftVersion", document.draftVersion());
        snapshot.put("termVersion", document.termVersion());
        snapshot.put("targetLanguages", document.targetLanguages());
        snapshot.put("generatedAt", CitationLockService.formatUtc(clock.instant()));
        List<Map<String, Object>> termList = new ArrayList<>();
        for (TermRuleRow rule : termRules) {
            Map<String, Object> termJson = new LinkedHashMap<>();
            termJson.put("sourceTerm", rule.sourceTerm());
            termJson.put("language", rule.language());
            termJson.put("requiredTranslation", rule.requiredTranslation());
            termList.add(termJson);
        }
        snapshot.put("terms", termList);
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
                List<Map<String, Object>> anchorList = new ArrayList<>();
                for (CitationAnchorRow anchor : anchorsByTranslation.getOrDefault(
                        key(segment.segmentId(), language), List.of())) {
                    anchorList.add(anchorSnapshotJson(anchor));
                }
                translationJson.put("citationAnchors", anchorList);
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

    /** 快照内锚点 JSON：固化当时状态、区间、文本、责任人与 UTC 时间，released* 字段保留 null 语义。 */
    private Map<String, Object> anchorSnapshotJson(CitationAnchorRow anchor) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("anchorId", anchor.anchorId());
        json.put("citationKey", anchor.citationKey());
        json.put("rangeStart", anchor.rangeStart());
        json.put("rangeEnd", anchor.rangeEnd());
        json.put("anchorText", anchor.anchorText());
        json.put("lockReason", anchor.lockReason());
        json.put("createdBy", anchor.createdBy());
        json.put("translationVersion", anchor.translationVersion());
        json.put("sourceVersion", anchor.sourceVersion());
        json.put("status", anchor.status());
        json.put("releasedBy", anchor.releasedBy());
        json.put("releaseReason", anchor.releaseReason());
        json.put("createdAt", CitationLockService.formatUtc(anchor.createdAt()));
        json.put("releasedAt", anchor.releasedAt() == null ? null
                : CitationLockService.formatUtc(anchor.releasedAt()));
        return json;
    }
}
