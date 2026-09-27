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
import java.util.List;
import java.util.Optional;

/**
 * 观测地理围栏业务服务：站点矩形围栏配置、离线观测批量提交（按采集时刻匹配围栏）、
 * 被拒提交的人工接受，以及明细/历史/诊断查询。
 *
 * <p>所有写操作沿用与观测合并一致的幂等框架：单事务内先占位 request_log（失败回滚不占键），
 * 再执行业务判定与变更，最后回填成功响应快照；同键同参重放返回首次完整响应，同键异参 409。
 * 指纹包含操作者、操作类型、规范化对象集合及全部影响结果的字段；集合元素先各自哈希（十六进制
 * 不含分隔符）再拼接，不直接拼接未转义的原始值。
 *
 * <p>时间统一 UTC；围栏生效区间按左闭右开 [effectiveFromUtc, effectiveToUtc) 解释，
 * 矩形边界含端点。批量提交先校验全部明细的完整最终状态（经纬度范围等），再在单事务内
 * 按提交顺序逐条裁决：落在围栏内的参与三方自动合并，落在围栏外的保存为 GEOFENCE_REJECTED
 * 原始证据；任一步失败整批回滚，查询不到半成品。
 *
 * <p>围栏配置、原始坐标、匹配结果与提交时刻不可变；后续新增/变更围栏不重算历史接受的提交。
 */
@Service
public class GeofenceService {

    private static final String SEPARATOR = "";

    private static final BigDecimal MIN_LATITUDE = new BigDecimal("-90");
    private static final BigDecimal MAX_LATITUDE = new BigDecimal("90");
    private static final BigDecimal MIN_LONGITUDE = new BigDecimal("-180");
    private static final BigDecimal MAX_LONGITUDE = new BigDecimal("180");

