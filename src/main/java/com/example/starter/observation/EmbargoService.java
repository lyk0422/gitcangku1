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
 * 观测禁运与到期发布业务服务。
 *
 * <p>批量设置禁运：先规范化并校验全部簇的最终状态（存在性、墓碑、截止时刻不早于簇创建、
 * 披露范围非空、无未解决冲突、未重复设置），全部合法才在一个事务内插入；任一非法整次回滚。
 * 禁运设置不可变，重复设置（新 requestId）返回 409。
 *
 * <p>公开查询：未设禁运直接公开当前内容；截止前（区间左闭右开）只返回禁运元信息与剩余秒数；
 * 到期后首次公开查询在同一事务内生成不可变发布快照（胜出版本、冲突裁决、质量标记），
 * 同簇并发发布由 UNIQUE(observation_id) 串行化，只生成一个快照；之后读取只返回首次快照。
 *
 * <p>幂等：requestId 同参重放返回首次完整响应快照，异参 409，失败不占键；
 * 指纹包含操作者、操作类型与规范化对象集合（JSON 转义序列化，不用未转义分隔符拼接集合）。
 */
@Service
public class EmbargoService {

    private static final String SEPARATOR = String.valueOf((char) 1);

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private final ObservationRepository observationRepository;
    private final EmbargoRepository embargoRepository;
    private final ReleaseRepository releaseRepository;
    private final OpenConflictRepository openConflictRepository;
    private final ResolutionRepository resolutionRepository;
    private final RequestLogRepository requestLogRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public EmbargoService(ObservationRepository observationRepository,
                          EmbargoRepository embargoRepository,
                          ReleaseRepository releaseRepository,
                          OpenConflictRepository openConflictRepository,
                          ResolutionRepository resolutionRepository,
                          RequestLogRepository requestLogRepository,
                          ObjectMapper objectMapper,
                          Clock clock) {
        this.observationRepository = observationRepository;
        this.embargoRepository = embargoRepository;
        this.releaseRepository = releaseRepository;
        this.openConflictRepository = openConflictRepository;
        this.resolutionRepository = resolutionRepository;
        this.requestLogRepository = requestLogRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 批量设置禁运：任一簇非法（404/410/422/409）整次回滚，查询不到半成品。
     */
    @Transactional
    public SetEmbargoOutcome setEmbargoes(SetEmbargoRequest request) {
        List<NormalizedItem> items = normalizeItems(request.items());
        String fingerprint = fingerprint("EMBARGO_SET", request.operator(), writeItemsJson(items));
        SetEmbargoOutcome replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        SetEmbargoOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint);
        if (concurrent != null) {
            return concurrent;
        }

        Instant now = Instant.now(clock);
        List<EmbargoRecord> records = new ArrayList<>();
        // 先校验完整最终状态：按 observationId 升序逐簇加行锁并校验，全部通过后才写入
        for (NormalizedItem item : items) {
            records.add(validateAndBuild(item, request.operator(), request.requestId(), now));
        }
        for (EmbargoRecord record : records) {
            embargoRepository.insert(record);
        }

        List<SetEmbargoResponse.Item> views = records.stream()
                .map(record -> new SetEmbargoResponse.Item(record.observationId(), record.embargoUntilUtc(),
                        readScope(record.disclosureScope()), record.versionAtSet(), record.setAtUtc()))
                .toList();
        SetEmbargoResponse body = new SetEmbargoResponse(request.requestId(), request.operator(),
                items.size(), records.size(), views);
        return complete(request.requestId(), HttpStatus.CREATED, body);
    }

