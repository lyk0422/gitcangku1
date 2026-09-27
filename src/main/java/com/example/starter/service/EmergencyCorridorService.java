package com.example.starter.service;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.CorridorConflictDiagnosticsDto;
import com.example.starter.api.dto.CorridorCreateRequest;
import com.example.starter.api.dto.CorridorCreateResult;
import com.example.starter.api.dto.CorridorDetailDto;
import com.example.starter.api.dto.CorridorRevokeRequest;
import com.example.starter.api.dto.CorridorRevokeResult;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.OccupancyCreateRequest;
import com.example.starter.api.dto.OccupancyDetailDto;
import com.example.starter.api.dto.OccupancyResult;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.TakeoffRequest;
import com.example.starter.api.dto.TakeoffResult;
import com.example.starter.api.dto.YieldRecordDto;
import com.example.starter.domain.CorridorStatus;
import com.example.starter.domain.Geometry;
import com.example.starter.domain.OccupancyStatus;
import com.example.starter.domain.ReviewConclusion;
import com.example.starter.domain.RouteType;
import com.example.starter.repo.AirspaceRepository;
import com.example.starter.repo.CorridorPo;
import com.example.starter.repo.CorridorRepository;
import com.example.starter.repo.CorridorYieldPo;
import com.example.starter.repo.OccupancyPo;
import com.example.starter.repo.OccupancyRepository;
import com.example.starter.repo.ReviewPo;
import com.example.starter.repo.ReviewRepository;
import com.example.starter.repo.RoutePo;
import com.example.starter.repo.RouteRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 空域应急走廊预留与常规占用强制让渡业务服务。
 *
 * <p>走廊创建、撤销、常规/应急占用与起飞登记都先对协调锁行做真实更新取得行级排他锁，
 * 与禁飞区变更、审核事务按同一锁串行化，按事务提交顺序裁决，
 * 保证不会提交“同一时空既有走廊又有未让渡常规占用”的状态。</p>
 *
 * <p>走廊创建/撤销推进全局空域版本，使既有审查自然失效（STALE）：
 * 被让渡航线不自动恢复，必须重新审查并显式重新申请占用。
 * 走廊、让渡与占用历史不可变；让渡记录固化原审查版本、走廊版本与让渡原因。</p>
 *
 * <p>所有时间统一使用 UTC epoch 毫秒；时段与高度范围均按左闭右开解释。</p>
 */
@Service
public class EmergencyCorridorService {

    static final String KIND_CORRIDOR_CREATE = "CORRIDOR_CREATE";
    static final String KIND_CORRIDOR_REVOKE = "CORRIDOR_REVOKE";
    static final String KIND_OCCUPANCY_CREATE = "OCCUPANCY_CREATE";
    static final String KIND_TAKEOFF_REGISTER = "TAKEOFF_REGISTER";

    private final AirspaceRepository airspaceRepo;
    private final RouteRepository routeRepo;
    private final ReviewRepository reviewRepo;
    private final CorridorRepository corridorRepo;
    private final OccupancyRepository occupancyRepo;
    private final IdempotentMutationExecutor idempotency;
    private final Clock clock;
    private final TransactionTemplate txTemplate;
    private final Set<String> authorizedDispatchers;

    public EmergencyCorridorService(AirspaceRepository airspaceRepo,
                                    RouteRepository routeRepo,
                                    ReviewRepository reviewRepo,
                                    CorridorRepository corridorRepo,
                                    OccupancyRepository occupancyRepo,
                                    IdempotentMutationExecutor idempotency,
                                    Clock clock,
                                    PlatformTransactionManager transactionManager,
                                    @Value("${airspace.authorized-dispatchers:}")
                                    List<String> authorizedDispatchers) {
        this.airspaceRepo = airspaceRepo;
        this.routeRepo = routeRepo;
        this.reviewRepo = reviewRepo;
        this.corridorRepo = corridorRepo;
        this.occupancyRepo = occupancyRepo;
        this.idempotency = idempotency;
        this.clock = clock;
        this.txTemplate = new TransactionTemplate(transactionManager);
        // 配置为空列表表示不限制调度员；非空时必须命中名单
        Set<String> dispatchers = new TreeSet<>();
        for (String d : authorizedDispatchers) {
            if (d != null && !d.isBlank()) {
                dispatchers.add(d.trim());
            }
        }
        this.authorizedDispatchers = Set.copyOf(dispatchers);
    }

