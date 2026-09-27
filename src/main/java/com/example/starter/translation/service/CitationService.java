package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiDtos;
import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.domain.Rows;
import com.example.starter.translation.domain.Rows.ApprovalRow;
import com.example.starter.translation.domain.Rows.CitationAnchorEventRow;
import com.example.starter.translation.domain.Rows.CitationAnchorRow;
import com.example.starter.translation.domain.Rows.DocumentRow;
import com.example.starter.translation.domain.Rows.SegmentRow;
import com.example.starter.translation.domain.Rows.TranslationRow;
import com.example.starter.translation.repo.TranslationRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 译文引文锁定核心服务：锚点登记、批量译文修订（锚点映射与术语整批校验）、
 * 法务解除、发布一致性核对与快照固化，以及明细/历史/诊断只读查询。
 * 与其他写操作一样在调用方事务内执行，先锁文档行，按提交顺序裁决。
 */
@Service
public class CitationService {

    /** 解除锚点的操作人角色（X-Actor-Role）。 */
    public static final String ROLE_LEGAL = "LEGAL";
    /** 登记/迁移事件的默认操作人角色。 */
    public static final String ROLE_EDITOR = "EDITOR";

    private final TranslationRepository repository;
    private final TimeSource timeSource;

    public CitationService(TranslationRepository repository, TimeSource timeSource) {
        this.repository = repository;
        this.timeSource = timeSource;
    }

    /**
     * 登记一个或多个引文锚点：目标译文必须存在与当前源文/译文版本一致的有效批准，
     * 请求的 translationVersion 必须等于当前译文版本；引用标识规范化后同段落唯一（含已解除），
     * 新区间合法、互不交叉且不与既有生效锚点交叉。整次登记草稿版本只加一。
     */
    @Transactional
    public ApiDtos.RegisterAnchorsResponse registerAnchors(long documentId, String segmentId, String rawLanguage,
                                                          String actorId, String actorRole,
                                                          ApiDtos.RegisterAnchorsRequest request) {
        DocumentRow document = lockDocument(documentId);
        String language = normalizeLanguage(rawLanguage);
        ensureLanguageInDocument(document, language);
        SegmentRow segment = findSegmentOrThrow(documentId, segmentId);
        TranslationRow translation = repository.findTranslation(documentId, segmentId, language)
                .orElseThrow(() -> ApiException.unprocessable(
                        "译文不存在，不能登记引文锚点: " + segmentId + "/" + language));
        ApprovalRow approval = repository.findApproval(documentId, segmentId, language)
                .orElseThrow(() -> ApiException.unprocessable(
                        "译文尚未批准，只能在已批准译文版本上登记引文锚点: " + segmentId + "/" + language));
        if (request.translationVersion() != translation.translationVersion()) {
            throw ApiException.unprocessable("登记锚点要求译文版本 " + request.translationVersion()
                    + "，当前译文版本 " + translation.translationVersion() + "，差额 "
                    + Math.abs(request.translationVersion() - translation.translationVersion()));
        }
        if (translation.sourceVersion() != segment.sourceVersion()) {
            throw ApiException.unprocessable("译文基于源文版本 " + translation.sourceVersion()
                    + "，当前源文版本 " + segment.sourceVersion()
                    + "，译文待更新，不能登记引文锚点");
        }
        if (approval.translationVersion() != translation.translationVersion()) {
            throw ApiException.unprocessable("当前译文版本 " + translation.translationVersion()
                    + " 尚无有效批准（批准停留在版本 " + approval.translationVersion()
                    + "），不能登记引文锚点");
        }
        List<CitationAnchorRow> existing = repository.listActiveAnchorsForTranslation(documentId, segmentId, language);
        List<AnchorSupport.Range> occupied = existing.stream()
                .map(a -> new AnchorSupport.Range(a.startOffset(), a.endOffset())).collect(Collectors.toList());

        Instant now = AnchorSupport.truncateToMicros(timeSource.instant());
        Set<String> requestKeys = new HashSet<>();
        List<CitationAnchorRow> toInsert = new ArrayList<>();
        for (ApiDtos.AnchorInput input : request.anchors()) {
            String citationKey = AnchorSupport.normalizeCitationKey(input.citationKey());
            if (!requestKeys.add(citationKey)) {
                throw ApiException.unprocessable("本次登记引用标识重复: " + citationKey);
            }
            if (repository.findAnchorByKey(documentId, segmentId, citationKey).isPresent()) {
                throw ApiException.conflict("引用标识在该段落已锁定过且不可复用: " + citationKey);
            }
            String problem = AnchorSupport.validateRange(input.startOffset(), input.endOffset(),
                    translation.content().length());
            if (problem != null) {
                throw ApiException.unprocessable("锚点 " + citationKey + " " + problem + "（实际译文长度 "
                        + translation.content().length() + " 字符）");
            }
            AnchorSupport.Range range = new AnchorSupport.Range(input.startOffset(), input.endOffset());
            occupied.add(range);
            toInsert.add(new CitationAnchorRow(0, documentId, segmentId, language, citationKey,
                    input.startOffset(), input.endOffset(),
                    AnchorSupport.slice(translation.content(), input.startOffset(), input.endOffset()),
                    input.lockReason(), actorId, now, translation.translationVersion(),
                    Rows.AnchorStatus.ACTIVE, null, null, null));
        }
        AnchorSupport.assertNoOverlap(occupied);

        List<ApiDtos.AnchorView> views = new ArrayList<>();
        for (CitationAnchorRow row : toInsert) {
            long anchorId;
            try {
                anchorId = repository.insertAnchor(row);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("引用标识在该段落已锁定过且不可复用（并发登记冲突）: "
                        + row.citationKey());
            }
            CitationAnchorRow inserted = rowWithId(row, anchorId);
            repository.insertAnchorEvent(registeredEvent(inserted, actorId, normalizeRole(actorRole), now));
            views.add(toView(inserted));
        }
        int draftVersion = bumpDraftVersion(document);
        return new ApiDtos.RegisterAnchorsResponse(documentId, segmentId, language, draftVersion, views);
    }

