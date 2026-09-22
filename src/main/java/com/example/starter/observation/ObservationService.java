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
import java.util.TreeMap;

/**
 * 现场观测离线三方合并业务服务。
 *
 * <p>写操作（创建/离线提交/冲突解决/删除）均在单事务内完成：先占位写入幂等去重记录，
 * 再对 observation_current 行加锁（SELECT ... FOR UPDATE）完成业务判定与变更，
 * 最后回填去重记录的响应并整体提交；任何业务失败都会回滚，去重记录不占键。
 *
 * <p>合并规则（按字段比较基线 base、当前 current、候选 candidate）：
 * 候选未改（等于基线）保留当前；当前未改接受候选；两边改为相同值也接受；
 * 否则整次 409，不写入任何部分结果。读数按数值比较，其余字段按原文比较。
 *
 * <p>冲突解决：不信任客户端上次看到的冲突列表，事务内重读基线与最新当前版本重算冲突，
 * 人工选择必须恰好覆盖仍冲突的字段；解决结果与不可变解决记录（observation_resolution）
 * 同事务原子提交，解决结果与当前完全相同则不增加观测版本。
 */
@Service
public class ObservationService {

    private static final String SEPARATOR = "\u0001";

    private static final Set<String> MERGEABLE_FIELDS = Set.of("location", "reading", "note");

