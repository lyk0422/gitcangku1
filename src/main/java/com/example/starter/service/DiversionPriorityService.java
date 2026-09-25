package com.example.starter.service;

import com.example.starter.api.ApiException;
import com.example.starter.api.CapacityConflictException;
import com.example.starter.api.dto.CapacityBucketDto;
import com.example.starter.api.dto.CapacitySetRequest;
import com.example.starter.api.dto.DepartureRequest;
import com.example.starter.api.dto.DepartureResultDto;
import com.example.starter.api.dto.DisplacedRouteDto;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.PriorityReviewRequest;
import com.example.starter.api.dto.PriorityReviewResultDto;
import com.example.starter.api.dto.PreemptionResultDto;
import com.example.starter.api.dto.SpaceTimePointDto;
import com.example.starter.domain.ClearanceStatus;
import com.example.starter.domain.DiversionPriority;
import com.example.starter.domain.Geometry;
import com.example.starter.domain.PreemptionItemStatus;
import com.example.starter.domain.ReviewConclusion;
import com.example.starter.domain.SpaceBucket;
import com.example.starter.domain.SpaceTimePoint;
import com.example.starter.repo.AirspaceRepository;
import com.example.starter.repo.BucketOccupancyPo;
import com.example.starter.repo.CapacityRepository;
import com.example.starter.repo.ClearancePo;
import com.example.starter.repo.DedupPo;
import com.example.starter.repo.PreemptionItemPo;
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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 紧急备降优先级与时空容量抢占业务服务。
 *
 * <p>所有裁决在同一数据库事务内完成：先锁全局协调锁行（与禁飞区变更、航线审查、
 * 起飞登记互斥，按事务提交顺序裁决），再锁航线行；紧急抢占在同一事务内批准紧急
 * 航线、把完整受影响 NORMAL 集合转为 DISPLACED、删除其桶占用并写入不可变抢占快照，
 * 任一状态不符整体回滚。</p>
 *
 * <p>requestKey 指纹含优先级、事件号、航线版本与规范化时空段；同键成功重放首次
 * 快照，业务失败回滚不占用 requestId。</p>
 */
@Service
public class DiversionPriorityService {

    static final String KIND_PRIORITY_REVIEW = "PRIORITY_REVIEW";
    static final String KIND_DEPARTURE = "DEPARTURE";
    static final String KIND_CAPACITY_SET = "CAPACITY_SET";

    /** 未配置单元的缺省容量。 */
    static final int DEFAULT_CAPACITY = 1;

    private final AirspaceRepository airspaceRepo;
    private final RouteRepository routeRepo;
    private final ReviewRepository reviewRepo;
    private final CapacityRepository capacityRepo;
    private final ObjectMapper objectMapper;
    private final java.time.Clock clock;
    private final TransactionTemplate txTemplate;

