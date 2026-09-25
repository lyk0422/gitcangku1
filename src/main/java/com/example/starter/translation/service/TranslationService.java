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
import org.springframework.http.HttpStatus;
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
        Optional<TermFreezeRow> freeze = repository.findActiveTermFreeze(documentId, document.termVersion());
        if (freeze.isPresent()) {
            List<FreezeEntry> freezeEntries = loadFreezeEntries(documentId, freeze.get().freezeVersion());
            List<ApiDtos.FreezeViolationView> freezeViolations = findFreezeViolations(
                    segment, normalizedLanguage, request.content(), freezeEntries);
            if (!freezeViolations.isEmpty()) {
                throw ApiException.freezeViolation(
                        "译文违反 " + freezeViolations.size() + " 条冻结译法", freezeViolations);
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
     * 译文绑定当前术语版本（过期 422）、满足有效冻结的译法约束（违规 422 并稳定列出段落与术语）
     * 且满足当前术语规则（违规 422 并返回全部违规术语），
     * 全部通过后原子生成完整只读快照（固化术语版本与实际规则集、所用冻结版本与冻结条目）并递增发布版本；
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
        Optional<TermFreezeRow> freeze = repository.findActiveTermFreeze(documentId, document.termVersion());
        List<FreezeEntry> freezeEntries = freeze
                .map(f -> loadFreezeEntries(documentId, f.freezeVersion()))
                .orElse(List.of());
        List<ApiDtos.FreezeViolationView> freezeViolations = new ArrayList<>();
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
                freezeViolations.addAll(findFreezeViolations(
                        segment, language, translation.content(), freezeEntries));
            }
        }
        if (!freezeViolations.isEmpty()) {
            throw ApiException.freezeViolation(
                    "译文违反 " + freezeViolations.size() + " 条冻结译法", freezeViolations);
        }
        if (!termViolations.isEmpty()) {
            throw ApiException.termViolation("译文违反 " + termViolations.size() + " 条术语规则", termViolations);
        }
        int publishedVersion = document.publishedVersion() + 1;
        repository.insertSnapshot(documentId, publishedVersion,
                buildSnapshotJson(document, publishedVersion, segments, translations, approvals, termRules,
                        freeze.orElse(null), freezeEntries));
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
     * 生成完整只读快照 JSON：全部段落源文及各语言译文、作者、审核人、版本号、
     * 固化的术语版本及规则集，以及发布时所用冻结版本与冻结条目（无有效冻结时 freezeVersion 为 null）。
     */
    private String buildSnapshotJson(DocumentRow document, int publishedVersion, List<SegmentRow> segments,
                                     Map<String, TranslationRow> translations,
                                     Map<String, ApprovalRow> approvals, List<TermRuleRow> termRules,
                                     TermFreezeRow freeze, List<FreezeEntry> freezeEntries) {
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
        List<Map<String, Object>> freezeEntryList = new ArrayList<>();
        for (FreezeEntry entry : freezeEntries) {
            Map<String, Object> entryJson = new LinkedHashMap<>();
            entryJson.put("sourceTerm", entry.sourceTerm());
            entryJson.put("language", entry.language());
            entryJson.put("allowedTranslations", entry.allowedTranslations());
            freezeEntryList.add(entryJson);
        }
        snapshot.put("freezeEntries", freezeEntryList);
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
     * 创建术语冻结：冻结绑定当前术语版本（即冻结针对的文档版本），条目规范化后不可原地修改；
     * 同一术语版本只允许一份有效冻结（已存在 409 FREEZE_EXISTS）；
     * freezeKey 全局唯一，指纹含文档 ID、术语版本、规范化条目、操作者与状态，
     * 同键同指纹重放原结果，同键异指纹 409，失败不占键。冻结创建不变更草稿版本。
     */
    @Transactional
    public ApiDtos.FreezeResponse createFreeze(long documentId, String actorId,
                                               ApiDtos.CreateFreezeRequest request) {
        DocumentRow document = lockDocument(documentId);
        List<FreezeEntry> entries = normalizeFreezeEntries(request.entries(), document);
        String fingerprint = freezeFingerprint(document, entries, actorId);
        Optional<TermFreezeRow> existing = repository.findTermFreezeByKey(request.freezeKey());
        if (existing.isPresent()) {
            return replayFreeze(existing.get(), fingerprint, request.freezeKey(), entries.size());
        }
        if (repository.findActiveTermFreeze(documentId, document.termVersion()).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "FREEZE_EXISTS",
                    "当前术语版本 " + document.termVersion() + " 已存在有效冻结: " + documentId);
        }
        int freezeVersion = repository.findMaxFreezeVersion(documentId) + 1;
        try {
            repository.insertTermFreeze(documentId, freezeVersion, document.termVersion(),
                    request.freezeKey(), fingerprint, actorId);
        } catch (DuplicateKeyException e) {
            // 并发唯一约束冲突：同 freezeKey 重读按指纹裁决；同版本有效冻结冲突报 FREEZE_EXISTS
            Optional<TermFreezeRow> byKey = repository.findTermFreezeByKey(request.freezeKey());
            if (byKey.isPresent()) {
                return replayFreeze(byKey.get(), fingerprint, request.freezeKey(), entries.size());
            }
            throw new ApiException(HttpStatus.CONFLICT, "FREEZE_EXISTS",
                    "当前术语版本 " + document.termVersion() + " 已存在有效冻结: " + documentId);
        }
        for (FreezeEntry entry : entries) {
            for (String allowed : entry.allowedTranslations()) {
                repository.insertTermFreezeEntry(documentId, freezeVersion,
                        new TermFreezeEntryRow(entry.sourceTerm(), entry.language(), allowed));
            }
        }
        return new ApiDtos.FreezeResponse(documentId, freezeVersion, document.termVersion(),
                "ACTIVE", fingerprint, entries.size());
    }

    /**
     * 撤销冻结：仅可撤销 ACTIVE 冻结（重复撤销 409 FREEZE_ALREADY_REVOKED）；
     * 撤销只影响后续修订与发布，不重写既有发布快照，冻结条目保持不可变。撤销不变更草稿版本。
     */
    @Transactional
    public ApiDtos.FreezeResponse revokeFreeze(long documentId, int freezeVersion, String actorId,
                                               ApiDtos.RevokeFreezeRequest request) {
        lockDocument(documentId);
        TermFreezeRow freeze = repository.findTermFreeze(documentId, freezeVersion)
                .orElseThrow(() -> ApiException.notFound("冻结不存在: " + documentId + "/" + freezeVersion));
        if (!"ACTIVE".equals(freeze.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "FREEZE_ALREADY_REVOKED",
                    "冻结已撤销: " + documentId + "/" + freezeVersion);
        }
        repository.revokeTermFreeze(documentId, freezeVersion, actorId);
        int entryCount = loadFreezeEntries(documentId, freezeVersion).size();
        return new ApiDtos.FreezeResponse(documentId, freezeVersion, freeze.termVersion(),
                "REVOKED", freeze.fingerprint(), entryCount);
    }

    /** 查询当前术语版本的有效冻结及完整条目；无有效冻结返回 404 NO_ACTIVE_FREEZE。 */
    @Transactional(readOnly = true)
    public ApiDtos.FreezeView getCurrentFreeze(long documentId) {
        DocumentRow document = repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        TermFreezeRow freeze = repository.findActiveTermFreeze(documentId, document.termVersion())
                .orElseThrow(() -> ApiException.noActiveFreeze(
                        "当前术语版本 " + document.termVersion() + " 无有效冻结: " + documentId));
        return toFreezeView(freeze, loadFreezeEntries(documentId, freeze.freezeVersion()));
    }

    /** 查询指定冻结版本的冻结内容（含已撤销与已失效的历史冻结）；不存在返回 404。 */
    @Transactional(readOnly = true)
    public ApiDtos.FreezeView getFreeze(long documentId, int freezeVersion) {
        repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        TermFreezeRow freeze = repository.findTermFreeze(documentId, freezeVersion)
                .orElseThrow(() -> ApiException.notFound("冻结不存在: " + documentId + "/" + freezeVersion));
        return toFreezeView(freeze, loadFreezeEntries(documentId, freezeVersion));
    }

    /**
     * 冻结段落诊断：对当前有效冻结，逐段落逐语言列出命中的冻结术语及合规情况（含缺译文段落）；
     * 无有效冻结返回 404。仅输出命中术语的段落与语言，按段落、语言、术语稳定排序。
     */
    @Transactional(readOnly = true)
    public ApiDtos.FreezeDiagnosticsResponse getFreezeDiagnostics(long documentId) {
        DocumentRow document = repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        TermFreezeRow freeze = repository.findActiveTermFreeze(documentId, document.termVersion())
                .orElseThrow(() -> ApiException.noActiveFreeze(
                        "当前术语版本 " + document.termVersion() + " 无有效冻结: " + documentId));
        List<FreezeEntry> entries = loadFreezeEntries(documentId, freeze.freezeVersion());
        Map<String, TranslationRow> translations = repository.listTranslations(documentId).stream()
                .collect(Collectors.toMap(t -> key(t.segmentId(), t.language()), Function.identity()));
        List<ApiDtos.SegmentFreezeDiagnostics> diagnostics = new ArrayList<>();
        for (SegmentRow segment : repository.listSegments(documentId)) {
            for (String language : document.targetLanguages()) {
                TranslationRow translation = translations.get(key(segment.segmentId(), language));
                List<ApiDtos.FreezeHitView> hits = new ArrayList<>();
                for (FreezeEntry entry : entries) {
                    if (entry.language().equals(language)
                            && segment.sourceText().contains(entry.sourceTerm())) {
                        boolean satisfied = translation != null && entry.allowedTranslations().stream()
                                .anyMatch(translation.content()::contains);
                        hits.add(new ApiDtos.FreezeHitView(entry.sourceTerm(),
                                entry.allowedTranslations(), satisfied));
                    }
                }
                if (!hits.isEmpty()) {
                    diagnostics.add(new ApiDtos.SegmentFreezeDiagnostics(segment.segmentId(), language,
                            translation == null ? null : translation.translationVersion(), hits));
                }
            }
        }
        return new ApiDtos.FreezeDiagnosticsResponse(documentId, freeze.freezeVersion(), diagnostics);
    }

    /**
     * 批量译文修订：整批原子提交或回滚。先校验语言、段落与源文版本，再按最终段落版本
     * 校验每个命中冻结术语均采用冻结译法（任一违反 422 FREEZE_VIOLATION，稳定列出段落与术语，
     * 整批修订回滚、不产生任何发布候选快照），然后复核既有术语规则；
     * 全部通过后统一写入译文并将草稿版本加一。
     */
    @Transactional
    public ApiDtos.BatchRevisionsResponse submitRevisionBatch(long documentId, String actorId,
                                                              ApiDtos.BatchRevisionsRequest request) {
        DocumentRow document = lockDocument(documentId);
        List<ValidatedRevision> revisions = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ApiDtos.RevisionInput input : request.revisions()) {
            String language = normalizeLanguage(input.language());
            if (!document.targetLanguages().contains(language)) {
                throw ApiException.unprocessable("语言不在文档目标语言中: " + language);
            }
            if (!seen.add(input.segmentId() + " " + language)) {
                throw ApiException.unprocessable("批次内重复修订: " + input.segmentId() + "/" + language);
            }
            SegmentRow segment = findSegmentOrThrow(documentId, input.segmentId());
            if (input.sourceVersion() != segment.sourceVersion()) {
                throw ApiException.unprocessable("段落 " + input.segmentId() + " 的源文版本 "
                        + input.sourceVersion() + " 与当前源文版本 " + segment.sourceVersion() + " 不匹配");
            }
            revisions.add(new ValidatedRevision(segment, language, input.content()));
        }
        Optional<TermFreezeRow> freeze = repository.findActiveTermFreeze(documentId, document.termVersion());
        if (freeze.isPresent()) {
            List<FreezeEntry> entries = loadFreezeEntries(documentId, freeze.get().freezeVersion());
            List<ApiDtos.FreezeViolationView> violations = new ArrayList<>();
            for (ValidatedRevision revision : revisions) {
                violations.addAll(findFreezeViolations(
                        revision.segment(), revision.language(), revision.content(), entries));
            }
            if (!violations.isEmpty()) {
                // 按段落、语言、术语稳定排序，保证同一批次重放时违规列表一致
                violations.sort(Comparator.comparing(ApiDtos.FreezeViolationView::segmentId)
                        .thenComparing(ApiDtos.FreezeViolationView::language)
                        .thenComparing(ApiDtos.FreezeViolationView::sourceTerm));
                throw ApiException.freezeViolation(
                        "批量修订违反 " + violations.size() + " 条冻结译法", violations);
            }
        }
        List<TermRuleRow> termRules = repository.listTermRules(documentId, document.termVersion());
        for (ValidatedRevision revision : revisions) {
            List<ApiDtos.TermRuleView> termViolations = findViolations(revision.segment().sourceText(),
                    revision.language(), revision.content(), termRules);
            if (!termViolations.isEmpty()) {
                throw ApiException.termViolation("段落 " + revision.segment().segmentId()
                        + " 的译文违反 " + termViolations.size() + " 条术语规则", termViolations);
            }
        }
        List<ApiDtos.RevisionResult> results = new ArrayList<>();
        for (ValidatedRevision revision : revisions) {
            int translationVersion = repository.findTranslation(documentId,
                    revision.segment().segmentId(), revision.language())
                    .map(TranslationRow::translationVersion).orElse(0) + 1;
            repository.upsertTranslation(documentId, new TranslationRow(revision.segment().segmentId(),
                    revision.language(), revision.content(), actorId, revision.segment().sourceVersion(),
                    translationVersion, document.termVersion()));
            results.add(new ApiDtos.RevisionResult(revision.segment().segmentId(), revision.language(),
                    translationVersion, revision.segment().sourceVersion()));
        }
        int draftVersion = bumpDraftVersion(document);
        return new ApiDtos.BatchRevisionsResponse(documentId, draftVersion, document.termVersion(), results);
    }

    /** freezeKey 重放裁决：同指纹返回原冻结结果，异指纹 409。 */
    private static ApiDtos.FreezeResponse replayFreeze(TermFreezeRow freeze, String fingerprint,
                                                       String freezeKey, int entryCount) {
        if (!freeze.fingerprint().equals(fingerprint)) {
            throw new ApiException(HttpStatus.CONFLICT, "FREEZE_KEY_CONFLICT",
                    "freezeKey 已使用且冻结内容不同: " + freezeKey);
        }
        return new ApiDtos.FreezeResponse(freeze.documentId(), freeze.freezeVersion(),
                freeze.termVersion(), freeze.status(), freeze.fingerprint(), entryCount);
    }

    /**
     * 冻结条目规范化：sourceTerm 去首尾空白、语言小写且须在文档目标语言中、允许译法去空白去重排序；
     * 规范化后按术语与语言去重（重复 422），并按术语、语言排序保证指纹与存储稳定。
     */
    private static List<FreezeEntry> normalizeFreezeEntries(List<ApiDtos.FreezeEntryInput> inputs,
                                                            DocumentRow document) {
        List<FreezeEntry> entries = new ArrayList<>();
        Set<List<String>> seen = new HashSet<>();
        for (ApiDtos.FreezeEntryInput input : inputs) {
            String sourceTerm = input.sourceTerm().trim();
            if (sourceTerm.isEmpty()) {
                throw ApiException.unprocessable("sourceTerm 规范化后为空");
            }
            String language = normalizeLanguage(input.language());
            if (!document.targetLanguages().contains(language)) {
                throw ApiException.unprocessable("冻结条目语言不在文档目标语言中: " + language);
            }
            List<String> allowed = input.allowedTranslations().stream()
                    .map(String::trim)
                    .filter(a -> !a.isEmpty())
                    .distinct()
                    .sorted()
                    .toList();
            if (allowed.isEmpty()) {
                throw ApiException.unprocessable("允许译法规范化后为空: " + sourceTerm + "/" + language);
            }
            if (!seen.add(List.of(sourceTerm, language))) {
                throw ApiException.unprocessable("冻结条目重复: " + sourceTerm + "/" + language);
            }
            entries.add(new FreezeEntry(sourceTerm, language, allowed));
        }
        entries.sort(Comparator.comparing(FreezeEntry::sourceTerm).thenComparing(FreezeEntry::language));
        return entries;
    }

    /** freezeKey 指纹：文档 ID、术语版本、规范化术语条目、操作者与状态（ACTIVE）的 SHA-256。 */
    private static String freezeFingerprint(DocumentRow document, List<FreezeEntry> entries, String actorId) {
        StringBuilder canonical = new StringBuilder()
                .append("documentId=").append(document.documentId())
                .append("\ntermVersion=").append(document.termVersion())
                .append("\nactor=").append(actorId)
                .append("\nstatus=ACTIVE");
        for (FreezeEntry entry : entries) {
            canonical.append('\n').append(entry.sourceTerm()).append('|').append(entry.language())
                    .append('|').append(String.join(",", entry.allowedTranslations()));
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 加载冻结条目并按术语与语言分组；行已按术语、语言、允许译法排序，分组后保持稳定顺序。 */
    private List<FreezeEntry> loadFreezeEntries(long documentId, int freezeVersion) {
        Map<List<String>, List<String>> grouped = new LinkedHashMap<>();
        for (TermFreezeEntryRow row : repository.listTermFreezeEntries(documentId, freezeVersion)) {
            grouped.computeIfAbsent(List.of(row.sourceTerm(), row.language()), k -> new ArrayList<>())
                    .add(row.allowedTranslation());
        }
        List<FreezeEntry> entries = new ArrayList<>();
        grouped.forEach((groupKey, allowed) ->
                entries.add(new FreezeEntry(groupKey.get(0), groupKey.get(1), allowed)));
        return entries;
    }

    /**
     * 冻结违规判定：段落最终源文按 Unicode 原文、区分大小写做连续子串匹配命中冻结术语；
     * 译文不含该术语任一允许译法即违规。条目有序，返回该段落该语言下的全部违规。
     */
    private static List<ApiDtos.FreezeViolationView> findFreezeViolations(SegmentRow segment, String language,
                                                                          String content,
                                                                          List<FreezeEntry> entries) {
        List<ApiDtos.FreezeViolationView> violations = new ArrayList<>();
        for (FreezeEntry entry : entries) {
            if (entry.language().equals(language) && segment.sourceText().contains(entry.sourceTerm())
                    && entry.allowedTranslations().stream().noneMatch(content::contains)) {
                violations.add(new ApiDtos.FreezeViolationView(segment.segmentId(), language,
                        entry.sourceTerm(), entry.allowedTranslations()));
            }
        }
        return violations;
    }

    private static ApiDtos.FreezeView toFreezeView(TermFreezeRow freeze, List<FreezeEntry> entries) {
        return new ApiDtos.FreezeView(freeze.documentId(), freeze.freezeVersion(), freeze.termVersion(),
                freeze.status(), freeze.fingerprint(), freeze.createdBy(),
                entries.stream()
                        .map(e -> new ApiDtos.FreezeEntryView(e.sourceTerm(), e.language(),
                                e.allowedTranslations()))
                        .toList());
    }

    /** 冻结条目分组：一个规范化术语在一种语言下的全部允许译法（去重排序）。 */
    private record FreezeEntry(String sourceTerm, String language, List<String> allowedTranslations) {
    }

    /** 批量修订中通过基础校验的单条修订。 */
    private record ValidatedRevision(SegmentRow segment, String language, String content) {
    }
}
