package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiDtos;
import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.domain.Placeholders;
import com.example.starter.translation.domain.Rows.ApprovalRow;
import com.example.starter.translation.domain.Rows.DocumentRow;
import com.example.starter.translation.domain.Rows.PlaceholderRow;
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

    /**
     * 源文修订：源文版本加一、草稿版本加一；相关译文因源文版本落后而待更新。
     * 已声明占位符的段落，新源文的占位符计数必须与声明完全一致；
     * 需要改变占位符时必须走占位符声明端点，否则 422。
     */
    @Transactional
    public ApiDtos.SegmentResponse reviseSource(long documentId, String segmentId,
                                              ApiDtos.ReviseSourceRequest request) {
        DocumentRow document = lockDocument(documentId);
        SegmentRow segment = findSegmentOrThrow(documentId, segmentId);
        if (segment.placeholderVersion() > 0) {
            Map<String, Integer> required = requiredPlaceholders(documentId, segmentId);
            List<ApiDtos.PlaceholderViolation> violations =
                    Placeholders.validate(required, request.sourceText());
            if (!violations.isEmpty()) {
                throw ApiException.placeholderViolation("新源文占位符与已声明集合不一致，"
                        + "请通过占位符声明端点修订占位符（违规 " + violations.size() + " 项）", violations);
            }
        }
        int sourceVersion = segment.sourceVersion() + 1;
        repository.updateSegmentSource(documentId, segmentId, request.sourceText(), sourceVersion);
        int draftVersion = bumpDraftVersion(document);
        return new ApiDtos.SegmentResponse(documentId, segmentId, sourceVersion, draftVersion);
    }

    /**
     * 占位符声明：整体给出新源文与规范排序的占位符集合，创建新源版本（源文版本、占位符版本各加一），
     * 所有目标译文因源文版本落后转为待修订，禁止用旧译文发布；草稿版本加一。
     * 占位符格式非法、重复或与源文中实际占位符集合不一致（缺失/额外）均返回 422。
     */
    @Transactional
    public ApiDtos.PlaceholderResponse declarePlaceholders(long documentId, String segmentId,
                                                           ApiDtos.DeclarePlaceholdersRequest request) {
        DocumentRow document = lockDocument(documentId);
        SegmentRow segment = findSegmentOrThrow(documentId, segmentId);
        List<String> declared = request.placeholders();
        for (String placeholder : declared) {
            if (!Placeholders.isValid(placeholder)) {
                throw ApiException.unprocessable("占位符格式非法: " + placeholder
                        + "，占位符须形如 {name}，name 以字母开头、仅含字母数字下划线");
            }
        }
        Set<String> distinct = new HashSet<>(declared);
        if (distinct.size() != declared.size()) {
            throw ApiException.unprocessable("占位符声明重复");
        }
        Map<String, Integer> sourceCounts = Placeholders.countBy(request.sourceText());
        if (!sourceCounts.keySet().equals(distinct)) {
            List<ApiDtos.PlaceholderViolation> violations = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : sourceCounts.entrySet()) {
                if (!distinct.contains(entry.getKey())) {
                    violations.add(new ApiDtos.PlaceholderViolation(
                            entry.getKey(), "EXTRA", 0, entry.getValue()));
                }
            }
            for (String placeholder : declared) {
                if (!sourceCounts.containsKey(placeholder)) {
                    violations.add(new ApiDtos.PlaceholderViolation(placeholder, "MISSING", 1, 0));
                }
            }
            throw ApiException.placeholderViolation("声明的占位符集合与源文实际占位符不一致（违规 "
                    + violations.size() + " 项）", violations);
        }
        int sourceVersion = segment.sourceVersion() + 1;
        int placeholderVersion = segment.placeholderVersion() + 1;
        repository.updateSegmentPlaceholders(documentId, segmentId, request.sourceText(),
                sourceVersion, placeholderVersion);
        List<PlaceholderRow> rows = sourceCounts.entrySet().stream()
                .map(e -> new PlaceholderRow(segmentId, e.getKey(), e.getValue()))
                .toList();
        repository.replaceSegmentPlaceholders(documentId, segmentId, rows);
        int draftVersion = bumpDraftVersion(document);
        List<String> sorted = sourceCounts.keySet().stream().toList();
        return new ApiDtos.PlaceholderResponse(documentId, segmentId, sourceVersion,
                placeholderVersion, sorted, draftVersion);
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
        validatePlaceholders(documentId, segment, request.content());
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
        validatePlaceholders(documentId, segment, translation.content());
        repository.upsertApproval(documentId, new ApprovalRow(segmentId, normalizedLanguage, actorId,
                segment.sourceVersion(), translation.translationVersion()));
        return new ApiDtos.ApprovalResponse(documentId, segmentId, normalizedLanguage, actorId,
                translation.translationVersion(), segment.sourceVersion());
    }

    /**
     * 批量批准：先校验全部批准项的最终状态（译文存在、审核人非作者、版本匹配、占位符完整），
     * 任一段不完整时整次 422 且不改变任何批准状态；全部通过后在一个事务中写入全部批准。
     */
    @Transactional
    public ApiDtos.BatchApproveResponse batchApprove(long documentId, String actorId,
                                                     ApiDtos.BatchApproveRequest request) {
        lockDocument(documentId);
        List<ApiDtos.ApprovalResponse> approvals = new ArrayList<>();
        Set<List<String>> seen = new HashSet<>();
        for (ApiDtos.BatchApproveItem item : request.items()) {
            String normalizedLanguage = normalizeLanguage(item.language());
            if (!seen.add(List.of(item.segmentId(), normalizedLanguage))) {
                throw ApiException.unprocessable(
                        "批量批准项重复: " + item.segmentId() + "/" + normalizedLanguage);
            }
            SegmentRow segment = findSegmentOrThrow(documentId, item.segmentId());
            TranslationRow translation = repository
                    .findTranslation(documentId, item.segmentId(), normalizedLanguage)
                    .orElseThrow(() -> ApiException.unprocessable(
                            "批量批准存在不完整项，缺少译文: " + item.segmentId() + "/" + normalizedLanguage));
            if (translation.author().equals(actorId)) {
                throw ApiException.unprocessable("批量批准存在不完整项，审核人不得是该译文作者: "
                        + item.segmentId() + "/" + normalizedLanguage);
            }
            if (translation.translationVersion() != item.translationVersion()) {
                throw ApiException.unprocessable("批量批准存在不完整项，" + item.segmentId() + "/"
                        + normalizedLanguage + " 译文版本 " + item.translationVersion()
                        + " 与当前译文版本 " + translation.translationVersion() + " 不匹配");
            }
            if (translation.sourceVersion() != segment.sourceVersion()) {
                throw ApiException.unprocessable("批量批准存在不完整项，" + item.segmentId() + "/"
                        + normalizedLanguage + " 译文基于源文版本 " + translation.sourceVersion()
                        + "，当前源文版本 " + segment.sourceVersion() + "，译文待更新");
            }
            validatePlaceholders(documentId, segment, translation.content());
            approvals.add(new ApiDtos.ApprovalResponse(documentId, item.segmentId(), normalizedLanguage,
                    actorId, translation.translationVersion(), segment.sourceVersion()));
        }
        for (ApiDtos.ApprovalResponse approval : approvals) {
            repository.upsertApproval(documentId, new ApprovalRow(approval.segmentId(), approval.language(),
                    actorId, approval.sourceVersion(), approval.translationVersion()));
        }
        return new ApiDtos.BatchApproveResponse(documentId, actorId, approvals.size(), approvals);
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
        Map<String, Map<String, Integer>> placeholdersBySegment = new LinkedHashMap<>();
        for (PlaceholderRow row : repository.listSegmentPlaceholders(documentId)) {
            placeholdersBySegment.computeIfAbsent(row.segmentId(), k -> new LinkedHashMap<>())
                    .put(row.placeholder(), row.occurrence());
        }
        List<ApiDtos.TermRuleView> termViolations = new ArrayList<>();
        List<ApiDtos.PlaceholderViolation> placeholderViolations = new ArrayList<>();
        for (SegmentRow segment : segments) {
            Map<String, Integer> required = placeholdersBySegment.getOrDefault(
                    segment.segmentId(), Map.of());
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
                if (segment.placeholderVersion() > 0) {
                    placeholderViolations.addAll(Placeholders.validate(required, translation.content()));
                }
            }
        }
        if (!placeholderViolations.isEmpty()) {
            throw ApiException.placeholderViolation("译文占位符不完整，共 "
                    + placeholderViolations.size() + " 项违规", placeholderViolations);
        }
        if (!termViolations.isEmpty()) {
            throw ApiException.termViolation("译文违反 " + termViolations.size() + " 条术语规则", termViolations);
        }
        int publishedVersion = document.publishedVersion() + 1;
        repository.insertSnapshot(documentId, publishedVersion,
                buildSnapshotJson(document, publishedVersion, segments, translations, approvals, termRules,
                        placeholdersBySegment));
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

    /** 生成完整只读快照 JSON：全部段落源文、占位符版本与集合、各语言译文、作者、审核人、版本号与固化的术语版本及规则集。 */
    private String buildSnapshotJson(DocumentRow document, int publishedVersion, List<SegmentRow> segments,
                                     Map<String, TranslationRow> translations,
                                     Map<String, ApprovalRow> approvals, List<TermRuleRow> termRules,
                                     Map<String, Map<String, Integer>> placeholdersBySegment) {
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
            segmentJson.put("placeholderVersion", segment.placeholderVersion());
            List<Map<String, Object>> placeholderList = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : placeholdersBySegment
                    .getOrDefault(segment.segmentId(), Map.of()).entrySet()) {
                Map<String, Object> placeholderJson = new LinkedHashMap<>();
                placeholderJson.put("placeholder", entry.getKey());
                placeholderJson.put("requiredCount", entry.getValue());
                placeholderList.add(placeholderJson);
            }
            segmentJson.put("placeholders", placeholderList);
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

    /** 查询段落当前声明的占位符要求计数，按占位符原文升序（规范排序）。 */
    private Map<String, Integer> requiredPlaceholders(long documentId, String segmentId) {
        Map<String, Integer> required = new LinkedHashMap<>();
        for (PlaceholderRow row : repository.listSegmentPlaceholders(documentId, segmentId)) {
            required.put(row.placeholder(), row.occurrence());
        }
        return required;
    }

    /** 已声明占位符的段落校验译文占位符完整性，违规返回 422 及全部明细。 */
    private void validatePlaceholders(long documentId, SegmentRow segment, String content) {
        if (segment.placeholderVersion() <= 0) {
            return;
        }
        List<ApiDtos.PlaceholderViolation> violations =
                Placeholders.validate(requiredPlaceholders(documentId, segment.segmentId()), content);
        if (!violations.isEmpty()) {
            throw ApiException.placeholderViolation("译文占位符不完整: " + segment.segmentId()
                    + "，共 " + violations.size() + " 项违规", violations);
        }
    }

    /**
     * 段落占位符明细与诊断查询：当前占位符版本、规范排序的声明集合（含要求出现次数），
     * 以及各语言译文相对当前声明的完整性（缺失/额外/计数不一致明细，无违规为空列表）。
     */
    @Transactional(readOnly = true)
    public ApiDtos.PlaceholderStatusView getPlaceholderStatus(long documentId, String segmentId) {
        repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        SegmentRow segment = findSegmentOrThrow(documentId, segmentId);
        Map<String, Integer> required = requiredPlaceholders(documentId, segmentId);
        List<ApiDtos.PlaceholderCountView> placeholders = required.entrySet().stream()
                .map(e -> new ApiDtos.PlaceholderCountView(e.getKey(), e.getValue()))
                .toList();
        List<ApiDtos.TranslationPlaceholderStatus> translations = new ArrayList<>();
        for (TranslationRow translation : repository.listTranslations(documentId)) {
            if (!translation.segmentId().equals(segmentId)) {
                continue;
            }
            boolean stale = translation.sourceVersion() != segment.sourceVersion();
            List<ApiDtos.PlaceholderViolation> violations = segment.placeholderVersion() > 0
                    ? Placeholders.validate(required, translation.content()) : List.of();
            translations.add(new ApiDtos.TranslationPlaceholderStatus(translation.language(),
                    translation.translationVersion(), translation.sourceVersion(), stale, violations));
        }
        return new ApiDtos.PlaceholderStatusView(documentId, segmentId, segment.placeholderVersion(),
                placeholders, translations);
    }
}
