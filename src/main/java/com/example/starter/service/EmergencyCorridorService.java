package com.example.starter.service;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.CorridorCreateRequest;
import com.example.starter.api.dto.CorridorDetailView;
import com.example.starter.api.dto.CorridorEventView;
import com.example.starter.api.dto.CorridorRevokeRequest;
import com.example.starter.api.dto.CorridorResult;
import com.example.starter.api.dto.DepartureRequest;
import com.example.starter.api.dto.DepartureResult;
import com.example.starter.api.dto.EmergencyOccupancyRequest;
import com.example.starter.api.dto.OccupancyResult;
import com.example.starter.api.dto.OccupancyView;
import com.example.starter.api.dto.ReapplyHistoryView;
import com.example.starter.api.dto.ReapplyResultDto;
import com.example.starter.api.dto.RegularOccupancyRequest;
import com.example.starter.api.dto.YieldHistoryView;
import com.example.starter.api.dto.YieldedReapplyRequest;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.domain.CorridorEventAction;
import com.example.starter.domain.CorridorStatus;
import com.example.starter.domain.Geometry;
import com.example.starter.domain.OccupancyKind;
import com.example.starter.domain.OccupancyStatus;
import com.example.starter.domain.Point;
import com.example.starter.domain.ReapplyResult;
import com.example.starter.repo.AirspaceRepository;
import com.example.starter.repo.CorridorEventPo;
import com.example.starter.repo.CorridorPo;
import com.example.starter.repo.CorridorRepository;
import com.example.starter.repo.OccupancyPo;
import com.example.starter.repo.OccupancyRepository;
import com.example.starter.repo.ReapplyHistoryPo;
import com.example.starter.repo.ReviewPo;
import com.example.starter.repo.ReviewRepository;
import com.example.starter.repo.RoutePo;
import com.example.starter.repo.RouteRepository;
import com.example.starter.repo.YieldHistoryPo;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 空域应急走廊业务服务。
 *
 * <p>所有写操作经 {@link IdempotentExecutor} 与业务变更同事务原子提交，requestId
 * 同参重放首次结果、异参 409、失败不占键。走廊建立、撤销、常规/应急占用、起飞
 * 登记、重新申请均以事务提交顺序裁决：所有写事务先更新全局协调锁行，再操作
 * 走廊/占用行，保证不会出现同一时空既有有效走廊又有未让渡常规占用。</p>
 *
 * <p>走廊覆盖路径段在有效期内只允许应急航线使用：可让渡常规 ACTIVE 占用在走廊
 * 建立事务内标为 YIELDED 并固化原审查版本与让渡原因；不可让渡、已起飞或应急
 * 占用存在时整次 422，全部让渡与走廊插入回滚、不留部分结果。</p>
 */
@Service
public class EmergencyCorridorService {

    static final String KIND_CORRIDOR_CREATE = "CORRIDOR_CREATE";
    static final String KIND_CORRIDOR_REVOKE = "CORRIDOR_REVOKE";
    static final String KIND_REGULAR_OCCUPANCY = "REGULAR_OCCUPANCY";
    static final String KIND_EMERGENCY_OCCUPANCY = "EMERGENCY_OCCUPANCY";
    static final String KIND_DEPARTURE = "DEPARTURE";
    static final String KIND_YIELDED_REAPPLY = "YIELDED_REAPPLY";

    private final AirspaceRepository airspaceRepo;
    private final CorridorRepository corridorRepo;
    private final OccupancyRepository occupancyRepo;
    private final RouteRepository routeRepo;
    private final ReviewRepository reviewRepo;
    private final ReviewEngine reviewEngine;
    private final IdempotentExecutor idempotent;
    private final Clock clock;
    private final TransactionTemplate txTemplate;

