package com.example.starter.observation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 站点矩形地理围栏配置服务。
 *
 * <p>围栏按 (fenceId, fenceVersion) 追加不可变版本行：创建为版本 1，变更为版本 +1，
 * 历史版本永不修改，后续围栏变更不重算历史提交的匹配结果。
 *
 * <p>写操作（创建/变更）均在单事务内完成：先占位写入幂等去重记录，再对站点锁行
 * （SELECT ... FOR UPDATE）串行化同站点并发写，校验经纬度范围、矩形边界与生效区间
 * （非法返回 422），并校验同站点各围栏当前版本生效区间不重叠（左闭右开，重叠返回 409）；
 * 任何失败整体回滚，去重记录不占键。
 */
@Service
public class GeofenceService {

    private final GeofenceRepository geofenceRepository;
    private final RequestLogRepository requestLogRepository;
    private final ObjectMapper objectMapper;

    public GeofenceService(GeofenceRepository geofenceRepository,
                           RequestLogRepository requestLogRepository,
                           ObjectMapper objectMapper) {
        this.geofenceRepository = geofenceRepository;
        this.requestLogRepository = requestLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建站点矩形围栏，初始版本为 1。围栏标识已存在返回 409。
     */
    @Transactional
    public GeofenceOutcome create(CreateGeofenceRequest request) {
        String fingerprint = fenceFingerprint("FENCE_CREATE", request.fenceId(), request.siteId(),
                null, request.minLatitude(), request.maxLatitude(), request.minLongitude(),
                request.maxLongitude(), request.effectiveFromUtc(), request.effectiveToUtc(), request.operator());
        GeofenceOutcome replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        GeofenceOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint, "FENCE_CREATE");
        if (concurrent != null) {
            return concurrent;
        }

        GeoValidation.requireValidFence(request.minLatitude(), request.maxLatitude(),
                request.minLongitude(), request.maxLongitude(),
                request.effectiveFromUtc(), request.effectiveToUtc());
        lockSite(request.siteId());
        // 站点锁内重查：并发同 fenceId 创建被串行化后，后到者按冲突处理而非主键异常
        if (geofenceRepository.findLatest(request.fenceId()).isPresent()) {
            throw ApiException.conflict("fence already exists: " + request.fenceId(), null);
        }
        checkOverlap(request.siteId(), null, request.effectiveFromUtc(), request.effectiveToUtc());

        GeofenceRecord record = new GeofenceRecord(request.fenceId(), 1, request.siteId(),
                request.minLatitude(), request.maxLatitude(), request.minLongitude(), request.maxLongitude(),
                request.effectiveFromUtc(), request.effectiveToUtc(), request.operator(), request.requestId());
        geofenceRepository.insert(record);
        return complete(request.requestId(), HttpStatus.CREATED, GeofenceResponse.of(record));
    }

    /**
     * 变更站点围栏：expectedVersion 匹配当前版本时追加新版本；历史版本保留，不重算历史匹配结果。
     */
    @Transactional
    public GeofenceOutcome update(String fenceId, UpdateGeofenceRequest request) {
        GeofenceRecord current = geofenceRepository.findLatest(fenceId)
                .orElseThrow(() -> ApiException.notFound("fence not found: " + fenceId));
        String fingerprint = fenceFingerprint("FENCE_UPDATE", fenceId, current.siteId(),
                request.expectedVersion(), request.minLatitude(), request.maxLatitude(),
                request.minLongitude(), request.maxLongitude(),
                request.effectiveFromUtc(), request.effectiveToUtc(), request.operator());
        GeofenceOutcome replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        GeofenceOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint, "FENCE_UPDATE");
        if (concurrent != null) {
            return concurrent;
        }

        GeoValidation.requireValidFence(request.minLatitude(), request.maxLatitude(),
                request.minLongitude(), request.maxLongitude(),
                request.effectiveFromUtc(), request.effectiveToUtc());
        lockSite(current.siteId());
        // 加锁后重读当前版本，保证版本判定与重叠校验基于最新已提交状态
        GeofenceRecord locked = geofenceRepository.findLatest(fenceId)
                .orElseThrow(() -> ApiException.notFound("fence not found: " + fenceId));
        if (request.expectedVersion() != locked.fenceVersion()) {
            throw ApiException.conflict("expectedVersion mismatch", locked.fenceVersion());
        }
        checkOverlap(locked.siteId(), fenceId, request.effectiveFromUtc(), request.effectiveToUtc());

