package com.example.starter.observation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 现场观测离线三方合并业务服务。
 *
 * <p>写操作（创建/离线提交/删除）均在单事务内完成：先占位写入幂等去重记录，
 * 再对 observation_current 行加锁（SELECT ... FOR UPDATE）完成业务判定与变更，
 * 最后回填去重记录的响应并整体提交；任何业务失败都会回滚，去重记录不占键。
 *
 * <p>量纲规则：创建时固定量纲（LENGTH 支持 m/cm，TEMPERATURE 支持 C/K），后续提交不得跨量纲；
 * 旧无单位读数不自动推断量纲。存储与合并比较一律使用精确规范值（长度 m、温度 C，不舍入），
 * 等价不同单位（如 100cm 与 1m、273.15K 与 0C）不增业务版本；每次成功收件都另存
 * 原值、原始单位与规范值到 observation_receipt，不因等价省掉收件溯源。
 *
 * <p>合并规则（按字段比较基线 base、当前 current、候选 candidate）：
 * 候选未改（等于基线）保留当前；当前未改接受候选；两边改为相同值也接受；
 * 否则整次 409，不写入任何部分结果。读数按精确规范值比较，其余字段按原文比较。
 *
 * <p>显式冲突解决（RESOLVE）在同一套占位与行锁机制内执行：事务内重读基线与最新当前版本重算冲突，
 * 人工选择必须恰好覆盖重算后的冲突字段；成功后原子写入新观测版本（内容无变化则不加版本）、
 * 不可变 conflict_resolution 记录（冻结所选来源与精确规范读数）与 request_log 响应。
 * resolutionId 全局唯一，同参重放、改参 409。
 */
@Service
public class ObservationService {

    /**
     * 三个可编辑字段的固定顺序：冲突重算、选择校验与指纹均以此顺序处理，保证结果稳定。
     */
    private static final List<String> FIELDS = List.of("location", "reading", "note");

    private final ObservationRepository observationRepository;
    private final RequestLogRepository requestLogRepository;
    private final ResolutionRepository resolutionRepository;
    private final ReceiptRepository receiptRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ObservationService(ObservationRepository observationRepository,
                              RequestLogRepository requestLogRepository,
                              ResolutionRepository resolutionRepository,
                              ReceiptRepository receiptRepository,
                              ObjectMapper objectMapper,
                              Clock clock) {
        this.observationRepository = observationRepository;
        this.requestLogRepository = requestLogRepository;
        this.resolutionRepository = resolutionRepository;
        this.receiptRepository = receiptRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 创建观测记录，初始版本为 1。记录已存在返回 409。
     * 带量纲创建时固定量纲并保存规范读数与原始单位；成功后写入收件记录。
     */
    @Transactional
    public WriteOutcome create(CreateObservationRequest request) {
        Dimension dimension = parseDimension(request.dimension());
        String normalizedReading = normalizeCreateReading(dimension, request.reading(), request.readingUnit());
        String dimensionName = dimension == null ? null : dimension.name();

        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("operation", "CREATE");
        identity.put("observationId", request.observationId());
        identity.put("operator", request.operator());
        identity.put("location", request.location());
        identity.put("reading", request.reading());
        identity.put("readingUnit", request.readingUnit());
        identity.put("dimension", request.dimension());
        identity.put("note", request.note());
        String fingerprint = fingerprint(identity);
        WriteOutcome replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        WriteOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint, "CREATE");
        if (concurrent != null) {
            return concurrent;
        }

        if (observationRepository.findCurrentForUpdate(request.observationId()).isPresent()) {
            throw ApiException.conflict("observation already exists: " + request.observationId(), null);
        }
        ObservationSnapshot snapshot = new ObservationSnapshot(request.observationId(), 1,
                request.location(), normalizedReading, request.readingUnit(), dimensionName,
                request.note(), false);
        try {
            observationRepository.insertCurrent(snapshot);
        } catch (DuplicateKeyException e) {
            // 并发创建同一 observationId：由主键串行化，后到者按冲突处理
            throw ApiException.conflict("observation already exists: " + request.observationId(), null);
        }
        observationRepository.insertVersion(snapshot);
        insertReceipt(request.requestId(), "CREATE", snapshot.observationId(), dimensionName,
                request.reading(), request.readingUnit(), normalizedReading, snapshot.version());
        return complete(request.requestId(), HttpStatus.CREATED, ObservationResponse.of(snapshot));
    }