    public EmergencyCorridorService(AirspaceRepository airspaceRepo,
                                    CorridorRepository corridorRepo,
                                    OccupancyRepository occupancyRepo,
                                    RouteRepository routeRepo,
                                    ReviewRepository reviewRepo,
                                    ReviewEngine reviewEngine,
                                    IdempotentExecutor idempotent,
                                    Clock clock,
                                    PlatformTransactionManager transactionManager) {
        this.airspaceRepo = airspaceRepo;
        this.corridorRepo = corridorRepo;
        this.occupancyRepo = occupancyRepo;
        this.routeRepo = routeRepo;
        this.reviewRepo = reviewRepo;
        this.reviewEngine = reviewEngine;
        this.idempotent = idempotent;
        this.clock = clock;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    // ============================ 走廊建立/撤销 ============================

    /**
     * 建立应急走廊：
     * 同优先级时空重叠 409；相交占用中存在不可让渡/已起飞/应急占用时整次 422；
     * 可让渡常规占用在同一事务内标 YIELDED 并固化原审查版本与让渡原因。
     */
    public MutationResponse createCorridor(CorridorCreateRequest request) {
        return idempotent.execute(request.requestId(), KIND_CORRIDOR_CREATE, request, () -> {
            validateCorridorShape(request);
            long now = nowMillis();
            // 先更新协调锁行：与走廊撤销、占用登记、起飞登记、重新申请全部串行化
            airspaceRepo.getGlobalVersionForUpdate();
            if (corridorRepo.findCorridor(request.corridorId()) != null) {
                throw new ApiException(HttpStatus.CONFLICT, "CORRIDOR_ALREADY_EXISTS",
                        "应急走廊已存在: " + request.corridorId());
            }

            // 同优先级走廊时空（闭矩形 × 左闭右开时间窗 × 左闭右开高度）不得重叠；
            // 已自然到期但尚未撤销的走廊不再阻断新走廊
            for (CorridorPo other : corridorRepo.findActiveCorridors()) {
                if (other.endMs() > now
                        && other.priority() == request.priority()
                        && corridorsOverlap(request, other)) {
                    throw new ApiException(HttpStatus.CONFLICT, "CORRIDOR_OVERLAP",
                            "与同优先级应急走廊时空重叠: " + other.corridorId());
                }
            }

            // 检索所有未让渡占用：仅常规占用参与让渡裁决（题干要求检索相交的常规占用）；
            // 走廊覆盖段本就允许应急航线使用，应急占用跳过；YIELDED 已为其他走廊让渡
            List<OccupancyPo> occupancies = occupancyRepo.findActiveAndDepartedOccupancies();
            Map<String, List<Point>> routePointsCache = new HashMap<>();
            List<OccupancyPo> toYield = new ArrayList<>();
            for (OccupancyPo occ : occupancies) {
                if (OccupancyKind.EMERGENCY.name().equals(occ.kind())) {
                    continue;
                }
                if (!spacetimeHits(request, occ, routePointsCache)) {
                    continue;
                }
                // 失败须区分原因：异常直接中断，整次事务回滚，不留部分结果
                ensureYieldable(occ);
                toYield.add(occ);
            }
            toYield.sort(Comparator.comparing(OccupancyPo::occupancyId));

            // 先验证完整后态通过，再在一个事务内提交走廊、事件与全部让渡
            List<String> yieldedIds = new ArrayList<>(toYield.size());
            for (OccupancyPo occ : toYield) {
                int updated = occupancyRepo.compareAndYield(
                        occ.occupancyId(), request.corridorId(), request.reason(), now);
                if (updated == 0) {
                    // 持锁预扫描后状态仍可能被持同把锁之前已提交的起飞事务推进：
                    // 重载当前状态并按原因失败（已起飞 → 422），整次回滚
                    OccupancyPo current = occupancyRepo.findOccupancy(occ.occupancyId());
                    if (current != null
                            && OccupancyStatus.DEPARTED.name().equals(current.status())) {
                        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "OCCUPANCY_DEPARTED",
                                "与走廊时空相交的占用已起飞，不能让渡: " + occ.occupancyId());
                    }
                    throw new ApiException(HttpStatus.CONFLICT, "OCCUPANCY_STATE_CHANGED",
                            "占用状态在走廊建立期间发生变化: "
                                    + (current == null ? occ.occupancyId() : current.status()));
                }
                ReviewPo originalReview = reviewRepo.findReview(occ.reviewId());
                if (originalReview == null) {
                    throw new IllegalStateException("占用引用的审查不存在: " + occ.reviewId());
                }
                occupancyRepo.insertYieldHistory(new YieldHistoryPo(
                        0L, request.corridorId(), 1, occ.occupancyId(), occ.routeId(),
                        occ.reviewId(), originalReview.routeVersion(),
                        originalReview.airspaceVersion(), request.reason(),
                        request.requestId(), now));
                yieldedIds.add(occ.occupancyId());
            }

            corridorRepo.insertCorridor(new CorridorPo(request.corridorId(),
                    request.xMin(), request.yMin(), request.xMax(), request.yMax(),
                    request.startMs(), request.endMs(), request.altMin(), request.altMax(),
                    request.priority(), CorridorStatus.ACTIVE.name(), 1,
                    null, null, now));
            corridorRepo.insertEvent(new CorridorEventPo(0L, request.corridorId(), 1,
                    CorridorEventAction.CREATED.name(), request.reason(), request.requestId(), now,
                    request.xMin(), request.yMin(), request.xMax(), request.yMax(),
                    request.startMs(), request.endMs(), request.altMin(), request.altMax(),
                    request.priority()));

            return new MutationResponse(request.requestId(), false, new CorridorResult(
                    request.corridorId(), CorridorStatus.ACTIVE.name(), 1,
                    request.startMs(), request.endMs(), request.endMs() - request.startMs(),
                    List.copyOf(yieldedIds)));
        });
    }

