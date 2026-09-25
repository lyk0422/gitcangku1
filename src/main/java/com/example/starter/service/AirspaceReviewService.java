package com.example.starter.service;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.ReviewResultDto;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.RouteReplaceRequest;
import com.example.starter.api.dto.RouteResult;
import com.example.starter.api.dto.ZoneCreateRequest;
import com.example.starter.api.dto.ZoneResult;
import com.example.starter.api.dto.ZoneRevokeRequest;
import com.example.starter.domain.Geometry;
import com.example.starter.domain.Point;
import com.example.starter.domain.ReviewConclusion;
import com.example.starter.domain.ZoneStatus;
import com.example.starter.repo.AirspaceRepository;
import com.example.starter.repo.BandPo;
import com.example.starter.repo.BandRepository;
import com.example.starter.repo.ReviewPo;
import com.example.starter.repo.ReviewRepository;
import com.example.starter.repo.RoutePo;
import com.example.starter.repo.RouteRepository;
import com.example.starter.repo.VerticalDetailPo;
import com.example.starter.repo.ZonePo;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 禁飞区与航线审查业务服务。
 *
 * <p>所有写操作在同一数据库事务内完成业务变更与幂等去重记录的原子提交；
 * 失败（含业务冲突）回滚事务，不占用 requestId。</p>
 *
 * <p>审核事务先锁全局空域版本行、再锁航线行：区域变更事务必须更新版本行，
 * 因而与审核互斥，保证审核使用的空域版本号与全部禁飞区来自同一已提交状态。</p>
 *
 * <p>高度层语义（本题新增）：区域可登记左闭右开高度带。审查时仅对二维路径相交的
 * 区域进一步比较高度——巡航高度落入某高度带则记为垂直命中（verticalHit=true，
 * 容量在占用环节裁决，审查仍可 CLEAR）；二维相交但高度不相交则垂直分离
 * （verticalHit=false），该区域不拦截也不消耗容量。未登记任何高度带的区域保持
 * “全高度禁飞”旧语义：二维相交即 BLOCKED。未登记巡航高度的历史航线只做二维审查。</p>
 */
@Service
public class AirspaceReviewService {

    private final AirspaceRepository airspaceRepo;
    private final RouteRepository routeRepo;
    private final ReviewRepository reviewRepo;
    private final BandRepository bandRepo;
    private final IdempotencyService idempotency;
    private final Clock clock;
    private final TransactionTemplate txTemplate;

    public AirspaceReviewService(AirspaceRepository airspaceRepo,
                                 RouteRepository routeRepo,
                                 ReviewRepository reviewRepo,
                                 BandRepository bandRepo,
                                 IdempotencyService idempotency,
                                 Clock clock,
                                 PlatformTransactionManager transactionManager) {
        this.airspaceRepo = airspaceRepo;
        this.routeRepo = routeRepo;
        this.reviewRepo = reviewRepo;
        this.bandRepo = bandRepo;
        this.idempotency = idempotency;
        this.clock = clock;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    // ============================ 禁飞区 ============================

    /** 创建禁飞区；同键同参重放原结果，异参 409。 */
    public MutationResponse createZone(ZoneCreateRequest request) {
        return idempotency.execute(request.requestId(), IdempotencyService.KIND_ZONE_CREATE,
                idempotency.canonicalHash(request), () -> {
            if (request.xMin() >= request.xMax() || request.yMin() >= request.yMax()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ZONE_RECTANGLE",
                        "禁飞区必须是非退化轴对齐矩形：xMin < xMax 且 yMin < yMax");
            }
            if (airspaceRepo.findZone(request.zoneId()) != null) {
                throw new ApiException(HttpStatus.CONFLICT, "ZONE_ALREADY_EXISTS",
                        "禁飞区已存在: " + request.zoneId());
            }
            long newVersion = airspaceRepo.incrementGlobalVersion();
            airspaceRepo.insertZone(new ZonePo(request.zoneId(), request.xMin(), request.yMin(),
                    request.xMax(), request.yMax(), ZoneStatus.ACTIVE.name(), newVersion, null, 1));
            return new MutationResponse(request.requestId(), false,
                    new ZoneResult(request.zoneId(), ZoneStatus.ACTIVE.name(), newVersion));
        });
    }

