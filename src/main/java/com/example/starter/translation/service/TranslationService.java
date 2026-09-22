package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiDtos;
import com.example.starter.translation.api.ApiException;
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
 * 所有写操作先对文档行加 FOR UPDATE 行锁，保证同一文档的修改、审核、术语更新与发布串行，
 * 并发下对应一个一致的文档状态；方法均加入调用方事务，与幂等记录原子提交。
 * 术语匹配按 Unicode 原文、区分大小写做连续子串匹配（Java String.contains），不做分词或词形推断。
 */
@Service
public class TranslationService {

    private final TranslationRepository repository;
    private final ObjectMapper objectMapper;

    public TranslationService(TranslationRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /** 建文档：1~5 种目标语言，可携带初始段落，初始草稿版本 1、发布版本 0、术语版本 0。 */
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
     * 译文提交：所依据源文版本必须等于当前源文版本；译文绑定文档当前术语版本，
     * 当前源文命中的该语言规则要求译文包含 requiredTranslation，否则 422 返回全部违规术语且不写译文。
     * 校验通过后译文版本递增、绑定术语版本，草稿版本加一。
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
        List<ApiDtos.TermViolation> violations = findViolations(
                repository.listTermRules(documentId, document.termVersion()),
                normalizedLanguage, segmentId, segment.sourceText(), request.content());
        if (!violations.isEmpty()) {
            throw ApiException.termViolations("译文违反当前术语版本 " + document.termVersion() + " 的术语规则",
                    violations);
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
     * 新增术语版本：expectedTermVersion 必须等于当前术语版本（不符 409，已有版本不可覆盖），
     * rules 为 0~100 条完整规则集，按目标语言与 sourceTerm 唯一且语言须属于文档目标语言。
     * 成功后术语版本与草稿版本各加一；版本行、规则快照、文档版本与幂等记录原子提交。
     */
    @Transactional
    public ApiDtos.TermVersionResponse updateTerms(long documentId, ApiDtos.UpdateTermsRequest request) {
        DocumentRow document = lockDocument(documentId);
        if (document.termVersion() != request.expectedTermVersion()) {
            throw ApiException.conflict("术语版本冲突：当前术语版本 " + document.termVersion()
                    + "，期望 " + request.expectedTermVersion() + "，已有术语版本不可覆盖");
        }
        List<TermRuleRow> rules = normalizeRules(document, request.rules());
        int newTermVersion = document.termVersion() + 1;
        repository.insertTermVersion(documentId, newTermVersion);
        for (TermRuleRow rule : rules) {
            repository.insertTermRule(documentId, newTermVersion, rule);
        }
        repository.updateTermVersion(documentId, newTermVersion);
        int draftVersion = bumpDraftVersion(document);
        return new ApiDtos.TermVersionResponse(documentId, newTermVersion, draftVersion, toRuleResponses(rules));
    }

    /**
     * 查询术语版本：version 为 null 时返回当前版本；版本 0 返回空规则集；
     * 指定的正数版本不存在返回 404。历史版本为不可变快照，不受后续术语更新影响。
     */
    @Transactional(readOnly = true)
    public ApiDtos.TermVersionResponse getTerms(long documentId, Integer version) {
        DocumentRow document = repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        int termVersion = version == null ? document.termVersion() : version;
        if (termVersion < 0) {
            throw ApiException.unprocessable("术语版本不能为负数");
        }
        if (termVersion > 0 && !repository.termVersionExists(documentId, termVersion)) {
            throw ApiException.notFound("术语版本不存在: " + documentId + "/" + termVersion);
        }
        List<TermRuleRow> rules = termVersion == 0 ? List.of() : repository.listTermRules(documentId, termVersion);
        return new ApiDtos.TermVersionResponse(documentId, termVersion, document.draftVersion(),
                toRuleResponses(rules));
    }

    /**
     * 查询译文术语状态：译文绑定术语版本是否为当前版本，以及当前源文命中规则下的全部违规。
     * 无译文时 hasTranslation=false、translationVersion=0、boundTermVersion=0。
     */
    @Transactional(readOnly = true)
    public ApiDtos.TranslationTermStatus getTranslationTermStatus(long documentId, String segmentId,
                                                                  String language) {
        DocumentRow document = repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        String normalizedLanguage = normalizeLanguage(language);
        if (!document.targetLanguages().contains(normalizedLanguage)) {
            throw ApiException.unprocessable("语言不在文档目标语言中: " + normalizedLanguage);
        }
        SegmentRow segment = repository.findSegment(documentId, segmentId)
                .orElseThrow(() -> ApiException.notFound("段落不存在: " + segmentId));
        TranslationRow translation = repository.findTranslation(documentId, segmentId, normalizedLanguage)
                .orElse(null);
        String content = translation == null ? "" : translation.content();
        List<ApiDtos.TermViolation> violations = findViolations(
                repository.listTermRules(documentId, document.termVersion()),
                normalizedLanguage, segmentId, segment.sourceText(), content);
        int boundTermVersion = translation == null ? 0 : translation.termVersion();
        return new ApiDtos.TranslationTermStatus(documentId, segmentId, normalizedLanguage,
                translation != null, translation == null ? 0 : translation.translationVersion(),
                boundTermVersion, document.termVersion(),
                boundTermVersion == document.termVersion(), violations);
    }

    /**
     * 发布：校验期望版本（不符 409），再在同一锁定一致视图中校验全部段落在全部目标语言
     * 均有绑定当前术语版本、满足当前源文术语规则且批准有效的译文（缺译/审核失效/术语过期 422），
     * 全部通过后原子生成完整只读快照（固化术语版本与实际规则集）并递增发布版本；任何失败回滚。
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
        List<TermRuleRow> currentRules = repository.listTermRules(documentId, document.termVersion());
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
                    throw ApiException.unprocessable("译文术语过期: " + segment.segmentId() + "/" + language
                            + " 绑定术语版本 " + translation.termVersion()
                            + "，当前术语版本 " + document.termVersion() + "，须基于当前源文与术语版本重新提交");
                }
                List<ApiDtos.TermViolation> violations = findViolations(currentRules, language,
                        segment.segmentId(), segment.sourceText(), translation.content());
                if (!violations.isEmpty()) {
                    throw ApiException.unprocessable("译文不满足当前术语规则: " + segment.segmentId() + "/"
                            + language + "，违规术语 " + violations.size() + " 条");
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
            }
        }
        int publishedVersion = document.publishedVersion() + 1;
        repository.insertSnapshot(documentId, publishedVersion,
                buildSnapshotJson(document, publishedVersion, segments, translations, approvals, currentRules));
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

    /**
     * 校验并归一化术语规则：语言须属于文档目标语言，同一语言内 sourceTerm 区分大小写唯一。
     * 保留提交顺序（rule_order 从 0 开始）。
     */
    private List<TermRuleRow> normalizeRules(DocumentRow document, List<ApiDtos.TermRuleInput> inputs) {
        List<TermRuleRow> rules = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int order = 0;
        for (ApiDtos.TermRuleInput input : inputs) {
            String language = normalizeLanguage(input.language());
            if (!document.targetLanguages().contains(language)) {
                throw ApiException.unprocessable("术语规则语言不在文档目标语言中: " + language);
            }
            String uniquenessKey = language + " " + input.sourceTerm();
            if (!seen.add(uniquenessKey)) {
                throw ApiException.unprocessable(
                        "术语规则重复: language=" + language + ", sourceTerm=" + input.sourceTerm());
            }
            rules.add(new TermRuleRow(language, input.sourceTerm(), input.requiredTranslation(), order++));
        }
        return rules;
    }

    private static List<ApiDtos.TermRuleResponse> toRuleResponses(List<TermRuleRow> rules) {
        return rules.stream()
                .map(rule -> new ApiDtos.TermRuleResponse(rule.language(), rule.sourceTerm(),
                        rule.requiredTranslation()))
                .toList();
    }

    /**
     * 计算指定语言译文中的全部术语违规：源文包含规则 sourceTerm（区分大小写连续子串）
     * 而译文正文不包含 requiredTranslation。源文未命中的规则不参与校验。
     * 结果按规则提交顺序（rule_order）排列。
     */
    private static List<ApiDtos.TermViolation> findViolations(List<TermRuleRow> rules, String language,
                                                              String segmentId, String sourceText,
                                                              String content) {
        List<ApiDtos.TermViolation> violations = new ArrayList<>();
        for (TermRuleRow rule : rules) {
            if (!rule.language().equals(language)) {
                continue;
            }
            if (sourceText.contains(rule.sourceTerm()) && !content.contains(rule.requiredTranslation())) {
                violations.add(new ApiDtos.TermViolation(segmentId, language,
                        rule.sourceTerm(), rule.requiredTranslation()));
            }
        }
        return violations;
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
     * 生成完整只读快照 JSON：全部段落源文及各语言译文、作者、审核人、版本号，
     * 并固化发布时的术语版本与实际规则集；后续术语更新不影响历史快照查询。
     */
    private String buildSnapshotJson(DocumentRow document, int publishedVersion, List<SegmentRow> segments,
                                     Map<String, TranslationRow> translations,
                                     Map<String, ApprovalRow> approvals, List<TermRuleRow> termRules) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("documentId", document.documentId());
        snapshot.put("publishedVersion", publishedVersion);
        snapshot.put("draftVersion", document.draftVersion());
        snapshot.put("targetLanguages", document.targetLanguages());
        snapshot.put("termVersion", document.termVersion());
        List<Map<String, Object>> termRuleList = new ArrayList<>();
        for (TermRuleRow rule : termRules) {
            Map<String, Object> ruleJson = new LinkedHashMap<>();
            ruleJson.put("language", rule.language());
            ruleJson.put("sourceTerm", rule.sourceTerm());
            ruleJson.put("requiredTranslation", rule.requiredTranslation());
            termRuleList.add(ruleJson);
        }
        snapshot.put("termRules", termRuleList);
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
}
