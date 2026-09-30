package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiDtos;
import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.domain.Rows.ApprovalRow;
import com.example.starter.translation.domain.Rows.DocumentRow;
import com.example.starter.translation.domain.Rows.LanguageLegalStatusRow;
import com.example.starter.translation.domain.Rows.SegmentRow;
import com.example.starter.translation.domain.Rows.SignatureRow;
import com.example.starter.translation.domain.Rows.TermRuleRow;
import com.example.starter.translation.domain.Rows.TranslationRow;
import com.example.starter.translation.repo.TranslationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
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
        repository.insertDefaultLegalStatuses(documentId, languages);
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
     * 切换目标语种法律效力状态（INFORMATIONAL/BINDING）。
     * 语言不在文档目标语言中返回 422；与当前状态相同则不改变草稿版本，不同则草稿版本加一。
     */
    @Transactional
    public ApiDtos.LegalStatusUpdateResponse updateLegalStatus(long documentId, String language,
                                                               ApiDtos.UpdateLegalStatusRequest request) {
        DocumentRow document = lockDocument(documentId);
        String normalizedLanguage = normalizeLanguage(language);
        if (!document.targetLanguages().contains(normalizedLanguage)) {
            throw ApiException.unprocessable("语言不在文档目标语言中: " + normalizedLanguage);
        }
        LanguageLegalStatusRow current = repository.listLegalStatuses(documentId).stream()
                .filter(s -> s.language().equals(normalizedLanguage))
                .findFirst()
                .orElseThrow(() -> ApiException.unprocessable("语种法律效力状态缺失: " + normalizedLanguage));
        int draftVersion = document.draftVersion();
        if (!current.legalStatus().equals(request.legalStatus())) {
            int updated = repository.updateLegalStatus(documentId, normalizedLanguage, request.legalStatus());
            if (updated == 0) {
                throw ApiException.unprocessable("语种法律效力状态缺失: " + normalizedLanguage);
            }
            draftVersion = bumpDraftVersion(document);
        }
        return new ApiDtos.LegalStatusUpdateResponse(documentId, normalizedLanguage,
                request.legalStatus(), draftVersion);
    }

    /**
     * BINDING 译文签署：签署人取 X-Actor-Id，固化语种、译文版本与签署时间。
     * 译文不存在 404；签署版本不是当前译文版本（已被修订）422；同一签署人重复签同一版本 422（幂等重放除外）。
     * 签署不要求语种当前为 BINDING，也不校验源文是否最新；旧版本译文保留时仍可对其签署留痕，
     * 但发布仅认当前译文版本上的签署。成功签署不改变草稿版本（仅追加签署事实）。
     */
    @Transactional
    public ApiDtos.SignResponse signTranslation(long documentId, String segmentId, String language,
                                                String actorId, ApiDtos.SignTranslationRequest request) {
        lockDocument(documentId);
        String normalizedLanguage = normalizeLanguage(language);
        findSegmentOrThrow(documentId, segmentId);
        TranslationRow translation = repository.findTranslation(documentId, segmentId, normalizedLanguage)
                .orElseThrow(() -> ApiException.notFound(
                        "译文不存在: " + segmentId + "/" + normalizedLanguage));
        if (translation.translationVersion() != request.translationVersion()) {
            throw ApiException.unprocessable("签署所针对的译文版本 " + request.translationVersion()
                    + " 已被修订，当前译文版本 " + translation.translationVersion());
        }
        OffsetDateTime signedAt = OffsetDateTime.now(clock);
        try {
            repository.insertSignature(new SignatureRow(documentId, segmentId, normalizedLanguage,
                    actorId, request.translationVersion(), signedAt));
        } catch (DuplicateKeyException e) {
            throw ApiException.unprocessable("签署人 " + actorId + " 已签署译文版本 "
                    + request.translationVersion() + "，不能重复签署");
        }
        return new ApiDtos.SignResponse(documentId, segmentId, normalizedLanguage, actorId,
                request.translationVersion(), DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(signedAt));
    }

    /**
     * 查询法律效力状态：清楚区分草稿签署、当前法律效力与历史发布。
     * languages 中 draftLegalStatus 为当前草稿状态，publishedLegalStatus 为当前已发布版本快照中
     * 固化的状态（尚未发布为 null）；draftSignatures 包含全部历史签署并标记是否仍对应当前译文版本。
     */
    @Transactional(readOnly = true)
    public ApiDtos.LegalStatusResponse getLegalStatus(long documentId) {
        DocumentRow document = repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        Map<String, Integer> currentTranslationVersions = repository.listTranslations(documentId).stream()
                .collect(Collectors.toMap(t -> key(t.segmentId(), t.language()),
                        TranslationRow::translationVersion));
        Map<String, String> publishedStatuses = loadPublishedLegalStatuses(document, document.publishedVersion());
        List<ApiDtos.LanguageLegalStatusView> languageViews = repository.listLegalStatuses(documentId).stream()
                .map(row -> new ApiDtos.LanguageLegalStatusView(row.language(), row.legalStatus(),
                        publishedStatuses.get(row.language())))
                .toList();
        Map<String, List<SignatureRow>> grouped = new LinkedHashMap<>();
        for (SignatureRow signature : repository.listSignatures(documentId)) {
            grouped.computeIfAbsent(key(signature.segmentId(), signature.language()),
                    k -> new ArrayList<>()).add(signature);
        }
        List<ApiDtos.DraftSignatureView> draftViews = new ArrayList<>();
        for (List<SignatureRow> signatures : grouped.values()) {
            SignatureRow first = signatures.get(0);
            String groupKey = key(first.segmentId(), first.language());
            int currentVersion = currentTranslationVersions.getOrDefault(groupKey, 0);
            List<ApiDtos.SignatureView> views = signatures.stream()
                    .map(signature -> new ApiDtos.SignatureView(signature.signer(),
                            signature.translationVersion(),
                            DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(signature.signedAt()),
                            signature.translationVersion() == currentVersion))
                    .toList();
            draftViews.add(new ApiDtos.DraftSignatureView(first.segmentId(), first.language(),
                    currentVersion, views));
        }
        return new ApiDtos.LegalStatusResponse(documentId, document.draftVersion(),
                document.publishedVersion(), languageViews, draftViews);
    }

    /** 读取指定已发布版本快照中固化的语种法律效力状态；无该版本时返回空映射。 */
    private Map<String, String> loadPublishedLegalStatuses(DocumentRow document, int publishedVersion) {
        if (publishedVersion <= 0) {
            return Map.of();
        }
        return repository.findSnapshot(document.documentId(), publishedVersion).map(json -> {
            try {
                Map<String, String> result = new LinkedHashMap<>();
                var languagesNode = objectMapper.readTree(json).get("languageLegalStatuses");
                if (languagesNode != null && languagesNode.isArray()) {
                    for (var node : languagesNode) {
                        result.put(node.get("language").asText(), node.get("legalStatus").asText());
                    }
                }
                return result;
            } catch (Exception e) {
                throw new IllegalStateException("已发布快照解析失败", e);
            }
        }).orElseGet(Map::of);
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
     * 发布：校验期望版本（不符 409），再校验全部段落在全部目标语言均满足对应法律效力状态的发布条件：
     * INFORMATIONAL 沿用原条件——当前译文版本有有效单审核人批准；
     * BINDING 要求当前译文版本有两名不同签署人（缺签、同人重复签或签署版本已被修订均 422）。
     * 两种状态均要求译文存在、源文版本最新、绑定当前术语版本且满足当前术语规则（违规 422 并返回全部违规术语）。
     * 全部通过后原子生成完整只读快照（固化语种法律效力、签署人、签署时间、术语版本与实际规则集）并递增发布版本；
     * 任何失败回滚，不产生部分快照，也不改变当前已发布版本。
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
        Map<String, String> legalStatuses = repository.listLegalStatuses(documentId).stream()
                .collect(Collectors.toMap(LanguageLegalStatusRow::language,
                        LanguageLegalStatusRow::legalStatus));
        Map<String, List<SignatureRow>> signatures = repository.listSignatures(documentId).stream()
                .collect(Collectors.groupingBy(s -> key(s.segmentId(), s.language())));
        List<TermRuleRow> termRules = repository.listTermRules(documentId, document.termVersion());
        List<ApiDtos.TermRuleView> termViolations = new ArrayList<>();
        for (SegmentRow segment : segments) {
            for (String language : document.targetLanguages()) {
                String itemKey = key(segment.segmentId(), language);
                TranslationRow translation = translations.get(itemKey);
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
                if ("BINDING".equals(legalStatuses.get(language))) {
                    LegalStatusPolicy.verifyBindingSignatures(segment, language, translation,
                            signatures.getOrDefault(itemKey, List.of()));
                } else {
                    LegalStatusPolicy.verifyInformationalApproval(segment, language, translation,
                            approvals.get(itemKey));
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
                buildSnapshotJson(document, publishedVersion, segments, translations, approvals,
                        legalStatuses, signatures, termRules));
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
     * 生成完整只读快照 JSON：全部段落源文及各语言译文、作者、版本号、按法律效力固化的
     * 审核人/签署人与签署时间、固化的术语版本及规则集；后续修订不回写已发布快照。
     */
    private String buildSnapshotJson(DocumentRow document, int publishedVersion, List<SegmentRow> segments,
                                     Map<String, TranslationRow> translations,
                                     Map<String, ApprovalRow> approvals,
                                     Map<String, String> legalStatuses,
                                     Map<String, List<SignatureRow>> signatures,
                                     List<TermRuleRow> termRules) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("documentId", document.documentId());
        snapshot.put("publishedVersion", publishedVersion);
        snapshot.put("draftVersion", document.draftVersion());
        snapshot.put("termVersion", document.termVersion());
        snapshot.put("targetLanguages", document.targetLanguages());
        List<Map<String, Object>> languageStatusList = new ArrayList<>();
        for (String language : document.targetLanguages()) {
            Map<String, Object> statusJson = new LinkedHashMap<>();
            statusJson.put("language", language);
            statusJson.put("legalStatus", legalStatuses.getOrDefault(language, "INFORMATIONAL"));
            languageStatusList.add(statusJson);
        }
        snapshot.put("languageLegalStatuses", languageStatusList);
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
                String itemKey = key(segment.segmentId(), language);
                TranslationRow translation = translations.get(itemKey);
                String legalStatus = legalStatuses.getOrDefault(language, "INFORMATIONAL");
                Map<String, Object> translationJson = new LinkedHashMap<>();
                translationJson.put("language", language);
                translationJson.put("legalStatus", legalStatus);
                translationJson.put("content", translation.content());
                translationJson.put("author", translation.author());
                translationJson.put("translationVersion", translation.translationVersion());
                translationJson.put("sourceVersion", translation.sourceVersion());
                translationJson.put("termVersion", translation.termVersion());
                if ("BINDING".equals(legalStatus)) {
                    // 仅固化发布时满足条件的当前版本签署，旧版本失效签署不进入快照
                    List<Map<String, Object>> signerList = new ArrayList<>();
                    for (SignatureRow signature : signatures.getOrDefault(itemKey, List.of())) {
                        if (signature.translationVersion() == translation.translationVersion()) {
                            Map<String, Object> signerJson = new LinkedHashMap<>();
                            signerJson.put("signer", signature.signer());
                            signerJson.put("signedAt",
                                    DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(signature.signedAt()));
                            signerList.add(signerJson);
                        }
                    }
                    signerList.sort(Comparator
                            .comparing((Map<String, Object> s) -> String.valueOf(s.get("signedAt")))
                            .thenComparing(s -> String.valueOf(s.get("signer"))));
                    translationJson.put("signers", signerList);
                } else {
                    ApprovalRow approval = approvals.get(itemKey);
                    translationJson.put("reviewer", approval.reviewer());
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
}
