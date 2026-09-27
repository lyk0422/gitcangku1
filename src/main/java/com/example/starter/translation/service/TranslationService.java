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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
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
 * 所有写操作先对文档行加 FOR UPDATE 行锁，保证同一文档的修改、审核、撤回与发布按提交顺序串行裁决，
 * 发布在同一锁内读取一致版本；方法均加入调用方事务，业务变更、历史证据与幂等记录原子提交，
 * 任一对象最终状态校验失败则整体回滚，查不到半成品。
 *
 * <p>版式锁定：源段可声明规范排序的占位符多重集合，译文提交、批准（含批量）与发布前必须保留
 * 完全相同的占位符集合及出现次数，否则 422 并返回要求/实际/差额计数；源段修订产生新源版本并
 * 删除该段落全部既有批准，旧译文一律转为待修订、不得发布。
 */
@Service
public class TranslationService {

    private final TranslationRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public TranslationService(TranslationRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** 建文档：1~5 种目标语言，可携带初始段落（含占位符声明），初始草稿版本 1、发布版本 0。 */
    @Transactional
    public ApiDtos.DocumentResponse createDocument(ApiDtos.CreateDocumentRequest request) {
        List<String> languages = normalizeLanguages(request.targetLanguages());
        List<ApiDtos.SegmentInput> segments = request.segments();
        long distinct = segments.stream().map(ApiDtos.SegmentInput::segmentId).distinct().count();
        if (distinct != segments.size()) {
            throw ApiException.unprocessable("初始段落 segmentId 重复");
        }
        List<List<String>> canonicalPlaceholders = new ArrayList<>();
        for (ApiDtos.SegmentInput segment : segments) {
            canonicalPlaceholders.add(Placeholders.canonical(segment.placeholders()));
        }
        long documentId = repository.insertDocument(languages);
        for (int i = 0; i < segments.size(); i++) {
            ApiDtos.SegmentInput segment = segments.get(i);
            repository.insertSegment(documentId, segment.segmentId(), segment.sourceText(),
                    canonicalPlaceholders.get(i));
        }
        ApiDtos.DocumentResponse response = new ApiDtos.DocumentResponse(documentId, 1, 0, languages);
        Map<String, Object> detail = Fingerprint.ordered(
                "documentId", documentId,
                "draftVersion", 1,
                "publishedVersion", 0,
                "targetLanguages", Fingerprint.collection(languages),
                "segments", segments.stream()
                        .map(s -> Fingerprint.ordered("segmentId", s.segmentId(),
                                "sourceText", s.sourceText(),
                                "placeholders", Fingerprint.collection(
                                        Placeholders.canonical(s.placeholders()))))
                        .toList());
        recordHistory(documentId, null, null, request.requestId(), null,
                OperationType.CREATE_DOCUMENT, detail);
        return response;
    }

    /** 增加段落：segmentId 文档内唯一，携带规范占位符集合，草稿版本加一。 */
    @Transactional
    public ApiDtos.SegmentResponse addSegment(long documentId, ApiDtos.AddSegmentRequest request) {
        DocumentRow document = lockDocument(documentId);
        if (repository.findSegment(documentId, request.segmentId()).isPresent()) {
            throw ApiException.conflict("段落已存在: " + request.segmentId());
        }
        List<String> placeholders = Placeholders.canonical(request.placeholders());
        repository.insertSegment(documentId, request.segmentId(), request.sourceText(), placeholders);
        int draftVersion = bumpDraftVersion(document);
        ApiDtos.SegmentResponse response = new ApiDtos.SegmentResponse(
                documentId, request.segmentId(), 1, draftVersion, placeholders);
        recordHistory(documentId, request.segmentId(), null, request.requestId(), null,
                OperationType.ADD_SEGMENT, Fingerprint.ordered(
                        "documentId", documentId,
                        "segmentId", request.segmentId(),
                        "sourceText", request.sourceText(),
                        "sourceVersion", 1,
                        "draftVersion", draftVersion,
                        "placeholders", Fingerprint.collection(placeholders)));
        return response;
    }

    /**
     * 源文修订：创建新源版本（版本加一），占位符集合随新版本声明（格式非法 422）；
     * 同时删除该段落全部语言的既有批准，所有目标译文转为待修订，旧译文不得发布；草稿版本加一；
     * 不影响已发布快照。
     */
    @Transactional
    public ApiDtos.SegmentResponse reviseSource(long documentId, String segmentId,
                                              ApiDtos.ReviseSourceRequest request) {
        DocumentRow document = lockDocument(documentId);
        SegmentRow segment = findSegmentOrThrow(documentId, segmentId);
        List<String> placeholders = Placeholders.canonical(request.placeholders());
        int sourceVersion = segment.sourceVersion() + 1;
        repository.updateSegmentSource(documentId, segmentId, request.sourceText(), sourceVersion, placeholders);
        int invalidatedApprovals = repository.deleteApprovalsForSegment(documentId, segmentId);
        int draftVersion = bumpDraftVersion(document);
        ApiDtos.SegmentResponse response = new ApiDtos.SegmentResponse(
                documentId, segmentId, sourceVersion, draftVersion, placeholders);
        recordHistory(documentId, segmentId, null, request.requestId(), null,
                OperationType.REVISE_SOURCE, Fingerprint.ordered(
                        "documentId", documentId,
                        "segmentId", segmentId,
                        "sourceText", request.sourceText(),
                        "sourceVersion", sourceVersion,
                        "previousSourceVersion", segment.sourceVersion(),
                        "draftVersion", draftVersion,
                        "placeholders", Fingerprint.collection(placeholders),
                        "invalidatedApprovals", invalidatedApprovals));
        return response;
    }

    /**
     * 译文提交：所依据源文版本必须等于当前源文版本；绑定当前术语版本，
     * 当前源文命中的术语规则要求译文包含对应必译文本，否则 422 返回全部违规术语且不写译文；
     * 译文中实际占位符集合必须与源段声明集合及出现次数完全一致，否则 422 返回要求/实际/差额；
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
        List<String> actualPlaceholders = Placeholders.extract(request.content());
        Placeholders.Mismatch placeholderMismatch = Placeholders.compare(
                segment.placeholders() == null ? List.of() : segment.placeholders(), actualPlaceholders);
        if (placeholderMismatch != null) {
            throw ApiException.unprocessable(Placeholders.describe(
                    "占位符集合与源段声明不一致: " + segmentId + "/" + normalizedLanguage, placeholderMismatch));
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
        ApiDtos.TranslationResponse response = new ApiDtos.TranslationResponse(
                documentId, segmentId, normalizedLanguage, translationVersion, segment.sourceVersion(),
                document.termVersion(), draftVersion,
                List.copyOf(segment.placeholders() == null ? List.of() : segment.placeholders()),
                actualPlaceholders);
        recordHistory(documentId, segmentId, normalizedLanguage, request.requestId(), actorId,
                OperationType.SUBMIT_TRANSLATION, Fingerprint.ordered(
                        "documentId", documentId,
                        "segmentId", segmentId,
                        "language", normalizedLanguage,
                        "content", request.content(),
                        "translationVersion", translationVersion,
                        "sourceVersion", segment.sourceVersion(),
                        "termVersion", document.termVersion(),
                        "draftVersion", draftVersion,
                        "requiredPlaceholders",
                        Fingerprint.collection(segment.placeholders() == null ? List.of() : segment.placeholders()),
                        "actualPlaceholders", Fingerprint.collection(actualPlaceholders)));
        return response;
    }

    /** 译文批准：审核人不得是作者，必须匹配当前源文与译文版本，且占位符集合当前仍与源段一致。 */
    @Transactional
    public ApiDtos.ApprovalResponse approveTranslation(long documentId, String segmentId, String language,
                                                       String actorId, ApiDtos.ApproveTranslationRequest request) {
        DocumentRow document = lockDocument(documentId);
        ApprovalResult result = validateApproval(document, segmentId, language, actorId,
                request.translationVersion());
        writeApproval(document, result);
        return buildApprovalResponse(document.documentId(), result, request.requestId(), actorId,
                OperationType.APPROVE_TRANSLATION);
    }

    /**
     * 批量批准：先对全部条目做最终状态校验（语言越界、段落/译文缺失、作者自审、版本不符、
     * 占位符不一致等），任一不完整整次 422 且不写入任何批准；全部通过后统一写入并提交。
     * 同一段落+语言重复条目以 422 拒绝，避免同批次内自相覆盖。
     */
    @Transactional
    public ApiDtos.BatchApproveResponse batchApprove(long documentId, String actorId,
                                                     ApiDtos.BatchApproveRequest request) {
        DocumentRow document = lockDocument(documentId);
        Set<String> seen = new HashSet<>();
        List<ApprovalResult> results = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (ApiDtos.ApprovalItemInput item : request.approvals()) {
            String language = normalizeLanguage(item.language());
            String dedupeKey = item.segmentId() + " " + language;
            if (!seen.add(dedupeKey)) {
                errors.add("批量条目重复: " + dedupeKey);
                continue;
            }
            try {
                results.add(validateApproval(document, item.segmentId(), language, actorId,
                        item.translationVersion()));
            } catch (ApiException e) {
                errors.add(item.segmentId() + "/" + language + ": " + e.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.unprocessable("批量批准存在 " + errors.size() + " 条不完整条目，整次未变更: "
                    + String.join(" | ", errors));
        }
        for (ApprovalResult result : results) {
            writeApproval(document, result);
        }
        List<ApiDtos.ApprovalResponse> responses = new ArrayList<>();
        for (ApprovalResult result : results) {
            responses.add(approvalResponseView(document.documentId(), result));
        }
        ApiDtos.BatchApproveResponse response = new ApiDtos.BatchApproveResponse(
                document.documentId(), responses.size(), List.copyOf(responses));
        recordHistory(document.documentId(), null, null, request.requestId(), actorId,
                OperationType.BATCH_APPROVE, Fingerprint.ordered(
                        "documentId", document.documentId(),
                        "approvedCount", responses.size(),
                        "approvals", responses.stream().map(a -> Fingerprint.ordered(
                                "segmentId", a.segmentId(),
                                "language", a.language(),
                                "reviewer", a.reviewer(),
                                "translationVersion", a.translationVersion(),
                                "sourceVersion", a.sourceVersion(),
                                "placeholders", Fingerprint.collection(a.placeholders()))).toList()));
        return response;
    }

    /** 撤回批准：删除该段落语言的批准记录使其重回待修订；不存在批准 404。读取一致状态后删除并提交。 */
    @Transactional
    public ApiDtos.WithdrawResponse withdrawApproval(long documentId, String segmentId, String language,
                                                     String actorId, ApiDtos.WithdrawApprovalRequest request) {
        DocumentRow document = lockDocument(documentId);
        String normalizedLanguage = normalizeLanguage(language);
        if (!document.targetLanguages().contains(normalizedLanguage)) {
            throw ApiException.unprocessable("语言不在文档目标语言中: " + normalizedLanguage);
        }
        findSegmentOrThrow(documentId, segmentId);
        ApprovalRow approval = repository.findApproval(documentId, segmentId, normalizedLanguage)
                .orElseThrow(() -> ApiException.notFound(
                        "批准不存在，无法撤回: " + segmentId + "/" + normalizedLanguage));
        repository.deleteApproval(documentId, segmentId, normalizedLanguage);
        ApiDtos.WithdrawResponse response = new ApiDtos.WithdrawResponse(
                documentId, segmentId, normalizedLanguage, approval.reviewer());
        recordHistory(documentId, segmentId, normalizedLanguage, request.requestId(), actorId,
                OperationType.WITHDRAW_APPROVAL, Fingerprint.ordered(
                        "documentId", documentId,
                        "segmentId", segmentId,
                        "language", normalizedLanguage,
                        "withdrawnBy", actorId,
                        "previousReviewer", approval.reviewer(),
                        "previousSourceVersion", approval.sourceVersion(),
                        "previousTranslationVersion", approval.translationVersion()));
        return response;
    }

    /**
     * 发布：校验期望版本（不符 409），再校验全部段落在全部目标语言均有有效批准（缺译或审核失效 422）、
     * 译文绑定当前术语版本（过期 422）、满足当前术语规则（违规 422 并返回全部违规术语）且占位符集合
     * 与源段完全一致（缺失/额外 422，含要求/实际/差额），全部通过后原子生成完整只读快照
     * （固化术语版本、规则集与占位符集合）并递增发布版本；任何失败回滚，不产生部分快照。
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
        for (SegmentRow segment : segments) {
            List<String> requiredPlaceholders =
                    segment.placeholders() == null ? List.of() : segment.placeholders();
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
                Placeholders.Mismatch placeholderMismatch =
                        Placeholders.compare(requiredPlaceholders, Placeholders.extract(translation.content()));
                if (placeholderMismatch != null) {
                    throw ApiException.unprocessable(Placeholders.describe(
                            "占位符集合与源段声明不一致: " + segment.segmentId() + "/" + language,
                            placeholderMismatch));
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
        ApiDtos.PublishResponse response = new ApiDtos.PublishResponse(documentId, publishedVersion);
        recordHistory(documentId, null, null, request.requestId(), null,
                OperationType.PUBLISH, Fingerprint.ordered(
                        "documentId", documentId,
                        "publishedVersion", publishedVersion,
                        "draftVersion", document.draftVersion(),
                        "termVersion", document.termVersion(),
                        "segmentCount", segments.size(),
                        "translationCount", segments.size() * document.targetLanguages().size()));
        return response;
    }

    /**
     * 新增术语版本：expectedTermVersion 必须等于当前术语版本（不符 409）；
     * 规则 0~100 条、按 sourceTerm 与目标语言唯一、语言须在文档目标语言中（不符 422）。
     * 成功后术语版本加一（已有版本不可覆盖），草稿版本加一；术语快照、历史与草稿版本同一事务提交。
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
        ApiDtos.TermVersionResponse response =
                new ApiDtos.TermVersionResponse(documentId, termVersion, rules.size(), draftVersion);
        recordHistory(documentId, null, null, request.requestId(), null,
                OperationType.UPDATE_TERMS, Fingerprint.ordered(
                        "documentId", documentId,
                        "termVersion", termVersion,
                        "previousTermVersion", document.termVersion(),
                        "ruleCount", rules.size(),
                        "draftVersion", draftVersion,
                        "rules", rules.stream().map(r -> Fingerprint.ordered(
                                "sourceTerm", r.sourceTerm(),
                                "language", r.language(),
                                "requiredTranslation", r.requiredTranslation())).toList()));
        return response;
    }

    /** 文档明细：段落与译文版本、术语绑定、占位符锁定状态及批准状态，稳定排序，只读不改变状态。 */
    @Transactional(readOnly = true)
    public ApiDtos.DocumentDetailResponse getDocumentDetail(long documentId) {
        DocumentRow document = repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        List<TermRuleRow> termRules = repository.listTermRules(documentId, document.termVersion());
        Map<String, TranslationRow> translations = repository.listTranslations(documentId).stream()
                .collect(Collectors.toMap(t -> key(t.segmentId(), t.language()), Function.identity()));
        Map<String, ApprovalRow> approvals = repository.listApprovals(documentId).stream()
                .collect(Collectors.toMap(a -> key(a.segmentId(), a.language()), Function.identity()));
        List<ApiDtos.SegmentDetailView> segmentViews = new ArrayList<>();
        for (SegmentRow segment : repository.listSegments(documentId)) {
            List<String> requiredPlaceholders =
                    segment.placeholders() == null ? List.of() : segment.placeholders();
            List<ApiDtos.TranslationDetailView> translationViews = new ArrayList<>();
            for (String language : document.targetLanguages()) {
                TranslationRow translation = translations.get(key(segment.segmentId(), language));
                if (translation == null) {
                    continue;
                }
                ApiDtos.PlaceholderStatusView placeholderStatus = placeholderStatus(
                        requiredPlaceholders, Placeholders.extract(translation.content()));
                ApprovalRow approval = approvals.get(key(segment.segmentId(), language));
                ApiDtos.ApprovalView approvalView;
                if (approval == null) {
                    approvalView = new ApiDtos.ApprovalView(null, 0, 0, false);
                } else {
                    boolean valid = approval.sourceVersion() == segment.sourceVersion()
                            && approval.translationVersion() == translation.translationVersion();
                    approvalView = new ApiDtos.ApprovalView(approval.reviewer(), approval.sourceVersion(),
                            approval.translationVersion(), valid);
                }
                translationViews.add(new ApiDtos.TranslationDetailView(language, translation.content(),
                        translation.author(), translation.translationVersion(), translation.sourceVersion(),
                        translation.termVersion(), placeholderStatus, approvalView));
            }
            segmentViews.add(new ApiDtos.SegmentDetailView(segment.segmentId(), segment.sourceText(),
                    segment.sourceVersion(), List.copyOf(requiredPlaceholders),
                    List.copyOf(translationViews)));
        }
        return new ApiDtos.DocumentDetailResponse(documentId, document.draftVersion(),
                document.publishedVersion(), document.termVersion(), document.targetLanguages(),
                List.copyOf(segmentViews));
    }

    /**
     * 操作历史：UTC 半开区间 [from, to)，参数为 ISO-8601（如 2026-09-28T10:00:00Z），null 不设界；
     * 按提交顺序（自增 sequence）稳定排序，返回固化字段、微秒精度时间与指纹；纯读取不改变状态。
     */
    @Transactional(readOnly = true)
    public ApiDtos.HistoryResponse getHistory(long documentId, String from, String to) {
        repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        LocalDateTime fromTime = parseUtcBoundary(from, "from");
        LocalDateTime toTime = parseUtcBoundary(to, "to");
        if (fromTime != null && toTime != null && !fromTime.isBefore(toTime)) {
            throw ApiException.badRequest("历史区间须满足 from < to（左闭右开）");
        }
        List<HistoryRow> rows = repository.listHistory(documentId, fromTime, toTime);
        List<ApiDtos.HistoryEntryView> entries = new ArrayList<>();
        for (HistoryRow row : rows) {
            JsonNode detail;
            try {
                detail = objectMapper.readTree(row.detailJson());
            } catch (Exception e) {
                throw new IllegalStateException("历史明细解析失败: sequence=" + row.id(), e);
            }
            entries.add(new ApiDtos.HistoryEntryView(row.id(), row.segmentId(), row.language(),
                    row.requestId(), row.actor(), row.operationType(), row.fingerprint(),
                    row.occurredAtUtc().atOffset(ZoneOffset.UTC).toString(), detail));
        }
        return new ApiDtos.HistoryResponse(documentId, from, to, entries.size(), List.copyOf(entries));
    }

    /** 发布就绪诊断：逐段落逐语言列出实际就绪数量与不可发布原因（含占位符要求/实际/差额），只读。 */
    @Transactional(readOnly = true)
    public ApiDtos.DiagnosticResponse getDiagnostics(long documentId) {
        DocumentRow document = repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        List<SegmentRow> segments = repository.listSegments(documentId);
        Map<String, TranslationRow> translations = repository.listTranslations(documentId).stream()
                .collect(Collectors.toMap(t -> key(t.segmentId(), t.language()), Function.identity()));
        Map<String, ApprovalRow> approvals = repository.listApprovals(documentId).stream()
                .collect(Collectors.toMap(a -> key(a.segmentId(), a.language()), Function.identity()));
        List<TermRuleRow> termRules = repository.listTermRules(documentId, document.termVersion());
        List<ApiDtos.DiagnosticItemView> items = new ArrayList<>();
        int readyCount = 0;
        for (SegmentRow segment : segments) {
            List<String> requiredPlaceholders =
                    segment.placeholders() == null ? List.of() : segment.placeholders();
            for (String language : document.targetLanguages()) {
                List<String> reasons = new ArrayList<>();
                TranslationRow translation = translations.get(key(segment.segmentId(), language));
                ApiDtos.PlaceholderStatusView placeholderStatus;
                if (translation == null) {
                    placeholderStatus = placeholderStatus(requiredPlaceholders, List.of());
                    reasons.add("缺少译文");
                } else {
                    if (translation.sourceVersion() != segment.sourceVersion()) {
                        reasons.add("译文待更新：译文基于源文版本 " + translation.sourceVersion()
                                + "，当前源文版本 " + segment.sourceVersion());
                    }
                    Placeholders.Mismatch mismatch =
                            Placeholders.compare(requiredPlaceholders, Placeholders.extract(translation.content()));
                    placeholderStatus = placeholderStatus(requiredPlaceholders,
                            Placeholders.extract(translation.content()));
                    if (mismatch != null) {
                        reasons.add(Placeholders.describe("占位符集合不一致", mismatch));
                    }
                    if (translation.termVersion() != document.termVersion()) {
                        reasons.add("译文术语版本过期：绑定 " + translation.termVersion()
                                + "，当前 " + document.termVersion());
                    }
                    for (ApiDtos.TermRuleView violation : findViolations(
                            segment.sourceText(), language, translation.content(), termRules)) {
                        reasons.add("术语违规: " + violation.sourceTerm() + " 须译 "
                                + violation.requiredTranslation());
                    }
                }
                ApprovalRow approval = approvals.get(key(segment.segmentId(), language));
                if (approval == null) {
                    reasons.add("缺少批准");
                } else if (translation != null
                        && (approval.translationVersion() != translation.translationVersion()
                        || approval.sourceVersion() != segment.sourceVersion())) {
                    reasons.add("批准已失效：源文或译文版本已改变");
                }
                boolean ready = reasons.isEmpty();
                if (ready) {
                    readyCount++;
                }
                items.add(new ApiDtos.DiagnosticItemView(segment.segmentId(), language, ready,
                        List.copyOf(reasons), placeholderStatus));
            }
        }
        int requiredCount = segments.size() * document.targetLanguages().size();
        return new ApiDtos.DiagnosticResponse(documentId, requiredCount, readyCount,
                readyCount == requiredCount, List.copyOf(items));
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

    /** 单条批准的校验结果（已通过全部最终状态校验，尚未写库）。 */
    private record ApprovalResult(SegmentRow segment, String language, TranslationRow translation,
                                  String reviewer, int translationVersion, int sourceVersion,
                                  List<String> placeholders) {
    }

    private ApprovalResult validateApproval(DocumentRow document, String segmentId, String language,
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
        if (translation.sourceVersion() != segment.sourceVersion()) {
            throw ApiException.unprocessable("译文基于源文版本 " + translation.sourceVersion()
                    + "，当前源文版本 " + segment.sourceVersion() + "，译文待修订，不能批准");
        }
        List<String> requiredPlaceholders =
                segment.placeholders() == null ? List.of() : segment.placeholders();
        Placeholders.Mismatch mismatch =
                Placeholders.compare(requiredPlaceholders, Placeholders.extract(translation.content()));
        if (mismatch != null) {
            throw ApiException.unprocessable(Placeholders.describe(
                    "占位符集合与源段声明不一致: " + segmentId + "/" + normalizedLanguage, mismatch));
        }
        return new ApprovalResult(segment, normalizedLanguage, translation, actorId,
                translation.translationVersion(), segment.sourceVersion(),
                List.copyOf(requiredPlaceholders));
    }

    private void writeApproval(DocumentRow document, ApprovalResult result) {
        repository.upsertApproval(document.documentId(), new ApprovalRow(
                result.segment().segmentId(), result.language(), result.reviewer(),
                result.sourceVersion(), result.translationVersion()));
    }

    private ApiDtos.ApprovalResponse buildApprovalResponse(long documentId, ApprovalResult result,
                                                           String requestId, String actorId,
                                                           String operationType) {
        ApiDtos.ApprovalResponse response = approvalResponseView(documentId, result);
        recordHistory(documentId, result.segment().segmentId(), result.language(), requestId, actorId,
                operationType, Fingerprint.ordered(
                        "documentId", documentId,
                        "segmentId", result.segment().segmentId(),
                        "language", result.language(),
                        "reviewer", result.reviewer(),
                        "translationVersion", result.translationVersion(),
                        "sourceVersion", result.sourceVersion(),
                        "placeholders", Fingerprint.collection(result.placeholders())));
        return response;
    }

    private static ApiDtos.ApprovalResponse approvalResponseView(long documentId, ApprovalResult result) {
        return new ApiDtos.ApprovalResponse(documentId, result.segment().segmentId(), result.language(),
                result.reviewer(), result.translationVersion(), result.sourceVersion(),
                List.copyOf(result.placeholders()));
    }

    private static ApiDtos.PlaceholderStatusView placeholderStatus(List<String> required, List<String> actual) {
        Placeholders.Mismatch mismatch = Placeholders.compare(required, actual);
        if (mismatch == null) {
            return new ApiDtos.PlaceholderStatusView(List.copyOf(required), List.copyOf(actual), true,
                    List.of(), List.of());
        }
        List<ApiDtos.PlaceholderDiffView> missing = mismatch.missing().stream()
                .map(d -> new ApiDtos.PlaceholderDiffView(d.placeholder(), d.requiredCount(),
                        d.actualCount(), d.difference())).toList();
        List<ApiDtos.PlaceholderDiffView> extra = mismatch.extra().stream()
                .map(d -> new ApiDtos.PlaceholderDiffView(d.placeholder(), d.requiredCount(),
                        d.actualCount(), d.difference())).toList();
        return new ApiDtos.PlaceholderStatusView(List.copyOf(required), List.copyOf(actual), false,
                missing, extra);
    }

    /** 追加不可变历史证据：UTC 微秒精度时间，与业务变更同一事务，只追加不改写。 */
    private void recordHistory(long documentId, String segmentId, String language, String requestId,
                               String actor, String operationType, Map<String, Object> resultFields) {
        String detailJson;
        try {
            detailJson = objectMapper.writeValueAsString(resultFields);
        } catch (Exception e) {
            throw new IllegalStateException("历史明细序列化失败", e);
        }
        String fingerprint = Fingerprint.of(objectMapper, operationType, actor, resultFields);
        LocalDateTime occurredAtUtc = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        repository.insertHistory(new HistoryRow(0L, documentId, segmentId, language, requestId, actor,
                operationType, fingerprint, detailJson, occurredAtUtc));
    }

    private LocalDateTime parseUtcBoundary(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            Instant instant = Instant.parse(value.trim());
            return LocalDateTime.ofInstant(instant, ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(field + " 须为 ISO-8601 UTC 时间，如 2026-09-28T10:00:00Z");
        }
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

    /** 生成完整只读快照 JSON：段落占位符集合、全部译文（含实际占位符与锁定状态）及固化术语版本与规则集。 */
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
            List<String> requiredPlaceholders =
                    segment.placeholders() == null ? List.of() : segment.placeholders();
            Map<String, Object> segmentJson = new LinkedHashMap<>();
            segmentJson.put("segmentId", segment.segmentId());
            segmentJson.put("sourceText", segment.sourceText());
            segmentJson.put("sourceVersion", segment.sourceVersion());
            segmentJson.put("placeholders", requiredPlaceholders);
            List<Map<String, Object>> translationList = new ArrayList<>();
            for (String language : document.targetLanguages()) {
                TranslationRow translation = translations.get(key(segment.segmentId(), language));
                ApprovalRow approval = approvals.get(key(segment.segmentId(), language));
                List<String> actualPlaceholders = Placeholders.extract(translation.content());
                Map<String, Object> translationJson = new LinkedHashMap<>();
                translationJson.put("language", language);
                translationJson.put("content", translation.content());
                translationJson.put("author", translation.author());
                translationJson.put("translationVersion", translation.translationVersion());
                translationJson.put("sourceVersion", translation.sourceVersion());
                translationJson.put("termVersion", translation.termVersion());
                translationJson.put("requiredPlaceholders", requiredPlaceholders);
                translationJson.put("actualPlaceholders", actualPlaceholders);
                translationJson.put("placeholderMatched",
                        Placeholders.compare(requiredPlaceholders, actualPlaceholders) == null);
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

    /** 操作类型常量，与 operation_history.operation_type 注释保持一致。 */
    private static final class OperationType {
        static final String CREATE_DOCUMENT = "CREATE_DOCUMENT";
        static final String ADD_SEGMENT = "ADD_SEGMENT";
        static final String REVISE_SOURCE = "REVISE_SOURCE";
        static final String SUBMIT_TRANSLATION = "SUBMIT_TRANSLATION";
        static final String APPROVE_TRANSLATION = "APPROVE_TRANSLATION";
        static final String BATCH_APPROVE = "BATCH_APPROVE";
        static final String WITHDRAW_APPROVAL = "WITHDRAW_APPROVAL";
        static final String PUBLISH = "PUBLISH";
        static final String UPDATE_TERMS = "UPDATE_TERMS";

        private OperationType() {
        }
    }
}
