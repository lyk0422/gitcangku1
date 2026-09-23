package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiDtos;
import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.domain.Rows.ApprovalRow;
import com.example.starter.translation.domain.Rows.DocumentRow;
import com.example.starter.translation.domain.Rows.ReleasePointerRow;
import com.example.starter.translation.domain.Rows.ReleaseTrainRow;
import com.example.starter.translation.domain.Rows.SegmentRow;
import com.example.starter.translation.domain.Rows.TermRuleRow;
import com.example.starter.translation.domain.Rows.TrainLocaleRow;
import com.example.starter.translation.domain.Rows.TrainStatus;
import com.example.starter.translation.domain.Rows.TranslationRow;
import com.example.starter.translation.repo.TranslationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
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
 * 多语言发布列车服务。
 * 写操作（创建/READY/取消/激活）先对文档行加 FOR UPDATE 行锁，与源文修订、译文编辑、
 * 批准、术语激活及其他列车按提交顺序串行；激活在单个事务内重查源文、全部候选、
 * 批准状态、术语有效性与发布指针，任一语言不满足则整列回滚，所有语言保持旧版本。
 */
@Service
public class ReleaseTrainService {

    private final TranslationRepository repository;
    private final ObjectMapper objectMapper;

    public ReleaseTrainService(TranslationRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建列车：2~20 个唯一语言且集合与文档目标语言完全一致（422），
     * 声明的源文档版本须等于当前草稿版本（409），trainKey 全局唯一（409）。
     * 创建后状态为 DRAFT，候选内容在 READY/激活时校验。
     */
    @Transactional
    public ApiDtos.TrainResponse createTrain(long documentId, ApiDtos.CreateReleaseTrainRequest request) {
        DocumentRow document = lockDocument(documentId);
        List<ApiDtos.TrainLocaleInput> normalized = request.locales().stream()
                .map(l -> new ApiDtos.TrainLocaleInput(normalizeLocale(l.locale()),
                        l.translationVersion(), l.expectedVersion()))
                .toList();
        Set<String> seen = new HashSet<>();
        for (ApiDtos.TrainLocaleInput locale : normalized) {
            if (!seen.add(locale.locale())) {
                throw ApiException.unprocessable("列车语言重复: " + locale.locale());
            }
        }
        List<String> declared = normalized.stream().map(ApiDtos.TrainLocaleInput::locale).sorted().toList();
        List<String> required = document.targetLanguages().stream().sorted().toList();
        if (!declared.equals(required)) {
            throw ApiException.unprocessable("列车语言集合 " + declared + " 与文档目标语言 " + required + " 不一致");
        }
        if (document.draftVersion() != request.sourceDocumentVersion()) {
            throw ApiException.conflict("源文档版本冲突：当前草稿版本 " + document.draftVersion()
                    + "，与声明的 " + request.sourceDocumentVersion() + " 不一致");
        }
        long trainId;
        try {
            trainId = repository.insertTrain(request.trainKey(), documentId,
                    request.sourceDocumentVersion(), request.plannedAt().toInstant());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("trainKey 已存在: " + request.trainKey());
        }
        for (ApiDtos.TrainLocaleInput locale : normalized) {
            repository.insertTrainLocale(trainId, new TrainLocaleRow(trainId, locale.locale(),
                    locale.translationVersion(), locale.expectedVersion()));
        }
        return toTrainResponse(findTrainOrThrow(documentId, request.trainKey()),
                repository.listTrainLocales(trainId));
    }

    /**
     * 预检：逐语言返回缺段、候选版本不符、源段版本差异、未批准、术语过期/违规与当前发布指针；
     * 只读，不写数据。
     */
    @Transactional(readOnly = true)
    public ApiDtos.PrecheckResponse precheck(long documentId, String trainKey) {
        DocumentRow document = repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        ReleaseTrainRow train = findTrainOrThrow(documentId, trainKey);
        PrecheckResult result = computePrecheck(document, repository.listTrainLocales(train.trainId()),
                document.termVersion());
        return new ApiDtos.PrecheckResponse(train.trainId(), train.trainKey(), documentId,
                train.status().name(), document.termVersion(), result.ready(), result.locales());
    }

    /**
     * 进入 READY：重跑预检，内容侧全部通过才冻结候选集合、源文摘要、术语版本与预检结果；
     * 预检未通过返回 422，不写数据。READY 后候选不可替换，只能整列取消。
     */
    @Transactional
    public ApiDtos.TrainResponse ready(long documentId, String trainKey) {
        DocumentRow document = lockDocument(documentId);
        ReleaseTrainRow train = findTrainOrThrow(documentId, trainKey);
        if (train.status() != TrainStatus.DRAFT) {
            throw ApiException.conflict("列车状态为 " + train.status() + "，不能进入 READY");
        }
        List<TrainLocaleRow> locales = repository.listTrainLocales(train.trainId());
        PrecheckResult result = computePrecheck(document, locales, document.termVersion());
        if (!result.ready()) {
            throw ApiException.unprocessable("预检未通过，不能进入 READY: " + result.summary());
        }
        String precheckJson;
        try {
            precheckJson = objectMapper.writeValueAsString(new ApiDtos.PrecheckResponse(train.trainId(),
                    train.trainKey(), documentId, TrainStatus.READY.name(), document.termVersion(),
                    true, result.locales()));
        } catch (Exception e) {
            throw new IllegalStateException("预检结果序列化失败", e);
        }
        repository.markTrainReady(train.trainId(), document.termVersion(),
                sourceDigest(result.segments()), precheckJson);
        return toTrainResponse(findTrainOrThrow(documentId, trainKey), locales);
    }

    /** 整列取消：仅 DRAFT/READY 可取消；已激活或已取消返回 409。 */
    @Transactional
    public ApiDtos.TrainResponse cancel(long documentId, String trainKey) {
        lockDocument(documentId);
        ReleaseTrainRow train = findTrainOrThrow(documentId, trainKey);
        if (train.status() == TrainStatus.ACTIVATED) {
            throw ApiException.conflict("列车已激活，不能取消");
        }
        if (train.status() == TrainStatus.CANCELLED) {
            throw ApiException.conflict("列车已取消");
        }
        repository.markTrainCancelled(train.trainId());
        return toTrainResponse(findTrainOrThrow(documentId, trainKey),
                repository.listTrainLocales(train.trainId()));
    }

    /**
     * 激活：到达计划时刻后，在一个事务内重查源文摘要、全部候选、批准状态、术语有效性与发布指针。
     * 任一语言被修改、撤批、缺段、术语失效或违规整列 422；指针已被其他发布推进整列 409；
     * 失败全部回滚，所有语言保持旧版本。成功后为全部语言同时生成不可变快照，
     * 并把全部发布指针切到同一个 releaseTrainVersion。
     */
    @Transactional
    public ApiDtos.TrainResponse activate(long documentId, String trainKey) {
        DocumentRow document = lockDocument(documentId);
        ReleaseTrainRow train = findTrainOrThrow(documentId, trainKey);
        if (train.status() == TrainStatus.DRAFT) {
            throw ApiException.conflict("列车尚未 READY，不能激活");
        }
        if (train.status() == TrainStatus.CANCELLED) {
            throw ApiException.conflict("列车已取消，不能激活");
        }
        if (train.status() == TrainStatus.ACTIVATED) {
            throw ApiException.conflict("列车已激活");
        }
        if (Instant.now().isBefore(train.plannedAt())) {
            throw ApiException.conflict("未到计划发布时间: " + train.plannedAt());
        }
        List<TrainLocaleRow> locales = repository.listTrainLocales(train.trainId());
        List<SegmentRow> segments = repository.listSegments(documentId);
        if (!sourceDigest(segments).equals(train.sourceDigest())) {
            throw ApiException.unprocessable("源文已变更，与 READY 时冻结的源文摘要不一致");
        }
        if (document.termVersion() != train.termVersion()) {
            throw ApiException.unprocessable("术语版本已失效：READY 时冻结术语版本 " + train.termVersion()
                    + "，当前术语版本 " + document.termVersion());
        }
        PrecheckResult result = computePrecheck(document, locales, train.termVersion());
        if (!result.ready()) {
            List<ApiDtos.TermRuleView> violations = result.locales().stream()
                    .flatMap(l -> l.termViolations().stream()).toList();
            if (!violations.isEmpty()) {
                throw ApiException.termViolation("列车激活校验失败：违反 " + violations.size()
                        + " 条术语规则", violations);
            }
            throw ApiException.unprocessable("列车激活校验失败: " + result.summary());
        }
        Map<String, Integer> pointers = new LinkedHashMap<>();
        for (TrainLocaleRow locale : locales) {
            int pointer = repository.findPointer(documentId, locale.locale())
                    .map(ReleasePointerRow::releasedVersion).orElse(0);
            if (pointer != locale.expectedVersion()) {
                throw ApiException.conflict("语言 " + locale.locale() + " 的发布指针已被其他发布推进：期望 "
                        + locale.expectedVersion() + "，当前 " + pointer);
            }
            pointers.put(locale.locale(), pointer);
        }
        int releaseTrainVersion = document.trainReleaseVersion() + 1;
        List<TermRuleRow> termRules = repository.listTermRules(documentId, train.termVersion());
        Map<String, TranslationRow> translations = repository.listTranslations(documentId).stream()
                .collect(Collectors.toMap(t -> key(t.segmentId(), t.language()), Function.identity()));
        Map<String, ApprovalRow> approvals = repository.listApprovals(documentId).stream()
                .collect(Collectors.toMap(a -> key(a.segmentId(), a.language()), Function.identity()));
        for (TrainLocaleRow locale : locales) {
            repository.insertTrainSnapshot(documentId, releaseTrainVersion, locale.locale(),
                    buildTrainSnapshotJson(train, document, locale, releaseTrainVersion,
                            pointers.get(locale.locale()), segments, translations, approvals, termRules));
            repository.upsertPointer(documentId, locale.locale(), releaseTrainVersion);
        }
        repository.updateTrainReleaseVersion(documentId, releaseTrainVersion);
        repository.markTrainActivated(train.trainId(), releaseTrainVersion);
        return toTrainResponse(findTrainOrThrow(documentId, trainKey), locales);
    }

    /** 查询单个列车详情（只读）。 */
    @Transactional(readOnly = true)
    public ApiDtos.TrainResponse getTrain(long documentId, String trainKey) {
        repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        ReleaseTrainRow train = findTrainOrThrow(documentId, trainKey);
        return toTrainResponse(train, repository.listTrainLocales(train.trainId()));
    }

    /** 查询文档全部列车，按 trainKey 稳定排序（只读）。 */
    @Transactional(readOnly = true)
    public ApiDtos.TrainListResponse listTrains(long documentId) {
        repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        List<ApiDtos.TrainResponse> trains = new ArrayList<>();
        for (ReleaseTrainRow train : repository.listTrains(documentId)) {
            trains.add(toTrainResponse(train, repository.listTrainLocales(train.trainId())));
        }
        return new ApiDtos.TrainListResponse(documentId, trains);
    }

    /** 查询指定发布列车版本的全部语言快照（只读，按语言码稳定排序）；不存在返回 404。 */
    @Transactional(readOnly = true)
    public String getTrainRelease(long documentId, int releaseTrainVersion) {
        repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        List<String> locales = repository.listTrainSnapshotLocales(documentId, releaseTrainVersion);
        if (locales.isEmpty()) {
            throw ApiException.notFound("列车发布版本不存在: " + documentId + "/" + releaseTrainVersion);
        }
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("documentId", documentId);
            root.put("releaseTrainVersion", releaseTrainVersion);
            ArrayNode snapshots = root.putArray("locales");
            for (String locale : locales) {
                snapshots.add(objectMapper.readTree(repository
                        .findTrainSnapshot(documentId, releaseTrainVersion, locale).orElseThrow()));
            }
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("列车快照读取失败", e);
        }
    }

