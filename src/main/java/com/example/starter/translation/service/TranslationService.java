package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiDtos;
import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.domain.Placeholders;
import com.example.starter.translation.domain.Rows.ApprovalRow;
import com.example.starter.translation.domain.Rows.DocumentRow;
import com.example.starter.translation.domain.Rows.OperationLogRow;
import com.example.starter.translation.domain.Rows.SegmentRow;
import com.example.starter.translation.domain.Rows.TermRuleRow;
import com.example.starter.translation.domain.Rows.TranslationRow;
import com.example.starter.translation.repo.TranslationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 多语种段落修订、版式占位符锁定与发布快照的核心业务服务。
 * 所有写操作先对文档行加 FOR UPDATE 行锁，保证同一文档的修改、审核、撤回与发布按提交顺序串行裁决；
 * 业务变更、操作历史与幂等记录在同一事务内提交，失败整体回滚、查不到半成品。
 *
 * <p>版式占位符规则：源段显式声明去重并按字典序排序的占位符集合，且须与源文中实际出现的
 * 占位符去重集合完全一致；译文占位符多重集合（名称与出现次数）须与源文完全一致，
 * 缺失、额外或重复计数不一致均返回 422。源段正文或占位符集合修订都创建新源版本，
 * 并把该段全部译文置为 NEEDS_REVISION，旧译文禁止批准与发布。
 */
@Service
public class TranslationService {

