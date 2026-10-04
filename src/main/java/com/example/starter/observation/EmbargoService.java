package com.example.starter.observation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 观测禁运与到期披露业务服务。
 *
 * <p>批量禁运设置：先对全部簇做完整最终状态校验（存在性、墓碑、重复设置、截止时刻不早于簇创建、
 * 披露范围非空、无未解决冲突），任一非法则整次回滚并返回逐簇错误明细；全部通过后在一个事务内写入。
 * 禁运记录不可变，重复设置按 409 处理。
 *
 * <p>到期发布：禁运截止（左闭右开区间终点）后首次发布生成不可变披露快照，包含当时胜出版本、
 * 全部冲突裁决与质量标记；同簇并发发布由主键串行化，只生成一个快照，后到者读取既有快照返回。
 *
 * <p>幂等：requestId 同参重放返回首次完整响应快照，异参 409，失败不占键；
 * 指纹包含操作者、操作类型、规范化对象集合及全部影响结果的字段，集合经 JSON 转义序列化。
 */
@Service
public class EmbargoService {

    private static final String SEPARATOR = "\u0001";

    private static final TypeReference<List<ResolutionSummary>> RESOLUTION_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, String>> SELECTIONS_TYPE = new TypeReference<>() {
    };

    private final ObservationRepository observationRepository;
    private final EmbargoRepository embargoRepository;
    private final DisclosureRepository disclosureRepository;
    private final OpenConflictRepository openConflictRepository;
    private final ResolutionRepository resolutionRepository;
    private final RequestLogRepository requestLogRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public EmbargoService(ObservationRepository observationRepository,
                          EmbargoRepository embargoRepository,
                          DisclosureRepository disclosureRepository,
                          OpenConflictRepository openConflictRepository,
                          ResolutionRepository resolutionRepository,
                          RequestLogRepository requestLogRepository,
                          ObjectMapper objectMapper,
                          Clock clock) {
        this.observationRepository = observationRepository;
        this.embargoRepository = embargoRepository;
        this.disclosureRepository = disclosureRepository;
        this.openConflictRepository = openConflictRepository;
        this.resolutionRepository = resolutionRepository;
        this.requestLogRepository = requestLogRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 批量设置禁运：先完整校验全部簇，任一非法整次回滚；全部通过后单事务提交。成功返回 201。
     */
    @Transactional
    public EmbargoBatchOutcome setEmbargoes(SetEmbargoBatchRequest request) {
        List<NormalizedItem> items = normalizeItems(request.items());
        String fingerprint = fingerprint("EMBARGO_SET", request.operator(), writeJson(items));
        EmbargoBatchOutcome replayed = checkBatchReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        EmbargoBatchOutcome concurrent = insertBatchPlaceholder(request.requestId(), fingerprint);
        if (concurrent != null) {
            return concurrent;
        }

        List<ErrorDetail> errors = new ArrayList<>();
        Map<String, ObservationSnapshot> currents = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        for (NormalizedItem item : items) {
            if (!seen.add(item.observationId())) {
                errors.add(new ErrorDetail(item.observationId(), "DUPLICATE_OBSERVATION",
                        "duplicate observationId in batch: " + item.observationId(), null, null, null));
                continue;
            }
            Optional<ObservationSnapshot> current =
                    observationRepository.findCurrentForUpdate(item.observationId());
            if (current.isEmpty()) {
                errors.add(new ErrorDetail(item.observationId(), "OBSERVATION_NOT_FOUND",
                        "observation not found: " + item.observationId(), null, null, null));
                continue;
            }
            currents.put(item.observationId(), current.get());
            if (current.get().deleted()) {
                errors.add(new ErrorDetail(item.observationId(), "OBSERVATION_DELETED",
                        "observation already deleted: " + item.observationId(), null, null, null));
                continue;
            }
            if (embargoRepository.find(item.observationId()).isPresent()) {
                errors.add(new ErrorDetail(item.observationId(), "EMBARGO_ALREADY_SET",
                        "embargo already set and immutable: " + item.observationId(), null, null, null));
                continue;
            }
            Instant createdAtUtc = observationRepository.findCreatedAt(item.observationId())
                    .orElseThrow(() -> new IllegalStateException(
                            "creation version missing: " + item.observationId()));
            if (item.embargoUntilUtc().isBefore(createdAtUtc)) {
                errors.add(new ErrorDetail(item.observationId(), "EMBARGO_BEFORE_CREATION",
                        "embargo deadline is earlier than cluster creation",
                        item.embargoUntilUtc().toString(), createdAtUtc.toString(),
                        Duration.between(createdAtUtc, item.embargoUntilUtc()).getSeconds()));
                continue;
            }
            if (item.disclosureScope().isEmpty()) {
                errors.add(new ErrorDetail(item.observationId(), "EMPTY_DISCLOSURE_SCOPE",
                        "disclosure scope must not be empty", "0", "1", -1L));
                continue;
            }
            Optional<OpenConflictRecord> openConflict = openConflictRepository.find(item.observationId());
            if (openConflict.isPresent()) {
                errors.add(new ErrorDetail(item.observationId(), "UNRESOLVED_CONFLICT",
                        "cluster has unresolved conflicts on fields: "
                                + String.join(", ", openConflict.get().conflictFields()),
                        String.valueOf(openConflict.get().conflictFields().size()), "0",
                        (long) openConflict.get().conflictFields().size()));
            }
        }
        if (!errors.isEmpty()) {
            boolean alreadySet = errors.stream().anyMatch(d -> "EMBARGO_ALREADY_SET".equals(d.reason()));
            String message = "embargo batch rejected: " + errors.size() + " invalid item(s) out of "
                    + items.size();
            if (alreadySet) {
                throw ApiException.conflictWithDetails(message, errors);
            }
            throw ApiException.unprocessable(message, errors);
        }

        Instant setAtUtc = Instant.now(clock);
        List<EmbargoItemResponse> responses = new ArrayList<>();
        for (NormalizedItem item : items) {
            EmbargoRecord record = new EmbargoRecord(item.observationId(), item.embargoUntilUtc(),
                    writeJson(item.disclosureScope()), request.operator(),
                    currents.get(item.observationId()).version(), request.requestId(), setAtUtc);
            embargoRepository.insert(record);
            responses.add(itemResponseOf(record));
        }
        EmbargoBatchResponse body = new EmbargoBatchResponse(request.requestId(), request.operator(),
                items.size(), responses.size(), List.copyOf(responses));
        return completeBatch(request.requestId(), HttpStatus.CREATED, body);
    }    /**
     * 到期发布：禁运截止后首次发布生成不可变披露快照（201）；已发布时返回既有快照（200）。
     * 未设置禁运返回 404；未到截止时刻返回 422 并给出当前时刻、截止时刻与剩余秒数。
     */
    @Transactional
    public ReleaseOutcome release(String observationId, ReleaseDisclosureRequest request) {
        String fingerprint = fingerprint("RELEASE", observationId, request.operator());
        ReleaseOutcome replayed = checkReleaseReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        ReleaseOutcome concurrent = insertReleasePlaceholder(request.requestId(), fingerprint);
        if (concurrent != null) {
            return concurrent;
        }

        EmbargoRecord embargo = embargoRepository.find(observationId)
                .orElseThrow(() -> ApiException.notFound("embargo not found: " + observationId));
        // 与更正（合并/裁决/删除）按提交顺序串行化：快照取提交时刻的胜出内容
        ObservationSnapshot current = observationRepository.findCurrentForUpdate(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));

        Optional<DisclosureSnapshotRecord> existing = disclosureRepository.findSnapshot(observationId);
        if (existing.isPresent()) {
            return completeRelease(request.requestId(), HttpStatus.OK,
                    disclosureResponseOf(existing.get()));
        }

        Instant now = Instant.now(clock);
        if (now.isBefore(embargo.embargoUntilUtc())) {
            throw ApiException.unprocessable(
                    "embargo still active until " + embargo.embargoUntilUtc(),
                    List.of(new ErrorDetail(observationId, "EMBARGO_STILL_ACTIVE",
                            "embargo deadline not reached; release is allowed at or after the deadline",
                            now.toString(), embargo.embargoUntilUtc().toString(),
                            Duration.between(now, embargo.embargoUntilUtc()).getSeconds())));
        }

        List<ResolutionRecord> resolutions = resolutionRepository.findByObservationId(observationId);
        List<String> qualityFlags = qualityFlags(embargo, current, resolutions);
        DisclosureSnapshotRecord snapshot = new DisclosureSnapshotRecord(
                observationId, current.version(),
                current.deleted() ? null : current.location(),
                current.deleted() ? null : current.reading(),
                current.deleted() ? null : current.note(),
                current.deleted(),
                writeJson(resolutionSummaries(resolutions)),
                writeJson(qualityFlags),
                request.operator(), request.requestId(), now);
        try {
            disclosureRepository.insertSnapshot(snapshot);
        } catch (DuplicateKeyException e) {
            // 同簇并发到期发布：主键串行化，只保留先到者的快照，后到者读取既有快照返回
            DisclosureSnapshotRecord winner = disclosureRepository.findSnapshot(observationId)
                    .orElseThrow(() -> ApiException.conflict(
                            "disclosure snapshot conflict: " + observationId, null));
            return completeRelease(request.requestId(), HttpStatus.OK, disclosureResponseOf(winner));
        }
        return completeRelease(request.requestId(), HttpStatus.CREATED, disclosureResponseOf(snapshot));
    }

