package com.example.starter.observation;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 观测地理围栏业务服务。
 *
 * <p>围栏写操作（创建/改版）在单事务内完成：先按站点锁行（geofence_site）串行化，
 * 再校验同站点当前生效围栏不与新配置在时间区间（UTC，左闭右开）与矩形区域上同时重叠，
 * 最后原子写入围栏指针与不可变配置版本。任何业务失败整体回滚，去重占位不占键。
 *
 * <p>经纬度范围、矩形边界方向或生效区间非法返回 422；同站点生效围栏重叠返回 409；
 * 改版指向不存在的围栏返回 404。requestId 同参重放、异参 409、失败不占键。
 */
@Service
public class GeofenceService {

    private final GeofenceRepository geofenceRepository;
    private final IdempotentRequests idempotentRequests;
    private final Fingerprinting fingerprinting;
    private final Clock clock;

    public GeofenceService(GeofenceRepository geofenceRepository,
                           IdempotentRequests idempotentRequests,
                           Fingerprinting fingerprinting,
                           Clock clock) {
        this.geofenceRepository = geofenceRepository;
        this.idempotentRequests = idempotentRequests;
        this.fingerprinting = fingerprinting;
        this.clock = clock;
    }

    /**
     * 创建围栏：初始配置版本为 1。fenceId 已存在返回 409；同站点生效围栏重叠返回 409。
     */
    @Transactional
    public GeofenceOutcome create(CreateGeofenceRequest request) {
        GeoValidation.validateFenceBounds(request.minLat(), request.maxLat(),
                request.minLon(), request.maxLon());
        GeoValidation.validateEffectiveInterval(request.effectiveFromUtc(), request.effectiveToUtc());

        String fingerprint = fingerprinting.of("FENCE_CREATE", request.operator(), request.fenceId(),
                request.siteId(), request.minLat(), request.maxLat(), request.minLon(), request.maxLon(),
                request.effectiveFromUtc(), request.effectiveToUtc());
        RequestLogEntry existing = idempotentRequests.findExistingOrThrow(request.requestId(), fingerprint);
        if (existing != null) {
            return replay(existing);
        }
        RequestLogEntry concurrent = idempotentRequests.insertPlaceholderOrNull(
                request.requestId(), fingerprint, "FENCE_CREATE");
        if (concurrent != null) {
            return replay(concurrent);
        }

        geofenceRepository.lockSiteForUpdate(request.siteId());
        if (geofenceRepository.findFence(request.fenceId()).isPresent()) {
            throw ApiException.conflict("geofence already exists: " + request.fenceId(), null);
        }
        checkOverlap(request.siteId(), request.fenceId(), request.minLat(), request.maxLat(),
                request.minLon(), request.maxLon(), request.effectiveFromUtc(), request.effectiveToUtc());
        try {
            geofenceRepository.insertFence(request.fenceId(), request.siteId());
        } catch (DuplicateKeyException e) {
            // 并发创建同一 fenceId 且站点不同（同站点已被锁行串行化）：主键冲突按 409 处理
            throw ApiException.conflict("geofence already exists: " + request.fenceId(), null);
        }
        GeofenceVersionRecord version = new GeofenceVersionRecord(
                request.fenceId(), request.siteId(), 1,
                request.minLat(), request.maxLat(), request.minLon(), request.maxLon(),
                request.effectiveFromUtc(), request.effectiveToUtc(),
                request.operator(), Instant.now(clock));
        geofenceRepository.insertVersion(version);
        return complete(request.requestId(), HttpStatus.CREATED, version);
    }

