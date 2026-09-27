package com.example.starter.observation.geofence;

import com.example.starter.observation.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 观测地理围栏业务服务。
 *
 * <p>写操作（围栏配置/变更、观测批量提交、人工裁决）均在单事务内完成：先占位写入幂等去重记录，
 * 再做语义校验（422）与状态判定（404/409），最后回填去重记录的响应并整体提交；
 * 任何失败都会回滚，去重记录不占键，不留下部分结果。
 *
 * <p>围栏匹配：按采集时刻（UTC，区间左闭右开）匹配站点生效围栏版本；落在矩形外保存为
 * GEOFENCE_REJECTED，不参与自动合并但保留原始证据；采集时刻站点无生效围栏时按 ACCEPTED 落库，
 * 匹配围栏字段为 null。匹配结果、原始坐标与提交时刻不可变，后续改围栏不重算历史结果。
 *
 * <p>幂等：指纹由操作者、操作类型、规范化集合（批量条目按观测标识排序）及所有影响结果字段
 * 序列化为规范化 JSON 后取 SHA-256，不使用分隔符拼接；同键同参重放返回首次完整响应快照，
 * 同键异参返回 409。
 */
@Service
public class GeofenceService {

    /**
     * 批量提交的条数上限。
     */
    static final int MAX_BATCH_SIZE = 100;

    private static final BigDecimal MIN_LATITUDE = new BigDecimal("-90");
    private static final BigDecimal MAX_LATITUDE = new BigDecimal("90");
    private static final BigDecimal MIN_LONGITUDE = new BigDecimal("-180");
    private static final BigDecimal MAX_LONGITUDE = new BigDecimal("180");
    private static final int COORDINATE_SCALE = 7;

