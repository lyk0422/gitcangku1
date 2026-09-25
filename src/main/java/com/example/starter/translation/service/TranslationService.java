package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiDtos;
import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.domain.Rows.ApprovalRow;
import com.example.starter.translation.domain.Rows.DocumentRow;
import com.example.starter.translation.domain.Rows.LegalSignRow;
import com.example.starter.translation.domain.Rows.SegmentRow;
import com.example.starter.translation.domain.Rows.TermRuleRow;
import com.example.starter.translation.domain.Rows.TranslationRow;
import com.example.starter.translation.repo.TranslationRepository;
import com.fasterxml.jackson.databind.JsonNode;
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
     * 法律审签：仅已批准（批准仍有效）的译文可由法务人员提交终态审签 APPROVED/REJECTED；
     * expectedVersion 必须等于当前译文版本（不符 422），REJECTED 的说明不能为空。
     * 同一译文版本仅保留最后一条终态审签（可由不同法务人员发起，后到终态覆盖先前终态）；
     * 译文修订产生新版本后旧审签仅归属旧版本，新版本须重新审签。
     */
    @Transactional
    public ApiDtos.LegalSignResponse legalSign(long documentId, String segmentId, String language,
                                               String actorId, ApiDtos.LegalSignRequest request) {
        DocumentRow document = lockDocument(documentId);
        String normalizedLanguage = normalizeLanguage(language);
        if (!document.targetLanguages().contains(normalizedLanguage)) {
            throw ApiException.unprocessable("语言不在文档目标语言中: " + normalizedLanguage);
        }
        SegmentRow segment = findSegmentOrThrow(documentId, segmentId);
        TranslationRow translation = repository.findTranslation(documentId, segmentId, normalizedLanguage)
                .orElseThrow(() -> ApiException.notFound(
                        "译文不存在: " + segmentId + "/" + normalizedLanguage));
        String status = request.status() == null ? "" : request.status().trim().toUpperCase(Locale.ROOT);
        if (!"APPROVED".equals(status) && !"REJECTED".equals(status)) {
            throw ApiException.unprocessable("审签状态必须为 APPROVED 或 REJECTED");
        }
        String reason = request.reason() == null ? "" : request.reason().trim();
        if ("REJECTED".equals(status) && reason.isEmpty()) {
            throw ApiException.unprocessable("拒绝说明不能为空");
        }
        if (translation.translationVersion() != request.expectedVersion()) {
            throw ApiException.unprocessable("审签所针对的译文版本 " + request.expectedVersion()
                    + " 与当前译文版本 " + translation.translationVersion() + " 不匹配");
        }
        ApprovalRow approval = repository.findApproval(documentId, segmentId, normalizedLanguage)
                .orElseThrow(() -> ApiException.unprocessable(
                        "译文尚未批准，不能提交法律审签: " + segmentId + "/" + normalizedLanguage));
        if (approval.translationVersion() != translation.translationVersion()
                || approval.sourceVersion() != segment.sourceVersion()) {
            throw ApiException.unprocessable("译文批准已失效，需重新批准后再提交法律审签: "
                    + segmentId + "/" + normalizedLanguage);
        }
        repository.upsertLegalSign(documentId, new LegalSignRow(segmentId, normalizedLanguage,
                translation.translationVersion(), actorId, status, reason));
        return new ApiDtos.LegalSignResponse(documentId, segmentId, normalizedLanguage,
                translation.translationVersion(), actorId, status, reason);
    }

    /** 查询指定段落、语言的逐版审签历史：每译文版本仅含其最后一条终态审签，按译文版本排序。 */
    @Transactional(readOnly = true)
    public ApiDtos.LegalSignHistoryResponse getLegalSignHistory(long documentId, String segmentId,
                                                                String language) {
        repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        String normalizedLanguage = normalizeLanguage(language);
        findSegmentOrThrow(documentId, segmentId);
        List<ApiDtos.LegalSignView> signs = repository
                .listLegalSigns(documentId, segmentId, normalizedLanguage).stream()
                .map(TranslationService::toLegalSignView).toList();
        return new ApiDtos.LegalSignHistoryResponse(documentId, segmentId, normalizedLanguage, signs);
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
     * 并且每个纳入快照的译文版本均有 APPROVED 法律审签（缺失或 REJECTED 422，返回稳定排序的全部阻断条目），
     * 全部通过后原子生成完整只读快照（固化术语版本、实际规则集与所用审签版本）并递增发布版本；
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
        Map<String, LegalSignRow> legalSigns = latestLegalSignsByTranslation(documentId);
        List<TermRuleRow> termRules = repository.listTermRules(documentId, document.termVersion());
        List<ApiDtos.TermRuleView> termViolations = new ArrayList<>();
        List<ApiDtos.PublishBlocker> blockers = new ArrayList<>();
        for (SegmentRow segment : segments) {
            for (String language : document.targetLanguages()) {
                TranslationRow translation = translations.get(key(segment.segmentId(), language));
                if (translation == null) {
                    blockers.add(new ApiDtos.PublishBlocker(segment.segmentId(), language, 0,
                            "缺少译文"));
                    continue;
                }
                int translationVersion = translation.translationVersion();
                if (translation.sourceVersion() != segment.sourceVersion()) {
                    blockers.add(new ApiDtos.PublishBlocker(segment.segmentId(), language, translationVersion,
                            "译文待更新：基于源文版本 " + translation.sourceVersion()
                                    + "，当前源文版本 " + segment.sourceVersion()));
                }
                if (translation.termVersion() != document.termVersion()) {
                    blockers.add(new ApiDtos.PublishBlocker(segment.segmentId(), language, translationVersion,
                            "译文术语版本过期：绑定术语版本 " + translation.termVersion()
                                    + "，当前术语版本 " + document.termVersion()));
                }
                ApprovalRow approval = approvals.get(key(segment.segmentId(), language));
                if (approval == null) {
                    blockers.add(new ApiDtos.PublishBlocker(segment.segmentId(), language, translationVersion,
                            "缺少批准"));
                } else if (approval.translationVersion() != translationVersion
                        || approval.sourceVersion() != segment.sourceVersion()) {
                    blockers.add(new ApiDtos.PublishBlocker(segment.segmentId(), language, translationVersion,
                            "批准已失效：源文或译文版本已改变"));
                }
                LegalSignRow legalSign = legalSigns.get(key(segment.segmentId(), language));
                if (legalSign == null) {
                    blockers.add(new ApiDtos.PublishBlocker(segment.segmentId(), language, translationVersion,
                            "缺少法律审签"));
                } else if (legalSign.translationVersion() != translationVersion) {
                    blockers.add(new ApiDtos.PublishBlocker(segment.segmentId(), language, translationVersion,
                            "法律审签仅归属旧译文版本 " + legalSign.translationVersion()
                                    + "，当前译文版本 " + translationVersion + " 需重新审签"));
                } else if ("REJECTED".equals(legalSign.status())) {
                    blockers.add(new ApiDtos.PublishBlocker(segment.segmentId(), language, translationVersion,
                            "法律审签拒绝：" + legalSign.reason()));
                }
                termViolations.addAll(findViolations(
                        segment.sourceText(), language, translation.content(), termRules));
            }
        }
        blockers.sort(Comparator.comparing(ApiDtos.PublishBlocker::segmentId)
                .thenComparing(ApiDtos.PublishBlocker::language));
        if (!blockers.isEmpty()) {
            throw ApiException.publishBlocked("发布被 " + blockers.size() + " 项问题阻断", blockers);
        }
        if (!termViolations.isEmpty()) {
            throw ApiException.termViolation("译文违反 " + termViolations.size() + " 条术语规则", termViolations);
        }
        int publishedVersion = document.publishedVersion() + 1;
        repository.insertSnapshot(documentId, publishedVersion,
                buildSnapshotJson(document, publishedVersion, segments, translations, approvals,
                        legalSigns, termRules));
        repository.updatePublishedVersion(documentId, publishedVersion);
        return new ApiDtos.PublishResponse(documentId, publishedVersion);
    }

    /**
     * 发布阻断诊断：返回当前状态下全部阻断条目（含缺少法律审签/拒绝），按段落、语言稳定排序；
     * 空列表表示当前可发布。不做期望版本校验，不产生任何变更。
     */
    @Transactional(readOnly = true)
    public ApiDtos.PublishDiagnosticsResponse getPublishDiagnostics(long documentId) {
        DocumentRow document = repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        List<SegmentRow> segments = repository.listSegments(documentId);
        Map<String, TranslationRow> translations = repository.listTranslations(documentId).stream()
                .collect(Collectors.toMap(t -> key(t.segmentId(), t.language()), Function.identity()));
        Map<String, ApprovalRow> approvals = repository.listApprovals(documentId).stream()
                .collect(Collectors.toMap(a -> key(a.segmentId(), a.language()), Function.identity()));
        Map<String, LegalSignRow> legalSigns = latestLegalSignsByTranslation(documentId);
        List<ApiDtos.PublishBlocker> blockers = new ArrayList<>();
        for (SegmentRow segment : segments) {
            for (String language : document.targetLanguages()) {
                TranslationRow translation = translations.get(key(segment.segmentId(), language));
                if (translation == null) {
                    blockers.add(new ApiDtos.PublishBlocker(segment.segmentId(), language, 0, "缺少译文"));
                    continue;
                }
                int translationVersion = translation.translationVersion();
                if (translation.sourceVersion() != segment.sourceVersion()) {
                    blockers.add(new ApiDtos.PublishBlocker(segment.segmentId(), language, translationVersion,
                            "译文待更新"));
                }
                if (translation.termVersion() != document.termVersion()) {
                    blockers.add(new ApiDtos.PublishBlocker(segment.segmentId(), language, translationVersion,
                            "译文术语版本过期"));
                }
                ApprovalRow approval = approvals.get(key(segment.segmentId(), language));
                if (approval == null
                        || approval.translationVersion() != translationVersion
                        || approval.sourceVersion() != segment.sourceVersion()) {
                    blockers.add(new ApiDtos.PublishBlocker(segment.segmentId(), language, translationVersion,
                            approval == null ? "缺少批准" : "批准已失效"));
                }
                LegalSignRow legalSign = legalSigns.get(key(segment.segmentId(), language));
                if (legalSign == null || legalSign.translationVersion() != translationVersion) {
                    blockers.add(new ApiDtos.PublishBlocker(segment.segmentId(), language, translationVersion,
                            "缺少法律审签"));
                } else if ("REJECTED".equals(legalSign.status())) {
                    blockers.add(new ApiDtos.PublishBlocker(segment.segmentId(), language, translationVersion,
                            "法律审签拒绝：" + legalSign.reason()));
                }
            }
        }
        blockers.sort(Comparator.comparing(ApiDtos.PublishBlocker::segmentId)
                .thenComparing(ApiDtos.PublishBlocker::language));
        return new ApiDtos.PublishDiagnosticsResponse(documentId, document.draftVersion(),
                document.publishedVersion(), blockers);
    }

    /**
     * 查询已发布快照所用审签版本：从冻结的快照 JSON 中解析每个段落、语言的译文版本及其
     * APPROVED 审签信息；后续对同一译文版本的拒绝不追溯改变此结果。发布版本不存在返回 404。
     */
    @Transactional(readOnly = true)
    public List<ApiDtos.ReleaseLegalSignView> getReleaseLegalSigns(long documentId, int publishedVersion) {
        repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        String snapshotJson = repository.findSnapshot(documentId, publishedVersion)
                .orElseThrow(() -> ApiException.notFound(
                        "发布版本不存在: " + documentId + "/" + publishedVersion));
        try {
            JsonNode root = objectMapper.readTree(snapshotJson);
            List<ApiDtos.ReleaseLegalSignView> views = new ArrayList<>();
            for (JsonNode segmentNode : root.path("segments")) {
                String segmentId = segmentNode.path("segmentId").asText();
                for (JsonNode translationNode : segmentNode.path("translations")) {
                    views.add(new ApiDtos.ReleaseLegalSignView(segmentId,
                            translationNode.path("language").asText(),
                            translationNode.path("translationVersion").asInt(),
                            textOrNull(translationNode, "legalReviewer"),
                            textOrNull(translationNode, "legalStatus")));
                }
            }
            return views;
        } catch (Exception e) {
            throw new IllegalStateException("快照解析失败", e);
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    /**
     * 取每个段落、语言当前最新译文版本上的审签（按译文版本升序遍历，后者覆盖前者）；
     * 仅用于判定当前状态：旧译文版本上的审签不会被误算到新版本。
     */
    private Map<String, LegalSignRow> latestLegalSignsByTranslation(long documentId) {
        Map<String, LegalSignRow> latest = new LinkedHashMap<>();
        for (LegalSignRow sign : repository.listLegalSigns(documentId)) {
            latest.put(key(sign.segmentId(), sign.language()), sign);
        }
        return latest;
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

    private static ApiDtos.LegalSignView toLegalSignView(LegalSignRow sign) {
        return new ApiDtos.LegalSignView(sign.segmentId(), sign.language(), sign.translationVersion(),
                sign.legalReviewer(), sign.status(), sign.reason());
    }

    /** 生成完整只读快照 JSON：全部段落源文及各语言译文、作者、审核人、法律审签、版本号与固化的术语版本及规则集。 */
    private String buildSnapshotJson(DocumentRow document, int publishedVersion, List<SegmentRow> segments,
                                     Map<String, TranslationRow> translations,
                                     Map<String, ApprovalRow> approvals,
                                     Map<String, LegalSignRow> legalSigns,
                                     List<TermRuleRow> termRules) {
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
                LegalSignRow legalSign = legalSigns.get(key(segment.segmentId(), language));
                Map<String, Object> translationJson = new LinkedHashMap<>();
                translationJson.put("language", language);
                translationJson.put("content", translation.content());
                translationJson.put("author", translation.author());
                translationJson.put("translationVersion", translation.translationVersion());
                translationJson.put("sourceVersion", translation.sourceVersion());
                translationJson.put("termVersion", translation.termVersion());
                translationJson.put("reviewer", approval.reviewer());
                translationJson.put("legalReviewer", legalSign.legalReviewer());
                translationJson.put("legalStatus", legalSign.status());
                translationJson.put("legalReason", legalSign.reason());
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
