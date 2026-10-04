package com.example.starter.observation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * <p>合并规则（按字段比较基线 base、当前 current、候选 candidate）：
 * 候选未改（等于基线）保留当前；当前未改接受候选；两边改为相同值也接受；
 * 否则整批 409，不写入任何部分结果。读数按（限定符+数值）不可拆分的一组比较：
 * 数值相等且限定符相同才是等价；一侧改限定符、另一侧改数值且两组不同时返回 422，
 * 不拼成无人提交过的组合。其余字段按原文比较。
 *
 * <p>显式冲突解决（RESOLVE）在同一套占位与行锁机制内执行：事务内重读基线与最新当前版本重算冲突，
 * 人工选择必须恰好覆盖重算后的冲突字段；成功后原子写入新观测版本（内容无变化则不加版本）、
 * 不可变 conflict_resolution 记录与 request_log 响应。resolutionId 全局唯一，同参重放、改参 409。
 *
 * <p>幂等身份为结构化 JSON（操作、操作者、资源及全部参数和期望版本）的 SHA-256，
 * 不按分隔符拼接，避免分隔符碰撞。
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
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ObservationService(ObservationRepository observationRepository,
                              RequestLogRepository requestLogRepository,
                              ResolutionRepository resolutionRepository,
                              ObjectMapper objectMapper,
                              Clock clock) {
        this.observationRepository = observationRepository;
        this.requestLogRepository = requestLogRepository;
        this.resolutionRepository = resolutionRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 创建观测记录，初始版本为 1。记录已存在返回 409。
     */
    @Transactional
    public WriteOutcome create(CreateObservationRequest request) {
        String qualifier = requireQualifier(request.readingQualifier());
        String fingerprint = fingerprint("CREATE", params(
                "observationId", request.observationId(),
                "location", request.location(),
                "reading", request.reading(),
                "readingQualifier", qualifier,
                "note", request.note()));
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
                request.location(), request.reading(), qualifier, request.note(), false);
        try {
            observationRepository.insertCurrent(snapshot);
        } catch (DuplicateKeyException e) {
            // 并发创建同一 observationId：由主键串行化，后到者按冲突处理
            throw ApiException.conflict("observation already exists: " + request.observationId(), null);
        }
        observationRepository.insertVersion(snapshot);
        return complete(request.requestId(), HttpStatus.CREATED, ObservationResponse.of(snapshot));
    }

    /**
     * 离线提交：基于 baseVersion 的三方合并。合并结果与当前完全相同则不加版本，否则版本加一。
     * 读数按（限定符+数值）整组比较；限定符与数值被拆分变更且两组不同时返回 422。
     */
    @Transactional
    public WriteOutcome merge(String observationId, MergeObservationRequest request) {
        String qualifier = requireQualifier(request.readingQualifier());
        String fingerprint = fingerprint("MERGE", params(
                "observationId", observationId,
                "baseVersion", String.valueOf(request.baseVersion()),
                "location", request.location(),
                "reading", request.reading(),
                "readingQualifier", qualifier,
                "note", request.note()));
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
        ObservationSnapshot base = observationRepository.findVersion(observationId, request.baseVersion())
                .orElseThrow(() -> ApiException.notFound(
                        "base version not found: " + observationId + "@" + request.baseVersion()));

        List<String> conflictFields = new ArrayList<>();
        String mergedLocation = mergeField("location", base.location(), current.location(),
                request.location(), conflictFields);
        ReadingGroup mergedReading = mergeReadingGroup(readingGroupOf(base), readingGroupOf(current),
                new ReadingGroup(qualifier, request.reading()), conflictFields, current.version());
        String mergedNote = mergeField("note", base.note(), current.note(), request.note(), conflictFields);
        if (!conflictFields.isEmpty()) {
            throw ApiException.mergeConflict(conflictFields, current.version());
        }

        ObservationSnapshot merged = new ObservationSnapshot(observationId, current.version(),
                mergedLocation, mergedReading.value(), mergedReading.qualifier(), mergedNote, false);
        if (sameContent(merged, current)) {
            // 合并结果与当前完全相同：返回当前版本，不加版本
            return complete(request.requestId(), HttpStatus.OK, ObservationResponse.of(current));
        }
        ObservationSnapshot next = new ObservationSnapshot(observationId, current.version() + 1,
                mergedLocation, mergedReading.value(), mergedReading.qualifier(), mergedNote, false);
        observationRepository.updateCurrent(next);
        observationRepository.insertVersion(next);
        return complete(request.requestId(), HttpStatus.OK, ObservationResponse.of(next));
    }

    /**
     * 删除观测记录：expectedVersion 匹配当前版本时生成新版本墓碑（不含读数等任何业务字段）；
     * 已删除记录返回 410。
     */
    @Transactional
    public WriteOutcome delete(String observationId, DeleteObservationRequest request) {
        String fingerprint = fingerprint("DELETE", params(
                "observationId", observationId,
                "expectedVersion", String.valueOf(request.expectedVersion())));
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
                null, null, null, null, true);
        observationRepository.markDeleted(observationId, tombstone.version());
        observationRepository.insertVersion(tombstone);
        return complete(request.requestId(), HttpStatus.OK, ObservationResponse.of(tombstone));
    }
    /**
     * 查询当前内容（含读数限定符与读数值）；墓碑只返回删除状态和版本。
     */
    @Transactional(readOnly = true)
    public ObservationResponse getCurrent(String observationId) {
        ObservationSnapshot current = observationRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        return ObservationResponse.of(current);
    }

    /**
     * 显式冲突解决：事务内重读基线与最新当前版本并按原三方规则重算冲突，不信任客户端上次看到的冲突列表。
     * 读数限定符与数值作为不可拆分的一组：人工选择必须选择完整一侧，或以候选形式提交完整新组。
     * 选择恰好覆盖本次仍冲突字段时生成新版本（内容无变化则不加版本）并原子写入不可变解决记录。
     */
    @Transactional
    public ResolveOutcome resolveConflict(String observationId, ResolveConflictRequest request) {
        String qualifier = requireQualifier(request.readingQualifier());
        Map<String, FieldSelection> selections = parseSelections(request.selections());
        String normalizedSelections = writeSelectionsJson(selections);
        String fingerprint = fingerprint("RESOLVE", params(
                "observationId", observationId,
                "resolutionId", request.resolutionId(),
                "baseVersion", String.valueOf(request.baseVersion()),
                "expectedCurrentVersion", String.valueOf(request.expectedCurrentVersion()),
                "location", request.location(),
                "reading", request.reading(),
                "readingQualifier", qualifier,
                "note", request.note(),
                "selections", normalizedSelections,
                "operator", request.operator()));
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
            if (!sameResolutionParams(existing, observationId, request, qualifier, normalizedSelections)) {
                throw ApiException.conflict(
                        "resolutionId reused with different parameters: " + request.resolutionId(),
                        current.version());
            }
            return completeResolve(request.requestId(), HttpStatus.OK, resolutionResponseOf(existing));
        }

        if (current.deleted()) {
            throw ApiException.gone("observation already deleted: " + observationId);
        }

        if (request.expectedCurrentVersion() != current.version()) {
            throw ApiException.conflict("expectedCurrentVersion mismatch", current.version());
        }
        ObservationSnapshot base = observationRepository.findVersion(observationId, request.baseVersion())
                .orElseThrow(() -> ApiException.notFound(
                        "base version not found: " + observationId + "@" + request.baseVersion()));

        ReadingGroup baseReading = readingGroupOf(base);
        ReadingGroup currentReading = readingGroupOf(current);
        ReadingGroup candidateReading = new ReadingGroup(qualifier, request.reading());

        List<String> conflictFields = new ArrayList<>();
        boolean locationConflict = fieldConflicts(base.location(), current.location(), request.location());
        if (locationConflict) {
            conflictFields.add("location");
        }
        boolean readingConflict = readingGroupConflicts(baseReading, currentReading, candidateReading);
        if (readingConflict) {
            conflictFields.add("reading");
        }
        boolean noteConflict = fieldConflicts(base.note(), current.note(), request.note());
        if (noteConflict) {
            conflictFields.add("note");
        }
        validateSelectionCoverage(selections.keySet(), conflictFields);

        String resolvedLocation = resolveFieldValue(locationConflict, selections.get("location"),
                base.location(), current.location(), request.location());
        ReadingGroup resolvedReading = resolveReadingGroup(readingConflict, selections.get("reading"),
                baseReading, currentReading, candidateReading);
        String resolvedNote = resolveFieldValue(noteConflict, selections.get("note"),
                base.note(), current.note(), request.note());

        ObservationSnapshot merged = new ObservationSnapshot(observationId, current.version(),
                resolvedLocation, resolvedReading.value(), resolvedReading.qualifier(), resolvedNote, false);
        boolean contentChanged = !sameContent(merged, current);
        int newVersion = contentChanged ? current.version() + 1 : current.version();
        if (contentChanged) {
            ObservationSnapshot next = new ObservationSnapshot(observationId, newVersion,
                    resolvedLocation, resolvedReading.value(), resolvedReading.qualifier(), resolvedNote, false);
            observationRepository.updateCurrent(next);
            observationRepository.insertVersion(next);
        }

        ResolutionRecord record = new ResolutionRecord(
                request.resolutionId(), observationId, request.requestId(),
                request.baseVersion(), current.version(), newVersion,
                request.location(), request.reading(), qualifier, request.note(),
                List.copyOf(conflictFields), normalizedSelections, request.operator(),
                Instant.now(clock), contentChanged);
        try {
            resolutionRepository.insert(record);
        } catch (DuplicateKeyException e) {
            // 并发下 resolutionId 已被其他事务占用（可能指向其他观测记录）：重读后同参重放，否则 409。
            ResolutionRecord winner = resolutionRepository.findByResolutionId(request.resolutionId())
                    .orElseThrow(() -> ApiException.conflict(
                            "resolutionId conflict: " + request.resolutionId(), current.version()));
            if (!sameResolutionParams(winner, observationId, request, qualifier, normalizedSelections)) {
                throw ApiException.conflict(
                        "resolutionId reused with different parameters: " + request.resolutionId(),
                        current.version());
            }
            return completeResolve(request.requestId(), HttpStatus.OK, resolutionResponseOf(winner));
        }

        ObservationSnapshot pointed = contentChanged
                ? new ObservationSnapshot(observationId, newVersion, resolvedLocation,
                        resolvedReading.value(), resolvedReading.qualifier(), resolvedNote, false)
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
     * 查询指定历史版本（含读数限定符与读数值）；墓碑版本只返回删除状态和版本。
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
     * 读数组三方合并：限定符与数值作为不可拆分的一组比较。
     * 一侧仅改限定符、另一侧仅改数值且两组不同时返回 422，不拼成无人提交过的组合；
     * 其余整组不一致按普通字段冲突处理（409，字段名 reading）。
     */
    private ReadingGroup mergeReadingGroup(ReadingGroup base, ReadingGroup current, ReadingGroup candidate,
                                           List<String> conflictFields, int currentVersion) {
        if (candidate.sameAs(base)) {
            return current;
        }
        if (current.sameAs(base) || candidate.sameAs(current)) {
            return candidate;
        }
        if (isSplitQualifierValueChange(base, current, candidate)) {
            throw ApiException.readingGroupConflict(currentVersion);
        }
        conflictFields.add("reading");
        return current;
    }

    /**
     * 拆分变更识别：一侧仅改限定符（数值与基线相同）、另一侧仅改数值（限定符与基线相同）。
     */
    private boolean isSplitQualifierValueChange(ReadingGroup base, ReadingGroup current, ReadingGroup candidate) {
        return splitsQualifierOnly(base, current) && splitsValueOnly(base, candidate)
                || splitsQualifierOnly(base, candidate) && splitsValueOnly(base, current);
    }

    private boolean splitsQualifierOnly(ReadingGroup base, ReadingGroup side) {
        return !side.qualifier().equals(base.qualifier())
                && ReadingQualifiers.valueEquals(side.value(), base.value());
    }

    private boolean splitsValueOnly(ReadingGroup base, ReadingGroup side) {
        return side.qualifier().equals(base.qualifier())
                && !ReadingQualifiers.valueEquals(side.value(), base.value());
    }

    /**
     * 判断合并结果与当前版本内容是否完全相同（读数按整组数值比较，其余按原文），
     * 不因浮点或字符串格式差异制造版本变化。
     */
    private boolean sameContent(ObservationSnapshot merged, ObservationSnapshot current) {
        return Objects.equals(merged.location(), current.location())
                && Objects.equals(merged.note(), current.note())
                && readingGroupOf(merged).sameAs(readingGroupOf(current));
    }

    /**
     * 由快照构造读数组；墓碑版本读数为 null，整组为 null 组。
     */
    private ReadingGroup readingGroupOf(ObservationSnapshot snapshot) {
        return new ReadingGroup(snapshot.readingQualifier(), snapshot.reading());
    }

    /**
     * 规范化并校验请求中的读数限定符：缺省为 EXACT；非法值返回 400。
     */
    private String requireQualifier(String raw) {
        String qualifier = ReadingQualifiers.normalize(raw);
        if (qualifier == null) {
            throw ApiException.badRequest("readingQualifier must be one of EXACT/LT/GT: " + raw);
        }
        return qualifier;
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
     * 构造有序参数映射（键固定顺序），供结构化指纹使用；null 值显式保留，避免参数错位。
     */
    private Map<String, String> params(String... keyValues) {
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            params.put(keyValues[i], keyValues[i + 1]);
        }
        return params;
    }

    /**
     * 结构化幂等指纹：操作名与全部参数（含操作者、资源、期望版本）序列化为 JSON 后取 SHA-256，
     * 不按分隔符拼接，避免分隔符碰撞。
     */
    private String fingerprint(String operation, Map<String, String> params) {
        Map<String, String> identity = new LinkedHashMap<>();
        identity.put("operation", operation);
        identity.putAll(params);
        try {
            String json = objectMapper.writeValueAsString(identity);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize request identity", e);
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
     * 按原三方规则判断普通字段是否冲突：候选未改或任一侧未改或两边改为相同值均不冲突，否则冲突。
     */
    private boolean fieldConflicts(String base, String current, String candidate) {
        if (Objects.equals(candidate, base)) {
            return false;
        }
        return !Objects.equals(current, base) && !Objects.equals(candidate, current);
    }

    /**
     * 按原三方规则判断读数组是否冲突：限定符与数值作为不可拆分的一组整体比较。
     */
    private boolean readingGroupConflicts(ReadingGroup base, ReadingGroup current, ReadingGroup candidate) {
        if (candidate.sameAs(base)) {
            return false;
        }
        return !current.sameAs(base) && !candidate.sameAs(current);
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
     * 计算普通字段解决结果：冲突字段按人工选择取当前或候选；非冲突字段继续按原三方规则自动合并。
     */
    private String resolveFieldValue(boolean conflict, FieldSelection selection,
                                     String base, String current, String candidate) {
        if (conflict) {
            return selection == FieldSelection.CANDIDATE ? candidate : current;
        }
        if (Objects.equals(candidate, base)) {
            return current;
        }
        return candidate;
    }

    /**
     * 计算读数组解决结果：冲突时按人工选择取完整一侧（限定符与数值整组），
     * 不拼接两侧；非冲突时按原三方规则自动合并整组。
     */
    private ReadingGroup resolveReadingGroup(boolean conflict, FieldSelection selection,
                                             ReadingGroup base, ReadingGroup current, ReadingGroup candidate) {
        if (conflict) {
            return selection == FieldSelection.CANDIDATE ? candidate : current;
        }
        if (candidate.sameAs(base)) {
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
     * expectedCurrentVersion 必须等于记录中的解决前当前版本：任何参数（含乐观锁版本与读数限定符）
     * 变化均按改参 409 处理。
     */
    private boolean sameResolutionParams(ResolutionRecord record, String observationId,
                                         ResolveConflictRequest request, String qualifier,
                                         String normalizedSelections) {
        return Objects.equals(record.observationId(), observationId)
                && record.baseVersion() == request.baseVersion()
                && record.previousVersion() == request.expectedCurrentVersion()
                && Objects.equals(record.candidateLocation(), request.location())
                && Objects.equals(record.candidateReading(), request.reading())
                && Objects.equals(ReadingQualifiers.orDefault(record.candidateReadingQualifier()), qualifier)
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
     * 读数不可拆分的一组：限定符与数值。等价判定为数值相等（按 BigDecimal）且限定符相同，
     * 与字符串书写格式无关（5.0 与 5.000 等价，EXACT 5 与 LT 5 不同）。
     */
    private record ReadingGroup(String qualifier, String value) {

        private boolean sameAs(ReadingGroup other) {
            return ReadingQualifiers.groupEquals(qualifier, value, other.qualifier, other.value);
        }
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