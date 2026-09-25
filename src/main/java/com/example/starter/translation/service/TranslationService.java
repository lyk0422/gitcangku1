package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiDtos;
import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.domain.Rows.ApprovalRow;
import com.example.starter.translation.domain.Rows.DocumentRow;
import com.example.starter.translation.domain.Rows.FallbackRecordRow;
import com.example.starter.translation.domain.Rows.RegionVariantRow;
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
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 多语种段落修订与发布快照的核心业务服务。
 * 所有写操作先对文档行加 FOR UPDATE 行锁，保证同一文档的修改、审核与发布串行，
 * 并发下对应一个一致的文档状态；方法均加入调用方事务，与幂等记录原子提交。
 */
@Service
public class TranslationService {

    /** 全局默认区域码；基线译文即为 DEFAULT 译文。 */
    public static final String DEFAULT_REGION = "DEFAULT";
    /** 回退来源：直接使用具体区域有效变体。 */
    private static final String REGION = "REGION";
    /** 回退来源：具体区域缺失，回退 DEFAULT。 */
    private static final String DEFAULT_FALLBACK = "DEFAULT";
    /** 回退来源：具体区域与 DEFAULT 均无有效译文。 */
    private static final String NO_FALLBACK = "NONE";

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_REVOKED = "REVOKED";

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
     * 发布：校验期望版本（不符 409），在同一事务内读取一致的段落、基线译文、批准、区域变体与术语版本集合。
     * region 为 DEFAULT 时沿用基线发布；为具体区域时每个段落/语言优先使用该区域有效变体，缺失回退 DEFAULT；
     * 若具体区域与 DEFAULT 均无有效译文，整次发布 422 并稳定排序返回缺失段落，既有发布版本不变。
     * 成功后原子生成只读快照（固化每段落最终译文版本、区域码与回退来源）并递增发布版本。
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
        String region = normalizeRegion(request.region());
        List<SegmentRow> segments = repository.listSegments(documentId);
        Map<String, TranslationRow> translations = repository.listTranslations(documentId).stream()
                .collect(Collectors.toMap(t -> key(t.segmentId(), t.language()), Function.identity()));
        Map<String, ApprovalRow> approvals = repository.listApprovals(documentId).stream()
                .collect(Collectors.toMap(a -> key(a.segmentId(), a.language()), Function.identity()));
        List<TermRuleRow> termRules = repository.listTermRules(documentId, document.termVersion());
        List<ApiDtos.TermRuleView> termViolations = new ArrayList<>();