    /** 撤销禁飞区（只能撤销一次，撤销使空域版本加一）。 */
    public MutationResponse revokeZone(ZoneRevokeRequest request) {
        return idempotency.execute(request.requestId(), IdempotencyService.KIND_ZONE_REVOKE,
                idempotency.canonicalHash(request), () -> {
            ZonePo zone = airspaceRepo.findZone(request.zoneId());
            if (zone == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "ZONE_NOT_FOUND",
                        "禁飞区不存在: " + request.zoneId());
            }
            if (ZoneStatus.REVOKED.name().equals(zone.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "ZONE_ALREADY_REVOKED",
                        "禁飞区已撤销: " + request.zoneId());
            }
            long newVersion = airspaceRepo.incrementGlobalVersion();
            airspaceRepo.markRevoked(request.zoneId(), newVersion);
            return new MutationResponse(request.requestId(), false,
                    new ZoneResult(request.zoneId(), ZoneStatus.REVOKED.name(), newVersion));
        });
    }

    // ============================ 航线 ============================

    /** 创建航线（初始版本 1），可携带巡航高度与 UTC 时段。 */
    public MutationResponse createRoute(RouteCreateRequest request) {
        return idempotency.execute(request.requestId(), IdempotencyService.KIND_ROUTE_CREATE,
                idempotency.canonicalHash(request), () -> {
            List<Point> points = toPoints(request.points());
            validatePointsDistinct(points);
            validateAltitudeWindow(request.cruiseAltitude(), request.startTime(), request.endTime());
            if (routeRepo.findRoute(request.routeId()) != null) {
                throw new ApiException(HttpStatus.CONFLICT, "ROUTE_ALREADY_EXISTS",
                        "航线已存在: " + request.routeId());
            }
            routeRepo.insertRoute(request.routeId(), points,
                    request.cruiseAltitude(), request.startTime(), request.endTime());
            return new MutationResponse(request.requestId(), false,
                    new RouteResult(request.routeId(), 1));
        });
    }

    /** 替换航线点列，expectedVersion 不匹配返回 409；成功版本加一。 */
    public MutationResponse replaceRoute(RouteReplaceRequest request) {
        return idempotency.execute(request.requestId(), IdempotencyService.KIND_ROUTE_REPLACE,
                idempotency.canonicalHash(request), () -> {
            List<Point> points = toPoints(request.points());
            validatePointsDistinct(points);
            RoutePo route = routeRepo.findRoute(request.routeId());
            if (route == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND",
                        "航线不存在: " + request.routeId());
            }
            if (route.version() != request.expectedVersion()) {
                throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                        "航线版本不匹配：expected=" + request.expectedVersion()
                                + ", current=" + route.version());
            }
            // 高度时段属性：请求全部缺省时保留原值；提供任一字段时必须三者齐全
            boolean keepAltitude = request.cruiseAltitude() == null
                    && request.startTime() == null && request.endTime() == null;
            Integer cruiseAltitude = keepAltitude ? route.cruiseAltitude() : request.cruiseAltitude();
            Long startTime = keepAltitude ? route.startTime() : request.startTime();
            Long endTime = keepAltitude ? route.endTime() : request.endTime();
            validateAltitudeWindow(cruiseAltitude, startTime, endTime);
            // 条件更新兜底并发替换：更新 0 行说明版本已被其他事务推进
            int updated = routeRepo.compareAndIncrementVersion(
                    request.routeId(), request.expectedVersion());
            if (updated == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                        "航线版本已变化，请使用最新 expectedVersion 重试");
            }
            routeRepo.replaceRouteAttributes(request.routeId(), points,
                    cruiseAltitude, startTime, endTime);
            return new MutationResponse(request.requestId(), false,
                    new RouteResult(request.routeId(), request.expectedVersion() + 1));
        });
    }

    // ============================ 审核 ============================

    /** 提交审核：任一指定版本不是当前版本返回 409；结果不可变。 */
    public MutationResponse review(ReviewRequest request) {
        return idempotency.execute(request.requestId(), IdempotencyService.KIND_REVIEW,
                idempotency.canonicalHash(request), () -> {
            // 先锁空域版本行：与任何区域创建/撤销/高度带配置事务互斥
            long globalVersion = airspaceRepo.getGlobalVersionForUpdate();
            // 再锁航线行：与航线替换事务互斥，保证版本号、点列与高度时段一致
            RoutePo route = routeRepo.findRouteForUpdate(request.routeId());
            if (route == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND",
                        "航线不存在: " + request.routeId());
            }
            if (route.version() != request.routeVersion()) {
                throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                        "航线版本不是当前版本：submitted=" + request.routeVersion()
                                + ", current=" + route.version());
            }
            if (globalVersion != request.airspaceVersion()) {
                throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                        "空域版本不是当前版本：submitted=" + request.airspaceVersion()
                                + ", current=" + globalVersion);
            }
            // 持锁状态下读取全部有效禁飞区，与上面的版本号同属一个一致状态
            List<ZonePo> activeZones = airspaceRepo.findActiveZones();
            Set<String> hits = new TreeSet<>();
            List<VerticalDetailPo> details = new ArrayList<>();
            boolean altitudeRoute = route.cruiseAltitude() != null;
            for (ZonePo zone : activeZones) {
                if (!Geometry.polylineHitsRectangle(route.points(),
                        zone.xMin(), zone.yMin(), zone.xMax(), zone.yMax())) {
                    continue;
                }
                List<BandPo> bands = bandRepo.findBands(zone.zoneId());
                if (!altitudeRoute || bands.isEmpty()) {
                    // 历史二维航线，或区域未登记高度带（全高度禁飞）：二维相交即拦截
                    hits.add(zone.zoneId());
                    continue;
                }
                // 高度层航线：仅比较二维相交且高度带相交的区域。
                // 命中容量管控高度带不直接 BLOCKED，记 verticalHit=true 供占用环节裁决容量；
                // 高度不相交记 verticalHit=false（垂直分离），不拦截不消耗容量。
                BandPo matched = matchBand(bands, route.cruiseAltitude());
                details.add(new VerticalDetailPo(null, zone.zoneId(),
                        matched == null ? null : matched.bandLower(),
                        matched == null ? null : matched.bandUpper(),
                        matched != null, details.size()));
            }
            String conclusion = hits.isEmpty()
                    ? ReviewConclusion.CLEAR.name()
                    : ReviewConclusion.BLOCKED.name();
            String reviewId = "rv_" + UUID.randomUUID();
            ReviewPo po = new ReviewPo(reviewId, request.routeId(), route.version(), globalVersion,
                    conclusion, new ArrayList<>(hits), List.copyOf(route.points()),
                    route.cruiseAltitude(), route.startTime(), route.endTime(),
                    request.requestId(), nowMillis());
            reviewRepo.insertReview(po);
            for (VerticalDetailPo detail : details) {
                reviewRepo.insertVerticalDetail(
                        new VerticalDetailPo(reviewId, detail.zoneId(), detail.bandLower(),
                                detail.bandUpper(), detail.verticalHit(), detail.detailSeq()));
            }
            return new MutationResponse(request.requestId(), false, toDto(po, conclusion, true));
        });
    }

    /** 按 reviewId 查询历史审核，保留原结论。 */
    public ReviewResultDto getReview(String reviewId) {
        ReviewPo po = reviewRepo.findReview(reviewId);
        if (po == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND",
                    "审核记录不存在: " + reviewId);
        }
        // 历史查询永远返回保存时的原结论，不重新计算
        return toDto(po, po.conclusion(), null);
    }

    /** 查询某航线当前可用结论；任何相关版本不再匹配返回 STALE。 */
    public ReviewResultDto getCurrentReview(String routeId) {
        return txTemplate.execute(status -> {
            ReviewPo latest = reviewRepo.findLatestReview(routeId);
            if (latest == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND",
                        "该航线尚无审核记录: " + routeId);
            }
            RoutePo currentRoute = routeRepo.findRoute(routeId);
            long globalVersion = airspaceRepo.getGlobalVersion();
            boolean current = currentRoute != null
                    && currentRoute.version() == latest.routeVersion()
                    && globalVersion == latest.airspaceVersion();
            // 不匹配时绝不能把旧 CLEAR/BLOCKED 当成当前结论，统一返回 STALE
            String conclusion = current ? latest.conclusion() : "STALE";
            return toDto(latest, conclusion, current);
        });
    }

    // ============================ 工具方法 ============================

    /** 在高度带列表中找出包含指定巡航高度的带（左闭右开），无命中返回 null。 */
    private static BandPo matchBand(List<BandPo> bands, int cruiseAltitude) {
        for (BandPo band : bands) {
            if (cruiseAltitude >= band.bandLower() && cruiseAltitude < band.bandUpper()) {
                return band;
            }
        }
        return null;
    }

    private static List<Point> toPoints(List<RoutePointDto> dtos) {
        List<Point> points = new ArrayList<>(dtos.size());
        for (RoutePointDto dto : dtos) {
            points.add(new Point(dto.x(), dto.y()));
        }
        return points;
    }

    private static void validatePointsDistinct(List<Point> points) {
        Point first = points.get(0);
        for (Point p : points) {
            if (p.x() != first.x() || p.y() != first.y()) {
                return;
            }
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "ROUTE_POINTS_IDENTICAL",
                "航线至少需要两个不同的点");
    }

    /**
     * 校验巡航高度与 UTC 时段：三者要么全部缺省（null，仅二维审查），
     * 要么全部提供，且时段为正长度（startTime &lt; endTime）。
     */
    static void validateAltitudeWindow(Integer cruiseAltitude, Long startTime, Long endTime) {
        boolean allNull = cruiseAltitude == null && startTime == null && endTime == null;
        boolean allPresent = cruiseAltitude != null && startTime != null && endTime != null;
        if (!allNull && !allPresent) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ALTITUDE_WINDOW_INCOMPLETE",
                    "巡航高度与 UTC 起止时刻必须同时提供或同时缺省");
        }
        if (allPresent && startTime >= endTime) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TIME_WINDOW",
                    "UTC 时段必须满足 startTime < endTime");
        }
    }

    private static ReviewResultDto toDto(ReviewPo po, String conclusion, Boolean current) {
        List<RoutePointDto> snapshot = new ArrayList<>(po.pointsSnapshot().size());
        for (Point p : po.pointsSnapshot()) {
            snapshot.add(new RoutePointDto(p.x(), p.y()));
        }
        return new ReviewResultDto(po.reviewId(), po.routeId(), po.routeVersion(),
                po.airspaceVersion(), conclusion, List.copyOf(po.hitZoneIds()), snapshot, current,
                po.cruiseAltitude(), po.startTime(), po.endTime());
    }

    private long nowMillis() {
        return clock.instant().toEpochMilli();
    }
}