    /**
     * 查询单簇禁运设置明细；未设置返回 404。
     */
    @Transactional(readOnly = true)
    public EmbargoItemResponse getEmbargo(String observationId) {
        EmbargoRecord record = embargoRepository.find(observationId)
                .orElseThrow(() -> ApiException.notFound("embargo not found: " + observationId));
        return itemResponseOf(record);
    }

    /**
     * 查询全部禁运设置，按设置时刻、簇标识稳定排序。
     */
    @Transactional(readOnly = true)
    public List<EmbargoItemResponse> listEmbargoes() {
        return embargoRepository.findAll().stream().map(this::itemResponseOf).toList();
    }

    /**
     * 查询单簇披露快照；未发布返回 404。
     */
    @Transactional(readOnly = true)
    public DisclosureResponse getDisclosure(String observationId) {
        DisclosureSnapshotRecord record = disclosureRepository.findSnapshot(observationId)
                .orElseThrow(() -> ApiException.notFound("disclosure not found: " + observationId));
        return disclosureResponseOf(record);
    }

    /**
     * 查询全部披露快照，按发布时刻、簇标识稳定排序。
     */
    @Transactional(readOnly = true)
    public List<DisclosureResponse> listDisclosures() {
        return disclosureRepository.findAllSnapshots().stream()
                .map(this::disclosureResponseOf).toList();
    }