    /** 查询文档全部发布指针，按语言码稳定排序（只读）；未发布的语言指针为 0。 */
    @Transactional(readOnly = true)
    public ApiDtos.ReleasePointerListResponse getPointers(long documentId) {
        DocumentRow document = repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        Map<String, Integer> pointers = repository.listPointers(documentId).stream()
                .collect(Collectors.toMap(ReleasePointerRow::locale, ReleasePointerRow::releasedVersion));
        List<ApiDtos.ReleasePointerView> views = document.targetLanguages().stream().sorted()
                .map(locale -> new ApiDtos.ReleasePointerView(locale, pointers.getOrDefault(locale, 0)))
                .toList();
        return new ApiDtos.ReleasePointerListResponse(documentId, document.trainReleaseVersion(), views);
    }

    /** 预检计算：对全部列车语言候选逐项校验，返回逐语言明细与汇总结论。 */
    private PrecheckResult computePrecheck(DocumentRow document, List<TrainLocaleRow> trainLocales,
                                           int termVersion) {
        long documentId = document.documentId();
        List<SegmentRow> segments = repository.listSegments(documentId);
        Map<String, TranslationRow> translations = repository.listTranslations(documentId).stream()
                .collect(Collectors.toMap(t -> key(t.segmentId(), t.language()), Function.identity()));
        Map<String, ApprovalRow> approvals = repository.listApprovals(documentId).stream()
                .collect(Collectors.toMap(a -> key(a.segmentId(), a.language()), Function.identity()));
        List<TermRuleRow> termRules = repository.listTermRules(documentId, termVersion);
        Map<String, Integer> pointers = repository.listPointers(documentId).stream()
                .collect(Collectors.toMap(ReleasePointerRow::locale, ReleasePointerRow::releasedVersion));
        List<ApiDtos.LocalePrecheck> locales = new ArrayList<>();
        for (TrainLocaleRow trainLocale : trainLocales) {
            String locale = trainLocale.locale();
            List<String> missing = new ArrayList<>();
            List<String> candidateMismatches = new ArrayList<>();
            List<ApiDtos.SourceVersionDiff> sourceDiffs = new ArrayList<>();
            List<String> unapproved = new ArrayList<>();
            List<ApiDtos.TermRuleView> violations = new ArrayList<>();
            boolean termStale = false;
            for (SegmentRow segment : segments) {
                TranslationRow translation = translations.get(key(segment.segmentId(), locale));
                if (translation == null) {
                    missing.add(segment.segmentId());
                    continue;
                }
                if (translation.translationVersion() != trainLocale.candidateTranslationVersion()) {
                    candidateMismatches.add(segment.segmentId());
                }
                if (translation.sourceVersion() != segment.sourceVersion()) {
                    sourceDiffs.add(new ApiDtos.SourceVersionDiff(segment.segmentId(),
                            translation.sourceVersion(), segment.sourceVersion()));
                }
                ApprovalRow approval = approvals.get(key(segment.segmentId(), locale));
                if (approval == null || approval.translationVersion() != translation.translationVersion()
                        || approval.sourceVersion() != segment.sourceVersion()) {
                    unapproved.add(segment.segmentId());
                }
                if (translation.termVersion() != termVersion) {
                    termStale = true;
                }
                violations.addAll(TranslationService.findViolations(
                        segment.sourceText(), locale, translation.content(), termRules));
            }
            int currentPointer = pointers.getOrDefault(locale, 0);
            locales.add(new ApiDtos.LocalePrecheck(locale, trainLocale.candidateTranslationVersion(),
                    trainLocale.expectedVersion(), currentPointer,
                    currentPointer != trainLocale.expectedVersion(),
                    missing, candidateMismatches, sourceDiffs, unapproved, termStale, violations));
        }
        return new PrecheckResult(segments, locales);
    }