    public DiversionPriorityService(AirspaceRepository airspaceRepo,
                                    RouteRepository routeRepo,
                                    ReviewRepository reviewRepo,
                                    CapacityRepository capacityRepo,
                                    ObjectMapper objectMapper,
                                    java.time.Clock clock,
                                    PlatformTransactionManager transactionManager) {
        this.airspaceRepo = airspaceRepo;
        this.routeRepo = routeRepo;
        this.reviewRepo = reviewRepo;
        this.capacityRepo = capacityRepo;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    // ============================ 优先级审查（含紧急抢占） ============================

    /**
     * 提交带备降优先级的审查：
     * NORMAL 在桶容量不足时返回 422；EMERGENCY 可抢占未起飞 NORMAL，
     * 移除完整受影响集合后仍不足时返回 422 并列出不可抢占航线。
     */
    public MutationResponse submitPriorityReview(PriorityReviewRequest request) {
        DiversionPriority priority = parsePriority(request.priority());
        validateEventNo(priority, request.eventNo());
        List<SpaceBucket> buckets = normalizeBuckets(request.segments());
        return withIdempotency(request.requestId(), KIND_PRIORITY_REVIEW, canonicalHash(request),
                () -> doSubmitReview(request, priority, buckets));
    }

    private PriorityReviewResultDto doSubmitReview(PriorityReviewRequest request,
                                                   DiversionPriority priority,
                                                   List<SpaceBucket> buckets) {
        // 1. 全局协调锁：与区域更新、航线审查、起飞登记、其他紧急抢占按提交顺序串行裁决
        long globalVersion = airspaceRepo.getGlobalVersionForUpdate();
        // 2. 航线行锁：与航线替换互斥，保证版本号与点列一致
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
        // 3. 同航线已有生效批件：同键并发落败走重放裁决；已起飞航线不得再提交
        ClearancePo active = capacityRepo.findActiveClearanceForUpdate(request.routeId());
        if (active != null && active.requestId().equals(request.requestId())) {
            throw new ApiException(HttpStatus.CONFLICT, "RESOURCE_CONFLICT",
                    "并发资源冲突，请稍后使用相同 requestId 与参数重试");
        }
        if (active != null && ClearanceStatus.DEPARTED.name().equals(active.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "ROUTE_ALREADY_DEPARTED",
                    "航线已起飞，不能重新提交审查: " + request.routeId());
        }
        // 4. 区域审查：与既有审查一致，命中任一有效禁飞区不得批准
        List<String> hitZones = findHitZones(route);
        if (!hitZones.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ROUTE_BLOCKED_BY_NO_FLY_ZONE",
                    "航线命中禁飞区，无法批准: " + String.join(",", hitZones));
        }

        long now = nowMillis();
        // 5. 容量裁决：按单元容量统计各桶占用，紧急先算完整受影响 NORMAL 集合。
        //    同航线旧批件将在第 7 步作旧，其占用不计入本次裁决。
        String selfClearanceId = active == null ? null : active.clearanceId();
        Map<String, ClearancePo> preemptById = new TreeMap<>();
        List<CapacityConflictException.BlockingRoute> blocking = new ArrayList<>();
        evaluateCapacity(buckets, priority, selfClearanceId, preemptById, blocking);
        if (!blocking.isEmpty()) {
            // 容量不可满足：回滚，不占键
            throw new CapacityConflictException(
                    priority == DiversionPriority.EMERGENCY
                            ? "移除全部可抢占 NORMAL 后容量仍不足"
                            : "时空桶容量不足",
                    blocking);
        }

        // 6. 写不可变审核结论（CLEAR 快照，复用 review 表）
        String reviewId = "rv_" + UUID.randomUUID();
        reviewRepo.insertReview(new ReviewPo(reviewId, request.routeId(), route.version(),
                globalVersion, ReviewConclusion.CLEAR.name(), List.of(),
                List.copyOf(route.points()), request.requestId(), now));

        // 7. 同航线重新提交：旧 APPROVED 批件（含占用）作旧
        if (active != null) {
            int superseded = capacityRepo.supersedeApprovedByRoute(request.routeId());
            if (superseded != 1) {
                // 持锁期间状态不符：整次回滚
                throw new ApiException(HttpStatus.CONFLICT, "CLEARANCE_STATE_CONFLICT",
                        "原批件状态已变化，请重试");
            }
            capacityRepo.deleteBuckets(active.clearanceId());
        }

        // 8. 批准新批件并占用全部时空桶
        String clearanceId = "cl_" + UUID.randomUUID();
        String bucketsCanonical = encodeBuckets(buckets);
        ClearancePo clearance = new ClearancePo(clearanceId, request.routeId(), route.version(),
                globalVersion, priority.name(),
                priority == DiversionPriority.EMERGENCY ? request.eventNo() : null,
                ClearanceStatus.APPROVED.name(), reviewId, bucketsCanonical,
                request.requestId(), now, null, null);
        capacityRepo.insertClearance(clearance);
        for (SpaceBucket bucket : buckets) {
            capacityRepo.insertBucket(new BucketOccupancyPo(clearanceId, request.routeId(),
                    bucket.cellX(), bucket.cellY(), bucket.timeBucket(), priority.name()));
        }

        String preemptionId = null;
        List<DisplacedRouteDto> displacedDtos = List.of();
        if (priority == DiversionPriority.EMERGENCY && !preemptById.isEmpty()) {
            // 9. 紧急抢占：状态条件更新 + 快照写入，任一不符整事务回滚。
            //    被置换航线按标识字典序生成快照，保证受影响集合文本确定且与贪心选取顺序无关。
            List<ClearancePo> victims = preemptById.values().stream()
                    .sorted(java.util.Comparator.comparing(ClearancePo::routeId))
                    .toList();
            List<PreemptionItemPo> items = new ArrayList<>();
            List<String> displacedRouteIds = new ArrayList<>();
            for (ClearancePo victim : victims) {
                int changed = capacityRepo.displaceIfApproved(victim.clearanceId(), now);
                if (changed != 1) {
                    // 持锁期间被起飞或被置换：状态不符，整次回滚
                    throw new ApiException(HttpStatus.CONFLICT, "CLEARANCE_STATE_CONFLICT",
                            "受影响批件状态已变化: " + victim.clearanceId());
                }
                capacityRepo.deleteBuckets(victim.clearanceId());
                items.add(new PreemptionItemPo(null, null, victim.routeId(),
                        victim.clearanceId(), victim.routeVersion(), victim.bucketsCanonical(),
                        PreemptionItemStatus.PENDING.name(), victim.routeId(), now, null, null));
                displacedRouteIds.add(victim.routeId());
            }
            preemptionId = "pm_" + UUID.randomUUID();
            capacityRepo.insertPreemption(new PreemptionPo(preemptionId, clearanceId,
                    request.routeId(), request.eventNo(), globalVersion,
                    List.copyOf(displacedRouteIds), request.requestId(), now));
            for (PreemptionItemPo item : items) {
                capacityRepo.insertPreemptionItem(new PreemptionItemPo(null, preemptionId,
                        item.displacedRouteId(), item.displacedClearanceId(),
                        item.routeVersionSnapshot(), item.bucketsCanonical(), item.status(),
                        item.pendingKey(), now, null, null));
            }
            displacedDtos = items.stream().map(DiversionPriorityService::toDisplacedDto).toList();
        }
        if (priority == DiversionPriority.NORMAL) {
            // 被抢占航线重新提交获批准：推进其未处理抢占记录（至多一条，唯一索引兜底）
            capacityRepo.resolvePendingItem(request.routeId(), clearanceId, now);
        }

        return new PriorityReviewResultDto(clearanceId, reviewId, request.routeId(),
                route.version(), globalVersion, priority.name(),
                priority == DiversionPriority.EMERGENCY ? request.eventNo() : null,
                buckets.stream().map(SpaceBucket::canonical).toList(),
                displacedDtos, preemptionId);
    }