        Map<String, SelectedTranslation> selected;
        List<FallbackRecordRow> fallbacks;
        if (DEFAULT_REGION.equals(region)) {
            // DEFAULT 发布：沿用既有基线校验，缺译/批准失效/术语过期立即失败。
            for (SegmentRow segment : segments) {
                for (String language : document.targetLanguages()) {
                    TranslationRow translation = requireBaseline(
                            document, segment, language, translations, approvals);
                    termViolations.addAll(findViolations(
                            segment.sourceText(), language, translation.content(), termRules));
                }
            }
            selected = Map.of();
            fallbacks = List.of();
        } else {
            // 具体区域发布：逐段落/语言解析区域覆盖，收集全部缺失段落后统一 422。
            Map<String, RegionVariantRow> activeVariants = activeVariantsByKey(
                    repository.listRegionVariants(documentId), document, segments);
            selected = new LinkedHashMap<>();
            fallbacks = new ArrayList<>();
            Set<String> missing = new TreeSet<>();
            for (SegmentRow segment : segments) {
                for (String language : document.targetLanguages()) {
                    String mapKey = key(segment.segmentId(), language);
                    TranslationRow baseline = translations.get(mapKey);
                    ApprovalRow approval = approvals.get(mapKey);
                    RegionVariantRow variant = activeVariants.get(variantKey(
                            segment.segmentId(), language, region));
                    boolean baselineValid = baseline != null
                            && isBaselineEffective(document, segment, baseline, approval);
                    if (variant != null) {
                        SelectedTranslation chosen = new SelectedTranslation(
                                variant.content(), variant.author(), variant.reviewer(),
                                variant.translationVersion(), variant.sourceVersion(),
                                variant.termVersion(), region, REGION);
                        selected.put(mapKey, chosen);
                        termViolations.addAll(findViolations(
                                segment.sourceText(), language, variant.content(), termRules));
                    } else if (baselineValid) {
                        selected.put(mapKey, new SelectedTranslation(
                                baseline.content(), baseline.author(), approval.reviewer(),
                                baseline.translationVersion(), baseline.sourceVersion(),
                                baseline.termVersion(), DEFAULT_REGION, DEFAULT_FALLBACK));
                        fallbacks.add(new FallbackRecordRow(0, segment.segmentId(), language,
                                region, baseline.translationVersion()));
                        termViolations.addAll(findViolations(
                                segment.sourceText(), language, baseline.content(), termRules));
                    } else {
                        missing.add(segment.segmentId());
                    }
                }
            }
            if (!missing.isEmpty()) {
                throw ApiException.missingSegments(
                        "区域 " + region + " 与 DEFAULT 均无有效译文的段落: " + String.join(",", missing),
                        new ArrayList<>(missing));
            }
        }
        if (!termViolations.isEmpty()) {
            throw ApiException.termViolation("译文违反 " + termViolations.size() + " 条术语规则", termViolations);
        }
        int publishedVersion = document.publishedVersion() + 1;
        repository.insertSnapshot(documentId, publishedVersion,
                buildSnapshotJson(document, publishedVersion, region, segments, translations, approvals,
                        termRules, selected, fallbacks));
        for (FallbackRecordRow fallback : fallbacks) {
            repository.insertFallbackRecord(documentId, publishedVersion, fallback);
        }
        repository.updatePublishedVersion(documentId, publishedVersion);
        return new ApiDtos.PublishResponse(documentId, publishedVersion, region);
    }

    /** DEFAULT 发布时校验并返回某段落/语言的有效基线译文，否则 422。 */
    private TranslationRow requireBaseline(DocumentRow document, SegmentRow segment, String language,
                                           Map<String, TranslationRow> translations,
                                           Map<String, ApprovalRow> approvals) {
        TranslationRow translation = translations.get(key(segment.segmentId(), language));
        if (translation == null) {
            throw ApiException.unprocessable("缺少译文: " + segment.segmentId() + "/" + language);
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
            throw ApiException.unprocessable("缺少批准: " + segment.segmentId() + "/" + language);
        }
        if (approval.translationVersion() != translation.translationVersion()
                || approval.sourceVersion() != segment.sourceVersion()) {
            throw ApiException.unprocessable("批准已失效: " + segment.segmentId() + "/" + language
                    + "，源文或译文版本已改变");
        }
        return translation;
    }

    /** 判断基线译文在当前文档状态下是否有效（源文/术语版本一致且批准有效）。 */
    private boolean isBaselineEffective(DocumentRow document, SegmentRow segment, TranslationRow baseline,
                                        ApprovalRow approval) {
        return baseline.sourceVersion() == segment.sourceVersion()
                && baseline.termVersion() == document.termVersion()
                && approval != null
                && approval.translationVersion() == baseline.translationVersion()
                && approval.sourceVersion() == segment.sourceVersion();
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
     * 登记区域译文变体：区域为具体区域码（非 DEFAULT），语言须在文档目标语言中；
     * 基线译文必须存在、已有效批准且其译文版本等于 expectedVersion（不符 409）；
     * 同段落/语言/区域/译文版本只能有一条变体（重复 409）。登记后为 PENDING，需独立批准。
     */
    @Transactional
    public ApiDtos.VariantResponse createVariant(long documentId, String segmentId, String language,
                                                 String region, ApiDtos.CreateVariantRequest request) {
        DocumentRow document = lockDocument(documentId);
        String normalizedLanguage = normalizeLanguage(language);
        String normalizedRegion = normalizeConcreteRegion(region);
        if (!document.targetLanguages().contains(normalizedLanguage)) {
            throw ApiException.unprocessable("语言不在文档目标语言中: " + normalizedLanguage);
        }
        findSegmentOrThrow(documentId, segmentId);
        TranslationRow baseline = repository.findTranslation(documentId, segmentId, normalizedLanguage)
                .orElseThrow(() -> ApiException.notFound(
                        "基线译文不存在: " + segmentId + "/" + normalizedLanguage));
        if (baseline.translationVersion() != request.expectedVersion()) {
            throw ApiException.conflict("变体登记冲突：当前基线译文版本 " + baseline.translationVersion()
                    + "，与期望的 " + request.expectedVersion() + " 不一致");
        }
        ApprovalRow approval = repository.findApproval(documentId, segmentId, normalizedLanguage)
                .orElseThrow(() -> ApiException.unprocessable(
                        "基线译文尚未批准，不能登记区域变体: " + segmentId + "/" + normalizedLanguage));
        if (approval.translationVersion() != baseline.translationVersion()) {
            throw ApiException.unprocessable(
                    "基线译文批准已失效，不能登记区域变体: " + segmentId + "/" + normalizedLanguage);
        }
        RegionVariantRow row = new RegionVariantRow(segmentId, normalizedLanguage, normalizedRegion,
                baseline.translationVersion(), baseline.content(), baseline.author(), null,
                baseline.sourceVersion(), baseline.termVersion(), STATUS_PENDING);
        try {
            repository.insertRegionVariant(documentId, row);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw ApiException.conflict("区域变体已存在: " + segmentId + "/" + normalizedLanguage
                    + "/" + normalizedRegion + "@" + baseline.translationVersion());
        }
        int draftVersion = bumpDraftVersion(document);
        return new ApiDtos.VariantResponse(documentId, segmentId, normalizedLanguage, normalizedRegion,
                baseline.translationVersion(), STATUS_PENDING, draftVersion);
    }

    /**
     * 批准区域变体：审核人取 X-Actor-Id 且不得是基线译文作者；expectedVersion 必须等于变体所依据的
     * 基线译文版本（不符 409）。批准后同区域更早的待批准/有效变体置为 SUPERSEDED。
     */
    @Transactional
    public ApiDtos.VariantResponse approveVariant(long documentId, String segmentId, String language,
                                                  String region, String actorId,
                                                  ApiDtos.VariantVersionRequest request) {
        DocumentRow document = lockDocument(documentId);
        String normalizedLanguage = normalizeLanguage(language);
        String normalizedRegion = normalizeConcreteRegion(region);
        RegionVariantRow variant = requireVariant(
                documentId, segmentId, normalizedLanguage, normalizedRegion, request.expectedVersion());
        if (!STATUS_PENDING.equals(variant.status())) {
            throw ApiException.conflict("区域变体当前状态为 " + variant.status() + "，不能批准: "
                    + segmentId + "/" + normalizedLanguage + "/" + normalizedRegion);
        }
        if (variant.author().equals(actorId)) {
            throw ApiException.unprocessable("审核人不得是该基线译文作者");
        }
        int updated = repository.approveRegionVariant(documentId, segmentId, normalizedLanguage,
                normalizedRegion, request.expectedVersion(), actorId);
        if (updated == 0) {
            throw ApiException.conflict("区域变体状态已改变，批准冲突: "
                    + segmentId + "/" + normalizedLanguage + "/" + normalizedRegion);
        }
        repository.supersedeRegionVariants(documentId, segmentId, normalizedLanguage,
                normalizedRegion, request.expectedVersion());
        return new ApiDtos.VariantResponse(documentId, segmentId, normalizedLanguage, normalizedRegion,
                request.expectedVersion(), STATUS_ACTIVE, document.draftVersion());
    }

    /**
     * 撤销区域有效变体：expectedVersion 必须等于变体所依据的基线译文版本（不符 409）；
     * 仅 ACTIVE 变体可撤销。撤销后后续区域发布与查询自动回退 DEFAULT。
     */
    @Transactional
    public ApiDtos.VariantResponse revokeVariant(long documentId, String segmentId, String language,
                                                 String region, ApiDtos.VariantVersionRequest request) {
        DocumentRow document = lockDocument(documentId);
        String normalizedLanguage = normalizeLanguage(language);
        String normalizedRegion = normalizeConcreteRegion(region);
        requireVariant(documentId, segmentId, normalizedLanguage, normalizedRegion, request.expectedVersion());
        int updated = repository.revokeRegionVariant(documentId, segmentId, normalizedLanguage,
                normalizedRegion, request.expectedVersion());
        if (updated == 0) {
            throw ApiException.conflict("区域变体不是有效状态，不能撤销: "
                    + segmentId + "/" + normalizedLanguage + "/" + normalizedRegion
                    + "@" + request.expectedVersion());
        }
        return new ApiDtos.VariantResponse(documentId, segmentId, normalizedLanguage, normalizedRegion,
                request.expectedVersion(), STATUS_REVOKED, document.draftVersion());
    }

    /**
     * 区域覆盖解析（只读）：给定区域，逐段落/语言返回最终选用译文。
     * 具体区域有效变体优先，缺失回退 DEFAULT；DEFAULT 也无有效译文时标记 fallbackSource=NONE。
     */
    @Transactional(readOnly = true)
    public ApiDtos.RegionResolutionResponse resolveRegions(long documentId, String region) {
        DocumentRow document = repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        String normalizedRegion = normalizeRegion(region);
        List<SegmentRow> segments = repository.listSegments(documentId);
        Map<String, TranslationRow> translations = repository.listTranslations(documentId).stream()
                .collect(Collectors.toMap(t -> key(t.segmentId(), t.language()), Function.identity()));
        Map<String, ApprovalRow> approvals = repository.listApprovals(documentId).stream()
                .collect(Collectors.toMap(a -> key(a.segmentId(), a.language()), Function.identity()));
        Map<String, RegionVariantRow> activeVariants = DEFAULT_REGION.equals(normalizedRegion)
                ? Map.of() : activeVariantsByKey(repository.listRegionVariants(documentId), document, segments);
        List<ApiDtos.RegionResolutionItem> items = new ArrayList<>();
        for (SegmentRow segment : segments) {
            for (String language : document.targetLanguages()) {
                String mapKey = key(segment.segmentId(), language);
                TranslationRow baseline = translations.get(mapKey);
                ApprovalRow approval = approvals.get(mapKey);
                RegionVariantRow variant = activeVariants.get(variantKey(
                        segment.segmentId(), language, normalizedRegion));
                if (variant != null) {
                    items.add(new ApiDtos.RegionResolutionItem(segment.segmentId(), language,
                            normalizedRegion, normalizedRegion, REGION, variant.translationVersion(),
                            variant.content()));
                } else if (baseline != null && isBaselineEffective(document, segment, baseline, approval)) {
                    items.add(new ApiDtos.RegionResolutionItem(segment.segmentId(), language,
                            normalizedRegion, DEFAULT_REGION, DEFAULT_FALLBACK, baseline.translationVersion(),
                            baseline.content()));
                } else {
                    items.add(new ApiDtos.RegionResolutionItem(segment.segmentId(), language,
                            normalizedRegion, null, NO_FALLBACK, 0, null));
                }
            }
        }
        return new ApiDtos.RegionResolutionResponse(documentId, normalizedRegion, items);
    }

    /** 查询某具体区域的发布回退历史，按发布版本、段落、语言稳定排序；DEFAULT 区域返回空列表。 */
    @Transactional(readOnly = true)
    public ApiDtos.FallbackHistoryResponse getFallbackHistory(long documentId, String region) {
        repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        String normalizedRegion = normalizeConcreteRegion(region);
        List<ApiDtos.FallbackHistoryItem> items = repository.listFallbackRecords(documentId, normalizedRegion)
                .stream()
                .map(r -> new ApiDtos.FallbackHistoryItem(r.publishedVersion(), r.segmentId(), r.language(),
                        r.requestedRegion(), r.translationVersion()))
                .toList();
        return new ApiDtos.FallbackHistoryResponse(documentId, normalizedRegion, items);
    }

    private RegionVariantRow requireVariant(long documentId, String segmentId, String language, String region,
                                            int expectedVersion) {
        return repository.findRegionVariant(documentId, segmentId, language, region, expectedVersion)
                .orElseThrow(() -> ApiException.notFound("区域变体不存在: " + segmentId + "/" + language
                        + "/" + region + "@" + expectedVersion));
    }

    /**
     * 从全部变体中筛出当前有效（ACTIVE 且源文版本、术语版本与文档当前一致）的变体，
     * 同段落/语言/区域若存在多条 ACTIVE（理论上不会），取译文版本最大者。
     */
    private Map<String, RegionVariantRow> activeVariantsByKey(List<RegionVariantRow> variants,
                                                              DocumentRow document,
                                                              List<SegmentRow> segments) {
        Map<String, RegionVariantRow> active = new LinkedHashMap<>();
        List<RegionVariantRow> sorted = variants.stream()
                .filter(v -> STATUS_ACTIVE.equals(v.status()))
                .sorted(Comparator.comparingInt(RegionVariantRow::translationVersion))
                .toList();
        Map<String, SegmentRow> segmentMap = segments.stream()
                .collect(Collectors.toMap(SegmentRow::segmentId, Function.identity()));
        for (RegionVariantRow variant : sorted) {
            SegmentRow segment = segmentMap.get(variant.segmentId());
            if (segment != null && variant.sourceVersion() == segment.sourceVersion()
                    && variant.termVersion() == document.termVersion()) {
                active.put(variantKey(variant.segmentId(), variant.language(), variant.region()), variant);
            }
        }
        return active;
    }

    private static String variantKey(String segmentId, String language, String region) {
        return segmentId + " " + language + " " + region;
    }

    /** 归一化区域码：空白视为 DEFAULT；DEFAULT 原样返回。 */
    private static String normalizeRegion(String region) {
        if (region == null || region.isBlank()) {
            return DEFAULT_REGION;
        }
        String normalized = region.trim().toUpperCase(Locale.ROOT);
        if (DEFAULT_REGION.equals(normalized)) {
            return DEFAULT_REGION;
        }
        return normalized;
    }

    /** 归一化具体区域码：不允许 DEFAULT，长度 2~8 位大写字母/数字。 */
    private static String normalizeConcreteRegion(String region) {
        if (region == null || region.isBlank()) {
            throw ApiException.unprocessable("区域码不能为空");
        }
        String normalized = region.trim().toUpperCase(Locale.ROOT);
        if (DEFAULT_REGION.equals(normalized)) {
            throw ApiException.unprocessable("具体区域码不能为 DEFAULT，DEFAULT 为全局默认");
        }
        if (!normalized.matches("[A-Z0-9]{2,8}")) {
            throw ApiException.unprocessable("区域码须为 2~8 位大写字母或数字: " + normalized);
        }
        return normalized;
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
     * 生成完整只读快照 JSON：发布区域、全部段落源文及各语言最终选用译文（版本、区域码、回退来源）、
     * 作者、审核人、版本号与固化的术语版本及规则集。快照一经写入不再随变体新增/撤销/修订改变。
     */
    private String buildSnapshotJson(DocumentRow document, int publishedVersion, String requestedRegion,
                                     List<SegmentRow> segments,
                                     Map<String, TranslationRow> translations,
                                     Map<String, ApprovalRow> approvals, List<TermRuleRow> termRules,
                                     Map<String, SelectedTranslation> selected,
                                     List<FallbackRecordRow> fallbacks) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("documentId", document.documentId());
        snapshot.put("publishedVersion", publishedVersion);
        snapshot.put("region", requestedRegion);
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
        Set<String> fallbackKeys = fallbacks.stream()
                .map(f -> key(f.segmentId(), f.language())).collect(Collectors.toSet());
        List<Map<String, Object>> segmentList = new ArrayList<>();
        for (SegmentRow segment : segments) {
            Map<String, Object> segmentJson = new LinkedHashMap<>();
            segmentJson.put("segmentId", segment.segmentId());
            segmentJson.put("sourceText", segment.sourceText());
            segmentJson.put("sourceVersion", segment.sourceVersion());
            List<Map<String, Object>> translationList = new ArrayList<>();
            for (String language : document.targetLanguages()) {
                TranslationRow baseline = translations.get(key(segment.segmentId(), language));
                ApprovalRow approval = approvals.get(key(segment.segmentId(), language));
                SelectedTranslation chosen = selected.get(key(segment.segmentId(), language));
                Map<String, Object> translationJson = new LinkedHashMap<>();
                translationJson.put("language", language);
                if (chosen != null) {
                    translationJson.put("content", chosen.content());
                    translationJson.put("author", chosen.author());
                    translationJson.put("translationVersion", chosen.translationVersion());
                    translationJson.put("sourceVersion", chosen.sourceVersion());
                    translationJson.put("termVersion", chosen.termVersion());
                    translationJson.put("reviewer", chosen.reviewer());
                    translationJson.put("region", chosen.region());
                    translationJson.put("fallbackSource",
                            fallbackKeys.contains(key(segment.segmentId(), language)) ? DEFAULT_FALLBACK : REGION);
                } else {
                    translationJson.put("content", baseline.content());
                    translationJson.put("author", baseline.author());
                    translationJson.put("translationVersion", baseline.translationVersion());
                    translationJson.put("sourceVersion", baseline.sourceVersion());
                    translationJson.put("termVersion", baseline.termVersion());
                    translationJson.put("reviewer", approval.reviewer());
                    translationJson.put("region", DEFAULT_REGION);
                    translationJson.put("fallbackSource", DEFAULT_REGION);
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

    /**
     * 发布快照中某段落/语言最终选用的译文：区域变体或 DEFAULT 基线，固化版本、区域码与回退来源。
     */
    private record SelectedTranslation(String content, String author, String reviewer, int translationVersion,
                                       int sourceVersion, int termVersion, String region, String fallbackSource) {
    }
}
