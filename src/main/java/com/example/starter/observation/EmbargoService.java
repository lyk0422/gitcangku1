package com.example.starter.observation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 观测禁运与到期发布业务服务。
 *
 * <p>禁运设置（SET_EMBARGO）支持批量：先按 observationId 排序规范化条目，完成全部只读校验
 * （存在性、墓碑、披露范围、未解决冲突、截止时刻不早于簇创建、未重复设置）后在同一事务内原子写入；
 * 任一簇非法整次回滚，失败不占 requestId 键。
 *
 * <p>到期发布（RELEASE）在禁运到期后（now &gt;= embargoUntil，左闭右开）首次执行时生成不可变披露快照，
 * 包含发布时胜出版本、最近一次冲突裁决与质量标记；同簇并发或重复发布只保留首个快照，后续请求返回既有快照。
 *
 * <p>发布后更正（CORRECT）只追加 disclosure_appendix 附页，不改写首次快照。
 * 所有写操作沿用 request_log 占位 + 行锁机制：同参重放返回首次完整响应，异参 409，失败不占键。
 */
@Service
public class EmbargoService {

    public static final String REASON_EMBARGO_BEFORE_CREATION = "EMBARGO_BEFORE_CREATION";
    public static final String REASON_EMPTY_DISCLOSURE_SCOPE = "EMPTY_DISCLOSURE_SCOPE";
    public static final String REASON_UNRESOLVED_CONFLICT = "UNRESOLVED_CONFLICT";
    public static final String REASON_OBSERVATION_DELETED = "OBSERVATION_DELETED";
    public static final String REASON_DUPLICATE_ITEM = "DUPLICATE_ITEM";
    public static final String REASON_EMBARGO_ALREADY_SET = "EMBARGO_ALREADY_SET";
    public static final String REASON_EMBARGO_NOT_SET = "EMBARGO_NOT_SET";
    public static final String REASON_EMBARGO_NOT_EXPIRED = "EMBARGO_NOT_EXPIRED";
    public static final String REASON_NOT_RELEASED = "NOT_RELEASED";
    public static final String REASON_EMBARGOED = "EMBARGOED";
    public static final String REASON_RELEASE_PENDING = "RELEASE_PENDING";
    public static final String REASON_EVIDENCE_UNAUTHORIZED = "EVIDENCE_UNAUTHORIZED";

    private static final String SEPARATOR = "\u0001";
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, String>> STRING_MAP_TYPE = new TypeReference<>() {
    };