    /**
     * 容量裁决：按规范化桶序逐桶统计占用与单元容量。
     *
     * <p>NORMAL：任一桶加入本航线后超容即拒绝，列出该桶占用方。</p>
     *
     * <p>EMERGENCY：抢占以航线为粒度（置换一条航线会释放它在全部桶上的占用）。
     * 按桶序贪心：桶仍超容时，从该桶未起飞 NORMAL 占用中按批件标识升序补选
     * 最小数量加入完整受影响集合；可抢占数量不足时，把剩余缺口对应的不可抢占
     * 占用（已起飞 NORMAL 或另一 EMERGENCY）列入 blocking，最终返回 422。</p>
     */
    private void evaluateCapacity(List<SpaceBucket> buckets,
                                  DiversionPriority priority,
                                  String selfClearanceId,
                                  Map<String, ClearancePo> preemptById,
                                  List<CapacityConflictException.BlockingRoute> blocking) {
        Map<String, CapacityConflictException.BlockingRoute> blockingById = new LinkedHashMap<>();
        for (SpaceBucket bucket : buckets) {
            Integer configured = capacityRepo.findCapacity(bucket.cellX(), bucket.cellY());
            int capacity = configured == null ? DEFAULT_CAPACITY : configured;
            List<BucketOccupancyPo> occupants = capacityRepo.findOccupants(
                    bucket.cellX(), bucket.cellY(), bucket.timeBucket());
            Map<String, ClearancePo> perOccupantClearances = loadOccupantClearances(occupants);

            if (priority == DiversionPriority.NORMAL) {
                if (occupants.size() + 1 > capacity) {
                    for (BucketOccupancyPo occ : occupants) {
                        blockingById.putIfAbsent(occ.clearanceId(),
                                toBlocking(occ, perOccupantClearances, false));
                    }
                }
                continue;
            }

            // EMERGENCY：剔除本航线旧批件（将在第 7 步作旧）与已选受影响航线后重算
            List<BucketOccupancyPo> effective = occupants.stream()
                    .filter(o -> !o.clearanceId().equals(selfClearanceId))
                    .filter(o -> !preemptById.containsKey(o.clearanceId()))
                    .toList();
            int deficit = effective.size() + 1 - capacity;
            if (deficit <= 0) {
                continue;
            }
            List<BucketOccupancyPo> removable = effective.stream()
                    .filter(o -> {
                        ClearancePo c = perOccupantClearances.get(o.clearanceId());
                        return c != null
                                && DiversionPriority.NORMAL.name().equals(c.priority())
                                && ClearanceStatus.APPROVED.name().equals(c.status());
                    })
                    .sorted(java.util.Comparator.comparing(BucketOccupancyPo::clearanceId))
                    .toList();
            int take = Math.min(deficit, removable.size());
            for (int i = 0; i < take; i++) {
                BucketOccupancyPo chosen = removable.get(i);
                preemptById.putIfAbsent(chosen.clearanceId(),
                        perOccupantClearances.get(chosen.clearanceId()));
            }
            if (deficit > removable.size()) {
                // 可抢占集合用尽后仍有缺口：不可抢占占用导致 422
                List<BucketOccupancyPo> nonRemovable = effective.stream()
                        .filter(o -> !removable.contains(o))
                        .sorted(java.util.Comparator.comparing(BucketOccupancyPo::clearanceId))
                        .toList();
                int blockersNeeded = deficit - removable.size();
                for (int i = 0; i < blockersNeeded && i < nonRemovable.size(); i++) {
                    BucketOccupancyPo occ = nonRemovable.get(i);
                    blockingById.putIfAbsent(occ.clearanceId(),
                            toBlocking(occ, perOccupantClearances, true));
                }
            }
        }
        if (!blockingById.isEmpty()) {
            // 不足时本次抢占不生效：受影响集合仅用于错误说明，不做任何更新
            preemptById.clear();
            blocking.addAll(blockingById.values());
        }
    }

