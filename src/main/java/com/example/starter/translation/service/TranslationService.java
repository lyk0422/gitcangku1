package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiDtos;
import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.domain.Rows.ApprovalRow;
import com.example.starter.translation.domain.Rows.DocumentRow;
import com.example.starter.translation.domain.Rows.FallbackRow;
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
     * 撤回批准：删除该段落与语言的批准记录，译文内容保留但不再处于已批准状态；
     * 无有效批准返回 404。撤回不影响已发布快照，仅使后续发布的该语种解析沿回退链或判缺失。
     */
    @Transactional
    public ApiDtos.ApprovalResponse withdrawApproval(long documentId, String segmentId, String language,
                                                     String actorId) {
        DocumentRow document = lockDocument(documentId);
        String normalizedLanguage = normalizeLanguage(language);
        ApprovalRow approval = repository.findApproval(documentId, segmentId, normalizedLanguage)
                .orElseThrow(() -> ApiException.notFound(
                        "批准不存在: " + segmentId + "/" + normalizedLanguage));
        repository.deleteApproval(documentId, segmentId, normalizedLanguage);
        return new ApiDtos.ApprovalResponse(documentId, segmentId, normalizedLanguage, actorId,
                approval.translationVersion(), approval.sourceVersion());
    }

    /**
     * 配置某目标语种的回退语种：expectedVersion 必须等于当前草稿版本（不符 409）；
     * 语种与回退语种均须为文档已登记目标语种且不得相同（422）；fallbackLanguage 为空表示清除回退。
     * 应用变更后在同一事务内复核全部目标语种的回退链无环，成环则整次回滚（422）；
     * 成功后草稿版本加一，与并发发布按文档行锁提交顺序裁决。
     */
    @Transactional
    public ApiDtos.FallbackConfigResponse configureFallback(long documentId, String language,
                                                            ApiDtos.ConfigureFallbackRequest request) {
        DocumentRow document = lockDocument(documentId);
        if (document.draftVersion() != request.expectedVersion()) {
            throw ApiException.conflict("版本冲突：当前草稿版本 " + document.draftVersion()
                    + "，与期望的 " + request.expectedVersion() + " 不一致");
        }
        String normalizedLanguage = normalizeLanguage(language);
        if (!document.targetLanguages().contains(normalizedLanguage)) {
            throw ApiException.unprocessable("语言不在文档目标语言中: " + normalizedLanguage);
        }
        String fallbackLanguage = request.fallbackLanguage() == null
                || request.fallbackLanguage().isBlank() ? null : normalizeLanguage(request.fallbackLanguage());
        if (fallbackLanguage != null) {
            if (!document.targetLanguages().contains(fallbackLanguage)) {
                throw ApiException.unprocessable("回退语种未登记为文档目标语言: " + fallbackLanguage);
            }
            if (fallbackLanguage.equals(normalizedLanguage)) {
                throw ApiException.unprocessable("回退语种不得与自身相同: " + normalizedLanguage);
            }
            repository.upsertFallback(documentId, normalizedLanguage, fallbackLanguage);
        } else {
            repository.deleteFallback(documentId, normalizedLanguage);
        }
        Map<String, String> fallbacks = loadFallbackMap(documentId);
        if (FallbackChain.hasCycle(fallbacks)) {
            throw ApiException.unprocessable("回退链成环，整次配置回滚: " + normalizedLanguage);
        }
        int draftVersion = bumpDraftVersion(document);
        return new ApiDtos.FallbackConfigResponse(documentId, normalizedLanguage, fallbackLanguage, draftVersion);
    }

    /** 查询全部目标语种的回退链：每个语种从自身出发逐级展开的有序链（含未配置回退的单节点链）。 */
    @Transactional(readOnly = true)
    public ApiDtos.FallbackChainsResponse getFallbackChains(long documentId) {
        DocumentRow document = repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        Map<String, String> fallbacks = loadFallbackMap(documentId);
        List<ApiDtos.FallbackChainView> chains = document.targetLanguages().stream()
                .map(language -> new ApiDtos.FallbackChainView(language, FallbackChain.chain(language, fallbacks)))
                .toList();
        return new ApiDtos.FallbackChainsResponse(documentId, chains);
    }

    /**
     * 缺失段诊断查询：按当前回退链与已批准译文状态，逐段落逐语种解析，
     * 返回沿链全部语种均无已批准译文的段落及已尝试语种，按 segmentId、language 稳定排序。
     */
    @Transactional(readOnly = true)
    public ApiDtos.MissingSegmentsResponse getMissingSegments(long documentId) {
        DocumentRow document = repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        ResolutionContext context = loadResolutionContext(document);
        List<ApiDtos.MissingSegmentView> missing = new ArrayList<>();
        for (SegmentRow segment : context.segments()) {
            for (String language : document.targetLanguages()) {
                if (resolve(segment, language, context) == null) {
                    missing.add(new ApiDtos.MissingSegmentView(segment.segmentId(), language,
                            FallbackChain.chain(language, context.fallbacks())));
                }
            }
        }
        missing.sort(Comparator.comparing(ApiDtos.MissingSegmentView::segmentId)
                .thenComparing(ApiDtos.MissingSegmentView::language));
        return new ApiDtos.MissingSegmentsResponse(documentId, missing);
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
     * 发布：校验期望版本（不符 409）；对每个源段、每个待发布目标语种先取该语种已批准译文，
     * 缺失时沿回退链逐级查找首个已批准译文（直接译文优先）；回退链全部缺失的源段使整次发布 422，
     * 稳定排序返回缺失段标识与已尝试语种，不产生部分快照。解析出的译文仍须满足当前术语版本与规则
     * （过期或违规 422）。全部通过后原子生成完整只读快照，固化逐段实际使用语种与译文版本并递增发布版本。
     * request.language 为空时发布全部目标语种，否则只发布指定语种（须已登记，422）。
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
        List<String> publishLanguages = document.targetLanguages();
        if (request.language() != null && !request.language().isBlank()) {
            String language = normalizeLanguage(request.language());
            if (!document.targetLanguages().contains(language)) {
                throw ApiException.unprocessable("语言不在文档目标语言中: " + language);
            }
            publishLanguages = List.of(language);
        }
        ResolutionContext context = loadResolutionContext(document);
        List<ApiDtos.MissingSegmentView> missing = new ArrayList<>();
        List<ApiDtos.TermRuleView> termViolations = new ArrayList<>();
        // key: segmentId + " " + 请求语种 → 实际解析结果
        Map<String, Resolved> resolved = new HashMap<>();
        for (SegmentRow segment : context.segments()) {
            for (String language : publishLanguages) {
                Resolved hit = resolve(segment, language, context);
                if (hit == null) {
                    missing.add(new ApiDtos.MissingSegmentView(segment.segmentId(), language,
                            FallbackChain.chain(language, context.fallbacks())));
                    continue;
                }
                resolved.put(key(segment.segmentId(), language), hit);
                termViolations.addAll(findViolations(segment.sourceText(), hit.usedLanguage(),
                        hit.translation().content(), context.termRules()));
            }
        }
        if (!missing.isEmpty()) {
            missing.sort(Comparator.comparing(ApiDtos.MissingSegmentView::segmentId)
                    .thenComparing(ApiDtos.MissingSegmentView::language));
            throw ApiException.missingSegments(
                    "回退链全部缺失的源段共 " + missing.size() + " 处，整次发布拦截", missing);
        }
        if (!termViolations.isEmpty()) {
            throw ApiException.termViolation("译文违反 " + termViolations.size() + " 条术语规则", termViolations);
        }
        int publishedVersion = document.publishedVersion() + 1;
        repository.insertSnapshot(documentId, publishedVersion,
                buildSnapshotJson(document, publishedVersion, publishLanguages, context.segments(), resolved));
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
     * 生成完整只读快照 JSON：全部段落源文及逐请求语种的解析结果，
     * 每条固化实际使用语种（usedLanguage，直接译文时等于请求语种）、译文版本、作者、审核人与术语版本，
     * 并固化发布时的回退链与术语版本及规则集；生成后不可修改，后续撤回/修订/术语退役不追溯。
     */
    private String buildSnapshotJson(DocumentRow document, int publishedVersion, List<String> publishLanguages,
                                     List<SegmentRow> segments, Map<String, Resolved> resolved) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("documentId", document.documentId());
        snapshot.put("publishedVersion", publishedVersion);
        snapshot.put("draftVersion", document.draftVersion());
        snapshot.put("termVersion", document.termVersion());
        snapshot.put("targetLanguages", document.targetLanguages());
        snapshot.put("publishedLanguages", publishLanguages);
        Map<String, String> fallbacks = loadFallbackMap(document.documentId());
        List<Map<String, Object>> chainList = new ArrayList<>();
        for (String language : publishLanguages) {
            Map<String, Object> chainJson = new LinkedHashMap<>();
            chainJson.put("language", language);
            chainJson.put("chain", FallbackChain.chain(language, fallbacks));
            chainList.add(chainJson);
        }
        snapshot.put("fallbackChains", chainList);
        List<TermRuleRow> termRules = repository.listTermRules(document.documentId(), document.termVersion());
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
            for (String language : publishLanguages) {
                Resolved hit = resolved.get(key(segment.segmentId(), language));
                Map<String, Object> translationJson = new LinkedHashMap<>();
                translationJson.put("language", language);
                translationJson.put("usedLanguage", hit.usedLanguage());
                translationJson.put("content", hit.translation().content());
                translationJson.put("author", hit.translation().author());
                translationJson.put("translationVersion", hit.translation().translationVersion());
                translationJson.put("sourceVersion", hit.translation().sourceVersion());
                translationJson.put("termVersion", hit.translation().termVersion());
                translationJson.put("reviewer", hit.approval().reviewer());
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

    /** 回退链解析结果：实际使用语种及该语种的译文与批准（版本已校验与当前源文/术语一致）。 */
    private record Resolved(String usedLanguage, TranslationRow translation, ApprovalRow approval) {
    }

    /** 发布/诊断共用的只读解析上下文：段落、译文、批准、回退链、当前术语版本与规则。 */
    private record ResolutionContext(List<SegmentRow> segments, Map<String, TranslationRow> translations,
                                     Map<String, ApprovalRow> approvals, Map<String, String> fallbacks,
                                     int termVersion, List<TermRuleRow> termRules) {
    }

    private Map<String, String> loadFallbackMap(long documentId) {
        return repository.listFallbacks(documentId).stream()
                .collect(Collectors.toMap(FallbackRow::language, FallbackRow::fallbackLanguage));
    }

    private ResolutionContext loadResolutionContext(DocumentRow document) {
        long documentId = document.documentId();
        Map<String, TranslationRow> translations = repository.listTranslations(documentId).stream()
                .collect(Collectors.toMap(t -> key(t.segmentId(), t.language()), Function.identity()));
        Map<String, ApprovalRow> approvals = repository.listApprovals(documentId).stream()
                .collect(Collectors.toMap(a -> key(a.segmentId(), a.language()), Function.identity()));
        return new ResolutionContext(repository.listSegments(documentId), translations, approvals,
                loadFallbackMap(documentId), document.termVersion(),
                repository.listTermRules(documentId, document.termVersion()));
    }

    /**
     * 解析某段落某请求语种的可用译文：沿回退链（含直接语种）逐级查找首个已批准译文，
     * 要求译文基于当前源文版本、绑定当前术语版本且批准与当前源文/译文版本一致；全部不满足返回 null。
     */
    private Resolved resolve(SegmentRow segment, String language, ResolutionContext context) {
        for (String candidate : FallbackChain.chain(language, context.fallbacks())) {
            TranslationRow translation = context.translations().get(key(segment.segmentId(), candidate));
            if (translation == null || translation.sourceVersion() != segment.sourceVersion()
                    || translation.termVersion() != context.termVersion()) {
                continue;
            }
            ApprovalRow approval = context.approvals().get(key(segment.segmentId(), candidate));
            if (approval == null || approval.translationVersion() != translation.translationVersion()
                    || approval.sourceVersion() != segment.sourceVersion()) {
                continue;
            }
            return new Resolved(candidate, translation, approval);
        }
        return null;
    }
}
