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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 观测禁运与到期披露服务。
 *
 * <p>批量禁运设置：先校验全部条目的最终状态（记录存在、未设禁运、截止时刻不早于簇创建、
 * 披露范围非空、无未解决冲突、批内不重复），任一非法整次 422 回滚；全部通过后在一个事务内
 * 逐条插入，禁运设置不可变。生效区间 [setAtUtc, embargoUntilUtc) 左闭右开。
 *
 * <p>到期发布：禁运到期（now &gt;= until）后首次发布生成不可变披露快照，包含发布时点胜出版本、
 * 冲突裁决摘要与质量标记；同一簇并发发布由快照主键串行化，只生成一个快照，后到者重读同一快照。
 *
 * <p>幂等：requestId 同参重放返回首次完整响应快照，异参 409，失败不占键；
 * 指纹包含操作者、操作类型、规范化对象集合（排序后以 JSON 序列化，不用未转义分隔符拼接）
 * 及所有影响结果的字段。业务时间统一为 UTC 毫秒精度，保证存取往返与响应重放稳定。
 */
@Service
public class EmbargoService {

    private static final String SEPARATOR = "";

    private final ObservationRepository observationRepository;
    private final EmbargoRepository embargoRepository;
    private final ConflictRepository conflictRepository;
    private final DisclosureRepository disclosureRepository;
    private final ResolutionRepository resolutionRepository;
    private final RequestLogRepository requestLogRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public EmbargoService(ObservationRepository observationRepository,
                          EmbargoRepository embargoRepository,
                          ConflictRepository conflictRepository,
                          DisclosureRepository disclosureRepository,
                          ResolutionRepository resolutionRepository,
                          RequestLogRepository requestLogRepository,
                          ObjectMapper objectMapper,
                          Clock clock) {
        this.observationRepository = observationRepository;
        this.embargoRepository = embargoRepository;
        this.conflictRepository = conflictRepository;
        this.disclosureRepository = disclosureRepository;
        this.resolutionRepository = resolutionRepository;
        this.requestLogRepository = requestLogRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 批量设置禁运：全量校验通过后单事务提交；任一簇非法整次 422 回滚，查询不到半成品。
     */
    @Transactional
    public EmbargoOutcome setEmbargo(EmbargoSetRequest request) {
        List<EmbargoItem> normalized = request.items().stream()
                .sorted(Comparator.comparing(EmbargoItem::observationId))
                .toList();
        String fingerprint = fingerprint("EMBARGO", request.operator(), canonicalItemsJson(normalized));
        EmbargoOutcome replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        EmbargoOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint, "EMBARGO");
        if (concurrent != null) {
            return concurrent;
        }

        List<Map<String, Object>> itemErrors = validateItems(normalized);
        if (!itemErrors.isEmpty()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("requestedCount", normalized.size());
            details.put("invalidCount", itemErrors.size());
            details.put("itemErrors", itemErrors);
            throw ApiException.unprocessable(
                    "embargo validation failed for " + itemErrors.size() + " of " + normalized.size()
                            + " item(s); the whole batch is rolled back", details);
        }

        Instant setAtUtc = Instant.now(clock).truncatedTo(ChronoUnit.MILLIS);
        List<EmbargoResponse.EmbargoEntry> entries = new ArrayList<>();
        for (EmbargoItem item : normalized) {
            EmbargoRecord record = new EmbargoRecord(item.observationId(), item.embargoUntilUtc(),
                    item.disclosureScope().trim(), request.operator(), request.requestId(), setAtUtc);
            try {
                embargoRepository.insert(record);
            } catch (DuplicateKeyException e) {
                // 并发事务已为本簇设置禁运：禁运不可变，整次回滚并指明原因
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("itemErrors", List.of(itemError(item.observationId(), "ALREADY_EMBARGOED",
                        "embargo already set", null, null)));
                throw ApiException.unprocessable(
                        "embargo already set for observation: " + item.observationId(), details);
            }
            entries.add(new EmbargoResponse.EmbargoEntry(item.observationId(), item.embargoUntilUtc(),
                    record.disclosureScope()));
        }
        EmbargoResponse body = new EmbargoResponse(request.requestId(), request.operator(), setAtUtc,
                entries.size(), List.copyOf(entries));
        requestLogRepository.complete(request.requestId(), HttpStatus.OK.value(), writeJson(body));
        return new EmbargoOutcome(HttpStatus.OK.value(), body);
    }

