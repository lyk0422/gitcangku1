package com.example.starter.service;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.CorridorCreateRequest;
import com.example.starter.api.dto.CorridorDetail;
import com.example.starter.api.dto.CorridorDiagnostics;
import com.example.starter.api.dto.CorridorResult;
import com.example.starter.api.dto.CorridorRevokeRequest;
import com.example.starter.api.dto.DepartureRequest;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.OccupationCreateRequest;
import com.example.starter.api.dto.OccupationDetail;
import com.example.starter.api.dto.OccupationHistoryDto;
import com.example.starter.api.dto.OccupationHistoryEvent;
import com.example.starter.api.dto.OccupationResult;
import com.example.starter.api.dto.ReapplyRequest;
import com.example.starter.api.dto.ReapplyResult;
import com.example.starter.api.dto.YieldRecordDto;
import com.example.starter.domain.CorridorStatus;
import com.example.starter.domain.Geometry;
import com.example.starter.domain.OccupationKind;
import com.example.starter.domain.OccupationStatus;
import com.example.starter.domain.Point;
import com.example.starter.domain.ReviewConclusion;
import com.example.starter.repo.AirspaceRepository;
import com.example.starter.repo.CorridorPo;
import com.example.starter.repo.CorridorRepository;
import com.example.starter.repo.OccupationPo;
import com.example.starter.repo.OccupationRepository;
import com.example.starter.repo.ReapplyPo;
import com.example.starter.repo.ReviewPo;
import com.example.starter.repo.ReviewRepository;
import com.example.starter.repo.RoutePo;
import com.example.starter.repo.RouteRepository;
import com.example.starter.repo.YieldPo;
import com.example.starter.repo.ZonePo;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 空域应急走廊与占用裁决服务。
 *
 * <p>走廊创建、撤销、常规/应急占用、起飞登记与重新申请都先对协调锁行做真实更新，
 * 在同一事务内完成全部校验与写入，按事务提交顺序串行裁决，
 * 保证不会出现“同一时空既有有效走廊又有未让渡常规占用”。</p>
 *
 * <p>所有时间统一为 UTC epoch 毫秒，时段与高度范围均为左闭右开区间；
 * 让渡与重新申请历史不可变，撤销或自然到期只影响后续占用。</p>
 */
@Service
public class EmergencyCorridorService {

    static final String KIND_CORRIDOR_CREATE = "CORRIDOR_CREATE";
    static final String KIND_CORRIDOR_REVOKE = "CORRIDOR_REVOKE";
    static final String KIND_OCCUPATION_CREATE = "OCCUPATION_CREATE";
    static final String KIND_OCCUPATION_DEPART = "OCCUPATION_DEPART";
    static final String KIND_OCCUPATION_REAPPLY = "OCCUPATION_REAPPLY";

    private static final String YIELD_REASON_PREFIX = "应急走廊预留强制让渡: ";

    private final AirspaceRepository airspaceRepo;
    private final RouteRepository routeRepo;
    private final ReviewRepository reviewRepo;
    private final CorridorRepository corridorRepo;
    private final OccupationRepository occupationRepo;
    private final IdempotentExecutor idempotentExecutor;
    private final Clock clock;
    private final TransactionTemplate txTemplate;