    private final GeofenceRepository geofenceRepository;
    private final GeofenceSubmissionRepository submissionRepository;
    private final RequestLogRepository requestLogRepository;
    private final ObservationService observationService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public GeofenceService(GeofenceRepository geofenceRepository,
                           GeofenceSubmissionRepository submissionRepository,
                           RequestLogRepository requestLogRepository,
                           ObservationService observationService,
                           ObjectMapper objectMapper,
                           Clock clock) {
        this.geofenceRepository = geofenceRepository;
        this.submissionRepository = submissionRepository;
        this.requestLogRepository = requestLogRepository;
        this.observationService = observationService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ---------- 围栏配置 ----------

    /**
     * 创建站点矩形围栏：经纬度范围、边界大小关系、生效区间非法返回 422；
     * 同站点生效区间重叠或 fenceId 已存在返回 409。
     */
    @Transactional
    public Outcome<GeofenceResponse> createGeofence(CreateGeofenceRequest request) {
        String fingerprint = fingerprint("GEOFENCE_CREATE", request.operator(), request.fenceId(),
                request.siteId(), request.minLat().toPlainString(), request.maxLat().toPlainString(),
                request.minLon().toPlainString(), request.maxLon().toPlainString(),
                request.effectiveFromUtc().toString(), request.effectiveToUtc().toString());
        Outcome<GeofenceResponse> replayed = replayIfPresent(
                request.requestId(), fingerprint, GeofenceResponse.class);
        if (replayed != null) {
            return replayed;
        }
        Outcome<GeofenceResponse> concurrent = insertPlaceholder(
                request.requestId(), fingerprint, "GEOFENCE_CREATE", GeofenceResponse.class);
        if (concurrent != null) {
            return concurrent;
        }

        validateLatLon(request.minLat(), request.minLon());
        validateLatLon(request.maxLat(), request.maxLon());
        if (request.minLat().compareTo(request.maxLat()) >= 0) {
            throw ApiException.unprocessableEntity("minLat must be less than maxLat: "
                    + request.minLat() + " vs " + request.maxLat());
        }
        if (request.minLon().compareTo(request.maxLon()) >= 0) {
            throw ApiException.unprocessableEntity("minLon must be less than maxLon: "
                    + request.minLon() + " vs " + request.maxLon());
        }
        if (!request.effectiveFromUtc().isBefore(request.effectiveToUtc())) {
            throw ApiException.unprocessableEntity("effectiveFromUtc must be before effectiveToUtc: "
                    + request.effectiveFromUtc() + " vs " + request.effectiveToUtc());
        }

        if (geofenceRepository.findByFenceId(request.fenceId()).isPresent()) {
            throw ApiException.conflict("geofence already exists: " + request.fenceId(), null);
        }
        List<Geofence> overlapping = geofenceRepository.findOverlapping(
                request.siteId(), request.effectiveFromUtc(), request.effectiveToUtc());
        if (!overlapping.isEmpty()) {
            throw ApiException.conflict("effective geofence overlaps with existing fence(s) at site "
                    + request.siteId() + ": " + overlapping.getFirst().fenceId(), null);
        }

        Geofence geofence = new Geofence(request.fenceId(), request.siteId(),
                request.minLat(), request.maxLat(), request.minLon(), request.maxLon(),
                request.effectiveFromUtc(), request.effectiveToUtc(), request.operator(),
                Instant.now(clock));
        try {
            geofenceRepository.insert(geofence);
        } catch (DuplicateKeyException e) {
            // 并发创建同一 fenceId：由主键串行化，后到者按冲突处理
            throw ApiException.conflict("geofence already exists: " + request.fenceId(), null);
        }
        return complete(request.requestId(), HttpStatus.CREATED, GeofenceResponse.of(geofence));
    }

    /**
     * 按 fenceId 查询围栏明细；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public GeofenceResponse getGeofence(String fenceId) {
        return geofenceRepository.findByFenceId(fenceId)
                .map(GeofenceResponse::of)
                .orElseThrow(() -> ApiException.notFound("geofence not found: " + fenceId));
    }

    /**
     * 按站点查询围栏历史，按生效起点与围栏标识稳定排序。
     */
    @Transactional(readOnly = true)
    public List<GeofenceResponse> listGeofences(String siteId) {
        return geofenceRepository.findBySiteId(siteId).stream()
                .map(GeofenceResponse::of)
                .toList();
    }

    // ---------- 离线观测批量提交 ----------

    /**
     * 批量离线提交：可混合站点，按 items 顺序裁决。任一明细经纬度越界整批 422 回滚；
     * 合并冲突、基线缺失、记录已删除等任一步失败同样整批回滚，不产生半成品。
     */
    @Transactional
    public Outcome<BatchGeofenceSubmissionResponse> submitBatch(BatchGeofenceSubmissionRequest request) {
        String fingerprint = batchFingerprint(request);
        Outcome<BatchGeofenceSubmissionResponse> replayed = replayIfPresent(
                request.requestId(), fingerprint, BatchGeofenceSubmissionResponse.class);
        if (replayed != null) {
            return replayed;
        }
        Outcome<BatchGeofenceSubmissionResponse> concurrent = insertPlaceholder(
                request.requestId(), fingerprint, "SUBMIT_BATCH", BatchGeofenceSubmissionResponse.class);
        if (concurrent != null) {
            return concurrent;
        }

        // 先校验完整最终状态：全部明细的经纬度范围合法后才允许任何写入
        for (GeofenceSubmissionItem item : request.items()) {
            validateLatLon(item.latitude(), item.longitude());
        }

        Instant submittedAt = Instant.now(clock);
        List<GeofenceSubmissionResponse> results = new ArrayList<>();
        int accepted = 0;
        int rejected = 0;
        int seq = 0;
        for (GeofenceSubmissionItem item : request.items()) {
            Optional<Geofence> fence = geofenceRepository.findEffectiveAt(item.siteId(), item.observedAtUtc());
            boolean outside = fence.isPresent()
                    && !inside(fence.get(), item.latitude(), item.longitude());
            String matchResult = outside ? GeofenceSubmission.GEOFENCE_REJECTED : GeofenceSubmission.ACCEPTED;
            Integer mergeVersion = null;
            if (!outside) {
                // 落在围栏内（或站点无生效围栏）：按提交顺序执行三方自动合并
                ObservationSnapshot merged = observationService.applyMerge(item.observationId(),
                        item.baseVersion(), item.location(), item.reading(), item.note());
                mergeVersion = merged.version();
                accepted++;
            } else {
                rejected++;
            }
            GeofenceSubmission submission = new GeofenceSubmission(item.submissionId(),
                    request.requestId(), seq, item.siteId(), item.observationId(), item.baseVersion(),
                    item.location(), item.reading(), item.note(), item.latitude(), item.longitude(),
                    item.observedAtUtc(), matchResult, fence.map(Geofence::fenceId).orElse(null),
                    matchResult, mergeVersion, request.operator(), submittedAt, null, null, null);
            try {
                submissionRepository.insert(submission);
            } catch (DuplicateKeyException e) {
                // submissionId 全局唯一：重复提交标识整批 409 回滚
                throw ApiException.conflict("submission already exists: " + item.submissionId(), null);
            }
            results.add(GeofenceSubmissionResponse.of(submission));
            seq++;
        }

        BatchGeofenceSubmissionResponse body = new BatchGeofenceSubmissionResponse(
                request.requestId(), request.items().size(), accepted, rejected, List.copyOf(results));
        return complete(request.requestId(), HttpStatus.CREATED, body);
    }

    /**
     * 人工接受被拒提交：必须填写理由；仅 GEOFENCE_REJECTED 状态可接受。
     * 接受时按提交保存的候选值与基线版本执行三方合并（冲突 409、记录删除 410），
     * 成功后原子写入裁决审计字段；原始证据不被修改。
     */
    @Transactional
    public Outcome<GeofenceSubmissionResponse> accept(String submissionId,
                                                      AcceptGeofenceSubmissionRequest request) {
        String fingerprint = fingerprint("MANUAL_ACCEPT", submissionId, request.operator(), request.reason());
        Outcome<GeofenceSubmissionResponse> replayed = replayIfPresent(
                request.requestId(), fingerprint, GeofenceSubmissionResponse.class);
        if (replayed != null) {
            return replayed;
        }
        Outcome<GeofenceSubmissionResponse> concurrent = insertPlaceholder(
                request.requestId(), fingerprint, "MANUAL_ACCEPT", GeofenceSubmissionResponse.class);
        if (concurrent != null) {
            return concurrent;
        }

        GeofenceSubmission submission = submissionRepository.findBySubmissionIdForUpdate(submissionId)
                .orElseThrow(() -> ApiException.notFound("geofence submission not found: " + submissionId));
        if (!GeofenceSubmission.GEOFENCE_REJECTED.equals(submission.matchResult())) {
            throw ApiException.conflict("submission was not geofence-rejected: " + submissionId, null);
        }
        if (GeofenceSubmission.MANUALLY_ACCEPTED.equals(submission.status())) {
            throw ApiException.conflict("submission already manually accepted: " + submissionId, null);
        }

        ObservationSnapshot merged = observationService.applyMerge(submission.observationId(),
                submission.baseVersion(), submission.candidateLocation(), submission.candidateReading(),
                submission.candidateNote());
        submissionRepository.markManuallyAccepted(submissionId, request.operator(),
                Instant.now(clock), request.reason(), merged.version());

        GeofenceSubmission updated = submissionRepository.findBySubmissionId(submissionId)
                .orElseThrow(() -> new IllegalStateException("submission lost after accept: " + submissionId));
        return complete(request.requestId(), HttpStatus.OK, GeofenceSubmissionResponse.of(updated));
    }

    // ---------- 明细 / 历史 / 诊断查询（只读，不改变状态） ----------

    /**
     * 按 submissionId 查询提交明细（含不可变原始证据）；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public GeofenceSubmissionResponse getSubmission(String submissionId) {
        return submissionRepository.findBySubmissionId(submissionId)
                .map(GeofenceSubmissionResponse::of)
                .orElseThrow(() -> ApiException.notFound("geofence submission not found: " + submissionId));
    }

    /**
     * 按站点查询提交历史，按受理时刻与提交标识稳定排序。
     */
    @Transactional(readOnly = true)
    public List<GeofenceSubmissionResponse> listSubmissions(String siteId) {
        return submissionRepository.findBySiteId(siteId).stream()
                .map(GeofenceSubmissionResponse::of)
                .toList();
    }

    /**
     * 站点提交诊断：各状态数量均为实际值，total 为三者之和。
     */
    @Transactional(readOnly = true)
    public GeofenceDiagnosticsResponse diagnostics(String siteId) {
        long accepted = submissionRepository.countBySiteIdAndStatus(siteId, GeofenceSubmission.ACCEPTED);
        long rejected = submissionRepository.countBySiteIdAndStatus(siteId, GeofenceSubmission.GEOFENCE_REJECTED);
        long manuallyAccepted = submissionRepository.countBySiteIdAndStatus(
                siteId, GeofenceSubmission.MANUALLY_ACCEPTED);
        return new GeofenceDiagnosticsResponse(siteId, accepted + rejected + manuallyAccepted,
                accepted, rejected, manuallyAccepted);
    }

    // ---------- 内部辅助 ----------

    /**
     * 校验经纬度范围：纬度 [-90,90]、经度 [-180,180]，越界返回 422。
     */
    private void validateLatLon(BigDecimal latitude, BigDecimal longitude) {
        if (latitude.compareTo(MIN_LATITUDE) < 0 || latitude.compareTo(MAX_LATITUDE) > 0) {
            throw ApiException.unprocessableEntity("latitude out of range [-90,90]: " + latitude);
        }
        if (longitude.compareTo(MIN_LONGITUDE) < 0 || longitude.compareTo(MAX_LONGITUDE) > 0) {
            throw ApiException.unprocessableEntity("longitude out of range [-180,180]: " + longitude);
        }
    }

    /**
     * 判断采集点是否落在矩形围栏内（边界含端点）。
     */
    private boolean inside(Geofence fence, BigDecimal latitude, BigDecimal longitude) {
        return latitude.compareTo(fence.minLat()) >= 0 && latitude.compareTo(fence.maxLat()) <= 0
                && longitude.compareTo(fence.minLon()) >= 0 && longitude.compareTo(fence.maxLon()) <= 0;
    }

    /**
     * 批量提交指纹：每条明细先独立哈希（十六进制串不含分隔符），再按提交顺序拼接，
     * 集合不通过未转义分隔符直接拼接原始值；指纹包含操作者与全部影响结果的字段。
     */
    private String batchFingerprint(BatchGeofenceSubmissionRequest request) {
        List<String> itemHashes = new ArrayList<>();
        for (GeofenceSubmissionItem item : request.items()) {
            itemHashes.add(fingerprint("ITEM", item.submissionId(), item.siteId(), item.observationId(),
                    String.valueOf(item.baseVersion()), item.location(), item.reading(), item.note(),
                    item.latitude().toPlainString(), item.longitude().toPlainString(),
                    item.observedAtUtc().toString()));
        }
        return fingerprint("SUBMIT_BATCH", request.operator(), String.join(SEPARATOR, itemHashes));
    }

    /**
     * 幂等检查：同键同参返回首次成功响应快照；同键异参返回 409；无记录返回 null 继续执行。
     */
    private <T> Outcome<T> replayIfPresent(String requestId, String fingerprint, Class<T> bodyType) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new Outcome<>(entry.responseStatus(), readBody(entry.responseBody(), bodyType));
    }

    /**
     * 占位写入去重记录；并发同键时主键冲突，重读已提交结果：同参重放、异参 409。
     * 业务失败时占位随事务回滚，不占用该键。
     */
    private <T> Outcome<T> insertPlaceholder(String requestId, String fingerprint, String operation,
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
            return new Outcome<>(entry.responseStatus(), readBody(entry.responseBody(), bodyType));
        }
    }

    /**
     * 业务成功后回填去重记录响应快照，与业务变更在同一事务内提交。
     */
    private <T> Outcome<T> complete(String requestId, HttpStatus status, T body) {
        requestLogRepository.complete(requestId, status.value(), writeBody(body));
        return new Outcome<>(status.value(), body);
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
     *
     * @param status HTTP 状态码
     * @param body   响应体
     * @param <T>    响应体类型
     */
    public record Outcome<T>(int status, T body) {
    }
}