    /**
     * 离线提交：基于 baseVersion 的三方合并。合并结果与当前完全相同则不加版本，否则版本加一。
     * 读数按精确规范值比较：等价不同单位不增版本，但成功收件仍写入收件记录。
     */
    @Transactional
    public WriteOutcome merge(String observationId, MergeObservationRequest request) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("operation", "MERGE");
        identity.put("observationId", observationId);
        identity.put("operator", request.operator());
        identity.put("baseVersion", request.baseVersion());
        identity.put("location", request.location());
        identity.put("reading", request.reading());
        identity.put("readingUnit", request.readingUnit());
        identity.put("note", request.note());
        String fingerprint = fingerprint(identity);
        WriteOutcome replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        WriteOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint, "MERGE");
        if (concurrent != null) {
            return concurrent;
        }

        ObservationSnapshot current = observationRepository.findCurrentForUpdate(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        if (current.deleted()) {
            throw ApiException.gone("observation already deleted: " + observationId);
        }
        String normalizedCandidate = normalizeSubmittedReading(current.dimension(),
                request.reading(), request.readingUnit());
        ObservationSnapshot base = observationRepository.findVersion(observationId, request.baseVersion())
                .orElseThrow(() -> ApiException.notFound(
                        "base version not found: " + observationId + "@" + request.baseVersion()));

        List<String> conflictFields = new ArrayList<>();
        String mergedLocation = mergeField("location", base.location(), current.location(),
                request.location(), conflictFields);
        ReadingMerge readingMerge = mergeReading(base.reading(), current.reading(), current.readingUnit(),
                normalizedCandidate, request.readingUnit());
        if (readingMerge.conflict()) {
            conflictFields.add("reading");
        }
        String mergedNote = mergeField("note", base.note(), current.note(), request.note(), conflictFields);
        if (!conflictFields.isEmpty()) {
            throw ApiException.mergeConflict(conflictFields, current.version());
        }

        ObservationSnapshot merged = new ObservationSnapshot(observationId, current.version(),
                mergedLocation, readingMerge.reading(), readingMerge.unit(), current.dimension(),
                mergedNote, false);
        if (sameContent(merged, current)) {
            // 合并结果与当前完全相同（含规范读数等价）：返回当前版本，不加版本，但仍留下收件溯源
            insertReceipt(request.requestId(), "MERGE", observationId, current.dimension(),
                    request.reading(), request.readingUnit(), normalizedCandidate, current.version());
            return complete(request.requestId(), HttpStatus.OK, ObservationResponse.of(current));
        }
        ObservationSnapshot next = new ObservationSnapshot(observationId, current.version() + 1,
                mergedLocation, readingMerge.reading(), readingMerge.unit(), current.dimension(),
                mergedNote, false);
        observationRepository.updateCurrent(next);
        observationRepository.insertVersion(next);
        insertReceipt(request.requestId(), "MERGE", observationId, current.dimension(),
                request.reading(), request.readingUnit(), normalizedCandidate, next.version());
        return complete(request.requestId(), HttpStatus.OK, ObservationResponse.of(next));
    }

    /**
     * 删除观测记录：expectedVersion 匹配当前版本时生成新版本墓碑；已删除记录返回 410。
     */
    @Transactional
    public WriteOutcome delete(String observationId, DeleteObservationRequest request) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("operation", "DELETE");
        identity.put("observationId", observationId);
        identity.put("operator", request.operator());
        identity.put("expectedVersion", request.expectedVersion());
        String fingerprint = fingerprint(identity);
        WriteOutcome replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        WriteOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint, "DELETE");
        if (concurrent != null) {
            return concurrent;
        }

        ObservationSnapshot current = observationRepository.findCurrentForUpdate(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        if (current.deleted()) {
            throw ApiException.gone("observation already deleted: " + observationId);
        }
        if (request.expectedVersion() != current.version()) {
            throw ApiException.conflict("expectedVersion mismatch", current.version());
        }
        ObservationSnapshot tombstone = new ObservationSnapshot(observationId, current.version() + 1,
                null, null, null, true);
        observationRepository.markDeleted(observationId, tombstone.version());
        observationRepository.insertVersion(tombstone);
        return complete(request.requestId(), HttpStatus.OK, ObservationResponse.of(tombstone));
    }

    /**
     * 查询当前内容；墓碑只返回删除状态和版本。
     */
    @Transactional(readOnly = true)
    public ObservationResponse getCurrent(String observationId) {
        ObservationSnapshot current = observationRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        return ObservationResponse.of(current);
    }

    /**
     * 显式冲突解决：事务内重读基线与最新当前版本并按原三方规则重算冲突，不信任客户端上次看到的冲突列表。
     * 选择恰好覆盖本次仍冲突字段时生成新版本（内容无变化则不加版本）并原子写入不可变解决记录、
     * 收件记录与 request_log 响应。读数按精确规范值重算与冻结，裁决冻结所选来源与精确规范值。
     */
    @Transactional
    public ResolveOutcome resolveConflict(String observationId, ResolveConflictRequest request) {
        Map<String, FieldSelection> selections = parseSelections(request.selections());
        String normalizedSelections = writeSelectionsJson(selections);
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("operation", "RESOLVE");
        identity.put("observationId", observationId);
        identity.put("operator", request.operator());
        identity.put("resolutionId", request.resolutionId());
        identity.put("baseVersion", request.baseVersion());
        identity.put("expectedCurrentVersion", request.expectedCurrentVersion());
        identity.put("location", request.location());
        identity.put("reading", request.reading());
        identity.put("readingUnit", request.readingUnit());
        identity.put("note", request.note());
        identity.put("selections", normalizedSelections);
        String fingerprint = fingerprint(identity);
        ResolveOutcome replayed = checkResolveReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        ResolveOutcome concurrent = insertResolvePlaceholder(request.requestId(), fingerprint);
        if (concurrent != null) {
            return concurrent;
        }

        ObservationSnapshot current = observationRepository.findCurrentForUpdate(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));

        // resolutionId 重放判定先于墓碑与版本校验：同参重放始终返回原结果（即使记录之后已被删除）；
        // 改参或跨记录复用一律 409。
        ResolutionRecord existing = resolutionRepository.findByResolutionId(request.resolutionId()).orElse(null);
        if (existing != null) {
            if (!sameResolutionParams(existing, observationId, request, normalizedSelections)) {
                throw ApiException.conflict(
                        "resolutionId reused with different parameters: " + request.resolutionId(),
                        current.version());
            }
            return completeResolve(request.requestId(), HttpStatus.OK, resolutionResponseOf(existing));
        }

        if (current.deleted()) {
            throw ApiException.gone("observation already deleted: " + observationId);
        }

        String normalizedCandidate = normalizeSubmittedReading(current.dimension(),
                request.reading(), request.readingUnit());

        if (request.expectedCurrentVersion() != current.version()) {
            throw ApiException.conflict("expectedCurrentVersion mismatch", current.version());
        }
        ObservationSnapshot base = observationRepository.findVersion(observationId, request.baseVersion())
                .orElseThrow(() -> ApiException.notFound(
                        "base version not found: " + observationId + "@" + request.baseVersion()));

        List<String> conflictFields = new ArrayList<>();
        boolean locationConflict = fieldConflicts(base.location(), current.location(), request.location(), false);
        if (locationConflict) {
            conflictFields.add("location");
        }
        boolean readingConflict = fieldConflicts(base.reading(), current.reading(), normalizedCandidate, true);
        if (readingConflict) {
            conflictFields.add("reading");
        }
        boolean noteConflict = fieldConflicts(base.note(), current.note(), request.note(), false);
        if (noteConflict) {
            conflictFields.add("note");
        }
        validateSelectionCoverage(selections.keySet(), conflictFields);

        String resolvedLocation = resolveFieldValue(locationConflict, selections.get("location"),
                base.location(), current.location(), request.location(), false);
        ResolvedReading resolvedReading = resolveReading(readingConflict, selections.get("reading"),
                base.reading(), current, normalizedCandidate, request.readingUnit());
        String resolvedNote = resolveFieldValue(noteConflict, selections.get("note"),
                base.note(), current.note(), request.note(), false);

        ObservationSnapshot merged = new ObservationSnapshot(observationId, current.version(),
                resolvedLocation, resolvedReading.value(), resolvedReading.unit(), current.dimension(),
                resolvedNote, false);
        boolean contentChanged = !sameContent(merged, current);
        int newVersion = contentChanged ? current.version() + 1 : current.version();
        if (contentChanged) {
            ObservationSnapshot next = new ObservationSnapshot(observationId, newVersion,
                    resolvedLocation, resolvedReading.value(), resolvedReading.unit(), current.dimension(),
                    resolvedNote, false);
            observationRepository.updateCurrent(next);
            observationRepository.insertVersion(next);
        }

        ResolutionRecord record = new ResolutionRecord(
                request.resolutionId(), observationId, request.requestId(),
                request.baseVersion(), current.version(), newVersion,
                request.location(), request.reading(), request.note(),
                List.copyOf(conflictFields), normalizedSelections, request.operator(),
                Instant.now(clock), contentChanged,
                request.readingUnit(), resolvedReading.value(), resolvedReading.source());
        try {
            resolutionRepository.insert(record);
        } catch (DuplicateKeyException e) {
            // 并发下 resolutionId 已被其他事务占用（可能指向其他观测记录）：重读后同参重放，否则 409。
            ResolutionRecord winner = resolutionRepository.findByResolutionId(request.resolutionId())
                    .orElseThrow(() -> ApiException.conflict(
                            "resolutionId conflict: " + request.resolutionId(), current.version()));
            if (!sameResolutionParams(winner, observationId, request, normalizedSelections)) {
                throw ApiException.conflict(
                        "resolutionId reused with different parameters: " + request.resolutionId(),
                        current.version());
            }
            return completeResolve(request.requestId(), HttpStatus.OK, resolutionResponseOf(winner));
        }

        // 收件与业务版本变化同事务保存：候选输入的原值、单位与精确规范值留痕
        insertReceipt(request.requestId(), "RESOLVE", observationId, current.dimension(),
                request.reading(), request.readingUnit(), normalizedCandidate, newVersion);

        ObservationSnapshot pointed = contentChanged
                ? new ObservationSnapshot(observationId, newVersion, resolvedLocation, resolvedReading.value(),
                        resolvedReading.unit(), current.dimension(), resolvedNote, false)
                : current;
        return completeResolve(request.requestId(), HttpStatus.OK,
                ResolutionResponse.of(record, pointed, objectMapper));
    }

    /**
     * 按全局唯一 resolutionId 查询不可变解决记录；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public ResolutionRecord getResolution(String resolutionId) {
        return resolutionRepository.findByResolutionId(resolutionId)
                .orElseThrow(() -> ApiException.notFound("resolution not found: " + resolutionId));
    }

    /**
     * 按 observationId 查询解决历史，按解决时刻先后排序；观测记录不存在返回 404。
     */
    @Transactional(readOnly = true)
    public List<ResolutionRecord> listResolutions(String observationId) {
        observationRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        return resolutionRepository.findByObservationId(observationId);
    }

    /**
     * 读取指定历史版本快照（供解决记录响应组装使用）；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public ObservationSnapshot getVersionSnapshot(String observationId, int version) {
        return observationRepository.findVersion(observationId, version)
                .orElseThrow(() -> ApiException.notFound(
                        "version not found: " + observationId + "@" + version));
    }

    /**
     * 查询指定历史版本；墓碑版本只返回删除状态和版本。
     */
    @Transactional(readOnly = true)
    public ObservationResponse getVersion(String observationId, int version) {
        ObservationSnapshot snapshot = observationRepository.findVersion(observationId, version)
                .orElseThrow(() -> ApiException.notFound(
                        "version not found: " + observationId + "@" + version));
        return ObservationResponse.of(snapshot);
    }

    /**
     * 普通字段三方合并：候选未改保留当前；当前未改接受候选；两边改为相同值接受；否则记录冲突。
     */
    private String mergeField(String field, String base, String current, String candidate,
                              List<String> conflictFields) {
        if (Objects.equals(candidate, base)) {
            return current;
        }
        if (Objects.equals(current, base) || Objects.equals(candidate, current)) {
            return candidate;
        }
        conflictFields.add(field);
        return current;
    }

    /**
     * 读数字段三方合并：按精确规范值比较（BigDecimal），其余规则与普通字段一致。
     * 返回合并后的规范读数及其原始单位：接受候选时随候选单位，保留当前时随当前单位。
     */
    private ReadingMerge mergeReading(String baseReading, String currentReading, String currentUnit,
                                      String normalizedCandidate, String candidateUnit) {
        if (readingEquals(normalizedCandidate, baseReading)) {
            return new ReadingMerge(currentReading, currentUnit, false);
        }
        if (readingEquals(currentReading, baseReading) || readingEquals(normalizedCandidate, currentReading)) {
            return new ReadingMerge(normalizedCandidate, candidateUnit, false);
        }
        return new ReadingMerge(currentReading, currentUnit, true);
    }

    /**
     * 读数合并结果：规范读数、原始单位与是否冲突。
     */
    private record ReadingMerge(String reading, String unit, boolean conflict) {
    }

    /**
     * 冲突解决中的读数取值：冲突字段按人工选择冻结来源与精确规范值；非冲突字段按原三方规则自动合并。
     */
    private ResolvedReading resolveReading(boolean conflict, FieldSelection selection,
                                           String baseReading, ObservationSnapshot current,
                                           String normalizedCandidate, String candidateUnit) {
        if (conflict) {
            if (selection == FieldSelection.CANDIDATE) {
                return new ResolvedReading(normalizedCandidate, candidateUnit, "CANDIDATE");
            }
            return new ResolvedReading(current.reading(), current.readingUnit(), "CURRENT");
        }
        ReadingMerge auto = mergeReading(baseReading, current.reading(), current.readingUnit(),
                normalizedCandidate, candidateUnit);
        String source = readingEquals(auto.reading(), normalizedCandidate)
                && !readingEquals(normalizedCandidate, baseReading) ? "CANDIDATE" : "CURRENT";
        return new ResolvedReading(auto.reading(), auto.unit(), source);
    }

    /**
     * 裁决冻结的读数：精确规范值、原始单位与来源（CURRENT/CANDIDATE）。
     */
    private record ResolvedReading(String value, String unit, String source) {
    }

    /**
     * 创建请求的读数规范化：dimension 与 readingUnit 必须同时提供或同时缺省；
     * 非法量纲、单位不属于量纲均 400。旧无单位读数不自动推断量纲，按原文存储。
     */
    private String normalizeCreateReading(Dimension dimension, String reading, String readingUnit) {
        if (dimension == null) {
            if (readingUnit != null && !readingUnit.isBlank()) {
                throw ApiException.badRequest("readingUnit requires dimension; "
                        + "unit-less readings do not infer a dimension");
            }
            return reading;
        }
        return normalizeWithDimension(dimension, reading, readingUnit);
    }

    /**
     * 后续提交（合并/解决）的读数规范化：量纲在创建时固定，不得跨量纲提交；
     * 带量纲记录必须携带属于该量纲的单位，旧无单位记录不得携带单位。
     */
    private String normalizeSubmittedReading(String recordDimension, String reading, String readingUnit) {
        if (recordDimension == null) {
            if (readingUnit != null && !readingUnit.isBlank()) {
                throw ApiException.badRequest("observation has no fixed dimension; "
                        + "unit-less readings do not infer a dimension, submit without readingUnit");
            }
            return reading;
        }
        return normalizeWithDimension(Dimension.valueOf(recordDimension), reading, readingUnit);
    }

    private String normalizeWithDimension(Dimension dimension, String reading, String readingUnit) {
        if (readingUnit == null || readingUnit.isBlank()) {
            throw ApiException.badRequest("readingUnit is required for dimension " + dimension.name());
        }
        if (!dimension.supports(readingUnit)) {
            throw ApiException.badRequest("unsupported unit '" + readingUnit + "' for dimension "
                    + dimension.name() + "; cross-dimension or unknown units are rejected");
        }
        return dimension.normalize(readingUnit, reading);
    }

    /**
     * 解析创建请求的量纲：缺省（null/空白）返回 null 表示旧无单位读数；非法量纲名 400。
     */
    private Dimension parseDimension(String dimension) {
        if (dimension == null || dimension.isBlank()) {
            return null;
        }
        Dimension parsed = Dimension.parse(dimension);
        if (parsed == null) {
            throw ApiException.badRequest("unsupported dimension: " + dimension
                    + "; supported: LENGTH, TEMPERATURE");
        }
        return parsed;
    }

    /**
     * 成功收件落库：另存原值、原始单位与精确规范值，与业务变更同事务提交。
     */
    private void insertReceipt(String requestId, String operation, String observationId, String dimension,
                               String originalValue, String originalUnit, String normalizedValue,
                               int resultVersion) {
        receiptRepository.insert(new ObservationReceipt(null, observationId, requestId, operation,
                dimension, originalValue, originalUnit, normalizedValue, resultVersion,
                Instant.now(clock)));
    }

    /**
     * 按 observationId 查询历次收件（原始输入溯源），按收件时刻先后排序；观测记录不存在返回 404。
     */
    @Transactional(readOnly = true)
    public List<ObservationReceipt> listReceipts(String observationId) {
        observationRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        return receiptRepository.findByObservationId(observationId);
    }

    /**
     * 判断合并结果与当前版本内容是否完全相同（读数按数值，其余按原文）。
     */
    private boolean sameContent(ObservationSnapshot merged, ObservationSnapshot current) {
        return Objects.equals(merged.location(), current.location())
                && Objects.equals(merged.note(), current.note())
                && readingEquals(merged.reading(), current.reading());
    }

    private boolean readingEquals(String left, String right) {
        if (left == null || right == null) {
            return Objects.equals(left, right);
        }
        return new BigDecimal(left).compareTo(new BigDecimal(right)) == 0;
    }

    /**
     * 幂等检查：同键同参返回原成功结果；同键异参返回 409；无记录返回 null 继续执行。
     */
    private WriteOutcome checkReplay(String requestId, String fingerprint) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new WriteOutcome(entry.responseStatus(), readBody(entry.responseBody()));
    }

    /**
     * 占位写入去重记录；并发同键时主键冲突，等待对方事务结束后读取已提交结果：
     * 同参返回重放结果，异参抛 409；正常占位返回 null。业务失败时占位随事务回滚，不占键。
     */
    private WriteOutcome insertPlaceholder(String requestId, String fingerprint, String operation) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, operation);
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return new WriteOutcome(entry.responseStatus(), readBody(entry.responseBody()));
        }
    }

    /**
     * 业务成功后回填去重记录响应，并构造本次写操作结果；与业务变更同事务提交。
     */
    private WriteOutcome complete(String requestId, HttpStatus status, ObservationResponse body) {
        requestLogRepository.complete(requestId, status.value(), writeBody(body));
        return new WriteOutcome(status.value(), body);
    }

    /**
     * 请求指纹：操作、操作者、资源与全部参数（含期望版本）按固定键序组成结构化 JSON 后取 SHA-256。
     * 结构化序列化避免分隔符碰撞：参数内容中的任意字符（含控制符）都不会改变字段边界。
     */
    private String fingerprint(Map<String, Object> identity) {
        String raw;
        try {
            raw = objectMapper.writeValueAsString(identity);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize request identity", e);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String writeBody(ObservationResponse body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize response", e);
        }
    }

    private ObservationResponse readBody(String json) {
        try {
            return objectMapper.readValue(json, ObservationResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored response", e);
        }
    }

    // ---------- 冲突解决辅助逻辑 ----------

    /**
     * 解析并规范化选择映射：键必须是三个可编辑字段之一，值仅允许 CURRENT/CANDIDATE（大小写不敏感）；
     * 重复字段（JSON 反序列化不会出现）或非法键值一律 400。
     */
    private Map<String, FieldSelection> parseSelections(Map<String, String> raw) {
        Map<String, FieldSelection> selections = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            String field = entry.getKey() == null ? null : entry.getKey().trim();
            if (field == null || !FIELDS.contains(field)) {
                throw ApiException.badRequest("selection key must be one of location/reading/note: " + field);
            }
            FieldSelection selection = FieldSelection.fromValue(entry.getValue());
            if (selection == null) {
                throw ApiException.badRequest(
                        "selection for field '" + field + "' must be CURRENT or CANDIDATE (BASE is not allowed)");
            }
            if (selections.put(field, selection) != null) {
                throw ApiException.badRequest("duplicate selection for field: " + field);
            }
        }
        return selections;
    }

    /**
     * 将选择按固定字段顺序序列化为 JSON 原文，作为指纹与不可变记录的稳定表示。
     */
    private String writeSelectionsJson(Map<String, FieldSelection> selections) {
        Map<String, String> ordered = new LinkedHashMap<>();
        for (String field : FIELDS) {
            FieldSelection selection = selections.get(field);
            if (selection != null) {
                ordered.put(field, selection.name());
            }
        }
        try {
            return objectMapper.writeValueAsString(ordered);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize selections", e);
        }
    }

    /**
     * 按原三方规则判断单字段是否冲突：候选未改或任一侧未改或两边改为相同值均不冲突，否则冲突。
     */
    private boolean fieldConflicts(String base, String current, String candidate, boolean numeric) {
        if (valueEquals(candidate, base, numeric)) {
            return false;
        }
        if (valueEquals(current, base, numeric) || valueEquals(candidate, current, numeric)) {
            return false;
        }
        return true;
    }

    private boolean valueEquals(String left, String right, boolean numeric) {
        return numeric ? readingEquals(left, right) : Objects.equals(left, right);
    }

    /**
     * 选择覆盖校验：选择键集合必须恰好等于本次重算出的冲突字段集合。
     * 遗漏冲突字段、多选非冲突字段均拒绝（BASE 已在解析阶段拒绝）。
     */
    private void validateSelectionCoverage(Set<String> selectedFields, List<String> conflictFields) {
        Set<String> conflictSet = Set.copyOf(conflictFields);
        if (!selectedFields.equals(conflictSet)) {
            List<String> missing = new ArrayList<>(conflictFields);
            missing.removeAll(selectedFields);
            List<String> extra = new ArrayList<>(selectedFields);
            extra.removeAll(conflictSet);
            List<String> problems = new ArrayList<>();
            if (!missing.isEmpty()) {
                problems.add("missing selections for conflict fields: " + String.join(", ", missing));
            }
            if (!extra.isEmpty()) {
                problems.add("selections for non-conflict fields are not allowed: " + String.join(", ", extra));
            }
            throw ApiException.badRequest(
                    "selections must cover exactly the recalculated conflict fields; " + String.join("; ", problems)
                            + "; current conflict fields: " + String.join(", ", conflictFields));
        }
    }

    /**
     * 计算单字段解决结果：冲突字段按人工选择取当前或候选；非冲突字段继续按原三方规则自动合并。
     */
    private String resolveFieldValue(boolean conflict, FieldSelection selection,
                                     String base, String current, String candidate, boolean numeric) {
        if (conflict) {
            return selection == FieldSelection.CANDIDATE ? candidate : current;
        }
        if (valueEquals(candidate, base, numeric)) {
            return current;
        }
        return candidate;
    }

    /**
     * 解决请求的 requestId 幂等检查：同键同参返回原成功结果；同键异参返回 409；无记录返回 null。
     */
    private ResolveOutcome checkResolveReplay(String requestId, String fingerprint) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new ResolveOutcome(entry.responseStatus(), readResolutionBody(entry.responseBody()));
    }

    /**
     * 解决请求的占位写入，语义与普通写操作一致：冲突后读已提交记录，同参重放、异参 409。
     */
    private ResolveOutcome insertResolvePlaceholder(String requestId, String fingerprint) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, "RESOLVE");
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return new ResolveOutcome(entry.responseStatus(), readResolutionBody(entry.responseBody()));
        }
    }

    /**
     * 解决成功后回填去重记录响应，与业务变更、解决记录在同一事务提交。
     */
    private ResolveOutcome completeResolve(String requestId, HttpStatus status, ResolutionResponse body) {
        try {
            requestLogRepository.complete(requestId, status.value(), objectMapper.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize resolution response", e);
        }
        return new ResolveOutcome(status.value(), body);
    }

    private ResolutionResponse readResolutionBody(String json) {
        try {
            return objectMapper.readValue(json, ResolutionResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored resolution response", e);
        }
    }

    /**
     * 判断本次请求参数与已存在解决记录是否完全一致（用于 resolutionId 同参重放）。
     * expectedCurrentVersion 必须等于记录中的解决前当前版本：任何参数（含乐观锁版本）变化均按改参 409 处理。
     */
    private boolean sameResolutionParams(ResolutionRecord record, String observationId,
                                         ResolveConflictRequest request, String normalizedSelections) {
        return Objects.equals(record.observationId(), observationId)
                && record.baseVersion() == request.baseVersion()
                && record.previousVersion() == request.expectedCurrentVersion()
                && Objects.equals(record.candidateLocation(), request.location())
                && Objects.equals(record.candidateReading(), request.reading())
                && Objects.equals(record.candidateReadingUnit(), request.readingUnit())
                && Objects.equals(record.candidateNote(), request.note())
                && Objects.equals(record.fieldSelections(), normalizedSelections)
                && Objects.equals(record.operator(), request.operator());
    }

    /**
     * 依据已落库的不可变记录重新组装响应（重放路径；指向版本必然已存在）。
     */
    private ResolutionResponse resolutionResponseOf(ResolutionRecord record) {
        ObservationSnapshot pointed = observationRepository.findVersion(
                record.observationId(), record.newVersion())
                .orElseThrow(() -> new IllegalStateException(
                        "pointed version missing for resolution: " + record.resolutionId()));
        return ResolutionResponse.of(record, pointed, objectMapper);
    }

    /**
     * 写操作结果：HTTP 状态码与响应体。
     */
    public record WriteOutcome(int status, ObservationResponse body) {
    }

    /**
     * 冲突解决结果：HTTP 状态码与解决响应体。
     */
    public record ResolveOutcome(int status, ResolutionResponse body) {
    }
}