        GeofenceRecord next = new GeofenceRecord(fenceId, locked.fenceVersion() + 1, locked.siteId(),
                request.minLatitude(), request.maxLatitude(), request.minLongitude(), request.maxLongitude(),
                request.effectiveFromUtc(), request.effectiveToUtc(), request.operator(), request.requestId());
        geofenceRepository.insert(next);
        return complete(request.requestId(), HttpStatus.OK, GeofenceResponse.of(next));
    }

    /**
     * 查询围栏当前版本；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public GeofenceResponse getCurrent(String fenceId) {
        GeofenceRecord record = geofenceRepository.findLatest(fenceId)
                .orElseThrow(() -> ApiException.notFound("fence not found: " + fenceId));
        return GeofenceResponse.of(record);
    }

    /**
     * 查询围栏指定历史版本；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public GeofenceResponse getVersion(String fenceId, int fenceVersion) {
        GeofenceRecord record = geofenceRepository.findVersion(fenceId, fenceVersion)
                .orElseThrow(() -> ApiException.notFound(
                        "fence version not found: " + fenceId + "@" + fenceVersion));
        return GeofenceResponse.of(record);
    }

    /**
     * 查询站点全部围栏的当前版本，按围栏标识稳定排序；查询不修改状态。
     */
    @Transactional(readOnly = true)
    public List<GeofenceResponse> listCurrentBySite(String siteId) {
        return currentFences(siteId).stream().map(GeofenceResponse::of).toList();
    }

    /**
     * 站点各围栏的当前版本集合（每围栏取最大版本号），按围栏标识稳定排序。
     */
    List<GeofenceRecord> currentFences(String siteId) {
        Map<String, GeofenceRecord> latest = new LinkedHashMap<>();
        for (GeofenceRecord record : geofenceRepository.findBySite(siteId)) {
            latest.merge(record.fenceId(), record,
                    (a, b) -> a.fenceVersion() >= b.fenceVersion() ? a : b);
        }
        return new ArrayList<>(latest.values());
    }

    /**
     * 对站点锁行加行锁：首行不存在时先插入（并发首插由主键串行化），再 SELECT ... FOR UPDATE。
     */
    private void lockSite(String siteId) {
        try {
            geofenceRepository.insertSiteLock(siteId);
        } catch (DuplicateKeyException e) {
            // 锁行已存在：直接加锁
        }
        geofenceRepository.lockSiteForUpdate(siteId);
    }

    /**
     * 校验新生效区间与同站点其他围栏当前版本的生效区间不重叠（左闭右开）；重叠返回 409。
     */
    private void checkOverlap(String siteId, String excludeFenceId, Instant from, Instant to) {
        for (GeofenceRecord other : currentFences(siteId)) {
            if (other.fenceId().equals(excludeFenceId)) {
                continue;
            }
            if (from.isBefore(other.effectiveToUtc()) && other.effectiveFromUtc().isBefore(to)) {
                throw ApiException.conflict(
                        "geofence effective interval overlaps with fence " + other.fenceId()
                                + " version " + other.fenceVersion() + " [" + other.effectiveFromUtc()
                                + ", " + other.effectiveToUtc() + ")",
                        null);
            }
        }
    }

    /**
     * 围栏写操作指纹：操作类型、围栏与站点标识、版本及全部影响结果的配置字段。
     */
    private String fenceFingerprint(String operation, String fenceId, String siteId, Integer expectedVersion,
                                    BigDecimal minLatitude, BigDecimal maxLatitude,
                                    BigDecimal minLongitude, BigDecimal maxLongitude,
                                    Instant effectiveFromUtc, Instant effectiveToUtc, String operator) {
        return Fingerprinting.of(Arrays.asList(
                operation, fenceId, siteId,
                expectedVersion == null ? null : String.valueOf(expectedVersion),
                minLatitude.toPlainString(), maxLatitude.toPlainString(),
                minLongitude.toPlainString(), maxLongitude.toPlainString(),
                effectiveFromUtc.toString(), effectiveToUtc.toString(), operator));
    }

    /**
     * 幂等检查：同键同参返回原成功结果；同键异参返回 409；无记录返回 null 继续执行。
     */
    private GeofenceOutcome checkReplay(String requestId, String fingerprint) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new GeofenceOutcome(entry.responseStatus(), readBody(entry.responseBody()));
    }

    /**
     * 占位写入去重记录；并发同键时主键冲突，等待对方事务结束后读取已提交结果：
     * 同参返回重放结果，异参抛 409；正常占位返回 null。业务失败时占位随事务回滚，不占键。
     */
    private GeofenceOutcome insertPlaceholder(String requestId, String fingerprint, String operation) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, operation);
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return new GeofenceOutcome(entry.responseStatus(), readBody(entry.responseBody()));
        }
    }

    /**
     * 业务成功后回填去重记录响应，并构造本次写操作结果；与业务变更同事务提交。
     */
    private GeofenceOutcome complete(String requestId, HttpStatus status, GeofenceResponse body) {
        try {
            requestLogRepository.complete(requestId, status.value(), objectMapper.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize geofence response", e);
        }
        return new GeofenceOutcome(status.value(), body);
    }

    private GeofenceResponse readBody(String json) {
        try {
            return objectMapper.readValue(json, GeofenceResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored geofence response", e);
        }
    }

    /**
     * 围栏写操作结果：HTTP 状态码与响应体。
     */
    public record GeofenceOutcome(int status, GeofenceResponse body) {
    }
}
