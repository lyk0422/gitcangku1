package com.example.starter.service;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.CapacityBucketDto;
import com.example.starter.api.dto.CapacityBucketRequest;
import com.example.starter.api.dto.DepartRequest;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.PreemptionDto;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.ReviewResultDto;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.RouteReplaceRequest;
import com.example.starter.api.dto.RouteResult;
import com.example.starter.api.dto.RouteStateResult;
import com.example.starter.api.dto.ZoneCreateRequest;
import com.example.starter.api.dto.ZoneResult;
import com.example.starter.api.dto.ZoneRevokeRequest;
import com.example.starter.domain.DiversionPriority;
import com.example.starter.domain.Geometry;
import com.example.starter.domain.Point;
import com.example.starter.domain.PreemptionState;
import com.example.starter.domain.ReviewConclusion;
import com.example.starter.domain.RouteStatus;
import com.example.starter.domain.ZoneStatus;
import com.example.starter.repo.AirspaceRepository;
import com.example.starter.repo.CapacityBucketPo;
import com.example.starter.repo.CapacityRepository;
import com.example.starter.repo.DedupPo;
import com.example.starter.repo.OccupancyPo;
import com.example.starter.repo.PreemptionPo;
import com.example.starter.repo.ReviewPo;
import com.example.starter.repo.ReviewRepository;
import com.example.starter.repo.RoutePo;
import com.example.starter.repo.RouteRepository;
import com.example.starter.repo.ZonePo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 禁飞区与航线审查业务服务。
 *
 * <p>所有写操作在同一数据库事务内完成业务变更与幂等去重记录的原子提交；
 * 失败（含业务冲突）回滚事务，不占用 requestId。</p>
 *
 * <p>审核事务先锁全局空域版本行、再锁航线行：区域变更事务必须更新版本行，
 * 因而与审核互斥，保证审核使用的空域版本号与全部禁飞区来自同一已提交状态，
 * 不会产生“携带新版本、使用旧区域”或反之的结论。</p>
 *
 * <p>紧急备降抢占：EMERGENCY 审查在容量不足时，于同一事务内批准紧急航线、
 * 把全部受影响的未起飞 NORMAL 已批准航线转为 DISPLACED 并写入不可变抢占快照；
 * 任一版本冲突或状态不符（条件更新 0 行）都会抛出异常使整次回滚。
 * 审查、起飞登记、区域更新与紧急抢占都先取协调锁行，按事务提交顺序裁决。</p>
 */
@Service
public class AirspaceReviewService {

    static final String KIND_ZONE_CREATE = "ZONE_CREATE";
    static final String KIND_ZONE_REVOKE = "ZONE_REVOKE";
    static final String KIND_ROUTE_CREATE = "ROUTE_CREATE";
    static final String KIND_ROUTE_REPLACE = "ROUTE_REPLACE";
    static final String KIND_REVIEW = "REVIEW";
    static final String KIND_ROUTE_DEPART = "ROUTE_DEPART";
    static final String KIND_CAPACITY_BUCKET_CREATE = "CAPACITY_BUCKET_CREATE";

    /** 一分钟的毫秒数，用于时间窗规范化（向下取整到分钟）。 */
    private static final long MINUTE_MILLIS = 60_000L;

    private final AirspaceRepository airspaceRepo;
    private final RouteRepository routeRepo;
    private final ReviewRepository reviewRepo;
    private final CapacityRepository capacityRepo;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate txTemplate;

