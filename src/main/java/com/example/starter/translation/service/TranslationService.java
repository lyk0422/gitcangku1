package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiDtos;
import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.domain.Rows.ApprovalRow;
import com.example.starter.translation.domain.Rows.DocumentRow;
import com.example.starter.translation.domain.Rows.PluralApprovalRow;
import com.example.starter.translation.domain.Rows.PluralTranslationRow;
import com.example.starter.translation.domain.Rows.SegmentRow;
import com.example.starter.translation.domain.Rows.TermRuleRow;
import com.example.starter.translation.domain.Rows.TranslationRow;
import com.example.starter.translation.repo.TranslationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 多语种段落修订与发布快照的核心业务服务。
 * 所有写操作先对文档行加 FOR UPDATE 行锁，保证同一文档的修改、审核与发布串行，
 * 并发下对应一个一致的文档状态；方法均加入调用方事务，与幂等记录原子提交。
 */
@Service
public class TranslationService {

    private final TranslationRepository repository;
    private final ObjectMapper objectMapper;

    public TranslationService(TranslationRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
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
            repository.insertSegment(documentId, segment.segmentId(), segment.sourceText(),
                    segmentTypeOf(segment.plural()));
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
        repository.insertSegment(documentId, request.segmentId(), request.sourceText(),
                segmentTypeOf(request.plural()));
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
        if (segment.isPlural()) {
            throw ApiException.unprocessable("段落为复数类型，请按复数类别提交译文: " + segmentId);
        }
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

    /** 译文批准：审核人不得是作者，且必须同时匹配当前源文与译文版本。 */
    @Transactional
    public ApiDtos.ApprovalResponse approveTranslation(long documentId, String segmentId, String language,
                                                       String actorId, ApiDtos.ApproveTranslationRequest request) {
        lockDocument(documentId);
        String normalizedLanguage = normalizeLanguage(language);
        SegmentRow segment = findSegmentOrThrow(documentId, segmentId);
        if (segment.isPlural()) {
            throw ApiException.unprocessable("段落为复数类型，请按复数类别批准: " + segmentId);
        }
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
     * 复数类别译文提交：段落须为复数类型，语言限 zh/en 且在文档目标语言中；
     * 所依据源文版本必须等于当前源文版本，绑定当前术语版本并校验术语规则（违规 422 返回全部违规术语）。
     * 一次提交 1~n 个类别，各类别独立 upsert 文本、作者与递增译文版本，不影响未提交的类别；
     * 同一请求内类别重复 422。草稿版本加一。
     */
    @Transactional
    public ApiDtos.PluralSubmitResponse submitPluralTranslations(long documentId, String segmentId,
                                                                 String language, String actorId,
                                                                 ApiDtos.SubmitPluralTranslationsRequest request) {
        DocumentRow document = lockDocument(documentId);
        String normalizedLanguage = normalizeLanguage(language);
        validatePluralLanguage(document, normalizedLanguage);
        SegmentRow segment = findSegmentOrThrow(documentId, segmentId);
        if (!segment.isPlural()) {
            throw ApiException.unprocessable("段落不是复数类型，不能按类别提交译文: " + segmentId);
        }
        if (request.sourceVersion() != segment.sourceVersion()) {
            throw ApiException.unprocessable("译文所依据的源文版本 " + request.sourceVersion()
                    + " 与当前源文版本 " + segment.sourceVersion() + " 不匹配");
        }
        Set<String> seen = new HashSet<>();
        for (ApiDtos.PluralCategoryInput input : request.categories()) {
            String category = normalizeCategory(input.category());
            if (!seen.add(category)) {
                throw ApiException.unprocessable("同一请求内复数类别重复: " + category);
            }
        }
        List<TermRuleRow> termRules = repository.listTermRules(documentId, document.termVersion());
        List<ApiDtos.TermRuleView> violations = new ArrayList<>();
        for (ApiDtos.PluralCategoryInput input : request.categories()) {
            violations.addAll(findViolations(segment.sourceText(), normalizedLanguage,
                    input.content(), termRules));
        }
        if (!violations.isEmpty()) {
            throw ApiException.termViolation("译文违反 " + violations.size() + " 条术语规则", violations);
        }
        List<ApiDtos.PluralCategoryVersionView> submitted = new ArrayList<>();
        for (ApiDtos.PluralCategoryInput input : request.categories()) {
            String category = normalizeCategory(input.category());
            int translationVersion = repository
                    .findPluralTranslation(documentId, segmentId, normalizedLanguage, category)
                    .map(PluralTranslationRow::translationVersion).orElse(0) + 1;
            repository.upsertPluralTranslation(documentId, new PluralTranslationRow(segmentId,
                    normalizedLanguage, category, input.content(), actorId, segment.sourceVersion(),
                    translationVersion, document.termVersion()));
            submitted.add(new ApiDtos.PluralCategoryVersionView(category, translationVersion));
        }
        int draftVersion = bumpDraftVersion(document);
        return new ApiDtos.PluralSubmitResponse(documentId, segmentId, normalizedLanguage,
                segment.sourceVersion(), document.termVersion(), draftVersion, submitted);
    }

    /**
     * 复数类别批准：审核人不得是该类别译文作者，且必须同时匹配当前源文版本与该类别当前译文版本；
     * 批准按类别独立记录，发布时要求完整类别集合全部有效批准。
     */
    @Transactional
    public ApiDtos.PluralApprovalResponse approvePluralCategory(long documentId, String segmentId,
                                                                String language, String actorId,
                                                                ApiDtos.ApprovePluralCategoryRequest request) {
        lockDocument(documentId);
        String normalizedLanguage = normalizeLanguage(language);
        SegmentRow segment = findSegmentOrThrow(documentId, segmentId);
        if (!segment.isPlural()) {
            throw ApiException.unprocessable("段落不是复数类型，不能按类别批准: " + segmentId);
        }
        String category = normalizeCategory(request.category());
        PluralTranslationRow translation = repository
                .findPluralTranslation(documentId, segmentId, normalizedLanguage, category)
                .orElseThrow(() -> ApiException.notFound(
                        "复数类别译文不存在: " + segmentId + "/" + normalizedLanguage + "/" + category));
        if (translation.author().equals(actorId)) {
            throw ApiException.unprocessable("审核人不得是该类别译文作者");
        }
        if (translation.translationVersion() != request.translationVersion()) {
            throw ApiException.unprocessable("批准所针对的译文版本 " + request.translationVersion()
                    + " 与该类别当前译文版本 " + translation.translationVersion() + " 不匹配");
        }
        if (translation.sourceVersion() != segment.sourceVersion()) {
            throw ApiException.unprocessable("译文基于源文版本 " + translation.sourceVersion()
                    + "，当前源文版本 " + segment.sourceVersion() + "，译文待更新，不能批准");
        }
        repository.upsertPluralApproval(documentId, new PluralApprovalRow(segmentId, normalizedLanguage,
                category, actorId, segment.sourceVersion(), translation.translationVersion()));
        return new ApiDtos.PluralApprovalResponse(documentId, segmentId, normalizedLanguage, category,
                actorId, translation.translationVersion(), segment.sourceVersion());
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
        Map<List<String>, Map<String, PluralTranslationRow>> pluralTranslations = new HashMap<>();
        for (PluralTranslationRow row : repository.listAllPluralTranslations(documentId)) {
            pluralTranslations.computeIfAbsent(List.of(row.segmentId(), row.language()), k -> new HashMap<>())
                    .put(row.category(), row);
        }
        Map<List<String>, PluralApprovalRow> pluralApprovals = new HashMap<>();
        for (PluralApprovalRow row : repository.listAllPluralApprovals(documentId)) {
            pluralApprovals.put(List.of(row.segmentId(), row.language(), row.category()), row);
        }
        List<TermRuleRow> termRules = repository.listTermRules(documentId, document.termVersion());
        List<ApiDtos.TermRuleView> termViolations = new ArrayList<>();
        for (SegmentRow segment : segments) {
            for (String language : document.targetLanguages()) {
                if (segment.isPlural()) {
                    validatePluralForPublish(document, segment, language, pluralTranslations,
                            pluralApprovals, termRules, termViolations);
                    continue;
                }
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
        int publishedVersion = document.publishedVersion() + 1;
        repository.insertSnapshot(documentId, publishedVersion,
                buildSnapshotJson(document, publishedVersion, segments, translations, approvals,
                        pluralTranslations, pluralApprovals, termRules));
        repository.updatePublishedVersion(documentId, publishedVersion);
        return new ApiDtos.PublishResponse(documentId, publishedVersion);
    }

    /**
     * 复数段落发布校验：类别集合必须与语言要求的集合完全一致（缺类别或额外类别 422），
     * 每个类别译文基于当前源文版本、绑定当前术语版本、有匹配当前版本的有效批准，
     * 且各类别正文满足当前术语规则（违规汇总后统一 422）。
     */
    private static void validatePluralForPublish(DocumentRow document, SegmentRow segment, String language,
                                                 Map<List<String>, Map<String, PluralTranslationRow>> translations,
                                                 Map<List<String>, PluralApprovalRow> approvals,
                                                 List<TermRuleRow> termRules,
                                                 List<ApiDtos.TermRuleView> termViolations) {
        String where = segment.segmentId() + "/" + language;
        Set<String> required = REQUIRED_CATEGORIES.get(language);
        if (required == null) {
            throw ApiException.unprocessable("复数段落 " + segment.segmentId()
                    + " 不支持目标语言（仅 zh 与 en）: " + language);
        }
        Map<String, PluralTranslationRow> byCategory = translations.getOrDefault(
                List.of(segment.segmentId(), language), Map.of());
        for (String category : byCategory.keySet()) {
            if (!required.contains(category)) {
                throw ApiException.unprocessable("额外复数类别: " + where + "/" + category);
            }
        }
        for (String category : required) {
            PluralTranslationRow translation = byCategory.get(category);
            if (translation == null) {
                throw ApiException.unprocessable("缺少复数类别译文: " + where + "/" + category);
            }
            if (translation.sourceVersion() != segment.sourceVersion()) {
                throw ApiException.unprocessable("复数类别译文待更新: " + where + "/" + category
                        + " 基于源文版本 " + translation.sourceVersion()
                        + "，当前源文版本 " + segment.sourceVersion());
            }
            if (translation.termVersion() != document.termVersion()) {
                throw ApiException.unprocessable("复数类别译文术语版本过期: " + where + "/" + category
                        + " 绑定术语版本 " + translation.termVersion()
                        + "，当前术语版本 " + document.termVersion());
            }
            PluralApprovalRow approval = approvals.get(
                    List.of(segment.segmentId(), language, category));
            if (approval == null) {
                throw ApiException.unprocessable("缺少复数类别批准: " + where + "/" + category);
            }
            if (approval.translationVersion() != translation.translationVersion()
                    || approval.sourceVersion() != segment.sourceVersion()) {
                throw ApiException.unprocessable("复数类别批准已失效: " + where + "/" + category
                        + "，源文或译文版本已改变");
            }
            termViolations.addAll(findViolations(segment.sourceText(), language,
                    translation.content(), termRules));
        }
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

    /** 复数流程允许的语言及其要求的类别集合：zh 要求 other，en 要求 one 与 other。 */
    private static final Map<String, Set<String>> REQUIRED_CATEGORIES = Map.of(
            "zh", Set.of("other"),
            "en", Set.of("one", "other"));

    private static String segmentTypeOf(Boolean plural) {
        return Boolean.TRUE.equals(plural) ? "plural" : "plain";
    }

    private static String normalizeCategory(String category) {
        return category.trim().toLowerCase(Locale.ROOT);
    }

    /** 复数流程语言校验：限 zh/en 且须在文档目标语言中。 */
    private static void validatePluralLanguage(DocumentRow document, String language) {
        if (!REQUIRED_CATEGORIES.containsKey(language)) {
            throw ApiException.unprocessable("复数译文仅支持 zh 与 en，不支持语言: " + language);
        }
        if (!document.targetLanguages().contains(language)) {
            throw ApiException.unprocessable("语言不在文档目标语言中: " + language);
        }
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

    /** 生成完整只读快照 JSON：全部段落源文及各语言译文（复数段落按类别冻结，类别按名称排序）、
     * 作者、审核人、版本号与固化的术语版本及规则集。 */
    private String buildSnapshotJson(DocumentRow document, int publishedVersion, List<SegmentRow> segments,
                                     Map<String, TranslationRow> translations,
                                     Map<String, ApprovalRow> approvals,
                                     Map<List<String>, Map<String, PluralTranslationRow>> pluralTranslations,
                                     Map<List<String>, PluralApprovalRow> pluralApprovals,
                                     List<TermRuleRow> termRules) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("documentId", document.documentId());
        snapshot.put("publishedVersion", publishedVersion);
        snapshot.put("draftVersion", document.draftVersion());
        snapshot.put("termVersion", document.termVersion());
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
        List<Map<String, Object>> segmentList = new ArrayList<>();
        for (SegmentRow segment : segments) {
            Map<String, Object> segmentJson = new LinkedHashMap<>();
            segmentJson.put("segmentId", segment.segmentId());
            segmentJson.put("segmentType", segment.segmentType());
            segmentJson.put("sourceText", segment.sourceText());
            segmentJson.put("sourceVersion", segment.sourceVersion());
            List<Map<String, Object>> translationList = new ArrayList<>();
            for (String language : document.targetLanguages()) {
                Map<String, Object> translationJson = new LinkedHashMap<>();
                translationJson.put("language", language);
                if (segment.isPlural()) {
                    Map<String, PluralTranslationRow> byCategory = pluralTranslations.get(
                            List.of(segment.segmentId(), language));
                    List<String> categories = new ArrayList<>(byCategory.keySet());
                    categories.sort(String::compareTo);
                    List<Map<String, Object>> categoryList = new ArrayList<>();
                    for (String category : categories) {
                        PluralTranslationRow plural = byCategory.get(category);
                        PluralApprovalRow pluralApproval = pluralApprovals.get(
                                List.of(segment.segmentId(), language, category));
                        Map<String, Object> categoryJson = new LinkedHashMap<>();
                        categoryJson.put("category", category);
                        categoryJson.put("content", plural.content());
                        categoryJson.put("author", plural.author());
                        categoryJson.put("translationVersion", plural.translationVersion());
                        categoryJson.put("sourceVersion", plural.sourceVersion());
                        categoryJson.put("termVersion", plural.termVersion());
                        categoryJson.put("reviewer", pluralApproval.reviewer());
                        categoryList.add(categoryJson);
                    }
                    translationJson.put("categories", categoryList);
                } else {
                    TranslationRow translation = translations.get(key(segment.segmentId(), language));
                    ApprovalRow approval = approvals.get(key(segment.segmentId(), language));
                    translationJson.put("content", translation.content());
                    translationJson.put("author", translation.author());
                    translationJson.put("translationVersion", translation.translationVersion());
                    translationJson.put("sourceVersion", translation.sourceVersion());
                    translationJson.put("termVersion", translation.termVersion());
                    translationJson.put("reviewer", approval.reviewer());
                }
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