    /**
     * 查询簇的披露附页历史，按序号升序稳定排序；观测记录不存在返回 404。
     */
    @Transactional(readOnly = true)
    public List<AppendixResponse> listAppendices(String observationId) {
        observationRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        return disclosureRepository.findAppendices(observationId).stream()
                .map(EmbargoService::appendixResponseOf).toList();
    }

    /**
     * 禁运诊断：汇报簇当前的禁运、发布与未解决冲突状态；观测记录不存在返回 404。
     */
    @Transactional(readOnly = true)
    public EmbargoStatusResponse getStatus(String observationId) {
        observationRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        Instant now = Instant.now(clock);
        EmbargoRecord embargo = embargoRepository.find(observationId).orElse(null);
        DisclosureSnapshotRecord snapshot = disclosureRepository.findSnapshot(observationId).orElse(null);
        OpenConflictRecord openConflict = openConflictRepository.find(observationId).orElse(null);
        boolean released = snapshot != null;
        boolean active = embargo != null && now.isBefore(embargo.embargoUntilUtc()) && !released;
        Long remainingSeconds = embargo == null ? null
                : Duration.between(now, embargo.embargoUntilUtc()).getSeconds();
        return new EmbargoStatusResponse(
                observationId,
                now,
                embargo != null,
                embargo == null ? null : embargo.embargoUntilUtc(),
                embargo == null ? null : embargo.setAtUtc(),
                active,
                remainingSeconds,
                released,
                released ? snapshot.releasedAtUtc() : null,
                openConflict != null,
                openConflict == null ? null : openConflict.conflictFields(),
                disclosureRepository.countAppendices(observationId));
    }

    // ---------- 内部辅助逻辑 ----------

    /**
     * 规范化批次项：披露范围去空白、去重、排序；批次项按 observationId 字典序排序，保证指纹与响应稳定。
     */
    private List<NormalizedItem> normalizeItems(List<EmbargoItemRequest> items) {
        return items.stream()
                .map(item -> new NormalizedItem(item.observationId(), item.embargoUntilUtc(),
                        item.disclosureScope() == null ? List.of()
                                : item.disclosureScope().stream()
                                        .map(String::trim)
                                        .filter(scope -> !scope.isEmpty())
                                        .distinct()
                                        .sorted()
                                        .toList()))
                .sorted((left, right) -> left.observationId().compareTo(right.observationId()))
                .toList();
    }

    /**
     * 计算质量标记：冲突裁决经历、禁运后内容变更、发布前删除；按字典序排序保证输出稳定。
     */
    private List<String> qualityFlags(EmbargoRecord embargo, ObservationSnapshot current,
                                      List<ResolutionRecord> resolutions) {
        List<String> flags = new ArrayList<>();
        if (!resolutions.isEmpty()) {
            flags.add("HAS_RESOLVED_CONFLICTS");
        }
        if (current.version() != embargo.versionAtSet()) {
            flags.add("CONTENT_CHANGED_AFTER_EMBARGO");
        }
        if (current.deleted()) {
            flags.add("DELETED_BEFORE_RELEASE");
        }
        Collections.sort(flags);
        return flags;
    }

