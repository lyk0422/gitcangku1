package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiDtos;
import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.api.TrainDtos;
import com.example.starter.translation.api.TrainDtos.ApprovalIssue;
import com.example.starter.translation.api.TrainDtos.CandidateInput;
import com.example.starter.translation.api.TrainDtos.LocalePrecheck;
import com.example.starter.translation.api.TrainDtos.PrecheckResponse;
import com.example.starter.translation.api.TrainDtos.SourceVersionDiff;
import com.example.starter.translation.api.TrainDtos.TrainSnapshotView;
import com.example.starter.translation.api.TrainDtos.TrainView;
import com.example.starter.translation.domain.Rows.ApprovalRow;
import com.example.starter.translation.domain.Rows.DocumentRow;
import com.example.starter.translation.domain.Rows.ReleasePointerRow;
import com.example.starter.translation.domain.Rows.ReleaseTrainRow;
import com.example.starter.translation.domain.Rows.SegmentRow;
import com.example.starter.translation.domain.Rows.TermRuleRow;
import com.example.starter.translation.domain.Rows.TranslationRow;
import com.example.starter.translation.repo.ReleaseTrainRepository;
import com.example.starter.translation.repo.ReleaseTrainRepository.SnapshotRow;
import com.example.starter.translation.repo.TranslationRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 发布列车核心服务：创建（DRAFT）、只读预检、READY 冻结、整列取消、计划时刻后一次激活发布。
 * 激活在单个事务内按“文档行 → 列车行 → 发布指针”顺序加锁后重查源文、候选译文、批准状态、
 * 术语有效性与当前指针；任一语言不满足则整体 409/422 回滚，全部语言保持旧版本。
 * 成功后同事务为全部 locale 创建不可变快照并把指针切到同一 releaseTrainVersion，
 * 任意外部查询只能观察到全部语言未切换或全部语言已切换。
 */
@Service
public class ReleaseTrainService {

    static final String STATUS_DRAFT = "DRAFT";
    static final String STATUS_READY = "READY";
    static final String STATUS_CANCELLED = "CANCELLED";
    static final String STATUS_PUBLISHED = "PUBLISHED";

    private final TranslationRepository repository;
    private final ReleaseTrainRepository trainRepository;
    private final ObjectMapper objectMapper;
    private final TrainClock clock;

    public ReleaseTrainService(TranslationRepository repository, ReleaseTrainRepository trainRepository,
                               ObjectMapper objectMapper, TrainClock clock) {
        this.repository = repository;
        this.trainRepository = trainRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 创建发布列车：sourceDocumentVersion 须等于文档当前草稿版本（不符 409）；
     * locale 2~20 个、唯一且与文档目标语言集合完全一致（不符 422）；trainKey 唯一（重复 409）。
     * 创建只登记候选声明，不校验译文内容，预检结果由 precheck 只读返回。
     */
    @Transactional
    public TrainView createTrain(long documentId, TrainDtos.CreateTrainRequest request) {
        DocumentRow document = repository.findDocumentForUpdate(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        if (document.draftVersion() != request.sourceDocumentVersion()) {
            throw ApiException.conflict("源文档版本冲突：当前草稿版本 " + document.draftVersion()
                    + "，列车声明 " + request.sourceDocumentVersion());
        }
        List<CandidateInput> candidates = normalizeCandidates(request.candidates(), document);
        Instant now = clock.now();
        ReleaseTrainRow row = new ReleaseTrainRow(
                request.trainKey(), document.documentId(), request.sourceDocumentVersion(),
                request.scheduledAt(), STATUS_DRAFT, writeJson(candidates), null, null,
                request.requestId(), now, null, null, null);
        try {
            trainRepository.insertTrain(row);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("trainKey 已存在: " + request.trainKey());
        }
        return toView(trainRepository.findTrain(request.trainKey()).orElseThrow(), candidates, null);
    }

    /** 只读预检：DRAFT 按当前数据实时计算；READY 及之后返回冻结时的预检结果。不写任何数据。 */
    @Transactional(readOnly = true)
    public PrecheckResponse precheck(String trainKey) {
        ReleaseTrainRow train = trainRepository.findTrain(trainKey)
                .orElseThrow(() -> ApiException.notFound("发布列车不存在: " + trainKey));
        if (train.frozenJson() != null) {
            return withTrainContext(train, readFreeze(train).precheck());
        }
        DocumentRow document = repository.findDocument(train.documentId())
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + train.documentId()));
        Evaluation evaluation = evaluate(document, readCandidates(train));
        return withTrainContext(train, evaluation);
    }