    /**
     * 围栏改版：追加不可变配置版本并推进当前指针；旧版本保留供历史提交证据查询。
     */
    @Transactional
    public GeofenceOutcome revise(String fenceId, ReviseGeofenceRequest request) {
        GeoValidation.validateFenceBounds(request.minLat(), request.maxLat(),
                request.minLon(), request.maxLon());
        GeoValidation.validateEffectiveInterval(request.effectiveFromUtc(), request.effectiveToUtc());

        String fingerprint = fingerprinting.of("FENCE_REVISE", request.operator(), fenceId,
                request.minLat(), request.maxLat(), request.minLon(), request.maxLon(),
                request.effectiveFromUtc(), request.effectiveToUtc());
        RequestLogEntry existing = idempotentRequests.findExistingOrThrow(request.requestId(), fingerprint);
        if (existing != null) {
            return replay(existing);
        }
        RequestLogEntry concurrent = idempotentRequests.insertPlaceholderOrNull(
                request.requestId(), fingerprint, "FENCE_REVISE");
        if (concurrent != null) {
            return replay(concurrent);
        }

        GeofenceFence fence = geofenceRepository.findFence(fenceId)
                .orElseThrow(() -> ApiException.notFound("geofence not found: " + fenceId));
        // 锁站点串行化兄弟围栏的并发配置变更，再锁围栏行串行化自身并发改版
        geofenceRepository.lockSiteForUpdate(fence.siteId());
        GeofenceFence locked = geofenceRepository.findFenceForUpdate(fenceId)
                .orElseThrow(() -> ApiException.notFound("geofence not found: " + fenceId));
        checkOverlap(locked.siteId(), fenceId, request.minLat(), request.maxLat(),
                request.minLon(), request.maxLon(), request.effectiveFromUtc(), request.effectiveToUtc());
        int newVersion = locked.currentVersion() + 1;
        GeofenceVersionRecord version = new GeofenceVersionRecord(
                fenceId, locked.siteId(), newVersion,
                request.minLat(), request.maxLat(), request.minLon(), request.maxLon(),
                request.effectiveFromUtc(), request.effectiveToUtc(),
                request.operator(), Instant.now(clock));
        geofenceRepository.insertVersion(version);
        geofenceRepository.updateCurrentVersion(fenceId, newVersion);
        return complete(request.requestId(), HttpStatus.OK, version);
    }

    /**
     * 查询围栏当前生效配置版本；围栏不存在返回 404。
     */
    @Transactional(readOnly = true)
    public GeofenceResponse getCurrent(String fenceId) {
        return GeofenceResponse.of(geofenceRepository.findCurrentVersion(fenceId)
                .orElseThrow(() -> ApiException.notFound("geofence not found: " + fenceId)));
    }

    /**
     * 查询围栏全部配置版本，按版本号升序；围栏不存在返回 404。
     */
    @Transactional(readOnly = true)
    public List<GeofenceVersionRecord> listVersions(String fenceId) {
        geofenceRepository.findFence(fenceId)
                .orElseThrow(() -> ApiException.notFound("geofence not found: " + fenceId));
        return geofenceRepository.findVersions(fenceId);
    }

    /**
     * 诊断查询：列出站点全部围栏的当前生效配置，按 fenceId 升序。
     */
    @Transactional(readOnly = true)
    public List<GeofenceVersionRecord> listSiteCurrent(String siteId) {
        return geofenceRepository.findCurrentVersionsBySite(siteId);
    }

    /**
     * 同站点生效围栏重叠校验：与其他围栏的当前版本在区域和生效区间上同时重叠则 409，
     * 错误信息携带首个冲突围栏标识与要求的不重叠条件。
     */
    private void checkOverlap(String siteId, String selfFenceId,
                              BigDecimal minLat, BigDecimal maxLat,
                              BigDecimal minLon, BigDecimal maxLon,
                              Instant effectiveFromUtc, Instant effectiveToUtc) {
        List<GeofenceVersionRecord> overlapping = geofenceRepository.findOverlapping(
                siteId, selfFenceId, minLat, maxLat, minLon, maxLon,
                effectiveFromUtc, effectiveToUtc);
        if (!overlapping.isEmpty()) {
            GeofenceVersionRecord other = overlapping.get(0);
            throw ApiException.conflict(
                    "active geofence overlaps at site " + siteId + ": existing fence " + other.fenceId()
                            + " (version " + other.version() + ", effective "
                            + other.effectiveFromUtc() + "/" + other.effectiveToUtc()
                            + ") overlaps new interval " + effectiveFromUtc + "/" + effectiveToUtc
                            + " and rectangular bounds", null);
        }
    }

    private GeofenceOutcome complete(String requestId, HttpStatus status, GeofenceVersionRecord version) {
        GeofenceResponse body = GeofenceResponse.of(version);
        idempotentRequests.complete(requestId, status.value(), body);
        return new GeofenceOutcome(status.value(), body);
    }

    private GeofenceOutcome replay(RequestLogEntry entry) {
        return new GeofenceOutcome(entry.responseStatus(),
                idempotentRequests.readStoredBody(entry, GeofenceResponse.class));
    }

    /**
     * 围栏写操作结果：HTTP 状态码与围栏配置响应体。
     */
    public record GeofenceOutcome(int status, GeofenceResponse body) {
    }
}