    private static final String CHOICE_CURRENT = "CURRENT";
    private static final String CHOICE_CANDIDATE = "CANDIDATE";

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
    public WriteOutcome<ObservationResponse> create(CreateObservationRequest request) {
        String fingerprint = fingerprint("CREATE", request.observationId(), request.location(),
                request.reading(), request.note());
        WriteOutcome<ObservationResponse> replayed =
                checkReplay(request.requestId(), fingerprint, ObservationResponse.class);
        if (replayed != null) {
            return replayed;
        }
        WriteOutcome<ObservationResponse> concurrent =
                insertPlaceholder(request.requestId(), fingerprint, "CREATE", ObservationResponse.class);
        if (concurrent != null) {
            return concurrent;
        }

        if (observationRepository.findCurrentForUpdate(request.observationId()).isPresent()) {
            throw ApiException.conflict("observation already exists: " + request.observationId(), null);
        }
        ObservationSnapshot snapshot = new ObservationSnapshot(request.observationId(), 1,
                request.location(), request.reading(), request.note(), false);
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
     */
    @Transactional
    public WriteOutcome<ObservationResponse> merge(String observationId, MergeObservationRequest request) {
        String fingerprint = fingerprint("MERGE", observationId, String.valueOf(request.baseVersion()),
                request.location(), request.reading(), request.note());
        WriteOutcome<ObservationResponse> replayed =
                checkReplay(request.requestId(), fingerprint, ObservationResponse.class);
        if (replayed != null) {
            return replayed;
        }
        WriteOutcome<ObservationResponse> concurrent =
                insertPlaceholder(request.requestId(), fingerprint, "MERGE", ObservationResponse.class);
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
        String mergedReading = mergeReadingField(base.reading(), current.reading(),
                request.reading(), conflictFields);
        String mergedNote = mergeField("note", base.note(), current.note(), request.note(), conflictFields);
        if (!conflictFields.isEmpty()) {
            throw ApiException.mergeConflict(conflictFields, current.version());
        }

        ObservationSnapshot merged = new ObservationSnapshot(observationId, current.version(),
                mergedLocation, mergedReading, mergedNote, false);
        if (sameContent(merged, current)) {
            // 合并结果与当前完全相同：返回当前版本，不加版本
            return complete(request.requestId(), HttpStatus.OK, ObservationResponse.of(current));
        }
        ObservationSnapshot next = new ObservationSnapshot(observationId, current.version() + 1,
                mergedLocation, mergedReading, mergedNote, false);
        observationRepository.updateCurrent(next);
        observationRepository.insertVersion(next);
        return complete(request.requestId(), HttpStatus.OK, ObservationResponse.of(next));
    }

    /**
     * 删除观测记录：expectedVersion 匹配当前版本时生成新版本墓碑；已删除记录返回 410。
     */
    @Transactional
    public WriteOutcome<ObservationResponse> delete(String observationId, DeleteObservationRequest request) {
        String fingerprint = fingerprint("DELETE", observationId, String.valueOf(request.expectedVersion()));
        WriteOutcome<ObservationResponse> replayed =
                checkReplay(request.requestId(), fingerprint, ObservationResponse.class);
        if (replayed != null) {
            return replayed;
        }
        WriteOutcome<ObservationResponse> concurrent =
                insertPlaceholder(request.requestId(), fingerprint, "DELETE", ObservationResponse.class);
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
     * 冲突解决提交：在事务内重新读取基线与最新当前版本，按原三方规则重算冲突，
     * 校验人工选择恰好覆盖仍冲突的字段后应用；非冲突字段按原规则自动合并。
     * 解决结果与当前完全相同则不增加观测版本，但仍保存指向当前版本的不可变解决记录。
     * resolutionId 全局唯一：同参重放返回原结果，异参 409。
     */
    @Transactional
    public WriteOutcome<ResolutionResponse> resolve(String observationId, ResolveObservationRequest request) {
        String canonicalSelections = canonicalSelections(request.selections());
        String fingerprint = fingerprint("RESOLVE", observationId, request.resolutionId(),
                String.valueOf(request.baseVersion()), String.valueOf(request.expectedCurrentVersion()),
                request.location(), request.reading(), request.note(), canonicalSelections, request.operator());
        WriteOutcome<ResolutionResponse> replayed =
                checkReplay(request.requestId(), fingerprint, ResolutionResponse.class);
        if (replayed != null) {
            return replayed;
        }
        WriteOutcome<ResolutionResponse> concurrent =
                insertPlaceholder(request.requestId(), fingerprint, "RESOLVE", ResolutionResponse.class);
        if (concurrent != null) {
            return concurrent;
        }

        ObservationSnapshot current = observationRepository.findCurrentForUpdate(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        if (current.deleted()) {
            throw ApiException.gone("observation already deleted: " + observationId);
        }

        // resolutionId 全局唯一：同一观测记录下同参重放返回原结果，异参 409
        String resolutionFingerprint = fingerprint("RESOLUTION", observationId,
                String.valueOf(request.baseVersion()), String.valueOf(request.expectedCurrentVersion()),
                request.location(), request.reading(), request.note(), canonicalSelections, request.operator());
        Optional<ResolutionRecord> existing = resolutionRepository.find(request.resolutionId());
        if (existing.isPresent()) {
            ResolutionRecord committed = existing.get();
            if (!committed.fingerprint().equals(resolutionFingerprint)) {
                throw ApiException.conflict(
                        "resolutionId reused with different parameters: " + request.resolutionId(), null);
            }
            return complete(request.requestId(), HttpStatus.OK, toResponse(committed));
        }

        ObservationSnapshot base = observationRepository.findVersion(observationId, request.baseVersion())
                .orElseThrow(() -> ApiException.notFound(
                        "base version not found: " + observationId + "@" + request.baseVersion()));
        if (request.expectedCurrentVersion() != current.version()) {
            throw ApiException.conflict("expectedCurrentVersion mismatch", current.version());
        }

        // 不信任客户端上次看到的冲突列表：按当前基线与最新当前版本重算冲突
        List<String> conflictFields = new ArrayList<>();
        String mergedLocation = mergeField("location", base.location(), current.location(),
                request.location(), conflictFields);
        String mergedReading = mergeReadingField(base.reading(), current.reading(),
                request.reading(), conflictFields);
        String mergedNote = mergeField("note", base.note(), current.note(), request.note(), conflictFields);

        validateSelections(request.selections(), conflictFields);

        String finalLocation = applySelection("location", request.selections(),
                mergedLocation, current.location(), request.location());
        String finalReading = applySelection("reading", request.selections(),
                mergedReading, current.reading(), request.reading());
        String finalNote = applySelection("note", request.selections(),
                mergedNote, current.note(), request.note());

        ObservationSnapshot resolved = new ObservationSnapshot(observationId, current.version(),
                finalLocation, finalReading, finalNote, false);
        boolean versionCreated = !sameContent(resolved, current);
        int resultVersion = versionCreated ? current.version() + 1 : current.version();
        if (versionCreated) {
            ObservationSnapshot next = new ObservationSnapshot(observationId, resultVersion,
                    finalLocation, finalReading, finalNote, false);
            observationRepository.updateCurrent(next);
            observationRepository.insertVersion(next);
        }

        ResolutionRecord record = new ResolutionRecord(request.resolutionId(), observationId,
                request.baseVersion(), current.version(), resultVersion, versionCreated,
                request.location(), request.reading(), request.note(),
                String.join(",", conflictFields), selectionsJson(request.selections()),
                resolutionFingerprint, request.operator(), Instant.now(clock).toString());
        try {
            resolutionRepository.insert(record);
        } catch (DuplicateKeyException e) {
            // 并发同 resolutionId（不同观测记录不走同一行锁）：读取已提交记录，同参重放、异参 409
            ResolutionRecord committed = resolutionRepository.find(request.resolutionId())
                    .orElseThrow(() -> ApiException.conflict(
                            "resolutionId conflict: " + request.resolutionId(), null));
            if (!committed.fingerprint().equals(resolutionFingerprint)) {
                throw ApiException.conflict(
                        "resolutionId reused with different parameters: " + request.resolutionId(), null);
            }
            return complete(request.requestId(), HttpStatus.OK, toResponse(committed));
        }
        return complete(request.requestId(), HttpStatus.OK, toResponse(record));
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
     * 按解决标识查询解决记录；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public ResolutionResponse getResolution(String resolutionId) {
        return toResponse(resolutionRepository.find(resolutionId)
                .orElseThrow(() -> ApiException.notFound("resolution not found: " + resolutionId)));
    }

    /**
     * 按观测记录查询解决历史，按解决时刻升序。
     */
    @Transactional(readOnly = true)
    public List<ResolutionResponse> listResolutions(String observationId) {
        return resolutionRepository.findByObservationId(observationId).stream()
                .map(this::toResponse)
                .toList();
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
     * 读数字段三方合并：按数值比较（BigDecimal），其余规则与普通字段一致。
     */
    private String mergeReadingField(String base, String current, String candidate,
                                     List<String> conflictFields) {
        if (readingEquals(candidate, base)) {
            return current;
        }
        if (readingEquals(current, base) || readingEquals(candidate, current)) {
            return candidate;
        }
        conflictFields.add("reading");
        return current;
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
     * 校验人工选择：必须恰好覆盖本次重算后仍冲突的字段，
     * 不得遗漏、多选非冲突字段或选择 CURRENT/CANDIDATE 以外的值（如 BASE）。
     */
    private void validateSelections(Map<String, String> selections, List<String> conflictFields) {
        for (Map.Entry<String, String> entry : selections.entrySet()) {
            String field = entry.getKey();
            String choice = entry.getValue();
            if (!MERGEABLE_FIELDS.contains(field)) {
                throw ApiException.badRequest("unknown selection field: " + field);
            }
            if (!CHOICE_CURRENT.equals(choice) && !CHOICE_CANDIDATE.equals(choice)) {
                throw ApiException.badRequest(
                        "selection for field " + field + " must be CURRENT or CANDIDATE");
            }
            if (!conflictFields.contains(field)) {
                throw ApiException.badRequest("field not in conflict: " + field);
            }
        }
        for (String field : conflictFields) {
            if (!selections.containsKey(field)) {
                throw ApiException.badRequest("missing selection for conflicted field: " + field);
            }
        }
    }

    /**
     * 应用人工选择：冲突字段按选择取当前值或候选值；非冲突字段保留自动合并结果。
     */
    private String applySelection(String field, Map<String, String> selections,
                                  String mergedValue, String currentValue, String candidateValue) {
        String choice = selections.get(field);
        if (choice == null) {
            return mergedValue;
        }
        return CHOICE_CURRENT.equals(choice) ? currentValue : candidateValue;
    }

    /**
     * 将选择映射规范化为确定顺序的文本，用于指纹计算（与客户端提交顺序无关）。
     */
    private String canonicalSelections(Map<String, String> selections) {
        StringBuilder canonical = new StringBuilder();
        new TreeMap<>(selections).forEach((field, choice) ->
                canonical.append(field).append('=').append(choice).append(';'));
        return canonical.toString();
    }

    /**
     * 将选择映射序列化为确定顺序的 JSON，存入解决记录。
     */
    private String selectionsJson(Map<String, String> selections) {
        try {
            return objectMapper.writeValueAsString(new TreeMap<>(selections));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize selections", e);
        }
    }

    /**
     * 由解决记录构造响应视图；冲突字段与选择从存储文本还原。
     */
    private ResolutionResponse toResponse(ResolutionRecord record) {
        List<String> conflictFields = record.conflictFields().isEmpty()
                ? List.of()
                : List.of(record.conflictFields().split(","));
        Map<String, String> selections;
        try {
            selections = objectMapper.readValue(record.selectionsJson(),
                    objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, String.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored selections", e);
        }
        return new ResolutionResponse(record.resolutionId(), record.observationId(), record.baseVersion(),
                record.previousVersion(), record.resultVersion(), record.versionCreated(),
                conflictFields, selections, record.operator(), record.resolvedAt());
    }

    /**
     * 幂等检查：同键同参返回原成功结果；同键异参返回 409；无记录返回 null 继续执行。
     */
    private <T> WriteOutcome<T> checkReplay(String requestId, String fingerprint, Class<T> bodyType) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new WriteOutcome<>(entry.responseStatus(), readBody(entry.responseBody(), bodyType));
    }

    /**
     * 占位写入去重记录；并发同键时主键冲突，等待对方事务结束后读取已提交结果：
     * 同参返回重放结果，异参抛 409；正常占位返回 null。业务失败时占位随事务回滚，不占键。
     */
    private <T> WriteOutcome<T> insertPlaceholder(String requestId, String fingerprint, String operation,
                                                  Class<T> bodyType) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, operation);
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return new WriteOutcome<>(entry.responseStatus(), readBody(entry.responseBody(), bodyType));
        }
    }

    /**
     * 业务成功后回填去重记录响应，并构造本次写操作结果；与业务变更同事务提交。
     */
    private <T> WriteOutcome<T> complete(String requestId, HttpStatus status, T body) {
        requestLogRepository.complete(requestId, status.value(), writeBody(body));
        return new WriteOutcome<>(status.value(), body);
    }

    private String fingerprint(String operation, String... parts) {
        StringBuilder raw = new StringBuilder(operation);
        for (String part : parts) {
            raw.append(SEPARATOR).append(part == null ? "<null>" : part);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String writeBody(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize response", e);
        }
    }

    private <T> T readBody(String json, Class<T> bodyType) {
        try {
            return objectMapper.readValue(json, bodyType);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored response", e);
        }
    }

    /**
     * 写操作结果：HTTP 状态码与响应体。
     */
    public record WriteOutcome<T>(int status, T body) {
    }
}
