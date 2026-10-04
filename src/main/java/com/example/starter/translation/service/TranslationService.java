package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiDtos;
import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.domain.Rows.AnnotationRow;
import com.example.starter.translation.domain.Rows.ApprovalRow;
import com.example.starter.translation.domain.Rows.DocumentRow;
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

    /** 批注状态：未解决。 */
    private static final String STATUS_OPEN = "OPEN";
    /** 批注状态：已孤立（编辑与锚点相交或纯插入落入内部），仅保留原引用。 */
    private static final String STATUS_ORPHAN = "ORPHAN";
    /** 批注状态：已解决。 */
    private static final String STATUS_RESOLVED = "RESOLVED";
    /** 每个段落+语言允许的未解决批注上限。 */
    private static final int MAX_UNRESOLVED_ANNOTATIONS = 20;

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
        if (repository.countUnresolvedAnnotations(documentId, segmentId, normalizedLanguage) > 0) {
            throw ApiException.conflict("存在未解决批注，普通全文替换被禁止，请使用定点编辑或先解决批注");
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
        if (repository.countUnresolvedAnnotations(documentId, segmentId, normalizedLanguage) > 0) {
            throw ApiException.unprocessable("存在未解决批注，不能批准: " + segmentId + "/" + normalizedLanguage);
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
        int unresolvedAnnotations = repository.countUnresolvedAnnotations(documentId);
        if (unresolvedAnnotations > 0) {
            throw ApiException.unprocessable("存在 " + unresolvedAnnotations + " 条未解决批注，不能发布");
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
        int publishedVersion = document.publishedVersion() + 1;
        repository.insertSnapshot(documentId, publishedVersion,
                buildSnapshotJson(document, publishedVersion, segments, translations, approvals, termRules));
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

    /** 生成完整只读快照 JSON：全部段落源文及各语言译文、作者、审核人、版本号与固化的术语版本及规则集。 */
    private String buildSnapshotJson(DocumentRow document, int publishedVersion, List<SegmentRow> segments,
                                     Map<String, TranslationRow> translations,
                                     Map<String, ApprovalRow> approvals, List<TermRuleRow> termRules) {
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
     * 新增批注：绑定当前译文版本与非空码点区间（从 0 计数、左闭右开），区间越界或为空 400；
     * 每个段落+语言未解决批注最多 20 条（超出 422）；保存创建时锚定的原文引用；
     * 新增批注使当前批准失效（删除该段落+语言的批准记录）。
     */
    @Transactional
    public ApiDtos.AnnotationView createAnnotation(long documentId, String segmentId, String language,
                                                   String actorId, ApiDtos.CreateAnnotationRequest request) {
        DocumentRow document = lockDocument(documentId);
        String normalizedLanguage = normalizeLanguage(language);
        if (!document.targetLanguages().contains(normalizedLanguage)) {
            throw ApiException.unprocessable("语言不在文档目标语言中: " + normalizedLanguage);
        }
        TranslationRow translation = repository.findTranslation(documentId, segmentId, normalizedLanguage)
                .orElseThrow(() -> ApiException.notFound(
                        "译文不存在，无法批注: " + segmentId + "/" + normalizedLanguage));
        int length = codePointCount(translation.content());
        if (request.start() >= request.end() || request.end() > length) {
            throw ApiException.badRequest("批注区间为空或越界: [" + request.start() + "," + request.end()
                    + ")，译文长度 " + length + " 码点");
        }
        if (repository.countUnresolvedAnnotations(documentId, segmentId, normalizedLanguage)
                >= MAX_UNRESOLVED_ANNOTATIONS) {
            throw ApiException.unprocessable("未解决批注已达上限 " + MAX_UNRESOLVED_ANNOTATIONS + " 条");
        }
        AnnotationRow row = new AnnotationRow(0, segmentId, normalizedLanguage,
                translation.translationVersion(), request.start(), request.end(),
                substringByCodePoints(translation.content(), request.start(), request.end()),
                request.body(), actorId, STATUS_OPEN);
        long annotationId = repository.insertAnnotation(documentId, row);
        repository.deleteApproval(documentId, segmentId, normalizedLanguage);
        return toAnnotationView(new AnnotationRow(annotationId, row.segmentId(), row.language(),
                row.translationVersion(), row.startOffset(), row.endOffset(), row.quote(), row.body(),
                row.creator(), row.status()));
    }

    /**
     * 定点编辑：expectedTranslationVersion 必须等于当前译文版本（不符 409），替换区间按码点计、
     * 越界 400；新区间文本替换后译文版本加一、草稿版本加一，原批准因版本变化失效；
     * OPEN 批注在编辑前方保持、后方按新增减删除码点数平移、与非空替换区间相交则 ORPHAN；
     * 纯插入在批注起点或更早时平移、严格在批注内部时 ORPHAN、在终点或更晚时不变。
     */
    @Transactional
    public ApiDtos.TranslationResponse anchoredEdit(long documentId, String segmentId, String language,
                                                    String actorId, ApiDtos.AnchoredEditRequest request) {
        DocumentRow document = lockDocument(documentId);
        String normalizedLanguage = normalizeLanguage(language);
        TranslationRow translation = repository.findTranslation(documentId, segmentId, normalizedLanguage)
                .orElseThrow(() -> ApiException.notFound(
                        "译文不存在: " + segmentId + "/" + normalizedLanguage));
        if (translation.translationVersion() != request.expectedTranslationVersion()) {
            throw ApiException.conflict("译文版本冲突：当前译文版本 " + translation.translationVersion()
                    + "，与期望的 " + request.expectedTranslationVersion() + " 不一致");
        }
        int length = codePointCount(translation.content());
        if (request.start() > request.end() || request.end() > length) {
            throw ApiException.badRequest("替换区间越界: [" + request.start() + "," + request.end()
                    + ")，译文长度 " + length + " 码点");
        }
        String newContent = substringByCodePoints(translation.content(), 0, request.start())
                + request.newText()
                + substringByCodePoints(translation.content(), request.end(), length);
        int translationVersion = translation.translationVersion() + 1;
        repository.upsertTranslation(documentId, new TranslationRow(segmentId, normalizedLanguage, newContent,
                actorId, translation.sourceVersion(), translationVersion, translation.termVersion()));
        shiftAnnotations(documentId, segmentId, normalizedLanguage,
                request.start(), request.end(), codePointCount(request.newText()));
        int draftVersion = bumpDraftVersion(document);
        return new ApiDtos.TranslationResponse(documentId, segmentId, normalizedLanguage,
                translationVersion, translation.sourceVersion(), translation.termVersion(), draftVersion);
    }

    /**
     * 批注解决：仅批注创建人可将 OPEN 或 ORPHAN 确认为 RESOLVED（非创建人 422，已解决 422）；
     * 携带的 expectedTranslationVersion 必须等于当前译文版本（不符 409），与编辑、批准同版本原子裁决。
     */
    @Transactional
    public ApiDtos.AnnotationView resolveAnnotation(long documentId, String segmentId, String language,
                                                    long annotationId, String actorId,
                                                    ApiDtos.ResolveAnnotationRequest request) {
        lockDocument(documentId);
        String normalizedLanguage = normalizeLanguage(language);
        AnnotationRow annotation = repository
                .findAnnotation(documentId, segmentId, normalizedLanguage, annotationId)
                .orElseThrow(() -> ApiException.notFound("批注不存在: " + annotationId));
        TranslationRow translation = repository.findTranslation(documentId, segmentId, normalizedLanguage)
                .orElseThrow(() -> ApiException.notFound(
                        "译文不存在: " + segmentId + "/" + normalizedLanguage));
        if (translation.translationVersion() != request.expectedTranslationVersion()) {
            throw ApiException.conflict("译文版本冲突：当前译文版本 " + translation.translationVersion()
                    + "，与期望的 " + request.expectedTranslationVersion() + " 不一致");
        }
        if (!annotation.creator().equals(actorId)) {
            throw ApiException.unprocessable("仅批注创建人可确认解决: " + annotationId);
        }
        if (STATUS_RESOLVED.equals(annotation.status())) {
            throw ApiException.unprocessable("批注已解决: " + annotationId);
        }
        repository.resolveAnnotation(annotationId);
        return toAnnotationView(new AnnotationRow(annotation.annotationId(), annotation.segmentId(),
                annotation.language(), annotation.translationVersion(), annotation.startOffset(),
                annotation.endOffset(), annotation.quote(), annotation.body(), annotation.creator(),
                STATUS_RESOLVED));
    }

    /** 查询段落某语言下全部批注：OPEN 携带当前锚点，ORPHAN 的 start/end 为 null 仅保留原引用。 */
    @Transactional(readOnly = true)
    public ApiDtos.AnnotationListResponse listAnnotations(long documentId, String segmentId, String language) {
        repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        String normalizedLanguage = normalizeLanguage(language);
        List<ApiDtos.AnnotationView> annotations = repository
                .listAnnotations(documentId, segmentId, normalizedLanguage)
                .stream().map(TranslationService::toAnnotationView).toList();
        return new ApiDtos.AnnotationListResponse(documentId, segmentId, normalizedLanguage, annotations);
    }

    /** 按 Unicode 码点计数（增补字符如 😀 计 1 个码点）。 */
    private static int codePointCount(String text) {
        return text.codePointCount(0, text.length());
    }

    /** 按码点区间 [start, end) 截取子串。 */
    private static String substringByCodePoints(String text, int start, int end) {
        int from = text.offsetByCodePoints(0, start);
        int to = text.offsetByCodePoints(from, end - start);
        return text.substring(from, to);
    }

    /**
     * 定点编辑后迁移 OPEN 批注：替换区间 [editStart, editEnd) 删除 deleted 个码点、插入 inserted 个码点。
     * 与非空替换区间相交或纯插入严格落入批注内部的批注孤立（ORPHAN，清空锚点）；
     * 编辑在批注前方（含纯插入在起点或更早）整体平移 delta；编辑在批注终点或更晚时不变。
     */
    private void shiftAnnotations(long documentId, String segmentId, String language,
                                  int editStart, int editEnd, int inserted) {
        int deleted = editEnd - editStart;
        int delta = inserted - deleted;
        for (AnnotationRow annotation : repository.listOpenAnnotations(documentId, segmentId, language)) {
            int start = annotation.startOffset();
            int end = annotation.endOffset();
            boolean intersectsReplacement = deleted > 0 && start < editEnd && editStart < end;
            boolean insertionInside = deleted == 0 && start < editStart && editStart < end;
            if (intersectsReplacement || insertionInside) {
                repository.markAnnotationOrphan(annotation.annotationId());
            } else if (editEnd <= start) {
                repository.shiftAnnotation(annotation.annotationId(), start + delta, end + delta);
            }
        }
    }

    private static ApiDtos.AnnotationView toAnnotationView(AnnotationRow row) {
        return new ApiDtos.AnnotationView(row.annotationId(), row.segmentId(), row.language(),
                row.translationVersion(), row.startOffset(), row.endOffset(),
                row.quote(), row.body(), row.creator(), row.status());
    }
}
