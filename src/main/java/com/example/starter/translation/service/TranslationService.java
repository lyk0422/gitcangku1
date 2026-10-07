package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiDtos;
import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.domain.Rows.ApprovalRow;
import com.example.starter.translation.domain.Rows.DocumentRow;
import com.example.starter.translation.domain.Rows.SegmentReferenceRow;
import com.example.starter.translation.domain.Rows.SegmentRow;
import com.example.starter.translation.domain.Rows.TermRuleRow;
import com.example.starter.translation.domain.Rows.TranslationReferenceRow;
import com.example.starter.translation.domain.Rows.TranslationRow;
import com.example.starter.translation.repo.TranslationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
     * 修改源段引用集合：整组替换出边（至多 5 个不同目标，目标须同文档且不是自身，替换后全图无环），
     * 携带 expectedDraftVersion 乐观校验（不符 409，成环 409）；
     * 引用集合变化即推进源文版本与草稿版本（即使正文未变），并失效本段各语言旧批准。
     * 校验全部通过后才落库，任何失败回滚，不留部分边。
     */
    @Transactional
    public ApiDtos.ReferencesResponse updateReferences(long documentId, String segmentId,
                                                       ApiDtos.UpdateReferencesRequest request) {
        DocumentRow document = lockDocument(documentId);
        if (document.draftVersion() != request.expectedDraftVersion()) {
            throw ApiException.conflict("版本冲突：当前草稿版本 " + document.draftVersion()
                    + "，与期望的 " + request.expectedDraftVersion() + " 不一致");
        }
        findSegmentOrThrow(documentId, segmentId);
        List<String> targets = new ArrayList<>();
        Set<String> distinct = new LinkedHashSet<>();
        for (ApiDtos.ReferenceTargetInput input : request.references()) {
            String target = input.targetSegmentId();
            if (target.equals(segmentId)) {
                throw ApiException.unprocessable("段落不能引用自身: " + segmentId);
            }
            if (repository.findSegment(documentId, target).isEmpty()) {
                throw ApiException.unprocessable("引用目标段落不存在: " + target);
            }
            if (!distinct.add(target)) {
                throw ApiException.unprocessable("引用目标段号重复: " + target);
            }
            targets.add(target);
        }
        if (distinct.size() > 5) {
            throw ApiException.unprocessable("每段至多引用 5 个不同目标段");
        }

        Map<String, List<String>> graph = loadReferenceGraph(documentId);
        List<String> currentTargets = graph.getOrDefault(segmentId, List.of());
        boolean unchanged = new LinkedHashSet<>(currentTargets).equals(distinct);
        if (!unchanged) {
            graph.put(segmentId, targets);
            if (hasCycle(graph)) {
                throw ApiException.conflict("引用图修改后存在环，拒绝引用编辑");
            }
            SegmentRow current = findSegmentOrThrow(documentId, segmentId);
            int sourceVersion = current.sourceVersion() + 1;
            repository.updateSegmentSource(documentId, segmentId, current.sourceText(), sourceVersion);
            repository.replaceReferences(documentId, segmentId, targets);
            // 引用集合变化失效本段各语言旧批准；引用版本绑定随下次译文提交重钉。
            for (String language : document.targetLanguages()) {
                repository.deleteApproval(documentId, segmentId, language);
            }
            int draftVersion = bumpDraftVersion(document);
            return new ApiDtos.ReferencesResponse(documentId, segmentId, sourceVersion, draftVersion, targets);
        }
        SegmentRow current = findSegmentOrThrow(documentId, segmentId);
        return new ApiDtos.ReferencesResponse(documentId, segmentId, current.sourceVersion(),
                document.draftVersion(), currentTargets);
    }

    /**
     * 译文提交：所依据源文版本必须等于当前源文版本；绑定当前术语版本，
     * 当前源文命中的术语规则要求译文包含对应必译文本，否则 422 返回全部违规术语且不写译文；
     * 若该段声明了跨段引用，references 必须与当前出边逐条对应（不缺项、不多项、不指向另一语言），
     * 每条携带目标当前源版本与同语言当前译稿版本，目标译稿不存在 422；
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
        List<TranslationReferenceRow> bindings = bindReferences(
                documentId, segmentId, normalizedLanguage, request.references() == null ? List.of()
                        : request.references());
        int translationVersion = repository.findTranslation(documentId, segmentId, normalizedLanguage)
                .map(TranslationRow::translationVersion).orElse(0) + 1;
        repository.upsertTranslation(documentId, new TranslationRow(segmentId, normalizedLanguage,
                request.content(), actorId, segment.sourceVersion(), translationVersion, document.termVersion()));
        repository.replaceTranslationReferences(documentId, segmentId, normalizedLanguage, bindings);
        int draftVersion = bumpDraftVersion(document);
        return new ApiDtos.TranslationResponse(documentId, segmentId, normalizedLanguage,
                translationVersion, segment.sourceVersion(), document.termVersion(), draftVersion,
                toReferenceViews(bindings));
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
        Map<String, List<TranslationReferenceRow>> referenceBindings = repository
                .listAllTranslationReferences(documentId).stream()
                .collect(Collectors.groupingBy(r -> key(r.segmentId(), r.language())));
        Map<String, SegmentRow> segmentById = segments.stream()
                .collect(Collectors.toMap(SegmentRow::segmentId, Function.identity()));
        Map<String, List<String>> referenceGraph = loadReferenceGraph(documentId);
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
                if (approval.reviewer().equals(translation.author())) {
                    throw ApiException.unprocessable("批准人不得是译文作者: "
                            + segment.segmentId() + "/" + language);
                }
                checkReferenceBindings(segment.segmentId(), language,
                        referenceGraph.getOrDefault(segment.segmentId(), List.of()),
                        referenceBindings.get(key(segment.segmentId(), language)),
                        translations, segmentById);
                termViolations.addAll(findViolations(
                        segment.sourceText(), language, translation.content(), termRules));
            }
        }
        if (!termViolations.isEmpty()) {
            throw ApiException.termViolation("译文违反 " + termViolations.size() + " 条术语规则", termViolations);
        }
        int publishedVersion = document.publishedVersion() + 1;
        repository.insertSnapshot(documentId, publishedVersion,
                buildSnapshotJson(document, publishedVersion, segments, translations, approvals, termRules,
                        referenceGraph, referenceBindings));
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

    private static List<ApiDtos.ReferenceView> toReferenceViews(List<TranslationReferenceRow> rows) {
        return rows.stream()
                .map(r -> new ApiDtos.ReferenceView(r.targetSegmentId(),
                        r.targetSourceVersion(), r.targetTranslationVersion()))
                .toList();
    }

    /** 读取文档当前完整引用图：源段段号 → 有序目标段号列表。 */
    private Map<String, List<String>> loadReferenceGraph(long documentId) {
        Map<String, List<String>> graph = new LinkedHashMap<>();
        for (SegmentReferenceRow edge : repository.listReferenceEdges(documentId)) {
            graph.computeIfAbsent(edge.sourceSegmentId(), k -> new ArrayList<>()).add(edge.targetSegmentId());
        }
        return graph;
    }

    /** 有向图环检测：任一节点处于当前 DFS 路径上即存在环。 */
    private static boolean hasCycle(Map<String, List<String>> graph) {
        Set<String> done = new HashSet<>();
        Set<String> onStack = new HashSet<>();
        for (String node : graph.keySet()) {
            if (!done.contains(node) && reachesBack(node, graph, done, onStack)) {
                return true;
            }
        }
        return false;
    }

    private static boolean reachesBack(String node, Map<String, List<String>> graph,
                                       Set<String> done, Set<String> onStack) {
        onStack.add(node);
        for (String next : graph.getOrDefault(node, List.of())) {
            if (onStack.contains(next)) {
                return true;
            }
            if (!done.contains(next) && reachesBack(next, graph, done, onStack)) {
                return true;
            }
        }
        onStack.remove(node);
        done.add(node);
        return false;
    }

    /**
     * 译文提交时逐条钉住引用版本：references 必须与源段当前出边一一对应（不缺项、不多项、不重复），
     * 携带的目标源版本必须等于目标当前源版本；目标同语言译稿必须存在，且携带的译稿版本等于当前版本。
     */
    private List<TranslationReferenceRow> bindReferences(long documentId, String segmentId, String language,
                                                         List<ApiDtos.ReferenceInput> inputs) {
        List<String> targets = repository.listOutgoingTargets(documentId, segmentId);
        Map<String, ApiDtos.ReferenceInput> inputByTarget = new LinkedHashMap<>();
        for (ApiDtos.ReferenceInput input : inputs) {
            if (inputByTarget.put(input.targetSegmentId(), input) != null) {
                throw ApiException.unprocessable("引用条目目标段号重复: " + input.targetSegmentId());
            }
        }
        if (!inputByTarget.keySet().equals(new LinkedHashSet<>(targets))) {
            throw ApiException.unprocessable("引用条目必须与当前引用边逐条对应，不能缺项或多项: "
                    + segmentId + "/" + language);
        }
        List<TranslationReferenceRow> rows = new ArrayList<>();
        for (String targetSegmentId : targets) {
            ApiDtos.ReferenceInput input = inputByTarget.get(targetSegmentId);
            SegmentRow targetSegment = findSegmentOrThrow(documentId, targetSegmentId);
            TranslationRow targetTranslation = repository
                    .findTranslation(documentId, targetSegmentId, language)
                    .orElseThrow(() -> ApiException.unprocessable(
                            "目标译稿尚不存在: " + targetSegmentId + "/" + language));
            if (input.targetSourceVersion() != targetSegment.sourceVersion()) {
                throw ApiException.unprocessable("引用目标源版本不是当前版本: " + targetSegmentId
                        + " 携带 " + input.targetSourceVersion() + "，当前 " + targetSegment.sourceVersion());
            }
            if (input.targetTranslationVersion() != targetTranslation.translationVersion()) {
                throw ApiException.unprocessable("引用目标译稿版本不是当前版本: " + targetSegmentId + "/" + language
                        + " 携带 " + input.targetTranslationVersion()
                        + "，当前 " + targetTranslation.translationVersion());
            }
            rows.add(new TranslationReferenceRow(segmentId, language, targetSegmentId,
                    input.targetSourceVersion(), input.targetTranslationVersion()));
        }
        return rows;
    }

    /**
     * 发布校验某段某语言的全部引用：绑定集合须与当前引用图出边一致；
     * 每条绑定钉住的目标源版本与同语言译稿版本必须仍为当前版本（目标变更即失效，递归影响上游引用方）。
     */
    private static void checkReferenceBindings(String segmentId, String language, List<String> targets,
                                               List<TranslationReferenceRow> bindings,
                                               Map<String, TranslationRow> translations,
                                               Map<String, SegmentRow> segments) {
        Map<String, TranslationReferenceRow> byTarget = bindings == null ? Map.of()
                : bindings.stream().collect(Collectors.toMap(
                        TranslationReferenceRow::targetSegmentId, Function.identity(), (a, b) -> a,
                        LinkedHashMap::new));
        if (!byTarget.keySet().equals(new LinkedHashSet<>(targets))) {
            throw ApiException.unprocessable(
                    "引用绑定缺失或与当前引用图不一致: " + segmentId + "/" + language);
        }
        for (String targetSegmentId : targets) {
            TranslationReferenceRow binding = byTarget.get(targetSegmentId);
            SegmentRow targetSegment = segments.get(targetSegmentId);
            TranslationRow targetTranslation = translations.get(key(targetSegmentId, language));
            if (targetSegment == null || targetTranslation == null) {
                throw ApiException.unprocessable(
                        "引用目标段或目标译稿不存在: " + targetSegmentId + "/" + language);
            }
            if (binding.targetSourceVersion() != targetSegment.sourceVersion()
                    || binding.targetTranslationVersion() != targetTranslation.translationVersion()) {
                throw ApiException.unprocessable("引用目标版本已变化，本段批准不可用于发布: "
                        + segmentId + "/" + language + " → " + targetSegmentId);
            }
        }
    }

    /**
     * 生成完整只读快照 JSON：全部段落源文及各语言译文、作者、审核人、版本号、固化的术语版本及规则集，
     * 以及冻结的完整引用边集合与每条译稿引用的目标源版本/译稿版本；后续修改不倒改已发布快照。
     */
    private String buildSnapshotJson(DocumentRow document, int publishedVersion, List<SegmentRow> segments,
                                     Map<String, TranslationRow> translations,
                                     Map<String, ApprovalRow> approvals, List<TermRuleRow> termRules,
                                     Map<String, List<String>> referenceGraph,
                                     Map<String, List<TranslationReferenceRow>> referenceBindings) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("documentId", document.documentId());
        snapshot.put("publishedVersion", publishedVersion);
        snapshot.put("draftVersion", document.draftVersion());
        snapshot.put("termVersion", document.termVersion());
        snapshot.put("targetLanguages", document.targetLanguages());
        List<Map<String, Object>> edgeList = new ArrayList<>();
        for (Map.Entry<String, List<String>> edge : referenceGraph.entrySet()) {
            for (String targetSegmentId : edge.getValue()) {
                Map<String, Object> edgeJson = new LinkedHashMap<>();
                edgeJson.put("sourceSegmentId", edge.getKey());
                edgeJson.put("targetSegmentId", targetSegmentId);
                edgeList.add(edgeJson);
            }
        }
        snapshot.put("references", edgeList);
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
                List<Map<String, Object>> bindingList = new ArrayList<>();
                for (TranslationReferenceRow binding :
                        referenceBindings.getOrDefault(key(segment.segmentId(), language), List.of())) {
                    Map<String, Object> bindingJson = new LinkedHashMap<>();
                    bindingJson.put("targetSegmentId", binding.targetSegmentId());
                    bindingJson.put("targetSourceVersion", binding.targetSourceVersion());
                    bindingJson.put("targetTranslationVersion", binding.targetTranslationVersion());
                    bindingList.add(bindingJson);
                }
                translationJson.put("references", bindingList);
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