    private final GeofenceRepository geofenceRepository;
    private final GeofenceObservationRepository observationRepository;
    private final GeofenceAdjudicationRepository adjudicationRepository;
    private final GeofenceRequestLogRepository requestLogRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public GeofenceService(GeofenceRepository geofenceRepository,
                           GeofenceObservationRepository observationRepository,
                           GeofenceAdjudicationRepository adjudicationRepository,
                           GeofenceRequestLogRepository requestLogRepository,
                           ObjectMapper objectMapper,
                           Clock clock) {
        this.geofenceRepository = geofenceRepository;
        this.observationRepository = observationRepository;
        this.adjudicationRepository = adjudicationRepository;
        this.requestLogRepository = requestLogRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ---------- 围栏配置与变更 ----------

    /**
     * 创建围栏，初始版本为 1。语义非法返回 422；围栏已存在或同站点生效区间重叠返回 409。
     */
    @Transactional
    public GeofenceOutcome<FenceResponse> createFence(CreateFenceRequest request) {
        String fingerprint = fingerprint("FENCE_CREATE", request.operator(),
                fenceParamsFingerprint(request.fenceId(), request.siteId(), request.minLatitude(),
                        request.maxLatitude(), request.minLongitude(), request.maxLongitude(),
                        request.effectiveFromUtc(), request.effectiveToUtc()));
        GeofenceOutcome<FenceResponse> replayed = checkReplay(request.requestId(), fingerprint, FenceResponse.class);
        if (replayed != null) {
            return replayed;
        }
        GeofenceOutcome<FenceResponse> concurrent =
                insertPlaceholder(request.requestId(), fingerprint, "FENCE_CREATE", FenceResponse.class);
        if (concurrent != null) {
            return concurrent;
        }

        ValidatedFence params = validateFenceParams(request.minLatitude(), request.maxLatitude(),
                request.minLongitude(), request.maxLongitude(),
                request.effectiveFromUtc(), request.effectiveToUtc());

        geofenceRepository.lockSite(request.siteId());
        if (geofenceRepository.existsFence(request.fenceId())) {
            throw ApiException.conflict("fence already exists: " + request.fenceId(), null);
        }
        checkOverlap(request.siteId(), params);

        FenceVersionRecord record = new FenceVersionRecord(request.fenceId(), 1, request.siteId(),
                params.minLatitude(), params.maxLatitude(), params.minLongitude(), params.maxLongitude(),
                params.effectiveFromUtc(), params.effectiveToUtc(), request.operator(), request.requestId());
        geofenceRepository.insertVersion(record);
        return complete(request.requestId(), HttpStatus.CREATED.value(), FenceResponse.of(record));
    }

    /**
     * 变更围栏：追加一个不可变新版本。围栏不存在返回 404；语义非法返回 422；
     * 新区间与同站点任一历史生效版本重叠返回 409。历史匹配结果不重算。
     */
    @Transactional
    public GeofenceOutcome<FenceResponse> updateFence(String fenceId, UpdateFenceRequest request) {
        FenceVersionRecord latest = geofenceRepository.findLatestVersion(fenceId)
                .orElseThrow(() -> ApiException.notFound("fence not found: " + fenceId));
        String fingerprint = fingerprint("FENCE_UPDATE", request.operator(),
                fenceParamsFingerprint(fenceId, latest.siteId(), request.minLatitude(), request.maxLatitude(),
                        request.minLongitude(), request.maxLongitude(),
                        request.effectiveFromUtc(), request.effectiveToUtc()));
        GeofenceOutcome<FenceResponse> replayed = checkReplay(request.requestId(), fingerprint, FenceResponse.class);
        if (replayed != null) {
            return replayed;
        }
        GeofenceOutcome<FenceResponse> concurrent =
                insertPlaceholder(request.requestId(), fingerprint, "FENCE_UPDATE", FenceResponse.class);
        if (concurrent != null) {
            return concurrent;
        }

        ValidatedFence params = validateFenceParams(request.minLatitude(), request.maxLatitude(),
                request.minLongitude(), request.maxLongitude(),
                request.effectiveFromUtc(), request.effectiveToUtc());

        geofenceRepository.lockSite(latest.siteId());
        // 行锁内重读最新版本，保证版本号基于提交时的最新状态
        FenceVersionRecord locked = geofenceRepository.findLatestVersion(fenceId)
                .orElseThrow(() -> ApiException.notFound("fence not found: " + fenceId));
        checkOverlap(locked.siteId(), params);

        FenceVersionRecord record = new FenceVersionRecord(fenceId, locked.fenceVersion() + 1, locked.siteId(),
                params.minLatitude(), params.maxLatitude(), params.minLongitude(), params.maxLongitude(),
                params.effectiveFromUtc(), params.effectiveToUtc(), request.operator(), request.requestId());
        geofenceRepository.insertVersion(record);
        return complete(request.requestId(), HttpStatus.CREATED.value(), FenceResponse.of(record));
    }

    /**
     * 查询围栏最新版本；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public FenceResponse getFence(String fenceId) {
        return FenceResponse.of(geofenceRepository.findLatestVersion(fenceId)
                .orElseThrow(() -> ApiException.notFound("fence not found: " + fenceId)));
    }

    /**
     * 查询围栏全部版本，按版本号升序；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public List<FenceResponse> listFenceVersions(String fenceId) {
        List<FenceVersionRecord> versions = geofenceRepository.findVersions(fenceId);
        if (versions.isEmpty()) {
            throw ApiException.notFound("fence not found: " + fenceId);
        }
        return versions.stream().map(FenceResponse::of).toList();
    }

    /**
     * 查询站点全部围栏版本，按生效起始时刻、围栏标识、版本号升序；无记录返回空列表。
     */
    @Transactional(readOnly = true)
    public List<FenceResponse> listSiteFences(String siteId) {
        return geofenceRepository.findBySite(siteId).stream().map(FenceResponse::of).toList();
    }

    // ---------- 离线观测批量提交 ----------

    /**
     * 批量提交离线观测：先验证全部条目的完整后态（格式 422、标识冲突 409），
     * 再在一个事务内落库；任一格式错误整批回滚，不写部分结果。
     */
    @Transactional
    public GeofenceOutcome<BatchObservationResponse> submitBatch(BatchObservationRequest request) {
        String fingerprint = fingerprint("OBS_BATCH", request.operator(), batchFingerprint(request.items()));
        GeofenceOutcome<BatchObservationResponse> replayed =
                checkReplay(request.requestId(), fingerprint, BatchObservationResponse.class);
        if (replayed != null) {
            return replayed;
        }
        GeofenceOutcome<BatchObservationResponse> concurrent =
                insertPlaceholder(request.requestId(), fingerprint, "OBS_BATCH", BatchObservationResponse.class);
        if (concurrent != null) {
            return concurrent;
        }

        List<ValidatedItem> validated = validateBatch(request.items());

        List<String> ids = validated.stream().map(item -> item.item().observationId()).toList();
        List<String> existing = observationRepository.findExistingIds(ids);
        if (!existing.isEmpty()) {
            throw ApiException.conflict("observation already exists: " + String.join(", ", existing), null);
        }

        Instant submittedAtUtc = Instant.now(clock);
        List<ObservationEvidenceRecord> records = new ArrayList<>();
        List<BatchObservationResponse.Item> items = new ArrayList<>();
        int acceptedCount = 0;
        int rejectedCount = 0;
        for (int seq = 0; seq < validated.size(); seq++) {
            ValidatedItem item = validated.get(seq);
            Optional<FenceVersionRecord> fence =
                    geofenceRepository.findEffective(item.item().siteId(), item.observedAtUtc());
            ObservationStatus matchResult = ObservationStatus.ACCEPTED;
            String matchedFenceId = null;
            Integer matchedFenceVersion = null;
            if (fence.isPresent()) {
                matchedFenceId = fence.get().fenceId();
                matchedFenceVersion = fence.get().fenceVersion();
                if (!insideFence(fence.get(), item.item().latitude(), item.item().longitude())) {
                    matchResult = ObservationStatus.GEOFENCE_REJECTED;
                }
            }
            if (matchResult == ObservationStatus.ACCEPTED) {
                acceptedCount++;
            } else {
                rejectedCount++;
            }
            records.add(new ObservationEvidenceRecord(item.item().observationId(), request.requestId(), seq,
                    item.item().siteId(), item.item().latitude(), item.item().longitude(), item.observedAtUtc(),
                    item.item().payload(), matchResult.name(), matchResult.name(),
                    matchedFenceId, matchedFenceVersion, request.operator(), submittedAtUtc));
            items.add(new BatchObservationResponse.Item(item.item().observationId(), item.item().siteId(),
                    matchResult.name(), matchResult.name(), matchedFenceId, matchedFenceVersion));
        }

        try {
            for (ObservationEvidenceRecord record : records) {
                observationRepository.insert(record);
            }
        } catch (DuplicateKeyException e) {
            // 并发批量提交同一 observationId：由主键串行化，后到者整批按冲突处理
            throw ApiException.conflict("observation already exists (concurrent submission)", null);
        }

        BigDecimal acceptedRatio = BigDecimal.valueOf(acceptedCount)
                .divide(BigDecimal.valueOf(validated.size()), 4, RoundingMode.HALF_UP);
        BatchObservationResponse body = new BatchObservationResponse(request.requestId(), request.operator(),
                validated.size(), acceptedCount, rejectedCount, acceptedRatio, submittedAtUtc, items);
        return complete(request.requestId(), HttpStatus.CREATED.value(), body);
    }

    /**
     * 查询观测证据明细；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public ObservationEvidenceResponse getObservation(String observationId) {
        return ObservationEvidenceResponse.of(observationRepository.find(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId)));
    }

    /**
     * 按站点查询观测证据，可按状态过滤；按提交时刻、观测标识升序，查询不修改状态。
     */
    @Transactional(readOnly = true)
    public List<ObservationEvidenceResponse> listSiteObservations(String siteId, String status) {
        if (status != null && !status.isBlank()) {
            try {
                ObservationStatus.valueOf(status);
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("unknown observation status: " + status);
            }
            return observationRepository.findBySite(siteId, status).stream()
                    .map(ObservationEvidenceResponse::of).toList();
        }
        return observationRepository.findBySite(siteId, null).stream()
                .map(ObservationEvidenceResponse::of).toList();
    }

    // ---------- 人工裁决 ----------

    /**
     * 人工接受一条被围栏拒绝的观测：必须填写理由；观测不处于 GEOFENCE_REJECTED 返回 409。
     * adjudicationId 全局唯一，同参重放返回原结果，改参 409。
     */
    @Transactional
    public GeofenceOutcome<AdjudicationResponse> adjudicate(String observationId, AdjudicateRequest request) {
        String reason = request.reason() == null ? null : request.reason().trim();
        String fingerprint = fingerprint("ADJUDICATE", request.operator(),
                adjudicationFingerprint(observationId, request.adjudicationId(), reason));
        GeofenceOutcome<AdjudicationResponse> replayed =
                checkReplay(request.requestId(), fingerprint, AdjudicationResponse.class);
        if (replayed != null) {
            return replayed;
        }
        GeofenceOutcome<AdjudicationResponse> concurrent =
                insertPlaceholder(request.requestId(), fingerprint, "ADJUDICATE", AdjudicationResponse.class);
        if (concurrent != null) {
            return concurrent;
        }

        if (reason == null || reason.isEmpty()) {
            throw new GeofenceValidationException("adjudication reason is required",
                    List.of(new ValidationDetail("reason", reason, "非空裁决理由", null)));
        }

        ObservationEvidenceRecord observation = observationRepository.findForUpdate(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));

        // adjudicationId 重放判定先于状态校验：同参重放始终返回原结果；改参或跨观测复用一律 409。
        Optional<AdjudicationRecord> existing =
                adjudicationRepository.findByAdjudicationId(request.adjudicationId());
        if (existing.isPresent()) {
            AdjudicationRecord record = existing.get();
            if (!sameAdjudicationParams(record, observationId, reason, request.operator())) {
                throw ApiException.conflict(
                        "adjudicationId reused with different parameters: " + request.adjudicationId(), null);
            }
            return complete(request.requestId(), HttpStatus.OK.value(),
                    AdjudicationResponse.of(record, observation.status()));
        }

        if (!ObservationStatus.GEOFENCE_REJECTED.name().equals(observation.status())) {
            throw ApiException.conflict(
                    "observation is not in GEOFENCE_REJECTED status: " + observationId, null);
        }

        AdjudicationRecord record = new AdjudicationRecord(request.adjudicationId(), observationId,
                request.requestId(), "ACCEPT", reason, request.operator(), Instant.now(clock));
        try {
            adjudicationRepository.insert(record);
        } catch (DuplicateKeyException e) {
            // 并发裁决：adjudicationId 或 observationId 唯一键冲突，重读后同参重放，否则 409。
            AdjudicationRecord winner = adjudicationRepository.findByAdjudicationId(request.adjudicationId())
                    .orElseGet(() -> adjudicationRepository.findByObservationId(observationId)
                            .orElseThrow(() -> ApiException.conflict(
                                    "adjudication conflict: " + request.adjudicationId(), null)));
            if (!sameAdjudicationParams(winner, observationId, reason, request.operator())) {
                throw ApiException.conflict(
                        "adjudicationId reused with different parameters: " + request.adjudicationId(), null);
            }
            return complete(request.requestId(), HttpStatus.OK.value(),
                    AdjudicationResponse.of(winner, ObservationStatus.ACCEPTED.name()));
        }
        observationRepository.updateStatus(observationId, ObservationStatus.ACCEPTED.name());
        return complete(request.requestId(), HttpStatus.OK.value(),
                AdjudicationResponse.of(record, ObservationStatus.ACCEPTED.name()));
    }