    /**
     * 到期公开发布：到期后首次发布生成不可变披露快照；未到期 422 并返回剩余时长；
     * 已发布时重放首次快照（发布与快照均不可变）；并发发布只生成一个快照。
     */
    @Transactional
    public ReleaseOutcome release(String observationId, ReleaseRequest request) {
        String fingerprint = fingerprint("RELEASE", observationId, request.operator());
        ReleaseOutcome replayed = checkReleaseReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        ReleaseOutcome concurrent = insertReleasePlaceholder(request.requestId(), fingerprint);
        if (concurrent != null) {
            return concurrent;
        }

        // 行锁串行化发布与更正：保证快照胜出版本与附页登记按提交顺序裁决
        ObservationSnapshot current = observationRepository.findCurrentForUpdate(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        EmbargoRecord embargo = embargoRepository.find(observationId)
                .orElseThrow(() -> ApiException.unprocessable(
                        "no embargo set for observation: " + observationId,
                        Map.of("reason", "NO_EMBARGO", "observationId", observationId)));

        Optional<DisclosureSnapshotRecord> existing = disclosureRepository.findSnapshot(observationId);
        if (existing.isPresent()) {
            // 发布不可变：重复发布返回首次快照
            return completeRelease(request.requestId(), disclosureResponse(existing.get()));
        }

        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MILLIS);
        if (now.isBefore(embargo.embargoUntilUtc())) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("reason", "EMBARGO_NOT_EXPIRED");
            details.put("embargoUntilUtc", embargo.embargoUntilUtc().toString());
            details.put("nowUtc", now.toString());
            details.put("remainingMillis", embargo.embargoUntilUtc().toEpochMilli() - now.toEpochMilli());
            throw ApiException.unprocessable("embargo not expired for observation: " + observationId,
                    details);
        }