    /**
     * 进入 READY：源文档版本仍须与列车声明一致（否则 409），预检必须全部通过（否则 422 且保持 DRAFT）。
     * 冻结候选集合、源文摘要（含哈希）、术语版本与规则、切换前指针、每段候选译文/批准及预检结果。
     */
    @Transactional
    public TrainView markReady(String trainKey) {
        ReleaseTrainRow train = lockTrainOfExistingDocument(trainKey);
        if (!STATUS_DRAFT.equals(train.status())) {
            throw ApiException.conflict("只有 DRAFT 列车可以进入 READY，当前状态: " + train.status());
        }
        DocumentRow document = repository.findDocumentForUpdate(train.documentId()).orElseThrow();
        if (document.draftVersion() != train.sourceDocumentVersion()) {
            throw ApiException.conflict("源文档版本已变化：当前草稿版本 " + document.draftVersion()
                    + "，列车声明 " + train.sourceDocumentVersion());
        }
        List<CandidateInput> candidates = readCandidates(train);
        Evaluation evaluation = evaluate(document, candidates);
        if (!evaluation.clean()) {
            throw ApiException.unprocessable("预检未通过，不能进入 READY："
                    + summarizeIssues(withTrainContext(train, evaluation)));
        }
        FreezeDoc freeze = buildFreeze(train, document, candidates, evaluation);
        trainRepository.markReady(trainKey, writeJson(freeze), clock.now());
        ReleaseTrainRow updated = trainRepository.findTrain(trainKey).orElseThrow();
        return toView(updated, candidates, freeze.precheck());
    }

    /** 整列取消：仅 DRAFT/READY 可取消；READY 后候选不可替换，取消是唯一退出方式。 */
    @Transactional
    public TrainView cancel(String trainKey) {
        ReleaseTrainRow train = lockTrainOfExistingDocument(trainKey);
        if (STATUS_PUBLISHED.equals(train.status()) || STATUS_CANCELLED.equals(train.status())) {
            throw ApiException.conflict("已发布或已取消的列车不能取消，当前状态: " + train.status());
        }
        trainRepository.markCancelled(trainKey, clock.now());
        ReleaseTrainRow updated = trainRepository.findTrain(trainKey).orElseThrow();
        FreezeDoc freeze = updated.frozenJson() == null ? null : readFreeze(updated);
        return toView(updated, readCandidates(updated), freeze == null ? null : freeze.precheck());
    }

    /**
     * 激活发布：仅 READY 且当前时刻已到达计划时间。单事务加锁后重查全部条件：
     * 源文/译文/术语/指针任一被并发推进则 409，撤批/缺批准/术语违规则 422，整列回滚。
     * 全部通过后分配新的 releaseTrainVersion，为全部 locale 同事务写不可变快照并切换指针。
     */
    @Transactional
    public TrainView activate(String trainKey) {
        ReleaseTrainRow train = lockTrainOfExistingDocument(trainKey);
        if (!STATUS_READY.equals(train.status())) {
            throw ApiException.conflict("只有 READY 列车可以激活发布，当前状态: " + train.status());
        }
        if (clock.now().isBefore(train.scheduledAt())) {
            throw ApiException.unprocessable("尚未到达计划发布时间: " + train.scheduledAt());
        }
        DocumentRow document = repository.findDocumentForUpdate(train.documentId()).orElseThrow();
        // 锁定本文档全部发布指针，与其它列车激活串行，保证同 locale 只由一个成功列车推进。
        List<ReleasePointerRow> lockedPointers = trainRepository.listPointersForUpdate(document.documentId());
        FreezeDoc freeze = readFreeze(train);
        revalidate(document, freeze, lockedPointers);

        int newVersion = trainRepository.maxReleaseTrainVersion(document.documentId()) + 1;
        Instant publishedAt = clock.now();
        Map<String, Integer> currentPointers = lockedPointers.stream()
                .collect(Collectors.toMap(ReleasePointerRow::locale, ReleasePointerRow::releaseTrainVersion));
        for (CandidateInput candidate : freeze.candidates()) {
            int pointerBefore = currentPointers.getOrDefault(candidate.locale(), 0);
            FrozenLocale frozenLocale = freeze.locales().get(candidate.locale());
            trainRepository.insertTrainSnapshot(trainKey, document.documentId(), candidate.locale(),
                    newVersion, pointerBefore, newVersion,
                    buildSnapshotJson(train, document, freeze, candidate.locale(), frozenLocale,
                            pointerBefore, newVersion));
            trainRepository.upsertPointer(document.documentId(), candidate.locale(), newVersion);
        }
        trainRepository.markPublished(trainKey, newVersion, publishedAt);
        ReleaseTrainRow updated = trainRepository.findTrain(trainKey).orElseThrow();
        return toView(updated, freeze.candidates(), freeze.precheck());
    }

