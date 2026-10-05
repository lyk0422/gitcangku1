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
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 观测禁运与到期发布服务。
 *
 * <p>批量禁运设置：先完整校验所有条目的最终状态（存在性、墓碑、禁运不可变、范围非空、
 * 截止时刻不早于簇创建、无未解决冲突），再在单事务内一次性写入；任一条目非法整次回滚，
 * 失败不占幂等键。禁运区间按左闭右开 [setAtUtc, embargoUntilUtc) 解释，到达截止时刻即失效。
 *
 * <p>到期发布：禁运到期后首次发布生成不可变披露快照（胜出版本、全部冲突裁决、质量标记），
 * 同一簇并发发布由 disclosure_snapshot 主键串行化，只生成一个快照，后到者返回同一快照。
 * 发布后的新更正只追加披露附页，不改写首次快照。
 *
 * <p>幂等：requestId 同参重放返回首次完整响应快照，异参 409，失败不占键；
 * 指纹包含操作者、操作类型与规范化后的对象集合（JSON 序列化，无未转义分隔符拼接）。
 */
@Service
public class EmbargoService {

    private static final String SEPARATOR = "";

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
     * 批量设置禁运：全部条目校验通过后单事务原子提交；任一条目非法整次回滚并返回可区分原因。
     */
    @Transactional
    public EmbargoOutcome setEmbargoBatch(SetEmbargoRequest request) {
        List<EmbargoItemRequest> items = normalizeItems(request.items());
        String fingerprint = fingerprint("EMBARGO", request.operator(), canonicalItemsJson(items));
        Replay<EmbargoBatchResponse> replayed = checkReplay(request.requestId(), fingerprint,
                EmbargoBatchResponse.class);
        if (replayed != null) {
            return new EmbargoOutcome(replayed.status(), replayed.body());
        }
        Replay<EmbargoBatchResponse> concurrent = insertPlaceholder(request.requestId(), fingerprint,
                "EMBARGO", EmbargoBatchResponse.class);
        if (concurrent != null) {
            return new EmbargoOutcome(concurrent.status(), concurrent.body());
        }

        // 先完整校验所有条目的最终状态，再统一写入；行锁按 observationId 字典序获取，避免并发死锁
        List<EmbargoRecord> records = new ArrayList<>();
        Instant setAtUtc = Instant.now(clock);
        for (EmbargoItemRequest item : items) {
            String observationId = item.observationId();
            ObservationSnapshot current = observationRepository.findCurrentForUpdate(observationId)
                    .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
            if (current.deleted()) {
                throw ApiException.gone("observation already deleted: " + observationId);
            }
            if (embargoRepository.find(observationId).isPresent()) {
                throw ApiException.conflict("embargo already set for observation: " + observationId, null);
            }
            List<String> scope = normalizeScope(item.scope());
            if (scope.isEmpty()) {
                throw ApiException.unprocessable(
                        "disclosure scope must not be empty for observation: " + observationId);
            }
            Instant createdAtUtc = observationRepository.findCreatedAtUtc(observationId)
                    .orElseThrow(() -> ApiException.unprocessable(
                            "cluster creation time missing for observation: " + observationId));
            if (item.embargoUntilUtc().isBefore(createdAtUtc)) {
                throw ApiException.unprocessable(
                        "embargoUntilUtc earlier than cluster creation for observation: " + observationId
                                + "; actual=" + item.embargoUntilUtc() + ", required>=" + createdAtUtc);
            }
            Optional<OpenConflictRecord> openConflict = openConflictRepository.find(observationId);
            if (openConflict.isPresent()) {
                throw ApiException.unprocessable(
                        "unresolved conflicts present for observation: " + observationId
                                + "; conflictFields=" + String.join(",", openConflict.get().conflictFields()));
            }
            records.add(new EmbargoRecord(observationId, item.embargoUntilUtc(), scope,
                    request.operator(), request.requestId(), setAtUtc));
        }
        for (EmbargoRecord record : records) {
            try {
                embargoRepository.insert(record);
            } catch (DuplicateKeyException e) {
                // 并发批次已设置同一簇禁运：禁运不可变，整次按冲突处理并回滚
                throw ApiException.conflict(
                        "embargo already set for observation: " + record.observationId(), null);
            }
        }
        EmbargoBatchResponse body = new EmbargoBatchResponse(request.requestId(), request.operator(),
                records.size(), records.stream().map(EmbargoService::toItemResponse).toList());
        return completeEmbargo(request.requestId(), HttpStatus.CREATED, body);
    }