    /**
     * 规范化禁运项：披露范围去空白、去重、按字典序排序；批次内 observationId 重复返回 400；
     * 范围为空（或规范化后为空）返回 422，携带实际数量与要求数量。
     */
    private List<NormalizedItem> normalizeItems(List<SetEmbargoRequest.Item> rawItems) {
        Set<String> seen = new HashSet<>();
        List<NormalizedItem> items = new ArrayList<>();
        for (SetEmbargoRequest.Item raw : rawItems) {
            if (!seen.add(raw.observationId())) {
                throw ApiException.badRequest(
                        "duplicate observationId in batch: " + raw.observationId());
            }
            List<String> scope = raw.disclosureScope().stream()
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .distinct()
                    .sorted()
                    .toList();
            if (scope.isEmpty()) {
                throw ApiException.unprocessable(
                        "disclosureScope must not be empty for observation " + raw.observationId()
                                + " (actual size 0, required at least 1)");
            }
            items.add(new NormalizedItem(raw.observationId(), raw.embargoUntilUtc(), scope));
        }
        items.sort(Comparator.comparing(NormalizedItem::observationId));
        return items;
    }

    /**
     * 单簇校验并构造禁运记录：行锁串行化并发写入；依次检查存在性（404）、墓碑（410）、
     * 截止时刻不早于簇创建（422，含差额秒数）、无未解决冲突（422）、未重复设置（409）。
     */
    private EmbargoRecord validateAndBuild(NormalizedItem item, String operator, String requestId, Instant now) {
        String observationId = item.observationId();
        ObservationSnapshot current = observationRepository.findCurrentForUpdate(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        if (current.deleted()) {
            throw ApiException.gone("observation already deleted: " + observationId);
        }
        Instant createdAtUtc = observationRepository.findCreatedAtUtc(observationId)
                .orElseThrow(() -> new IllegalStateException(
                        "created_at_utc missing for observation: " + observationId));
        if (item.embargoUntilUtc().isBefore(createdAtUtc)) {
            long shortfallSeconds = Duration.between(item.embargoUntilUtc(), createdAtUtc).getSeconds();
            throw ApiException.unprocessable(
                    "embargoUntilUtc " + item.embargoUntilUtc() + " is before cluster creation "
                            + createdAtUtc + " for observation " + observationId
                            + " (shortfall " + shortfallSeconds + " seconds)");
        }
        Optional<OpenConflictRecord> openConflict = openConflictRepository.find(observationId);
        if (openConflict.isPresent()) {
            OpenConflictRecord conflict = openConflict.get();
            throw ApiException.unprocessable(
                    "observation " + observationId + " has unresolved conflict on fields: "
                            + String.join(", ", conflict.conflictFields())
                            + " (detected at version " + conflict.currentVersion() + ")");
        }
        if (embargoRepository.find(observationId).isPresent()) {
            throw ApiException.conflict("embargo already set for observation: " + observationId, null);
        }
        return new EmbargoRecord(observationId, item.embargoUntilUtc(), writeJson(item.scope()),
                operator, requestId, current.version(), now);
    }

    /**
     * 规范化后的禁运项：披露范围已去重排序。
     */
    private record NormalizedItem(String observationId, Instant embargoUntilUtc, List<String> scope) {
    }

    /**
     * 批量设置结果：HTTP 状态码与响应体。
     */
    public record SetEmbargoOutcome(int status, SetEmbargoResponse body) {
    }

    // ---------- 公开查询与到期发布 ----------

    /**
     * 公开查询：未设禁运返回当前内容；截止前（左闭右开）只返回禁运元信息；
     * 到期后在同一事务内完成首次发布并返回不可变快照。重复读取不改变状态。
     */
    @Transactional
    public PublicObservationResponse publicGet(String observationId) {
        ObservationSnapshot current = observationRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        Optional<EmbargoRecord> embargoOpt = embargoRepository.find(observationId);
        if (embargoOpt.isEmpty()) {
            return PublicObservationResponse.publicView(current);
        }
        EmbargoRecord embargo = embargoOpt.get();
        Instant now = Instant.now(clock);
        if (now.isBefore(embargo.embargoUntilUtc())) {
            long remainingSeconds = Duration.between(now, embargo.embargoUntilUtc()).getSeconds();
            return PublicObservationResponse.embargoedView(embargo, readScope(embargo.disclosureScope()),
                    remainingSeconds);
        }
        ReleaseSnapshotRecord release = releaseIfDue(embargo, now);
        return PublicObservationResponse.releasedView(ReleaseResponse.of(release, objectMapper));
    }

    /**
     * 到期发布：已发布则直接返回首次快照（读取不改变状态）；否则对簇行加锁后生成唯一快照。
     * 与更正（合并/解决/删除）按提交顺序裁决：行锁保证发布读到的是已提交的最新胜出版本。
     */
    private ReleaseSnapshotRecord releaseIfDue(EmbargoRecord embargo, Instant now) {
        String observationId = embargo.observationId();
        Optional<ReleaseSnapshotRecord> existing = releaseRepository.findRelease(observationId);
        if (existing.isPresent()) {
            return existing.get();
        }
        ObservationSnapshot current = observationRepository.findCurrentForUpdate(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        List<ReleaseResponse.ResolutionSummary> summaries = resolutionRepository
                .findByObservationId(observationId).stream()
                .map(record -> new ReleaseResponse.ResolutionSummary(record.resolutionId(),
                        record.previousVersion(), record.newVersion(), record.operator(),
                        record.resolvedAtUtc()))
                .toList();
        List<String> qualityFlags = qualityFlags(current, embargo, summaries);
        ReleaseSnapshotRecord record = new ReleaseSnapshotRecord(
                "rel-" + observationId, observationId, embargo.embargoUntilUtc(), embargo.disclosureScope(),
                current.version(), current.location(), current.reading(), current.note(), current.deleted(),
                writeJson(summaries), writeJson(qualityFlags), now);
        try {
            releaseRepository.insertRelease(record);
            return record;
        } catch (DuplicateKeyException e) {
            // 同簇并发到期发布：唯一键串行化，后到者返回先提交者的唯一快照
            return releaseRepository.findRelease(observationId)
                    .orElseThrow(() -> ApiException.conflict(
                            "release conflict for observation: " + observationId, null));
        }
    }

    /**
     * 计算发布时的质量标记：DELETED（墓碑）、HAS_RESOLVED_CONFLICTS（存在冲突裁决）、
     * CORRECTED_AFTER_EMBARGO（设禁运后版本有推进）；均无则 CLEAN。
     */
    private List<String> qualityFlags(ObservationSnapshot current, EmbargoRecord embargo,
                                      List<ReleaseResponse.ResolutionSummary> summaries) {
        List<String> flags = new ArrayList<>();
        if (current.deleted()) {
            flags.add("DELETED");
        }
        if (!summaries.isEmpty()) {
            flags.add("HAS_RESOLVED_CONFLICTS");
        }
        if (current.version() > embargo.versionAtSet()) {
            flags.add("CORRECTED_AFTER_EMBARGO");
        }
        if (flags.isEmpty()) {
            flags.add("CLEAN");
        }
        return flags;
    }

    // ---------- 明细、历史与诊断查询 ----------

    /**
     * 禁运明细查询（授权）；未设置返回 404。
     */
    @Transactional(readOnly = true)
    public EmbargoDetailResponse getEmbargo(String observationId) {
        EmbargoRecord embargo = embargoRepository.find(observationId)
                .orElseThrow(() -> ApiException.notFound("embargo not found: " + observationId));
        return detailOf(embargo);
    }

    /**
     * 禁运诊断列表：按 observationId 字典序稳定排序。
     */
    @Transactional(readOnly = true)
    public List<EmbargoDetailResponse> listEmbargoes() {
        return embargoRepository.findAllOrdered().stream().map(this::detailOf).toList();
    }

    /**
     * 发布详情（授权证据查询）：首次发布快照 + 全部更正附页（按版本升序）；未发布返回 404。
     */
    @Transactional(readOnly = true)
    public ReleaseDetailResponse getReleaseDetail(String observationId) {
        ReleaseSnapshotRecord release = releaseRepository.findRelease(observationId)
                .orElseThrow(() -> ApiException.notFound("release not found: " + observationId));
        List<ReleaseDetailResponse.AddendumView> addenda = releaseRepository.findAddenda(observationId)
                .stream().map(ReleaseDetailResponse.AddendumView::of).toList();
        return new ReleaseDetailResponse(ReleaseResponse.of(release, objectMapper), addenda);
    }

    /**
     * 公开附页查询：仅在已发布后可见（按版本升序）；未发布返回 404。
     */
    @Transactional(readOnly = true)
    public List<ReleaseDetailResponse.AddendumView> listPublicAddenda(String observationId) {
        releaseRepository.findRelease(observationId)
                .orElseThrow(() -> ApiException.notFound("release not found: " + observationId));
        return releaseRepository.findAddenda(observationId).stream()
                .map(ReleaseDetailResponse.AddendumView::of).toList();
    }

    private EmbargoDetailResponse detailOf(EmbargoRecord embargo) {
        Optional<ReleaseSnapshotRecord> release = releaseRepository.findRelease(embargo.observationId());
        return new EmbargoDetailResponse(embargo.observationId(), embargo.embargoUntilUtc(),
                readScope(embargo.disclosureScope()), embargo.operator(), embargo.requestId(),
                embargo.versionAtSet(), embargo.setAtUtc(),
                release.isPresent() ? "RELEASED" : "PENDING",
                release.map(ReleaseSnapshotRecord::releaseId).orElse(null),
                release.map(ReleaseSnapshotRecord::releasedAtUtc).orElse(null));
    }

    // ---------- 发布后更正附页钩子 ----------

    /**
     * 发布后更正钩子（在调用方写事务内执行）：若簇已发布，为新版本追加不可变附页；
     * 首次快照不被改写。未发布或无新版本时不产生附页。
     */
    public void appendAddendumIfReleased(ObservationSnapshot version, String operation, String requestId) {
        releaseRepository.findRelease(version.observationId()).ifPresent(release -> {
            ReleaseAddendumRecord addendum = new ReleaseAddendumRecord(
                    version.observationId() + "@" + version.version(), version.observationId(),
                    release.releaseId(), version.version(), version.location(), version.reading(),
                    version.note(), version.deleted(), operation, requestId, Instant.now(clock));
            releaseRepository.insertAddendum(addendum);
        });
    }

    // ---------- 幂等与序列化辅助 ----------

    /**
     * 幂等检查：同键同参返回原成功结果；同键异参返回 409；无记录返回 null 继续执行。
     */
    private SetEmbargoOutcome checkReplay(String requestId, String fingerprint) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new SetEmbargoOutcome(entry.responseStatus(), readBody(entry.responseBody()));
    }

    /**
     * 占位写入去重记录，语义与其他写操作一致：并发同键时读已提交结果，同参重放、异参 409；
     * 业务失败时占位随事务回滚，不占键。
     */
    private SetEmbargoOutcome insertPlaceholder(String requestId, String fingerprint) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, "EMBARGO_SET");
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return new SetEmbargoOutcome(entry.responseStatus(), readBody(entry.responseBody()));
        }
    }

    /**
     * 业务成功后回填去重记录响应，与禁运设置在同一事务提交。
     */
    private SetEmbargoOutcome complete(String requestId, HttpStatus status, SetEmbargoResponse body) {
        requestLogRepository.complete(requestId, status.value(), writeJson(body));
        return new SetEmbargoOutcome(status.value(), body);
    }

    /**
     * 指纹：操作类型 + 操作者 + 规范化对象集合（JSON 转义序列化），SHA-256。
     */
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

    /**
     * 规范化对象集合的稳定 JSON 表示：按 observationId 升序，字段固定顺序。
     */
    private String writeItemsJson(List<NormalizedItem> items) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (NormalizedItem item : items) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("observationId", item.observationId());
            entry.put("embargoUntilUtc", item.embargoUntilUtc().toString());
            entry.put("disclosureScope", item.scope());
            normalized.add(entry);
        }
        return writeJson(normalized);
    }

    private List<String> readScope(String json) {
        try {
            return objectMapper.readValue(json, STRING_LIST_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored disclosure scope", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize embargo fields", e);
        }
    }

    private SetEmbargoResponse readBody(String json) {
        try {
            return objectMapper.readValue(json, SetEmbargoResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored response", e);
        }
    }
}