    // ============================ 应急走廊 ============================

    /**
     * 创建应急走廊预留。同一事务内：校验完整最终状态（区域/时段/高度合法、调度员授权、
     * 同优先级不重叠、相交常规 ACTIVE 占用全部可让渡且未起飞），
     * 再原子提交走廊、全部让渡记录与让渡原因；任一步失败整体回滚，查询不到半成品。
     */
    public MutationResponse createCorridor(CorridorCreateRequest request) {
        return idempotency.execute(request.requestId(), KIND_CORRIDOR_CREATE, request, () -> {
            validateRectangle(request.xMin(), request.yMin(), request.xMax(), request.yMax());
            validateWindow(request.startAt(), request.endAt());
            validateAltitude(request.altitudeLower(), request.altitudeUpper());
            requireAuthorizedDispatcher(request.dispatcherId());
            long now = nowMillis();
            // 锁协调行：与区域变更、审核、占用、起飞及其他走廊事务互斥
            airspaceRepo.getGlobalVersionForUpdate();
            if (corridorRepo.findCorridor(request.corridorId()) != null) {
                throw new ApiException(HttpStatus.CONFLICT, "CORRIDOR_ALREADY_EXISTS",
                        "应急走廊已存在: " + request.corridorId());
            }
            // 同优先级有效走廊时空不得重叠
            List<CorridorPo> overlaps = corridorRepo.findOverlappingActiveCorridors(
                    request.priority(), now,
                    request.xMin(), request.yMin(), request.xMax(), request.yMax(),
                    request.startAt(), request.endAt(),
                    request.altitudeLower(), request.altitudeUpper());
            if (!overlaps.isEmpty()) {
                CorridorPo first = overlaps.get(0);
                throw new ApiException(HttpStatus.CONFLICT, "CORRIDOR_OVERLAP",
                        "与同优先级应急走廊重叠: " + first.corridorId()
                                + "（冲突数量=" + overlaps.size() + "）",
                        Map.of("corridorId", first.corridorId(),
                                "conflictCount", overlaps.size()));
            }
            // 检索所有与走廊时空相交的常规 ACTIVE 占用
            List<OccupancyPo> intersecting = findIntersectingRegularOccupancies(request);
            // 先校验完整最终状态：存在不可让渡或已起飞占用时整次 422，不产生任何让渡
            List<String> blocking = new ArrayList<>();
            List<OccupancyPo> yieldable = new ArrayList<>();
            for (OccupancyPo occ : intersecting) {
                if (occ.departed() || !occ.yieldable()) {
                    blocking.add(occ.occupancyId());
                } else {
                    yieldable.add(occ);
                }
            }
            if (!blocking.isEmpty()) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("blockingOccupancyIds", List.copyOf(blocking));
                details.put("blockingCount", blocking.size());
                details.put("yieldableCount", yieldable.size());
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CORRIDOR_PREEMPTION_BLOCKED",
                        "存在不可让渡或已起飞占用，整次预留被拒绝：阻断=" + blocking.size()
                                + "，可让渡=" + yieldable.size() + "，阻断占用=" + blocking,
                        details);
            }
            // 校验通过，推进全局空域版本（走廊版本），使既有审查自然失效
            long newVersion = airspaceRepo.incrementGlobalVersion();
            // 同一事务内让渡全部可让渡占用并固化原审查版本与让渡原因
            List<String> yieldedIds = new ArrayList<>();
            for (OccupancyPo occ : yieldable) {
                int updated = occupancyRepo.markYielded(occ.occupancyId());
                if (updated == 0) {
                    // 持锁期间不应发生；兜底保证不产生半成品
                    throw new ApiException(HttpStatus.CONFLICT, "OCCUPANCY_STATE_CHANGED",
                            "占用状态已变化，请重试: " + occ.occupancyId());
                }
                ReviewPo review = reviewRepo.findReview(occ.reviewId());
                if (review == null) {
                    throw new IllegalStateException("占用引用的审查记录不存在: " + occ.reviewId());
                }
                corridorRepo.insertYield(new CorridorYieldPo(
                        "yd_" + UUID.randomUUID(), request.corridorId(), newVersion,
                        occ.occupancyId(), occ.routeId(), occ.reviewId(),
                        review.routeVersion(), review.airspaceVersion(),
                        request.reason(), now));
                yieldedIds.add(occ.occupancyId());
            }
            corridorRepo.insertCorridor(new CorridorPo(request.corridorId(),
                    request.xMin(), request.yMin(), request.xMax(), request.yMax(),
                    request.startAt(), request.endAt(),
                    request.altitudeLower(), request.altitudeUpper(),
                    request.priority(), CorridorStatus.ACTIVE.name(), request.dispatcherId(),
                    request.reason(), newVersion, null, now, null));
            return new MutationResponse(request.requestId(), false,
                    new CorridorCreateResult(request.corridorId(), CorridorStatus.ACTIVE.name(),
                            newVersion, List.copyOf(yieldedIds), yieldedIds.size(),
                            request.endAt() - request.startAt()));
        });
    }

    /** 撤销应急走廊。只影响后续占用；已让渡航线不自动恢复。 */
    public MutationResponse revokeCorridor(CorridorRevokeRequest request) {
        return idempotency.execute(request.requestId(), KIND_CORRIDOR_REVOKE, request, () -> {
            requireAuthorizedDispatcher(request.dispatcherId());
            long now = nowMillis();
            airspaceRepo.getGlobalVersionForUpdate();
            CorridorPo corridor = corridorRepo.findCorridor(request.corridorId());
            if (corridor == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "CORRIDOR_NOT_FOUND",
                        "应急走廊不存在: " + request.corridorId());
            }
            if (CorridorStatus.REVOKED.name().equals(corridor.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "CORRIDOR_ALREADY_REVOKED",
                        "应急走廊已撤销: " + request.corridorId());
            }
            long newVersion = airspaceRepo.incrementGlobalVersion();
            corridorRepo.markRevoked(request.corridorId(), newVersion, now);
            return new MutationResponse(request.requestId(), false,
                    new CorridorRevokeResult(request.corridorId(),
                            CorridorStatus.REVOKED.name(), newVersion));
        });
    }

    // ============================ 占用与起飞 ============================

    /**
     * 创建空域占用（常规或应急；被让渡后的显式重新申请也走本入口）。
     * 要求航线存在当前有效的 CLEAR 审查结论；常规占用命中有效走廊返回 409 并给出走廊标识。
     */
    public MutationResponse createOccupancy(OccupancyCreateRequest request) {
        return idempotency.execute(request.requestId(), KIND_OCCUPANCY_CREATE, request, () -> {
            validateWindow(request.startAt(), request.endAt());
            validateAltitude(request.altitudeLower(), request.altitudeUpper());
            long now = nowMillis();
            // 锁协调行：与走廊创建/撤销、区域变更、审核、起飞事务互斥
            long globalVersion = airspaceRepo.getGlobalVersionForUpdate();
            // 锁航线行：与航线替换事务互斥，保证版本号与点列一致
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
            if (occupancyRepo.findOccupancy(request.occupancyId()) != null) {
                throw new ApiException(HttpStatus.CONFLICT, "OCCUPANCY_ALREADY_EXISTS",
                        "占用已存在: " + request.occupancyId());
            }
            // 必须存在当前有效的 CLEAR 审查（应急航线同样须通过禁飞审查）；
            // 走廊创建/撤销已推进空域版本，被让渡航线必须重新审查后才能重新申请
            ReviewPo latest = reviewRepo.findLatestReview(request.routeId());
            if (latest == null
                    || !ReviewConclusion.CLEAR.name().equals(latest.conclusion())
                    || latest.routeVersion() != route.version()
                    || latest.airspaceVersion() != globalVersion) {
                throw new ApiException(HttpStatus.CONFLICT, "NO_CURRENT_CLEAR_REVIEW",
                        "航线缺少当前有效的 CLEAR 审查结论，请先重新审查: " + request.routeId());
            }
            // 常规占用命中有效走廊 → 409 并给出走廊标识；应急占用不受走廊限制
            if (RouteType.REGULAR.name().equals(route.routeType())) {
                CorridorPo hit = findHitCorridor(route.points(), request.startAt(), request.endAt(),
                        request.altitudeLower(), request.altitudeUpper(), now);
                if (hit != null) {
                    throw new ApiException(HttpStatus.CONFLICT, "CORRIDOR_CONFLICT",
                            "常规占用命中有效应急走廊: " + hit.corridorId(),
                            Map.of("corridorId", hit.corridorId()));
                }
            }
            occupancyRepo.insertOccupancy(new OccupancyPo(request.occupancyId(),
                    request.routeId(), route.version(), route.routeType(),
                    request.startAt(), request.endAt(),
                    request.altitudeLower(), request.altitudeUpper(),
                    request.yieldable(), OccupancyStatus.ACTIVE.name(), false, null,
                    latest.reviewId(), List.copyOf(route.points()),
                    request.requestId(), now));
            return new MutationResponse(request.requestId(), false,
                    new OccupancyResult(request.occupancyId(), request.routeId(), route.version(),
                            route.routeType(), OccupancyStatus.ACTIVE.name(),
                            request.endAt() - request.startAt()));
        });
    }

    /** 起飞登记：仅 ACTIVE 占用可登记；已让渡或已起飞返回 409。 */
    public MutationResponse registerTakeoff(TakeoffRequest request) {
        return idempotency.execute(request.requestId(), KIND_TAKEOFF_REGISTER, request, () -> {
            long now = nowMillis();
            // 锁协调行：与走廊创建事务互斥，保证走廊让渡判定看到一致的起飞状态
            airspaceRepo.getGlobalVersionForUpdate();
            OccupancyPo occupancy = occupancyRepo.findOccupancy(request.occupancyId());
            if (occupancy == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "OCCUPANCY_NOT_FOUND",
                        "占用不存在: " + request.occupancyId());
            }
            if (OccupancyStatus.YIELDED.name().equals(occupancy.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "OCCUPANCY_NOT_ACTIVE",
                        "占用已被让渡，不能起飞: " + request.occupancyId());
            }
            if (occupancy.departed()) {
                throw new ApiException(HttpStatus.CONFLICT, "OCCUPANCY_ALREADY_DEPARTED",
                        "占用已起飞登记: " + request.occupancyId());
            }
            int updated = occupancyRepo.markDeparted(request.occupancyId(), now);
            if (updated == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "OCCUPANCY_STATE_CHANGED",
                        "占用状态已变化，请重试: " + request.occupancyId());
            }
            return new MutationResponse(request.requestId(), false,
                    new TakeoffResult(request.occupancyId(), true, now));
        });
    }

    // ============================ 查询（只读，不改变状态） ============================

    /** 走廊明细；effective 表示查询时刻是否实际生效。 */
    public CorridorDetailDto getCorridor(String corridorId) {
        CorridorPo po = corridorRepo.findCorridor(corridorId);
        if (po == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CORRIDOR_NOT_FOUND",
                    "应急走廊不存在: " + corridorId);
        }
        long now = nowMillis();
        boolean effective = CorridorStatus.ACTIVE.name().equals(po.status())
                && po.startAt() <= now && now < po.endAt();
        return new CorridorDetailDto(po.corridorId(), po.xMin(), po.yMin(), po.xMax(), po.yMax(),
                po.startAt(), po.endAt(), po.altitudeLower(), po.altitudeUpper(),
                po.priority(), po.status(), effective, po.dispatcherId(), po.reason(),
                po.createdVersion(), po.revokedVersion(), po.createdAt(), po.revokedAt(),
                po.endAt() - po.startAt());
    }

    /** 某走廊的全部让渡历史（不可变），按让渡时间、yieldId 稳定排序。 */
    public List<YieldRecordDto> getCorridorYields(String corridorId) {
        if (corridorRepo.findCorridor(corridorId) == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CORRIDOR_NOT_FOUND",
                    "应急走廊不存在: " + corridorId);
        }
        List<YieldRecordDto> result = new ArrayList<>();
        for (CorridorYieldPo po : corridorRepo.findYieldsByCorridor(corridorId)) {
            result.add(toYieldDto(po));
        }
        return result;
    }

    /** 占用明细，关联让渡记录（未被让渡为 null）。 */
    public OccupancyDetailDto getOccupancy(String occupancyId) {
        OccupancyPo po = occupancyRepo.findOccupancy(occupancyId);
        if (po == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "OCCUPANCY_NOT_FOUND",
                    "占用不存在: " + occupancyId);
        }
        CorridorYieldPo yield = corridorRepo.findYieldByOccupancy(occupancyId);
        return toOccupancyDto(po, yield == null ? null : toYieldDto(yield));
    }

    /** 某航线的全部占用历史（含重新申请记录），按创建时间、occupancyId 稳定排序。 */
    public List<OccupancyDetailDto> getRouteOccupancies(String routeId) {
        if (routeRepo.findRoute(routeId) == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND",
                    "航线不存在: " + routeId);
        }
        List<OccupancyDetailDto> result = new ArrayList<>();
        for (OccupancyPo po : occupancyRepo.findByRoute(routeId)) {
            CorridorYieldPo yield = corridorRepo.findYieldByOccupancy(po.occupancyId());
            result.add(toOccupancyDto(po, yield == null ? null : toYieldDto(yield)));
        }
        return result;
    }

    /**
     * 一致性诊断：当前仍有效的走廊与未让渡常规 ACTIVE 占用的时空冲突对。
     * 不变量要求结果恒为空；只读，不改变任何状态。
     */
    public CorridorConflictDiagnosticsDto diagnoseCorridorConflicts() {
        return txTemplate.execute(status -> {
            long now = nowMillis();
            List<CorridorPo> corridors = corridorRepo.findActiveCorridors(now);
            List<OccupancyPo> occupancies = occupancyRepo.findActiveRegular();
            List<CorridorConflictDiagnosticsDto.Violation> violations = new ArrayList<>();
            for (CorridorPo corridor : corridors) {
                for (OccupancyPo occ : occupancies) {
                    if (overlaps(corridor, occ)) {
                        violations.add(new CorridorConflictDiagnosticsDto.Violation(
                                corridor.corridorId(), occ.occupancyId()));
                    }
                }
            }
            return new CorridorConflictDiagnosticsDto(violations.size(), List.copyOf(violations));
        });
    }

    // ============================ 内部方法 ============================

    /** 检索与走廊时空相交的常规 ACTIVE 占用（时段、高度重叠且航点快照与走廊区域相交）。 */
    private List<OccupancyPo> findIntersectingRegularOccupancies(CorridorCreateRequest request) {
        List<OccupancyPo> candidates = occupancyRepo.findActiveRegularOverlapping(
                request.startAt(), request.endAt(),
                request.altitudeLower(), request.altitudeUpper());
        List<OccupancyPo> intersecting = new ArrayList<>();
        for (OccupancyPo occ : candidates) {
            if (Geometry.polylineHitsRectangle(occ.pointsSnapshot(),
                    request.xMin(), request.yMin(), request.xMax(), request.yMax())) {
                intersecting.add(occ);
            }
        }
        return intersecting;
    }

    /** 常规占用/审查的走廊命中判定：返回命中的第一个有效走廊（按 corridorId 排序），未命中返回 null。 */
    private CorridorPo findHitCorridor(List<com.example.starter.domain.Point> points,
                                       long startAt, long endAt,
                                       int altitudeLower, int altitudeUpper, long now) {
        List<CorridorPo> corridors = corridorRepo.findActiveCorridorsOverlappingWindow(
                now, startAt, endAt, altitudeLower, altitudeUpper);
        for (CorridorPo corridor : corridors) {
            if (Geometry.polylineHitsRectangle(points,
                    corridor.xMin(), corridor.yMin(), corridor.xMax(), corridor.yMax())) {
                return corridor;
            }
        }
        return null;
    }

    /** 走廊与占用的时空重叠判定（诊断用）：时段、高度左闭右开重叠且空间相交。 */
    private static boolean overlaps(CorridorPo corridor, OccupancyPo occ) {
        boolean timeOverlap = corridor.startAt() < occ.endAt() && occ.startAt() < corridor.endAt();
        boolean altitudeOverlap = corridor.altitudeLower() < occ.altitudeUpper()
                && occ.altitudeLower() < corridor.altitudeUpper();
        return timeOverlap && altitudeOverlap
                && Geometry.polylineHitsRectangle(occ.pointsSnapshot(),
                corridor.xMin(), corridor.yMin(), corridor.xMax(), corridor.yMax());
    }

    private void requireAuthorizedDispatcher(String dispatcherId) {
        if (!authorizedDispatchers.isEmpty() && !authorizedDispatchers.contains(dispatcherId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DISPATCHER_NOT_AUTHORIZED",
                    "调度员未授权: " + dispatcherId);
        }
    }

    private static void validateRectangle(int xMin, int yMin, int xMax, int yMax) {
        if (xMin >= xMax || yMin >= yMax) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CORRIDOR_RECTANGLE",
                    "走廊区域必须是非退化轴对齐矩形：xMin < xMax 且 yMin < yMax");
        }
    }

    private static void validateWindow(long startAt, long endAt) {
        if (startAt >= endAt) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TIME_WINDOW",
                    "时段必须满足 startAt < endAt（左闭右开）：startAt=" + startAt
                            + ", endAt=" + endAt);
        }
    }

    private static void validateAltitude(int altitudeLower, int altitudeUpper) {
        if (altitudeLower >= altitudeUpper) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ALTITUDE_RANGE",
                    "高度范围必须满足 altitudeLower < altitudeUpper（左闭右开）：lower="
                            + altitudeLower + ", upper=" + altitudeUpper);
        }
    }

    private static YieldRecordDto toYieldDto(CorridorYieldPo po) {
        return new YieldRecordDto(po.yieldId(), po.corridorId(), po.corridorVersion(),
                po.occupancyId(), po.routeId(), po.reviewId(),
                po.routeVersion(), po.airspaceVersion(), po.reason(), po.createdAt());
    }

    private static OccupancyDetailDto toOccupancyDto(OccupancyPo po, YieldRecordDto yield) {
        List<RoutePointDto> snapshot = new ArrayList<>(po.pointsSnapshot().size());
        for (com.example.starter.domain.Point p : po.pointsSnapshot()) {
            snapshot.add(new RoutePointDto(p.x(), p.y()));
        }
        return new OccupancyDetailDto(po.occupancyId(), po.routeId(), po.routeVersion(),
                po.routeType(), po.startAt(), po.endAt(), po.altitudeLower(), po.altitudeUpper(),
                po.yieldable(), po.status(), po.departed(), po.departedAt(), po.reviewId(),
                snapshot, po.createdAt(), po.endAt() - po.startAt(), yield);
    }

    private long nowMillis() {
        return clock.instant().toEpochMilli();
    }
}
