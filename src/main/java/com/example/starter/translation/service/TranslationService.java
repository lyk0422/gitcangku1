package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiDtos;
import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.domain.Rows.ApprovalRow;
import com.example.starter.translation.domain.Rows.DocumentRow;
import com.example.starter.translation.domain.Rows.SegmentRow;
import com.example.starter.translation.domain.Rows.TermFreezeEntryRow;
import com.example.starter.translation.domain.Rows.TermFreezeRow;
import com.example.starter.translation.domain.Rows.TermRuleRow;
import com.example.starter.translation.domain.Rows.TranslationRow;
import com.example.starter.translation.repo.TranslationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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

    /** 增加段落：segmentId 文档内唯一，草稿版本与文档版本各加一。 */
    @Transactional
    public ApiDtos.SegmentResponse addSegment(long documentId, ApiDtos.AddSegmentRequest request) {
        DocumentRow document = lockDocument(documentId);
        if (repository.findSegment(documentId, request.segmentId()).isPresent()) {
            throw ApiException.conflict("段落已存在: " + request.segmentId());
        }
        repository.insertSegment(documentId, request.segmentId(), request.sourceText());
        bumpDocumentVersion(document);
        int draftVersion = bumpDraftVersion(document);
        return new ApiDtos.SegmentResponse(documentId, request.segmentId(), 1, draftVersion);
    }

    /** 源文修订：源文版本加一、草稿版本与文档版本各加一；相关译文因源文版本落后而待更新。 */
    @Transactional
    public ApiDtos.SegmentResponse reviseSource(long documentId, String segmentId,
                                              ApiDtos.ReviseSourceRequest request) {
        DocumentRow document = lockDocument(documentId);
        SegmentRow segment = findSegmentOrThrow(documentId, segmentId);
        int sourceVersion = segment.sourceVersion() + 1;
        repository.updateSegmentSource(documentId, segmentId, request.sourceText(), sourceVersion);
        bumpDocumentVersion(document);
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
        TermFreezeRow freeze = effectiveFreeze(document);
        if (freeze != null) {
            List<ApiDtos.FreezeViolationView> freezeViolations = findFreezeViolations(segmentId,
                    normalizedLanguage, segment.sourceText(), request.content(),
                    groupFreezeEntries(repository.listFreezeEntries(documentId, freeze.freezeVersion())));
            if (!freezeViolations.isEmpty()) {
                throw ApiException.freezeViolation(
                        "译文未采用冻结允许译法，共 " + freezeViolations.size() + " 处", freezeViolations);
            }
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
        TermFreezeRow freeze = effectiveFreeze(document);
        List<ApiDtos.FreezeEntryView> freezeEntries = List.of();
        if (freeze != null) {
            List<FreezeTermGroup> groups =
                    groupFreezeEntries(repository.listFreezeEntries(documentId, freeze.freezeVersion()));
            List<ApiDtos.FreezeViolationView> freezeViolations = new ArrayList<>();
            for (SegmentRow segment : segments) {
                for (String language : document.targetLanguages()) {
                    TranslationRow translation = translations.get(key(segment.segmentId(), language));
                    freezeViolations.addAll(findFreezeViolations(segment.segmentId(), language,
                            segment.sourceText(), translation.content(), groups));
                }
            }
            sortFreezeViolations(freezeViolations);
            if (!freezeViolations.isEmpty()) {
                throw ApiException.freezeViolation(
                        "译文未采用冻结允许译法，共 " + freezeViolations.size() + " 处", freezeViolations);
            }
            freezeEntries = groups.stream()
                    .map(g -> new ApiDtos.FreezeEntryView(g.term(), g.language(), g.allowedTranslations()))
                    .toList();
        }
        int publishedVersion = document.publishedVersion() + 1;
        repository.insertSnapshot(documentId, publishedVersion,
                buildSnapshotJson(document, publishedVersion, segments, translations, approvals, termRules,
                        freeze, freezeEntries));
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

    /** 文档版本加一：仅源文景观变化（增段落、源文修订）时调用，使既有术语冻结失效。 */
    private int bumpDocumentVersion(DocumentRow document) {
        int documentVersion = document.documentVersion() + 1;
        repository.updateDocumentVersion(document.documentId(), documentVersion);
        return documentVersion;
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
                                     Map<String, ApprovalRow> approvals, List<TermRuleRow> termRules,
                                     TermFreezeRow freeze, List<ApiDtos.FreezeEntryView> freezeEntries) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("documentId", document.documentId());
        snapshot.put("publishedVersion", publishedVersion);
        snapshot.put("draftVersion", document.draftVersion());
        snapshot.put("termVersion", document.termVersion());
        snapshot.put("freezeVersion", freeze == null ? null : freeze.freezeVersion());
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
        List<Map<String, Object>> freezeTermList = new ArrayList<>();
        for (ApiDtos.FreezeEntryView entry : freezeEntries) {
            Map<String, Object> entryJson = new LinkedHashMap<>();
            entryJson.put("term", entry.term());
            entryJson.put("language", entry.language());
            entryJson.put("allowedTranslations", entry.allowedTranslations());
            freezeTermList.add(entryJson);
        }
        snapshot.put("freezeTerms", freezeTermList);
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
     * 创建术语冻结：条目规范化后落库，创建后不可原地修改；同一文档版本只允许一份有效冻结（冲突 409）。
     * freezeKey 指纹含文档 ID、文档版本、规范化术语条目、操作者与状态；同键重放已记录冻结，
     * 校验失败不落库、不占键。旧文档版本上的有效冻结随新冻结创建自动撤销（旧冻结不能复用）。
     */
    @Transactional
    public ApiDtos.FreezeResponse createFreeze(long documentId, String operator,
                                               ApiDtos.CreateFreezeRequest request) {
        DocumentRow document = lockDocument(documentId);
        List<TermFreezeEntryRow> entries = normalizeFreezeEntries(document, request.entries());
        String freezeKey = computeFreezeKey(documentId, document.documentVersion(), operator, entries);
        Optional<TermFreezeRow> replayed = repository.findFreezeByKey(freezeKey);
        if (replayed.isPresent()) {
            TermFreezeRow existing = replayed.get();
            int entryCount = repository.listFreezeEntries(documentId, existing.freezeVersion()).size();
            return new ApiDtos.FreezeResponse(documentId, existing.freezeVersion(),
                    existing.documentVersion(), existing.status(), existing.freezeKey(), entryCount);
        }
        Optional<TermFreezeRow> active = repository.findActiveFreeze(documentId);
        if (active.isPresent() && active.get().documentVersion() == document.documentVersion()) {
            throw ApiException.freezeConflict("当前文档版本 " + document.documentVersion()
                    + " 已存在有效冻结: " + active.get().freezeVersion());
        }
        repository.revokeStaleActiveFreezes(documentId, document.documentVersion());
        int freezeVersion = repository.maxFreezeVersion(documentId) + 1;
        try {
            repository.insertTermFreeze(documentId,
                    new TermFreezeRow(freezeVersion, document.documentVersion(), "ACTIVE", freezeKey, operator));
        } catch (DuplicateKeyException e) {
            // 并发创建：同 freezeKey 由先提交者占键，重放其结果；异键则同版本已有有效冻结
            Optional<TermFreezeRow> committed = repository.findFreezeByKey(freezeKey);
            if (committed.isPresent()) {
                TermFreezeRow existing = committed.get();
                int entryCount = repository.listFreezeEntries(documentId, existing.freezeVersion()).size();
                return new ApiDtos.FreezeResponse(documentId, existing.freezeVersion(),
                        existing.documentVersion(), existing.status(), existing.freezeKey(), entryCount);
            }
            throw ApiException.freezeConflict(
                    "当前文档版本 " + document.documentVersion() + " 已存在有效冻结");
        }
        for (TermFreezeEntryRow entry : entries) {
            repository.insertTermFreezeEntry(documentId, freezeVersion, entry);
        }
        return new ApiDtos.FreezeResponse(documentId, freezeVersion, document.documentVersion(),
                "ACTIVE", freezeKey, entries.size());
    }

    /** 撤销术语冻结：只影响后续修订与发布，不重写既有快照；已撤销或非当前有效状态重复撤销返回 409。 */
    @Transactional
    public ApiDtos.FreezeResponse revokeFreeze(long documentId, int freezeVersion) {
        lockDocument(documentId);
        TermFreezeRow freeze = repository.findFreeze(documentId, freezeVersion)
                .orElseThrow(() -> ApiException.notFound("冻结不存在: " + documentId + "/" + freezeVersion));
        if (!"ACTIVE".equals(freeze.status())) {
            throw ApiException.freezeConflict("冻结已撤销，不能重复撤销: " + freezeVersion);
        }
        repository.revokeFreeze(documentId, freezeVersion);
        int entryCount = repository.listFreezeEntries(documentId, freezeVersion).size();
        return new ApiDtos.FreezeResponse(documentId, freezeVersion, freeze.documentVersion(),
                "REVOKED", freeze.freezeKey(), entryCount);
    }

    /** 查询当前有效冻结的完整内容；current 表示冻结绑定的文档版本是否仍为当前草稿版本。无有效冻结返回 404。 */
    @Transactional(readOnly = true)
    public ApiDtos.FreezeView getCurrentFreeze(long documentId) {
        DocumentRow document = repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        TermFreezeRow freeze = repository.findActiveFreeze(documentId)
                .orElseThrow(() -> ApiException.notFound("文档当前无有效冻结: " + documentId));
        return toFreezeView(document, freeze);
    }

    /** 查询指定冻结版本的内容（含已撤销）；不存在返回 404。 */
    @Transactional(readOnly = true)
    public ApiDtos.FreezeView getFreeze(long documentId, int freezeVersion) {
        DocumentRow document = repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        TermFreezeRow freeze = repository.findFreeze(documentId, freezeVersion)
                .orElseThrow(() -> ApiException.notFound("冻结不存在: " + documentId + "/" + freezeVersion));
        return toFreezeView(document, freeze);
    }

    /**
     * 冻结段落诊断：按当前有效冻结逐条核对全部译文，列出命中术语、允许译法及是否采用；
     * 无有效冻结时 freezeVersion 为 null 且各译文核对列表为空。
     */
    @Transactional(readOnly = true)
    public ApiDtos.FreezeDiagnosticsResponse getFreezeDiagnostics(long documentId) {
        DocumentRow document = repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        TermFreezeRow freeze = effectiveFreeze(document);
        List<FreezeTermGroup> groups = freeze == null ? List.of()
                : groupFreezeEntries(repository.listFreezeEntries(documentId, freeze.freezeVersion()));
        Map<String, SegmentRow> segments = repository.listSegments(documentId).stream()
                .collect(Collectors.toMap(SegmentRow::segmentId, Function.identity()));
        List<ApiDtos.FreezeDiagnosticView> views = new ArrayList<>();
        for (TranslationRow translation : repository.listTranslations(documentId)) {
            SegmentRow segment = segments.get(translation.segmentId());
            List<ApiDtos.FreezeTermCheck> checks = new ArrayList<>();
            if (segment != null) {
                String normalizedSource = normalizeTermText(segment.sourceText());
                String normalizedContent = normalizeTermText(translation.content());
                for (FreezeTermGroup group : groups) {
                    if (group.language().equals(translation.language())
                            && normalizedSource.contains(group.term())) {
                        boolean satisfied = group.allowedTranslations().stream()
                                .anyMatch(normalizedContent::contains);
                        checks.add(new ApiDtos.FreezeTermCheck(group.term(), group.language(),
                                group.allowedTranslations(), satisfied));
                    }
                }
            }
            views.add(new ApiDtos.FreezeDiagnosticView(
                    translation.segmentId(), translation.language(), checks));
        }
        return new ApiDtos.FreezeDiagnosticsResponse(documentId,
                freeze == null ? null : freeze.freezeVersion(), views);
    }

    /**
     * 批量译文修订：同一事务内先校验全部修订（语言、段落、源文版本），再按最终段落版本做冻结校验
     * （任一违反 422 并稳定列出段落与术语），然后校验既有术语规则，全部通过后整批应用并将草稿版本加一；
     * 任一失败整批回滚，不留半成品状态。
     */
    @Transactional
    public ApiDtos.BatchRevisionResponse submitBatchRevisions(long documentId, String actorId,
                                                              ApiDtos.BatchRevisionRequest request) {
        DocumentRow document = lockDocument(documentId);
        List<SegmentRow> segments = new ArrayList<>();
        List<String> languages = new ArrayList<>();
        Set<List<String>> seen = new HashSet<>();
        for (ApiDtos.RevisionInput revision : request.revisions()) {
            String language = normalizeLanguage(revision.language());
            if (!document.targetLanguages().contains(language)) {
                throw ApiException.unprocessable("语言不在文档目标语言中: " + language);
            }
            if (!seen.add(List.of(revision.segmentId(), language))) {
                throw ApiException.unprocessable(
                        "批次内段落语言重复: " + revision.segmentId() + "/" + language);
            }
            SegmentRow segment = findSegmentOrThrow(documentId, revision.segmentId());
            if (revision.sourceVersion() != segment.sourceVersion()) {
                throw ApiException.unprocessable("修订所依据的源文版本 " + revision.sourceVersion()
                        + " 与段落 " + revision.segmentId() + " 当前源文版本 "
                        + segment.sourceVersion() + " 不匹配");
            }
            segments.add(segment);
            languages.add(language);
        }
        TermFreezeRow freeze = effectiveFreeze(document);
        if (freeze != null) {
            List<FreezeTermGroup> groups =
                    groupFreezeEntries(repository.listFreezeEntries(documentId, freeze.freezeVersion()));
            List<ApiDtos.FreezeViolationView> freezeViolations = new ArrayList<>();
            for (int i = 0; i < segments.size(); i++) {
                ApiDtos.RevisionInput revision = request.revisions().get(i);
                freezeViolations.addAll(findFreezeViolations(revision.segmentId(), languages.get(i),
                        segments.get(i).sourceText(), revision.content(), groups));
            }
            sortFreezeViolations(freezeViolations);
            if (!freezeViolations.isEmpty()) {
                throw ApiException.freezeViolation(
                        "译文未采用冻结允许译法，共 " + freezeViolations.size() + " 处", freezeViolations);
            }
        }
        List<TermRuleRow> termRules = repository.listTermRules(documentId, document.termVersion());
        List<ApiDtos.TermRuleView> termViolations = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            termViolations.addAll(findViolations(segments.get(i).sourceText(), languages.get(i),
                    request.revisions().get(i).content(), termRules));
        }
        if (!termViolations.isEmpty()) {
            throw ApiException.termViolation("译文违反 " + termViolations.size() + " 条术语规则", termViolations);
        }
        List<ApiDtos.RevisionResult> results = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            ApiDtos.RevisionInput revision = request.revisions().get(i);
            String language = languages.get(i);
            SegmentRow segment = segments.get(i);
            int translationVersion = repository.findTranslation(documentId, revision.segmentId(), language)
                    .map(TranslationRow::translationVersion).orElse(0) + 1;
            repository.upsertTranslation(documentId, new TranslationRow(revision.segmentId(), language,
                    revision.content(), actorId, segment.sourceVersion(), translationVersion,
                    document.termVersion()));
            results.add(new ApiDtos.RevisionResult(revision.segmentId(), language, translationVersion,
                    segment.sourceVersion(), document.termVersion()));
        }
        int draftVersion = bumpDraftVersion(document);
        return new ApiDtos.BatchRevisionResponse(documentId, draftVersion, results.size(), results);
    }

    /** 有效冻结：状态 ACTIVE 且绑定当前文档版本；文档版本变化后旧冻结不能复用。 */
    private TermFreezeRow effectiveFreeze(DocumentRow document) {
        return repository.findActiveFreeze(document.documentId())
                .filter(freeze -> freeze.documentVersion() == document.documentVersion())
                .orElse(null);
    }

    private ApiDtos.FreezeView toFreezeView(DocumentRow document, TermFreezeRow freeze) {
        List<FreezeTermGroup> groups =
                groupFreezeEntries(repository.listFreezeEntries(document.documentId(), freeze.freezeVersion()));
        List<ApiDtos.FreezeEntryView> entries = groups.stream()
                .map(group -> new ApiDtos.FreezeEntryView(group.term(), group.language(),
                        group.allowedTranslations()))
                .toList();
        return new ApiDtos.FreezeView(document.documentId(), freeze.freezeVersion(), freeze.documentVersion(),
                freeze.status(), freeze.freezeKey(), freeze.operator(),
                freeze.documentVersion() == document.documentVersion(), entries);
    }

    /** 规范化并校验冻结条目：语言须在目标语言中，规范化后非空，按（术语、语言、译法）去重，排序保证稳定。 */
    private static List<TermFreezeEntryRow> normalizeFreezeEntries(DocumentRow document,
                                                                   List<ApiDtos.FreezeEntryInput> inputs) {
        List<TermFreezeEntryRow> entries = new ArrayList<>();
        Set<List<String>> seen = new HashSet<>();
        for (ApiDtos.FreezeEntryInput input : inputs) {
            String language = normalizeLanguage(input.language());
            if (!document.targetLanguages().contains(language)) {
                throw ApiException.unprocessable("冻结条目语言不在文档目标语言中: " + language);
            }
            String term = normalizeTermText(input.term());
            if (term.isEmpty()) {
                throw ApiException.unprocessable("术语规范化后为空");
            }
            for (String rawAllowed : input.allowedTranslations()) {
                String allowed = normalizeTermText(rawAllowed);
                if (allowed.isEmpty()) {
                    throw ApiException.unprocessable("允许译法规范化后为空: " + input.term());
                }
                if (!seen.add(List.of(term, language, allowed))) {
                    throw ApiException.unprocessable(
                            "冻结条目重复: " + term + "/" + language + "/" + allowed);
                }
                entries.add(new TermFreezeEntryRow(term, language, allowed));
            }
        }
        entries.sort(Comparator.comparing(TermFreezeEntryRow::normalizedTerm)
                .thenComparing(TermFreezeEntryRow::language)
                .thenComparing(TermFreezeEntryRow::allowedTranslation));
        return entries;
    }

    /** 冻结条目按（术语、语言）分组，组内允许译法有序。 */
    private static List<FreezeTermGroup> groupFreezeEntries(List<TermFreezeEntryRow> entries) {
        Map<String, FreezeTermGroup> groups = new LinkedHashMap<>();
        for (TermFreezeEntryRow entry : entries) {
            String groupKey = entry.normalizedTerm() + "" + entry.language();
            groups.computeIfAbsent(groupKey,
                    k -> new FreezeTermGroup(entry.normalizedTerm(), entry.language(), new ArrayList<>()))
                    .allowedTranslations().add(entry.allowedTranslation());
        }
        return List.copyOf(groups.values());
    }

    /**
     * 冻结违规判定：源文与译文均按规范化文本（去空白、转小写）做连续子串匹配；
     * 命中术语且译文不含任一允许译法即为违规。调用方负责排序保证输出稳定。
     */
    private static List<ApiDtos.FreezeViolationView> findFreezeViolations(String segmentId, String language,
                                                                          String sourceText, String content,
                                                                          List<FreezeTermGroup> groups) {
        String normalizedSource = normalizeTermText(sourceText);
        String normalizedContent = normalizeTermText(content);
        List<ApiDtos.FreezeViolationView> violations = new ArrayList<>();
        for (FreezeTermGroup group : groups) {
            if (group.language().equals(language) && normalizedSource.contains(group.term())
                    && group.allowedTranslations().stream().noneMatch(normalizedContent::contains)) {
                violations.add(new ApiDtos.FreezeViolationView(segmentId, language, group.term(),
                        group.allowedTranslations()));
            }
        }
        return violations;
    }

    /** 冻结违规按段落、语言、术语排序，保证 422 响应稳定列出。 */
    private static void sortFreezeViolations(List<ApiDtos.FreezeViolationView> violations) {
        violations.sort(Comparator.comparing(ApiDtos.FreezeViolationView::segmentId)
                .thenComparing(ApiDtos.FreezeViolationView::language)
                .thenComparing(ApiDtos.FreezeViolationView::term));
    }

    /** 规范化术语文本：去首尾空白、压缩连续空白并转小写。 */
    private static String normalizeTermText(String text) {
        return text.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** freezeKey 指纹：文档 ID、文档版本、操作者、状态与规范化术语条目的 SHA-256。 */
    private static String computeFreezeKey(long documentId, int documentVersion, String operator,
                                           List<TermFreezeEntryRow> entries) {
        StringBuilder canonical = new StringBuilder()
                .append(documentId).append('\n')
                .append(documentVersion).append('\n')
                .append(operator).append('\n')
                .append("ACTIVE");
        for (TermFreezeEntryRow entry : entries) {
            canonical.append('\n').append(entry.normalizedTerm()).append(' ')
                    .append(entry.language()).append(' ').append(entry.allowedTranslation());
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 冻结条目按（术语、语言）分组的内部视图。 */
    private record FreezeTermGroup(String term, String language, List<String> allowedTranslations) {
    }
}
