package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiDtos;
import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.domain.Rows.ApprovalRow;
import com.example.starter.translation.domain.Rows.DocumentRow;
import com.example.starter.translation.domain.Rows.GlobalTermRuleRow;
import com.example.starter.translation.domain.Rows.SegmentRow;
import com.example.starter.translation.domain.Rows.TermRuleRow;
import com.example.starter.translation.domain.Rows.TranslationRow;
import com.example.starter.translation.repo.TranslationRepository;
import com.example.starter.translation.service.GlossaryComposer.EffectiveRule;
import com.example.starter.translation.service.GlossaryComposer.Source;
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
 * 所有文档写操作先对文档行加 FOR UPDATE 行锁，全局术语库写操作对全局锁行加锁，
 * 保证并发下对应一个一致的术语合成状态与发布快照；方法均加入调用方事务，与幂等记录原子提交。
 */
@Service
public class TranslationService {

    private final TranslationRepository repository;
    private final ObjectMapper objectMapper;

    public TranslationService(TranslationRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /** 建文档：1~5 种目标语言，可携带初始段落，初始草稿版本 1、发布版本 0、两个术语版本均为 0。 */
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
     * 译文提交：所依据源文版本必须等于当前源文版本；基于当前引用全局版本与文档术语版本合成的生效规则集校验，
     * 当前源文命中的生效规则要求译文包含对应必译文本（SUPPRESSED 不参与校验），
     * 否则 422 返回全部违规术语及来源且不写译文；译文版本递增，草稿版本加一。
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
        List<EffectiveRule> effectiveRules = loadEffectiveRules(document);
        List<ApiDtos.TermViolationView> violations = findViolations(
                segment.sourceText(), normalizedLanguage, request.content(), effectiveRules);
        if (!violations.isEmpty()) {
            throw ApiException.termViolation("译文违反 " + violations.size() + " 条术语规则", violations);
        }
        int translationVersion = repository.findTranslation(documentId, segmentId, normalizedLanguage)
                .map(TranslationRow::translationVersion).orElse(0) + 1;
        repository.upsertTranslation(documentId, new TranslationRow(segmentId, normalizedLanguage,
                request.content(), actorId, segment.sourceVersion(), translationVersion,
                document.termVersion(), document.globalTermVersion()));
        int draftVersion = bumpDraftVersion(document);
        return new ApiDtos.TranslationResponse(documentId, segmentId, normalizedLanguage,
                translationVersion, segment.sourceVersion(), document.termVersion(),
                document.globalTermVersion(), draftVersion);
    }