    /**
     * 按全局唯一 adjudicationId 查询裁决记录；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public AdjudicationResponse getAdjudication(String adjudicationId) {
        AdjudicationRecord record = adjudicationRepository.findByAdjudicationId(adjudicationId)
                .orElseThrow(() -> ApiException.notFound("adjudication not found: " + adjudicationId));
        String status = observationRepository.find(record.observationId())
                .map(ObservationEvidenceRecord::status)
                .orElseThrow(() -> new IllegalStateException(
                        "observation missing for adjudication: " + adjudicationId));
        return AdjudicationResponse.of(record, status);
    }

    /**
     * 按观测标识查询裁决历史，按裁决时刻升序；观测不存在返回 404。
     */
    @Transactional(readOnly = true)
    public List<AdjudicationResponse> listAdjudications(String observationId) {
        ObservationEvidenceRecord observation = observationRepository.find(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        return adjudicationRepository.listByObservationId(observationId).stream()
                .map(record -> AdjudicationResponse.of(record, observation.status()))
                .toList();
    }

    // ---------- 校验辅助 ----------

    /**
     * 校验围栏边界与生效区间，收集全部语义错误后一次性抛出 422。
     */
    private ValidatedFence validateFenceParams(BigDecimal minLatitude, BigDecimal maxLatitude,
                                               BigDecimal minLongitude, BigDecimal maxLongitude,
                                               String effectiveFromUtc, String effectiveToUtc) {
        List<ValidationDetail> details = new ArrayList<>();
        checkCoordinate(details, "minLatitude", minLatitude, MIN_LATITUDE, MAX_LATITUDE);
        checkCoordinate(details, "maxLatitude", maxLatitude, MIN_LATITUDE, MAX_LATITUDE);
        checkCoordinate(details, "minLongitude", minLongitude, MIN_LONGITUDE, MAX_LONGITUDE);
        checkCoordinate(details, "maxLongitude", maxLongitude, MIN_LONGITUDE, MAX_LONGITUDE);
        if (minLatitude.compareTo(maxLatitude) >= 0) {
            details.add(new ValidationDetail("latitudeBoundary",
                    minLatitude.toPlainString() + " .. " + maxLatitude.toPlainString(),
                    "minLatitude < maxLatitude（矩形边界合法）", null));
        }
        if (minLongitude.compareTo(maxLongitude) >= 0) {
            details.add(new ValidationDetail("longitudeBoundary",
                    minLongitude.toPlainString() + " .. " + maxLongitude.toPlainString(),
                    "minLongitude < maxLongitude（矩形边界合法）", null));
        }
        Instant from = parseInstant(details, "effectiveFromUtc", effectiveFromUtc);
        Instant to = parseInstant(details, "effectiveToUtc", effectiveToUtc);
        if (from != null && to != null && !from.isBefore(to)) {
            Duration actual = Duration.between(from, to);
            details.add(new ValidationDetail("effectiveInterval",
                    actual.toString(), "effectiveFromUtc < effectiveToUtc（正时长，左闭右开）", null));
        }
        if (!details.isEmpty()) {
            throw new GeofenceValidationException("invalid geofence parameters", details);
        }
        return new ValidatedFence(minLatitude, maxLatitude, minLongitude, maxLongitude, from, to);
    }

    /**
     * 校验批量条目：数量、坐标范围与精度、采集时刻格式、批内标识唯一；收集全部错误后一次性抛出 422。
     */
    private List<ValidatedItem> validateBatch(List<ObservationItemRequest> items) {
        List<ValidationDetail> details = new ArrayList<>();
        if (items.isEmpty() || items.size() > MAX_BATCH_SIZE) {
            details.add(new ValidationDetail("items", String.valueOf(items.size()),
                    "1.." + MAX_BATCH_SIZE, null));
        }
        Set<String> seen = new HashSet<>();
        List<ValidatedItem> validated = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            ObservationItemRequest item = items.get(i);
            String prefix = "items[" + i + "]";
            checkCoordinate(details, prefix + ".latitude", item.latitude(), MIN_LATITUDE, MAX_LATITUDE);
            checkCoordinate(details, prefix + ".longitude", item.longitude(), MIN_LONGITUDE, MAX_LONGITUDE);
            Instant observedAt = parseInstant(details, prefix + ".observedAtUtc", item.observedAtUtc());
            if (!seen.add(item.observationId())) {
                details.add(new ValidationDetail(prefix + ".observationId", item.observationId(),
                        "批量内唯一", null));
            }
            validated.add(new ValidatedItem(item, observedAt));
        }
        if (!details.isEmpty()) {
            throw new GeofenceValidationException("invalid observation batch", details);
        }
        return validated;
    }

