package com.example.starter.service;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.BatchReviewRequest;
import com.example.starter.api.dto.BatchReviewResult;
import com.example.starter.api.dto.FlightPlanDto;
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
import com.example.starter.domain.FlightPlan;
import com.example.starter.domain.Geometry;
import com.example.starter.domain.Point;
import com.example.starter.domain.ReviewConclusion;
import com.example.starter.domain.ReviewReason;
import com.example.starter.domain.RouteCategory;
import com.example.starter.domain.RouteStatus;
import com.example.starter.domain.ZoneStatus;
import com.example.starter.repo.AirspaceRepository;
import com.example.starter.repo.ClosurePo;
import com.example.starter.repo.ReviewPo;
import com.example.starter.repo.ReviewRepository;
import com.example.starter.repo.RoutePo;
import com.example.starter.repo.RouteRepository;
import com.example.starter.repo.RunwayPo;
import com.example.starter.repo.RunwayRepository;
import com.example.starter.repo.ZonePo;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 禁飞区与航线审查业务服务。
 *
 * <p>所有写操作在同一数据库事务内完成业务变更与幂等去重记录的原子提交；
 * 失败（含业务冲突）回滚事务，不占用请求键。</p>
 *
 * <p>审核事务先锁全局空域版本行、再锁航线行：区域变更事务必须更新版本行，
 * 因而与审核互斥，保证审核使用的空域版本号与全部禁飞区来自同一已提交状态，
 * 不会产生“携带新版本、使用旧区域”或反之的结论。跑道关闭变更、起飞与改航
 * 同样先更新协调锁行，与审核按提交顺序串行裁决。</p>
 *
 * <p>审查时额外执行跑道关闭与容量联动：NORMAL 航线起降段与关闭窗口相交即 422；
 * EMERGENCY 仅在窗口允许例外且附事件号时通过；已批准航线的跑道小时容量
 * 超限时 422。批量审查先计算全部航线的最终容量与关闭影响，任一拒绝则整批
 * 不批准（事务回滚，不留半成品状态）。</p>
 */
@Service
public class AirspaceReviewService {

    static final String KIND_ZONE_CREATE = "ZONE_CREATE";
    static final String KIND_ZONE_REVOKE = "ZONE_REVOKE";
    static final String KIND_ROUTE_CREATE = "ROUTE_CREATE";
    static final String KIND_ROUTE_REPLACE = "ROUTE_REPLACE";
    static final String KIND_REVIEW = "REVIEW";
    static final String KIND_REVIEW_BATCH = "REVIEW_BATCH";

    /** 一小时毫秒数：跑道容量按 UTC 小时桶统计。 */
    static final long HOUR_MILLIS = 3_600_000L;

    private final AirspaceRepository airspaceRepo;
    private final RouteRepository routeRepo;
    private final ReviewRepository reviewRepo;
    private final RunwayRepository runwayRepo;
    private final IdempotencySupport idempotency;
    private final Clock clock;