    /**
     * 译文批准：审核人不得是作者，必须同时匹配当前源文与译文版本。
     * 术语过期（文档术语更新或全局引用落后）不阻止批准本身，但该批准在发布校验时不满足条件，
     * 须重新提交译文并重新批准后方可发布。
     */
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
     * 新增文档术语版本：expectedTermVersion 必须等于当前文档术语版本（不符 409）；
     * 规则 0~100 条、按 sourceTerm 与目标语言唯一、语言须在文档目标语言中（不符 422）；
     * suppressed 规则不得携带 requiredTranslation，普通规则必须携带（不符 400/422）。
     * 成功后文档术语版本加一（已有版本不可覆盖），草稿版本加一；术语快照与草稿版本同一事务提交。
     */
    @Transactional
    public ApiDtos.TermVersionResponse updateTerms(long documentId, ApiDtos.UpdateTermsRequest request) {
        DocumentRow document = lockDocument(documentId);
        if (document.termVersion() != request.expectedTermVersion()) {
            throw ApiException.conflict("文档术语版本冲突：当前术语版本 " + document.termVersion()
                    + "，与期望的 " + request.expectedTermVersion() + " 不一致");
        }
        List<TermRuleRow> rules = new ArrayList<>();
        Set<List<String>> seen = new HashSet<>();
        for (ApiDtos.TermRuleInput input : request.rules()) {
            String normalizedLanguage = normalizeLanguage(input.language());
            if (!document.targetLanguages().contains(normalizedLanguage)) {
                throw ApiException.unprocessable("术语规则语言不在文档目标语言中: " + normalizedLanguage);
            }
            if (!seen.add(List.of(input.sourceTerm(), normalizedLanguage))) {
                throw ApiException.unprocessable(
                        "术语规则重复: " + input.sourceTerm() + "/" + normalizedLanguage);
            }
            boolean suppressed = input.suppressed();
            String required = input.requiredTranslation();
            if (suppressed) {
                if (required != null && !required.isBlank()) {
                    throw ApiException.unprocessable("suppressed 术语规则不能携带 requiredTranslation: "
                            + input.sourceTerm() + "/" + normalizedLanguage);
                }
                rules.add(new TermRuleRow(input.sourceTerm(), normalizedLanguage, null, true));
            } else {
                if (required == null || required.isBlank()) {
                    throw ApiException.unprocessable("requiredTranslation 不能为空: "
                            + input.sourceTerm() + "/" + normalizedLanguage);
                }
                rules.add(new TermRuleRow(input.sourceTerm(), normalizedLanguage, required, false));
            }
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
     * 新增全局术语库版本：全局唯一版本，从 1 起递增，每版为不可变快照；
     * expectedGlobalTermVersion 必须等于当前全局版本（不符 409），规则 0~200 条、
     * 按 sourceTerm 与目标语言唯一（不符 422）。全局术语库不属于任何文档，其更新不改变任何文档草稿版本。
     */
    @Transactional
    public ApiDtos.GlobalTermVersionResponse createGlobalTerms(ApiDtos.CreateGlobalTermsRequest request) {
        repository.lockGlobalGlossary();
        int current = repository.currentGlobalTermVersion();
        if (current != request.expectedGlobalTermVersion()) {
            throw ApiException.conflict("全局术语版本冲突：当前全局术语版本 " + current
                    + "，与期望的 " + request.expectedGlobalTermVersion() + " 不一致");
        }
        List<GlobalTermRuleRow> rules = new ArrayList<>();
        Set<List<String>> seen = new HashSet<>();
        for (ApiDtos.GlobalTermRuleInput input : request.rules()) {
            String normalizedLanguage = normalizeLanguage(input.language());
            if (!seen.add(List.of(input.sourceTerm(), normalizedLanguage))) {
                throw ApiException.unprocessable(
                        "全局术语规则重复: " + input.sourceTerm() + "/" + normalizedLanguage);
            }
            rules.add(new GlobalTermRuleRow(0, input.sourceTerm(), normalizedLanguage,
                    input.requiredTranslation()));
        }
        if (rules.size() > 200) {
            throw ApiException.unprocessable("全局术语规则最多 200 条");
        }
        int globalTermVersion = repository.insertGlobalTermVersion();
        for (GlobalTermRuleRow rule : rules) {
            repository.insertGlobalTermRule(globalTermVersion,
                    new GlobalTermRuleRow(globalTermVersion, rule.sourceTerm(), rule.language(),
                            rule.requiredTranslation()));
        }
        return new ApiDtos.GlobalTermVersionResponse(globalTermVersion, rules.size());
    }

    /** 查询指定全局术语版本的不可变规则集；版本不存在返回 404。 */
    @Transactional(readOnly = true)
    public ApiDtos.GlobalTermVersionView getGlobalTerms(int globalTermVersion) {
        if (globalTermVersion < 1 || !repository.globalTermVersionExists(globalTermVersion)) {
            throw ApiException.notFound("全局术语版本不存在: " + globalTermVersion);
        }
        List<ApiDtos.GlobalTermRuleView> rules = repository.listGlobalTermRules(globalTermVersion).stream()
                .map(rule -> new ApiDtos.GlobalTermRuleView(rule.sourceTerm(), rule.language(),
                        rule.requiredTranslation()))
                .toList();
        return new ApiDtos.GlobalTermVersionView(globalTermVersion, rules);
    }

    /**
     * 显式提交全局术语引用升级：expectedGlobalTermVersion 必须等于文档当前引用版本，
     * targetGlobalTermVersion 必须存在且严格大于当前引用版本（不符 409/422/404）。
     * 升级后既有译文与批准因绑定旧全局版本而不再满足发布条件；升级使 draftVersion 加一。
     */
    @Transactional
    public ApiDtos.GlobalReferenceResponse upgradeGlobalReference(
            long documentId, ApiDtos.UpgradeGlobalReferenceRequest request) {
        DocumentRow document = lockDocument(documentId);
        if (document.globalTermVersion() != request.expectedGlobalTermVersion()) {
            throw ApiException.conflict("全局术语引用冲突：文档当前引用全局术语版本 "
                    + document.globalTermVersion() + "，与期望的 "
                    + request.expectedGlobalTermVersion() + " 不一致");
        }
        if (request.targetGlobalTermVersion() <= document.globalTermVersion()) {
            throw ApiException.unprocessable("目标全局术语版本 " + request.targetGlobalTermVersion()
                    + " 必须大于当前引用版本 " + document.globalTermVersion());
        }
        if (!repository.globalTermVersionExists(request.targetGlobalTermVersion())) {
            throw ApiException.notFound(
                    "全局术语版本不存在: " + request.targetGlobalTermVersion());
        }
        repository.updateGlobalTermVersion(documentId, request.targetGlobalTermVersion());
        int draftVersion = bumpDraftVersion(document);
        return new ApiDtos.GlobalReferenceResponse(documentId, request.targetGlobalTermVersion(),
                document.termVersion(), draftVersion);
    }

    /**
     * 查询文档当前生效术语规则集：文档引用的全局版本快照与文档术语版本快照合成，
     * 逐条标明来源 GLOBAL/DOCUMENT/SUPPRESSED，按 sourceTerm 与目标语言升序稳定排序。
     */
    @Transactional(readOnly = true)
    public ApiDtos.EffectiveTermSetResponse getEffectiveTerms(long documentId) {
        DocumentRow document = repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        List<ApiDtos.EffectiveTermRuleView> rules = loadEffectiveRules(document).stream()
                .map(rule -> new ApiDtos.EffectiveTermRuleView(rule.sourceTerm(), rule.language(),
                        rule.requiredTranslation(), rule.source().name()))
                .toList();
        return new ApiDtos.EffectiveTermSetResponse(documentId, document.globalTermVersion(),
                document.termVersion(), rules);
    }

    /**
     * 发布：校验期望版本（不符 409），再校验全部段落在全部目标语言均有有效批准（缺译或审核失效 422）、
     * 译文绑定当前两个术语版本（任一过期 422）且满足当前生效术语规则（违规 422 并返回全部违规术语及来源），
     * 全部通过后原子生成完整只读快照（固化两个术语版本与实际生效规则集）并递增发布版本；
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
        // 锁定全局术语库串行点并读取最新全局版本：引用落后即拒绝发布，与全局更新并发按提交顺序裁决
        repository.lockGlobalGlossary();
        int latestGlobalTermVersion = repository.currentGlobalTermVersion();
        if (document.globalTermVersion() != latestGlobalTermVersion) {
            throw ApiException.unprocessable("文档引用的全局术语版本已落后: 当前引用 "
                    + document.globalTermVersion() + "，最新全局术语版本 " + latestGlobalTermVersion
                    + "，须显式提交引用升级后重新提交译文并批准");
        }
        List<EffectiveRule> effectiveRules = loadEffectiveRules(document);
        List<ApiDtos.TermViolationView> termViolations = new ArrayList<>();
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
                if (translation.termVersion() != document.termVersion()
                        || translation.globalTermVersion() != document.globalTermVersion()) {
                    throw ApiException.unprocessable("译文术语版本过期: " + segment.segmentId() + "/" + language
                            + " 绑定文档术语版本 " + translation.termVersion() + "/全局术语版本 "
                            + translation.globalTermVersion() + "，当前为 " + document.termVersion() + "/"
                            + document.globalTermVersion());
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
                        segment.sourceText(), language, translation.content(), effectiveRules));
            }
        }
        if (!termViolations.isEmpty()) {
            throw ApiException.termViolation("译文违反 " + termViolations.size() + " 条术语规则", termViolations);
        }
        int publishedVersion = document.publishedVersion() + 1;
        repository.insertSnapshot(documentId, publishedVersion,
                buildSnapshotJson(document, publishedVersion, segments, translations, approvals, effectiveRules));
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

    /** 查询当前文档术语版本及完整原始规则集（含 suppressed 标记）；尚未建立术语版本时返回版本 0 与空规则。 */
    @Transactional(readOnly = true)
    public ApiDtos.TermVersionView getCurrentTerms(long documentId) {
        DocumentRow document = repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        return new ApiDtos.TermVersionView(documentId, document.termVersion(),
                toRuleViews(repository.listTermRules(documentId, document.termVersion())));
    }

    /** 查询指定文档术语版本的不可变规则集；版本不存在返回 404。 */
    @Transactional(readOnly = true)
    public ApiDtos.TermVersionView getTerms(long documentId, int termVersion) {
        repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        if (termVersion < 1 || !repository.termVersionExists(documentId, termVersion)) {
            throw ApiException.notFound("文档术语版本不存在: " + documentId + "/" + termVersion);
        }
        return new ApiDtos.TermVersionView(documentId, termVersion,
                toRuleViews(repository.listTermRules(documentId, termVersion)));
    }

    /**
     * 查询全部译文的术语状态：绑定的两个术语版本、是否相对当前引用版本过期，
     * 以及按当前源文与当前生效规则判定的违规术语及来源（无违规为空列表）。
     */
    @Transactional(readOnly = true)
    public ApiDtos.TermStatusResponse getTermStatus(long documentId) {
        DocumentRow document = repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        Map<String, SegmentRow> segments = repository.listSegments(documentId).stream()
                .collect(Collectors.toMap(SegmentRow::segmentId, Function.identity()));
        List<EffectiveRule> effectiveRules = loadEffectiveRules(document);
        int latestGlobalTermVersion = repository.currentGlobalTermVersion();
        boolean referenceBehind = document.globalTermVersion() != latestGlobalTermVersion;
        List<ApiDtos.TranslationTermStatus> statuses = new ArrayList<>();
        for (TranslationRow translation : repository.listTranslations(documentId)) {
            SegmentRow segment = segments.get(translation.segmentId());
            List<ApiDtos.TermViolationView> violations = segment == null ? List.of()
                    : findViolations(segment.sourceText(), translation.language(),
                            translation.content(), effectiveRules);
            boolean stale = referenceBehind
                    || translation.termVersion() != document.termVersion()
                    || translation.globalTermVersion() != document.globalTermVersion();
            statuses.add(new ApiDtos.TranslationTermStatus(translation.segmentId(), translation.language(),
                    translation.translationVersion(), translation.termVersion(),
                    translation.globalTermVersion(), stale, violations));
        }
        return new ApiDtos.TermStatusResponse(documentId, document.globalTermVersion(),
                document.termVersion(), statuses);
    }

    private DocumentRow lockDocument(long documentId) {
        return repository.findDocumentForUpdate(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
    }

    /** 加载文档当前引用的两个不可变快照并合成生效规则集（含 SUPPRESSED 条目）。 */
    private List<EffectiveRule> loadEffectiveRules(DocumentRow document) {
        List<GlobalTermRuleRow> globalRules = document.globalTermVersion() == 0
                ? List.of() : repository.listGlobalTermRules(document.globalTermVersion());
        List<TermRuleRow> documentRules = document.termVersion() == 0
                ? List.of() : repository.listTermRules(document.documentId(), document.termVersion());
        return GlossaryComposer.compose(globalRules, documentRules, Set.copyOf(document.targetLanguages()));
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
     * 术语违规判定：源文按 Unicode 原文、区分大小写做连续子串匹配；仅源文命中 sourceTerm 的生效规则参与校验，
     * SUPPRESSED 条目不参与；译文正文（同样区分大小写）不含 requiredTranslation 即为违规。返回全部违规规则及来源。
     */
    private static List<ApiDtos.TermViolationView> findViolations(String sourceText, String language, String content,
                                                                  List<EffectiveRule> rules) {
        List<ApiDtos.TermViolationView> violations = new ArrayList<>();
        for (EffectiveRule rule : rules) {
            if (rule.source() == Source.SUPPRESSED) {
                continue;
            }
            if (rule.language().equals(language) && sourceText.contains(rule.sourceTerm())
                    && !content.contains(rule.requiredTranslation())) {
                violations.add(new ApiDtos.TermViolationView(rule.sourceTerm(), rule.language(),
                        rule.requiredTranslation(), rule.source().name()));
            }
        }
        return violations;
    }

    private static List<ApiDtos.TermRuleView> toRuleViews(List<TermRuleRow> rules) {
        return rules.stream()
                .map(rule -> new ApiDtos.TermRuleView(rule.sourceTerm(), rule.language(),
                        rule.requiredTranslation(), rule.suppressed()))
                .toList();
    }

    /** 生成完整只读快照 JSON：全部段落源文及各语言译文、作者、审核人、版本号与固化的两个术语版本及实际生效规则集。 */
    private String buildSnapshotJson(DocumentRow document, int publishedVersion, List<SegmentRow> segments,
                                     Map<String, TranslationRow> translations,
                                     Map<String, ApprovalRow> approvals, List<EffectiveRule> effectiveRules) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("documentId", document.documentId());
        snapshot.put("publishedVersion", publishedVersion);
        snapshot.put("draftVersion", document.draftVersion());
        snapshot.put("termVersion", document.termVersion());
        snapshot.put("globalTermVersion", document.globalTermVersion());
        snapshot.put("targetLanguages", document.targetLanguages());
        List<Map<String, Object>> termList = new ArrayList<>();
        for (EffectiveRule rule : effectiveRules) {
            Map<String, Object> termJson = new LinkedHashMap<>();
            termJson.put("sourceTerm", rule.sourceTerm());
            termJson.put("language", rule.language());
            termJson.put("requiredTranslation", rule.requiredTranslation());
            termJson.put("source", rule.source().name());
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
                translationJson.put("globalTermVersion", translation.globalTermVersion());
                translationJson.put("reviewer", approval.reviewer());
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
