package com.example.starter.service;

import com.example.starter.api.dto.CreateReviewRequest;
import com.example.starter.api.dto.CurrentReviewResponse;
import com.example.starter.api.dto.ReviewResponse;
import com.example.starter.dao.AirspaceDao;
import com.example.starter.dao.ReviewDao;
import com.example.starter.dao.RouteDao;
import com.example.starter.dao.ZoneDao;
import com.example.starter.domain.Conclusion;
import com.example.starter.domain.Geometry;
import com.example.starter.domain.ReviewRecord;
import com.example.starter.domain.RouteRecord;
import com.example.starter.domain.Zone;
import com.example.starter.error.ConflictException;
import com.example.starter.error.NotFoundException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 审核服务：在全局版本行与航线行排他锁内读取一致快照，计算结论并保存不可变结果。
 */
@Service
public class ReviewService {

    private final ReviewDao reviewDao;
    private final RouteDao routeDao;
    private final ZoneDao zoneDao;
    private final AirspaceDao airspaceDao;
    private final IdempotentExecutor idempotentExecutor;
    private final Clock clock;

    public ReviewService(ReviewDao reviewDao, RouteDao routeDao, ZoneDao zoneDao,
                         AirspaceDao airspaceDao, IdempotentExecutor idempotentExecutor,
                         Clock clock) {
        this.reviewDao = reviewDao;
        this.routeDao = routeDao;
        this.zoneDao = zoneDao;
        this.airspaceDao = airspaceDao;
        this.idempotentExecutor = idempotentExecutor;
        this.clock = clock;
    }

    /**
     * 提交审核：航线版本与空域版本须均为当前版本，否则 409；
     * 结论与命中区域基于锁内一致快照计算，结果不可变保存。
     */
    public ReviewResponse create(CreateReviewRequest request) {
        String fingerprint = "REVIEW|" + request.routeId() + "|" + request.routeVersion() + "|"
                + request.airspaceVersion();
        return idempotentExecutor.execute(request.requestId(), "REVIEW", fingerprint,
                ReviewResponse.class, () -> {
                    long airspaceVersion = airspaceDao.lockAndGetVersion();
                    if (airspaceVersion != request.airspaceVersion()) {
                        throw new ConflictException("AIRSPACE_VERSION_MISMATCH",
                                "空域版本不匹配：期望 " + request.airspaceVersion()
                                        + "，当前 " + airspaceVersion);
                    }
                    RouteRecord route = routeDao.lockById(request.routeId())
                            .orElseThrow(() -> new NotFoundException("ROUTE_NOT_FOUND",
                                    "航线不存在：" + request.routeId()));
                    if (route.version() != request.routeVersion()) {
                        throw new ConflictException("ROUTE_VERSION_MISMATCH",
                                "航线版本不匹配：期望 " + request.routeVersion()
                                        + "，当前 " + route.version());
                    }
                    List<Zone> activeZones = zoneDao.findActive();
                    List<String> hitZoneIds = activeZones.stream()
                            .filter(zone -> Geometry.routeIntersectsZone(route.points(), zone))
                            .map(Zone::zoneId)
                            .distinct()
                            .sorted()
                            .toList();
                    Conclusion conclusion = hitZoneIds.isEmpty()
                            ? Conclusion.CLEAR : Conclusion.BLOCKED;
                    ReviewRecord record = new ReviewRecord(
                            UUID.randomUUID().toString(),
                            route.routeId(),
                            route.version(),
                            airspaceVersion,
                            conclusion,
                            hitZoneIds,
                            Instant.now(clock));
                    reviewDao.insert(record, request.requestId());
                    return toResponse(record);
                });
    }

    /**
     * 按审核标识查询历史结果：永远返回提交时的原结论。
     */
    public ReviewResponse getById(String reviewId) {
        ReviewRecord record = reviewDao.findById(reviewId)
                .orElseThrow(() -> new NotFoundException("REVIEW_NOT_FOUND",
                        "审核结果不存在：" + reviewId));
        return toResponse(record);
    }

    /**
     * 查询航线当前可用结论：最近一次审核的航线版本与空域版本任一不再匹配即返回 STALE。
     */
    public CurrentReviewResponse currentForRoute(String routeId) {
        RouteRecord route = routeDao.findById(routeId)
                .orElseThrow(() -> new NotFoundException("ROUTE_NOT_FOUND",
                        "航线不存在：" + routeId));
        long airspaceVersion = airspaceDao.currentVersion();
        return reviewDao.findLatestByRoute(routeId)
                .filter(latest -> latest.routeVersion() == route.version()
                        && latest.airspaceVersion() == airspaceVersion)
                .map(latest -> CurrentReviewResponse.current(toResponse(latest)))
                .orElseGet(() -> CurrentReviewResponse.stale(route.version(), airspaceVersion));
    }

    private ReviewResponse toResponse(ReviewRecord record) {
        return new ReviewResponse(
                record.reviewId(),
                record.routeId(),
                record.routeVersion(),
                record.airspaceVersion(),
                record.conclusion().name(),
                record.hitZoneIds(),
                record.createdAt());
    }
}