    /**
     * 到期发布：禁运到期后生成不可变披露快照；未到期返回 422（携带当前时刻、要求时刻与剩余秒数）。
     * 已发布（含并发后到者）返回首次快照，快照不可变。
     */
    @Transactional
    public ReleaseOutcome release(String observationId, ReleaseEmbargoRequest request) {
        String fingerprint = fingerprint("RELEASE", observationId, request.operator());
        Replay<DisclosureResponse> replayed = checkReplay(request.requestId(), fingerprint,
                DisclosureResponse.class);
        if (replayed != null) {
            return new ReleaseOutcome(replayed.status(), replayed.body());
        }
        Replay<DisclosureResponse> concurrent = insertPlaceholder(request.requestId(), fingerprint,
                "RELEASE", DisclosureResponse.class);
        if (concurrent != null) {
            return new ReleaseOutcome(concurrent.status(), concurrent.body());
        }

        ObservationSnapshot current = observationRepository.findCurrentForUpdate(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        EmbargoRecord embargo = embargoRepository.find(observationId)
                .orElseThrow(() -> ApiException.notFound("embargo not set for observation: " + observationId));
        Optional<DisclosureSnapshotRecord> existing = disclosureRepository.findSnapshot(observationId);
        if (existing.isPresent()) {
            // 首次快照不可变：后续发布请求返回同一快照
            return completeRelease(request.requestId(), HttpStatus.OK, DisclosureResponse.of(existing.get()));
        }
        Instant now = Instant.now(clock);
        if (now.isBefore(embargo.embargoUntilUtc())) {
            long remainingSeconds = Duration.between(now, embargo.embargoUntilUtc()).getSeconds();
            throw ApiException.unprocessable("embargo not expired for observation: " + observationId
                    + "; now=" + now + ", required>=" + embargo.embargoUntilUtc()
                    + ", remainingSeconds=" + remainingSeconds);
        }

        List<DisclosureResolutionItem> resolutions = resolutionRepository.findByObservationId(observationId)
                .stream().map(this::toResolutionItem).toList();
        List<String> qualityFlags = new ArrayList<>();
        if (current.deleted()) {
            qualityFlags.add("DELETED");
        }
        if (!resolutions.isEmpty()) {
            qualityFlags.add("HAS_RESOLVED_CONFLICTS");
        }
        if (openConflictRepository.find(observationId).isPresent()) {
            qualityFlags.add("OPEN_CONFLICTS_PRESENT");
        }
        DisclosureSnapshotRecord record = new DisclosureSnapshotRecord(
                observationId, current.version(),
                current.deleted() ? null : current.location(),
                current.deleted() ? null : current.reading(),
                current.deleted() ? null : current.note(),
                current.deleted(),
                resolutions, List.copyOf(qualityFlags),
                embargo.embargoUntilUtc(), request.operator(), request.requestId(), now);
        try {
            disclosureRepository.insertSnapshot(record);
        } catch (DuplicateKeyException e) {
            // 并发发布：主键串行化后重读，整簇只生成一个快照，后到者返回同一快照
            DisclosureSnapshotRecord winner = disclosureRepository.findSnapshot(observationId)
                    .orElseThrow(() -> ApiException.conflict(
                            "disclosure snapshot conflict: " + observationId, null));
            return completeRelease(request.requestId(), HttpStatus.OK, DisclosureResponse.of(winner));
        }
        return completeRelease(request.requestId(), HttpStatus.CREATED, DisclosureResponse.of(record));
    }

    /**
     * 查询首次披露快照（明细查询）；观测记录不存在或未发布返回 404。只读，不改变状态。
     */
    @Transactional(readOnly = true)
    public DisclosureResponse getDisclosure(String observationId) {
        observationRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        DisclosureSnapshotRecord record = disclosureRepository.findSnapshot(observationId)
                .orElseThrow(() -> ApiException.notFound("disclosure not released: " + observationId));
        return DisclosureResponse.of(record);
    }

    /**
     * 查询更正附页历史，按附页序号升序稳定排序；观测记录不存在返回 404。只读，不改变状态。
     */
    @Transactional(readOnly = true)
    public List<AddendumResponse> listAddenda(String observationId) {
        observationRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        return disclosureRepository.findAddenda(observationId).stream()
                .map(AddendumResponse::of)
                .toList();
    }

    /**
     * 诊断查询：禁运状态、发布状态与未解决冲突明细；未解决冲突按检出时刻升序稳定排序。
     * 只读，不改变状态。
     */
    @Transactional(readOnly = true)
    public DiagnosticsResponse getDiagnostics(String observationId) {
        observationRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        Optional<EmbargoRecord> embargo = embargoRepository.find(observationId);
        Optional<DisclosureSnapshotRecord> snapshot = disclosureRepository.findSnapshot(observationId);
        boolean embargoed = embargo.isPresent() && snapshot.isEmpty()
                && Instant.now(clock).isBefore(embargo.get().embargoUntilUtc());
        List<DiagnosticsResponse.OpenConflictInfo> openConflicts = openConflictRepository.find(observationId)
                .map(record -> List.of(new DiagnosticsResponse.OpenConflictInfo(
                        List.copyOf(record.conflictFields()), record.baseVersion(), record.currentVersion(),
                        record.requestId(), record.detectedAtUtc())))
                .orElse(List.of());
        return new DiagnosticsResponse(observationId, embargoed, embargo.isPresent(),
                embargo.map(EmbargoRecord::embargoUntilUtc).orElse(null),
                embargo.map(EmbargoRecord::scope).orElse(null),
                snapshot.isPresent(),
                snapshot.map(DisclosureSnapshotRecord::releasedAtUtc).orElse(null),
                openConflicts.size(), openConflicts);
    }

    // ---------- 批量设置辅助逻辑 ----------

    /**
     * 规范化批量条目：按 observationId 字典序排序（锁获取顺序与响应顺序稳定），同批重复标识拒绝。
     */
    private List<EmbargoItemRequest> normalizeItems(List<EmbargoItemRequest> items) {
        List<EmbargoItemRequest> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparing(EmbargoItemRequest::observationId));
        Set<String> seen = new HashSet<>();
        for (EmbargoItemRequest item : sorted) {
            if (!seen.add(item.observationId())) {
                throw ApiException.badRequest("duplicate observationId in embargo batch: " + item.observationId());
            }
        }
        return sorted;
    }