    public EmergencyCorridorService(AirspaceRepository airspaceRepo,
                                    RouteRepository routeRepo,
                                    ReviewRepository reviewRepo,
                                    CorridorRepository corridorRepo,
                                    OccupationRepository occupationRepo,
                                    IdempotentExecutor idempotentExecutor,
                                    Clock clock,
                                    PlatformTransactionManager transactionManager) {
        this.airspaceRepo = airspaceRepo;
        this.routeRepo = routeRepo;
        this.reviewRepo = reviewRepo;
        this.corridorRepo = corridorRepo;
        this.occupationRepo = occupationRepo;
        this.idempotentExecutor = idempotentExecutor;
        this.clock = clock;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    // ============================ 应急走廊 ============================

    /**
     * 创建应急走廊：先校验完整最终状态（参数合法、同优先级不重叠、
     * 相交常规占用全部可让渡），再在同一事务内让渡并创建走廊；
     * 存在不可让渡或已起飞占用时整次 422，不产生任何半成品。
     */
    public MutationResponse createCorridor(CorridorCreateRequest request) {
        return idempotentExecutor.execute(request.requestId(), KIND_CORRIDOR_CREATE, request, () -> {
            validateRectangle(request.xMin(), request.yMin(), request.xMax(), request.yMax());
            validateWindow(request.startUtc(), request.endUtc());
            validateAltitude(request.altMin(), request.altMax());
            // 串行化：与占用、起飞、其他走廊变更事务互斥
            airspaceRepo.getGlobalVersionForUpdate();
            if (corridorRepo.findCorridor(request.corridorId()) != null) {
                throw new ApiException(HttpStatus.CONFLICT, "CORRIDOR_ALREADY_EXISTS",
                        "应急走廊已存在: " + request.corridorId());
            }
            // 同优先级 ACTIVE 走廊时空重叠禁止
            for (CorridorPo other : corridorRepo.findActiveCorridors()) {
                if (other.priority() == request.priority()
                        && corridorOverlap(other, request.xMin(), request.yMin(),
                        request.xMax(), request.yMax(), request.startUtc(), request.endUtc(),
                        request.altMin(), request.altMax())) {
                    throw new ApiException(HttpStatus.CONFLICT, "CORRIDOR_OVERLAP",
                            "与同优先级应急走廊时空重叠: " + other.corridorId(),
                            Map.of("conflictCorridorId", other.corridorId(),
                                    "priority", request.priority()));
                }
            }
            // 检索所有与走廊时空相交的常规 ACTIVE/DEPARTED 占用
            List<OccupationPo> intersecting = intersectingRegularOccupations(
                    request.xMin(), request.yMin(), request.xMax(), request.yMax(),
                    request.startUtc(), request.endUtc(), request.altMin(), request.altMax());
            List<String> departed = new ArrayList<>();
            List<String> nonYieldable = new ArrayList<>();
            List<OccupationPo> toYield = new ArrayList<>();
            for (OccupationPo occ : intersecting) {
                if (OccupationStatus.DEPARTED.name().equals(occ.status())) {
                    departed.add(occ.occupationId());
                } else if (!occ.yieldable()) {
                    nonYieldable.add(occ.occupationId());
                } else {
                    toYield.add(occ);
                }
            }
            if (!departed.isEmpty() || !nonYieldable.isEmpty()) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("departedCount", departed.size());
                details.put("nonYieldableCount", nonYieldable.size());
                details.put("departedOccupationIds", departed);
                details.put("nonYieldableOccupationIds", nonYieldable);
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "OCCUPATION_NOT_YIELDABLE",
                        "存在不可让渡或已起飞占用，走廊创建整次拒绝：departed=" + departed.size()
                                + ", nonYieldable=" + nonYieldable.size(), details);
            }
            // 全部可让渡：同一事务内标 YIELDED 并固化原审查版本与让渡原因
            long now = nowMillis();
            List<String> yieldedIds = new ArrayList<>();
            for (OccupationPo occ : toYield) {
                occupationRepo.markYielded(occ.occupationId());
                corridorRepo.insertYield(new YieldPo("yd_" + UUID.randomUUID(),
                        request.corridorId(), 1, occ.occupationId(), occ.routeId(),
                        occ.reviewId(), occ.routeVersion(),
                        YIELD_REASON_PREFIX + request.corridorId(), now));
                yieldedIds.add(occ.occupationId());
            }
            corridorRepo.insertCorridor(new CorridorPo(request.corridorId(),
                    request.xMin(), request.yMin(), request.xMax(), request.yMax(),
                    request.startUtc(), request.endUtc(), request.altMin(), request.altMax(),
                    request.priority(), CorridorStatus.ACTIVE.name(), 1,
                    request.operator(), now, null));
            return new MutationResponse(request.requestId(), false,
                    new CorridorResult(request.corridorId(), CorridorStatus.ACTIVE.name(), 1,
                            yieldedIds.size(), List.copyOf(yieldedIds)));
        });
    }

    /** 撤销走廊：只影响后续占用，已让渡占用不自动恢复。 */
    public MutationResponse revokeCorridor(CorridorRevokeRequest request) {
        return idempotentExecutor.execute(request.requestId(), KIND_CORRIDOR_REVOKE, request, () -> {
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
            corridorRepo.markRevoked(request.corridorId(), nowMillis());
            CorridorPo after = corridorRepo.findCorridor(request.corridorId());
            return new MutationResponse(request.requestId(), false,
                    new CorridorResult(request.corridorId(), CorridorStatus.REVOKED.name(),
                            after.version(), 0, List.of()));
        });
    }

    /** 走廊明细查询（只读）。 */
    public CorridorDetail getCorridor(String corridorId) {
        CorridorPo c = corridorRepo.findCorridor(corridorId);
        if (c == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CORRIDOR_NOT_FOUND",
                    "应急走廊不存在: " + corridorId);
        }
        return new CorridorDetail(c.corridorId(), c.status(), c.version(),
                c.xMin(), c.yMin(), c.xMax(), c.yMax(), c.startUtc(), c.endUtc(),
                c.altMin(), c.altMax(), c.priority(), c.operator(), c.createdAt(), c.revokedAt());
    }

    /** 走廊让渡历史查询（只读，按让渡时间、yieldId 升序）。 */
    public List<YieldRecordDto> getCorridorYields(String corridorId) {
        if (corridorRepo.findCorridor(corridorId) == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CORRIDOR_NOT_FOUND",
                    "应急走廊不存在: " + corridorId);
        }
        List<YieldRecordDto> result = new ArrayList<>();
        for (YieldPo y : corridorRepo.findYieldsByCorridor(corridorId)) {
            result.add(toYieldDto(y));
        }
        return result;
    }

    // ============================ 占用 ============================

    /**
     * 创建占用：同一事务内完成禁飞审查（应急同样必须）与走廊裁决。
     * 常规占用命中有效走廊返回 409 并给出走廊标识；命中禁飞区返回 409。
     */
    public MutationResponse createOccupation(OccupationCreateRequest request) {
        return idempotentExecutor.execute(request.requestId(), KIND_OCCUPATION_CREATE, request, () -> {
            validateWindow(request.startUtc(), request.endUtc());
            validateAltitude(request.altMin(), request.altMax());
            long globalVersion = airspaceRepo.getGlobalVersionForUpdate();
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
            if (occupationRepo.findOccupation(request.occupationId()) != null) {
                throw new ApiException(HttpStatus.CONFLICT, "OCCUPATION_ALREADY_EXISTS",
                        "占用已存在: " + request.occupationId());
            }
            // 禁飞审查：常规与应急占用都必须通过，走廊不能绕过禁区
            ensureClearOfNoFlyZones(route);
            // 走廊裁决：常规占用命中有效走廊 409；应急占用允许使用走廊
            List<String> hitCorridors = hitActiveCorridors(route.points(),
                    request.startUtc(), request.endUtc(), request.altMin(), request.altMax());
            if (OccupationKind.REGULAR.name().equals(request.kind()) && !hitCorridors.isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, "CORRIDOR_CONFLICT",
                        "常规占用命中有效应急走廊: " + hitCorridors.get(0),
                        Map.of("corridorIds", hitCorridors));
            }
            String reviewId = "rv_" + UUID.randomUUID();
            reviewRepo.insertReview(new ReviewPo(reviewId, request.routeId(), route.version(),
                    globalVersion, ReviewConclusion.CLEAR.name(), List.of(),
                    List.copyOf(route.points()), request.requestId(), nowMillis()));
            occupationRepo.insertOccupation(new OccupationPo(request.occupationId(),
                    request.routeId(), route.version(), request.kind(),
                    request.startUtc(), request.endUtc(), request.altMin(), request.altMax(),
                    request.yieldable(), OccupationStatus.ACTIVE.name(), reviewId, globalVersion,
                    List.copyOf(route.points()), null, nowMillis(), null));
            return new MutationResponse(request.requestId(), false,
                    new OccupationResult(request.occupationId(), OccupationStatus.ACTIVE.name(),
                            request.kind(), request.routeId(), route.version(), reviewId,
                            globalVersion));
        });
    }

    /** 起飞登记：仅 ACTIVE 占用可登记；已让渡占用须先重新申请。 */
    public MutationResponse depart(DepartureRequest request) {
        return idempotentExecutor.execute(request.requestId(), KIND_OCCUPATION_DEPART, request, () -> {
            airspaceRepo.getGlobalVersionForUpdate();
            OccupationPo occ = occupationRepo.findOccupation(request.occupationId());
            if (occ == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "OCCUPATION_NOT_FOUND",
                        "占用不存在: " + request.occupationId());
            }
            if (!OccupationStatus.ACTIVE.name().equals(occ.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "OCCUPATION_NOT_ACTIVE",
                        "仅 ACTIVE 占用可登记起飞，当前状态: " + occ.status(),
                        Map.of("status", occ.status()));
            }
            occupationRepo.markDeparted(request.occupationId(), nowMillis());
            return new MutationResponse(request.requestId(), false,
                    new OccupationResult(request.occupationId(), OccupationStatus.DEPARTED.name(),
                            occ.kind(), occ.routeId(), occ.routeVersion(), occ.reviewId(),
                            occ.airspaceVersion()));
        });
    }

    /**
     * 已让渡占用重新申请：继承原占用时空参数与类型，按当前航线版本与
     * 当前空域状态重新审查并生成新占用；原占用不自动恢复。
     */
    public MutationResponse reapply(ReapplyRequest request) {
        return idempotentExecutor.execute(request.requestId(), KIND_OCCUPATION_REAPPLY, request, () -> {
            long globalVersion = airspaceRepo.getGlobalVersionForUpdate();
            OccupationPo source = occupationRepo.findOccupation(request.sourceOccupationId());
            if (source == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "OCCUPATION_NOT_FOUND",
                        "占用不存在: " + request.sourceOccupationId());
            }
            if (!OccupationStatus.YIELDED.name().equals(source.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "OCCUPATION_NOT_YIELDED",
                        "仅 YIELDED 占用可重新申请，当前状态: " + source.status(),
                        Map.of("status", source.status()));
            }
            if (occupationRepo.findOccupation(request.newOccupationId()) != null) {
                throw new ApiException(HttpStatus.CONFLICT, "OCCUPATION_ALREADY_EXISTS",
                        "占用已存在: " + request.newOccupationId());
            }
            RoutePo route = routeRepo.findRouteForUpdate(source.routeId());
            if (route == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND",
                        "航线不存在: " + source.routeId());
            }
            // 重新审查：禁飞与走廊都按当前状态裁决
            ensureClearOfNoFlyZones(route);
            List<String> hitCorridors = hitActiveCorridors(route.points(),
                    source.startUtc(), source.endUtc(), source.altMin(), source.altMax());
            if (OccupationKind.REGULAR.name().equals(source.kind()) && !hitCorridors.isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, "CORRIDOR_CONFLICT",
                        "常规占用命中有效应急走廊: " + hitCorridors.get(0),
                        Map.of("corridorIds", hitCorridors));
            }
            String reviewId = "rv_" + UUID.randomUUID();
            reviewRepo.insertReview(new ReviewPo(reviewId, source.routeId(), route.version(),
                    globalVersion, ReviewConclusion.CLEAR.name(), List.of(),
                    List.copyOf(route.points()), request.requestId(), nowMillis()));
            occupationRepo.insertOccupation(new OccupationPo(request.newOccupationId(),
                    source.routeId(), route.version(), source.kind(),
                    source.startUtc(), source.endUtc(), source.altMin(), source.altMax(),
                    source.yieldable(), OccupationStatus.ACTIVE.name(), reviewId, globalVersion,
                    List.copyOf(route.points()), null, nowMillis(), null));
            occupationRepo.markSuperseded(request.sourceOccupationId(), request.newOccupationId());
            String reapplyId = "ra_" + UUID.randomUUID();
            corridorRepo.insertReapply(new ReapplyPo(reapplyId, request.sourceOccupationId(),
                    request.newOccupationId(), reviewId, nowMillis()));
            return new MutationResponse(request.requestId(), false,
                    new ReapplyResult(reapplyId, request.sourceOccupationId(),
                            request.newOccupationId(), OccupationStatus.ACTIVE.name(), reviewId));
        });
    }

    /** 占用明细查询（只读）：关联让渡记录、走廊版本与处置结果。 */
    public OccupationDetail getOccupation(String occupationId) {
        OccupationPo occ = occupationRepo.findOccupation(occupationId);
        if (occ == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "OCCUPATION_NOT_FOUND",
                    "占用不存在: " + occupationId);
        }
        YieldPo yield = corridorRepo.findYieldByOccupation(occupationId);
        return new OccupationDetail(occ.occupationId(), occ.routeId(), occ.routeVersion(),
                occ.kind(), occ.status(), occ.yieldable(), occ.startUtc(), occ.endUtc(),
                occ.altMin(), occ.altMax(), occ.reviewId(), occ.airspaceVersion(),
                occ.supersededBy(), occ.createdAt(), occ.departedAt(),
                yield == null ? null : toYieldDto(yield));
    }

    /** 占用历史查询（只读）：创建、让渡、起飞、重新申请关联事件，按时间稳定排序。 */
    public OccupationHistoryDto getOccupationHistory(String occupationId) {
        OccupationPo occ = occupationRepo.findOccupation(occupationId);
        if (occ == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "OCCUPATION_NOT_FOUND",
                    "占用不存在: " + occupationId);
        }
        List<OccupationHistoryEvent> events = new ArrayList<>();
        ReapplyPo bornFrom = corridorRepo.findReapplyByNewOccupation(occupationId);
        if (bornFrom != null) {
            events.add(new OccupationHistoryEvent("OCCUPATION_CREATED_BY_REAPPLY",
                    occ.createdAt(), null, null, bornFrom.reviewId(),
                    bornFrom.sourceOccupationId(), null));
        } else {
            events.add(new OccupationHistoryEvent("OCCUPATION_CREATED",
                    occ.createdAt(), null, null, occ.reviewId(), null, null));
        }
        YieldPo yield = corridorRepo.findYieldByOccupation(occupationId);
        if (yield != null) {
            events.add(new OccupationHistoryEvent("OCCUPATION_YIELDED",
                    yield.createdAt(), yield.corridorId(), yield.corridorVersion(),
                    yield.reviewId(), null, yield.reason()));
        }
        if (occ.departedAt() != null) {
            events.add(new OccupationHistoryEvent("OCCUPATION_DEPARTED",
                    occ.departedAt(), null, null, null, null, null));
        }
        for (ReapplyPo reapply : corridorRepo.findReappliesBySource(occupationId)) {
            events.add(new OccupationHistoryEvent("OCCUPATION_REAPPLIED",
                    reapply.createdAt(), null, null, reapply.reviewId(),
                    reapply.newOccupationId(), null));
        }
        events.sort(java.util.Comparator.comparingLong(OccupationHistoryEvent::occurredAt)
                .thenComparing(OccupationHistoryEvent::eventType));
        return new OccupationHistoryDto(occupationId, occ.status(), List.copyOf(events));
    }

    /** 走廊时空诊断（只读）：返回与指定航线时空相交的有效走廊与常规占用。 */
    public CorridorDiagnostics diagnose(String routeId, long startUtc, long endUtc,
                                        int altMin, int altMax) {
        return txTemplate.execute(status -> {
            validateWindow(startUtc, endUtc);
            validateAltitude(altMin, altMax);
            RoutePo route = routeRepo.findRoute(routeId);
            if (route == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND",
                        "航线不存在: " + routeId);
            }
            List<String> hitCorridors = hitActiveCorridors(route.points(),
                    startUtc, endUtc, altMin, altMax);
            List<String> activeRegular = new ArrayList<>();
            List<String> departed = new ArrayList<>();
            for (OccupationPo occ : occupationRepo.findRegularOccupationsByStatus(
                    List.of(OccupationStatus.ACTIVE.name(), OccupationStatus.DEPARTED.name()))) {
                if (occupationIntersects(occ, route.points(), startUtc, endUtc, altMin, altMax)) {
                    if (OccupationStatus.DEPARTED.name().equals(occ.status())) {
                        departed.add(occ.occupationId());
                    } else {
                        activeRegular.add(occ.occupationId());
                    }
                }
            }
            return new CorridorDiagnostics(routeId, startUtc, endUtc, altMin, altMax,
                    hitCorridors, List.copyOf(activeRegular), List.copyOf(departed));
        });
    }

    // ============================ 时空判定 ============================

    /** 左闭右开区间重叠：[s1,e1) 与 [s2,e2) 有交集。 */
    static boolean windowOverlap(long s1, long e1, long s2, long e2) {
        return s1 < e2 && s2 < e1;
    }

    /** 走廊与给定矩形+时段+高度范围是否时空重叠（区域为闭矩形，边界接触算重叠）。 */
    private static boolean corridorOverlap(CorridorPo c, int xMin, int yMin, int xMax, int yMax,
                                           long startUtc, long endUtc, int altMin, int altMax) {
        return windowOverlap(c.startUtc(), c.endUtc(), startUtc, endUtc)
                && windowOverlap(c.altMin(), c.altMax(), altMin, altMax)
                && c.xMin() <= xMax && xMin <= c.xMax()
                && c.yMin() <= yMax && yMin <= c.yMax();
    }

    /** 占用与给定航迹+时段+高度范围是否时空相交（几何按航迹折线相交判定）。 */
    private static boolean occupationIntersects(OccupationPo occ, List<Point> points,
                                                long startUtc, long endUtc, int altMin, int altMax) {
        return windowOverlap(occ.startUtc(), occ.endUtc(), startUtc, endUtc)
                && windowOverlap(occ.altMin(), occ.altMax(), altMin, altMax)
                && Geometry.polylinesIntersect(occ.pointsSnapshot(), points);
    }

    /** 占用与走廊矩形+时段+高度范围是否时空相交（几何用占用航点快照判定）。 */
    private static boolean occupationHitsCorridor(OccupationPo occ, CorridorPo c) {
        return windowOverlap(occ.startUtc(), occ.endUtc(), c.startUtc(), c.endUtc())
                && windowOverlap(occ.altMin(), occ.altMax(), c.altMin(), c.altMax())
                && Geometry.polylineHitsRectangle(occ.pointsSnapshot(),
                c.xMin(), c.yMin(), c.xMax(), c.yMax());
    }

    /** 检索与走廊时空相交的常规 ACTIVE/DEPARTED 占用（按 occupationId 字典序）。 */
    private List<OccupationPo> intersectingRegularOccupations(
            int xMin, int yMin, int xMax, int yMax,
            long startUtc, long endUtc, int altMin, int altMax) {
        CorridorPo probe = new CorridorPo("", xMin, yMin, xMax, yMax, startUtc, endUtc,
                altMin, altMax, 0, CorridorStatus.ACTIVE.name(), 1, "", 0L, null);
        List<OccupationPo> result = new ArrayList<>();
        for (OccupationPo occ : occupationRepo.findRegularOccupationsByStatus(
                List.of(OccupationStatus.ACTIVE.name(), OccupationStatus.DEPARTED.name()))) {
            if (occupationHitsCorridor(occ, probe)) {
                result.add(occ);
            }
        }
        return result;
    }

    /** 计算航迹+时段+高度范围命中的 ACTIVE 走廊标识（字典序）。 */
    private List<String> hitActiveCorridors(List<Point> points, long startUtc, long endUtc,
                                            int altMin, int altMax) {
        List<String> hits = new ArrayList<>();
        for (CorridorPo c : corridorRepo.findActiveCorridors()) {
            if (windowOverlap(c.startUtc(), c.endUtc(), startUtc, endUtc)
                    && windowOverlap(c.altMin(), c.altMax(), altMin, altMax)
                    && Geometry.polylineHitsRectangle(points,
                    c.xMin(), c.yMin(), c.xMax(), c.yMax())) {
                hits.add(c.corridorId());
            }
        }
        return hits;
    }

    /** 禁飞审查：任一有效禁飞区与航迹相交则 409，并给出全部命中 zoneId。 */
    private void ensureClearOfNoFlyZones(RoutePo route) {
        List<String> hits = new ArrayList<>();
        for (ZonePo zone : airspaceRepo.findActiveZones()) {
            if (Geometry.polylineHitsRectangle(route.points(),
                    zone.xMin(), zone.yMin(), zone.xMax(), zone.yMax())) {
                hits.add(zone.zoneId());
            }
        }
        if (!hits.isEmpty()) {
            java.util.Collections.sort(hits);
            throw new ApiException(HttpStatus.CONFLICT, "NO_FLY_BLOCKED",
                    "占用航迹命中禁飞区: " + hits.get(0),
                    Map.of("hitZoneIds", List.copyOf(hits)));
        }
    }

    // ============================ 参数校验 ============================

    private static void validateRectangle(int xMin, int yMin, int xMax, int yMax) {
        if (xMin >= xMax || yMin >= yMax) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CORRIDOR_RECTANGLE",
                    "走廊区域必须是非退化轴对齐矩形：xMin < xMax 且 yMin < yMax");
        }
    }

    /** 左闭右开时段必须非空：startUtc < endUtc。 */
    static void validateWindow(long startUtc, long endUtc) {
        if (startUtc >= endUtc) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TIME_WINDOW",
                    "时段必须满足 startUtc < endUtc（左闭右开）：startUtc=" + startUtc
                            + ", endUtc=" + endUtc);
        }
    }

    /** 左闭右开高度范围必须非空：altMin < altMax。 */
    static void validateAltitude(int altMin, int altMax) {
        if (altMin >= altMax) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ALTITUDE_RANGE",
                    "高度范围必须满足 altMin < altMax（左闭右开）：altMin=" + altMin
                            + ", altMax=" + altMax);
        }
    }

    private static YieldRecordDto toYieldDto(YieldPo y) {
        return new YieldRecordDto(y.yieldId(), y.corridorId(), y.corridorVersion(),
                y.occupationId(), y.routeId(), y.reviewId(), y.routeVersion(),
                y.reason(), y.createdAt());
    }

    private long nowMillis() {
        return clock.instant().toEpochMilli();
    }
}
