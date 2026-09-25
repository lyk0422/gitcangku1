package com.example.starter.service;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.BucketRefDto;
import com.example.starter.api.dto.BucketUsageDto;
import com.example.starter.api.dto.CapacityConfigRequest;
import com.example.starter.api.dto.CapacityConfigResult;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.RouteActivateRequest;
import com.example.starter.api.dto.RouteActivateResult;
import com.example.starter.api.dto.TransferEvidenceDto;
import com.example.starter.api.dto.TransferItemDto;
import com.example.starter.api.dto.TransferItemEvidence;
import com.example.starter.api.dto.TransferPreviewRequest;
import com.example.starter.api.dto.TransferPreviewResult;
import com.example.starter.api.dto.TransferRequest;
import com.example.starter.api.dto.TransferRoutePreview;
import com.example.starter.api.dto.TransferViolationDto;
import com.example.starter.domain.BucketRef;
import com.example.starter.domain.ReviewConclusion;
import com.example.starter.domain.Trajectory;
import com.example.starter.repo.AirspaceRepository;
import com.example.starter.repo.CapacityConfigPo;
import com.example.starter.repo.CapacityRepository;
import com.example.starter.repo.OccupancyPo;
import com.example.starter.repo.ReviewPo;
import com.example.starter.repo.ReviewRepository;
import com.example.starter.repo.RouteActivationPo;
import com.example.starter.repo.RoutePo;
import com.example.starter.repo.RouteRepository;
import com.example.starter.repo.TransferBucketPo;
import com.example.starter.repo.TransferItemPo;
import com.example.starter.repo.ZonePo;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 空域容量账本与时空桶转配服务。
 *
 * <p>容量模型：空域划分为 1000m×1000m 网格单元，时间划分为 15 分钟 UTC 桶；
 * 管理员按（单元，桶）配置最大航班数。审查结论 CLEAR 且仍为当前结论的航线版本
 * 可激活，激活后按起飞时刻与恒定地速计算网格穿越序列，在每个穿越时空桶占用
 * 1 架次。</p>
 *
 * <p>转配：2~20 个 ACTIVE 航线版本之间交换时空桶占用。预览与激活都按完整后态
 * 一次性计算全部航线的占用变化（先统一释放源桶、再统一占用目标桶），因此
 * A→B→C→A 闭环不会因逐项顺序被误判超限。激活在单个事务内重读航线、禁飞区、
 * 容量配置与全量占用，任一校验失败整体回滚；成功时逐航线增版、一次性替换占用
 * 并冻结不可变证据。所有容量相关写事务先取协调锁行（coord_lock），再按
 * routeId 字典序取航线行锁，与航线修订、审查、容量调整及其他转配互斥，
 * 并发时按提交顺序只能观察到完整状态。</p>
 */
@Service
public class CapacityTransferService {

    static final String KIND_CAPACITY_CONFIG = "CAPACITY_CONFIG";
    static final String KIND_ROUTE_ACTIVATE = "ROUTE_ACTIVATE";
    static final String KIND_CAPACITY_TRANSFER = "CAPACITY_TRANSFER";

    private final AirspaceRepository airspaceRepo;
    private final RouteRepository routeRepo;
    private final ReviewRepository reviewRepo;
    private final CapacityRepository capacityRepo;
    private final Clock clock;
    private final IdempotencySupport idempotency;

    public CapacityTransferService(AirspaceRepository airspaceRepo,
                                   RouteRepository routeRepo,
                                   ReviewRepository reviewRepo,
                                   CapacityRepository capacityRepo,
                                   Clock clock,
                                   IdempotencySupport idempotency) {
        this.airspaceRepo = airspaceRepo;
        this.routeRepo = routeRepo;
        this.reviewRepo = reviewRepo;
        this.capacityRepo = capacityRepo;
        this.clock = clock;
        this.idempotency = idempotency;
    }

    // ============================ 容量配置 ============================