    /**
     * 校验坐标范围与小数精度；越界时给出与最近边界的差额。
     */
    private void checkCoordinate(List<ValidationDetail> details, String field, BigDecimal value,
                                 BigDecimal min, BigDecimal max) {
        if (value.compareTo(min) < 0) {
            details.add(new ValidationDetail(field, value.toPlainString(),
                    "[" + min.toPlainString() + ", " + max.toPlainString() + "]",
                    value.subtract(min).toPlainString()));
        } else if (value.compareTo(max) > 0) {
            details.add(new ValidationDetail(field, value.toPlainString(),
                    "[" + min.toPlainString() + ", " + max.toPlainString() + "]",
                    value.subtract(max).toPlainString()));
        }
        if (value.scale() > COORDINATE_SCALE) {
            details.add(new ValidationDetail(field, value.toPlainString(),
                    "最多 " + COORDINATE_SCALE + " 位小数", null));
        }
    }

    /**
     * 解析 UTC 时刻（ISO-8601）；格式非法时记录 422 明细并返回 null。
     */
    private Instant parseInstant(List<ValidationDetail> details, String field, String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            details.add(new ValidationDetail(field, value, "UTC ISO-8601 时刻，如 2026-09-28T01:00:00Z", null));
            return null;
        }
    }

    /**
     * 同站点生效区间重叠检查：任一历史版本与新区间重叠返回 409。
     */
    private void checkOverlap(String siteId, ValidatedFence params) {
        List<FenceVersionRecord> overlapping = geofenceRepository.findOverlapping(
                siteId, params.effectiveFromUtc(), params.effectiveToUtc());
        if (!overlapping.isEmpty()) {
            FenceVersionRecord first = overlapping.get(0);
            throw ApiException.conflict("effective interval overlaps with existing fence "
                    + first.fenceId() + "@" + first.fenceVersion() + " on site " + siteId, null);
        }
    }

    /**
     * 判断坐标是否落在矩形围栏内（边界含端点）。
     */
    private boolean insideFence(FenceVersionRecord fence, BigDecimal latitude, BigDecimal longitude) {
        return latitude.compareTo(fence.minLatitude()) >= 0 && latitude.compareTo(fence.maxLatitude()) <= 0
                && longitude.compareTo(fence.minLongitude()) >= 0
                && longitude.compareTo(fence.maxLongitude()) <= 0;
    }

    /**
     * 判断本次裁决参数与已存在裁决记录是否完全一致（用于 adjudicationId 同参重放）。
     */
    private boolean sameAdjudicationParams(AdjudicationRecord record, String observationId,
                                           String reason, String operator) {
        return Objects.equals(record.observationId(), observationId)
                && Objects.equals(record.reason(), reason)
                && Objects.equals(record.operator(), operator);
    }

    // ---------- 幂等与指纹 ----------

    /**
     * 幂等检查：同键同参返回原成功结果快照；同键异参返回 409；无记录返回 null 继续执行。
     */
    private <T> GeofenceOutcome<T> checkReplay(String requestId, String fingerprint, Class<T> bodyType) {
        Optional<GeofenceRequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        GeofenceRequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new GeofenceOutcome<>(entry.responseStatus(), readBody(entry.responseBody(), bodyType));
    }

    /**
     * 占位写入去重记录；并发同键时主键冲突，等待对方事务结束后读取已提交结果：
     * 同参返回重放结果，异参抛 409；正常占位返回 null。业务失败时占位随事务回滚，不占键。
     */
    private <T> GeofenceOutcome<T> insertPlaceholder(String requestId, String fingerprint, String operation,
                                                     Class<T> bodyType) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, operation);
            return null;
        } catch (DuplicateKeyException e) {
            GeofenceRequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return new GeofenceOutcome<>(entry.responseStatus(), readBody(entry.responseBody(), bodyType));
        }
    }

    /**
     * 业务成功后回填去重记录响应，并构造本次写操作结果；与业务变更同事务提交。
     */
    private <T> GeofenceOutcome<T> complete(String requestId, int status, T body) {
        requestLogRepository.complete(requestId, status, writeBody(body));
        return new GeofenceOutcome<>(status, body);
    }

    /**
     * 构造指纹：操作类型、操作者与规范化参数集合序列化为 JSON 后取 SHA-256；
     * 参数以 JSON 结构承载，不使用分隔符拼接。
     */
    private String fingerprint(String operation, String operator, Map<String, Object> params) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("operation", operation);
        canonical.put("operator", operator);
        canonical.put("params", params);
        try {
            String json = objectMapper.writeValueAsString(canonical);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize fingerprint", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * 围栏参数的规范化指纹结构：坐标统一为纯数字字符串，时刻解析为 UTC 原文（无法解析时保留原值）。
     */
    private Map<String, Object> fenceParamsFingerprint(String fenceId, String siteId,
                                                       BigDecimal minLatitude, BigDecimal maxLatitude,
                                                       BigDecimal minLongitude, BigDecimal maxLongitude,
                                                       String effectiveFromUtc, String effectiveToUtc) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("fenceId", fenceId);
        params.put("siteId", siteId);
        params.put("minLatitude", normalizeCoordinate(minLatitude));
        params.put("maxLatitude", normalizeCoordinate(maxLatitude));
        params.put("minLongitude", normalizeCoordinate(minLongitude));
        params.put("maxLongitude", normalizeCoordinate(maxLongitude));
        params.put("effectiveFromUtc", normalizeInstant(effectiveFromUtc));
        params.put("effectiveToUtc", normalizeInstant(effectiveToUtc));
        return params;
    }

    /**
     * 批量条目的规范化指纹结构：条目按观测标识排序，字段顺序固定。
     */
    private Map<String, Object> batchFingerprint(List<ObservationItemRequest> items) {
        List<Map<String, Object>> normalized = items.stream()
                .sorted(Comparator.comparing(ObservationItemRequest::observationId))
                .map(item -> {
                    Map<String, Object> entry = new LinkedHashMap<String, Object>();
                    entry.put("observationId", item.observationId());
                    entry.put("siteId", item.siteId());
                    entry.put("latitude", normalizeCoordinate(item.latitude()));
                    entry.put("longitude", normalizeCoordinate(item.longitude()));
                    entry.put("observedAtUtc", normalizeInstant(item.observedAtUtc()));
                    entry.put("payload", item.payload());
                    return entry;
                })
                .toList();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("items", normalized);
        return params;
    }

    /**
     * 裁决参数的规范化指纹结构。
     */
    private Map<String, Object> adjudicationFingerprint(String observationId, String adjudicationId, String reason) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("observationId", observationId);
        params.put("adjudicationId", adjudicationId);
        params.put("reason", reason);
        return params;
    }

    private String normalizeCoordinate(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private String normalizeInstant(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value).toString();
        } catch (DateTimeParseException e) {
            return value;
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
     * 校验通过的围栏参数。
     */
    private record ValidatedFence(BigDecimal minLatitude, BigDecimal maxLatitude,
                                  BigDecimal minLongitude, BigDecimal maxLongitude,
                                  Instant effectiveFromUtc, Instant effectiveToUtc) {
    }

    /**
     * 校验通过的批量条目及其采集时刻。
     */
    private record ValidatedItem(ObservationItemRequest item, Instant observedAtUtc) {
    }

    /**
     * 写操作结果：HTTP 状态码与响应体。
     */
    public record GeofenceOutcome<T>(int status, T body) {
    }
}