    /**
     * 批量译文修订：先整批校验（目标语言、源文版本、锚点一一映射与引用文本、当前术语规则全部违规），
     * 任一失败整次回滚；全部通过后逐条写新译文版本、原地迁移锚点（仅区间变动记录 MIGRATED 事件），
     * 整批草稿版本只加一。旧批准因译文版本递增而自然失效，发布前须重新批准。
     */
    @Transactional
    public ApiDtos.BatchReviseTranslationsResponse batchRevise(long documentId, String actorId, String actorRole,
                                                              ApiDtos.BatchReviseTranslationsRequest request) {
        DocumentRow document = lockDocument(documentId);
        List<ApiDtos.SegmentRevisionInput> revisions = request.revisions();
        Set<SegmentLanguage> revisionKeys = new HashSet<>();
        for (ApiDtos.SegmentRevisionInput revision : revisions) {
            String language = normalizeLanguage(revision.language());
            if (!revisionKeys.add(new SegmentLanguage(revision.segmentId(), language))) {
                throw ApiException.unprocessable(
                        "批量修订中段落+语言重复: " + revision.segmentId() + "/" + language);
            }
        }

        // 第一阶段：整批只读校验，收集全部术语违规，任何异常都在写入前抛出。
        List<PlannedRevision> plans = new ArrayList<>();
        List<ApiDtos.TermRuleView> allViolations = new ArrayList<>();
        for (ApiDtos.SegmentRevisionInput revision : revisions) {
            String language = normalizeLanguage(revision.language());
            ensureLanguageInDocument(document, language);
            SegmentRow segment = repository.findSegment(documentId, revision.segmentId())
                    .orElseThrow(() -> ApiException.notFound("段落不存在: " + revision.segmentId()));
            TranslationRow old = repository.findTranslation(documentId, revision.segmentId(), language)
                    .orElseThrow(() -> ApiException.unprocessable(
                            "译文不存在，不能修订: " + revision.segmentId() + "/" + language));
            if (revision.sourceVersion() != segment.sourceVersion()) {
                throw ApiException.unprocessable("段落 " + revision.segmentId() + "/" + language
                        + " 译文所依据源文版本 " + revision.sourceVersion() + " 与当前源文版本 "
                        + segment.sourceVersion() + " 不匹配");
            }
            List<Rows.TermRuleRow> termRules = repository.listTermRules(documentId, document.termVersion());
            allViolations.addAll(TermValidation.findViolations(
                    segment.sourceText(), language, revision.content(), termRules));

            List<CitationAnchorRow> activeAnchors =
                    repository.listActiveAnchorsForTranslation(documentId, revision.segmentId(), language);
            List<AnchorSupport.OldAnchor> oldAnchors = activeAnchors.stream()
                    .map(a -> new AnchorSupport.OldAnchor(a.citationKey(), a.startOffset(), a.endOffset()))
                    .toList();
            List<AnchorSupport.Migration> migrations = new ArrayList<>();
            Set<String> mappingKeys = new HashSet<>();
            if (revision.anchorMappings() != null) {
                for (ApiDtos.AnchorMappingInput mapping : revision.anchorMappings()) {
                    String key = AnchorSupport.normalizeCitationKey(mapping.citationKey());
                    if (!mappingKeys.add(key)) {
                        throw ApiException.unprocessable("段落 " + revision.segmentId() + "/" + language
                                + " 锚点映射重复: " + key);
                    }
                    migrations.add(new AnchorSupport.Migration(key,
                            new AnchorSupport.Range(mapping.startOffset(), mapping.endOffset())));
                }
            }
            List<AnchorSupport.Range> resolved = AnchorSupport.resolveAndAssert(
                    old.content(), revision.content(), oldAnchors, migrations);
            plans.add(new PlannedRevision(revision, language, segment, old, activeAnchors, resolved));
        }
        if (!allViolations.isEmpty()) {
            throw ApiException.termViolation(
                    "批量修订中译文违反 " + allViolations.size() + " 条术语规则", allViolations);
        }

        // 第二阶段：全部校验已通过，逐条落库（仍在同一事务，任何意外失败整体回滚）。
        Instant now = AnchorSupport.truncateToMicros(timeSource.instant());
        String role = normalizeRole(actorRole);
        List<ApiDtos.SegmentRevisionResult> results = new ArrayList<>();
        for (PlannedRevision plan : plans) {
            int newVersion = plan.old.translationVersion() + 1;
            repository.upsertTranslation(documentId, new TranslationRow(
                    plan.revision.segmentId(), plan.language, plan.revision.content(), actorId,
                    plan.segment.sourceVersion(), newVersion, document.termVersion()));
            for (int i = 0; i < plan.activeAnchors.size(); i++) {
                CitationAnchorRow anchor = plan.activeAnchors.get(i);
                AnchorSupport.Range target = plan.resolved.get(i);
                boolean moved = target.start() != anchor.startOffset() || target.end() != anchor.endOffset();
                repository.updateAnchorMigration(anchor.anchorId(), target.start(), target.end(), newVersion);
                if (moved) {
                    repository.insertAnchorEvent(new CitationAnchorEventRow(
                            0, anchor.anchorId(), documentId, anchor.segmentId(), anchor.language(),
                            anchor.citationKey(), "MIGRATED", anchor.startOffset(), anchor.endOffset(),
                            target.start(), target.end(), anchor.anchorText(), actorId, role,
                            anchor.lockReason(), now));
                }
            }
            List<ApiDtos.AnchorView> anchorViews = repository
                    .listActiveAnchorsForTranslation(documentId, plan.revision.segmentId(), plan.language)
                    .stream().map(CitationService::toView).toList();
            results.add(new ApiDtos.SegmentRevisionResult(plan.revision.segmentId(), plan.language, newVersion,
                    plan.segment.sourceVersion(), document.termVersion(), anchorViews));
        }
        int draftVersion = bumpDraftVersion(document);
        return new ApiDtos.BatchReviseTranslationsResponse(documentId, draftVersion, results);
    }

