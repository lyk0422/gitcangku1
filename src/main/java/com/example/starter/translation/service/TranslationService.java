package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiDtos;
import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.domain.Rows.ApprovalRow;
import com.example.starter.translation.domain.Rows.DocumentRow;
import com.example.starter.translation.domain.Rows.RegionalVariantRow;
import com.example.starter.translation.domain.Rows.ReleaseResolutionRow;
import com.example.starter.translation.domain.Rows.SegmentRow;
import com.example.starter.translation.domain.Rows.TermRuleRow;
import com.example.starter.translation.domain.Rows.TranslationRow;
import com.example.starter.translation.repo.TranslationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
     * 译文绑定当前术语版本（过期 422）且满足当前术语规则（违规 422 并返回全部违规术语）；
     * 给定区域时按 区域变体 → DEFAULT 变体 解析，两者均缺失则整次 422 并稳定排序返回缺失段落；
     * 全部通过后原子生成完整只读快照（固化术语版本、实际规则集、每个段落最终选用的译文版本、
     * 区域代码与回退来源）并递增发布版本；任何失败回滚，不产生部分快照，既有发布版本不变。
     * 变体与术语版本均在同一文档行锁下读取，并发按事务提交顺序裁决，不混合新旧区域选择。
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
        boolean allowBase = request.region() == null || request.region().isBlank();
        String requestedRegion = allowBase ? DEFAULT_REGION : normalizeRegion(request.region());
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
        Map<String, RegionalVariantRow> variants = repository.listVariants(documentId).stream()
                .collect(Collectors.toMap(
                        v -> variantKey(v.segmentId(), v.language(), v.regionCode()), Function.identity()));
        Map<String, RegionalPick> picks = new LinkedHashMap<>();
        List<ApiDtos.MissingSegment> missing = new ArrayList<>();
        for (SegmentRow segment : segments) {
            for (String language : document.targetLanguages()) {
                TranslationRow translation = translations.get(key(segment.segmentId(), language));
                RegionalPick pick = resolvePick(variants, translation, segment.segmentId(), language,
                        requestedRegion, allowBase, document.termVersion());
                if (pick == null) {
                    missing.add(new ApiDtos.MissingSegment(segment.segmentId(), language));
                } else {
                    picks.put(key(segment.segmentId(), language), pick);
                }
            }
        }
        if (!missing.isEmpty()) {
            missing.sort(Comparator.comparing(ApiDtos.MissingSegment::segmentId)
                    .thenComparing(ApiDtos.MissingSegment::language));
            throw ApiException.regionalMissing("区域 " + requestedRegion + " 有 " + missing.size()
                    + " 个段落语言缺少有效区域变体", missing);
        }
        int publishedVersion = document.publishedVersion() + 1;
        repository.insertSnapshot(documentId, publishedVersion,
                buildSnapshotJson(document, publishedVersion, segments, translations, approvals, termRules,
                        requestedRegion, picks));
        for (SegmentRow segment : segments) {
            for (String language : document.targetLanguages()) {
                RegionalPick pick = picks.get(key(segment.segmentId(), language));
                TranslationRow translation = translations.get(key(segment.segmentId(), language));
                repository.insertResolution(documentId, new ReleaseResolutionRow(publishedVersion,
                        segment.segmentId(), language, requestedRegion, pick.regionCode(),
                        translation.translationVersion(), pick.fallbackSource()));
            }
        }
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
     * 生成完整只读快照 JSON：全部段落源文及各语言最终选用的译文内容、作者、审核人、版本号、
     * 固化的术语版本及规则集，以及每个段落最终选用的区域代码与回退来源。
     */
    private String buildSnapshotJson(DocumentRow document, int publishedVersion, List<SegmentRow> segments,
                                     Map<String, TranslationRow> translations,
                                     Map<String, ApprovalRow> approvals, List<TermRuleRow> termRules,
                                     String requestedRegion, Map<String, RegionalPick> picks) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("documentId", document.documentId());
        snapshot.put("publishedVersion", publishedVersion);
        snapshot.put("draftVersion", document.draftVersion());
        snapshot.put("termVersion", document.termVersion());
        snapshot.put("targetLanguages", document.targetLanguages());
        snapshot.put("requestedRegion", requestedRegion);
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
                RegionalPick pick = picks.get(key(segment.segmentId(), language));
                Map<String, Object> translationJson = new LinkedHashMap<>();
                translationJson.put("language", language);
                translationJson.put("content", pick.content());
                translationJson.put("author", translation.author());
                translationJson.put("translationVersion", translation.translationVersion());
                translationJson.put("sourceVersion", translation.sourceVersion());
                translationJson.put("termVersion", translation.termVersion());
                translationJson.put("reviewer", approval.reviewer());
                translationJson.put("regionCode", pick.regionCode());
                translationJson.put("fallbackSource", pick.fallbackSource());
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

    /**
     * 区域变体创建：expectedVersion 必须等于当前草稿版本（不符 409）；语言须在目标语言中、
     * 译文存在且持有效批准（不符 422）；变体内容按当前术语规则校验（违规 422）；
     * 同一译文版本已存在未撤销变体时 409；绑定当前译文与术语版本，初始状态 PENDING，草稿版本加一。
     */
    @Transactional
    public ApiDtos.VariantResponse createVariant(long documentId, String segmentId, String language,
                                                 String actorId, ApiDtos.CreateVariantRequest request) {
        DocumentRow document = lockDocument(documentId);
        if (document.draftVersion() != request.expectedVersion()) {
            throw ApiException.conflict("版本冲突：当前草稿版本 " + document.draftVersion()
                    + "，与期望的 " + request.expectedVersion() + " 不一致");
        }
        String normalizedLanguage = normalizeLanguage(language);
        if (!document.targetLanguages().contains(normalizedLanguage)) {
            throw ApiException.unprocessable("语言不在文档目标语言中: " + normalizedLanguage);
        }
        String regionCode = normalizeRegion(request.regionCode());
        SegmentRow segment = findSegmentOrThrow(documentId, segmentId);
        TranslationRow translation = repository.findTranslation(documentId, segmentId, normalizedLanguage)
                .orElseThrow(() -> ApiException.notFound(
                        "译文不存在: " + segmentId + "/" + normalizedLanguage));
        ApprovalRow approval = repository.findApproval(documentId, segmentId, normalizedLanguage).orElse(null);
        if (approval == null || approval.translationVersion() != translation.translationVersion()
                || approval.sourceVersion() != segment.sourceVersion()) {
            throw ApiException.unprocessable("译文无有效批准，不能登记区域变体: " + segmentId + "/"
                    + normalizedLanguage);
        }
        List<ApiDtos.TermRuleView> violations = findViolations(segment.sourceText(), normalizedLanguage,
                request.content(), repository.listTermRules(documentId, document.termVersion()));
        if (!violations.isEmpty()) {
            throw ApiException.termViolation("区域变体违反 " + violations.size() + " 条术语规则", violations);
        }
        RegionalVariantRow existing = repository.findVariant(
                documentId, segmentId, normalizedLanguage, regionCode).orElse(null);
        if (existing != null && !STATUS_REVOKED.equals(existing.status())
                && existing.translationVersion() == translation.translationVersion()) {
            throw ApiException.conflict("同一译文版本已存在有效变体: " + segmentId + "/" + normalizedLanguage
                    + "/" + regionCode);
        }
        int variantVersion = existing == null ? 1 : existing.variantVersion() + 1;
        repository.upsertVariant(documentId, new RegionalVariantRow(segmentId, normalizedLanguage, regionCode,
                request.content(), actorId, translation.translationVersion(), document.termVersion(),
                variantVersion, STATUS_PENDING));
        int draftVersion = bumpDraftVersion(document);
        return new ApiDtos.VariantResponse(documentId, segmentId, normalizedLanguage, regionCode,
                variantVersion, translation.translationVersion(), STATUS_PENDING, draftVersion);
    }

    /** 区域变体批准：审核人不得是变体作者，仅 PENDING 状态可批准；批准后状态 ACTIVE 参与区域解析。 */
    @Transactional
    public ApiDtos.VariantResponse approveVariant(long documentId, String segmentId, String language,
                                                  String regionCode, String actorId,
                                                  ApiDtos.ApproveVariantRequest request) {
        DocumentRow document = lockDocument(documentId);
        String normalizedLanguage = normalizeLanguage(language);
        String normalizedRegion = normalizeRegion(regionCode);
        RegionalVariantRow variant = repository.findVariant(documentId, segmentId, normalizedLanguage,
                normalizedRegion).orElseThrow(() -> ApiException.notFound(
                        "区域变体不存在: " + segmentId + "/" + normalizedLanguage + "/" + normalizedRegion));
        if (!STATUS_PENDING.equals(variant.status())) {
            throw ApiException.unprocessable("变体不在待批准状态: " + variant.status());
        }
        if (variant.author().equals(actorId)) {
            throw ApiException.unprocessable("审核人不得是该变体作者");
        }
        repository.updateVariantStatus(documentId, segmentId, normalizedLanguage, normalizedRegion, STATUS_ACTIVE);
        return new ApiDtos.VariantResponse(documentId, segmentId, normalizedLanguage, normalizedRegion,
                variant.variantVersion(), variant.translationVersion(), STATUS_ACTIVE, document.draftVersion());
    }

    /**
     * 区域变体撤销：expectedVersion 必须等于当前草稿版本（不符 409）；已撤销的变体重复撤销 422；
     * 撤销后状态 REVOKED 不再参与区域解析，后续发布与查询自动回退 DEFAULT；草稿版本加一。
     */
    @Transactional
    public ApiDtos.VariantResponse revokeVariant(long documentId, String segmentId, String language,
                                                 String regionCode, ApiDtos.RevokeVariantRequest request) {
        DocumentRow document = lockDocument(documentId);
        if (document.draftVersion() != request.expectedVersion()) {
            throw ApiException.conflict("版本冲突：当前草稿版本 " + document.draftVersion()
                    + "，与期望的 " + request.expectedVersion() + " 不一致");
        }
        String normalizedLanguage = normalizeLanguage(language);
        String normalizedRegion = normalizeRegion(regionCode);
        RegionalVariantRow variant = repository.findVariant(documentId, segmentId, normalizedLanguage,
                normalizedRegion).orElseThrow(() -> ApiException.notFound(
                        "区域变体不存在: " + segmentId + "/" + normalizedLanguage + "/" + normalizedRegion));
        if (STATUS_REVOKED.equals(variant.status())) {
            throw ApiException.unprocessable("变体已撤销: " + segmentId + "/" + normalizedLanguage
                    + "/" + normalizedRegion);
        }
        repository.updateVariantStatus(documentId, segmentId, normalizedLanguage, normalizedRegion,
                STATUS_REVOKED);
        int draftVersion = bumpDraftVersion(document);
        return new ApiDtos.VariantResponse(documentId, segmentId, normalizedLanguage, normalizedRegion,
                variant.variantVersion(), variant.translationVersion(), STATUS_REVOKED, draftVersion);
    }

    /**
     * 区域覆盖解析查询：给定区域按 区域变体 → DEFAULT 变体 解析每个段落+语言最终选用的内容；
     * 未指定区域时解析 DEFAULT，无变体时回退基础译文；缺失条目稳定排序返回，不产生错误。
     */
    @Transactional(readOnly = true)
    public ApiDtos.ResolutionResponse getResolution(long documentId, String region) {
        DocumentRow document = repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        boolean allowBase = region == null || region.isBlank();
        String requestedRegion = allowBase ? DEFAULT_REGION : normalizeRegion(region);
        Map<String, TranslationRow> translations = repository.listTranslations(documentId).stream()
                .collect(Collectors.toMap(t -> key(t.segmentId(), t.language()), Function.identity()));
        Map<String, RegionalVariantRow> variants = repository.listVariants(documentId).stream()
                .collect(Collectors.toMap(
                        v -> variantKey(v.segmentId(), v.language(), v.regionCode()), Function.identity()));
        List<ApiDtos.ResolutionEntry> entries = new ArrayList<>();
        List<ApiDtos.MissingSegment> missing = new ArrayList<>();
        for (SegmentRow segment : repository.listSegments(documentId)) {
            for (String language : document.targetLanguages()) {
                TranslationRow translation = translations.get(key(segment.segmentId(), language));
                RegionalPick pick = translation == null ? null
                        : resolvePick(variants, translation, segment.segmentId(), language,
                                requestedRegion, allowBase, document.termVersion());
                if (pick == null) {
                    missing.add(new ApiDtos.MissingSegment(segment.segmentId(), language));
                    entries.add(new ApiDtos.ResolutionEntry(segment.segmentId(), language, true,
                            null, null, null, null));
                } else {
                    entries.add(new ApiDtos.ResolutionEntry(segment.segmentId(), language, false,
                            pick.content(), pick.regionCode(), translation.translationVersion(),
                            pick.fallbackSource()));
                }
            }
        }
        return new ApiDtos.ResolutionResponse(documentId, requestedRegion, entries, missing);
    }

    /** 回退历史查询：全部发布固化的区域解析记录，按发布版本、段落、语言稳定排序。 */
    @Transactional(readOnly = true)
    public ApiDtos.FallbackHistoryResponse getFallbackHistory(long documentId) {
        repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        List<ApiDtos.FallbackHistoryEntry> entries = repository.listResolutions(documentId).stream()
                .map(r -> new ApiDtos.FallbackHistoryEntry(r.publishedVersion(), r.segmentId(), r.language(),
                        r.requestedRegion(), r.resolvedRegion(), r.translationVersion(), r.fallbackSource()))
                .toList();
        return new ApiDtos.FallbackHistoryResponse(documentId, entries);
    }

    /** 全局默认区域代码：未指定区域或具体区域变体缺失时的回退目标。 */
    private static final String DEFAULT_REGION = "DEFAULT";

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_REVOKED = "REVOKED";

    /** 区域解析结果：最终选用的内容、区域代码与回退来源。 */
    private record RegionalPick(String content, String regionCode, String fallbackSource) {
    }

    /**
     * 区域解析：优先请求区域的有效变体（EXACT），缺失时回退 DEFAULT 有效变体
     * （请求 DEFAULT 时为 EXACT，否则 FALLBACK_DEFAULT）；allowBase 时最后回退基础译文（BASE），
     * 否则返回 null 表示缺失。具体区域变体按键精确匹配，不跨语言、不跨段落生效。
     */
    private static RegionalPick resolvePick(Map<String, RegionalVariantRow> variants, TranslationRow translation,
                                            String segmentId, String language, String requestedRegion,
                                            boolean allowBase, int termVersion) {
        if (!DEFAULT_REGION.equals(requestedRegion)) {
            RegionalVariantRow exact = effectiveVariant(
                    variants.get(variantKey(segmentId, language, requestedRegion)), translation, termVersion);
            if (exact != null) {
                return new RegionalPick(exact.content(), requestedRegion, "EXACT");
            }
        }
        RegionalVariantRow fallback = effectiveVariant(
                variants.get(variantKey(segmentId, language, DEFAULT_REGION)), translation, termVersion);
        if (fallback != null) {
            return new RegionalPick(fallback.content(), DEFAULT_REGION,
                    DEFAULT_REGION.equals(requestedRegion) ? "EXACT" : "FALLBACK_DEFAULT");
        }
        if (allowBase) {
            return new RegionalPick(translation.content(), DEFAULT_REGION, "BASE");
        }
        return null;
    }

    /** 有效变体：ACTIVE 且绑定的译文版本与术语版本均等于当前版本，否则视为失效不参与解析。 */
    private static RegionalVariantRow effectiveVariant(RegionalVariantRow variant, TranslationRow translation,
                                                       int termVersion) {
        if (variant == null || !STATUS_ACTIVE.equals(variant.status())
                || variant.translationVersion() != translation.translationVersion()
                || variant.termVersion() != termVersion) {
            return null;
        }
        return variant;
    }

    private static String variantKey(String segmentId, String language, String regionCode) {
        return segmentId + " " + language + " " + regionCode;
    }

    /** 区域代码归一为大写；须为字母数字开头、可含连字符、最长 32 字符（不符 422）。 */
    private static String normalizeRegion(String regionCode) {
        String normalized = regionCode.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9][A-Z0-9-]{0,31}")) {
            throw ApiException.unprocessable("区域代码不合法: " + regionCode);
        }
        return normalized;
    }
}
