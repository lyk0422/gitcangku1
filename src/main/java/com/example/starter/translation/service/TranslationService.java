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
     * 回退配置全量替换：expectedVersion 必须等于当前草稿版本（不符 409）；
     * 每个语种与回退目标均须已登记为文档目标语言、不得自环、不得重复（不符 422）；
     * 在同一事务内复核替换后该文档全部目标语种的回退链无环，任一不合法整次回滚。
     * 成功后草稿版本加一；空列表表示清空全部回退配置。
     */
    @Transactional
    public ApiDtos.FallbackConfigResponse updateFallbacks(long documentId,
                                                          ApiDtos.UpdateFallbacksRequest request) {
        DocumentRow document = lockDocument(documentId);
        if (document.draftVersion() != request.expectedVersion()) {
            throw ApiException.conflict("版本冲突：当前草稿版本 " + document.draftVersion()
                    + "，与期望的 " + request.expectedVersion() + " 不一致");
        }
        Map<String, String> fallbacks = new LinkedHashMap<>();
        for (ApiDtos.FallbackInput input : request.fallbacks()) {
            String language = normalizeLanguage(input.language());
            String fallbackLanguage = normalizeLanguage(input.fallbackLanguage());
            if (!document.targetLanguages().contains(language)) {
                throw ApiException.unprocessable("语言不在文档目标语言中: " + language);
            }
            if (!document.targetLanguages().contains(fallbackLanguage)) {
                throw ApiException.unprocessable("回退语种未登记为文档目标语言: " + fallbackLanguage);
            }
            if (language.equals(fallbackLanguage)) {
                throw ApiException.unprocessable("回退语种不能是自身: " + language);
            }
            if (fallbacks.put(language, fallbackLanguage) != null) {
                throw ApiException.unprocessable("回退配置重复: " + language);
            }
        }
        assertAcyclic(fallbacks);
        repository.deleteFallbacks(documentId);
        for (Map.Entry<String, String> entry : fallbacks.entrySet()) {
            repository.insertFallback(documentId, entry.getKey(), entry.getValue());
        }
        int draftVersion = bumpDraftVersion(document);
        return new ApiDtos.FallbackConfigResponse(documentId, draftVersion, toFallbackEntries(fallbacks));
    }

    /** 查询文档当前全部回退配置，按语言排序。 */
    @Transactional(readOnly = true)
    public ApiDtos.FallbackConfigView getFallbacks(long documentId) {
        repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        List<ApiDtos.FallbackEntry> entries = repository.listFallbacks(documentId).stream()
                .map(row -> new ApiDtos.FallbackEntry(row.language(), row.fallbackLanguage()))
                .toList();
        return new ApiDtos.FallbackConfigView(documentId, entries);
    }

    /** 查询指定目标语种的回退链：从该语种出发逐级解析的有序语种链（含自身）。 */
    @Transactional(readOnly = true)
    public ApiDtos.FallbackChainView getFallbackChain(long documentId, String language) {
        DocumentRow document = repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        String normalizedLanguage = normalizeLanguage(language);
        if (!document.targetLanguages().contains(normalizedLanguage)) {
            throw ApiException.unprocessable("语言不在文档目标语言中: " + normalizedLanguage);
        }
        Map<String, String> fallbackMap = fallbackMap(documentId);
        List<String> chain = new ArrayList<>();
        String current = normalizedLanguage;
        Set<String> visited = new HashSet<>();
        while (current != null && visited.add(current)) {
            chain.add(current);
            current = fallbackMap.get(current);
        }
        return new ApiDtos.FallbackChainView(documentId, normalizedLanguage, chain);
    }

    /**
     * 译文撤回：删除指定段落与语言的译文及其批准，草稿版本加一；
     * 只影响后续发布解析，不改写已发布快照。译文不存在返回 404。
     */
    @Transactional
    public ApiDtos.WithdrawResponse withdrawTranslation(long documentId, String segmentId, String language,
                                                        ApiDtos.WithdrawTranslationRequest request) {
        DocumentRow document = lockDocument(documentId);
        String normalizedLanguage = normalizeLanguage(language);
        findSegmentOrThrow(documentId, segmentId);
        repository.findTranslation(documentId, segmentId, normalizedLanguage)
                .orElseThrow(() -> ApiException.notFound(
                        "译文不存在: " + segmentId + "/" + normalizedLanguage));
        repository.deleteApproval(documentId, segmentId, normalizedLanguage);
        repository.deleteTranslation(documentId, segmentId, normalizedLanguage);
        int draftVersion = bumpDraftVersion(document);
        return new ApiDtos.WithdrawResponse(documentId, segmentId, normalizedLanguage, draftVersion);
    }

    /**
     * 缺失段诊断查询：按当前状态预估发布，返回全部回退链均无法解析出已批准译文的
     * 段落与目标语种（含已尝试语种），按 segmentId、语言稳定排序；不修改任何数据。
     */
    @Transactional(readOnly = true)
    public ApiDtos.MissingDiagnosticsResponse getMissingDiagnostics(long documentId) {
        DocumentRow document = repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        Resolution resolution = resolveDocument(document);
        return new ApiDtos.MissingDiagnosticsResponse(documentId, document.draftVersion(),
                resolution.missing());
    }

    /**
     * 发布：校验期望版本（不符 409），再对每个源段的每个目标语种先读取该语种已批准译文，
     * 缺失时沿回退链逐级查找首个已批准译文；回退链全部缺失的源段使整次发布 422，
     * 稳定排序返回缺失段标识与已尝试语种，不产生部分快照；
     * 解析结果按实际使用语种复核当前术语规则（违规 422 并返回全部违规术语），
     * 全部通过后原子生成完整只读快照（固化回退链、逐段实际使用语种与译文版本、
     * 术语版本与实际规则集）并递增发布版本；任何失败回滚。
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
        Resolution resolution = resolveDocument(document);
        if (!resolution.missing().isEmpty()) {
            throw ApiException.missingSegments(
                    "回退链全部缺失，无法发布: " + resolution.missing().size() + " 个段落语种组合缺译",
                    resolution.missing());
        }
        if (!resolution.termViolations().isEmpty()) {
            throw ApiException.termViolation(
                    "译文违反 " + resolution.termViolations().size() + " 条术语规则", resolution.termViolations());
        }
        int publishedVersion = document.publishedVersion() + 1;
        repository.insertSnapshot(documentId, publishedVersion,
                buildSnapshotJson(document, publishedVersion, resolution));
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
     * 生成完整只读快照 JSON：全部段落源文及各目标语种的解析结果（固化实际使用语种、
     * 是否回退、译文版本、作者、审核人）、发布时回退链配置、术语版本与实际规则集。
     */
    private String buildSnapshotJson(DocumentRow document, int publishedVersion, Resolution resolution) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("documentId", document.documentId());
        snapshot.put("publishedVersion", publishedVersion);
        snapshot.put("draftVersion", document.draftVersion());
        snapshot.put("termVersion", document.termVersion());
        snapshot.put("targetLanguages", document.targetLanguages());
        List<Map<String, Object>> fallbackList = new ArrayList<>();
        for (ApiDtos.FallbackEntry entry : toFallbackEntries(resolution.fallbackMap())) {
            Map<String, Object> fallbackJson = new LinkedHashMap<>();
            fallbackJson.put("language", entry.language());
            fallbackJson.put("fallbackLanguage", entry.fallbackLanguage());
            fallbackList.add(fallbackJson);
        }
        snapshot.put("fallbacks", fallbackList);
        List<Map<String, Object>> termList = new ArrayList<>();
        for (TermRuleRow rule : resolution.termRules()) {
            Map<String, Object> termJson = new LinkedHashMap<>();
            termJson.put("sourceTerm", rule.sourceTerm());
            termJson.put("language", rule.language());
            termJson.put("requiredTranslation", rule.requiredTranslation());
            termList.add(termJson);
        }
        snapshot.put("terms", termList);
        List<Map<String, Object>> segmentList = new ArrayList<>();
        for (SegmentRow segment : resolution.segments()) {
            Map<String, Object> segmentJson = new LinkedHashMap<>();
            segmentJson.put("segmentId", segment.segmentId());
            segmentJson.put("sourceText", segment.sourceText());
            segmentJson.put("sourceVersion", segment.sourceVersion());
            List<Map<String, Object>> translationList = new ArrayList<>();
            for (String language : document.targetLanguages()) {
                ResolvedTranslation resolved = resolution.resolved().get(key(segment.segmentId(), language));
                TranslationRow translation = resolved.translation();
                Map<String, Object> translationJson = new LinkedHashMap<>();
                translationJson.put("language", language);
                translationJson.put("usedLanguage", resolved.usedLanguage());
                translationJson.put("fallbackUsed", !resolved.usedLanguage().equals(language));
                translationJson.put("content", translation.content());
                translationJson.put("author", translation.author());
                translationJson.put("translationVersion", translation.translationVersion());
                translationJson.put("sourceVersion", translation.sourceVersion());
                translationJson.put("termVersion", translation.termVersion());
                translationJson.put("reviewer", resolved.approval().reviewer());
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

    /** 解析结果：逐（段落, 目标语种）的实际使用译文、缺失诊断、术语违规与解析所用数据。 */
    private record Resolution(List<SegmentRow> segments, Map<String, String> fallbackMap,
                              List<TermRuleRow> termRules, Map<String, ResolvedTranslation> resolved,
                              List<ApiDtos.MissingSegmentView> missing,
                              List<ApiDtos.TermRuleView> termViolations) {
    }

    /** 单个（段落, 目标语种）的解析结果：实际使用语种及其已批准译文与批准。 */
    private record ResolvedTranslation(String usedLanguage, TranslationRow translation, ApprovalRow approval) {
    }

    /**
     * 按当前数据库状态解析文档全部段落与目标语种：对每个（段落, 目标语种）先查直接语种的
     * 已批准译文（译文存在、源文版本与术语版本均为当前、批准匹配当前译文与源文版本），
     * 缺失时沿回退链逐级查找首个已批准译文；链全部缺失记入缺失诊断（含已尝试语种），
     * 按 segmentId、语言稳定排序；解析成功的译文按实际使用语种复核当前术语规则。
     */
    private Resolution resolveDocument(DocumentRow document) {
        long documentId = document.documentId();
        List<SegmentRow> segments = repository.listSegments(documentId);
        Map<String, TranslationRow> translations = repository.listTranslations(documentId).stream()
                .collect(Collectors.toMap(t -> key(t.segmentId(), t.language()), Function.identity()));
        Map<String, ApprovalRow> approvals = repository.listApprovals(documentId).stream()
                .collect(Collectors.toMap(a -> key(a.segmentId(), a.language()), Function.identity()));
        List<TermRuleRow> termRules = repository.listTermRules(documentId, document.termVersion());
        Map<String, String> fallbackMap = fallbackMap(documentId);
        Map<String, ResolvedTranslation> resolved = new HashMap<>();
        List<ApiDtos.MissingSegmentView> missing = new ArrayList<>();
        List<ApiDtos.TermRuleView> termViolations = new ArrayList<>();
        for (SegmentRow segment : segments) {
            for (String language : document.targetLanguages()) {
                List<String> attempted = new ArrayList<>();
                ResolvedTranslation resolvedOne = resolveOne(segment, language, translations, approvals,
                        document.termVersion(), fallbackMap, attempted);
                if (resolvedOne == null) {
                    missing.add(new ApiDtos.MissingSegmentView(segment.segmentId(), language,
                            List.copyOf(attempted)));
                } else {
                    resolved.put(key(segment.segmentId(), language), resolvedOne);
                    termViolations.addAll(findViolations(segment.sourceText(), resolvedOne.usedLanguage(),
                            resolvedOne.translation().content(), termRules));
                }
            }
        }
        missing.sort(Comparator.comparing(ApiDtos.MissingSegmentView::segmentId)
                .thenComparing(ApiDtos.MissingSegmentView::language));
        return new Resolution(segments, fallbackMap, termRules, resolved, missing, termViolations);
    }

    /**
     * 解析单个（段落, 目标语种）：从目标语种出发沿回退链逐级查找首个已批准译文，
     * 途经语种按顺序记入 attempted；找不到返回 null。直接译文优先于任意回退译文。
     */
    private static ResolvedTranslation resolveOne(SegmentRow segment, String requestedLanguage,
                                                  Map<String, TranslationRow> translations,
                                                  Map<String, ApprovalRow> approvals, int termVersion,
                                                  Map<String, String> fallbackMap, List<String> attempted) {
        String current = requestedLanguage;
        Set<String> visited = new HashSet<>();
        while (current != null && visited.add(current)) {
            attempted.add(current);
            TranslationRow translation = translations.get(key(segment.segmentId(), current));
            if (translation != null && translation.sourceVersion() == segment.sourceVersion()
                    && translation.termVersion() == termVersion) {
                ApprovalRow approval = approvals.get(key(segment.segmentId(), current));
                if (approval != null && approval.translationVersion() == translation.translationVersion()
                        && approval.sourceVersion() == segment.sourceVersion()) {
                    return new ResolvedTranslation(current, translation, approval);
                }
            }
            current = fallbackMap.get(current);
        }
        return null;
    }

    /** 查询文档回退配置并转为 语言 → 回退语种 映射。 */
    private Map<String, String> fallbackMap(long documentId) {
        return repository.listFallbacks(documentId).stream()
                .collect(Collectors.toMap(FallbackRow::language, FallbackRow::fallbackLanguage,
                        (a, b) -> a, LinkedHashMap::new));
    }

    /** 校验回退配置整体无环：从每个语种出发沿链行走，重复访问即成环（422）。 */
    private static void assertAcyclic(Map<String, String> fallbacks) {
        for (String start : fallbacks.keySet()) {
            Set<String> visited = new HashSet<>();
            String current = start;
            while (current != null) {
                if (!visited.add(current)) {
                    throw ApiException.unprocessable("回退链成环: " + start);
                }
                current = fallbacks.get(current);
            }
        }
    }

    /** 回退配置映射转为按语言排序的条目列表，保证稳定输出。 */
    private static List<ApiDtos.FallbackEntry> toFallbackEntries(Map<String, String> fallbacks) {
        return fallbacks.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new ApiDtos.FallbackEntry(entry.getKey(), entry.getValue()))
                .toList();
    }
}