    /**
     * 解除锚点：操作人必须具备 LEGAL 角色且不同于锚点创建人，理由不可变；
     * 解除后锚点不再约束修订，但行与事件保留，已发布快照中的锚点仍可追溯。草稿版本加一。
     */
    @Transactional
    public ApiDtos.ReleaseAnchorResponse releaseAnchor(long documentId, long anchorId, String actorId,
                                                       String actorRole, ApiDtos.ReleaseAnchorRequest request) {
        DocumentRow document = lockDocument(documentId);
        CitationAnchorRow anchor = repository.findAnchor(documentId, anchorId)
                .orElseThrow(() -> ApiException.notFound("引文锚点不存在: " + documentId + "/" + anchorId));
        if (anchor.status() == Rows.AnchorStatus.RELEASED) {
            throw ApiException.unprocessable("锚点已解除，不能重复解除: anchorId=" + anchorId
                    + "，解除人 " + anchor.releasedBy());
        }
        String role = normalizeRole(actorRole);
        if (!ROLE_LEGAL.equals(role)) {
            throw ApiException.unprocessable("解除锚点要求角色 " + ROLE_LEGAL + "，实际角色 " + role
                    + "（要求值与实际值不一致）");
        }
        if (anchor.createdBy().equals(actorId)) {
            throw ApiException.unprocessable("解除确认人不得是锚点创建人：创建人 " + anchor.createdBy()
                    + "，确认人 " + actorId);
        }
        Instant now = AnchorSupport.truncateToMicros(timeSource.instant());
        int updated = repository.releaseAnchor(anchorId, actorId, request.reason(), now);
        if (updated == 0) {
            throw ApiException.conflict("锚点状态已变化，解除失败: anchorId=" + anchorId);
        }
        CitationAnchorRow released = repository.findAnchor(documentId, anchorId).orElseThrow();
        repository.insertAnchorEvent(new CitationAnchorEventRow(
                0, anchorId, documentId, anchor.segmentId(), anchor.language(), anchor.citationKey(),
                "RELEASED", null, null, anchor.startOffset(), anchor.endOffset(), anchor.anchorText(),
                actorId, ROLE_LEGAL, request.reason(), now));
        int draftVersion = bumpDraftVersion(document);
        return new ApiDtos.ReleaseAnchorResponse(documentId, anchor.segmentId(), anchor.language(),
                draftVersion, toView(released));
    }