    /** 查询单个列车（只读），不存在 404。 */
    @Transactional(readOnly = true)
    public TrainView getTrain(String trainKey) {
        ReleaseTrainRow train = trainRepository.findTrain(trainKey)
                .orElseThrow(() -> ApiException.notFound("发布列车不存在: " + trainKey));
        FreezeDoc freeze = train.frozenJson() == null ? null : readFreeze(train);
        return toView(train, readCandidates(train), freeze == null ? null : freeze.precheck());
    }

    /** 查询文档下全部列车，按 trainKey 稳定升序。 */
    @Transactional(readOnly = true)
    public List<TrainView> listTrains(long documentId) {
        repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        List<TrainView> views = new ArrayList<>();
        for (ReleaseTrainRow train : trainRepository.listTrainsByDocument(documentId)) {
            FreezeDoc freeze = train.frozenJson() == null ? null : readFreeze(train);
            views.add(toView(train, readCandidates(train), freeze == null ? null : freeze.precheck()));
        }
        return views;
    }

    /** 查询列车全部语言快照，按 locale 稳定升序；未发布返回 404。 */
    @Transactional(readOnly = true)
    public List<TrainSnapshotView> listSnapshots(String trainKey) {
        ReleaseTrainRow train = trainRepository.findTrain(trainKey)
                .orElseThrow(() -> ApiException.notFound("发布列车不存在: " + trainKey));
        List<SnapshotRow> rows = trainRepository.listTrainSnapshots(trainKey);
        if (rows.isEmpty()) {
            throw ApiException.notFound("列车尚未发布，无快照: " + trainKey);
        }
        return rows.stream().map(ReleaseTrainService::toSnapshotView).toList();
    }

    /** 查询列车单个语言快照。 */
    @Transactional(readOnly = true)
    public TrainSnapshotView getSnapshot(String trainKey, String locale) {
        ReleaseTrainRow train = trainRepository.findTrain(trainKey)
                .orElseThrow(() -> ApiException.notFound("发布列车不存在: " + trainKey));
        String normalized = normalizeLanguage(locale);
        SnapshotRow row = trainRepository.findTrainSnapshot(trainKey, normalized)
                .orElseThrow(() -> ApiException.notFound(
                        "列车快照不存在: " + trainKey + "/" + normalized));
        return toSnapshotView(row);
    }

    /** 查询文档当前全部发布指针，按 locale 稳定升序；从未被列车推进的 locale 不返回。 */
    @Transactional(readOnly = true)
    public List<TrainDtos.ReleasePointerView> listPointers(long documentId) {
        repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        return trainRepository.listPointers(documentId).stream()
                .map(p -> new TrainDtos.ReleasePointerView(p.documentId(), p.locale(), p.releaseTrainVersion()))
                .toList();
    }

    // ===== 内部实现 =====