        List<ResolutionSummary> resolutions = resolutionRepository.findByObservationId(observationId)
                .stream().map(ResolutionSummary::of).toList();
        List<String> qualityFlags = qualityFlags(current, resolutions);
        DisclosureSnapshotRecord snapshot = new DisclosureSnapshotRecord(
                observationId, current.version(),
                current.deleted() ? null : current.location(),
                current.deleted() ? null : current.reading(),
                current.deleted() ? null : current.note(),
                current.deleted(),
                writeJson(resolutions), writeJson(qualityFlags),
                request.requestId(), request.operator(), now);
        try {
            disclosureRepository.insertSnapshot(snapshot);
        } catch (DuplicateKeyException e) {
            // 并发发布已由其他事务生成快照：重读同一快照，保证同一簇只有一个快照
            DisclosureSnapshotRecord winner = disclosureRepository.findSnapshot(observationId)
                    .orElseThrow(() -> ApiException.conflict(
                            "disclosure snapshot conflict: " + observationId, null));
            return completeRelease(request.requestId(), disclosureResponse(winner));
        }
        return completeRelease(request.requestId(), disclosureResponse(snapshot));
    }
    /**
     * 公开查询：禁运期内不返回簇内容；已发布返回首次披露快照；否则返回当前内容。只读，不改变状态。
     */
    @Transactional(readOnly = true)
    public PublicObservationResponse publicView(String observationId) {
        ObservationSnapshot current = observationRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        Optional<EmbargoRecord> embargo = embargoRepository.find(observationId);
        if (embargo.isPresent() && Instant.now(clock).isBefore(embargo.get().embargoUntilUtc())) {
            return PublicObservationResponse.embargoed(observationId, embargo.get().embargoUntilUtc());
        }
        Optional<DisclosureSnapshotRecord> snapshot = disclosureRepository.findSnapshot(observationId);
        if (snapshot.isPresent()) {
            return PublicObservationResponse.released(disclosureResponse(snapshot.get()));
        }
        return PublicObservationResponse.plain(current);
    }

    /**
     * 授权证据查询：需携带非空操作者标识，不受禁运限制返回当前内容与禁运状态。只读。
     */
    @Transactional(readOnly = true)
    public EvidenceResponse evidence(String observationId, String operator) {
        if (operator == null || operator.isBlank()) {
            throw ApiException.badRequest("operator is required for evidence query");
        }
        ObservationSnapshot current = observationRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        Optional<EmbargoRecord> embargo = embargoRepository.find(observationId);
        boolean embargoed = embargo.isPresent()
                && Instant.now(clock).isBefore(embargo.get().embargoUntilUtc());
        return new EvidenceResponse(
                observationId, current.version(), current.deleted(),
                current.deleted() ? null : current.location(),
                current.deleted() ? null : current.reading(),
                current.deleted() ? null : current.note(),
                embargoed,
                embargo.map(EmbargoRecord::embargoUntilUtc).orElse(null),
                embargo.map(EmbargoRecord::disclosureScope).orElse(null),
                conflictRepository.countOpen(observationId),
                disclosureRepository.findSnapshot(observationId).isPresent());
    }

    /**
     * 披露明细查询：返回首次披露快照及全部附页；未发布返回 404。只读。
     */
    @Transactional(readOnly = true)
    public DisclosureResponse disclosure(String observationId) {
        DisclosureSnapshotRecord snapshot = disclosureRepository.findSnapshot(observationId)
                .orElseThrow(() -> ApiException.notFound(
                        "disclosure snapshot not found: " + observationId));
        return disclosureResponse(snapshot);
    }

    /**
     * 附页历史查询：按 seq 升序返回，排序稳定；观测记录不存在返回 404。只读。
     */
    @Transactional(readOnly = true)
    public List<AppendixRecord> appendices(String observationId) {
        observationRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        return disclosureRepository.findAppendices(observationId);
    }

    /**
     * 禁运诊断查询：禁运设置、到期状态（含剩余毫秒实际值）、未解决冲突数与发布状态。只读。
     */
    @Transactional(readOnly = true)
    public EmbargoDiagnosticsResponse diagnostics(String observationId) {
        observationRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        Optional<EmbargoRecord> embargo = embargoRepository.find(observationId);
        Optional<DisclosureSnapshotRecord> snapshot = disclosureRepository.findSnapshot(observationId);
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MILLIS);
        Boolean expired = null;
        Long remainingMillis = null;
        if (embargo.isPresent()) {
            long remaining = embargo.get().embargoUntilUtc().toEpochMilli() - now.toEpochMilli();
            expired = remaining <= 0;
            remainingMillis = Math.max(0, remaining);
        }
        return new EmbargoDiagnosticsResponse(
                observationId,
                embargo.isPresent(),
                embargo.map(EmbargoRecord::embargoUntilUtc).orElse(null),
                embargo.map(EmbargoRecord::disclosureScope).orElse(null),
                embargo.map(EmbargoRecord::operator).orElse(null),
                embargo.map(EmbargoRecord::setAtUtc).orElse(null),
                now,
                expired,
                remainingMillis,
                conflictRepository.countOpen(observationId),
                snapshot.isPresent(),
                snapshot.map(DisclosureSnapshotRecord::releasedAtUtc).orElse(null));
    }

    // ---------- 禁运设置校验与组装 ----------

    /**
     * 逐条校验最终状态：记录存在、未设禁运、截止时刻不早于簇创建、披露范围非空、
     * 无未解决冲突、批内不重复。返回全部错误项（含实际值/要求值），不做任何写入。
     */
    private List<Map<String, Object>> validateItems(List<EmbargoItem> normalized) {
        List<Map<String, Object>> errors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (EmbargoItem item : normalized) {
            String observationId = item.observationId();
            if (!seen.add(observationId)) {
                errors.add(itemError(observationId, "DUPLICATE_IN_BATCH",
                        "duplicate observationId in batch", null, null));
                continue;
            }
            Optional<ObservationSnapshot> current = observationRepository.findCurrent(observationId);
            if (current.isEmpty()) {
                errors.add(itemError(observationId, "OBSERVATION_NOT_FOUND",
                        "observation not found", null, null));
                continue;
            }
            if (embargoRepository.find(observationId).isPresent()) {
                errors.add(itemError(observationId, "ALREADY_EMBARGOED",
                        "embargo already set and immutable", null, null));
            }
            if (item.disclosureScope() == null || item.disclosureScope().isBlank()) {
                errors.add(itemError(observationId, "EMPTY_SCOPE",
                        "disclosure scope must not be empty",
                        item.disclosureScope(), "non-blank scope"));
            }
            Instant createdAt = observationRepository.findCreatedAt(observationId)
                    .orElseThrow(() -> new IllegalStateException(
                            "creation version missing for observation: " + observationId));
            if (item.embargoUntilUtc().isBefore(createdAt)) {
                errors.add(itemError(observationId, "DEADLINE_BEFORE_CREATION",
                        "embargo deadline is before observation creation",
                        item.embargoUntilUtc().toString(), createdAt.toString()));
            }
            int openConflicts = conflictRepository.countOpen(observationId);
            if (openConflicts > 0) {
                errors.add(itemError(observationId, "UNRESOLVED_CONFLICTS",
                        "observation has unresolved conflicts",
                        String.valueOf(openConflicts), "0"));
            }
        }
        return errors;
    }

    private Map<String, Object> itemError(String observationId, String reason, String message,
                                          String actualValue, String requiredValue) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("observationId", observationId);
        error.put("reason", reason);
        error.put("message", message);
        if (actualValue != null) {
            error.put("actualValue", actualValue);
        }
        if (requiredValue != null) {
            error.put("requiredValue", requiredValue);
        }
        return error;
    }

    /**
     * 质量标记：删除墓碑记 DELETED；有冲突裁决记 CONFLICT_RESOLVED；均无则记 CLEAN。
     */
    private List<String> qualityFlags(ObservationSnapshot current, List<ResolutionSummary> resolutions) {
        List<String> flags = new ArrayList<>();
        if (current.deleted()) {
            flags.add("DELETED");
        }
        if (!resolutions.isEmpty()) {
            flags.add("CONFLICT_RESOLVED");
        }
        if (flags.isEmpty()) {
            flags.add("CLEAN");
        }
        return flags;
    }

    private DisclosureResponse disclosureResponse(DisclosureSnapshotRecord snapshot) {
        return DisclosureResponse.of(snapshot,
                disclosureRepository.findAppendices(snapshot.observationId()), objectMapper);
    }

    /**
     * 规范化条目集合的 JSON 表示：按 observationId 升序、键序固定，由 JSON 转义保证集合元素
     * 不通过未转义分隔符拼接，作为幂等指纹的稳定输入。
     */
    private String canonicalItemsJson(List<EmbargoItem> normalized) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (EmbargoItem item : normalized) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("observationId", item.observationId());
            entry.put("embargoUntilUtc", item.embargoUntilUtc().toString());
            entry.put("disclosureScope", item.disclosureScope());
            items.add(entry);
        }
        return writeJson(items);
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

    // ---------- 幂等去重（语义与 ObservationService 一致：同参重放、异参 409、失败不占键） ----------

    private EmbargoOutcome checkReplay(String requestId, String fingerprint) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new EmbargoOutcome(entry.responseStatus(), readBody(entry.responseBody(), EmbargoResponse.class));
    }

    private EmbargoOutcome insertPlaceholder(String requestId, String fingerprint, String operation) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, operation);
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return new EmbargoOutcome(entry.responseStatus(), readBody(entry.responseBody(), EmbargoResponse.class));
        }
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
        return new ReleaseOutcome(entry.responseStatus(), readBody(entry.responseBody(), DisclosureResponse.class));
    }

    private ReleaseOutcome insertReleasePlaceholder(String requestId, String fingerprint) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, "RELEASE");
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return new ReleaseOutcome(entry.responseStatus(), readBody(entry.responseBody(), DisclosureResponse.class));
        }
    }

    private ReleaseOutcome completeRelease(String requestId, DisclosureResponse body) {
        requestLogRepository.complete(requestId, HttpStatus.OK.value(), writeJson(body));
        return new ReleaseOutcome(HttpStatus.OK.value(), body);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize value", e);
        }
    }

    private <T> T readBody(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored response", e);
        }
    }

    /**
     * 批量禁运设置结果：HTTP 状态码与响应体。
     */
    public record EmbargoOutcome(int status, EmbargoResponse body) {
    }

    /**
     * 到期发布结果：HTTP 状态码与披露响应体。
     */
    public record ReleaseOutcome(int status, DisclosureResponse body) {
    }
}