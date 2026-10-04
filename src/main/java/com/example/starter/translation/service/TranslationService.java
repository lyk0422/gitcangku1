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

    /** 各语言要求的复数类别集合（按类别名排序）：zh 要求 other，en 要求 one 和 other。 */
    private static final Map<String, List<String>> PLURAL_CATEGORIES = Map.of(
            "zh", List.of("other"),
            "en", List.of("one", "other"));

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
            repository.insertSegment(documentId, segment.segmentId(), segment.sourceText(), segment.plural());
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
        repository.insertSegment(documentId, request.segmentId(), request.sourceText(), request.plural());
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
        if (segment.plural()) {
            throw ApiException.unprocessable("段落为复数类型，须按复数类别提交: " + segmentId);
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

    /** 单个复数类别译文提交：作者取 X-Actor-Id，类别须属于该语言的合法类别集合。 */
    @Transactional
    public ApiDtos.PluralTranslationResponse submitPluralCategory(long documentId, String segmentId,
                                                                  String language, String category, String actorId,
                                                                  ApiDtos.SubmitPluralCategoryRequest request) {
        ApiDtos.PluralCategoriesResponse response = submitPluralCategoriesInternal(documentId, segmentId, language,
                actorId, request.sourceVersion(),
                List.of(new ApiDtos.PluralCategoryInput(category, request.content())));
        ApiDtos.PluralCategoryView view = response.categories().get(0);
        return new ApiDtos.PluralTranslationResponse(response.documentId(), response.segmentId(),
                response.language(), view.category(), view.translationVersion(), response.sourceVersion(),
                response.termVersion(), response.draftVersion());
    }

    /**
     * 复数类别集合提交：一次提交同一语言下若干类别（允许部分集合，仅更新提及的类别，不清空其他类别文本）；
     * 全部类别同一事务提交，任一类别校验失败则整体回滚。
     */
    @Transactional
    public ApiDtos.PluralCategoriesResponse submitPluralCategories(long documentId, String segmentId,
                                                                   String language, String actorId,
                                                                   ApiDtos.SubmitPluralCategoriesRequest request) {
        return submitPluralCategoriesInternal(documentId, segmentId, language, actorId, request.sourceVersion(),
                request.categories());
    }

    /**
     * 复数类别提交公共逻辑：段落须为复数类型，语言须定义了类别集合（zh/en），类别合法且集合内不重复，
     * 所依据源文版本必须等于当前源文版本；绑定当前术语版本并通过术语校验；类别译文版本递增，草稿版本加一。
     */
    private ApiDtos.PluralCategoriesResponse submitPluralCategoriesInternal(
            long documentId, String segmentId, String language, String actorId, int sourceVersion,
            List<ApiDtos.PluralCategoryInput> categories) {
        DocumentRow document = lockDocument(documentId);
        String normalizedLanguage = normalizeLanguage(language);
        if (!document.targetLanguages().contains(normalizedLanguage)) {
            throw ApiException.unprocessable("语言不在文档目标语言中: " + normalizedLanguage);
        }
        SegmentRow segment = findSegmentOrThrow(documentId, segmentId);
        if (!segment.plural()) {
            throw ApiException.unprocessable("段落不是复数类型: " + segmentId);
        }
        List<String> required = PLURAL_CATEGORIES.get(normalizedLanguage);
        if (required == null) {
            throw ApiException.unprocessable("复数段落不支持语言: " + normalizedLanguage);
        }
        if (sourceVersion != segment.sourceVersion()) {
            throw ApiException.unprocessable("译文所依据的源文版本 " + sourceVersion
                    + " 与当前源文版本 " + segment.sourceVersion() + " 不匹配");
        }
        List<String> normalizedCategories = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ApiDtos.PluralCategoryInput input : categories) {
            String category = normalizeLanguage(input.category());
            if (!required.contains(category)) {
                throw ApiException.unprocessable(
                        "语言 " + normalizedLanguage + " 不允许的复数类别: " + category);
            }
            if (!seen.add(category)) {
                throw ApiException.unprocessable("复数类别重复: " + category);
            }
            normalizedCategories.add(category);
        }
        List<TermRuleRow> termRules = repository.listTermRules(documentId, document.termVersion());
        for (ApiDtos.PluralCategoryInput input : categories) {
            List<ApiDtos.TermRuleView> violations = findViolations(
                    segment.sourceText(), normalizedLanguage, input.content(), termRules);
            if (!violations.isEmpty()) {
                throw ApiException.termViolation("译文违反 " + violations.size() + " 条术语规则", violations);
            }
        }
        List<ApiDtos.PluralCategoryView> views = new ArrayList<>();
        for (int i = 0; i < categories.size(); i++) {
            String category = normalizedCategories.get(i);
            int translationVersion = repository
                    .findPluralTranslation(documentId, segmentId, normalizedLanguage, category)
                    .map(PluralTranslationRow::translationVersion).orElse(0) + 1;
            repository.upsertPluralTranslation(documentId, new PluralTranslationRow(segmentId, normalizedLanguage,
                    category, categories.get(i).content(), actorId, segment.sourceVersion(), translationVersion,
                    document.termVersion()));
            views.add(new ApiDtos.PluralCategoryView(category, translationVersion));
        }
        int draftVersion = bumpDraftVersion(document);
        return new ApiDtos.PluralCategoriesResponse(documentId, segmentId, normalizedLanguage, views,
                segment.sourceVersion(), document.termVersion(), draftVersion);
    }

    /** 复数类别批准：审核人不得是该类别作者，且须同时匹配当前源文版本与类别译文版本。 */
    @Transactional
    public ApiDtos.PluralApprovalResponse approvePluralCategory(long documentId, String segmentId, String language,
                                                                String category, String actorId,
                                                                ApiDtos.ApprovePluralCategoryRequest request) {
        lockDocument(documentId);
        String normalizedLanguage = normalizeLanguage(language);
        String normalizedCategory = normalizeLanguage(category);
        SegmentRow segment = findSegmentOrThrow(documentId, segmentId);
        if (!segment.plural()) {
            throw ApiException.unprocessable("段落不是复数类型: " + segmentId);
        }
        PluralTranslationRow translation = repository
                .findPluralTranslation(documentId, segmentId, normalizedLanguage, normalizedCategory)
                .orElseThrow(() -> ApiException.notFound("复数类别译文不存在: " + segmentId + "/"
                        + normalizedLanguage + "/" + normalizedCategory));
        if (translation.author().equals(actorId)) {
            throw ApiException.unprocessable("审核人不得是该类别译文作者");
        }
        if (translation.translationVersion() != request.translationVersion()) {
            throw ApiException.unprocessable("批准所针对的类别译文版本 " + request.translationVersion()
                    + " 与当前类别译文版本 " + translation.translationVersion() + " 不匹配");
        }
        if (translation.sourceVersion() != segment.sourceVersion()) {
            throw ApiException.unprocessable("类别译文基于源文版本 " + translation.sourceVersion()
                    + "，当前源文版本 " + segment.sourceVersion() + "，译文待更新，不能批准");
        }
        repository.upsertPluralApproval(documentId, new PluralApprovalRow(segmentId, normalizedLanguage,
                normalizedCategory, actorId, segment.sourceVersion(), translation.translationVersion()));
        return new ApiDtos.PluralApprovalResponse(documentId, segmentId, normalizedLanguage, normalizedCategory,
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
        List<TermRuleRow> termRules = repository.listTermRules(documentId, document.termVersion());
        Map<List<String>, PluralTranslationRow> pluralTranslations = repository.listPluralTranslations(documentId)
                .stream().collect(Collectors.toMap(
                        t -> List.of(t.segmentId(), t.language(), t.category()), Function.identity()));
        Map<List<String>, PluralApprovalRow> pluralApprovals = repository.listPluralApprovals(documentId)
                .stream().collect(Collectors.toMap(
                        a -> List.of(a.segmentId(), a.language(), a.category()), Function.identity()));
        List<ApiDtos.TermRuleView> termViolations = new ArrayList<>();
        for (SegmentRow segment : segments) {
            if (segment.plural()) {
                validatePluralSegment(document, segment, pluralTranslations, pluralApprovals, termRules,
                        termViolations);
                continue;
            }
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
        int publishedVersion = document.publishedVersion() + 1;
        repository.insertSnapshot(documentId, publishedVersion,
                buildSnapshotJson(document, publishedVersion, segments, translations, approvals,
                        pluralTranslations, pluralApprovals, termRules));
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
     * 校验复数段落在每种目标语言下的完整类别集合：缺类别、额外类别、类别译文源文/术语版本过期、
     * 类别未批准或批准失效均抛 422；术语违规收集到 termViolations 统一返回。
     */
    private void validatePluralSegment(DocumentRow document, SegmentRow segment,
                                       Map<List<String>, PluralTranslationRow> pluralTranslations,
                                       Map<List<String>, PluralApprovalRow> pluralApprovals,
                                       List<TermRuleRow> termRules,
                                       List<ApiDtos.TermRuleView> termViolations) {
        for (String language : document.targetLanguages()) {
            List<String> required = PLURAL_CATEGORIES.get(language);
            if (required == null) {
                throw ApiException.unprocessable(
                        "复数段落不支持语言: " + segment.segmentId() + "/" + language);
            }
            for (Map.Entry<List<String>, PluralTranslationRow> entry : pluralTranslations.entrySet()) {
                List<String> k = entry.getKey();
                if (k.get(0).equals(segment.segmentId()) && k.get(1).equals(language)
                        && !required.contains(k.get(2))) {
                    throw ApiException.unprocessable("额外复数类别: " + segment.segmentId() + "/" + language
                            + "/" + k.get(2));
                }
            }
            for (String category : required) {
                List<String> categoryKey = List.of(segment.segmentId(), language, category);
                PluralTranslationRow translation = pluralTranslations.get(categoryKey);
                if (translation == null) {
                    throw ApiException.unprocessable(
                            "缺少复数类别译文: " + segment.segmentId() + "/" + language + "/" + category);
                }
                if (translation.sourceVersion() != segment.sourceVersion()) {
                    throw ApiException.unprocessable("复数类别译文待更新: " + segment.segmentId() + "/" + language
                            + "/" + category + " 基于源文版本 " + translation.sourceVersion()
                            + "，当前源文版本 " + segment.sourceVersion());
                }
                if (translation.termVersion() != document.termVersion()) {
                    throw ApiException.unprocessable("复数类别译文术语版本过期: " + segment.segmentId() + "/"
                            + language + "/" + category + " 绑定术语版本 " + translation.termVersion()
                            + "，当前术语版本 " + document.termVersion());
                }
                PluralApprovalRow approval = pluralApprovals.get(categoryKey);
                if (approval == null) {
                    throw ApiException.unprocessable(
                            "缺少复数类别批准: " + segment.segmentId() + "/" + language + "/" + category);
                }
                if (approval.translationVersion() != translation.translationVersion()
                        || approval.sourceVersion() != segment.sourceVersion()) {
                    throw ApiException.unprocessable("复数类别批准已失效: " + segment.segmentId() + "/" + language
                            + "/" + category + "，源文或类别译文版本已改变");
                }
                termViolations.addAll(findViolations(
                        segment.sourceText(), language, translation.content(), termRules));
            }
        }
    }
    /** 生成完整只读快照 JSON：全部段落源文及各语言译文、作者、审核人、版本号与固化的术语版本及规则集。 */
    private String buildSnapshotJson(DocumentRow document, int publishedVersion, List<SegmentRow> segments,
                                     Map<String, TranslationRow> translations,
                                     Map<String, ApprovalRow> approvals,
                                     Map<List<String>, PluralTranslationRow> pluralTranslations,
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
            segmentJson.put("sourceText", segment.sourceText());
            segmentJson.put("sourceVersion", segment.sourceVersion());
            segmentJson.put("plural", segment.plural());
            List<Map<String, Object>> translationList = new ArrayList<>();
            for (String language : document.targetLanguages()) {
                Map<String, Object> translationJson = new LinkedHashMap<>();
                translationJson.put("language", language);
                if (segment.plural()) {
                    // 复数段落：按类别名排序冻结各类别文本、源文版本、术语版本与作者/批准人
                    List<Map<String, Object>> categoryList = new ArrayList<>();
                    for (String category : PLURAL_CATEGORIES.get(language)) {
                        List<String> categoryKey = List.of(segment.segmentId(), language, category);
                        PluralTranslationRow pluralTranslation = pluralTranslations.get(categoryKey);
                        PluralApprovalRow pluralApproval = pluralApprovals.get(categoryKey);
                        Map<String, Object> categoryJson = new LinkedHashMap<>();
                        categoryJson.put("category", category);
                        categoryJson.put("content", pluralTranslation.content());
                        categoryJson.put("author", pluralTranslation.author());
                        categoryJson.put("translationVersion", pluralTranslation.translationVersion());
                        categoryJson.put("sourceVersion", pluralTranslation.sourceVersion());
                        categoryJson.put("termVersion", pluralTranslation.termVersion());
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