    private final ObservationRepository observationRepository;
    private final EmbargoRepository embargoRepository;
    private final DisclosureRepository disclosureRepository;
    private final RequestLogRepository requestLogRepository;
    private final ResolutionRepository resolutionRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public EmbargoService(ObservationRepository observationRepository,
                          EmbargoRepository embargoRepository,
                          DisclosureRepository disclosureRepository,
                          RequestLogRepository requestLogRepository,
                          ResolutionRepository resolutionRepository,
                          ObjectMapper objectMapper,
                          Clock clock) {
        this.observationRepository = observationRepository;
        this.embargoRepository = embargoRepository;
        this.disclosureRepository = disclosureRepository;
        this.requestLogRepository = requestLogRepository;
        this.resolutionRepository = resolutionRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 批量设置禁运：任一簇非法（422/404/409）整次回滚；成功后各簇禁运不可变。
     */
    @Transactional
    public SetEmbargoOutcome setEmbargoes(SetEmbargoRequest request) {
        List<SetEmbargoItem> items = normalizeItems(request.items());
        String fingerprint = fingerprint("SET_EMBARGO", embargoFingerprintParts(request.operator(), items));
        SetEmbargoOutcome replayed = checkSetReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        SetEmbargoOutcome concurrent = insertSetPlaceholder(request.requestId(), fingerprint);
        if (concurrent != null) {
            return concurrent;
        }

        // 第一阶段：按排序顺序逐簇加行锁并完成全部只读校验，任何失败都不写入部分结果
        List<ObservationSnapshot> currents = new ArrayList<>();
        for (SetEmbargoItem item : items) {
            currents.add(observationRepository.findCurrentForUpdate(item.observationId())
                    .orElseThrow(() -> ApiException.notFound("observation not found: " + item.observationId())));
        }
        for (int i = 0; i < items.size(); i++) {
            validateEmbargoItem(items.get(i), currents.get(i));
        }

        // 第二阶段：全部校验通过后同事务写入
        Instant now = Instant.now(clock);
        List<EmbargoResponse> responses = new ArrayList<>();
        for (SetEmbargoItem item : items) {
            EmbargoRecord record = new EmbargoRecord(item.observationId(), item.embargoUntilUtc(),
                    item.disclosureScope(), request.operator(), request.requestId(), now);
            embargoRepository.insertEmbargo(record);
            responses.add(embargoResponseOf(record, null));
        }
        SetEmbargoResponse body = new SetEmbargoResponse(request.requestId(), request.operator(), now,
                List.copyOf(responses));
        requestLogRepository.complete(request.requestId(), HttpStatus.CREATED.value(), writeJson(body));
        return new SetEmbargoOutcome(HttpStatus.CREATED.value(), body);
    }

    /**
     * 到期发布：禁运到期后首次发布生成不可变披露快照；同簇重复/并发发布返回既有快照（200）。
     */
    @Transactional
    public ReleaseOutcome release(String observationId, ReleaseRequest request) {
        String fingerprint = fingerprint("RELEASE", List.of(observationId, request.operator()));
        ReleaseOutcome replayed = checkReleaseReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        ReleaseOutcome concurrent = insertReleasePlaceholder(request.requestId(), fingerprint);
        if (concurrent != null) {
            return concurrent;
        }

        ObservationSnapshot current = observationRepository.findCurrentForUpdate(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        EmbargoRecord embargo = embargoRepository.findEmbargo(observationId)
                .orElseThrow(() -> ApiException.conflict(REASON_EMBARGO_NOT_SET,
                        "embargo not set for observation: " + observationId, null));
        Instant now = Instant.now(clock);
        if (now.isBefore(embargo.embargoUntilUtc())) {
            long remainingMillis = Duration.between(now, embargo.embargoUntilUtc()).toMillis();
            throw ApiException.conflict(REASON_EMBARGO_NOT_EXPIRED,
                    "embargo not expired: remainingMillis=" + remainingMillis + " requiredMillis=0"
                            + " embargoUntilUtc=" + embargo.embargoUntilUtc(), null);
        }

        Optional<DisclosureSnapshotRecord> existing = disclosureRepository.findSnapshot(observationId);
        if (existing.isPresent()) {
            return completeRelease(request.requestId(), HttpStatus.OK, disclosureResponseOf(existing.get()));
        }

        List<ResolutionRecord> resolutions = resolutionRepository.findByObservationId(observationId);
        ResolutionRecord latest = resolutions.isEmpty() ? null : resolutions.get(resolutions.size() - 1);
        List<String> qualityFlags = new ArrayList<>();
        if (current.deleted()) {
            qualityFlags.add("DELETED");
        }
        if (latest != null) {
            qualityFlags.add("CONFLICT_RESOLVED");
        }
        if (embargoRepository.findPendingConflict(observationId).isPresent()) {
            qualityFlags.add("UNRESOLVED_CONFLICT");
        }
        if (qualityFlags.isEmpty()) {
            qualityFlags.add("CLEAN");
        }

        DisclosureSnapshotRecord record = new DisclosureSnapshotRecord(
                observationId, current.version(),
                current.deleted() ? null : current.location(),
                current.deleted() ? null : current.reading(),
                current.deleted() ? null : current.note(),
                current.deleted(),
                latest == null ? null : latest.resolutionId(),
                latest == null ? null : writeJson(latest.conflictFields()),
                latest == null ? null : latest.fieldSelections(),
                writeJson(qualityFlags),
                request.operator(), request.requestId(), now);
        try {
            disclosureRepository.insertSnapshot(record);
        } catch (DuplicateKeyException e) {
            // 同簇并发发布：主键串行化，后到者返回先到者生成的唯一快照
            DisclosureSnapshotRecord winner = disclosureRepository.findSnapshot(observationId)
                    .orElseThrow(() -> ApiException.conflict("disclosure snapshot conflict: " + observationId, null));
            return completeRelease(request.requestId(), HttpStatus.OK, disclosureResponseOf(winner));
        }
        return completeRelease(request.requestId(), HttpStatus.CREATED, disclosureResponseOf(record));
    }

    /**
     * 发布后更正：只追加附页（seq 递增），不改写首次快照；未发布时 409。
     */
    @Transactional
    public CorrectOutcome correct(String observationId, CorrectionRequest request) {
        String fingerprint = fingerprint("CORRECT", List.of(observationId, request.operator(),
                request.location(), request.reading(), request.note(), request.reason()));
        CorrectOutcome replayed = checkCorrectReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        CorrectOutcome concurrent = insertCorrectPlaceholder(request.requestId(), fingerprint);
        if (concurrent != null) {
            return concurrent;
        }

        observationRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        disclosureRepository.findSnapshotForUpdate(observationId)
                .orElseThrow(() -> ApiException.conflict(REASON_NOT_RELEASED,
                        "observation not released yet: " + observationId, null));
        int seq = disclosureRepository.maxAppendixSeq(observationId) + 1;
        Instant now = Instant.now(clock);
        DisclosureAppendixRecord record = new DisclosureAppendixRecord(observationId, seq,
                request.location(), request.reading(), request.note(), request.reason(),
                request.operator(), request.requestId(), now);
        disclosureRepository.insertAppendix(record);
        AppendixResponse body = appendixResponseOf(record);
        requestLogRepository.complete(request.requestId(), HttpStatus.CREATED.value(), writeJson(body));
        return new CorrectOutcome(HttpStatus.CREATED.value(), body);
    }

    /**
     * 公开查询：未设禁运返回当前内容（OPEN）；截止前 403 不返回内容；到期未发布 403；
     * 已发布返回首次快照与全部附页（RELEASED）。只读，不改变状态。
     */
    @Transactional(readOnly = true)
    public PublicObservationResponse getPublic(String observationId) {
        ObservationSnapshot current = observationRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        Optional<EmbargoRecord> embargo = embargoRepository.findEmbargo(observationId);
        if (embargo.isPresent()) {
            Instant now = Instant.now(clock);
            if (now.isBefore(embargo.get().embargoUntilUtc())) {
                long remainingMillis = Duration.between(now, embargo.get().embargoUntilUtc()).toMillis();
                throw ApiException.forbidden(REASON_EMBARGOED,
                        "observation under embargo until " + embargo.get().embargoUntilUtc()
                                + ": remainingMillis=" + remainingMillis + " requiredMillis=0");
            }
            DisclosureSnapshotRecord snapshot = disclosureRepository.findSnapshot(observationId)
                    .orElseThrow(() -> ApiException.forbidden(REASON_RELEASE_PENDING,
                            "embargo expired but observation not released yet: " + observationId));
            return new PublicObservationResponse(observationId, "RELEASED", snapshot.version(),
                    snapshot.deleted(),
                    snapshot.deleted() ? null : snapshot.location(),
                    snapshot.deleted() ? null : snapshot.reading(),
                    snapshot.deleted() ? null : snapshot.note(),
                    snapshot.releasedAtUtc(), appendixResponses(observationId));
        }
        return new PublicObservationResponse(observationId, "OPEN", current.version(), current.deleted(),
                current.deleted() ? null : current.location(),
                current.deleted() ? null : current.reading(),
                current.deleted() ? null : current.note(),
                null, List.of());
    }

    /**
     * 授权证据查询（调用方已完成密钥校验）：不受禁运限制，返回当前内容、禁运设置与未解决冲突标记。
     */
    @Transactional(readOnly = true)
    public EvidenceResponse getEvidence(String observationId) {
        ObservationSnapshot current = observationRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        Optional<EmbargoRecord> embargo = embargoRepository.findEmbargo(observationId);
        Optional<PendingConflictRecord> pending = embargoRepository.findPendingConflict(observationId);
        return new EvidenceResponse(observationId, current.version(), current.deleted(),
                current.deleted() ? null : current.location(),
                current.deleted() ? null : current.reading(),
                current.deleted() ? null : current.note(),
                embargo.map(EmbargoRecord::embargoUntilUtc).orElse(null),
                embargo.map(EmbargoRecord::disclosureScope).orElse(null),
                embargo.map(EmbargoRecord::setAtUtc).orElse(null),
                pending.isPresent(),
                pending.map(record -> readStringList(record.conflictFieldsJson())).orElse(null));
    }

    /**
     * 禁运明细查询；观测记录不存在 404，未设禁运 404（EMBARGO_NOT_SET）。
     */
    @Transactional(readOnly = true)
    public EmbargoResponse getEmbargo(String observationId) {
        observationRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        EmbargoRecord embargo = embargoRepository.findEmbargo(observationId)
                .orElseThrow(() -> ApiException.notFound(REASON_EMBARGO_NOT_SET,
                        "embargo not set for observation: " + observationId));
        return embargoResponseOf(embargo,
                disclosureRepository.findSnapshot(observationId).orElse(null));
    }

    /**
     * 披露历史查询：首次快照与全部附页（按 seq 升序）；未发布 404（NOT_RELEASED）。
     */
    @Transactional(readOnly = true)
    public DisclosureResponse getDisclosure(String observationId) {
        observationRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        DisclosureSnapshotRecord snapshot = disclosureRepository.findSnapshot(observationId)
                .orElseThrow(() -> ApiException.notFound(REASON_NOT_RELEASED,
                        "observation not released yet: " + observationId));
        return disclosureResponseOf(snapshot);
    }

    /**
     * 禁运诊断查询：返回判定所需的实际值与差额（剩余毫秒、附页数等）。
     */
    @Transactional(readOnly = true)
    public EmbargoDiagnosticsResponse getDiagnostics(String observationId) {
        observationRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        Instant now = Instant.now(clock);
        Optional<EmbargoRecord> embargo = embargoRepository.findEmbargo(observationId);
        Optional<DisclosureSnapshotRecord> snapshot = disclosureRepository.findSnapshot(observationId);
        Optional<PendingConflictRecord> pending = embargoRepository.findPendingConflict(observationId);
        Boolean expired = embargo.map(record -> !now.isBefore(record.embargoUntilUtc())).orElse(null);
        Long remainingMillis = embargo
                .map(record -> Math.max(0L, Duration.between(now, record.embargoUntilUtc()).toMillis()))
                .orElse(null);
        return new EmbargoDiagnosticsResponse(observationId, now, embargo.isPresent(),
                embargo.map(EmbargoRecord::embargoUntilUtc).orElse(null),
                expired, remainingMillis, snapshot.isPresent(),
                snapshot.map(DisclosureSnapshotRecord::releasedAtUtc).orElse(null),
                disclosureRepository.countAppendices(observationId),
                pending.isPresent(),
                pending.map(record -> readStringList(record.conflictFieldsJson())).orElse(null));
    }

    /**
     * 记录未解决冲突标记：在独立事务（REQUIRES_NEW）中提交，使合并 409 回滚后标记仍然可查；
     * 同一簇再次冲突时刷新为最近一次。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordPendingConflict(String observationId, List<String> conflictFields,
                                      int baseVersion, int currentVersion) {
        PendingConflictRecord record = new PendingConflictRecord(observationId, writeJson(conflictFields),
                baseVersion, currentVersion, Instant.now(clock));
        try {
            embargoRepository.insertPendingConflict(record);
        } catch (DuplicateKeyException e) {
            embargoRepository.updatePendingConflict(record);
        }
    }

    /**
     * 清除未解决冲突标记：加入调用方事务，与冲突解决/删除原子提交。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void clearPendingConflict(String observationId) {
        embargoRepository.deletePendingConflict(observationId);
    }
    // ---------- 内部辅助逻辑 ----------

    /**
     * 规范化批量条目：披露范围去首尾空白，按 observationId 升序排序；同批重复簇 422。
     */
    private List<SetEmbargoItem> normalizeItems(List<SetEmbargoItem> rawItems) {
        List<SetEmbargoItem> items = rawItems.stream()
                .map(item -> new SetEmbargoItem(item.observationId(), item.embargoUntilUtc(),
                        item.disclosureScope() == null ? null : item.disclosureScope().trim()))
                .sorted(Comparator.comparing(SetEmbargoItem::observationId))
                .toList();
        Set<String> seen = new HashSet<>();
        for (SetEmbargoItem item : items) {
            if (!seen.add(item.observationId())) {
                throw ApiException.unprocessable(REASON_DUPLICATE_ITEM,
                        "duplicate observationId in batch: " + item.observationId());
            }
        }
        return items;
    }

    /**
     * 单簇禁运校验：墓碑、披露范围为空、未解决冲突、重复设置、截止时刻早于簇创建。
     */
    private void validateEmbargoItem(SetEmbargoItem item, ObservationSnapshot current) {
        String observationId = item.observationId();
        if (current.deleted()) {
            throw ApiException.unprocessable(REASON_OBSERVATION_DELETED,
                    "observation already deleted: " + observationId);
        }
        if (item.disclosureScope() == null || item.disclosureScope().isEmpty()) {
            throw ApiException.unprocessable(REASON_EMPTY_DISCLOSURE_SCOPE,
                    "disclosureScope must not be blank for observation: " + observationId);
        }
        Optional<PendingConflictRecord> pending = embargoRepository.findPendingConflict(observationId);
        if (pending.isPresent()) {
            throw ApiException.unprocessable(REASON_UNRESOLVED_CONFLICT,
                    "observation has unresolved conflict: " + observationId
                            + " conflictFields=" + pending.get().conflictFieldsJson());
        }
        if (embargoRepository.findEmbargo(observationId).isPresent()) {
            throw ApiException.conflict(REASON_EMBARGO_ALREADY_SET,
                    "embargo already set for observation: " + observationId, null);
        }
        Instant creation = observationRepository.findCreationInstant(observationId)
                .orElseThrow(() -> new IllegalStateException(
                        "creation version missing for observation: " + observationId));
        if (item.embargoUntilUtc().isBefore(creation)) {
            throw ApiException.unprocessable(REASON_EMBARGO_BEFORE_CREATION,
                    "embargoUntilUtc earlier than cluster creation for observation: " + observationId
                            + " actual=" + item.embargoUntilUtc() + " required>=" + creation
                            + " differenceMillis=" + Duration.between(item.embargoUntilUtc(), creation).toMillis());
        }
    }

    /**
     * 幂等指纹参数：操作者 + 规范化条目集合（每条目的全部结果相关字段按序展开）。
     */
    private List<String> embargoFingerprintParts(String operator, List<SetEmbargoItem> items) {
        List<String> parts = new ArrayList<>();
        parts.add(operator);
        for (SetEmbargoItem item : items) {
            parts.add(item.observationId());
            parts.add(item.embargoUntilUtc().toString());
            parts.add(item.disclosureScope());
        }
        return parts;
    }

    private EmbargoResponse embargoResponseOf(EmbargoRecord record, DisclosureSnapshotRecord snapshot) {
        return new EmbargoResponse(record.observationId(), record.embargoUntilUtc(), record.disclosureScope(),
                record.operator(), record.setAtUtc(), snapshot != null,
                snapshot == null ? null : snapshot.releasedAtUtc());
    }

    private DisclosureResponse disclosureResponseOf(DisclosureSnapshotRecord record) {
        List<AppendixResponse> appendices = appendixResponses(record.observationId());
        return new DisclosureResponse(record.observationId(), record.version(), record.deleted(),
                record.location(), record.reading(), record.note(),
                record.resolutionId(),
                record.conflictFieldsJson() == null ? null : readStringList(record.conflictFieldsJson()),
                record.fieldSelectionsJson() == null ? null : readStringMap(record.fieldSelectionsJson()),
                readStringList(record.qualityFlagsJson()),
                record.operator(), record.releasedAtUtc(), appendices.size(), appendices);
    }

    private List<AppendixResponse> appendixResponses(String observationId) {
        return disclosureRepository.findAppendices(observationId).stream()
                .map(this::appendixResponseOf)
                .toList();
    }

    private AppendixResponse appendixResponseOf(DisclosureAppendixRecord record) {
        return new AppendixResponse(record.observationId(), record.seq(), record.location(), record.reading(),
                record.note(), record.reason(), record.operator(), record.createdAtUtc());
    }

    /**
     * 幂等指纹：操作类型 + 各字段以转义分隔符（）连接后的 SHA-256，集合逐元素展开，不做原文拼接。
     */
    private String fingerprint(String operation, List<String> parts) {
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
            throw new IllegalStateException("failed to serialize value", e);
        }
    }

    private List<String> readStringList(String json) {
        try {
            return objectMapper.readValue(json, STRING_LIST_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored list", e);
        }
    }

    private Map<String, String> readStringMap(String json) {
        try {
            return objectMapper.readValue(json, STRING_MAP_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored map", e);
        }
    }
    // ---------- 幂等占位与重放（语义与 ObservationService 一致：失败不占键） ----------

    private SetEmbargoOutcome checkSetReplay(String requestId, String fingerprint) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new SetEmbargoOutcome(entry.responseStatus(), readBody(entry.responseBody(), SetEmbargoResponse.class));
    }

    private SetEmbargoOutcome insertSetPlaceholder(String requestId, String fingerprint) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, "SET_EMBARGO");
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return new SetEmbargoOutcome(entry.responseStatus(),
                    readBody(entry.responseBody(), SetEmbargoResponse.class));
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
            return new ReleaseOutcome(entry.responseStatus(),
                    readBody(entry.responseBody(), DisclosureResponse.class));
        }
    }

    private CorrectOutcome checkCorrectReplay(String requestId, String fingerprint) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new CorrectOutcome(entry.responseStatus(), readBody(entry.responseBody(), AppendixResponse.class));
    }

    private CorrectOutcome insertCorrectPlaceholder(String requestId, String fingerprint) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, "CORRECT");
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return new CorrectOutcome(entry.responseStatus(),
                    readBody(entry.responseBody(), AppendixResponse.class));
        }
    }


    private ReleaseOutcome completeRelease(String requestId, HttpStatus status, DisclosureResponse body) {
        requestLogRepository.complete(requestId, status.value(), writeJson(body));
        return new ReleaseOutcome(status.value(), body);
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
    public record SetEmbargoOutcome(int status, SetEmbargoResponse body) {
    }

    /**
     * 到期发布结果：HTTP 状态码与披露响应体。
     */
    public record ReleaseOutcome(int status, DisclosureResponse body) {
    }

    /**
     * 更正附页结果：HTTP 状态码与附页响应体。
     */
    public record CorrectOutcome(int status, AppendixResponse body) {
    }
}