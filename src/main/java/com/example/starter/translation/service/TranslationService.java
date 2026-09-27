package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiDtos;
import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.domain.Rows.ApprovalRow;
import com.example.starter.translation.domain.Rows.DocumentRow;
import com.example.starter.translation.domain.Rows.HistoryRow;
import com.example.starter.translation.domain.Rows.SegmentRow;
import com.example.starter.translation.domain.Rows.TermRuleRow;
import com.example.starter.translation.domain.Rows.TranslationRow;
import com.example.starter.translation.repo.TranslationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 多语种段落修订与发布快照的核心业务服务。
 * 所有写操作先对文档行加 FOR UPDATE 行锁，保证同一文档的修改、审核与发布串行，
 * 并发下对应一个一致的文档状态；方法均加入调用方事务，与幂等记录原子提交。
 */
@Service
public class TranslationService {

    /** 占位符格式：花括号包裹的非空名称，名称字符限于字母、数字及 _ . : -。 */
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{[A-Za-z0-9_.:-]+}");

    private final TranslationRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public TranslationService(TranslationRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
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
            repository.insertSegment(documentId, segment.segmentId(), segment.sourceText(),
                    normalizePlaceholders(segment.placeholders()));
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
                normalizePlaceholders(request.placeholders()));
        int draftVersion = bumpDraftVersion(document);
        return new ApiDtos.SegmentResponse(documentId, request.segmentId(), 1, draftVersion);
    }

    /** 源文修订：源文版本加一、草稿版本加一；相关译文因源文版本落后而待更新；记录操作历史。 */
    @Transactional
    public ApiDtos.SegmentResponse reviseSource(long documentId, String segmentId,
                                              ApiDtos.ReviseSourceRequest request) {
        DocumentRow document = lockDocument(documentId);
        SegmentRow segment = findSegmentOrThrow(documentId, segmentId);
        int sourceVersion = segment.sourceVersion() + 1;
        repository.updateSegmentSource(documentId, segmentId, request.sourceText(), sourceVersion);
        int draftVersion = bumpDraftVersion(document);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("sourceVersion", sourceVersion);
        detail.put("placeholders", segment.placeholders());
        recordHistory(documentId, "SOURCE_REVISED", segmentId, null, null, detail);
        return new ApiDtos.SegmentResponse(documentId, segmentId, sourceVersion, draftVersion);
    }

    /**
     * 占位符修订：整体替换源段的版式占位符声明（规范排序后存储），创建新源文版本，
     * 所有目标译文因源文版本落后转为待修订，禁止用旧译文发布；记录操作历史。
     */
    @Transactional
    public ApiDtos.SegmentResponse revisePlaceholders(long documentId, String segmentId,
                                                      ApiDtos.RevisePlaceholdersRequest request) {
        DocumentRow document = lockDocument(documentId);
        SegmentRow segment = findSegmentOrThrow(documentId, segmentId);
        List<String> placeholders = normalizePlaceholders(request.placeholders());
        int sourceVersion = segment.sourceVersion() + 1;
        repository.updateSegmentPlaceholders(documentId, segmentId, placeholders, sourceVersion);
        int draftVersion = bumpDraftVersion(document);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("sourceVersion", sourceVersion);
        detail.put("placeholders", placeholders);
        recordHistory(documentId, "PLACEHOLDERS_REVISED", segmentId, null, null, detail);
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

    /**
     * 译文批准：审核人不得是作者，必须同时匹配当前源文与译文版本；
     * 源段已声明占位符时，译文必须保留完全相同的占位符集合及出现次数，否则 422 返回全部计数差异。
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
        checkPlaceholders(segment, normalizedLanguage, translation.content());
        repository.upsertApproval(documentId, new ApprovalRow(segmentId, normalizedLanguage, actorId,
                segment.sourceVersion(), translation.translationVersion()));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("translationVersion", translation.translationVersion());
        detail.put("sourceVersion", segment.sourceVersion());
        recordHistory(documentId, "TRANSLATION_APPROVED", segmentId, normalizedLanguage, actorId, detail);
        return new ApiDtos.ApprovalResponse(documentId, segmentId, normalizedLanguage, actorId,
                translation.translationVersion(), segment.sourceVersion());
    }

    /**
     * 批量批准：先对全部项校验完整最终状态（语言、段落、译文、作者、版本、占位符），
     * 任一项不完整则整次 422 且不改变任何批准状态；全部通过后在同一事务中提交并逐条记录历史。
     * items 已在请求构造时按 (segmentId, language) 规范排序，处理顺序稳定。
     */
    @Transactional
    public ApiDtos.BatchApproveResponse batchApprove(long documentId, String actorId,
                                                     ApiDtos.BatchApproveRequest request) {
        DocumentRow document = lockDocument(documentId);
        List<ApiDtos.BatchItemErrorView> errors = new ArrayList<>();
        List<ApiDtos.BatchApproveItem> items = request.items();
        Set<List<String>> seen = new HashSet<>();
        Map<String, SegmentRow> segments = repository.listSegments(documentId).stream()
                .collect(Collectors.toMap(SegmentRow::segmentId, Function.identity()));
        Map<String, TranslationRow> translations = repository.listTranslations(documentId).stream()
                .collect(Collectors.toMap(t -> key(t.segmentId(), t.language()), Function.identity()));
        for (ApiDtos.BatchApproveItem item : items) {
            String language = normalizeLanguage(item.language());
            if (!seen.add(List.of(item.segmentId(), language))) {
                errors.add(new ApiDtos.BatchItemErrorView(item.segmentId(), language, "DUPLICATE_ITEM",
                        "批量项重复: " + item.segmentId() + "/" + language));
                continue;
            }
            if (!document.targetLanguages().contains(language)) {
                errors.add(new ApiDtos.BatchItemErrorView(item.segmentId(), language, "LANGUAGE_NOT_TARGET",
                        "语言不在文档目标语言中: " + language));
                continue;
            }
            SegmentRow segment = segments.get(item.segmentId());
            if (segment == null) {
                errors.add(new ApiDtos.BatchItemErrorView(item.segmentId(), language, "SEGMENT_NOT_FOUND",
                        "段落不存在: " + item.segmentId()));
                continue;
            }
            TranslationRow translation = translations.get(key(item.segmentId(), language));
            if (translation == null) {
                errors.add(new ApiDtos.BatchItemErrorView(item.segmentId(), language, "TRANSLATION_MISSING",
                        "译文不存在: " + item.segmentId() + "/" + language));
                continue;
            }
            if (translation.author().equals(actorId)) {
                errors.add(new ApiDtos.BatchItemErrorView(item.segmentId(), language, "AUTHOR_CANNOT_REVIEW",
                        "审核人不得是该译文作者"));
                continue;
            }
            if (translation.translationVersion() != item.translationVersion()) {
                errors.add(new ApiDtos.BatchItemErrorView(item.segmentId(), language, "VERSION_MISMATCH",
                        "批准所针对的译文版本 " + item.translationVersion()
                                + " 与当前译文版本 " + translation.translationVersion() + " 不匹配"));
                continue;
            }
            if (translation.sourceVersion() != segment.sourceVersion()) {
                errors.add(new ApiDtos.BatchItemErrorView(item.segmentId(), language, "SOURCE_STALE",
                        "译文基于源文版本 " + translation.sourceVersion()
                                + "，当前源文版本 " + segment.sourceVersion() + "，译文待更新，不能批准"));
                continue;
            }
            List<ApiDtos.PlaceholderMismatchView> mismatches =
                    placeholderMismatches(segment, language, translation.content());
            if (!mismatches.isEmpty()) {
                errors.add(new ApiDtos.BatchItemErrorView(item.segmentId(), language, "PLACEHOLDER_MISMATCH",
                        "译文占位符与源段声明不一致，差异 " + mismatches.size() + " 处"));
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.batchIncomplete(
                    "批量批准存在 " + errors.size() + " 个不完整项，整次未改变批准状态", errors);
        }
        Instant occurredAt = now();
        List<ApiDtos.ApprovalResponse> approvals = new ArrayList<>();
        for (ApiDtos.BatchApproveItem item : items) {
            String language = normalizeLanguage(item.language());
            SegmentRow segment = segments.get(item.segmentId());
            TranslationRow translation = translations.get(key(item.segmentId(), language));
            repository.upsertApproval(documentId, new ApprovalRow(item.segmentId(), language, actorId,
                    segment.sourceVersion(), translation.translationVersion()));
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("translationVersion", translation.translationVersion());
            detail.put("sourceVersion", segment.sourceVersion());
            recordHistoryAt(documentId, "TRANSLATION_APPROVED", item.segmentId(), language, actorId,
                    detail, occurredAt);
            approvals.add(new ApiDtos.ApprovalResponse(documentId, item.segmentId(), language, actorId,
                    translation.translationVersion(), segment.sourceVersion()));
        }
        return new ApiDtos.BatchApproveResponse(documentId, approvals.size(), approvals);
    }

    /**
     * 撤回批准：删除当前批准记录并记录不可变历史证据（原审核人与版本）；
     * 批准不存在返回 404。撤回后发布校验将因缺少批准而失败。
     */
    @Transactional
    public ApiDtos.WithdrawalResponse withdrawApproval(long documentId, String segmentId, String language,
                                                       String actorId, ApiDtos.WithdrawApprovalRequest request) {
        lockDocument(documentId);
        String normalizedLanguage = normalizeLanguage(language);
        SegmentRow segment = findSegmentOrThrow(documentId, segmentId);
        ApprovalRow approval = repository.findApproval(documentId, segmentId, normalizedLanguage)
                .orElseThrow(() -> ApiException.notFound(
                        "批准不存在: " + segmentId + "/" + normalizedLanguage));
        repository.deleteApproval(documentId, segmentId, normalizedLanguage);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("previousReviewer", approval.reviewer());
        detail.put("translationVersion", approval.translationVersion());
        detail.put("sourceVersion", approval.sourceVersion());
        recordHistory(documentId, "APPROVAL_WITHDRAWN", segmentId, normalizedLanguage, actorId, detail);
        return new ApiDtos.WithdrawalResponse(documentId, segmentId, normalizedLanguage, actorId,
                approval.reviewer(), approval.translationVersion(), segment.sourceVersion());
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
        List<ApiDtos.PlaceholderMismatchView> placeholderMismatches = new ArrayList<>();
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
                placeholderMismatches.addAll(
                        placeholderMismatches(segment, language, translation.content()));
            }
        }
        if (!termViolations.isEmpty()) {
            throw ApiException.termViolation("译文违反 " + termViolations.size() + " 条术语规则", termViolations);
        }
        if (!placeholderMismatches.isEmpty()) {
            throw ApiException.placeholderMismatch(
                    "译文占位符与源段声明不一致，差异 " + placeholderMismatches.size() + " 处",
                    placeholderMismatches);
        }
        int publishedVersion = document.publishedVersion() + 1;
        repository.insertSnapshot(documentId, publishedVersion,
                buildSnapshotJson(document, publishedVersion, segments, translations, approvals, termRules));
        repository.updatePublishedVersion(documentId, publishedVersion);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("publishedVersion", publishedVersion);
        detail.put("draftVersion", document.draftVersion());
        detail.put("termVersion", document.termVersion());
        recordHistory(documentId, "PUBLISHED", null, null, null, detail);
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

    /** 段落明细：源文、版本、占位符声明（null 表示未声明）及各目标语言译文状态，按目标语言顺序输出。 */
    @Transactional(readOnly = true)
    public ApiDtos.SegmentDetailView getSegmentDetail(long documentId, String segmentId) {
        DocumentRow document = repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        SegmentRow segment = repository.findSegment(documentId, segmentId)
                .orElseThrow(() -> ApiException.notFound("段落不存在: " + segmentId));
        Map<String, TranslationRow> translations = repository.listTranslations(documentId).stream()
                .filter(t -> t.segmentId().equals(segmentId))
                .collect(Collectors.toMap(TranslationRow::language, Function.identity()));
        Map<String, ApprovalRow> approvals = repository.listApprovals(documentId).stream()
                .filter(a -> a.segmentId().equals(segmentId))
                .collect(Collectors.toMap(ApprovalRow::language, Function.identity()));
        List<ApiDtos.TranslationStatusView> views = new ArrayList<>();
        for (String language : document.targetLanguages()) {
            TranslationRow translation = translations.get(language);
            ApprovalRow approval = approvals.get(language);
            if (translation == null) {
                views.add(new ApiDtos.TranslationStatusView(language, false, null, null, null, null,
                        null, null, false, null));
            } else {
                boolean approved = approval != null
                        && approval.translationVersion() == translation.translationVersion()
                        && approval.sourceVersion() == segment.sourceVersion();
                views.add(new ApiDtos.TranslationStatusView(language, true, translation.content(),
                        translation.author(), translation.translationVersion(), translation.sourceVersion(),
                        translation.termVersion(), translation.sourceVersion() != segment.sourceVersion(),
                        approved, approved ? approval.reviewer() : null));
            }
        }
        return new ApiDtos.SegmentDetailView(documentId, segmentId, segment.sourceText(),
                segment.sourceVersion(), segment.placeholders(), views);
    }

    /**
     * 发布就绪诊断：逐段落逐语言给出译文、批准与占位符状态；
     * 数量字段返回实际值与要求值（requiredApprovalCount = 段落数 × 目标语言数）。
     */
    @Transactional(readOnly = true)
    public ApiDtos.DiagnosticsView getDiagnostics(long documentId) {
        DocumentRow document = repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        List<SegmentRow> segments = repository.listSegments(documentId);
        Map<String, TranslationRow> translations = repository.listTranslations(documentId).stream()
                .collect(Collectors.toMap(t -> key(t.segmentId(), t.language()), Function.identity()));
        Map<String, ApprovalRow> approvals = repository.listApprovals(documentId).stream()
                .collect(Collectors.toMap(a -> key(a.segmentId(), a.language()), Function.identity()));
        List<ApiDtos.SegmentDiagnostics> segmentViews = new ArrayList<>();
        int approvedCount = 0;
        int readyCount = 0;
        for (SegmentRow segment : segments) {
            List<ApiDtos.TranslationDiagnostics> translationViews = new ArrayList<>();
            for (String language : document.targetLanguages()) {
                TranslationRow translation = translations.get(key(segment.segmentId(), language));
                ApprovalRow approval = approvals.get(key(segment.segmentId(), language));
                boolean approved = approval != null && translation != null
                        && approval.translationVersion() == translation.translationVersion()
                        && approval.sourceVersion() == segment.sourceVersion();
                boolean declared = segment.placeholders() != null;
                Integer expectedCount = declared ? segment.placeholders().size() : null;
                Integer actualCount = null;
                Boolean placeholdersMatch = null;
                if (declared && translation != null) {
                    actualCount = extractPlaceholders(translation.content()).size();
                    placeholdersMatch = placeholderMismatches(segment, language, translation.content())
                            .isEmpty();
                }
                boolean sourceStale = translation != null
                        && translation.sourceVersion() != segment.sourceVersion();
                Boolean termStale = translation == null ? null
                        : translation.termVersion() != document.termVersion();
                boolean ready = translation != null && !sourceStale && approved
                        && !Boolean.TRUE.equals(termStale)
                        && (!declared || Boolean.TRUE.equals(placeholdersMatch));
                if (approved) {
                    approvedCount++;
                }
                if (ready) {
                    readyCount++;
                }
                translationViews.add(new ApiDtos.TranslationDiagnostics(language, translation != null,
                        translation == null ? null : translation.translationVersion(),
                        translation == null ? null : sourceStale, termStale, approved, declared,
                        placeholdersMatch, expectedCount, actualCount, ready));
            }
            segmentViews.add(new ApiDtos.SegmentDiagnostics(segment.segmentId(), segment.sourceVersion(),
                    segment.placeholders(), translationViews));
        }
        return new ApiDtos.DiagnosticsView(documentId, document.draftVersion(), document.publishedVersion(),
                document.termVersion(), segments.size(), document.targetLanguages().size(),
                segments.size() * document.targetLanguages().size(), approvedCount, readyCount, segmentViews);
    }

    /**
     * 操作历史查询：时间区间左闭右开（from <= occurredAt < to），可选按操作类型、段落、语言过滤；
     * 结果按 (occurredAt, historyId) 升序稳定排序，历史记录不可变。
     */
    @Transactional(readOnly = true)
    public ApiDtos.HistoryListView getHistory(long documentId, Instant from, Instant to, String operation,
                                              String segmentId, String language) {
        repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        if (operation != null) {
            Set<String> known = Set.of("SOURCE_REVISED", "PLACEHOLDERS_REVISED", "TRANSLATION_APPROVED",
                    "APPROVAL_WITHDRAWN", "PUBLISHED");
            if (!known.contains(operation)) {
                throw ApiException.badRequest("未知操作类型: " + operation);
            }
        }
        List<HistoryRow> rows = repository.listHistory(documentId, from, to, operation, segmentId,
                language == null ? null : normalizeLanguage(language));
        List<ApiDtos.HistoryView> records = new ArrayList<>();
        for (HistoryRow row : rows) {
            records.add(new ApiDtos.HistoryView(row.historyId(), row.operation(), row.segmentId(),
                    row.language(), row.actor(), readJson(row.detailJson()), row.occurredAt().toString()));
        }
        return new ApiDtos.HistoryListView(documentId, records);
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
     * 规范化占位符声明：逐项校验格式（非法 422），按 Unicode 码点升序排序；
     * 重复元素保留并表示要求的出现次数；null 输入表示未声明，原样返回 null。
     */
    private static List<String> normalizePlaceholders(List<String> placeholders) {
        if (placeholders == null) {
            return null;
        }
        for (String placeholder : placeholders) {
            if (!PLACEHOLDER_PATTERN.matcher(placeholder).matches()) {
                throw ApiException.unprocessable("占位符格式非法: " + placeholder
                        + "，要求形如 {name}，名称字符限于字母、数字及 _ . : -");
            }
        }
        return placeholders.stream().sorted().toList();
    }

    /** 从正文中提取全部占位符（按出现顺序）。 */
    private static List<String> extractPlaceholders(String content) {
        List<String> found = new ArrayList<>();
        java.util.regex.Matcher matcher = PLACEHOLDER_PATTERN.matcher(content);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    /**
     * 计算译文占位符与源段声明的计数差异：按规范排序比较两个多重集，
     * 返回全部不一致项（要求值与实际值）；源段未声明时返回空列表。
     */
    private static List<ApiDtos.PlaceholderMismatchView> placeholderMismatches(
            SegmentRow segment, String language, String content) {
        if (segment.placeholders() == null) {
            return List.of();
        }
        Map<String, Integer> expected = countPlaceholders(segment.placeholders());
        Map<String, Integer> actual = countPlaceholders(extractPlaceholders(content));
        List<ApiDtos.PlaceholderMismatchView> mismatches = new ArrayList<>();
        Set<String> all = new HashSet<>();
        all.addAll(expected.keySet());
        all.addAll(actual.keySet());
        for (String placeholder : all.stream().sorted().toList()) {
            int expectedCount = expected.getOrDefault(placeholder, 0);
            int actualCount = actual.getOrDefault(placeholder, 0);
            if (expectedCount != actualCount) {
                mismatches.add(new ApiDtos.PlaceholderMismatchView(segment.segmentId(), language,
                        placeholder, expectedCount, actualCount));
            }
        }
        return mismatches;
    }

    private static Map<String, Integer> countPlaceholders(List<String> placeholders) {
        Map<String, Integer> counts = new TreeMap<>();
        for (String placeholder : placeholders) {
            counts.merge(placeholder, 1, Integer::sum);
        }
        return counts;
    }

    /** 批准前占位符校验：译文必须保留与源段声明完全相同的占位符集合及出现次数，否则 422。 */
    private static void checkPlaceholders(SegmentRow segment, String language, String content) {
        List<ApiDtos.PlaceholderMismatchView> mismatches = placeholderMismatches(segment, language, content);
        if (!mismatches.isEmpty()) {
            throw ApiException.placeholderMismatch(
                    "译文占位符与源段声明不一致，差异 " + mismatches.size() + " 处", mismatches);
        }
    }

    /** 当前 UTC 时间，截断到微秒（与 occurred_at 列精度一致）。 */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /** 记录一条操作历史（不可变业务证据），使用当前 UTC 时间。 */
    private void recordHistory(long documentId, String operation, String segmentId, String language,
                               String actor, Map<String, Object> detail) {
        recordHistoryAt(documentId, operation, segmentId, language, actor, detail, now());
    }

    /** 以指定时间记录一条操作历史；同一操作的多条记录共享同一时间，按自增 ID 稳定排序。 */
    private void recordHistoryAt(long documentId, String operation, String segmentId, String language,
                                 String actor, Map<String, Object> detail, Instant occurredAt) {
        try {
            repository.insertHistory(documentId, operation, segmentId, language, actor,
                    objectMapper.writeValueAsString(detail), occurredAt);
        } catch (Exception e) {
            throw new IllegalStateException("操作历史序列化失败", e);
        }
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("操作历史反序列化失败", e);
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
            segmentJson.put("placeholders", segment.placeholders());
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
}