    /** 查询段落全部锚点明细（含已解除），按 anchorId 排序，只读。 */
    @Transactional(readOnly = true)
    public List<ApiDtos.AnchorView> getAnchors(long documentId, String segmentId) {
        repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        repository.findSegment(documentId, segmentId)
                .orElseThrow(() -> ApiException.notFound("段落不存在: " + segmentId));
        return repository.listAnchorsBySegment(documentId, segmentId).stream()
                .map(CitationService::toView).toList();
    }

    /** 查询段落锚点不可变事件历史（登记/迁移/解除），按提交顺序排列，只读。 */
    @Transactional(readOnly = true)
    public ApiDtos.AnchorHistoryResponse getHistory(long documentId, String segmentId) {
        repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        repository.findSegment(documentId, segmentId)
                .orElseThrow(() -> ApiException.notFound("段落不存在: " + segmentId));
        List<ApiDtos.AnchorEventView> events = repository.listAnchorEvents(documentId, segmentId).stream()
                .map(e -> new ApiDtos.AnchorEventView(e.eventId(), e.anchorId(), e.eventType(),
                        e.fromStart(), e.fromEnd(), e.toStart(), e.toEnd(), e.anchorText(),
                        e.actorId(), e.actorRole(), e.reason(), e.eventTime()))
                .toList();
        return new ApiDtos.AnchorHistoryResponse(documentId, segmentId, events);
    }

    /**
     * 锚点诊断：对照当前译文核对每个锚点的绑定版本、区间落点与引用文本，标记 drift；
     * 不改变任何状态，只读。
     */
    @Transactional(readOnly = true)
    public ApiDtos.AnchorDiagnosticsResponse getDiagnostics(long documentId) {
        DocumentRow document = repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        Map<SegmentLanguage, TranslationRow> translations = indexTranslations(
                repository.listTranslations(documentId));
        List<ApiDtos.AnchorDiagnosticView> views = new ArrayList<>();
        for (CitationAnchorRow anchor : repository.listAnchorsForDocument(documentId)) {
            TranslationRow current = translations.get(new SegmentLanguage(anchor.segmentId(), anchor.language()));
            Integer currentVersion = current == null ? null : current.translationVersion();
            Integer currentLength = current == null ? null : current.content().length();
            boolean rangeValid = current != null
                    && AnchorSupport.validateRange(anchor.startOffset(), anchor.endOffset(),
                            current.content().length()) == null;
            boolean textIntact = rangeValid
                    && AnchorSupport.slice(current.content(), anchor.startOffset(), anchor.endOffset())
                            .equals(anchor.anchorText());
            boolean drift = anchor.status() == Rows.AnchorStatus.ACTIVE
                    && (current == null
                            || anchor.translationVersion() != currentVersion || !rangeValid || !textIntact);
            views.add(new ApiDtos.AnchorDiagnosticView(
                    anchor.anchorId(), anchor.segmentId(), anchor.language(), anchor.citationKey(),
                    anchor.status().name(), anchor.startOffset(), anchor.endOffset(), anchor.anchorText(),
                    anchor.translationVersion(), currentVersion, currentLength, rangeValid, textIntact, drift));
        }
        return new ApiDtos.AnchorDiagnosticsResponse(documentId, document.draftVersion(),
                document.termVersion(), views);
    }