    /** 源文摘要：全部段落（按 segmentId 排序）ID+版本+正文的 SHA-256，用于 READY 冻结与激活重查。 */
    private static String sourceDigest(List<SegmentRow> segments) {
        StringBuilder canonical = new StringBuilder();
        for (SegmentRow segment : segments) {
            canonical.append(segment.segmentId()).append(' ')
                    .append(segment.sourceVersion()).append(' ')
                    .append(segment.sourceText()).append('\n');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 生成某语言的列车快照 JSON：源文、候选译文、术语版本及规则集、切换前后发布指针。 */
    private String buildTrainSnapshotJson(ReleaseTrainRow train, DocumentRow document,
                                          TrainLocaleRow locale, int releaseTrainVersion, int pointerBefore,
                                          List<SegmentRow> segments, Map<String, TranslationRow> translations,
                                          Map<String, ApprovalRow> approvals, List<TermRuleRow> termRules) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("documentId", document.documentId());
        snapshot.put("releaseTrainVersion", releaseTrainVersion);
        snapshot.put("trainKey", train.trainKey());
        snapshot.put("locale", locale.locale());
        snapshot.put("sourceDocumentVersion", train.sourceDocumentVersion());
        snapshot.put("termVersion", train.termVersion());
        snapshot.put("candidateTranslationVersion", locale.candidateTranslationVersion());
        snapshot.put("pointerBefore", pointerBefore);
        snapshot.put("pointerAfter", releaseTrainVersion);
        snapshot.put("sourceDigest", train.sourceDigest());
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
            TranslationRow translation = translations.get(key(segment.segmentId(), locale.locale()));
            ApprovalRow approval = approvals.get(key(segment.segmentId(), locale.locale()));
            Map<String, Object> translationJson = new LinkedHashMap<>();
            translationJson.put("content", translation.content());
            translationJson.put("author", translation.author());
            translationJson.put("translationVersion", translation.translationVersion());
            translationJson.put("sourceVersion", translation.sourceVersion());
            translationJson.put("termVersion", translation.termVersion());
            translationJson.put("reviewer", approval.reviewer());
            segmentJson.put("translation", translationJson);
            segmentList.add(segmentJson);
        }
        snapshot.put("segments", segmentList);
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            throw new IllegalStateException("列车快照序列化失败", e);
        }
    }

    private ApiDtos.TrainResponse toTrainResponse(ReleaseTrainRow train, List<TrainLocaleRow> locales) {
        List<ApiDtos.TrainLocaleView> localeViews = locales.stream()
                .map(l -> new ApiDtos.TrainLocaleView(l.locale(), l.candidateTranslationVersion(),
                        l.expectedVersion()))
                .toList();
        return new ApiDtos.TrainResponse(train.trainId(), train.trainKey(), train.documentId(),
                train.status().name(), train.sourceDocumentVersion(),
                train.plannedAt().atOffset(ZoneOffset.UTC), train.termVersion(), train.sourceDigest(),
                train.releaseTrainVersion(), localeViews);
    }

    private DocumentRow lockDocument(long documentId) {
        return repository.findDocumentForUpdate(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
    }

    private ReleaseTrainRow findTrainOrThrow(long documentId, String trainKey) {
        return repository.findTrain(documentId, trainKey)
                .orElseThrow(() -> ApiException.notFound("发布列车不存在: " + trainKey));
    }

    private static String key(String segmentId, String language) {
        return segmentId + " " + language;
    }

    private static String normalizeLocale(String locale) {
        return locale.trim().toLowerCase(Locale.ROOT);
    }

    /** 预检计算结果：源段列表（供摘要计算）与逐语言明细。 */
    private record PrecheckResult(List<SegmentRow> segments, List<ApiDtos.LocalePrecheck> locales) {
        /** 内容侧是否全部通过（发布指针不符不阻塞 READY，仅在激活时强校验）。 */
        boolean ready() {
            return locales.stream().allMatch(ApiDtos.LocalePrecheck::contentReady);
        }

        /** 未通过项摘要，用于 422 错误信息。 */
        String summary() {
            List<String> problems = new ArrayList<>();
            for (ApiDtos.LocalePrecheck locale : locales) {
                if (!locale.missingSegments().isEmpty()) {
                    problems.add(locale.locale() + " 缺段 " + locale.missingSegments());
                }
                if (!locale.candidateMismatches().isEmpty()) {
                    problems.add(locale.locale() + " 候选版本不符 " + locale.candidateMismatches());
                }
                if (!locale.sourceVersionDiffs().isEmpty()) {
                    problems.add(locale.locale() + " 源段版本差异 " + locale.sourceVersionDiffs().stream()
                            .map(ApiDtos.SourceVersionDiff::segmentId).toList());
                }
                if (!locale.unapprovedSegments().isEmpty()) {
                    problems.add(locale.locale() + " 未批准 " + locale.unapprovedSegments());
                }
                if (locale.termStale()) {
                    problems.add(locale.locale() + " 术语版本过期");
                }
                if (!locale.termViolations().isEmpty()) {
                    problems.add(locale.locale() + " 术语违规 " + locale.termViolations().size() + " 条");
                }
            }
            return String.join("; ", problems);
        }
    }
}
