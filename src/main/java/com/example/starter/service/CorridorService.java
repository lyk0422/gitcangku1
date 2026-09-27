package com.example.starter.service;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.CorridorCreateRequest;
import com.example.starter.api.dto.CorridorDto;
import com.example.starter.api.dto.CorridorRevokeRequest;
import com.example.starter.api.dto.CorridorResult;
import com.example.starter.api.dto.DepartureRegisterRequest;
import com.example.starter.api.dto.DepartureResult;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.OccupancyCreateRequest;
import com.example.starter.api.dto.OccupancyEventDto;
import com.example.starter.api.dto.OccupancyReapplyRequest;
import com.example.starter.api.dto.OccupancyResult;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.YieldOutcomeDto;
import com.example.starter.api.dto.YieldRecordDto;
import com.example.starter.domain.CorridorStatus;
import com.example.starter.domain.Geometry;
import com.example.starter.domain.OccupancyEventType;
import com.example.starter.domain.OccupancyKind;
import com.example.starter.domain.OccupancyStatus;
import com.example.starter.domain.Point;
import com.example.starter.domain.ReviewConclusion;
import com.example.starter.repo.AirspaceRepository;
import com.example.starter.repo.CorridorPo;
import com.example.starter.repo.CorridorRepository;
import com.example.starter.repo.OccupancyEventPo;
import com.example.starter.repo.OccupancyPo;
import com.example.starter.repo.OccupancyRepository;
import com.example.starter.repo.ReviewPo;
import com.example.starter.repo.ReviewRepository;
import com.example.starter.repo.RoutePo;
import com.example.starter.repo.RouteRepository;
import com.example.starter.repo.YieldRecordPo;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * 空域应急走廊与占用业务服务。
 *
 * <p><b>裁决顺序。</b>走廊创建/撤销、常规/应急占用登记、重新申请与起飞登记都在事务内
 * 先更新 coord_lock 协调锁行取得行级排他锁（H2 MVStore 与 MySQL InnoDB 语义一致），
 * 因而这些操作按事务提交顺序全序裁决，不会出现同一时空既有走廊又有未让渡常规占用：</p>
 * <ul>
 *   <li>占用先提交、走廊后提交：走廊事务在锁内读到该 ACTIVE 常规占用，可让渡则同事务
 *       YIELDED 并固化历史；不可让渡或已起飞则整次 422 回滚；</li>
 *   <li>走廊先提交、占用后提交：常规占用在锁内读到走廊，时空高度相交即 409 并带走廊标识；
 *       应急占用不受走廊排斥，但其审查必须为当前版本 CLEAR。</li>
 * </ul>
 *
 * <p><b>区间语义。</b>所有时间为 UTC epoch 毫秒，时间窗与高度窗左闭右开；
 * 相交判定为 s1 &lt; e2 且 s2 &lt; e1，实际重叠窗为 [max(s1,s2), min(e1,e2))。</p>
 *
 * <p><b>不可变历史。</b>让渡记录（yield_record）与占用事件（occupancy_event）只追加，
 * 字段、排序（seq 升序）、毫秒精度与 null 语义保持稳定；查询不修改状态。</p>
 */
@Service
public class CorridorService {

    public static final String KIND_CORRIDOR_CREATE = "CORRIDOR_CREATE";
    public static final String KIND_CORRIDOR_REVOKE = "CORRIDOR_REVOKE";
    public static final String KIND_REGULAR_OCCUPANCY = "REGULAR_OCCUPANCY";
    public static final String KIND_EMERGENCY_OCCUPANCY = "EMERGENCY_OCCUPANCY";
    public static final String KIND_OCCUPANCY_REAPPLY = "OCCUPANCY_REAPPLY";
    public static final String KIND_DEPARTURE_REGISTER = "DEPARTURE_REGISTER";

    private final CorridorRepository corridorRepo;
    private final OccupancyRepository occupancyRepo;
    private final ReviewRepository reviewRepo;
    private final RouteRepository routeRepo;
    private final AirspaceRepository airspaceRepo;
    private final IdempotentExecutor idempotency;
    private final Clock clock;
    private final TransactionTemplate txTemplate;