    /**
     * 激活时事务内重查：源文摘要、每段候选译文版本、批准状态、术语有效性与当前发布指针。
     * 任一不满足直接抛 409/422，整体回滚，所有语言保持旧版本。
     */
    private void revalidate(DocumentRow document, FreezeDoc freeze, List<ReleasePointerRow> pointers) {
        if (document.draftVersion() != freeze.draftVersion()) {
            throw ApiException.conflict("源文档自 READY 冻结后已被修改：冻结时草稿版本 "
                    + freeze.draftVersion() + "，当前 " + document.draftVersion());
        }
        if (document.termVersion() != freeze.termVersion()) {
            throw ApiException.conflict("术语版本自冻结后已被推进：冻结时术语版本 "
                    + freeze.termVersion() + "，当前 " + document.termVersion());
        }
        List<SegmentRow> segments = repository.listSegments(document.documentId());
        if (segments.size() != freeze.sources().size()) {
            throw ApiException.conflict("源段集合自冻结后发生变化（段落数量不一致）");
        }
        Map<String, SegmentRow> currentSegments = segments.stream()
                .collect(Collectors.toMap(SegmentRow::segmentId, Function.identity()));
        Map<String, TranslationRow> translations = repository.listTranslations(document.documentId()).stream()
                .collect(Collectors.toMap(t -> t.segmentId() + " " + t.language(), Function.identity()));
        Map<String, ApprovalRow> approvals = repository.listApprovals(document.documentId()).stream()
                .collect(Collectors.toMap(a -> a.segmentId() + " " + a.language(), Function.identity()));
        Map<String, Integer> currentPointers = pointers.stream()
                .collect(Collectors.toMap(ReleasePointerRow::locale, ReleasePointerRow::releaseTrainVersion));

        for (FrozenSource frozenSource : freeze.sources()) {
            SegmentRow segment = currentSegments.get(frozenSource.segmentId());
            if (segment == null) {
                throw ApiException.conflict("源段自冻结后缺失: " + frozenSource.segmentId());
            }
            if (segment.sourceVersion() != frozenSource.sourceVersion()
                    || !sha256(segment.sourceText()).equals(frozenSource.sourceHash())) {
                throw ApiException.conflict("源文自冻结后已修订: " + frozenSource.segmentId());
            }
        }

        List<ApiDtos.TermRuleView> violations = new ArrayList<>();
        List<String> approvalProblems = new ArrayList<>();
        List<String> missingProblems = new ArrayList<>();
        for (CandidateInput candidate : freeze.candidates()) {
            String locale = candidate.locale();
            int pointer = currentPointers.getOrDefault(locale, 0);
            if (pointer != candidate.expectedVersion()) {
                throw ApiException.conflict("locale " + locale + " 的发布指针已被其他发布推进：期望 "
                        + candidate.expectedVersion() + "，当前 " + pointer);
            }
            for (FrozenTranslation frozen : freeze.locales().get(locale).segments()) {
                TranslationRow translation = translations.get(frozen.segmentId() + " " + locale);
                if (translation == null) {
                    missingProblems.add(frozen.segmentId() + "/" + locale);
                    continue;
                }
                if (translation.translationVersion() != frozen.translationVersion()) {
                    throw ApiException.conflict("locale " + locale + " 段落 " + frozen.segmentId()
                            + " 的候选译文已被修改：冻结版本 " + frozen.translationVersion()
                            + "，当前 " + translation.translationVersion());
                }
                if (translation.sourceVersion() != frozen.sourceVersion()
                        || translation.termVersion() != frozen.termVersion()) {
                    throw ApiException.conflict("locale " + locale + " 段落 " + frozen.segmentId()
                            + " 的候选译文基底已变化（源文或术语绑定版本不同）");
                }
                ApprovalRow approval = approvals.get(frozen.segmentId() + " " + locale);
                if (approval == null) {
                    approvalProblems.add(frozen.segmentId() + "/" + locale + " 缺少批准（可能已撤批）");
                } else if (approval.translationVersion() != frozen.translationVersion()
                        || approval.sourceVersion() != frozen.sourceVersion()) {
                    approvalProblems.add(frozen.segmentId() + "/" + locale + " 批准已失效");
                }
                SegmentRow segment = currentSegments.get(frozen.segmentId());
                violations.addAll(findViolations(segment.sourceText(), locale, translation.content(),
                        freeze.termRules()));
            }
        }
        if (!missingProblems.isEmpty()) {
            throw ApiException.unprocessable("候选译文缺段: " + String.join(", ", missingProblems));
        }
        if (!approvalProblems.isEmpty()) {
            throw ApiException.unprocessable("候选批准状态不满足: " + String.join("; ", approvalProblems));
        }
        if (!violations.isEmpty()) {
            throw ApiException.termViolation("候选译文违反 " + violations.size() + " 条术语规则", violations);
        }
    }

