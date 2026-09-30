package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiDtos;
import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.domain.Rows.ApprovalRow;
import com.example.starter.translation.domain.Rows.DocumentRow;
import com.example.starter.translation.domain.Rows.FreezeRow;
import com.example.starter.translation.domain.Rows.SegmentRow;
import com.example.starter.translation.domain.Rows.TermRuleRow;
import com.example.starter.translation.domain.Rows.TranslationRow;
import com.example.starter.translation.domain.FreezeStatus;
import com.example.starter.translation.repo.TranslationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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
    private final BusinessClock clock;

    public TranslationService(TranslationRepository repository, ObjectMapper objectMapper, BusinessClock clock) {
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
            repository.insertSegment(documentId, segment.segmentId(), segment.sourceText());
        }
        return new ApiDtos.DocumentResponse(documentId, 1, 0, languages);
    }

    /** 增加段落：segmentId 文档内唯一，草稿版本加一；冻结窗口进行中返回 409 且原草稿不变。 */
    @Transactional
    public ApiDtos.SegmentResponse addSegment(long documentId, ApiDtos.AddSegmentRequest request) {
        DocumentRow document = lockDocument(documentId);
        rejectIfFrozen(document);
        if (repository.findSegment(documentId, request.segmentId()).isPresent()) {
            throw ApiException.conflict("段落已存在: " + request.segmentId());
        }
        repository.insertSegment(documentId, request.segmentId(), request.sourceText());
        int draftVersion = bumpDraftVersion(document);
        return new ApiDtos.SegmentResponse(documentId, request.segmentId(), 1, draftVersion);
    }

    /** 源文修订：源文版本加一、草稿版本加一；相关译文因源文版本落后而待更新；冻结窗口中 409。 */
    @Transactional
    public ApiDtos.SegmentResponse reviseSource(long documentId, String segmentId,
                                              ApiDtos.ReviseSourceRequest request) {
        DocumentRow document = lockDocument(documentId);
        rejectIfFrozen(document);
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
        rejectIfFrozen(document);
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
        DocumentRow document = lockDocument(documentId);
        rejectIfFrozen(document);
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
     * 冻结窗口进行中不得换术语（409）；规则 0~100 条、按 sourceTerm 与目标语言唯一、
     * 语言须在文档目标语言中（不符 422）。
     * 若草稿当前术语版本由一个已结束的冻结窗口绑定（解冻后首次更新），须先按新规则重新校验全部受影响段落：
     * 缺译或任一语言译文违规即整单 422 回滚，不产生术语版本、不留下部分术语替换；
     * 成功后术语版本加一（已有版本不可覆盖），草稿版本加一，响应返回受影响段落。
     */
    @Transactional
    public ApiDtos.TermVersionResponse updateTerms(long documentId, ApiDtos.UpdateTermsRequest request) {
        DocumentRow document = lockDocument(documentId);
        rejectIfFrozen(document);
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
        List<String> affectedSegments = List.of();
        boolean unfreezeUpdate = isUnfreezeUpdate(document);
        if (unfreezeUpdate) {
            affectedSegments = revalidateAffectedSegments(document, rules);
        }
        int termVersion = document.termVersion() + 1;
        repository.insertTermVersion(documentId, termVersion);
        for (TermRuleRow rule : rules) {
            repository.insertTermRule(documentId, termVersion, rule);
        }
        if (unfreezeUpdate) {
            repository.rebaseTranslationsTermVersion(documentId, termVersion);
        }
        repository.updateTermVersion(documentId, termVersion);
        int draftVersion = bumpDraftVersion(document);
        return new ApiDtos.TermVersionResponse(documentId, termVersion, rules.size(), draftVersion, affectedSegments);
    }

    /**
     * 创建术语冻结窗口：绑定术语版本须等于草稿当前术语版本且已存在（不符 409/422），
     * 区间为 UTC 左闭右开 [freezeStart, freezeEnd)（无效区间 422），进行中的窗口内不得再建（409）。
     * 创建窗口本身不改变草稿与发布版本；响应返回源文命中绑定版本规则的受影响段落。
     */
    @Transactional
    public ApiDtos.FreezeResponse createFreeze(long documentId, ApiDtos.CreateFreezeRequest request) {
        DocumentRow document = lockDocument(documentId);
        rejectIfFrozen(document);
        if (!request.freezeEnd().isAfter(request.freezeStart())) {
            throw ApiException.unprocessable("冻结区间无效：freezeEnd 必须晚于 freezeStart（UTC 左闭右开）");
        }
        if (request.termVersion() != document.termVersion()) {
            throw ApiException.conflict("冻结绑定术语版本 V" + request.termVersion()
                    + " 与草稿当前术语版本 V" + document.termVersion() + " 不一致");
        }
        if (!repository.termVersionExists(documentId, request.termVersion())) {
            throw ApiException.unprocessable("术语版本不存在: V" + request.termVersion());
        }
        int freezeVersion = document.freezeVersion() + 1;
        FreezeRow freeze = new FreezeRow(freezeVersion, request.termVersion(),
                request.freezeStart(), request.freezeEnd());
        repository.insertFreeze(documentId, freeze);
        repository.updateFreezeVersion(documentId, freezeVersion);
        List<String> affectedSegments = affectedSegments(documentId, request.termVersion());
        return new ApiDtos.FreezeResponse(documentId, toFreezeView(freeze, clock.now()),
                document.draftVersion(), document.publishedVersion(), affectedSegments);
    }

    /** 查询指定冻结窗口；文档或窗口不存在返回 404。 */
    @Transactional(readOnly = true)
    public ApiDtos.FreezeWindowView getFreeze(long documentId, int freezeVersion) {
        repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        if (freezeVersion < 1) {
            throw ApiException.notFound("冻结窗口不存在: " + documentId + "/" + freezeVersion);
        }
        FreezeRow freeze = repository.findFreeze(documentId, freezeVersion)
                .orElseThrow(() -> ApiException.notFound("冻结窗口不存在: " + documentId + "/" + freezeVersion));
        return toFreezeView(freeze, clock.now());
    }

    /**
     * 查询冻结状态：冻结起止、当前是否在窗口内、草稿术语版本与受影响段落（源文命中当前术语版本规则），
     * 含全部历史窗口；从未冻结时 activeWindow 为 null、windows 为空。
     */
    @Transactional(readOnly = true)
    public ApiDtos.FreezeStatusResponse getFreezeStatus(long documentId) {
        DocumentRow document = repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        Instant now = clock.now();
        List<FreezeRow> freezes = repository.listFreezes(documentId);
        List<ApiDtos.FreezeWindowView> views = freezes.stream()
                .map(freeze -> toFreezeView(freeze, now)).toList();
        ApiDtos.FreezeWindowView activeWindow = views.stream()
                .filter(ApiDtos.FreezeWindowView::active).reduce((first, second) -> second).orElse(null);
        List<String> affectedSegments = affectedSegments(documentId, document.termVersion());
        return new ApiDtos.FreezeStatusResponse(documentId, document.termVersion(), document.draftVersion(),
                document.publishedVersion(), activeWindow, views, affectedSegments);
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
        // 解冻与发布竞争时，发布只能引用已完整校验的草稿版本：窗口进行中一律拒绝。
        rejectIfFrozen(document);
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

    /**
     * 写操作前置守卫（加入调用方事务）：锁定文档行，冻结窗口进行中抛 409。
     * 由幂等执行器在查重前调用，保证冻结期间连同键重放也被拦截；业务方法内部会再次加锁确认。
     */
    @Transactional
    public void assertWritable(long documentId) {
        rejectIfFrozen(lockDocument(documentId));
    }

    private DocumentRow lockDocument(long documentId) {
        return repository.findDocumentForUpdate(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
    }

    /**
     * 冻结守卫：最近窗口处于 UTC [start, end) 内时拒绝一切草稿修改与发布，返回 409 TERM_FROZEN。
     * 调用方须已持有文档行锁，使解冻更新与发布在同一文档上串行，发布只能引用完整校验后的草稿。
     */
    private void rejectIfFrozen(DocumentRow document) {
        if (document.freezeVersion() <= 0) {
            return;
        }
        FreezeRow freeze = repository.findFreeze(document.documentId(), document.freezeVersion())
                .orElseThrow(() -> new IllegalStateException(
                        "冻结窗口记录缺失: " + document.documentId() + "/" + document.freezeVersion()));
        if (FreezeStatus.isActive(freeze.freezeStart(), freeze.freezeEnd(), clock.now())) {
            throw ApiException.frozen("术语冻结窗口进行中：窗口 V" + freeze.freezeVersion()
                    + " 绑定术语 V" + freeze.termVersion() + "，UTC " + freeze.freezeStart() + " 至 "
                    + freeze.freezeEnd() + "（左闭右开）期间不得修改草稿或发布");
        }
    }

    /**
     * 是否为解冻后的首次术语更新：存在冻结窗口、窗口已结束且草稿术语版本仍是窗口绑定版本。
     * 一旦解冻更新成功，草稿术语版本加一，不再重复触发全量重校验。
     */
    private boolean isUnfreezeUpdate(DocumentRow document) {
        if (document.freezeVersion() <= 0) {
            return false;
        }
        FreezeRow freeze = repository.findFreeze(document.documentId(), document.freezeVersion())
                .orElseThrow(() -> new IllegalStateException(
                        "冻结窗口记录缺失: " + document.documentId() + "/" + document.freezeVersion()));
        return freeze.termVersion() == document.termVersion()
                && !clock.now().isBefore(freeze.freezeEnd());
    }

    /**
     * 解冻后更新的全量重校验：受影响段落为源文命中旧绑定版本或新版本任一源术语的段落（并集）。
     * 对每个受影响段落的全部目标语言，要求译文存在且满足新版本规则；汇总全部问题：
     * 缺译抛 422，术语违规抛 422 并返回全部违规规则。任何问题都由调用方事务回滚，不留部分替换。
     * 校验通过后由调用方把全部现有译文原子重绑到新术语版本：无术语命中的段落无适用规则，
     * 重绑不改变其合规性，保证草稿各译文与当前术语版本一致、可被发布引用。
     */
    private List<String> revalidateAffectedSegments(DocumentRow document, List<TermRuleRow> newRules) {
        long documentId = document.documentId();
        Set<String> oldTerms = repository.listTermRules(documentId, document.termVersion()).stream()
                .map(TermRuleRow::sourceTerm).collect(Collectors.toSet());
        Set<String> newTerms = newRules.stream().map(TermRuleRow::sourceTerm).collect(Collectors.toSet());
        List<SegmentRow> affected = repository.listSegments(documentId).stream()
                .filter(segment -> containsAny(segment.sourceText(), oldTerms)
                        || containsAny(segment.sourceText(), newTerms))
                .toList();
        Map<String, TranslationRow> translations = repository.listTranslations(documentId).stream()
                .collect(Collectors.toMap(t -> key(t.segmentId(), t.language()), Function.identity()));
        List<ApiDtos.TermRuleView> violations = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (SegmentRow segment : affected) {
            for (String language : document.targetLanguages()) {
                TranslationRow translation = translations.get(key(segment.segmentId(), language));
                if (translation == null) {
                    missing.add(segment.segmentId() + "/" + language);
                    continue;
                }
                violations.addAll(findViolations(segment.sourceText(), language, translation.content(), newRules));
            }
        }
        if (!missing.isEmpty()) {
            throw ApiException.unprocessable("受影响段落缺少译文，无法完成解冻术语更新: " + missing);
        }
        if (!violations.isEmpty()) {
            throw ApiException.termViolation("解冻后重校验发现 " + violations.size() + " 条术语违规", violations);
        }
        return affected.stream().map(SegmentRow::segmentId).toList();
    }

    private static boolean containsAny(String text, Set<String> terms) {
        for (String term : terms) {
            if (text.contains(term)) {
                return true;
            }
        }
        return false;
    }

    /** 受影响段落：源文命中指定术语版本中任一源术语（不区分语言）的段落，按 segmentId 排序。 */
    private List<String> affectedSegments(long documentId, int termVersion) {
        Set<String> terms = repository.listTermRules(documentId, termVersion).stream()
                .map(TermRuleRow::sourceTerm).collect(Collectors.toSet());
        if (terms.isEmpty()) {
            return List.of();
        }
        return repository.listSegments(documentId).stream()
                .filter(segment -> containsAny(segment.sourceText(), terms))
                .map(SegmentRow::segmentId)
                .toList();
    }

    private static ApiDtos.FreezeWindowView toFreezeView(FreezeRow freeze, Instant now) {
        return new ApiDtos.FreezeWindowView(freeze.freezeVersion(), freeze.termVersion(),
                freeze.freezeStart(), freeze.freezeEnd(),
                FreezeStatus.isActive(freeze.freezeStart(), freeze.freezeEnd(), now));
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
}