    public CorridorService(CorridorRepository corridorRepo,
                           OccupancyRepository occupancyRepo,
                           ReviewRepository reviewRepo,
                           RouteRepository routeRepo,
                           AirspaceRepository airspaceRepo,
                           IdempotentExecutor idempotency,
                           Clock clock,
                           PlatformTransactionManager transactionManager) {
        this.corridorRepo = corridorRepo;
        this.occupancyRepo = occupancyRepo;
        this.reviewRepo = reviewRepo;
        this.routeRepo = routeRepo;
        this.airspaceRepo = airspaceRepo;
        this.idempotency = idempotency;
        this.clock = clock;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    // ============================ 走廊 ============================

    /** 建立应急走廊：相交的可让渡常规 ACTIVE 占用同事务标 YIELDED；存在不可让渡或已起飞占用时 422。 */
    public MutationResponse createCorridor(CorridorCreateRequest request) {
        return idempotency.execute(request.requestId(), KIND_CORRIDOR_CREATE, request, () -> {
            validateRectangle(request.xMin(), request.yMin(), request.xMax(), request.yMax());
            validateTimeWindow(request.startUtc(), request.endUtc());
            validateAltitudeWindow(request.altMin(), request.altMax());
            // 先取协调锁：与占用登记、起飞登记、审查、禁飞区变更按提交顺序互斥
            airspaceRepo.getGlobalVersionForUpdate();
            if (corridorRepo.findCorridor(request.corridorId()) != null) {
                throw new ApiException(HttpStatus.CONFLICT, "CORRIDOR_ALREADY_EXISTS",
                        "应急走廊已存在: " + request.corridorId());
            }
            // 同优先级应急走廊不得在时间、高度与空间上重叠（边界接触也算重叠）
            for (CorridorPo other : corridorRepo.findActiveByPriority(request.priority())) {
                if (windowsOverlap(request.startUtc(), request.endUtc(),
                        other.startUtc(), other.endUtc())
                        && windowsOverlap(request.altMin(), request.altMax(),
                        other.altMin(), other.altMax())
                        && Geometry.rectanglesIntersect(request.xMin(), request.yMin(),
                        request.xMax(), request.yMax(),
                        other.xMin(), other.yMin(), other.xMax(), other.yMax())) {
                    throw new ApiException(HttpStatus.CONFLICT, "CORRIDOR_OVERLAP",
                            "与同优先级应急走廊时空重叠: " + other.corridorId());
                }
            }

            // 先验证完整后态：检索所有与走廊时空相交的常规 ACTIVE 占用，
            // 全部可让渡才允许提交；任一不可让渡或已起飞则整次 422，不留部分结果。
            List<OccupancyPo> activeRegular = occupancyRepo.findActiveRegularOccupancies();
            List<OccupancyPo> toYield = new ArrayList<>();
            List<String> blocked = new ArrayList<>();
            for (OccupancyPo occ : activeRegular) {
                if (!occupancyIntersectsCorridor(occ, request)) {
                    continue;
                }
                if (occ.departed()) {
                    blocked.add(occ.occupancyId() + "(已起飞不可让渡)");
                } else if (!occ.yieldable()) {
                    blocked.add(occ.occupancyId() + "(占用声明不可让渡)");
                } else {
                    toYield.add(occ);
                }
            }
            if (!blocked.isEmpty()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CORRIDOR_OCCUPANCY_BLOCKED",
                        "存在不可让渡或已起飞的常规占用，走廊创建被拒绝: " + String.join(", ", blocked));
            }

            // 走廊创建是空域变更：推进全局版本，走廊版本等于新版本
            long corridorVersion = airspaceRepo.incrementGlobalVersion();
            long now = nowMillis();
            corridorRepo.insertCorridor(new CorridorPo(request.corridorId(), request.priority(),
                    request.xMin(), request.yMin(), request.xMax(), request.yMax(),
                    request.startUtc(), request.endUtc(), request.altMin(), request.altMax(),
                    CorridorStatus.ACTIVE.name(), request.reason(), request.createdBy(),
                    corridorVersion, null, now, null));

            List<YieldOutcomeDto> yields = new ArrayList<>(toYield.size());
            for (OccupancyPo occ : toYield) {
                // 条件更新兜底：持锁下必须恰好让渡一行 ACTIVE 未起飞占用
                int updated = occupancyRepo.markYielded(occ.occupancyId(), request.corridorId());
                if (updated != 1) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CORRIDOR_OCCUPANCY_BLOCKED",
                            "占用状态在裁决中变化，走廊创建被拒绝: " + occ.occupancyId());
                }
                // 固化原审查版本、走廊版本与让渡原因（不可变）
                occupancyRepo.insertYieldRecord(new YieldRecordPo(0L, occ.occupancyId(),
                        request.corridorId(), corridorVersion, occ.reviewId(),
                        occ.routeVersion(), occ.airspaceVersion(), request.reason(), now));
                occupancyRepo.insertEvent(new OccupancyEventPo(0L, occ.occupancyId(),
                        OccupancyEventType.YIELDED.name(), now,
                        request.corridorId(), null, null, request.reason()));
                long overlapStart = Math.max(occ.startUtc(), request.startUtc());
                long overlapEnd = Math.min(occ.endUtc(), request.endUtc());
                yields.add(new YieldOutcomeDto(occ.occupancyId(), occ.reviewId(),
                        occ.routeVersion(), occ.airspaceVersion(),
                        overlapStart, overlapEnd, overlapEnd - overlapStart, request.reason()));
            }

            return new MutationResponse(request.requestId(), false,
                    new CorridorResult(request.corridorId(), CorridorStatus.ACTIVE.name(),
                            request.priority(), corridorVersion,
                            request.startUtc(), request.endUtc(),
                            request.endUtc() - request.startUtc(),
                            yields.size(), List.copyOf(yields)));
        });
    }

    /** 撤销应急走廊：只影响后续占用；已让渡占用不自动恢复，必须重新审查并显式申请。 */
    public MutationResponse revokeCorridor(CorridorRevokeRequest request) {
        return idempotency.execute(request.requestId(), KIND_CORRIDOR_REVOKE, request, () -> {
            // 取协调锁：与占用登记、走廊创建按提交顺序互斥
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
            long now = nowMillis();
            int updated = corridorRepo.markRevoked(request.corridorId(), newVersion, now);
            if (updated != 1) {
                throw new ApiException(HttpStatus.CONFLICT, "CORRIDOR_ALREADY_REVOKED",
                        "应急走廊状态已变化: " + request.corridorId());
            }
            // 不恢复任何已让渡占用、不追加占用事件：撤销只影响后续占用
            return new MutationResponse(request.requestId(), false,
                    new CorridorResult(corridor.corridorId(), CorridorStatus.REVOKED.name(),
                            corridor.priority(), newVersion,
                            corridor.startUtc(), corridor.endUtc(),
                            corridor.endUtc() - corridor.startUtc(), 0, List.of()));
        });
    }

    // ============================ 占用 ============================

    /** 登记空域占用（常规命中有效走廊 409；应急仍须通过当前版本 CLEAR 禁飞审查）。 */
    public MutationResponse createOccupancy(OccupancyCreateRequest request) {
        OccupancyKind kind = parseKind(request.kind());
        if (kind == OccupancyKind.REGULAR) {
            if (request.yieldable() == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "YIELDABLE_REQUIRED",
                        "常规占用必须显式声明 yieldable");
            }
        } else {
            if (request.yieldable() != null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "YIELDABLE_NOT_APPLICABLE",
                        "应急占用不适用 yieldable 声明");
            }
        }
        String dedupKind = kind == OccupancyKind.REGULAR
                ? KIND_REGULAR_OCCUPANCY : KIND_EMERGENCY_OCCUPANCY;
        return idempotency.execute(request.requestId(), dedupKind, request, () ->
                registerOccupancy(request.occupancyId(), null, kind, request.routeId(),
                        request.reviewId(), request.startUtc(), request.endUtc(),
                        request.altMin(), request.altMax(),
                        kind == OccupancyKind.REGULAR ? request.yieldable() : Boolean.TRUE,
                        request.requestId()));
    }

    /** 已让渡占用重新审查后的显式重新申请；新占用为全新 ACTIVE 常规占用，原历史不变。 */
    public MutationResponse reapplyOccupancy(OccupancyReapplyRequest request) {
        return idempotency.execute(request.requestId(), KIND_OCCUPANCY_REAPPLY, request, () -> {
            validateTimeWindow(request.startUtc(), request.endUtc());
            validateAltitudeWindow(request.altMin(), request.altMax());
            // 取协调锁后验证完整后态
            long globalVersion = airspaceRepo.getGlobalVersionForUpdate();
            OccupancyPo parent = occupancyRepo.findOccupancy(request.parentOccupancyId());
            if (parent == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "OCCUPANCY_NOT_FOUND",
                        "原占用不存在: " + request.parentOccupancyId());
            }
            if (!OccupancyStatus.YIELDED.name().equals(parent.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "PARENT_NOT_YIELDED",
                        "原占用不是已让渡状态，不能重新申请: " + request.parentOccupancyId());
            }
            if (occupancyRepo.findOccupancy(request.occupancyId()) != null) {
                throw new ApiException(HttpStatus.CONFLICT, "OCCUPANCY_ALREADY_EXISTS",
                        "占用已存在: " + request.occupancyId());
            }
            ReviewPo review = requireClearReviewForRoute(
                    request.reviewId(), parent.routeId());
            rejectIfHitsActiveCorridor(request.occupancyId(), review.pointsSnapshot(),
                    request.startUtc(), request.endUtc(), request.altMin(), request.altMax());
            requireReviewCurrent(review, globalVersion);

            long now = nowMillis();
            OccupancyPo po = newOccupancy(request.occupancyId(), parent.routeId(), review,
                    OccupancyKind.REGULAR, request.startUtc(), request.endUtc(),
                    request.altMin(), request.altMax(), true,
                    request.parentOccupancyId(), now);
            occupancyRepo.insertOccupancy(po);
            occupancyRepo.insertEvent(new OccupancyEventPo(0L, request.occupancyId(),
                    OccupancyEventType.CREATED.name(), now,
                    null, null, review.reviewId(), null));
            // 在原占用上追加 REAPPLIED 事件，关联新占用；原占用记录与让渡历史保持不变
            occupancyRepo.insertEvent(new OccupancyEventPo(0L, request.parentOccupancyId(),
                    OccupancyEventType.REAPPLIED.name(), now,
                    null, request.occupancyId(), null, null));
            return new MutationResponse(request.requestId(), false, toOccupancyResult(po));
        });
    }

    /** 起飞登记：仅 ACTIVE 未起飞占用可登记；已让渡占用必须先重新申请。 */
    public MutationResponse registerDeparture(DepartureRegisterRequest request) {
        return idempotency.execute(request.requestId(), KIND_DEPARTURE_REGISTER, request, () -> {
            // 取协调锁：与走廊创建互斥——已起飞占用不可让渡，让渡中的占用不能起飞
            airspaceRepo.getGlobalVersionForUpdate();
            OccupancyPo occ = occupancyRepo.findOccupancy(request.occupancyId());
            if (occ == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "OCCUPANCY_NOT_FOUND",
                        "占用不存在: " + request.occupancyId());
            }
            if (OccupancyStatus.YIELDED.name().equals(occ.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "OCCUPANCY_YIELDED",
                        "占用已让渡，必须重新审查并显式重新申请后才能起飞: " + occ.occupancyId());
            }
            if (occ.departed()) {
                throw new ApiException(HttpStatus.CONFLICT, "OCCUPANCY_ALREADY_DEPARTED",
                        "占用已登记起飞: " + occ.occupancyId());
            }
            long now = nowMillis();
            int updated = occupancyRepo.markDeparted(occ.occupancyId(), now);
            if (updated != 1) {
                throw new ApiException(HttpStatus.CONFLICT, "OCCUPANCY_ALREADY_DEPARTED",
                        "占用状态在裁决中变化: " + occ.occupancyId());
            }
            occupancyRepo.insertEvent(new OccupancyEventPo(0L, occ.occupancyId(),
                    OccupancyEventType.DEPARTED.name(), now, null, null, null, null));
            return new MutationResponse(request.requestId(), false,
                    new DepartureResult(occ.occupancyId(), OccupancyStatus.ACTIVE.name(), true, now));
        });
    }

    // ============================ 查询（不修改状态） ============================

    /** 查询单个走廊；不存在 404。 */
    public CorridorDto getCorridor(String corridorId) {
        CorridorPo po = corridorRepo.findCorridor(corridorId);
        if (po == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CORRIDOR_NOT_FOUND",
                    "应急走廊不存在: " + corridorId);
        }
        return toCorridorDto(po);
    }

    /** 列出全部走廊（按 corridorId 升序）。 */
    public List<CorridorDto> listCorridors() {
        return corridorRepo.findAll().stream().map(this::toCorridorDto).toList();
    }

    /** 查询单个占用；不存在 404。 */
    public OccupancyResult getOccupancy(String occupancyId) {
        OccupancyPo po = occupancyRepo.findOccupancy(occupancyId);
        if (po == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "OCCUPANCY_NOT_FOUND",
                    "占用不存在: " + occupancyId);
        }
        return toOccupancyResult(po);
    }

    /** 查询让渡不可变历史；corridorId 为 null 时返回全部，固定按 seq 升序。 */
    public List<YieldRecordDto> listYields(String corridorId) {
        List<YieldRecordPo> records = corridorId == null || corridorId.isBlank()
                ? occupancyRepo.findAllYieldRecords()
                : occupancyRepo.findYieldRecordsByCorridor(corridorId);
        return records.stream().map(CorridorService::toYieldDto).toList();
    }

    /** 查询占用处置不可变事件，固定按 seq 升序；占用不存在 404。 */
    public List<OccupancyEventDto> listOccupancyEvents(String occupancyId) {
        if (occupancyRepo.findOccupancy(occupancyId) == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "OCCUPANCY_NOT_FOUND",
                    "占用不存在: " + occupancyId);
        }
        return occupancyRepo.findEventsByOccupancy(occupancyId).stream()
                .map(CorridorService::toEventDto).toList();
    }

    // ============================ 内部实现 ============================

    /**
     * 占用登记/重新申请共用的后态验证与提交（调用方已持有幂等事务）。
     * 先取协调锁并验证完整后态（审查存在、CLEAR、当前版本、航线一致、常规占用不撞走廊、
     * 占用标识不重复），全部通过后才插入占用与事件，一个事务提交。
     */
    private MutationResponse registerOccupancy(String occupancyId, String parentOccupancyId,
                                               OccupancyKind kind, String routeId, String reviewId,
                                               long startUtc, long endUtc,
                                               int altMin, int altMax, boolean yieldable,
                                               String requestId) {
        validateTimeWindow(startUtc, endUtc);
        validateAltitudeWindow(altMin, altMax);
        long globalVersion = airspaceRepo.getGlobalVersionForUpdate();
        if (occupancyRepo.findOccupancy(occupancyId) != null) {
            throw new ApiException(HttpStatus.CONFLICT, "OCCUPANCY_ALREADY_EXISTS",
                    "占用已存在: " + occupancyId);
        }
        // 先验证审查凭证（存在、航线一致、CLEAR：应急也不能绕过禁区）
        ReviewPo review = requireClearReviewForRoute(reviewId, routeId);
        if (kind == OccupancyKind.REGULAR) {
            // 再做走廊排斥判定并在响应中给出走廊标识：走廊先提交时即使旧审查版本已过期，
            // 客户端也能首先拿到可操作的走廊冲突信息
            rejectIfHitsActiveCorridor(occupancyId, review.pointsSnapshot(),
                    startUtc, endUtc, altMin, altMax);
        }
        // 最后校验航线/空域版本为当前版本
        requireReviewCurrent(review, globalVersion);
        // 应急占用不做走廊排斥：走廊覆盖段本就只允许应急航线使用；
        // 但其审查必须 CLEAR，不能以走廊绕过禁飞区。
        long now = nowMillis();
        OccupancyPo po = newOccupancy(occupancyId, routeId, review, kind,
                startUtc, endUtc, altMin, altMax, yieldable, parentOccupancyId, now);
        occupancyRepo.insertOccupancy(po);
        occupancyRepo.insertEvent(new OccupancyEventPo(0L, occupancyId,
                OccupancyEventType.CREATED.name(), now, null, null, reviewId, null));
        return new MutationResponse(requestId, false, toOccupancyResult(po));
    }

    /**
     * 验证审查凭证的存在性与可使用性：审查存在、属于指定航线、结论 CLEAR。
     * 应急航线同样必须 CLEAR，不能以走廊绕过禁飞区。版本是否当前由
     * {@link #requireReviewCurrent} 在走廊冲突判定之后单独校验。
     */
    private ReviewPo requireClearReviewForRoute(String reviewId, String routeId) {
        ReviewPo review = reviewRepo.findReview(reviewId);
        if (review == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND",
                    "审查记录不存在: " + reviewId);
        }
        if (!review.routeId().equals(routeId)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REVIEW_ROUTE_MISMATCH",
                    "审查不属于该航线: review=" + reviewId + ", route=" + routeId);
        }
        if (!ReviewConclusion.CLEAR.name().equals(review.conclusion())) {
            throw new ApiException(HttpStatus.CONFLICT, "REVIEW_NOT_CLEAR",
                    "占用必须依据 CLEAR 审查，应急航线也不能绕过禁飞区: " + reviewId);
        }
        return review;
    }

    /**
     * 校验审查固化的航线版本与空域版本均为当前版本；不匹配返回 409，
     * 要求重新审查后再登记占用。
     */
    private void requireReviewCurrent(ReviewPo review, long globalVersion) {
        RoutePo route = routeRepo.findRoute(review.routeId());
        if (route == null || route.version() != review.routeVersion()
                || review.airspaceVersion() != globalVersion) {
            throw new ApiException(HttpStatus.CONFLICT, "REVIEW_NOT_CURRENT",
                    "审查版本不是当前版本，必须重新审查后再登记占用: review=" + review.reviewId());
        }
    }

    /**
     * 常规占用与有效走廊的排斥判定：走廊未撤销、时间窗（左闭右开）相交、高度窗相交、
     * 占用路径快照折线与走廊矩形相交（含边界接触）即拒绝，409 携带走廊标识。
     * 走廊自然到期后新占用的时间窗不再与其相交，自动允许，无需改状态。
     */
    private void rejectIfHitsActiveCorridor(String occupancyId, List<Point> points,
                                            long startUtc, long endUtc,
                                            int altMin, int altMax) {
        for (CorridorPo c : corridorRepo.findAll()) {
            if (!CorridorStatus.ACTIVE.name().equals(c.status())) {
                continue;
            }
            if (occupancyIntersects(c, points, startUtc, endUtc, altMin, altMax)) {
                throw new ApiException(HttpStatus.CONFLICT, "CORRIDOR_CONFLICT",
                        "常规占用命中有效应急走廊: " + c.corridorId()
                                + "（占用=" + occupancyId + "）");
            }
        }
    }

    private static boolean occupancyIntersectsCorridor(OccupancyPo occ, CorridorCreateRequest c) {
        return occupancyIntersects(c.xMin(), c.yMin(), c.xMax(), c.yMax(),
                occ.pointsSnapshot(), occ.startUtc(), occ.endUtc(), occ.altMin(), occ.altMax(),
                c.startUtc(), c.endUtc(), c.altMin(), c.altMax());
    }

    private static boolean occupancyIntersects(CorridorPo c, List<Point> points,
                                               long startUtc, long endUtc,
                                               int altMin, int altMax) {
        return occupancyIntersects(c.xMin(), c.yMin(), c.xMax(), c.yMax(), points,
                startUtc, endUtc, altMin, altMax,
                c.startUtc(), c.endUtc(), c.altMin(), c.altMax());
    }

    /**
     * 时空高度四维相交判定：时间窗相交（左闭右开）且高度窗相交（左闭右开）
     * 且路径折线与走廊闭矩形相交（含边界）。
     */
    private static boolean occupancyIntersects(int cxMin, int cyMin, int cxMax, int cyMax,
                                               List<Point> points,
                                               long s1, long e1, int a1Min, int a1Max,
                                               long s2, long e2, int a2Min, int a2Max) {
        return windowsOverlap(s1, e1, s2, e2)
                && windowsOverlap(a1Min, a1Max, a2Min, a2Max)
                && Geometry.polylineHitsRectangle(points, cxMin, cyMin, cxMax, cyMax);
    }

    /** 左闭右开区间相交：两区间存在公共时刻/高度。 */
    private static boolean windowsOverlap(long start1, long end1, long start2, long end2) {
        return start1 < end2 && start2 < end1;
    }

    private OccupancyPo newOccupancy(String occupancyId, String routeId, ReviewPo review,
                                     OccupancyKind kind, long startUtc, long endUtc,
                                     int altMin, int altMax, boolean yieldable,
                                     String parentOccupancyId, long now) {
        return new OccupancyPo(occupancyId, routeId, review.reviewId(),
                review.routeVersion(), review.airspaceVersion(), kind.name(),
                startUtc, endUtc, altMin, altMax,
                OccupancyStatus.ACTIVE.name(), yieldable, false,
                null, parentOccupancyId, List.copyOf(review.pointsSnapshot()), now, null);
    }

    private OccupancyResult toOccupancyResult(OccupancyPo po) {
        List<RoutePointDto> snapshot = po.pointsSnapshot().stream()
                .map(p -> new RoutePointDto(p.x(), p.y())).toList();
        Boolean yieldable = OccupancyKind.EMERGENCY.name().equals(po.kind()) ? null : po.yieldable();
        return new OccupancyResult(po.occupancyId(), po.routeId(), po.reviewId(),
                po.kind(), po.status(), po.startUtc(), po.endUtc(), po.endUtc() - po.startUtc(),
                po.altMin(), po.altMax(), po.altMax() - po.altMin(),
                yieldable, po.departed(), po.corridorId(), po.parentOccupancyId(), snapshot);
    }

    private CorridorDto toCorridorDto(CorridorPo po) {
        long now = nowMillis();
        boolean effective = CorridorStatus.ACTIVE.name().equals(po.status())
                && po.startUtc() <= now && now < po.endUtc();
        return new CorridorDto(po.corridorId(), po.priority(), po.status(), effective,
                po.xMin(), po.yMin(), po.xMax(), po.yMax(),
                po.startUtc(), po.endUtc(), po.altMin(), po.altMax(),
                po.reason(), po.createdBy(),
                po.createdAirspaceVersion(), po.revokedAirspaceVersion(),
                po.createdAt(), po.revokedAt());
    }

    private static YieldRecordDto toYieldDto(YieldRecordPo po) {
        return new YieldRecordDto(po.seq(), po.occupancyId(), po.corridorId(),
                po.corridorVersion(), po.reviewId(), po.routeVersion(),
                po.airspaceVersion(), po.reason(), po.createdAt());
    }

    private static OccupancyEventDto toEventDto(OccupancyEventPo po) {
        return new OccupancyEventDto(po.seq(), po.occupancyId(), po.eventType(), po.eventTime(),
                po.corridorId(), po.relatedOccupancyId(), po.reviewId(), po.reason());
    }

    private static OccupancyKind parseKind(String kind) {
        try {
            OccupancyKind parsed = OccupancyKind.valueOf(kind);
            if (parsed != OccupancyKind.REGULAR && parsed != OccupancyKind.EMERGENCY) {
                throw new IllegalArgumentException();
            }
            return parsed;
        } catch (IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_OCCUPANCY_KIND",
                    "占用类型必须是 REGULAR 或 EMERGENCY: " + kind);
        }
    }

    private void validateRectangle(int xMin, int yMin, int xMax, int yMax) {
        if (xMin >= xMax || yMin >= yMax) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CORRIDOR_RECTANGLE",
                    "走廊必须是非退化轴对齐矩形：xMin < xMax 且 yMin < yMax");
        }
    }

    private static void validateTimeWindow(long startUtc, long endUtc) {
        if (startUtc >= endUtc) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TIME_WINDOW",
                    "时间窗必须左闭右开且非空：startUtc < endUtc（UTC epoch 毫秒）");
        }
    }

    private static void validateAltitudeWindow(int altMin, int altMax) {
        if (altMin >= altMax) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ALTITUDE_WINDOW",
                    "高度窗必须左闭右开且非空：altMin < altMax（米）");
        }
    }

    private long nowMillis() {
        return clock.instant().toEpochMilli();
    }
}