    private Map<String, ClearancePo> loadOccupantClearances(List<BucketOccupancyPo> occupants) {
        Map<String, ClearancePo> map = new LinkedHashMap<>();
        Set<String> ids = new TreeSet<>();
        for (BucketOccupancyPo occ : occupants) {
            ids.add(occ.clearanceId());
        }
        for (String id : ids) {
            ClearancePo c = capacityRepo.findClearance(id);
            if (c != null) {
                map.put(id, c);
            }
        }
        return map;
    }

    private static CapacityConflictException.BlockingRoute toBlocking(
            BucketOccupancyPo occ, Map<String, ClearancePo> clearances, boolean emergencySubmission) {
        ClearancePo c = clearances.get(occ.clearanceId());
        String status = c == null ? "UNKNOWN" : c.status();
        String reason;
        if (ClearanceStatus.DEPARTED.name().equals(status)) {
            reason = "DEPARTED";
        } else if (DiversionPriority.EMERGENCY.name().equals(occ.priority())) {
            reason = "EMERGENCY";
        } else {
            reason = emergencySubmission ? "NO_CAPACITY" : "NO_CAPACITY";
        }
        return new CapacityConflictException.BlockingRoute(occ.routeId(), occ.clearanceId(),
                occ.priority(), status, reason);
    }

    // ============================ 起飞登记 ============================