    /**
     * 实时预检评估：逐语言统计缺段、源段版本差异、批准问题、术语违规、术语过期与当前发布指针。
     */
    private Evaluation evaluate(DocumentRow document, List<CandidateInput> candidates) {
        List<SegmentRow> segments = repository.listSegments(document.documentId());
        Map<String, TranslationRow> translations = repository.listTranslations(document.documentId()).stream()
                .collect(Collectors.toMap(t -> t.segmentId() + " " + t.language(), Function.identity()));
        Map<String, ApprovalRow> approvals = repository.listApprovals(document.documentId()).stream()
                .collect(Collectors.toMap(a -> a.segmentId() + " " + a.language(), Function.identity()));
        List<TermRuleRow> termRules = repository.listTermRules(document.documentId(), document.termVersion());
        Map<String, Integer> pointers = trainRepository.listPointers(document.documentId()).stream()
                .collect(Collectors.toMap(ReleasePointerRow::locale, ReleasePointerRow::releaseTrainVersion));

        boolean clean = true;
        List<LocalePrecheck> localeResults = new ArrayList<>();
        Map<String, FrozenLocale> localeData = new LinkedHashMap<>();
        for (CandidateInput candidate : candidates) {
            String locale = candidate.locale();
            int pointer = pointers.getOrDefault(locale, 0);
            List<String> missing = new ArrayList<>();
            List<SourceVersionDiff> diffs = new ArrayList<>();
            List<ApprovalIssue> approvalIssues = new ArrayList<>();
            List<ApiDtos.TermRuleView> localeViolations = new ArrayList<>();
            boolean termStale = false;
            List<FrozenTranslation> frozenTranslations = new ArrayList<>();
            for (SegmentRow segment : segments) {
                TranslationRow translation = translations.get(segment.segmentId() + " " + locale);
                ApprovalRow approval = approvals.get(segment.segmentId() + " " + locale);
                boolean candidateMissing = translation == null
                        || translation.translationVersion() != candidate.translationVersion();
                if (candidateMissing) {
                    missing.add(segment.segmentId());
                } else {
                    if (translation.sourceVersion() != segment.sourceVersion()) {
                        diffs.add(new SourceVersionDiff(segment.segmentId(),
                                translation.sourceVersion(), segment.sourceVersion()));
                    }
                    if (translation.termVersion() != document.termVersion()) {
                        termStale = true;
                    }
                    localeViolations.addAll(findViolations(
                            segment.sourceText(), locale, translation.content(), termRules));
                    frozenTranslations.add(new FrozenTranslation(segment.segmentId(), translation.content(),
                            translation.author(), translation.translationVersion(), translation.sourceVersion(),
                            translation.termVersion(), approval == null ? null : approval.reviewer()));
                }
                if (approval == null) {
                    approvalIssues.add(new ApprovalIssue(segment.segmentId(), "MISSING_APPROVAL"));
                } else if (candidateMissing || approval.translationVersion()
                        != (translation == null ? -1 : translation.translationVersion())
                        || approval.sourceVersion() != segment.sourceVersion()) {
                    approvalIssues.add(new ApprovalIssue(segment.segmentId(), "APPROVAL_STALE"));
                }
            }
            boolean localeClean = pointer == candidate.expectedVersion()
                    && missing.isEmpty() && diffs.isEmpty() && approvalIssues.isEmpty()
                    && localeViolations.isEmpty() && !termStale;
            clean &= localeClean;
            localeResults.add(new LocalePrecheck(locale, candidate.translationVersion(),
                    candidate.expectedVersion(), pointer, List.copyOf(missing), List.copyOf(diffs),
                    List.copyOf(approvalIssues), List.copyOf(localeViolations), termStale));
            localeData.put(locale, new FrozenLocale(pointer, List.copyOf(frozenTranslations)));
        }
        List<FrozenSource> sources = segments.stream()
                .map(s -> new FrozenSource(s.segmentId(), s.sourceVersion(),
                        sha256(s.sourceText()), s.sourceText()))
                .toList();
        return new Evaluation(clean, List.copyOf(localeResults), sources, termRules, localeData);
    }

    private PrecheckResponse withTrainContext(ReleaseTrainRow train, Evaluation evaluation) {
        return new PrecheckResponse(train.documentId(), train.trainKey(), train.sourceDocumentVersion(),
                train.status(), train.scheduledAt(), evaluation.clean(), evaluation.locales());
    }

    private PrecheckResponse withTrainContext(ReleaseTrainRow train, PrecheckResponse precheck) {
        return new PrecheckResponse(train.documentId(), train.trainKey(), train.sourceDocumentVersion(),
                train.status(), train.scheduledAt(), precheck.clean(), precheck.locales());
    }