    /**
     * 发布前一致性核对（在发布事务内、其他发布校验之后调用）：每个生效锚点必须绑定当前译文版本、
     * 区间仍在当前译文内且固化文本逐字一致；任一不满足则发布 422。返回文档全部锚点用于快照固化。
     */
    @Transactional(readOnly = true)
    public List<CitationAnchorRow> assertPublishConsistency(DocumentRow document,
                                                            Collection<TranslationRow> currentTranslations) {
        Map<SegmentLanguage, TranslationRow> translations = indexTranslations(currentTranslations);
        List<CitationAnchorRow> anchors = repository.listAnchorsForDocument(document.documentId());
        for (CitationAnchorRow anchor : anchors) {
            if (anchor.status() != Rows.AnchorStatus.ACTIVE) {
                continue;
            }
            TranslationRow translation = translations.get(new SegmentLanguage(anchor.segmentId(), anchor.language()));
            if (translation == null) {
                throw ApiException.unprocessable("生效锚点对应译文缺失: " + anchor.segmentId()
                        + "/" + anchor.language() + " 引用标识 " + anchor.citationKey());
            }
            if (anchor.translationVersion() != translation.translationVersion()) {
                throw ApiException.unprocessable("引文锚点未随译文修订迁移: " + anchor.segmentId()
                        + "/" + anchor.language() + " 引用标识 " + anchor.citationKey()
                        + " 绑定译文版本 " + anchor.translationVersion() + "，要求 "
                        + translation.translationVersion());
            }
            String problem = AnchorSupport.validateRange(anchor.startOffset(), anchor.endOffset(),
                    translation.content().length());
            if (problem != null) {
                throw ApiException.unprocessable("引文锚点区间失效: " + anchor.citationKey() + " " + problem);
            }
            String actual = AnchorSupport.slice(translation.content(), anchor.startOffset(), anchor.endOffset());
            if (!actual.equals(anchor.anchorText())) {
                throw ApiException.unprocessable("引文锚点文本不一致: " + anchor.citationKey()
                        + " 要求保留 \"" + anchor.anchorText() + "\"，当前为 \"" + actual + "\"");
            }
        }
        return anchors;
    }