    /**
     * 起飞登记：仅 APPROVED 且航线/空域版本仍为批准版本的批件可登记；
     * 登记后占用不可被任何航线抢占。
     */
    public MutationResponse registerDeparture(DepartureRequest request) {
        return withIdempotency(request.requestId(), KIND_DEPARTURE, canonicalHash(request), () -> {
            // 与审查/抢占/区域更新串行裁决
            airspaceRepo.getGlobalVersionForUpdate();
            ClearancePo found = capacityRepo.findClearance(request.clearanceId());
            if (found == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "CLEARANCE_NOT_FOUND",
                        "批件不存在: " + request.clearanceId());
            }
            // 对批件行做真实更新加行级写锁，随后重读，保证状态判定基于持锁后的最新已提交值
            lockClearanceRow(found.clearanceId());
            ClearancePo clearance = capacityRepo.findClearance(request.clearanceId());
            if (ClearanceStatus.DEPARTED.name().equals(clearance.status())) {
                if (clearance.requestId().equals(request.requestId())) {
                    throw new ApiException(HttpStatus.CONFLICT, "RESOURCE_CONFLICT",
                            "并发资源冲突，请稍后使用相同 requestId 与参数重试");
                }
                throw new ApiException(HttpStatus.CONFLICT, "CLEARANCE_ALREADY_DEPARTED",
                        "批件已起飞登记: " + request.clearanceId());
            }
            if (!ClearanceStatus.APPROVED.name().equals(clearance.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "CLEARANCE_NOT_ACTIVE",
                        "批件状态为 " + clearance.status() + "，不能登记起飞");
            }
            long globalVersion = airspaceRepo.getGlobalVersion();
            RoutePo route = routeRepo.findRouteForUpdate(clearance.routeId());
            if (route == null || route.version() != clearance.routeVersion()
                    || globalVersion != clearance.airspaceVersion()) {
                // 版本失效：按既有规则拒绝起飞，需重新提交审查（旧批件随后作旧）
                throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                        "批件依据的航线/空域版本已失效，请重新提交审查");
            }
            long now = nowMillis();
            int changed = capacityRepo.markDeparted(clearance.clearanceId(), now);
            if (changed != 1) {
                throw new ApiException(HttpStatus.CONFLICT, "CLEARANCE_STATE_CONFLICT",
                        "批件状态已变化，请重试");
            }
            return new DepartureResultDto(clearance.clearanceId(), clearance.routeId(),
                    ClearanceStatus.DEPARTED.name(), now);
        });
    }

    /** 对批件行做真实更新取得行级写锁（状态不随 touch 改变）。 */
    private void lockClearanceRow(String clearanceId) {
        capacityRepo.touchClearance(clearanceId);
    }

    // ============================ 容量配置 ============================

    /** 设置（覆盖）格网单元容量（缺省 1）；幂等重放首次结果。 */
    public MutationResponse setCapacity(CapacitySetRequest request) {
        return withIdempotency(request.requestId(), KIND_CAPACITY_SET, canonicalHash(request),
                () -> {
                    capacityRepo.upsertCapacity(request.cellX(), request.cellY(),
                            request.capacity(), nowMillis());
                    return new CapacityResult(request.cellX(), request.cellY(), request.capacity());
                });
    }

    /** 容量配置结果。 */
    public record CapacityResult(int cellX, int cellY, int capacity) {
    }

    // ============================ 查询 ============================

    /** 容量桶视图：单元容量、当前占用数与占用明细。 */
    public CapacityBucketViewResult getCapacityBucket(int cellX, int cellY, long timeBucket) {
        List<CapacityRepository.CapacityBucketView> views =
                capacityRepo.findBucketViews(cellX, cellY, timeBucket);
        Integer configured = capacityRepo.findCapacity(cellX, cellY);
        int capacity = configured == null ? DEFAULT_CAPACITY : configured;
        List<CapacityBucketDto> items = views.stream()
                .map(v -> new CapacityBucketDto(v.clearanceId(), v.routeId(), v.cellX(), v.cellY(),
                        v.timeBucket(), v.priority(), v.clearanceStatus()))
                .toList();
        return new CapacityBucketViewResult(cellX, cellY, timeBucket, capacity, items.size(), items);
    }

    /** 容量桶查询结果。 */
    public record CapacityBucketViewResult(int cellX, int cellY, long timeBucket, int capacity,
                                           int occupied, List<CapacityBucketDto> occupants) {
    }

    /** 查询不可变抢占快照（不存在 404）。 */
    public PreemptionResultDto getPreemption(String preemptionId) {
        PreemptionPo po = capacityRepo.findPreemption(preemptionId);
        if (po == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PREEMPTION_NOT_FOUND",
                    "抢占记录不存在: " + preemptionId);
        }
        List<PreemptionItemPo> items = capacityRepo.findPreemptionItems(preemptionId);
        return new PreemptionResultDto(po.preemptionId(), po.emergencyClearanceId(),
                po.emergencyRouteId(), po.eventNo(), po.airspaceVersion(),
                List.copyOf(po.displacedRouteIds()), po.createdAt(),
                items.stream().map(DiversionPriorityService::toDisplacedDto).toList());
    }

    /** 查询某航线被置换的全部记录（含处理状态）。 */
    public List<DisplacedRouteDto> getDisplacedRoutes(String routeId) {
        return capacityRepo.findItemsForRoute(routeId).stream()
                .map(DiversionPriorityService::toDisplacedDto).toList();
    }

    /** 查询某航线全部 DISPLACED 批件（按置换时间升序）。 */
    public List<ClearancePo> getDisplacedClearances(String routeId) {
        return capacityRepo.findDisplacedClearances(routeId);
    }

    /** 查询批件当前状态（不存在 404）。 */
    public ClearancePo getClearance(String clearanceId) {
        ClearancePo po = capacityRepo.findClearance(clearanceId);
        if (po == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CLEARANCE_NOT_FOUND",
                    "批件不存在: " + clearanceId);
        }
        return po;
    }

    private static DisplacedRouteDto toDisplacedDto(PreemptionItemPo item) {
        return new DisplacedRouteDto(item.displacedRouteId(), item.displacedClearanceId(),
                item.routeVersionSnapshot(), item.status(), item.resolvedClearanceId());
    }

    // ============================ 校验与规范化 ============================

    private static DiversionPriority parsePriority(String raw) {
        try {
            return DiversionPriority.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PRIORITY",
                    "priority 必须是 NORMAL 或 EMERGENCY: " + raw);
        }
    }

    private static void validateEventNo(DiversionPriority priority, String eventNo) {
        if (priority == DiversionPriority.EMERGENCY) {
            if (eventNo == null || eventNo.isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "EVENT_NO_REQUIRED",
                        "EMERGENCY 必须附事件编号 eventNo");
            }
        } else if (eventNo != null && !eventNo.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EVENT_NO_FOR_NORMAL_FORBIDDEN",
                    "NORMAL 航线不得携带事件编号");
        }
    }

    /**
     * 规范化时空段：逐采样点归入格网单元与 10 分钟时间桶，
     * 校验时间严格升序后按字典序去重。
     */
    private static List<SpaceBucket> normalizeBuckets(List<SpaceTimePointDto> segments) {
        long previous = Long.MIN_VALUE;
        TreeSet<SpaceBucket> distinct = new TreeSet<>();
        for (SpaceTimePointDto dto : segments) {
            if (dto.atMillis() <= previous) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "SEGMENTS_NOT_ORDERED",
                        "时空段采样点必须按时间严格升序");
            }
            previous = dto.atMillis();
            SpaceTimePoint point = new SpaceTimePoint(dto.atMillis(), dto.x(), dto.y());
            distinct.add(new SpaceBucket(point.cellX(), point.cellY(), point.timeBucket()));
        }
        return List.copyOf(distinct);
    }

    private static String encodeBuckets(List<SpaceBucket> buckets) {
        return String.join(";", buckets.stream().map(SpaceBucket::canonical).toList());
    }

    private List<String> findHitZones(RoutePo route) {
        Set<String> hits = new TreeSet<>();
        for (ZonePo zone : airspaceRepo.findActiveZones()) {
            if (Geometry.polylineHitsRectangle(route.points(),
                    zone.xMin(), zone.yMin(), zone.xMax(), zone.yMax())) {
                hits.add(zone.zoneId());
            }
        }
        return List.copyOf(hits);
    }

    // ============================ 幂等与事务 ============================

    /**
     * 与 {@link AirspaceReviewService} 相同的幂等语义：同键同参重放首次成功结果；
     * 同键异参/异种操作 409；业务异常回滚不占键，去重记录与业务变更同事务原子提交。
     */
    private MutationResponse withIdempotency(String requestId, String kind, String paramHash,
                                             Supplier<Object> action) {
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
                                          Supplier<Object> action) {
        DedupPo existing = reviewRepo.findDedup(requestId);
        if (existing != null) {
            ensureSameRequest(existing, kind, paramHash);
            return deserializeReplay(existing);
        }
        Object result = action.get();
        MutationResponse response = new MutationResponse(requestId, false, result);
        reviewRepo.insertDedup(new DedupPo(requestId, kind, paramHash,
                writeJson(response), nowMillis()));
        return response;
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
            return new MutationResponse(original.requestId(), true, original.data());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("无法解析幂等重放结果: " + po.requestId(), ex);
        }
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
     * 计算请求参数的规范化哈希（SHA-256），指纹含优先级、事件号、
     * 航线版本、空域版本与时空段采样点；字段按键名字典序递归排序，列表保持顺序。
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