    /** 撤销走廊：只影响后续占用，已让渡航线不自动恢复；只能撤销一次。 */
    public MutationResponse revokeCorridor(CorridorRevokeRequest request) {
        return idempotent.execute(request.requestId(), KIND_CORRIDOR_REVOKE, request, () -> {
            long now = nowMillis();
            // 与走廊建立及占用类事务串行化
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
            int revokedVersion = corridor.version() + 1;
            int updated = corridorRepo.compareAndRevoke(
                    request.corridorId(), request.reason(), now);
            if (updated == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "CORRIDOR_ALREADY_REVOKED",
                        "应急走廊已被并发撤销: " + request.corridorId());
            }
            corridorRepo.insertEvent(new CorridorEventPo(0L, request.corridorId(), revokedVersion,
                    CorridorEventAction.REVOKED.name(), request.reason(), request.requestId(), now,
                    corridor.xMin(), corridor.yMin(), corridor.xMax(), corridor.yMax(),
                    corridor.startMs(), corridor.endMs(), corridor.altMin(), corridor.altMax(),
                    corridor.priority()));
            return new MutationResponse(request.requestId(), false, new CorridorResult(
                    request.corridorId(), CorridorStatus.REVOKED.name(), revokedVersion,
                    corridor.startMs(), corridor.endMs(),
                    corridor.endMs() - corridor.startMs(), List.of()));
        });
    }

    // ============================ 占用登记 ============================

    /** 常规占用登记：同事务内完成携带时空窗的常规审查（命中有效走廊 409）后写入占用。 */
    public MutationResponse registerRegular(RegularOccupancyRequest request) {
        return idempotent.execute(request.requestId(), KIND_REGULAR_OCCUPANCY, request, () -> {
            SpaceTimeWindow window = toWindow(request.startMs(), request.endMs(),
                    request.altMin(), request.altMax());
            ReviewPo review = reviewEngine.performReview(request.routeId(),
                    request.routeVersion(), request.airspaceVersion(),
                    ReviewEngine.KIND_REGULAR, request.requestId(), window);
            // performReview 命中有效走廊时已抛 CORRIDOR_CONFLICT(409)
            ensureClear(review, "常规");
            String occupancyId = newOccupancyId();
            long now = nowMillis();
            occupancyRepo.insertOccupancy(new OccupancyPo(occupancyId, request.routeId(),
                    OccupancyKind.REGULAR.name(), OccupancyStatus.ACTIVE.name(),
                    request.yieldable(), request.startMs(), request.endMs(),
                    request.altMin(), request.altMax(), review.reviewId(), null,
                    request.requestId(), now, null, null, null, null));
            return new MutationResponse(request.requestId(), false, toOccupancyResult(
                    occupancyId, request.routeId(), OccupancyKind.REGULAR.name(), window,
                    review.reviewId(), null));
        });
    }

    /**
     * 应急占用登记：应急审查仍必须通过禁飞审查（不能绕过禁区），
     * 且时空窗必须显式命中指定的有效（未撤销、未到期）走廊；否则区分原因失败。
     */
    public MutationResponse registerEmergency(EmergencyOccupancyRequest request) {
        return idempotent.execute(request.requestId(), KIND_EMERGENCY_OCCUPANCY, request, () -> {
            SpaceTimeWindow window = toWindow(request.startMs(), request.endMs(),
                    request.altMin(), request.altMax());
            // 应急审查只做禁飞区裁决：走廊不豁免禁区
            ReviewPo review = reviewEngine.performReview(request.routeId(),
                    request.routeVersion(), request.airspaceVersion(),
                    ReviewEngine.KIND_EMERGENCY, request.requestId(), null);
            ensureClear(review, "应急");

            CorridorPo corridor = corridorRepo.findCorridor(request.corridorId());
            if (corridor == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "CORRIDOR_NOT_FOUND",
                        "应急走廊不存在: " + request.corridorId());
            }
            long now = nowMillis();
            if (CorridorStatus.REVOKED.name().equals(corridor.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "CORRIDOR_REVOKED",
                        "应急走廊已撤销，不能登记应急占用: " + corridor.corridorId());
            }
            if (corridor.endMs() <= now) {
                throw new ApiException(HttpStatus.CONFLICT, "CORRIDOR_EXPIRED",
                        "应急走廊已自然到期: " + corridor.corridorId());
            }
            // 审查事务已锁航线行，点列与版本一致
            RoutePo route = routeRepo.findRoute(request.routeId());
            if (route == null || !Geometry.polylineHitsCorridor(route.points(),
                    corridor.xMin(), corridor.yMin(), corridor.xMax(), corridor.yMax(),
                    window.startMs(), window.endMs(), window.altMin(), window.altMax(),
                    corridor.startMs(), corridor.endMs(), corridor.altMin(), corridor.altMax())) {
                throw new ApiException(HttpStatus.CONFLICT, "CORRIDOR_NOT_HIT",
                        "应急航线时空窗未命中指定走廊: " + corridor.corridorId());
            }

            String occupancyId = newOccupancyId();
            occupancyRepo.insertOccupancy(new OccupancyPo(occupancyId, request.routeId(),
                    OccupancyKind.EMERGENCY.name(), OccupancyStatus.ACTIVE.name(),
                    false, request.startMs(), request.endMs(),
                    request.altMin(), request.altMax(), review.reviewId(), corridor.corridorId(),
                    request.requestId(), now, null, null, null, null));
            return new MutationResponse(request.requestId(), false, toOccupancyResult(
                    occupancyId, request.routeId(), OccupancyKind.EMERGENCY.name(), window,
                    review.reviewId(), corridor.corridorId()));
        });
    }

    /** 起飞登记：仅 ACTIVE 占用可登记；已让渡/已起飞区分原因返回 409。 */
    public MutationResponse registerDeparture(DepartureRequest request) {
        return idempotent.execute(request.requestId(), KIND_DEPARTURE, request, () -> {
            long now = nowMillis();
            // 先取协调锁，与走廊建立串行化：走廊先提交则占用已 YIELDED，不能起飞
            airspaceRepo.getGlobalVersionForUpdate();
            OccupancyPo occ = occupancyRepo.findOccupancy(request.occupancyId());
            if (occ == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "OCCUPANCY_NOT_FOUND",
                        "占用不存在: " + request.occupancyId());
            }
            int updated = occupancyRepo.compareAndDepart(request.occupancyId(), now);
            if (updated == 0) {
                if (OccupancyStatus.YIELDED.name().equals(occ.status())) {
                    throw new ApiException(HttpStatus.CONFLICT, "OCCUPANCY_YIELDED",
                            "占用已让渡，不能登记起飞: " + request.occupancyId());
                }
                throw new ApiException(HttpStatus.CONFLICT, "OCCUPANCY_ALREADY_DEPARTED",
                        "占用已登记起飞，不能重复登记: " + request.occupancyId());
            }
            return new MutationResponse(request.requestId(), false,
                    new DepartureResult(request.occupancyId(),
                            OccupancyStatus.DEPARTED.name(), now));
        });
    }

    /**
     * 已让渡航线显式重新申请：重新进行携带时空窗的常规审查（走廊仍有效且仍命中
     * 则 409），通过后新建占用并追加不可变重新申请历史；原占用保持 YIELDED。
     */
    public MutationResponse reapply(YieldedReapplyRequest request) {
        return idempotent.execute(request.requestId(), KIND_YIELDED_REAPPLY, request, () -> {
            SpaceTimeWindow window = toWindow(request.startMs(), request.endMs(),
                    request.altMin(), request.altMax());
            OccupancyPo old = occupancyRepo.findOccupancy(request.oldOccupancyId());
            if (old == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "OCCUPANCY_NOT_FOUND",
                        "原占用不存在: " + request.oldOccupancyId());
            }
            if (!OccupancyStatus.YIELDED.name().equals(old.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "OCCUPANCY_NOT_YIELDED",
                        "只有已让渡占用可以显式重新申请，当前状态: " + old.status());
            }
            // 重新审查：版本裁决 + 禁飞裁决 + 走廊裁决（走廊仍有效且时空仍命中 → 409）
            ReviewPo review = reviewEngine.performReview(old.routeId(),
                    request.routeVersion(), request.airspaceVersion(),
                    ReviewEngine.KIND_REGULAR, request.requestId(), window);
            ensureClear(review, "重新申请");

            String newOccupancyId = newOccupancyId();
            long now = nowMillis();
            occupancyRepo.insertOccupancy(new OccupancyPo(newOccupancyId, old.routeId(),
                    OccupancyKind.REGULAR.name(), OccupancyStatus.ACTIVE.name(),
                    request.yieldable(), window.startMs(), window.endMs(),
                    window.altMin(), window.altMax(), review.reviewId(), null,
                    request.requestId(), now, null, null, null, null));
            occupancyRepo.insertReapplyHistory(new ReapplyHistoryPo(0L,
                    old.yieldCorridorId(), old.occupancyId(), newOccupancyId, old.routeId(),
                    review.reviewId(), ReapplyResult.APPROVED.name(), request.requestId(), now));
            return new MutationResponse(request.requestId(), false, new ReapplyResultDto(
                    old.occupancyId(), newOccupancyId, old.routeId(),
                    old.yieldCorridorId(), review.reviewId(),
                    ReapplyResult.APPROVED.name()));
        });
    }

    // ============================ 只读查询（不修改状态） ============================

    /** 查询占用详情；不存在 404。 */
    public OccupancyView getOccupancy(String occupancyId) {
        OccupancyPo po = occupancyRepo.findOccupancy(occupancyId);
        if (po == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "OCCUPANCY_NOT_FOUND",
                    "占用不存在: " + occupancyId);
        }
        return toView(po);
    }

    /** 查询某航线全部占用（含已让渡/已起飞），按创建时刻升序；航线不存在 404。 */
    public List<OccupancyView> listOccupancies(String routeId) {
        if (routeRepo.findRoute(routeId) == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND",
                    "航线不存在: " + routeId);
        }
        return occupancyRepo.findByRoute(routeId).stream().map(this::toView).toList();
    }

    /** 查询走廊不可变事件与让渡历史（可经占用标识、审查标识继续关联）。 */
    public CorridorDetailView getCorridorDetail(String corridorId) {
        return txTemplate.execute(status -> {
            CorridorPo corridor = corridorRepo.findCorridor(corridorId);
            if (corridor == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "CORRIDOR_NOT_FOUND",
                        "应急走廊不存在: " + corridorId);
            }
            List<CorridorEventView> events = corridorRepo.findEvents(corridorId).stream()
                    .map(e -> new CorridorEventView(e.seq(), e.corridorId(), e.version(),
                            e.action(), e.reason(), e.requestId(), e.eventMs()))
                    .toList();
            List<YieldHistoryView> yields = occupancyRepo.findYieldsByCorridor(corridorId).stream()
                    .map(y -> new YieldHistoryView(y.seq(), y.corridorId(), y.corridorVersion(),
                            y.occupancyId(), y.routeId(), y.reviewId(), y.routeVersion(),
                            y.airspaceVersion(), y.reason(), y.requestId(), y.yieldedMs()))
                    .toList();
            return new CorridorDetailView(corridor.corridorId(), corridor.status(),
                    corridor.version(), corridor.startMs(), corridor.endMs(),
                    corridor.altMin(), corridor.altMax(), corridor.priority(),
                    corridor.revokeReason(), corridor.revokedMs(), corridor.createdMs(),
                    events, yields);
        });
    }

    /** 查询某原占用关联的全部重新申请历史（按 seq 升序）。 */
    public List<ReapplyHistoryView> listReapplies(String occupancyId) {
        if (occupancyRepo.findOccupancy(occupancyId) == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "OCCUPANCY_NOT_FOUND",
                    "占用不存在: " + occupancyId);
        }
        return occupancyRepo.findReappliesByOccupancy(occupancyId).stream()
                .map(r -> new ReapplyHistoryView(r.seq(), r.corridorId(), r.oldOccupancyId(),
                        r.newOccupancyId(), r.routeId(), r.reviewId(), r.result(),
                        r.requestId(), r.appliedMs()))
                .toList();
    }

    // ============================ 工具方法 ============================

    private void validateCorridorShape(CorridorCreateRequest request) {
        if (request.xMin() >= request.xMax() || request.yMin() >= request.yMax()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CORRIDOR_GEOMETRY",
                    "走廊必须是非退化轴对齐矩形：xMin < xMax 且 yMin < yMax");
        }
        if (request.endMs() <= request.startMs()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CORRIDOR_WINDOW",
                    "走廊起止时间非法：endMs 必须大于 startMs（UTC 左闭右开）");
        }
        if (request.altMax() <= request.altMin()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CORRIDOR_ALTITUDE",
                    "走廊高度范围非法：altMax 必须大于 altMin（左闭右开）");
        }
    }

    private SpaceTimeWindow toWindow(long startMs, long endMs, int altMin, int altMax) {
        try {
            return new SpaceTimeWindow(startMs, endMs, altMin, altMax);
        } catch (IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_OCCUPANCY_WINDOW",
                    ex.getMessage());
        }
    }

    private void ensureClear(ReviewPo review, String scene) {
        if (!"CLEAR".equals(review.conclusion())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REVIEW_BLOCKED",
                    scene + "航线未通过禁飞审查，命中禁飞区: "
                            + String.join(",", review.hitZoneIds()));
        }
    }

    /**
     * 走廊建立时对相交的常规占用做可让渡裁决，不满足条件整次 422 并区分原因。
     * 调用方已排除应急占用；此处只剩常规 ACTIVE（可/不可让渡）与常规 DEPARTED。
     */
    private void ensureYieldable(OccupancyPo occ) {
        if (OccupancyStatus.DEPARTED.name().equals(occ.status())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "OCCUPANCY_DEPARTED",
                    "与走廊时空相交的占用已起飞，不能让渡: " + occ.occupancyId());
        }
        if (!occ.yieldable()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "OCCUPANCY_NOT_YIELDABLE",
                    "与走廊时空相交的常规占用不可让渡: " + occ.occupancyId());
        }
    }

    /** 占用时空窗（左闭右开时间/高度）与走廊覆盖段是否三维相交。 */
    private boolean spacetimeHits(CorridorCreateRequest corridor, OccupancyPo occ,
                                  Map<String, List<Point>> routePointsCache) {
        List<Point> points = routePointsCache.computeIfAbsent(occ.routeId(),
                id -> {
                    RoutePo route = routeRepo.findRoute(id);
                    return route == null ? List.of() : route.points();
                });
        if (points.isEmpty()) {
            return false;
        }
        return Geometry.polylineHitsCorridor(points,
                corridor.xMin(), corridor.yMin(), corridor.xMax(), corridor.yMax(),
                occ.startMs(), occ.endMs(), occ.altMin(), occ.altMax(),
                corridor.startMs(), corridor.endMs(), corridor.altMin(), corridor.altMax());
    }

    /** 两条走廊是否同优先级时空重叠：闭矩形相交且时间/高度左闭右开相交。 */
    private static boolean corridorsOverlap(CorridorCreateRequest a, CorridorPo b) {
        boolean horizontal = a.xMin() <= b.xMax() && b.xMin() <= a.xMax()
                && a.yMin() <= b.yMax() && b.yMin() <= a.yMax();
        return horizontal
                && Geometry.halfOpenOverlap(a.startMs(), a.endMs(), b.startMs(), b.endMs())
                && Geometry.halfOpenOverlap(a.altMin(), a.altMax(), b.altMin(), b.altMax());
    }

    private OccupancyResult toOccupancyResult(String occupancyId, String routeId, String kind,
                                              SpaceTimeWindow window, String reviewId,
                                              String corridorId) {
        return new OccupancyResult(occupancyId, routeId, kind,
                OccupancyStatus.ACTIVE.name(), window.startMs(), window.endMs(),
                window.altMin(), window.altMax(), window.endMs() - window.startMs(),
                reviewId, corridorId);
    }

    private OccupancyView toView(OccupancyPo po) {
        return new OccupancyView(po.occupancyId(), po.routeId(), po.kind(), po.status(),
                po.yieldable(), po.startMs(), po.endMs(), po.altMin(), po.altMax(),
                po.endMs() - po.startMs(), po.reviewId(), po.corridorId(), po.createdMs(),
                po.yieldedMs(), po.yieldCorridorId(), po.yieldReason(), po.departedMs());
    }

    private static String newOccupancyId() {
        return "occ_" + UUID.randomUUID();
    }

    private long nowMillis() {
        return clock.instant().toEpochMilli();
    }
}