    public AirspaceReviewService(AirspaceRepository airspaceRepo,
                                 RouteRepository routeRepo,
                                 ReviewRepository reviewRepo,
                                 RunwayRepository runwayRepo,
                                 IdempotencySupport idempotency,
                                 Clock clock) {
        this.airspaceRepo = airspaceRepo;
        this.routeRepo = routeRepo;
        this.reviewRepo = reviewRepo;
        this.runwayRepo = runwayRepo;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    // ============================ 禁飞区 ============================

    /** 创建禁飞区；同键同参重放原结果，异参 409。 */
    public MutationResponse createZone(ZoneCreateRequest request) {
        return idempotency.execute(request.requestId(), KIND_ZONE_CREATE, request, () -> {
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
                    request.xMax(), request.yMax(), ZoneStatus.ACTIVE.name(), newVersion, null));
            return new MutationResponse(request.requestId(), false,
                    new ZoneResult(request.zoneId(), ZoneStatus.ACTIVE.name(), newVersion));
        });
    }

    /** 撤销禁飞区（只能撤销一次，撤销使空域版本加一）。 */
    public MutationResponse revokeZone(ZoneRevokeRequest request) {
        return idempotency.execute(request.requestId(), KIND_ZONE_REVOKE, request, () -> {
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

    /** 创建航线（初始版本 1，状态 DRAFT），可携带起降计划。 */
    public MutationResponse createRoute(RouteCreateRequest request) {
        return idempotency.execute(request.requestId(), KIND_ROUTE_CREATE, request, () -> {
            List<Point> points = toPoints(request.points());
            validatePointsDistinct(points);
            FlightPlan plan = toFlightPlan(request.flightPlan());
            if (routeRepo.findRoute(request.routeId()) != null) {
                throw new ApiException(HttpStatus.CONFLICT, "ROUTE_ALREADY_EXISTS",
                        "航线已存在: " + request.routeId());
            }
            routeRepo.insertRoute(request.routeId(), points, plan);
            return new MutationResponse(request.requestId(), false,
                    new RouteResult(request.routeId(), 1));
        });
    }

    /**
     * 替换航线点列（风险航线改航也使用本接口）。expectedVersion 不匹配返回 409；
     * 成功版本加一、状态回到 DRAFT 并清除跑道风险快照。
     */
    public MutationResponse replaceRoute(RouteReplaceRequest request) {
        return idempotency.execute(request.requestId(), KIND_ROUTE_REPLACE, request, () -> {
            List<Point> points = toPoints(request.points());
            validatePointsDistinct(points);
            RoutePo route = routeRepo.findRoute(request.routeId());
            if (route == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND",
                        "航线不存在: " + request.routeId());
            }
            ensureMutable(route);
            FlightPlan plan = toFlightPlan(request.flightPlan());
            if (route.version() != request.expectedVersion()) {
                throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                        "航线版本不匹配：expected=" + request.expectedVersion()
                                + ", current=" + route.version());
            }
            // 条件更新兜底并发替换：更新 0 行说明版本已被其他事务推进
            int updated = routeRepo.compareAndIncrementVersion(
                    request.routeId(), request.expectedVersion(), plan);
            if (updated == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                        "航线版本已变化，请使用最新 expectedVersion 重试");
            }
            routeRepo.deletePoints(request.routeId());
            routeRepo.insertPoints(request.routeId(), points);
            // 改航后原风险快照不再适用
            runwayRepo.deleteRisk(request.routeId());
            return new MutationResponse(request.requestId(), false,
                    new RouteResult(request.routeId(), request.expectedVersion() + 1));
        });
    }

    // ============================ 审核 ============================

    /** 提交审核：任一指定版本不是当前版本返回 409；结果不可变。 */
    public MutationResponse review(ReviewRequest request) {
        return idempotency.execute(request.requestId(), KIND_REVIEW, request, () -> {
            List<ReviewPo> reviewed = executeReview(request.airspaceVersion(),
                    List.of(new ReviewItem(request.routeId(), request.routeVersion())),
                    request.requestId());
            ReviewPo po = reviewed.get(0);
            return new MutationResponse(request.requestId(), false,
                    toDto(po, po.conclusion(), true));
        });
    }

    /**
     * 批量审核：先计算全部航线的最终容量与关闭影响，任一拒绝则整批不批准
     * （事务回滚，不产生任何审查记录或状态变更）。
     */
    public MutationResponse reviewBatch(BatchReviewRequest request) {
        return idempotency.execute(request.requestId(), KIND_REVIEW_BATCH, request, () -> {
            List<ReviewItem> items = new ArrayList<>(request.items().size());
            for (BatchReviewRequest.BatchReviewItem item : request.items()) {
                items.add(new ReviewItem(item.routeId(), item.routeVersion()));
            }
            List<ReviewPo> reviewed = executeReview(request.airspaceVersion(), items,
                    request.requestId());
            List<ReviewResultDto> dtos = new ArrayList<>(reviewed.size());
            for (ReviewPo po : reviewed) {
                dtos.add(toDto(po, po.conclusion(), true));
            }
            return new MutationResponse(request.requestId(), false, new BatchReviewResult(dtos));
        });
    }

    /**
     * 审核事务主体（单条与批量共用）。先锁空域版本行与全部航线行，
     * 再在持锁状态下计算禁飞区命中、关闭窗口影响与最终容量；
     * 全部通过校验后才统一落库审查结果并推进航线状态。
     */
    private List<ReviewPo> executeReview(long submittedAirspaceVersion, List<ReviewItem> items,
                                         String requestId) {
        // 先锁空域版本行：与任何区域创建/撤销、关闭变更、起飞、改航事务互斥
        long globalVersion = airspaceRepo.getGlobalVersionForUpdate();
        if (globalVersion != submittedAirspaceVersion) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                    "空域版本不是当前版本：submitted=" + submittedAirspaceVersion
                            + ", current=" + globalVersion);
        }
        // 第一阶段：锁定并校验全部航线，计算关闭影响与最终容量，不写入任何状态
        Set<String> seen = new HashSet<>();
        List<RoutePo> routes = new ArrayList<>(items.size());
        List<String> batchRouteIds = new ArrayList<>(items.size());
        for (ReviewItem item : items) {
            if (!seen.add(item.routeId())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "DUPLICATE_ROUTE_IN_BATCH",
                        "批量审核中航线重复: " + item.routeId());
            }
            batchRouteIds.add(item.routeId());
            // 再锁航线行：与航线替换事务互斥，保证版本号与点列一致
            RoutePo route = routeRepo.findRouteForUpdate(item.routeId());
            if (route == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND",
                        "航线不存在: " + item.routeId());
            }
            if (route.version() != item.routeVersion()) {
                throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                        "航线版本不是当前版本：submitted=" + item.routeVersion()
                                + ", current=" + route.version() + ", route=" + item.routeId());
            }
            ensureReviewable(route);
            routes.add(route);
        }
        // 持锁状态下读取全部有效禁飞区，与上面的版本号同属一个一致状态
        List<ZonePo> activeZones = airspaceRepo.findActiveZones();
        Map<String, Integer> slotUsage = new HashMap<>();
        Map<String, RunwayPo> runwayCache = new HashMap<>();
        List<ReviewPo> results = new ArrayList<>(routes.size());
        for (RoutePo route : routes) {
            Set<String> hits = new TreeSet<>();
            for (ZonePo zone : activeZones) {
                if (Geometry.polylineHitsRectangle(route.points(),
                        zone.xMin(), zone.yMin(), zone.xMax(), zone.yMax())) {
                    hits.add(zone.zoneId());
                }
            }
            FlightPlan plan = route.flightPlan();
            Set<String> exceptionClosures = new TreeSet<>();
            if (plan != null) {
                // 关闭影响：NORMAL 相交即 422；EMERGENCY 需窗口允许例外且附事件号
                checkClosureImpact(plan.depRunwayId(), plan.depTimeUtc(), plan,
                        exceptionClosures);
                checkClosureImpact(plan.arrRunwayId(), plan.arrTimeUtc(), plan,
                        exceptionClosures);
                if (hits.isEmpty()) {
                    // 最终容量：仅对将通过的航线占用跑道小时容量
                    reserveCapacity(plan.depRunwayId(), plan.depTimeUtc(), batchRouteIds,
                            slotUsage, runwayCache);
                    reserveCapacity(plan.arrRunwayId(), plan.arrTimeUtc(), batchRouteIds,
                            slotUsage, runwayCache);
                }
            }
            String conclusion = hits.isEmpty()
                    ? ReviewConclusion.CLEAR.name()
                    : ReviewConclusion.BLOCKED.name();
            String reasonCode = !hits.isEmpty()
                    ? ReviewReason.ZONE_HIT.name()
                    : (exceptionClosures.isEmpty()
                            ? ReviewReason.CLEAR.name()
                            : ReviewReason.EMERGENCY_EXCEPTION.name());
            // 批量时各航线审查记录携带派生请求标识，保持 review.request_id 唯一
            String reviewRequestId = items.size() > 1
                    ? requestId + "#" + route.routeId()
                    : requestId;
            results.add(new ReviewPo("rv_" + UUID.randomUUID(), route.routeId(), route.version(),
                    globalVersion, conclusion, reasonCode, new ArrayList<>(hits),
                    new ArrayList<>(exceptionClosures), List.copyOf(route.points()),
                    reviewRequestId, clock.instant().toEpochMilli()));
        }
        // 第二阶段：全部校验通过，统一落库并推进状态
        for (ReviewPo po : results) {
            reviewRepo.insertReview(po);
            routeRepo.updateStatus(po.routeId(), ReviewConclusion.CLEAR.name().equals(po.conclusion())
                    ? RouteStatus.APPROVED.name()
                    : RouteStatus.DRAFT.name());
        }
        return results;
    }

    /** 单航段关闭影响检查：命中关闭窗口时按航线类别裁决。 */
    private void checkClosureImpact(String runwayId, Long timeUtc, FlightPlan plan,
                                    Set<String> exceptionClosures) {
        if (runwayId == null || timeUtc == null) {
            return;
        }
        List<ClosurePo> closures = runwayRepo.findClosuresAt(runwayId, timeUtc);
        if (closures.isEmpty()) {
            return;
        }
        if (!RouteCategory.EMERGENCY.name().equals(plan.category())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "RUNWAY_CLOSED",
                    "NORMAL 航线起降段与跑道关闭窗口相交: runway=" + runwayId
                            + ", closures=" + closureIds(closures));
        }
        if (plan.eventNo() == null || plan.eventNo().isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "EMERGENCY_EVENT_NO_REQUIRED",
                    "EMERGENCY 航线通过关闭窗口必须附事件号: runway=" + runwayId);
        }
        for (ClosurePo closure : closures) {
            if (!closure.allowEmergency()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "EMERGENCY_EXCEPTION_NOT_ALLOWED",
                        "关闭窗口不允许紧急例外: " + closure.closureId());
            }
            exceptionClosures.add(closure.closureId());
        }
    }

    /** 单航段容量占用：跑道小时桶内已批准架次（含本批）达到容量即 422。 */
    private void reserveCapacity(String runwayId, Long timeUtc, List<String> batchRouteIds,
                                 Map<String, Integer> slotUsage,
                                 Map<String, RunwayPo> runwayCache) {
        if (runwayId == null || timeUtc == null) {
            return;
        }
        long bucketStart = Math.floorDiv(timeUtc, HOUR_MILLIS) * HOUR_MILLIS;
        String key = runwayId + "|" + bucketStart;
        Integer used = slotUsage.get(key);
        if (used == null) {
            used = runwayRepo.countApprovedSegments(runwayId, bucketStart,
                    bucketStart + HOUR_MILLIS, batchRouteIds);
        }
        RunwayPo runway = runwayCache.get(runwayId);
        if (runway == null) {
            runway = runwayRepo.findRunway(runwayId);
            if (runway == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "RUNWAY_NOT_FOUND",
                        "跑道不存在: " + runwayId);
            }
            runwayCache.put(runwayId, runway);
        }
        if (used + 1 > runway.capacityPerHour()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CAPACITY_EXCEEDED",
                    "跑道小时容量超限: runway=" + runwayId + ", bucketStart=" + bucketStart
                            + ", capacity=" + runway.capacityPerHour());
        }
        slotUsage.put(key, used + 1);
    }

    private static void ensureReviewable(RoutePo route) {
        if (RouteStatus.RUNWAY_RISK.name().equals(route.status())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ROUTE_RUNWAY_RISK",
                    "跑道风险航线不能普通再次批准，只能改航、取消或转合格紧急例外: " + route.routeId());
        }
        ensureMutable(route);
    }

    private static void ensureMutable(RoutePo route) {
        if (RouteStatus.DEPARTED.name().equals(route.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "ROUTE_ALREADY_DEPARTED",
                    "航线已起飞，不可变更: " + route.routeId());
        }
        if (RouteStatus.CANCELLED.name().equals(route.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "ROUTE_ALREADY_CANCELLED",
                    "航线已取消，不可变更: " + route.routeId());
        }
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
        return idempotency.readOnly(() -> {
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

    /** 校验并转换起降计划；引用的跑道必须已登记。 */
    private FlightPlan toFlightPlan(FlightPlanDto dto) {
        if (dto == null) {
            return null;
        }
        String category = dto.category() == null ? RouteCategory.NORMAL.name() : dto.category();
        if (!RouteCategory.NORMAL.name().equals(category)
                && !RouteCategory.EMERGENCY.name().equals(category)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ROUTE_CATEGORY",
                    "航线类别必须是 NORMAL 或 EMERGENCY: " + category);
        }
        validateSegment(dto.depRunwayId(), dto.depTimeUtc(), "dep");
        validateSegment(dto.arrRunwayId(), dto.arrTimeUtc(), "arr");
        if (dto.depRunwayId() != null && runwayRepo.findRunway(dto.depRunwayId()) == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RUNWAY_NOT_FOUND",
                    "跑道不存在: " + dto.depRunwayId());
        }
        if (dto.arrRunwayId() != null && runwayRepo.findRunway(dto.arrRunwayId()) == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RUNWAY_NOT_FOUND",
                    "跑道不存在: " + dto.arrRunwayId());
        }
        return new FlightPlan(category, dto.eventNo(), dto.depRunwayId(), dto.depTimeUtc(),
                dto.arrRunwayId(), dto.arrTimeUtc());
    }

    private static void validateSegment(String runwayId, Long timeUtc, String segment) {
        if ((runwayId == null) != (timeUtc == null)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FLIGHT_PLAN",
                    "起降计划的跑道标识与时刻必须同时提供: " + segment);
        }
    }

    private static String closureIds(List<ClosurePo> closures) {
        List<String> ids = new ArrayList<>(closures.size());
        for (ClosurePo c : closures) {
            ids.add(c.closureId());
        }
        return ids.toString();
    }

    private static ReviewResultDto toDto(ReviewPo po, String conclusion, Boolean current) {
        List<RoutePointDto> snapshot = new ArrayList<>(po.pointsSnapshot().size());
        for (Point p : po.pointsSnapshot()) {
            snapshot.add(new RoutePointDto(p.x(), p.y()));
        }
        return new ReviewResultDto(po.reviewId(), po.routeId(), po.routeVersion(),
                po.airspaceVersion(), conclusion, po.reasonCode(), List.copyOf(po.hitZoneIds()),
                List.copyOf(po.hitClosureIds()), snapshot, current);
    }

    /** 审核项（航线标识与明确版本）。 */
    private record ReviewItem(String routeId, int routeVersion) {
    }
}