    /**
     * 规范化披露范围：去首尾空白、丢弃空标签、去重并按字典序排序。
     */
    private List<String> normalizeScope(List<String> raw) {
        return raw.stream()
                .map(tag -> tag == null ? "" : tag.trim())
                .filter(tag -> !tag.isEmpty())
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * 规范化对象集合的 JSON 表示：作为幂等指纹输入，由 Jackson 完成转义，
     * 不通过未转义分隔符拼接集合元素。
     */
    private String canonicalItemsJson(List<EmbargoItemRequest> items) {
        List<Map<String, Object>> canonical = new ArrayList<>();
        for (EmbargoItemRequest item : items) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("observationId", item.observationId());
            entry.put("embargoUntilUtc", item.embargoUntilUtc().toString());
            entry.put("scope", normalizeScope(item.scope()));
            canonical.add(entry);
        }
        return writeBody(canonical);
    }

    private static EmbargoItemResponse toItemResponse(EmbargoRecord record) {
        return new EmbargoItemResponse(record.observationId(), record.embargoUntilUtc(),
                List.copyOf(record.scope()), record.setAtUtc());
    }

    private DisclosureResolutionItem toResolutionItem(ResolutionRecord record) {
        Map<String, String> selections;
        try {
            selections = objectMapper.readValue(record.fieldSelections(), new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored selections", e);
        }
        return new DisclosureResolutionItem(record.resolutionId(), record.baseVersion(),
                record.previousVersion(), record.newVersion(), List.copyOf(record.conflictFields()),
                selections, record.operator(), record.resolvedAtUtc());
    }

    // ---------- 幂等去重辅助逻辑 ----------

    /**
     * 幂等检查：同键同参返回原成功结果；同键异参返回 409；无记录返回 null 继续执行。
     */
    private <T> Replay<T> checkReplay(String requestId, String fingerprint, Class<T> bodyType) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new Replay<>(entry.responseStatus(), readBody(entry.responseBody(), bodyType));
    }

    /**
     * 占位写入去重记录；并发同键时主键冲突，等待对方事务结束后读取已提交结果：
     * 同参返回重放结果，异参抛 409；正常占位返回 null。业务失败时占位随事务回滚，不占键。
     */
    private <T> Replay<T> insertPlaceholder(String requestId, String fingerprint, String operation,
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
            return new Replay<>(entry.responseStatus(), readBody(entry.responseBody(), bodyType));
        }
    }

    /**
     * 禁运设置成功后回填去重记录响应，与业务变更同事务提交。
     */
    private EmbargoOutcome completeEmbargo(String requestId, HttpStatus status, EmbargoBatchResponse body) {
        requestLogRepository.complete(requestId, status.value(), writeBody(body));
        return new EmbargoOutcome(status.value(), body);
    }

    /**
     * 发布成功后回填去重记录响应，与披露快照同事务提交。
     */
    private ReleaseOutcome completeRelease(String requestId, HttpStatus status, DisclosureResponse body) {
        requestLogRepository.complete(requestId, status.value(), writeBody(body));
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
     * 幂等重放结果：HTTP 状态码与响应体。
     */
    private record Replay<T>(int status, T body) {
    }

    /**
     * 批量禁运设置结果：HTTP 状态码与响应体。
     */
    public record EmbargoOutcome(int status, EmbargoBatchResponse body) {
    }

    /**
     * 到期发布结果：HTTP 状态码与响应体。
     */
    public record ReleaseOutcome(int status, DisclosureResponse body) {
    }
}