    private FreezeDoc buildFreeze(ReleaseTrainRow train, DocumentRow document, List<CandidateInput> candidates,
                                  Evaluation evaluation) {
        PrecheckResponse frozenPrecheck = new PrecheckResponse(document.documentId(), train.trainKey(),
                train.sourceDocumentVersion(), STATUS_READY, train.scheduledAt(), true, evaluation.locales());
        return new FreezeDoc(candidates, document.draftVersion(), document.termVersion(),
                evaluation.termRules(), evaluation.sources(), evaluation.localeData(), frozenPrecheck);
    }

    /**
     * 锁顺序统一为“文档行 → 列车行”，与源文修订、译文编辑、术语激活及另一列车激活并发时按提交顺序串行。
     */
    private ReleaseTrainRow lockTrainOfExistingDocument(String trainKey) {
        ReleaseTrainRow train = trainRepository.findTrain(trainKey)
                .orElseThrow(() -> ApiException.notFound("发布列车不存在: " + trainKey));
        repository.findDocumentForUpdate(train.documentId())
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + train.documentId()));
        return trainRepository.findTrainForUpdate(trainKey)
                .orElseThrow(() -> ApiException.notFound("发布列车不存在: " + trainKey));
    }

    private List<CandidateInput> normalizeCandidates(List<CandidateInput> raw, DocumentRow document) {
        List<CandidateInput> candidates = raw.stream()
                .map(c -> new CandidateInput(normalizeLanguage(c.locale()),
                        c.translationVersion(), c.expectedVersion()))
                .sorted((a, b) -> a.locale().compareTo(b.locale()))
                .toList();
        Set<String> declared = candidates.stream().map(CandidateInput::locale).collect(Collectors.toSet());
        if (declared.size() != candidates.size()) {
            throw ApiException.unprocessable("列车 locale 重复");
        }
        Set<String> required = Set.copyOf(document.targetLanguages());
        if (!declared.equals(required)) {
            throw ApiException.unprocessable("列车 locale 集合必须与文档目标语言完全一致，文档要求 "
                    + required + "，列车声明 " + declared);
        }
        return candidates;
    }

    private static String summarizeIssues(PrecheckResponse precheck) {
        List<String> problems = new ArrayList<>();
        for (LocalePrecheck locale : precheck.locales()) {
            if (locale.currentPointer() == null
                    || locale.currentPointer() != locale.expectedVersion()) {
                problems.add(locale.locale() + " 发布指针不符");
            }
            if (!locale.missingSegments().isEmpty()) {
                problems.add(locale.locale() + " 缺段 " + locale.missingSegments());
            }
            if (!locale.sourceVersionDiffs().isEmpty()) {
                problems.add(locale.locale() + " 源段版本差异 " + locale.sourceVersionDiffs().size() + " 段");
            }
            if (!locale.approvalIssues().isEmpty()) {
                problems.add(locale.locale() + " 批准问题 " + locale.approvalIssues().size() + " 段");
            }
            if (locale.termVersionStale()) {
                problems.add(locale.locale() + " 候选绑定术语版本已过期");
            }
            if (!locale.termViolations().isEmpty()) {
                problems.add(locale.locale() + " 术语违规 " + locale.termViolations().size() + " 条");
            }
        }
        return String.join("; ", problems);
    }

    /**
     * 术语违规判定：源文按 Unicode 原文、区分大小写做连续子串匹配；仅源文命中 sourceTerm 的规则参与校验，
     * 译文正文不含 requiredTranslation 即为违规。与 TranslationService 的判定保持一致。
     */
    private static List<ApiDtos.TermRuleView> findViolations(String sourceText, String language, String content,
                                                             List<TermRuleRow> rules) {
        List<ApiDtos.TermRuleView> violations = new ArrayList<>();
        for (TermRuleRow rule : rules) {
            if (rule.language().equals(language) && sourceText.contains(rule.sourceTerm())
                    && !content.contains(rule.requiredTranslation())) {
                violations.add(new ApiDtos.TermRuleView(rule.sourceTerm(), rule.language(),
                        rule.requiredTranslation()));
            }
        }
        return violations;
    }

    private String buildSnapshotJson(ReleaseTrainRow train, DocumentRow document, FreezeDoc freeze,
                                     String locale, FrozenLocale frozenLocale,
                                     int pointerBefore, int pointerAfter) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("trainKey", train.trainKey());
        snapshot.put("documentId", document.documentId());
        snapshot.put("locale", locale);
        snapshot.put("releaseTrainVersion", pointerAfter);
        snapshot.put("sourceDocumentVersion", train.sourceDocumentVersion());
        snapshot.put("termVersion", freeze.termVersion());
        snapshot.put("pointerBefore", pointerBefore);
        snapshot.put("pointerAfter", pointerAfter);
        snapshot.put("terms", freeze.termRules());
        List<Map<String, Object>> segmentList = new ArrayList<>();
        for (FrozenSource source : freeze.sources()) {
            FrozenTranslation frozen = frozenLocale.segments().stream()
                    .filter(t -> t.segmentId().equals(source.segmentId())).findFirst().orElseThrow();
            Map<String, Object> segmentJson = new LinkedHashMap<>();
            segmentJson.put("segmentId", source.segmentId());
            segmentJson.put("sourceText", source.sourceText());
            segmentJson.put("sourceVersion", source.sourceVersion());
            segmentJson.put("content", frozen.content());
            segmentJson.put("author", frozen.author());
            segmentJson.put("translationVersion", frozen.translationVersion());
            segmentJson.put("candidateSourceVersion", frozen.sourceVersion());
            segmentJson.put("boundTermVersion", frozen.termVersion());
            segmentJson.put("reviewer", frozen.reviewer());
            segmentList.add(segmentJson);
        }
        snapshot.put("segments", segmentList);
        return writeJson(snapshot);
    }

    private TrainView toView(ReleaseTrainRow train, List<CandidateInput> candidates,
                             PrecheckResponse frozenPrecheck) {
        PrecheckResponse precheck = null;
        if (frozenPrecheck != null) {
            precheck = new PrecheckResponse(train.documentId(), train.trainKey(),
                    train.sourceDocumentVersion(), train.status(), train.scheduledAt(),
                    frozenPrecheck.clean(), frozenPrecheck.locales());
        }
        return new TrainView(train.trainKey(), train.documentId(), train.sourceDocumentVersion(),
                train.scheduledAt(), train.status(), candidates, train.releaseTrainVersion(),
                train.readyAt(), train.publishedAt(), train.cancelledAt(), precheck);
    }

    private static TrainSnapshotView toSnapshotView(SnapshotRow row) {
        return new TrainSnapshotView(row.trainKey(), row.documentId(), row.locale(),
                row.releaseTrainVersion(), row.pointerBefore(), row.pointerAfter(),
                row.snapshotJson(), row.createdAt());
    }

    private List<CandidateInput> readCandidates(ReleaseTrainRow train) {
        return readJson(train.candidatesJson(), new TypeReference<List<CandidateInput>>() {
        });
    }

    private FreezeDoc readFreeze(ReleaseTrainRow train) {
        return readJson(train.frozenJson(), new TypeReference<FreezeDoc>() {
        });
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化失败", e);
        }
    }

    private <T> T readJson(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("反序列化失败", e);
        }
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String normalizeLanguage(String language) {
        return language.trim().toLowerCase(Locale.ROOT);
    }

    // ===== 冻结与评估的内部载体（同时作为 frozen_json 的序列化结构） =====

    /** READY 冻结内容：候选、草稿/术语版本、术语规则、源文摘要、各语言候选与冻结预检结果。 */
    private record FreezeDoc(List<CandidateInput> candidates, int draftVersion, int termVersion,
                             List<TermRuleRow> termRules, List<FrozenSource> sources,
                             Map<String, FrozenLocale> locales, PrecheckResponse precheck) {
    }

    /** 冻结源段：版本、源文哈希与源文正文（供不可变快照使用）。 */
    private record FrozenSource(String segmentId, int sourceVersion, String sourceHash, String sourceText) {
    }

    /** 冻结的单语言数据：切换前指针与全部候选段译文。 */
    private record FrozenLocale(int pointerBefore, List<FrozenTranslation> segments) {
    }

    /** 冻结的单段候选译文：含作者与审核人，发布时原样写入不可变快照。 */
    private record FrozenTranslation(String segmentId, String content, String author,
                                     int translationVersion, int sourceVersion, int termVersion,
                                     String reviewer) {
    }

    /** 实时预检评估结果及构造冻结内容所需数据。 */
    private record Evaluation(boolean clean, List<LocalePrecheck> locales, List<FrozenSource> sources,
                              List<TermRuleRow> termRules, Map<String, FrozenLocale> localeData) {
    }
}