    /** 操作类型常量：取值同时写入 operation_log 与历史明细，不得随意改名。 */
    public static final String OP_DOCUMENT_CREATED = "DOCUMENT_CREATED";
    public static final String OP_SEGMENT_ADDED = "SEGMENT_ADDED";
    public static final String OP_SOURCE_REVISED = "SOURCE_REVISED";
    public static final String OP_TRANSLATION_SUBMITTED = "TRANSLATION_SUBMITTED";
    public static final String OP_TRANSLATION_APPROVED = "TRANSLATION_APPROVED";
    public static final String OP_TRANSLATION_APPROVAL_WITHDRAWN = "TRANSLATION_APPROVAL_WITHDRAWN";
    public static final String OP_TRANSLATIONS_BATCH_APPROVED = "TRANSLATIONS_BATCH_APPROVED";
    public static final String OP_PUBLISHED = "PUBLISHED";
    public static final String OP_TERMS_UPDATED = "TERMS_UPDATED";

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
        List<SegmentInput> validated = new ArrayList<>();
        for (ApiDtos.SegmentInput input : segments) {
            validated.add(validateSegmentInput(input.segmentId(), input.sourceText(), input.placeholders()));
        }
        long documentId = repository.insertDocument(languages);
        for (SegmentInput segment : validated) {
            repository.insertSegment(documentId, segment.segmentId(), segment.sourceText(), segment.placeholders());
        }
        List<Map<String, Object>> segmentDetails = new ArrayList<>();
        for (SegmentInput segment : validated) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("segmentId", segment.segmentId());
            detail.put("sourceVersion", 1);
            detail.put("placeholders", segment.placeholders());
            segmentDetails.add(detail);
        }
        recordHistory(documentId, OP_DOCUMENT_CREATED, null, null, null, request.requestId(),
                Map.of("documentId", documentId, "draftVersion", 1, "publishedVersion", 0,
                        "targetLanguages", languages, "segments", segmentDetails));
        return new ApiDtos.DocumentResponse(documentId, 1, 0, languages);
    }

    /** 增加段落：segmentId 文档内唯一，草稿版本加一。 */
    @Transactional
    public ApiDtos.SegmentResponse addSegment(long documentId, ApiDtos.AddSegmentRequest request) {
        DocumentRow document = lockDocument(documentId);
        if (repository.findSegment(documentId, request.segmentId()).isPresent()) {
            throw ApiException.conflict("段落已存在: " + request.segmentId());
        }
        SegmentInput validated = validateSegmentInput(
                request.segmentId(), request.sourceText(), request.placeholders());
        repository.insertSegment(documentId, request.segmentId(), validated.sourceText(), validated.placeholders());
        int draftVersion = bumpDraftVersion(document);
        recordHistory(documentId, OP_SEGMENT_ADDED, null, request.segmentId(), null, request.requestId(),
                Map.of("segmentId", request.segmentId(), "sourceVersion", 1, "draftVersion", draftVersion,
                        "placeholders", validated.placeholders()));
        return new ApiDtos.SegmentResponse(documentId, request.segmentId(), 1, draftVersion,
                validated.placeholders());
    }

    /**
     * 源文修订：正文或占位符集合任一变化都创建新源版本（版本加一），并把该段全部译文置为
     * NEEDS_REVISION、旧批准随即失效；草稿版本加一，不影响已发布快照。
     */
    @Transactional
    public ApiDtos.SegmentResponse reviseSource(long documentId, String segmentId,
                                              ApiDtos.ReviseSourceRequest request) {
        DocumentRow document = lockDocument(documentId);
        SegmentRow segment = findSegmentOrThrow(documentId, segmentId);
        List<String> placeholders = normalizePlaceholders(request.placeholders());
        validateSourcePlaceholderConsistency(segmentId, request.sourceText(), placeholders);
        int sourceVersion = segment.sourceVersion() + 1;
        repository.updateSegmentSource(documentId, segmentId, request.sourceText(), placeholders, sourceVersion);
        int marked = repository.markTranslationsNeedsRevision(documentId, segmentId);
        int draftVersion = bumpDraftVersion(document);
        recordHistory(documentId, OP_SOURCE_REVISED, null, segmentId, null, request.requestId(),
                Map.of("segmentId", segmentId, "sourceVersion", sourceVersion, "draftVersion", draftVersion,
                        "placeholders", placeholders, "transitionsToNeedsRevision", marked));
        return new ApiDtos.SegmentResponse(documentId, segmentId, sourceVersion, draftVersion, placeholders);
    }

    /**
     * 译文提交：所依据源文版本必须等于当前源文版本；译文占位符多重集合与源文完全一致
     * （缺失/额外/重复计数不一致 422）；术语规则全部命中后绑定当前术语版本。
     * 提交后译文状态恢复 ACTIVE，译文版本递增，草稿版本加一。
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
        assertPlaceholderMultisetMatches(segment, request.content());
        List<TermRuleRow> termRules = repository.listTermRules(documentId, document.termVersion());
        List<ApiDtos.TermRuleView> violations = findViolations(
                segment.sourceText(), normalizedLanguage, request.content(), termRules);
        if (!violations.isEmpty()) {
            throw ApiException.termViolation("译文违反 " + violations.size() + " 条术语规则", violations);
        }
        int translationVersion = repository.findTranslation(documentId, segmentId, normalizedLanguage)
                .map(TranslationRow::translationVersion).orElse(0) + 1;
        repository.upsertTranslation(documentId, new TranslationRow(segmentId, normalizedLanguage,
                request.content(), actorId, segment.sourceVersion(), translationVersion,
                document.termVersion(), TranslationRow.ACTIVE));
        int draftVersion = bumpDraftVersion(document);
        recordHistory(documentId, OP_TRANSLATION_SUBMITTED, actorId, segmentId, normalizedLanguage,
                request.requestId(),
                Map.of("segmentId", segmentId, "language", normalizedLanguage,
                        "translationVersion", translationVersion, "sourceVersion", segment.sourceVersion(),
                        "termVersion", document.termVersion(), "draftVersion", draftVersion,
                        "status", TranslationRow.ACTIVE,
                        "placeholderCounts", Placeholders.counts(request.content())));
        return new ApiDtos.TranslationResponse(documentId, segmentId, normalizedLanguage,
                translationVersion, segment.sourceVersion(), document.termVersion(), draftVersion,
                TranslationRow.ACTIVE);
    }

    /** 译文批准：审核人不得是作者，译文须 ACTIVE，且必须同时匹配当前源文与译文版本。 */
    @Transactional
    public ApiDtos.ApprovalResponse approveTranslation(long documentId, String segmentId, String language,
                                                       String actorId, ApiDtos.ApproveTranslationRequest request) {
        DocumentRow document = lockDocument(documentId);
        Approval approval = validateSingleApproval(document, segmentId, language, actorId,
                request.translationVersion());
        repository.upsertApproval(documentId, new ApprovalRow(segmentId, approval.language(), actorId,
                approval.segment().sourceVersion(), approval.translation().translationVersion()));
        recordHistory(documentId, OP_TRANSLATION_APPROVED, actorId, segmentId, approval.language(),
                request.requestId(),
                Map.of("segmentId", segmentId, "language", approval.language(),
                        "translationVersion", approval.translation().translationVersion(),
                        "sourceVersion", approval.segment().sourceVersion()));
        return new ApiDtos.ApprovalResponse(documentId, segmentId, approval.language(), actorId,
                approval.translation().translationVersion(), approval.segment().sourceVersion());
    }

    /**
     * 批量批准：先对全部项目做完整校验（语言、作者、版本、状态、占位符一致性随发布条件复核），
     * 任一项不满足即 422 且整次不改变任何批准状态；全部通过后统一写入并与历史、幂等记录原子提交。
     */
    @Transactional
    public ApiDtos.BatchApproveResponse batchApprove(long documentId, String actorId,
                                                     ApiDtos.BatchApproveRequest request) {
        DocumentRow document = lockDocument(documentId);
        List<Approval> approvals = new ArrayList<>();
        java.util.Set<String> itemKeys = new java.util.HashSet<>();
        for (ApiDtos.ApprovalItem item : request.items()) {
            String itemKey = item.segmentId() + "/" + normalizeLanguage(item.language());
            if (!itemKeys.add(itemKey)) {
                throw ApiException.unprocessable("批量批准存在重复项目: " + itemKey);
            }
            approvals.add(validateSingleApproval(document, item.segmentId(), item.language(), actorId,
                    item.translationVersion()));
        }
        List<ApiDtos.ApprovalItemResult> results = new ArrayList<>();
        for (Approval approval : approvals) {
            repository.upsertApproval(documentId, new ApprovalRow(approval.segment().segmentId(),
                    approval.language(), actorId, approval.segment().sourceVersion(),
                    approval.translation().translationVersion()));
            results.add(new ApiDtos.ApprovalItemResult(approval.segment().segmentId(), approval.language(),
                    actorId, approval.translation().translationVersion(), approval.segment().sourceVersion()));
        }
        List<Map<String, Object>> itemDetails = new ArrayList<>();
        for (ApiDtos.ApprovalItemResult r : results) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("segmentId", r.segmentId());
            detail.put("language", r.language());
            detail.put("translationVersion", r.translationVersion());
            detail.put("sourceVersion", r.sourceVersion());
            itemDetails.add(detail);
        }
        recordHistory(documentId, OP_TRANSLATIONS_BATCH_APPROVED, actorId, null, null, request.requestId(),
                Map.of("approvedCount", results.size(), "items", itemDetails));
        return new ApiDtos.BatchApproveResponse(documentId, results.size(), results);
    }

    /**
     * 撤回批准：删除该段落/语言的当前批准（若存在），之后发布因缺少批准而 422。
     * removed 为 false 表示原本不存在批准；操作与历史记录原子提交。
     */
    @Transactional
    public ApiDtos.WithdrawApprovalResponse withdrawApproval(long documentId, String segmentId, String language,
                                                             String actorId,
                                                             ApiDtos.WithdrawApprovalRequest request) {
        lockDocument(documentId);
        String normalizedLanguage = normalizeLanguage(language);
        findSegmentOrThrow(documentId, segmentId);
        int removedRows = repository.deleteApproval(documentId, segmentId, normalizedLanguage);
        recordHistory(documentId, OP_TRANSLATION_APPROVAL_WITHDRAWN, actorId, segmentId, normalizedLanguage,
                request.requestId(),
                Map.of("segmentId", segmentId, "language", normalizedLanguage, "removed", removedRows > 0));
        return new ApiDtos.WithdrawApprovalResponse(documentId, segmentId, normalizedLanguage, actorId,
                removedRows > 0);
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
        var seen = new java.util.HashSet<List<String>>();
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
        List<Map<String, Object>> ruleDetails = new ArrayList<>();
        for (TermRuleRow r : rules) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("sourceTerm", r.sourceTerm());
            detail.put("language", r.language());
            detail.put("requiredTranslation", r.requiredTranslation());
            ruleDetails.add(detail);
        }
        recordHistory(documentId, OP_TERMS_UPDATED, null, null, null, request.requestId(),
                Map.of("termVersion", termVersion, "draftVersion", draftVersion,
                        "ruleCount", rules.size(), "rules", ruleDetails));
        return new ApiDtos.TermVersionResponse(documentId, termVersion, rules.size(), draftVersion);
    }

    /**
     * 发布：校验期望版本（不符 409），再校验全部段落在全部目标语言均有有效批准（缺译或审核失效 422）、
     * 译文 ACTIVE 且占位符多重集合一致、绑定当前术语版本（过期 422）且满足当前术语规则（违规 422 并
     * 返回全部违规术语）。全部通过后原子生成完整只读快照（固化源段、占位符集合、译文与术语版本）
     * 并递增发布版本；任何失败回滚，不产生部分快照。
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
        PublishDataset dataset = assemblePublishDataset(documentId);
        validatePublishable(document, dataset);
        int publishedVersion = document.publishedVersion() + 1;
        repository.insertSnapshot(documentId, publishedVersion,
                buildSnapshotJson(document, publishedVersion, dataset));
        repository.updatePublishedVersion(documentId, publishedVersion);
        recordHistory(documentId, OP_PUBLISHED, null, null, null, request.requestId(),
                Map.of("publishedVersion", publishedVersion, "draftVersion", document.draftVersion(),
                        "termVersion", document.termVersion(),
                        "segmentCount", dataset.segments().size(),
                        "translationCount", dataset.segments().size() * document.targetLanguages().size()));
        return new ApiDtos.PublishResponse(documentId, publishedVersion);
    }

    /** 查询文档明细：版本、段落（含占位符集合）与各语言译文/批准状态，读取不改变任何状态。 */
    @Transactional(readOnly = true)
    public ApiDtos.DocumentDetailResponse getDocumentDetail(long documentId) {
        DocumentRow document = repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        Map<String, TranslationRow> translations = repository.listTranslations(documentId).stream()
                .collect(Collectors.toMap(t -> key(t.segmentId(), t.language()), Function.identity()));
        Map<String, ApprovalRow> approvals = repository.listApprovals(documentId).stream()
                .collect(Collectors.toMap(a -> key(a.segmentId(), a.language()), Function.identity()));
        List<ApiDtos.SegmentDetailView> segmentViews = new ArrayList<>();
        for (SegmentRow segment : repository.listSegments(documentId)) {
            List<ApiDtos.TranslationDetailView> translationViews = new ArrayList<>();
            for (String language : document.targetLanguages()) {
                TranslationRow translation = translations.get(key(segment.segmentId(), language));
                ApprovalRow approval = approvals.get(key(segment.segmentId(), language));
                boolean approvalValid = translation != null && approval != null
                        && approval.sourceVersion() == segment.sourceVersion()
                        && approval.translationVersion() == translation.translationVersion();
                if (translation == null) {
                    translationViews.add(new ApiDtos.TranslationDetailView(language, null, null, 0,
                            segment.sourceVersion(), document.termVersion(), "MISSING",
                            false, approval == null ? null : approval.reviewer()));
                } else {
                    translationViews.add(new ApiDtos.TranslationDetailView(language, translation.content(),
                            translation.author(), translation.translationVersion(), translation.sourceVersion(),
                            translation.termVersion(), translation.status(), approvalValid,
                            approval == null ? null : approval.reviewer()));
                }
            }
            segmentViews.add(new ApiDtos.SegmentDetailView(segment.segmentId(), segment.sourceText(),
                    segment.sourceVersion(), segment.placeholders(), translationViews));
        }
        return new ApiDtos.DocumentDetailResponse(documentId, document.draftVersion(),
                document.publishedVersion(), document.termVersion(), document.targetLanguages(), segmentViews);
    }

    /**
     * 查询操作历史：UTC 纪元毫秒区间左闭右开（from 为空表示从头，to 为空表示到当前），
     * 按发生时间、序号稳定升序；读取不改变状态。历史证据只追加，任何写操作都不覆盖旧记录。
     */
    @Transactional(readOnly = true)
    public ApiDtos.HistoryResponse getHistory(long documentId, Long fromInclusive, Long toExclusive) {
        repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        long from = fromInclusive == null ? Long.MIN_VALUE : fromInclusive;
        long to = toExclusive == null ? Long.MAX_VALUE : toExclusive;
        if (from > to) {
            throw ApiException.unprocessable("历史查询区间起点不能晚于终点（区间左闭右开）");
        }
        List<OperationLogRow> rows = repository.listOperationLogs(documentId, from, to);
        List<ApiDtos.OperationHistoryView> views = new ArrayList<>();
        for (OperationLogRow row : rows) {
            Object detail;
            try {
                detail = objectMapper.readValue(row.detailJson(), Object.class);
            } catch (Exception e) {
                throw new IllegalStateException("历史明细解析失败: seq=" + row.seq(), e);
            }
            views.add(new ApiDtos.OperationHistoryView(row.seq(), row.operationType(), row.actor(),
                    row.segmentId(), row.language(), row.requestId(), row.occurredAt(), row.fingerprint(),
                    detail));
        }
        return new ApiDtos.HistoryResponse(documentId, fromInclusive, toExclusive, views.size(), views);
    }

    /**
     * 发布诊断：只读，逐项给出是否通过与实际值、要求值、差额（actual - required），
     * 汇总要求项数、就绪项数与阻塞项数，不写入任何数据、不产生历史记录。
     */
    @Transactional(readOnly = true)
    public ApiDtos.DiagnosisResponse diagnose(long documentId) {
        DocumentRow document = repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        PublishDataset dataset = assemblePublishDataset(documentId);
        List<TermRuleRow> termRules = repository.listTermRules(documentId, document.termVersion());
        List<ApiDtos.DiagnosisItem> items = new ArrayList<>();
        int requiredCount = 0;
        int readyCount = 0;
        for (SegmentRow segment : dataset.segments()) {
            for (String language : document.targetLanguages()) {
                requiredCount++;
                List<ApiDtos.DiagnosisItem> pairChecks = diagnosePair(
                        document, segment, language, dataset, termRules);
                items.addAll(pairChecks);
                boolean pairReady = pairChecks.stream().allMatch(ApiDtos.DiagnosisItem::passed);
                if (pairReady) {
                    readyCount++;
                }
            }
        }
        int blockedCount = requiredCount - readyCount;
        return new ApiDtos.DiagnosisResponse(documentId, document.draftVersion(), document.publishedVersion(),
                document.termVersion(), blockedCount == 0, requiredCount, readyCount, blockedCount, items);
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

    // ---- 内部辅助 --------------------------------------------------------

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

    /** 规范化段落输入：占位符词法校验、去重排序，并与源文中实际占位符去重集合比对。 */
    private static SegmentInput validateSegmentInput(String segmentId, String sourceText,
                                                     List<String> declaredPlaceholders) {
        List<String> placeholders = normalizePlaceholders(declaredPlaceholders);
        validateSourcePlaceholderConsistency(segmentId, sourceText, placeholders);
        return new SegmentInput(segmentId, sourceText, placeholders);
    }

    private static List<String> normalizePlaceholders(List<String> declared) {
        try {
            return Placeholders.normalizeDeclaration(declared);
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable(e.getMessage());
        }
    }

    /**
     * 源段声明集合必须与源文中实际出现的占位符去重集合一致：
     * 声明缺失（源文有、声明无）或声明多余（声明有、源文无）均 422，消息带实际值、要求值与差额。
     */
    private static void validateSourcePlaceholderConsistency(String segmentId, String sourceText,
                                                             List<String> declared) {
        try {
            Placeholders.assertNoMalformedBraces(sourceText);
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable("段落 " + segmentId + "：" + e.getMessage());
        }
        List<String> actual = Placeholders.counts(sourceText).keySet().stream().toList();
        if (!actual.equals(declared)) {
            List<String> missing = declared.stream().filter(token -> !actual.contains(token)).toList();
            List<String> extra = actual.stream().filter(token -> !declared.contains(token)).toList();
            throw ApiException.unprocessable("段落 " + segmentId + " 的占位符声明与源文实际出现不一致："
                    + "源文实际 " + actual + "，声明要求 " + declared
                    + "，声明缺少 " + missing + "，源文额外 " + extra
                    + "，差额 " + Math.abs(actual.size() - declared.size()) + " 个");
        }
    }

    /**
     * 译文占位符多重集合必须与源文完全一致。按声明的规范顺序逐项报告缺失、额外与重复计数差额。
     */
    private static void assertPlaceholderMultisetMatches(SegmentRow segment, String translationContent) {
        try {
            Placeholders.assertNoMalformedBraces(translationContent);
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable("译文占位符格式非法（" + segment.segmentId() + "）：" + e.getMessage());
        }
        Map<String, Integer> requiredCounts = Placeholders.counts(segment.sourceText());
        Map<String, Integer> actualCounts = Placeholders.counts(translationContent);
        List<String> problems = new ArrayList<>();
        for (String token : segment.placeholders()) {
            int required = requiredCounts.getOrDefault(token, 0);
            int actual = actualCounts.getOrDefault(token, 0);
            if (actual < required) {
                problems.add("占位符 " + token + " 缺失：实际 " + actual + " 次，要求 " + required
                        + " 次，差额 " + (required - actual) + " 次");
            } else if (actual > required) {
                problems.add("占位符 " + token + " 重复计数不一致：实际 " + actual + " 次，要求 " + required
                        + " 次，差额 " + (actual - required) + " 次");
            }
        }
        for (String token : actualCounts.keySet()) {
            if (!requiredCounts.containsKey(token)) {
                problems.add("额外占位符 " + token + "：实际 " + actualCounts.get(token)
                        + " 次，要求 0 次，差额 " + actualCounts.get(token) + " 次");
            }
        }
        if (!problems.isEmpty()) {
            throw ApiException.unprocessable("译文占位符与源段不一致（" + segment.segmentId() + "）："
                    + String.join("；", problems));
        }
    }

    /**
     * 单个批准的全部前置校验：语言在目标语言内、译文存在且 ACTIVE、审核人非作者、
     * 译文版本匹配且基于当前源文版本；不通过即抛 422/404，不写任何数据。
     */
    private Approval validateSingleApproval(DocumentRow document, String segmentId, String language,
                                            String actorId, int translationVersion) {
        String normalizedLanguage = normalizeLanguage(language);
        if (!document.targetLanguages().contains(normalizedLanguage)) {
            throw ApiException.unprocessable("语言不在文档目标语言中: " + normalizedLanguage);
        }
        SegmentRow segment = findSegmentOrThrow(document.documentId(), segmentId);
        TranslationRow translation = repository.findTranslation(
                document.documentId(), segmentId, normalizedLanguage)
                .orElseThrow(() -> ApiException.notFound(
                        "译文不存在: " + segmentId + "/" + normalizedLanguage));
        if (translation.author().equals(actorId)) {
            throw ApiException.unprocessable("审核人不得是该译文作者");
        }
        if (translation.translationVersion() != translationVersion) {
            throw ApiException.unprocessable("批准所针对的译文版本 " + translationVersion
                    + " 与当前译文版本 " + translation.translationVersion() + " 不匹配");
        }
        if (TranslationRow.NEEDS_REVISION.equals(translation.status())
                || translation.sourceVersion() != segment.sourceVersion()) {
            throw ApiException.unprocessable("译文待更新: " + segmentId + "/" + normalizedLanguage
                    + "，源段已修订，旧译文不能批准");
        }
        assertPlaceholderMultisetMatches(segment, translation.content());
        return new Approval(normalizedLanguage, segment, translation);
    }

    private record Approval(String language, SegmentRow segment, TranslationRow translation) {
    }

    private record SegmentInput(String segmentId, String sourceText, List<String> placeholders) {
    }

    /** 发布校验所需的一次性聚合数据。 */
    private record PublishDataset(List<SegmentRow> segments, Map<String, TranslationRow> translations,
                                  Map<String, ApprovalRow> approvals, List<TermRuleRow> termRules) {
    }

    private PublishDataset assemblePublishDataset(long documentId) {
        DocumentRow document = repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        Map<String, TranslationRow> translations = repository.listTranslations(documentId).stream()
                .collect(Collectors.toMap(t -> key(t.segmentId(), t.language()), Function.identity()));
        Map<String, ApprovalRow> approvals = repository.listApprovals(documentId).stream()
                .collect(Collectors.toMap(a -> key(a.segmentId(), a.language()), Function.identity()));
        List<TermRuleRow> termRules = repository.listTermRules(documentId, document.termVersion());
        return new PublishDataset(repository.listSegments(documentId), translations, approvals, termRules);
    }

    /** 发布前全量校验：任一条件不满足即 422（术语违规返回全部违规术语），用于真正发布路径。 */
    private void validatePublishable(DocumentRow document, PublishDataset dataset) {
        List<ApiDtos.TermRuleView> termViolations = new ArrayList<>();
        for (SegmentRow segment : dataset.segments()) {
            for (String language : document.targetLanguages()) {
                TranslationRow translation = dataset.translations().get(key(segment.segmentId(), language));
                if (translation == null) {
                    throw ApiException.unprocessable(
                            "缺少译文: " + segment.segmentId() + "/" + language);
                }
                if (TranslationRow.NEEDS_REVISION.equals(translation.status())
                        || translation.sourceVersion() != segment.sourceVersion()) {
                    throw ApiException.unprocessable("译文待更新: " + segment.segmentId() + "/" + language
                            + " 基于源文版本 " + translation.sourceVersion()
                            + "，当前源文版本 " + segment.sourceVersion());
                }
                assertPlaceholderMultisetMatches(segment, translation.content());
                if (translation.termVersion() != document.termVersion()) {
                    throw ApiException.unprocessable("译文术语版本过期: " + segment.segmentId() + "/" + language
                            + " 绑定术语版本 " + translation.termVersion()
                            + "，当前术语版本 " + document.termVersion());
                }
                ApprovalRow approval = dataset.approvals().get(key(segment.segmentId(), language));
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
                        segment.sourceText(), language, translation.content(), dataset.termRules()));
            }
        }
        if (!termViolations.isEmpty()) {
            throw ApiException.termViolation("译文违反 " + termViolations.size() + " 条术语规则", termViolations);
        }
    }

    /** 单段落/语言的全部发布条件诊断项：actual/required/difference 均为可核对的实际数值。 */
    private List<ApiDtos.DiagnosisItem> diagnosePair(DocumentRow document, SegmentRow segment, String language,
                                                     PublishDataset dataset, List<TermRuleRow> termRules) {
        List<ApiDtos.DiagnosisItem> items = new ArrayList<>();
        TranslationRow translation = dataset.translations().get(key(segment.segmentId(), language));
        ApprovalRow approval = dataset.approvals().get(key(segment.segmentId(), language));
        if (translation == null) {
            items.add(new ApiDtos.DiagnosisItem(segment.segmentId(), language, "TRANSLATION_PRESENT",
                    0, 1, -1, false, "缺少译文"));
            items.add(new ApiDtos.DiagnosisItem(segment.segmentId(), language, "APPROVAL_PRESENT",
                    approval == null ? 0 : 1, 1, approval == null ? -1 : 0, false,
                    approval == null ? "缺少批准" : "译文缺失，批准无法生效"));
            return items;
        }
        boolean sourceCurrent = translation.sourceVersion() == segment.sourceVersion()
                && !TranslationRow.NEEDS_REVISION.equals(translation.status());
        items.add(new ApiDtos.DiagnosisItem(segment.segmentId(), language, "SOURCE_VERSION_CURRENT",
                translation.sourceVersion(), segment.sourceVersion(),
                translation.sourceVersion() - segment.sourceVersion(), sourceCurrent,
                sourceCurrent ? "译文基于当前源文版本" : "译文待更新：实际源文版本 "
                        + translation.sourceVersion() + "，要求 " + segment.sourceVersion()));

        Map<String, Integer> requiredCounts = Placeholders.counts(segment.sourceText());
        Map<String, Integer> actualCounts = Placeholders.counts(translation.content());
        int placeholderTotalRequired = requiredCounts.values().stream().mapToInt(Integer::intValue).sum();
        int placeholderTotalActual = countMatchedOccurrences(requiredCounts, actualCounts);
        boolean placeholdersMatch = placeholderTotalActual == placeholderTotalRequired
                && actualCounts.keySet().equals(requiredCounts.keySet());
        items.add(new ApiDtos.DiagnosisItem(segment.segmentId(), language, "PLACEHOLDERS_MATCH",
                placeholderTotalActual, placeholderTotalRequired,
                placeholderTotalActual - placeholderTotalRequired, placeholdersMatch,
                placeholdersMatch ? "占位符集合与出现次数一致"
                        : "占位符缺失、额外或重复计数不一致：源文要求 " + Placeholders.expand(requiredCounts)
                                + "，译文实际 " + Placeholders.expand(actualCounts)));

        boolean termCurrent = translation.termVersion() == document.termVersion();
        items.add(new ApiDtos.DiagnosisItem(segment.segmentId(), language, "TERM_VERSION_CURRENT",
                translation.termVersion(), document.termVersion(),
                translation.termVersion() - document.termVersion(), termCurrent,
                termCurrent ? "绑定当前术语版本" : "术语版本过期"));

        List<ApiDtos.TermRuleView> violations = findViolations(
                segment.sourceText(), language, translation.content(), termRules);
        items.add(new ApiDtos.DiagnosisItem(segment.segmentId(), language, "TERM_RULES_PASS",
                violations.size(), 0, violations.size(), violations.isEmpty(),
                violations.isEmpty() ? "术语规则全部满足" : "违反术语规则 " + violations.size() + " 条"));

        if (approval == null) {
            items.add(new ApiDtos.DiagnosisItem(segment.segmentId(), language, "APPROVAL_PRESENT",
                    0, 1, -1, false, "缺少批准"));
        } else {
            boolean approvalCurrent = approval.sourceVersion() == segment.sourceVersion()
                    && approval.translationVersion() == translation.translationVersion();
            items.add(new ApiDtos.DiagnosisItem(segment.segmentId(), language, "APPROVAL_CURRENT",
                    approvalCurrent ? 1 : 0, 1, approvalCurrent ? 0 : -1, approvalCurrent,
                    approvalCurrent ? "批准对应当前源文与译文版本" : "批准已失效：源文或译文版本已改变"));
        }
        return items;
    }

    /** 统计译文中属于源文占位符集合的出现次数（额外占位符不计入匹配数）。 */
    private static int countMatchedOccurrences(Map<String, Integer> requiredCounts,
                                               Map<String, Integer> actualCounts) {
        int matched = 0;
        for (Map.Entry<String, Integer> entry : requiredCounts.entrySet()) {
            matched += Math.min(entry.getValue(), actualCounts.getOrDefault(entry.getKey(), 0));
        }
        return matched;
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
     * 追加操作历史：指纹覆盖操作者、操作类型、规范化 JSON 集合与全部结果相关字段。
     * 集合一律以 JSON 数组编码，绝不直接以分隔符拼接；时间统一取 UTC 毫秒。
     */
    private void recordHistory(long documentId, String operationType, String actor, String segmentId,
                               String language, String requestId, Map<String, Object> detail) {
        String detailJson = writeCanonicalJson(detail);
        LinkedHashMap<String, Object> fingerprintPayload = new LinkedHashMap<>();
        fingerprintPayload.put("actor", actor);
        fingerprintPayload.put("operationType", operationType);
        fingerprintPayload.put("documentId", documentId);
        fingerprintPayload.put("segmentId", segmentId);
        fingerprintPayload.put("language", language);
        fingerprintPayload.put("requestId", requestId);
        fingerprintPayload.put("detail", detail);
        String fingerprint = sha256(writeCanonicalJson(fingerprintPayload));
        repository.insertOperationLog(new OperationLogRow(0L, documentId, operationType, actor, segmentId,
                language, requestId, clock.millis(), fingerprint, detailJson));
    }

    private String writeCanonicalJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("规范化 JSON 序列化失败", e);
        }
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 生成完整只读快照 JSON：固化源段（含占位符集合）、各语言译文（含状态）、审核人、版本与术语规则集。 */
    private String buildSnapshotJson(DocumentRow document, int publishedVersion, PublishDataset dataset) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("documentId", document.documentId());
        snapshot.put("publishedVersion", publishedVersion);
        snapshot.put("draftVersion", document.draftVersion());
        snapshot.put("termVersion", document.termVersion());
        snapshot.put("targetLanguages", document.targetLanguages());
        List<Map<String, Object>> termList = new ArrayList<>();
        for (TermRuleRow rule : dataset.termRules()) {
            Map<String, Object> termJson = new LinkedHashMap<>();
            termJson.put("sourceTerm", rule.sourceTerm());
            termJson.put("language", rule.language());
            termJson.put("requiredTranslation", rule.requiredTranslation());
            termList.add(termJson);
        }
        snapshot.put("terms", termList);
        List<Map<String, Object>> segmentList = new ArrayList<>();
        for (SegmentRow segment : dataset.segments()) {
            Map<String, Object> segmentJson = new LinkedHashMap<>();
            segmentJson.put("segmentId", segment.segmentId());
            segmentJson.put("sourceText", segment.sourceText());
            segmentJson.put("sourceVersion", segment.sourceVersion());
            segmentJson.put("placeholders", segment.placeholders());
            segmentJson.put("placeholderCounts", Placeholders.counts(segment.sourceText()));
            List<Map<String, Object>> translationList = new ArrayList<>();
            for (String language : document.targetLanguages()) {
                TranslationRow translation = dataset.translations().get(key(segment.segmentId(), language));
                ApprovalRow approval = dataset.approvals().get(key(segment.segmentId(), language));
                Map<String, Object> translationJson = new LinkedHashMap<>();
                translationJson.put("language", language);
                translationJson.put("content", translation.content());
                translationJson.put("author", translation.author());
                translationJson.put("translationVersion", translation.translationVersion());
                translationJson.put("sourceVersion", translation.sourceVersion());
                translationJson.put("termVersion", translation.termVersion());
                translationJson.put("status", translation.status());
                translationJson.put("placeholderCounts", Placeholders.counts(translation.content()));
                translationJson.put("reviewer", approval.reviewer());
                translationList.add(translationJson);
            }
            segmentJson.put("translations", translationList);
            segmentList.add(segmentJson);
        }
        snapshot.put("segments", segmentList);
        return writeCanonicalJson(snapshot);
    }
}