    private List<ResolutionSummary> resolutionSummaries(List<ResolutionRecord> resolutions) {
        return resolutions.stream().map(this::resolutionSummaryOf).toList();
    }

    private ResolutionSummary resolutionSummaryOf(ResolutionRecord record) {
        return new ResolutionSummary(record.resolutionId(), record.requestId(), record.baseVersion(),
                record.previousVersion(), record.newVersion(), List.copyOf(record.conflictFields()),
                readSelections(record.fieldSelections()), record.operator(), record.resolvedAtUtc(),
                record.contentChanged());
    }

    private EmbargoItemResponse itemResponseOf(EmbargoRecord record) {
        return new EmbargoItemResponse(record.observationId(), record.embargoUntilUtc(),
                readStringList(record.disclosureScope()), record.versionAtSet(), record.setAtUtc(),
                Duration.between(record.setAtUtc(), record.embargoUntilUtc()).getSeconds());
    }

    private DisclosureResponse disclosureResponseOf(DisclosureSnapshotRecord record) {
        return new DisclosureResponse(record.observationId(), record.version(), record.deleted(),
                record.deleted() ? null : record.location(),
                record.deleted() ? null : record.reading(),
                record.deleted() ? null : record.note(),
                readResolutionList(record.resolutions()),
                readStringList(record.qualityFlags()),
                record.operator(), record.requestId(), record.releasedAtUtc(),
                disclosureRepository.countAppendices(record.observationId()));
    }

    private static AppendixResponse appendixResponseOf(AppendixRecord record) {
        return new AppendixResponse(record.observationId(), record.seq(), record.version(),
                record.deleted(),
                record.deleted() ? null : record.location(),
                record.deleted() ? null : record.reading(),
                record.deleted() ? null : record.note(),
                record.sourceRequestId(), record.recordedAtUtc());
    }

    private EmbargoBatchOutcome checkBatchReplay(String requestId, String fingerprint) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new EmbargoBatchOutcome(entry.responseStatus(), readBatchBody(entry.responseBody()));
    }

    private EmbargoBatchOutcome insertBatchPlaceholder(String requestId, String fingerprint) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, "EMBARGO_SET");
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict(
                        "requestId reused with different parameters: " + requestId, null);
            }
            return new EmbargoBatchOutcome(entry.responseStatus(), readBatchBody(entry.responseBody()));
        }
    }

    private EmbargoBatchOutcome completeBatch(String requestId, HttpStatus status,
                                              EmbargoBatchResponse body) {
        requestLogRepository.complete(requestId, status.value(), writeJson(body));
        return new EmbargoBatchOutcome(status.value(), body);
    }

    private ReleaseOutcome checkReleaseReplay(String requestId, String fingerprint) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new ReleaseOutcome(entry.responseStatus(), readReleaseBody(entry.responseBody()));
    }

    private ReleaseOutcome insertReleasePlaceholder(String requestId, String fingerprint) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, "RELEASE");
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict(
                        "requestId reused with different parameters: " + requestId, null);
            }
            return new ReleaseOutcome(entry.responseStatus(), readReleaseBody(entry.responseBody()));
        }
    }

    private ReleaseOutcome completeRelease(String requestId, HttpStatus status, DisclosureResponse body) {
        requestLogRepository.complete(requestId, status.value(), writeJson(body));
        return new ReleaseOutcome(status.value(), body);
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

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize embargo payload", e);
        }
    }

    private List<String> readStringList(String json) {
        try {
            return objectMapper.readValue(json, STRING_LIST_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored string list", e);
        }
    }

    private List<ResolutionSummary> readResolutionList(String json) {
        try {
            return objectMapper.readValue(json, RESOLUTION_LIST_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored resolutions", e);
        }
    }

    private Map<String, String> readSelections(String json) {
        try {
            return objectMapper.readValue(json, SELECTIONS_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored selections", e);
        }
    }

    private EmbargoBatchResponse readBatchBody(String json) {
        try {
            return objectMapper.readValue(json, EmbargoBatchResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored embargo response", e);
        }
    }

    private DisclosureResponse readReleaseBody(String json) {
        try {
            return objectMapper.readValue(json, DisclosureResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored disclosure response", e);
        }
    }

    /**
     * 规范化后的单簇禁运设置项：披露范围已去空白、去重、排序。
     */
    private record NormalizedItem(String observationId, Instant embargoUntilUtc,
                                  List<String> disclosureScope) {
    }

    /**
     * 批量禁运设置结果：HTTP 状态码与响应体。
     */
    public record EmbargoBatchOutcome(int status, EmbargoBatchResponse body) {
    }

    /**
     * 到期发布结果：HTTP 状态码与披露快照响应体。
     */
    public record ReleaseOutcome(int status, DisclosureResponse body) {
    }
}