    /** 构造快照中的引文锚点集合（发布时状态），之后新增/解除/迁移均不改写。 */
    public List<Map<String, Object>> snapshotCitations(List<CitationAnchorRow> anchors) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (CitationAnchorRow anchor : anchors) {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("anchorId", anchor.anchorId());
            json.put("segmentId", anchor.segmentId());
            json.put("language", anchor.language());
            json.put("citationKey", anchor.citationKey());
            json.put("startOffset", anchor.startOffset());
            json.put("endOffset", anchor.endOffset());
            json.put("length", anchor.endOffset() - anchor.startOffset());
            json.put("anchorText", anchor.anchorText());
            json.put("lockReason", anchor.lockReason());
            json.put("createdBy", anchor.createdBy());
            json.put("createdAt", anchor.createdAt());
            json.put("translationVersion", anchor.translationVersion());
            json.put("status", anchor.status().name());
            json.put("releasedBy", anchor.releasedBy());
            json.put("releaseReason", anchor.releaseReason());
            json.put("releasedAt", anchor.releasedAt());
            list.add(json);
        }
        return list;
    }

    /**
     * 单条译文提交的锚点保护：存在生效锚点时新译文必须逐字保留全部引用文本；
     * 区间变动可随提交携带旧锚点到新区间的一一映射（缺失、重复、越界或文本不一致 422）。
     * 通过后把锚点绑定到新译文版本，仅区间实际变动时记录 MIGRATED 事件。
     */
    public void preserveOnSubmit(long documentId, String segmentId, String language,
                                 String oldContent, String newContent, int newTranslationVersion,
                                 List<ApiDtos.AnchorMappingInput> mappingInputs,
                                 String actorId, String actorRole) {
        List<CitationAnchorRow> active =
                repository.listActiveAnchorsForTranslation(documentId, segmentId, language);
        if (active.isEmpty()) {
            if (mappingInputs != null && !mappingInputs.isEmpty()) {
                throw ApiException.unprocessable("该译文无生效引文锚点，不能提供锚点映射");
            }
            return;
        }
        List<AnchorSupport.OldAnchor> oldAnchors = active.stream()
                .map(a -> new AnchorSupport.OldAnchor(a.citationKey(), a.startOffset(), a.endOffset())).toList();
        List<AnchorSupport.Migration> migrations = new ArrayList<>();
        Set<String> mappingKeys = new HashSet<>();
        if (mappingInputs != null) {
            for (ApiDtos.AnchorMappingInput mapping : mappingInputs) {
                String key = AnchorSupport.normalizeCitationKey(mapping.citationKey());
                if (!mappingKeys.add(key)) {
                    throw ApiException.unprocessable("锚点映射重复: " + key);
                }
                migrations.add(new AnchorSupport.Migration(key,
                        new AnchorSupport.Range(mapping.startOffset(), mapping.endOffset())));
            }
        }
        List<AnchorSupport.Range> resolved =
                AnchorSupport.resolveAndAssert(oldContent, newContent, oldAnchors, migrations);
        Instant now = AnchorSupport.truncateToMicros(timeSource.instant());
        String role = normalizeRole(actorRole);
        for (int i = 0; i < active.size(); i++) {
            CitationAnchorRow anchor = active.get(i);
            AnchorSupport.Range target = resolved.get(i);
            boolean moved = target.start() != anchor.startOffset() || target.end() != anchor.endOffset();
            repository.updateAnchorMigration(anchor.anchorId(), target.start(), target.end(), newTranslationVersion);
            if (moved) {
                repository.insertAnchorEvent(new CitationAnchorEventRow(
                        0, anchor.anchorId(), documentId, anchor.segmentId(), anchor.language(),
                        anchor.citationKey(), "MIGRATED", anchor.startOffset(), anchor.endOffset(),
                        target.start(), target.end(), anchor.anchorText(), actorId, role,
                        anchor.lockReason(), now));
            }
        }
    }

    private Map<SegmentLanguage, TranslationRow> indexTranslations(Collection<TranslationRow> rows) {
        Map<SegmentLanguage, TranslationRow> map = new LinkedHashMap<>();
        for (TranslationRow row : rows) {
            map.put(new SegmentLanguage(row.segmentId(), row.language()), row);
        }
        return map;
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

    private static void ensureLanguageInDocument(DocumentRow document, String language) {
        if (!document.targetLanguages().contains(language)) {
            throw ApiException.unprocessable("语言不在文档目标语言中: " + language);
        }
    }

    private static String normalizeLanguage(String language) {
        return language.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeRole(String actorRole) {
        if (actorRole == null || actorRole.isBlank()) {
            return ROLE_EDITOR;
        }
        return actorRole.trim().toUpperCase(Locale.ROOT);
    }

    private static CitationAnchorRow rowWithId(CitationAnchorRow row, long anchorId) {
        return new CitationAnchorRow(anchorId, row.documentId(), row.segmentId(), row.language(),
                row.citationKey(), row.startOffset(), row.endOffset(), row.anchorText(), row.lockReason(),
                row.createdBy(), row.createdAt(), row.translationVersion(), row.status(),
                row.releasedBy(), row.releaseReason(), row.releasedAt());
    }

    private static CitationAnchorEventRow registeredEvent(CitationAnchorRow anchor, String actorId,
                                                          String actorRole, Instant now) {
        return new CitationAnchorEventRow(
                0, anchor.anchorId(), anchor.documentId(), anchor.segmentId(), anchor.language(),
                anchor.citationKey(), "REGISTERED", null, null, anchor.startOffset(), anchor.endOffset(),
                anchor.anchorText(), actorId, actorRole, anchor.lockReason(), now);
    }

    private static ApiDtos.AnchorView toView(CitationAnchorRow a) {
        return new ApiDtos.AnchorView(a.anchorId(), a.documentId(), a.segmentId(), a.language(),
                a.citationKey(), a.startOffset(), a.endOffset(), a.endOffset() - a.startOffset(),
                a.anchorText(), a.lockReason(), a.createdBy(), a.createdAt(), a.translationVersion(),
                a.status().name(), a.releasedBy(), a.releaseReason(), a.releasedAt());
    }

    /** 段落+语言复合键（record 等值语义，避免字符串分隔符歧义）。 */
    private record SegmentLanguage(String segmentId, String language) {
    }

    /** 第一阶段校验后的单条修订计划。 */
    private record PlannedRevision(ApiDtos.SegmentRevisionInput revision, String language, SegmentRow segment,
                                   TranslationRow old, List<CitationAnchorRow> activeAnchors,
                                   List<AnchorSupport.Range> resolved) {
    }
}