    /** 新建或调整某时空桶的容量上限；同键同参重放原结果，异参 409。 */
    public MutationResponse configureCapacity(CapacityConfigRequest request) {
        return idempotency.execute(request.requestId(), KIND_CAPACITY_CONFIG,
                idempotency.canonicalHash(request), () -> {
                    validateCellId(request.cellId());
                    validateBucketAligned(request.bucketStart());
                    // 锁协调行：与转配激活、航线激活串行，容量调整按提交顺序生效
                    airspaceRepo.getGlobalVersionForUpdate();
                    capacityRepo.upsertConfig(request.cellId(), request.bucketStart(),
                            request.maxFlights(), nowMillis());
                    return new MutationResponse(request.requestId(), false,
                            new CapacityConfigResult(request.cellId(), request.bucketStart(),
                                    request.maxFlights()));
                });
    }

    // ============================ 航线版本激活 ============================

    /**
     * 激活审查通过的航线版本：要求该版本最新审查结论为 CLEAR 且审查依据的
     * 航线版本与空域版本仍当前；成功后按穿越序列生成容量占用。
     */
    public MutationResponse activateRoute(RouteActivateRequest request) {
        return idempotency.execute(request.requestId(), KIND_ROUTE_ACTIVATE,
                idempotency.canonicalHash(request), () -> {
                    airspaceRepo.getGlobalVersionForUpdate();
                    RoutePo route = routeRepo.findRouteForUpdate(request.routeId());
                    if (route == null) {
                        throw new ApiException(HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND",
                                "航线不存在: " + request.routeId());
                    }
                    if (route.version() != request.expectedVersion()) {
                        throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                                "航线版本不匹配：expected=" + request.expectedVersion()
                                        + ", current=" + route.version());
                    }
                    long globalVersion = airspaceRepo.getGlobalVersion();
                    ReviewPo review = reviewRepo.findLatestReview(request.routeId());
                    if (review == null
                            || !ReviewConclusion.CLEAR.name().equals(review.conclusion())
                            || review.routeVersion() != route.version()
                            || review.airspaceVersion() != globalVersion) {
                        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REVIEW_NOT_CURRENT",
                                "该航线版本没有当前有效的 CLEAR 审查结论: " + request.routeId());
                    }
                    if (capacityRepo.findActiveActivation(request.routeId()) != null) {
                        throw new ApiException(HttpStatus.CONFLICT, "ROUTE_ALREADY_ACTIVE",
                                "航线已存在生效中的激活: " + request.routeId());
                    }
                    List<Trajectory.Leg> legs = Trajectory.compute(
                            route.points(), request.departureTime(), request.speedMps());
                    List<OccupancyPo> occupancies = toOccupancies(
                            request.routeId(), route.version(), legs);
                    // 激活同样受容量上限约束（仅已配置桶；计入全部既有占用）
                    Map<String, Integer> usage = usageMap(capacityRepo.findAllOccupancy());
                    for (OccupancyPo occ : occupancies) {
                        String key = bucketKey(occ.cellId(), occ.bucketStart());
                        int after = usage.getOrDefault(key, 0) + 1;
                        CapacityConfigPo config = capacityRepo.findConfig(occ.cellId(), occ.bucketStart());
                        if (config != null && after > config.maxFlights()) {
                            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CAPACITY_EXCEEDED",
                                    "时空桶容量超限: " + occ.cellId() + "@" + occ.bucketStart()
                                            + " 上限=" + config.maxFlights());
                        }
                        usage.put(key, after);
                    }
                    capacityRepo.insertActivation(new RouteActivationPo(request.routeId(),
                            route.version(), "ACTIVE", review.reviewId(), request.departureTime(),
                            request.speedMps(), request.requestId(), nowMillis()));
                    for (OccupancyPo occ : occupancies) {
                        capacityRepo.insertOccupancy(occ);
                    }
                    return new MutationResponse(request.requestId(), false,
                            new RouteActivateResult(request.routeId(), route.version(),
                                    review.reviewId(), toBucketDtos(legs)));
                });
    }

    // ============================ 转配预览 ============================

    /** 预览转配（只读）：按完整后态计算各航线穿越序列与全部桶占用，返回违规明细。 */
    public TransferPreviewResult previewTransfer(TransferPreviewRequest request) {
        return idempotency.inTransaction(() -> {
            Plan plan = buildPlan(request.items(), false);
            List<TransferRoutePreview> routes = new ArrayList<>();
            for (RoutePlan rp : plan.routePlans()) {
                routes.add(new TransferRoutePreview(rp.item().routeId(),
                        rp.route() == null ? null : rp.route().version(),
                        toBucketDtos(rp.legs()), toBucketRefDtos(rp.afterCells())));
            }
            return new TransferPreviewResult(routes, plan.bucketViews(),
                    plan.violations(), plan.violations().isEmpty());
        });
    }

    // ============================ 转配激活 ============================

    /**
     * 激活转配单：单事务内重读全部状态并校验，任一失败整体回滚；
     * 成功一次性替换全部占用、逐航线增版并冻结证据。
     * 转配项换序视为同参（参与幂等哈希前按 routeId 排序）。
     */
    public MutationResponse applyTransfer(TransferRequest request) {
        List<TransferItemDto> sortedItems = sortItems(request.items());
        String hash = idempotency.canonicalHash(
                new TransferRequest(request.transferKey(), sortedItems, request.requestId()));
        return idempotency.execute(request.requestId(), KIND_CAPACITY_TRANSFER, hash, () -> {
            // 锁协调行：与容量调整、航线激活、其他转配串行
            airspaceRepo.getGlobalVersionForUpdate();
            if (capacityRepo.findTransferCreatedAt(request.transferKey()) != null) {
                throw new ApiException(HttpStatus.CONFLICT, "TRANSFER_KEY_EXISTS",
                        "transferKey 已存在: " + request.transferKey());
            }
            Plan plan = buildPlan(sortedItems, true);
            if (!plan.violations().isEmpty()) {
                throw toApiException(plan.violations());
            }
            long now = nowMillis();
            List<TransferItemEvidence> itemEvidence = new ArrayList<>();
            for (RoutePlan rp : plan.routePlans()) {
                String routeId = rp.item().routeId();
                int newVersion = rp.item().expectedVersion() + 1;
                // 条件更新兜底：持锁期间版本不应再变化，0 行说明状态被破坏
                int updated = routeRepo.compareAndIncrementVersion(
                        routeId, rp.item().expectedVersion());
                if (updated == 0) {
                    throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                            "航线版本已变化，请使用最新 expectedVersion 重试: " + routeId);
                }
                capacityRepo.suspendActiveActivation(routeId);
                capacityRepo.deleteOccupancy(routeId, rp.item().expectedVersion());
                capacityRepo.insertActivation(new RouteActivationPo(routeId, newVersion, "ACTIVE",
                        rp.activation().reviewId(), rp.activation().departureTime(),
                        rp.activation().speedMps(), request.requestId(), now));
                for (OccupancyPo occ : rp.newOccupancy(newVersion)) {
                    capacityRepo.insertOccupancy(occ);
                }
                itemEvidence.add(new TransferItemEvidence(routeId, rp.item().expectedVersion(),
                        newVersion, rp.item().sourceBucket(), rp.item().targetBucket(),
                        rp.activation().reviewId(),
                        toBucketDtos(rp.legs()), toBucketRefDtos(rp.afterCells())));
            }
            capacityRepo.insertTransfer(request.transferKey(), request.requestId(), now);
            for (RoutePlan rp : plan.routePlans()) {
                capacityRepo.insertTransferItem(new TransferItemPo(request.transferKey(),
                        rp.item().routeId(), rp.item().expectedVersion(),
                        rp.item().expectedVersion() + 1,
                        rp.item().sourceBucket().cellId(), rp.item().sourceBucket().bucketStart(),
                        rp.item().targetBucket().cellId(), rp.item().targetBucket().bucketStart(),
                        rp.activation().reviewId(),
                        encodeCells(toBucketDtos(rp.legs())), encodeCells(toBucketRefDtos(rp.afterCells()))));
            }
            for (BucketUsageDto bucket : plan.bucketViews()) {
                capacityRepo.insertTransferBucket(new TransferBucketPo(request.transferKey(),
                        bucket.cellId(), bucket.bucketStart(),
                        bucket.usedBefore(), bucket.usedAfter(), bucket.maxFlights()));
            }
            return new MutationResponse(request.requestId(), false,
                    new TransferEvidenceDto(request.transferKey(), now,
                            itemEvidence, plan.bucketViews()));
        });
    }

    /** 查询转配单证据（只读，按桶、航线稳定排序）。 */
    public TransferEvidenceDto getTransfer(String transferKey) {
        return idempotency.inTransaction(() -> {
            Long createdAt = capacityRepo.findTransferCreatedAt(transferKey);
            if (createdAt == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND",
                        "转配单不存在: " + transferKey);
            }
            List<TransferItemEvidence> items = new ArrayList<>();
            for (TransferItemPo po : capacityRepo.findTransferItems(transferKey)) {
                items.add(new TransferItemEvidence(po.routeId(), po.expectedVersion(),
                        po.newVersion(),
                        new BucketRefDto(po.sourceCellId(), po.sourceBucket()),
                        new BucketRefDto(po.targetCellId(), po.targetBucket()),
                        po.reviewId(), decodeCells(po.beforeCells()), decodeCells(po.afterCells())));
            }
            List<BucketUsageDto> buckets = new ArrayList<>();
            for (TransferBucketPo po : capacityRepo.findTransferBuckets(transferKey)) {
                buckets.add(new BucketUsageDto(po.cellId(), po.bucketStart(),
                        po.usedBefore(), po.usedAfter(), po.maxFlights(),
                        po.maxFlights() == null ? null : po.maxFlights() - po.usedAfter()));
            }
            return new TransferEvidenceDto(transferKey, createdAt, items, buckets);
        });
    }

    // ============================ 转配计划（预览与激活共用） ============================

    /** 单条航线的转配计划。 */
    private record RoutePlan(TransferItemDto item, RoutePo route, RouteActivationPo activation,
                             List<Trajectory.Leg> legs, List<OccupancyPo> occupancy,
                             OccupancyPo sourceOcc, List<BucketRef> afterCells) {

        /** 转配后该航线新版本的全量占用（源桶移除、目标桶按源序号加入）。 */
        List<OccupancyPo> newOccupancy(int newVersion) {
            List<OccupancyPo> result = new ArrayList<>();
            for (OccupancyPo occ : occupancy) {
                if (occ == sourceOcc) {
                    continue;
                }
                result.add(new OccupancyPo(occ.routeId(), newVersion,
                        occ.cellId(), occ.bucketStart(), occ.seq()));
            }
            result.add(new OccupancyPo(item.routeId(), newVersion,
                    item.targetBucket().cellId(), item.targetBucket().bucketStart(),
                    sourceOcc.seq()));
            result.sort(Comparator.comparing(OccupancyPo::cellId)
                    .thenComparing(OccupancyPo::bucketStart));
            return result;
        }
    }

    private record Plan(List<RoutePlan> routePlans, List<BucketUsageDto> bucketViews,
                        List<TransferViolationDto> violations) {
    }

    /**
     * 构建转配计划：逐项校验版本、激活状态、源桶占用、路径连续、禁飞与目标桶配置，
     * 再按完整后态统一核算容量（闭环不会被逐项顺序误判）。
     *
     * @param forUpdate true 时对航线行加写锁（激活）；false 为只读（预览）
     */
    private Plan buildPlan(List<TransferItemDto> items, boolean forUpdate) {
        validateItemStructure(items);
        List<TransferItemDto> sorted = sortItems(items);
        List<TransferViolationDto> violations = new ArrayList<>();
        List<RoutePlan> routePlans = new ArrayList<>();
        List<ZonePo> activeZones = airspaceRepo.findActiveZones();

        for (TransferItemDto item : sorted) {
            String routeId = item.routeId();
            RoutePo route = forUpdate
                    ? routeRepo.findRouteForUpdate(routeId)
                    : routeRepo.findRoute(routeId);
            if (route == null) {
                violations.add(new TransferViolationDto("ROUTE_NOT_FOUND",
                        "航线不存在: " + routeId, routeId));
                routePlans.add(new RoutePlan(item, null, null, List.of(), List.of(), null, List.of()));
                continue;
            }
            if (route.version() != item.expectedVersion()) {
                violations.add(new TransferViolationDto("VERSION_CONFLICT",
                        "航线版本不匹配：expected=" + item.expectedVersion()
                                + ", current=" + route.version(), routeId));
                routePlans.add(new RoutePlan(item, route, null, List.of(), List.of(), null, List.of()));
                continue;
            }
            RouteActivationPo activation = capacityRepo.findActiveActivation(routeId);
            if (activation == null || activation.version() != route.version()) {
                violations.add(new TransferViolationDto("ROUTE_NOT_ACTIVE",
                        "航线版本未处于 ACTIVE 激活状态: " + routeId, routeId));
                routePlans.add(new RoutePlan(item, route, null, List.of(), List.of(), null, List.of()));
                continue;
            }
            List<Trajectory.Leg> legs = Trajectory.compute(route.points(),
                    activation.departureTime(), activation.speedMps());
            List<OccupancyPo> occupancy = capacityRepo.findOccupancy(routeId, route.version());
            OccupancyPo sourceOcc = null;
            for (OccupancyPo occ : occupancy) {
                if (occ.cellId().equals(item.sourceBucket().cellId())
                        && occ.bucketStart() == item.sourceBucket().bucketStart()) {
                    sourceOcc = occ;
                    break;
                }
            }
            if (sourceOcc == null) {
                violations.add(new TransferViolationDto("OCCUPANCY_NOT_FOUND",
                        "源桶不是该航线的真实占用（转配集合遗漏）: " + routeId
                                + " " + item.sourceBucket().cellId()
                                + "@" + item.sourceBucket().bucketStart(), routeId));
                routePlans.add(new RoutePlan(item, route, activation, legs, occupancy, null, List.of()));
                continue;
            }
            OccupancyPo targetClash = null;
            for (OccupancyPo occ : occupancy) {
                if (occ.cellId().equals(item.targetBucket().cellId())
                        && occ.bucketStart() == item.targetBucket().bucketStart()) {
                    targetClash = occ;
                    break;
                }
            }
            if (targetClash != null) {
                violations.add(new TransferViolationDto("TARGET_ALREADY_OCCUPIED",
                        "目标桶已是该航线的占用: " + routeId + " "
                                + item.targetBucket().cellId()
                                + "@" + item.targetBucket().bucketStart(), routeId));
                routePlans.add(new RoutePlan(item, route, activation, legs, occupancy, null, List.of()));
                continue;
            }
            // 路径连续：目标单元须为源单元本身或其穿越序列中的相邻单元，
            // 且时间桶相差不超过一个桶，保证转配后占用仍沿航线相邻路径连续
            int seq = sourceOcc.seq();
            boolean contiguous = Math.abs(
                    item.targetBucket().bucketStart() - item.sourceBucket().bucketStart())
                    <= BucketRef.BUCKET_SECONDS;
            if (contiguous) {
                contiguous = item.targetBucket().cellId().equals(item.sourceBucket().cellId())
                        || (seq > 0 && legs.get(seq - 1).cellId().equals(item.targetBucket().cellId()))
                        || (seq + 1 < legs.size()
                        && legs.get(seq + 1).cellId().equals(item.targetBucket().cellId()));
            }
            if (!contiguous) {
                violations.add(new TransferViolationDto("PATH_NOT_CONTIGUOUS",
                        "目标桶与该航线穿越路径不相邻连续: " + routeId + " "
                                + item.targetBucket().cellId()
                                + "@" + item.targetBucket().bucketStart(), routeId));
                routePlans.add(new RoutePlan(item, route, activation, legs, occupancy, null, List.of()));
                continue;
            }
            // 禁飞：目标单元不得与任何有效禁飞区相交（边界接触也算冲突）
            if (cellHitsAnyZone(item.targetBucket().cellId(), activeZones)) {
                violations.add(new TransferViolationDto("NO_FLY_CONFLICT",
                        "目标桶所在单元与禁飞区冲突: " + item.targetBucket().cellId(), routeId));
                routePlans.add(new RoutePlan(item, route, activation, legs, occupancy, null, List.of()));
                continue;
            }
            CapacityConfigPo targetConfig = capacityRepo.findConfig(
                    item.targetBucket().cellId(), item.targetBucket().bucketStart());
            if (targetConfig == null) {
                violations.add(new TransferViolationDto("CAPACITY_NOT_CONFIGURED",
                        "目标桶未配置容量上限: " + item.targetBucket().cellId()
                                + "@" + item.targetBucket().bucketStart(), routeId));
                routePlans.add(new RoutePlan(item, route, activation, legs, occupancy, null, List.of()));
                continue;
            }
            List<BucketRef> afterCells = new ArrayList<>();
            for (int i = 0; i < legs.size(); i++) {
                Trajectory.Leg leg = legs.get(i);
                if (i == seq) {
                    afterCells.add(new BucketRef(item.targetBucket().cellId(),
                            item.targetBucket().bucketStart()));
                } else {
                    afterCells.add(new BucketRef(leg.cellId(), leg.bucketStart()));
                }
            }
            routePlans.add(new RoutePlan(item, route, activation, legs, occupancy,
                    sourceOcc, afterCells));
        }

        // 完整后态容量核算：从全量占用出发统一应用所有释放与占用（闭环净变化为零）
        Map<String, Integer> usageBefore = usageMap(capacityRepo.findAllOccupancy());
        Map<String, Integer> usageAfter = new HashMap<>(usageBefore);
        List<RoutePlan> validPlans = new ArrayList<>();
        for (RoutePlan rp : routePlans) {
            if (rp.sourceOcc() == null) {
                continue;
            }
            validPlans.add(rp);
            String sourceKey = bucketKey(rp.item().sourceBucket().cellId(),
                    rp.item().sourceBucket().bucketStart());
            String targetKey = bucketKey(rp.item().targetBucket().cellId(),
                    rp.item().targetBucket().bucketStart());
            usageAfter.merge(sourceKey, -1, Integer::sum);
            usageAfter.merge(targetKey, 1, Integer::sum);
        }
        Map<String, BucketUsageDto> touched = new HashMap<>();
        for (RoutePlan rp : validPlans) {
            for (BucketRefDto bucket : List.of(rp.item().sourceBucket(), rp.item().targetBucket())) {
                String key = bucketKey(bucket.cellId(), bucket.bucketStart());
                if (touched.containsKey(key)) {
                    continue;
                }
                CapacityConfigPo config = capacityRepo.findConfig(bucket.cellId(), bucket.bucketStart());
                int before = usageBefore.getOrDefault(key, 0);
                int after = usageAfter.getOrDefault(key, 0);
                Integer max = config == null ? null : config.maxFlights();
                touched.put(key, new BucketUsageDto(bucket.cellId(), bucket.bucketStart(),
                        before, after, max, max == null ? null : max - after));
                if (max != null && after > max) {
                    violations.add(new TransferViolationDto("CAPACITY_EXCEEDED",
                            "时空桶容量超限: " + bucket.cellId() + "@" + bucket.bucketStart()
                                    + " 转配后=" + after + " 上限=" + max, null));
                }
            }
        }
        // 按单元、桶起点数值稳定排序
        List<BucketUsageDto> bucketViews = new ArrayList<>(touched.values());
        bucketViews.sort(Comparator.comparing(BucketUsageDto::cellId)
                .thenComparingLong(BucketUsageDto::bucketStart));
        return new Plan(routePlans, bucketViews, violations);
    }

    // ============================ 结构校验与工具 ============================

    /** 结构性校验（与状态无关）：项数、航线唯一、源目标不同、桶对齐、单元格式。 */
    private void validateItemStructure(List<TransferItemDto> items) {
        if (items == null || items.size() < 2 || items.size() > 20) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ITEM_COUNT",
                    "转配项数量须在 2~20 之间");
        }
        Map<String, TransferItemDto> seen = new LinkedHashMap<>();
        for (TransferItemDto item : items) {
            if (seen.put(item.routeId(), item) != null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "DUPLICATE_ROUTE_ITEM",
                        "同一航线在转配单中重复: " + item.routeId());
            }
            validateCellId(item.sourceBucket().cellId());
            validateCellId(item.targetBucket().cellId());
            validateBucketAligned(item.sourceBucket().bucketStart());
            validateBucketAligned(item.targetBucket().bucketStart());
            if (item.sourceBucket().cellId().equals(item.targetBucket().cellId())
                    && item.sourceBucket().bucketStart()
                    .equals(item.targetBucket().bucketStart())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TARGET_BUCKET",
                        "源桶与目标桶不能相同: " + item.routeId());
            }
        }
    }

    private static void validateCellId(String cellId) {
        if (cellId == null || !cellId.matches("-?\\d+:-?\\d+")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CELL_ID",
                    "空域单元标识格式应为 gx:gy: " + cellId);
        }
    }

    private static void validateBucketAligned(long bucketStart) {
        if (bucketStart % BucketRef.BUCKET_SECONDS != 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BUCKET_NOT_ALIGNED",
                    "时间桶起点须为 900 秒（15 分钟）的整数倍: " + bucketStart);
        }
    }

    private static List<TransferItemDto> sortItems(List<TransferItemDto> items) {
        if (items == null) {
            return List.of();
        }
        List<TransferItemDto> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparing(TransferItemDto::routeId));
        return sorted;
    }

    /** 违规映射为激活响应：版本冲突 409，其余 422；消息汇总全部违规。 */
    private static ApiException toApiException(List<TransferViolationDto> violations) {
        TransferViolationDto first = violations.get(0);
        HttpStatus status = "VERSION_CONFLICT".equals(first.code())
                ? HttpStatus.CONFLICT : HttpStatus.UNPROCESSABLE_ENTITY;
        StringBuilder message = new StringBuilder("转配校验失败：");
        for (TransferViolationDto v : violations) {
            message.append('[').append(v.code()).append("] ").append(v.message()).append('；');
        }
        return new ApiException(status, first.code(), message.toString());
    }

    /** 判断空域单元（闭矩形）是否与任一有效禁飞区相交，边界接触也算冲突。 */
    private static boolean cellHitsAnyZone(String cellId, List<ZonePo> activeZones) {
        String[] parts = cellId.split(":");
        long gx = Long.parseLong(parts[0]);
        long gy = Long.parseLong(parts[1]);
        long xMin = gx * BucketRef.CELL_SIZE_METERS;
        long xMax = (gx + 1) * BucketRef.CELL_SIZE_METERS;
        long yMin = gy * BucketRef.CELL_SIZE_METERS;
        long yMax = (gy + 1) * BucketRef.CELL_SIZE_METERS;
        for (ZonePo zone : activeZones) {
            boolean intersect = xMin <= zone.xMax() && zone.xMin() <= xMax
                    && yMin <= zone.yMax() && zone.yMin() <= yMax;
            if (intersect) {
                return true;
            }
        }
        return false;
    }

    private static List<OccupancyPo> toOccupancies(String routeId, int version,
                                                   List<Trajectory.Leg> legs) {
        List<OccupancyPo> result = new ArrayList<>();
        Map<String, Boolean> seen = new HashMap<>();
        for (int i = 0; i < legs.size(); i++) {
            Trajectory.Leg leg = legs.get(i);
            if (seen.put(bucketKey(leg.cellId(), leg.bucketStart()), Boolean.TRUE) != null) {
                continue;
            }
            result.add(new OccupancyPo(routeId, version, leg.cellId(), leg.bucketStart(), i));
        }
        return result;
    }

    private static Map<String, Integer> usageMap(List<OccupancyPo> all) {
        Map<String, Integer> usage = new HashMap<>();
        for (OccupancyPo occ : all) {
            usage.merge(bucketKey(occ.cellId(), occ.bucketStart()), 1, Integer::sum);
        }
        return usage;
    }

    private static String bucketKey(String cellId, long bucketStart) {
        return cellId + "@" + bucketStart;
    }

    private static List<BucketRefDto> toBucketDtos(List<Trajectory.Leg> legs) {
        List<BucketRefDto> result = new ArrayList<>(legs.size());
        for (Trajectory.Leg leg : legs) {
            result.add(new BucketRefDto(leg.cellId(), leg.bucketStart()));
        }
        return result;
    }

    private static List<BucketRefDto> toBucketRefDtos(List<BucketRef> cells) {
        List<BucketRefDto> result = new ArrayList<>(cells.size());
        for (BucketRef cell : cells) {
            result.add(new BucketRefDto(cell.cellId(), cell.bucketStart()));
        }
        return result;
    }

    /** 穿越序列编码："cell@bucket;cell@bucket"。 */
    private static String encodeCells(List<BucketRefDto> cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append(';');
            }
            sb.append(cells.get(i).cellId()).append('@').append(cells.get(i).bucketStart());
        }
        return sb.toString();
    }

    /** 穿越序列解码；空串返回空列表。 */
    private static List<BucketRefDto> decodeCells(String text) {
        List<BucketRefDto> result = new ArrayList<>();
        if (text != null && !text.isEmpty()) {
            for (String entry : text.split(";")) {
                String[] parts = entry.split("@");
                result.add(new BucketRefDto(parts[0], Long.parseLong(parts[1])));
            }
        }
        return result;
    }

    private long nowMillis() {
        return clock.instant().toEpochMilli();
    }
}