    public AirspaceReviewService(AirspaceRepository airspaceRepo,
                                 RouteRepository routeRepo,
                                 ReviewRepository reviewRepo,
                                 CapacityRepository capacityRepo,
                                 ObjectMapper objectMapper,
                                 Clock clock,
                                 PlatformTransactionManager transactionManager) {
        this.airspaceRepo = airspaceRepo;
        this.routeRepo = routeRepo;
        this.reviewRepo = reviewRepo;
        this.capacityRepo = capacityRepo;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    // ============================ 禁飞区 ============================

    /** 创建禁飞区；同键同参重放原结果，异参 409。 */
    public MutationResponse createZone(ZoneCreateRequest request) {
        return withIdempotency(request.requestId(), KIND_ZONE_CREATE, canonicalHash(request), () -> {
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
        return withIdempotency(request.requestId(), KIND_ZONE_REVOKE, canonicalHash(request), () -> {
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

    /** 创建航线（初始版本 1）。 */
    public MutationResponse createRoute(RouteCreateRequest request) {
        return withIdempotency(request.requestId(), KIND_ROUTE_CREATE, canonicalHash(request), () -> {
            List<Point> points = toPoints(request.points());
            validatePointsDistinct(points);
            if (routeRepo.findRoute(request.routeId()) != null) {
                throw new ApiException(HttpStatus.CONFLICT, "ROUTE_ALREADY_EXISTS",
                        "航线已存在: " + request.routeId());
            }
            routeRepo.insertRoute(request.routeId(), points);
            return new MutationResponse(request.requestId(), false,
                    new RouteResult(request.routeId(), 1));
        });
    }

    /** 替换航线点列，expectedVersion 不匹配返回 409；成功版本加一。 */
    public MutationResponse replaceRoute(RouteReplaceRequest request) {
        return withIdempotency(request.requestId(), KIND_ROUTE_REPLACE, canonicalHash(request), () -> {
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
            // 条件更新兜底并发替换：更新 0 行说明版本已被其他事务推进
            int updated = routeRepo.compareAndIncrementVersion(
                    request.routeId(), request.expectedVersion());
            if (updated == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                        "航线版本已变化，请使用最新 expectedVersion 重试");
            }
            routeRepo.deletePoints(request.routeId());
            routeRepo.insertPoints(request.routeId(), points);
            return new MutationResponse(request.requestId(), false,
                    new RouteResult(request.routeId(), request.expectedVersion() + 1));
        });
    }

    /** 起飞登记：仅 APPROVED 航线可登记；已起飞航线占用容量且不可被抢占。 */
    public MutationResponse depart(DepartRequest request) {
        return withIdempotency(request.requestId(), KIND_ROUTE_DEPART, canonicalHash(request), () -> {
            // 与审查/区域更新/紧急抢占同一协调锁，按事务提交顺序裁决
            airspaceRepo.getGlobalVersionForUpdate();
            RoutePo route = routeRepo.findRouteForUpdate(request.routeId());
            if (route == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND",
                        "航线不存在: " + request.routeId());
            }
            if (!RouteStatus.APPROVED.name().equals(route.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "INVALID_ROUTE_STATE",
                        "仅已批准未起飞航线可登记起飞，当前状态: " + route.status());
            }
            int updated = routeRepo.transitionStatus(
                    route.routeId(), RouteStatus.APPROVED.name(), RouteStatus.DEPARTED.name());
            if (updated == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "STATE_CONFLICT",
                        "航线状态已变化，请刷新后重试");
            }
            capacityRepo.markOccupancyDeparted(route.routeId());
            return new MutationResponse(request.requestId(), false,
                    new RouteStateResult(route.routeId(), route.version(), RouteStatus.DEPARTED.name()));
        });
    }

    // ============================ 时空容量桶 ============================

    /** 创建时空容量桶；同键同参重放原结果，桶已存在 409。 */
    public MutationResponse createCapacityBucket(CapacityBucketRequest request) {
        return withIdempotency(request.requestId(), KIND_CAPACITY_BUCKET_CREATE,
                canonicalHash(request), () -> {
                    Segment segment = normalizeSegment(request.cellX(), request.cellY(),
                            request.windowStart(), request.windowEnd());
                    // 与审查/起飞同一协调锁，按事务提交顺序裁决
                    airspaceRepo.getGlobalVersionForUpdate();
                    if (capacityRepo.findBucket(segment.key()) != null) {
                        throw new ApiException(HttpStatus.CONFLICT, "BUCKET_ALREADY_EXISTS",
                                "时空容量桶已存在: " + segment.key());
                    }
                    CapacityBucketPo po = new CapacityBucketPo(segment.key(), segment.cellX(),
                            segment.cellY(), segment.startMin(), segment.endMin(),
                            request.capacity(), nowMillis());
                    capacityRepo.insertBucket(po);
                    return new MutationResponse(request.requestId(), false, toBucketDto(po, 0));
                });
    }

    /** 查询全部容量桶及占用情况。 */
    public List<CapacityBucketDto> listCapacityBuckets() {
        return txTemplate.execute(status -> {
            List<CapacityBucketDto> result = new ArrayList<>();
            for (CapacityBucketPo bucket : capacityRepo.findAllBuckets()) {
                result.add(toBucketDto(bucket, capacityRepo.countOccupancy(bucket.bucketKey())));
            }
            return result;
        });
    }

    /** 查询抢占记录；routeId/state 可空表示不过滤。 */
    public List<PreemptionDto> listPreemptions(String routeId, String state) {
        if (state != null && !PreemptionState.PENDING.name().equals(state)
                && !PreemptionState.RESUBMITTED.name().equals(state)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PREEMPTION_STATE",
                    "state 只能是 PENDING 或 RESUBMITTED: " + state);
        }
        return txTemplate.execute(status -> {
            List<PreemptionDto> result = new ArrayList<>();
            for (PreemptionPo po : capacityRepo.findPreemptions(routeId, state)) {
                result.add(new PreemptionDto(po.preemptionId(), po.bucketKey(), po.routeId(),
                        po.displacedRouteVersion(), po.emergencyRouteId(), po.emergencyEventNo(),
                        po.emergencyReviewId(), po.state(), po.createdAt(), po.processedAt()));
            }
            return result;
        });
    }

    /** 查询全部被置换（DISPLACED）航线。 */
    public List<RouteStateResult> listDisplacedRoutes() {
        return txTemplate.execute(status -> {
            List<RouteStateResult> result = new ArrayList<>();
            for (RoutePo route : routeRepo.findRoutesByStatus(RouteStatus.DISPLACED.name())) {
                result.add(new RouteStateResult(route.routeId(), route.version(), route.status()));
            }
            return result;
        });
    }

    // ============================ 审核 ============================

    /** 提交审核：任一指定版本不是当前版本返回 409；结果不可变。 */
    public MutationResponse review(ReviewRequest request) {
        return withIdempotency(request.requestId(), KIND_REVIEW, reviewParamHash(request), () -> {
            Segment segment = normalizeSegment(request.cellX(), request.cellY(),
                    request.windowStart(), request.windowEnd());
            String priority = normalizePriority(request.priority());
            String eventNo = normalizeEventNo(priority, request.eventNo());
            // 先锁空域版本行：与任何区域创建/撤销事务互斥
            long globalVersion = airspaceRepo.getGlobalVersionForUpdate();
            // 再锁航线行：与航线替换事务互斥，保证版本号与点列一致
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
            // 被置换航线重新提交审查：关闭其未处理抢占记录（不自动复原状态）
            capacityRepo.closePendingPreemption(route.routeId(), nowMillis());
            // 持锁状态下读取全部有效禁飞区，与上面的版本号同属一个一致状态
            List<ZonePo> activeZones = airspaceRepo.findActiveZones();
            Set<String> hits = new TreeSet<>();
            for (ZonePo zone : activeZones) {
                if (Geometry.polylineHitsRectangle(route.points(),
                        zone.xMin(), zone.yMin(), zone.xMax(), zone.yMax())) {
                    hits.add(zone.zoneId());
                }
            }
            String conclusion = hits.isEmpty()
                    ? ReviewConclusion.CLEAR.name()
                    : ReviewConclusion.BLOCKED.name();
            String reviewId = "rv_" + UUID.randomUUID();
            if (ReviewConclusion.CLEAR.name().equals(conclusion)) {
                // 容量不足或抢占失败会抛异常，整次回滚（审核记录与去重键均不残留）
                allocateCapacity(segment, priority, eventNo, route, reviewId);
                routeRepo.markReviewed(route.routeId(), RouteStatus.APPROVED.name(),
                        priority, eventNo);
            }
            ReviewPo po = new ReviewPo(reviewId, request.routeId(), route.version(), globalVersion,
                    conclusion, new ArrayList<>(hits), List.copyOf(route.points()),
                    priority, eventNo, segment == null ? null : segment.key(),
                    request.requestId(), nowMillis());
            reviewRepo.insertReview(po);
            return new MutationResponse(request.requestId(), false, toDto(po, conclusion, true));
        });
    }

    /**
     * 容量分配：未声明时空段或未建桶不限制容量。
     * NORMAL 超容返回 422；EMERGENCY 先计算完整受影响 NORMAL 集合，
     * 移除后容量足够则同事务置换全部受影响航线并写入不可变抢占快照，
     * 否则返回 422 并列出不可抢占航线。
     */
    private void allocateCapacity(Segment segment, String priority, String eventNo,
                                  RoutePo route, String reviewId) {
        if (segment == null) {
            return;
        }
        CapacityBucketPo bucket = capacityRepo.findBucket(segment.key());
        if (bucket == null) {
            return;
        }
        List<OccupancyPo> occupants = capacityRepo.findOccupancy(segment.key());
        boolean selfOccupies = occupants.stream()
                .anyMatch(o -> o.routeId().equals(route.routeId()));
        int used = occupants.size();
        int needed = selfOccupies ? 0 : 1;
        if (used + needed <= bucket.capacity()) {
            if (!selfOccupies) {
                occupy(segment, route, reviewId, priority, eventNo);
            }
            return;
        }
        if (!DiversionPriority.EMERGENCY.name().equals(priority)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CAPACITY_EXCEEDED",
                    "时空桶容量不足：" + segment.key() + " 容量 " + bucket.capacity()
                            + "，已占用 " + used);
        }
        // 完整受影响集合：尚未起飞的 NORMAL 已批准航线（已起飞与 EMERGENCY 不可抢占）
        List<OccupancyPo> preemptable = new ArrayList<>();
        List<String> nonPreemptable = new ArrayList<>();
        for (OccupancyPo o : occupants) {
            if (o.routeId().equals(route.routeId())) {
                continue;
            }
            if (RouteStatus.APPROVED.name().equals(o.status())
                    && DiversionPriority.NORMAL.name().equals(o.priority())) {
                preemptable.add(o);
            } else {
                nonPreemptable.add(o.routeId());
            }
        }
        if (used - preemptable.size() + needed > bucket.capacity()) {
            nonPreemptable.sort(String::compareTo);
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CAPACITY_INSUFFICIENT",
                    "移除全部可抢占航线后容量仍不足：" + segment.key(),
                    Map.of("nonPreemptableRouteIds", List.copyOf(nonPreemptable)));
        }
        // 同事务原子置换：任一状态不符（条件更新 0 行）抛异常整次回滚
        for (OccupancyPo victim : preemptable) {
            // 锁定被置换航线行并读取置换时版本（与航线替换事务互斥）
            RoutePo victimRoute = routeRepo.findRouteForUpdate(victim.routeId());
            int transitioned = routeRepo.transitionStatus(victim.routeId(),
                    RouteStatus.APPROVED.name(), RouteStatus.DISPLACED.name());
            if (victimRoute == null || transitioned == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "STATE_CONFLICT",
                        "被抢占航线状态已变化，整次回滚: " + victim.routeId());
            }
            capacityRepo.deleteOccupancy(segment.key(), victim.routeId());
            capacityRepo.insertPreemption(new PreemptionPo("pm_" + UUID.randomUUID(),
                    segment.key(), victim.routeId(), victimRoute.version(),
                    route.routeId(), eventNo, reviewId,
                    PreemptionState.PENDING.name(), nowMillis(), null));
        }
        if (!selfOccupies) {
            occupy(segment, route, reviewId, priority, eventNo);
        }
    }

    private void occupy(Segment segment, RoutePo route, String reviewId,
                        String priority, String eventNo) {
        capacityRepo.insertOccupancy(new OccupancyPo(segment.key(), route.routeId(), reviewId,
                priority, eventNo, RouteStatus.APPROVED.name(), nowMillis()));
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

    // ============================ 幂等与事务 ============================

    /**
     * 在事务内执行幂等写操作：
     * 同键同参返回首次成功结果（标记 replayed=true）；同键异参/异种操作抛 409；
     * 业务异常回滚、不占用 requestId；去重记录与业务变更同一事务原子提交。
     *
     * <p>同键并发时，落败事务可能先撞上唯一约束（DuplicateKeyException），
     * 也可能在持锁后读到赢家已提交的状态而抛业务冲突（409）；两种情况都在
     * 回滚后用新事务查询去重表：赢家同键同参已提交则重放原结果，否则按原错误抛出。</p>
     */
    private MutationResponse withIdempotency(String requestId, String kind, String paramHash,
                                             Supplier<MutationResponse> action) {
        try {
            return txTemplate.execute(status -> doIdempotent(requestId, kind, paramHash, action));
        } catch (DuplicateKeyException dup) {
            return resolveAfterRace(requestId, kind, paramHash,
                    new ApiException(HttpStatus.CONFLICT, "RESOURCE_CONFLICT",
                            "并发资源冲突，请稍后使用相同 requestId 与参数重试"));
        } catch (ApiException api) {
            if (api.status() == HttpStatus.CONFLICT) {
                return resolveAfterRace(requestId, kind, paramHash, api);
            }
            throw api;
        }
    }

    /**
     * 并发落败后的裁决：去重表中存在同键记录则按重放/异参冲突处理；
     * 不存在（说明该 requestId 尚未成功、这是一次真实业务冲突）则抛出原错误，
     * 不占用 requestId。
     */
    private MutationResponse resolveAfterRace(String requestId, String kind, String paramHash,
                                              ApiException original) {
        DedupPo winner = txTemplate.execute(status -> reviewRepo.findDedup(requestId));
        if (winner == null) {
            throw original;
        }
        ensureSameRequest(winner, kind, paramHash);
        return deserializeReplay(winner);
    }

    private MutationResponse doIdempotent(String requestId, String kind, String paramHash,
                                          Supplier<MutationResponse> action) {
        DedupPo existing = reviewRepo.findDedup(requestId);
        if (existing != null) {
            ensureSameRequest(existing, kind, paramHash);
            return deserializeReplay(existing);
        }
        // 业务异常会触发事务回滚，去重键不会被占用
        MutationResponse result = action.get();
        reviewRepo.insertDedup(new DedupPo(requestId, kind, paramHash,
                writeJson(result), nowMillis()));
        return result;
    }

    private void ensureSameRequest(DedupPo existing, String kind, String paramHash) {
        if (!existing.requestKind().equals(kind) || !existing.requestHash().equals(paramHash)) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENT_PARAM_MISMATCH",
                    "requestId 已用于参数不同的请求: " + existing.requestId());
        }
    }

    private MutationResponse deserializeReplay(DedupPo po) {
        try {
            MutationResponse original = objectMapper.readValue(po.responseJson(), MutationResponse.class);
            // 业务结果原样重放，仅传输标记告知客户端本次为重放
            return new MutationResponse(original.requestId(), true, original.data());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("无法解析幂等重放结果: " + po.requestId(), ex);
        }
    }

    // ============================ 时空段与优先级规范化 ============================

    /** 规范化时空段：空间单元 + 向下取整到分钟的时间窗。 */
    private record Segment(int cellX, int cellY, long startMin, long endMin) {
        String key() {
            return cellX + ":" + cellY + ":" + startMin + ":" + endMin;
        }
    }

    /**
     * 规范化时空段：四个字段必须同时出现或同时缺省（缺省返回 null 表示不限容量）；
     * 时间窗按分钟向下取整，规范化后起始必须早于结束。
     */
    private static Segment normalizeSegment(Integer cellX, Integer cellY,
                                            Long windowStart, Long windowEnd) {
        if (cellX == null && cellY == null && windowStart == null && windowEnd == null) {
            return null;
        }
        if (cellX == null || cellY == null || windowStart == null || windowEnd == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SEGMENT_INCOMPLETE",
                    "时空段必须同时提供 cellX、cellY、windowStart、windowEnd");
        }
        long startMin = Math.floorDiv(windowStart, MINUTE_MILLIS);
        long endMin = Math.floorDiv(windowEnd, MINUTE_MILLIS);
        if (startMin >= endMin) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TIME_WINDOW",
                    "时间窗规范化后必须满足 startMin < endMin");
        }
        return new Segment(cellX, cellY, startMin, endMin);
    }

    /** 规范化优先级：缺省 NORMAL；只接受 NORMAL/EMERGENCY。 */
    private static String normalizePriority(String priority) {
        if (priority == null || priority.isBlank()) {
            return DiversionPriority.NORMAL.name();
        }
        if (DiversionPriority.NORMAL.name().equals(priority)
                || DiversionPriority.EMERGENCY.name().equals(priority)) {
            return priority;
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PRIORITY",
                "备降优先级只能是 NORMAL 或 EMERGENCY: " + priority);
    }

    /** EMERGENCY 必须附事件编号；NORMAL 不携带事件编号。 */
    private static String normalizeEventNo(String priority, String eventNo) {
        if (DiversionPriority.EMERGENCY.name().equals(priority)) {
            if (eventNo == null || eventNo.isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "EVENT_NO_REQUIRED",
                        "EMERGENCY 备降优先级必须附事件编号");
            }
            return eventNo;
        }
        return null;
    }

    /**
     * 审核请求指纹：含优先级、事件号、航线版本与规范化时空段，
     * 使仅毫秒精度不同的同一时间窗产生相同指纹。
     */
    private String reviewParamHash(ReviewRequest request) {
        Segment segment = normalizeSegment(request.cellX(), request.cellY(),
                request.windowStart(), request.windowEnd());
        String priority = normalizePriority(request.priority());
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("routeId", request.routeId());
        normalized.put("routeVersion", request.routeVersion());
        normalized.put("airspaceVersion", request.airspaceVersion());
        normalized.put("priority", priority);
        normalized.put("eventNo",
                DiversionPriority.EMERGENCY.name().equals(priority) ? request.eventNo() : null);
        normalized.put("bucketKey", segment == null ? null : segment.key());
        return canonicalHash(normalized);
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

    private static CapacityBucketDto toBucketDto(CapacityBucketPo po, int used) {
        return new CapacityBucketDto(po.bucketKey(), po.cellX(), po.cellY(),
                po.windowStartMin(), po.windowEndMin(), po.capacity(), used,
                po.capacity() - used);
    }

    private static ReviewResultDto toDto(ReviewPo po, String conclusion, Boolean current) {
        List<RoutePointDto> snapshot = new ArrayList<>(po.pointsSnapshot().size());
        for (Point p : po.pointsSnapshot()) {
            snapshot.add(new RoutePointDto(p.x(), p.y()));
        }
        return new ReviewResultDto(po.reviewId(), po.routeId(), po.routeVersion(),
                po.airspaceVersion(), conclusion, List.copyOf(po.hitZoneIds()), snapshot,
                po.priority(), po.eventNo(), po.bucketKey(), current);
    }

    private long nowMillis() {
        return clock.instant().toEpochMilli();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("响应序列化失败", ex);
        }
    }

    /**
     * 计算请求参数的规范化哈希（SHA-256）。
     * 对象字段按键名字典序递归排序，列表保持顺序，使相同语义参数产生相同哈希、
     * 字段书写顺序不同不影响比对结果。
     */
    private String canonicalHash(Object request) {
        try {
            JsonNode sorted = canonicalize(objectMapper.valueToTree(request));
            String canonical = objectMapper.writeValueAsString(sorted);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("无法计算请求哈希", ex);
        }
    }

    private static JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = JsonNodeFactory.instance.objectNode();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            for (String name : names) {
                sorted.set(name, canonicalize(node.get(name)));
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = JsonNodeFactory.instance.arrayNode();
            node.forEach(child -> array.add(canonicalize(child)));
            return array;
        }
        return node;
    }
}
