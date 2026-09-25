package com.example.starter.service;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.BucketDto;
import com.example.starter.api.dto.BucketMarginDto;
import com.example.starter.api.dto.CapacityConfigRequest;
import com.example.starter.api.dto.CapacityConfigResult;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.RouteActivateRequest;
import com.example.starter.api.dto.RouteActivateResult;
import com.example.starter.api.dto.RouteDeactivateRequest;
import com.example.starter.api.dto.RouteDeactivateResult;
import com.example.starter.api.dto.RouteVersionDto;
import com.example.starter.api.dto.TransferActivateRequest;
import com.example.starter.api.dto.TransferEvidenceDto;
import com.example.starter.api.dto.TransferItemDto;
import com.example.starter.api.dto.TransferPreviewRequest;
import com.example.starter.api.dto.TransferPreviewResponse;
import com.example.starter.api.dto.TransferResultDto;
import com.example.starter.api.dto.TransferRouteEvidenceDto;
import com.example.starter.api.dto.TransferRouteResultDto;
import com.example.starter.api.dto.ViolationDto;
import com.example.starter.domain.Bucket;
import com.example.starter.domain.Cells;
import com.example.starter.domain.ReviewConclusion;
import com.example.starter.repo.AirspaceRepository;
import com.example.starter.repo.CapacityConfigPo;
import com.example.starter.repo.CapacityRepository;
import com.example.starter.repo.OccupancyPo;
import com.example.starter.repo.ReviewPo;
import com.example.starter.repo.ReviewRepository;
import com.example.starter.repo.RoutePo;
import com.example.starter.repo.RouteRepository;
import com.example.starter.repo.TransferBucketPo;
import com.example.starter.repo.TransferItemPo;
import com.example.starter.repo.TransferPo;
import com.example.starter.repo.TransferRepository;
import com.example.starter.repo.TransferRoutePo;
import com.example.starter.repo.ZonePo;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 航路时空桶容量账本与闭环原子转配服务。
 *
 * <p>容量模型：空域为 1000 米固定网格（见 {@link Cells}），时间为 15 分钟 UTC 桶。
 * 审查通过（CLEAR 且当前有效）的航线版本可激活，按折线穿越单元序列占用时空桶，
 * 序列第 i 项占用时间桶 departure + i*15min，时间结构随位置固定。</p>
 *
 * <p>转配：每项把某航线版本在源桶的占用转移到目标桶。校验按完整后态计算——
 * 先汇总全部项的净变化再判定容量，允许多条航线形成 A→B→C→A 闭环，
 * 不因逐项释放/占用顺序误判。目标桶时间须等于源桶时间（位置时间固定），
 * 目标单元须与转配后相邻位置单元八邻接，且转配后完整路径不穿越有效禁飞区；
 * 容量判定计入全部航线（含未参与航线）的占用。</p>
 *
 * <p>激活在单个事务内先取协调锁（与区域变更、审核、容量调整、其他转配串行），
 * 再按 routeId 字典序锁参与航线行，重新读取航线、禁飞区、容量配置与全量占用；
 * 任一违规整体回滚，不产生部分转配，失败不占用 requestId。</p>
 */
@Service
public class CapacityTransferService {

    static final String KIND_CAPACITY_CONFIG = "CAPACITY_CONFIG";
    static final String KIND_ROUTE_ACTIVATE = "ROUTE_ACTIVATE";
    static final String KIND_ROUTE_DEACTIVATE = "ROUTE_DEACTIVATE";
    static final String KIND_TRANSFER_ACTIVATE = "TRANSFER_ACTIVATE";

    static final int MIN_TRANSFER_ROUTES = 2;
    static final int MAX_TRANSFER_ROUTES = 20;

    private final AirspaceRepository airspaceRepo;
    private final RouteRepository routeRepo;
    private final ReviewRepository reviewRepo;
    private final CapacityRepository capacityRepo;
    private final TransferRepository transferRepo;
    private final IdempotencyExecutor idempotency;
    private final Clock clock;
    private final TransactionTemplate txTemplate;

    public CapacityTransferService(AirspaceRepository airspaceRepo,
                                   RouteRepository routeRepo,
                                   ReviewRepository reviewRepo,
                                   CapacityRepository capacityRepo,
                                   TransferRepository transferRepo,
                                   IdempotencyExecutor idempotency,
                                   Clock clock,
                                   PlatformTransactionManager transactionManager) {
        this.airspaceRepo = airspaceRepo;
        this.routeRepo = routeRepo;
        this.reviewRepo = reviewRepo;
        this.capacityRepo = capacityRepo;
        this.transferRepo = transferRepo;
        this.idempotency = idempotency;
        this.clock = clock;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    // ============================ 容量配置 ============================

    /** 配置或调整时空桶容量上限；同键同参重放原结果，异参 409。 */
    public MutationResponse configureCapacity(CapacityConfigRequest request) {
        return idempotency.execute(request.requestId(), KIND_CAPACITY_CONFIG,
                idempotency.canonicalHash(request), () -> {
                    validateBucket(request.cellId(), request.bucketStart());
                    // 持协调锁：与转配激活、审核、区域变更串行，容量调整按提交顺序生效
                    airspaceRepo.getGlobalVersionForUpdate();
                    capacityRepo.upsertConfig(request.cellId(), request.bucketStart(),
                            request.maxFlights(), nowMillis());
                    return new MutationResponse(request.requestId(), false,
                            new CapacityConfigResult(request.cellId(), request.bucketStart(),
                                    request.maxFlights()));
                });
    }

    // ============================ 航线激活 / 停用 ============================

    /**
     * 激活航线版本：要求该版本审查通过（最新审核 CLEAR 且航线/空域版本均为当前），
     * 按穿越单元序列占用时空桶；任一配置桶超限则 422。
     */
    public MutationResponse activateRoute(RouteActivateRequest request) {
        return idempotency.execute(request.requestId(), KIND_ROUTE_ACTIVATE,
                idempotency.canonicalHash(request), () -> {
                    if (!Cells.isAlignedBucketStart(request.departureTime())) {
                        throw new ApiException(HttpStatus.BAD_REQUEST, "UNALIGNED_DEPARTURE",
                                "起飞时刻必须对齐 15 分钟 UTC 时间桶: " + request.departureTime());
                    }
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
                    if (!capacityRepo.findOccupancy(request.routeId()).isEmpty()) {
                        throw new ApiException(HttpStatus.CONFLICT, "ROUTE_ALREADY_ACTIVE",
                                "航线已激活占用容量: " + request.routeId());
                    }
                    ReviewPo review = reviewRepo.findLatestReview(request.routeId());
                    long globalVersion = airspaceRepo.getGlobalVersion();
                    if (review == null
                            || !ReviewConclusion.CLEAR.name().equals(review.conclusion())
                            || review.routeVersion() != route.version()
                            || review.airspaceVersion() != globalVersion) {
                        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                                "REVIEW_NOT_CURRENT",
                                "航线版本缺少当前有效的审查通过结论: " + request.routeId());
                    }
                    List<String> cells = Cells.traversalCells(route.points());
                    // 容量校验：逐桶计入全量占用后再加一，超限拒绝
                    for (int i = 0; i < cells.size(); i++) {
                        long bucketStart = request.departureTime() + i * Cells.BUCKET_MILLIS;
                        CapacityConfigPo config = capacityRepo.findConfig(cells.get(i), bucketStart);
                        if (config != null
                                && capacityRepo.countByBucket(cells.get(i), bucketStart) + 1
                                        > config.maxFlights()) {
                            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                                    "CAPACITY_EXCEEDED",
                                    "时空桶容量超限: " + cells.get(i) + "@" + bucketStart);
                        }
                    }
                    for (int i = 0; i < cells.size(); i++) {
                        capacityRepo.insertOccupancy(new OccupancyPo(request.routeId(),
                                route.version(), i, cells.get(i),
                                request.departureTime() + i * Cells.BUCKET_MILLIS,
                                review.reviewId()));
                    }
                    return new MutationResponse(request.requestId(), false,
                            new RouteActivateResult(request.routeId(), route.version(),
                                    cells.size(), review.reviewId()));
                });
    }

    /** 停用航线：移除全部时空桶占用；未激活返回 409。 */
    public MutationResponse deactivateRoute(RouteDeactivateRequest request) {
        return idempotency.execute(request.requestId(), KIND_ROUTE_DEACTIVATE,
                idempotency.canonicalHash(request), () -> {
                    airspaceRepo.getGlobalVersionForUpdate();
                    RoutePo route = routeRepo.findRouteForUpdate(request.routeId());
                    if (route == null) {
                        throw new ApiException(HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND",
                                "航线不存在: " + request.routeId());
                    }
                    int removed = capacityRepo.deleteOccupancyByRoute(request.routeId());
                    if (removed == 0) {
                        throw new ApiException(HttpStatus.CONFLICT, "ROUTE_NOT_ACTIVE",
                                "航线未激活占用容量: " + request.routeId());
                    }
                    return new MutationResponse(request.requestId(), false,
                            new RouteDeactivateResult(request.routeId(), removed));
                });
    }

    // ============================ 转配预览（只读） ============================

    /** 预览转配：按完整后态计算，只读返回版本、容量余量与违规明细，不落库。 */
    public TransferPreviewResponse previewTransfer(TransferPreviewRequest request) {
        return txTemplate.execute(status -> {
            Evaluation eval = evaluate(request.items(), false);
            return new TransferPreviewResponse(eval.violations.isEmpty(),
                    eval.routeVersions(), eval.margins(), eval.violations());
        });
    }

    // ============================ 转配激活 ============================

    /**
     * 激活转配：单事务内重新读取并校验全部状态，成功一次性替换全部占用、
     * 逐航线增版并冻结证据；任一违规整体回滚。transferKey 唯一；
     * requestId 同参（转配项换序视为同参）重放首次快照，异参 409，失败不占键。
     */
    public MutationResponse activateTransfer(TransferActivateRequest request) {
        return idempotency.execute(request.requestId(), KIND_TRANSFER_ACTIVATE,
                idempotency.canonicalHash(request, "items"), () -> {
                    // 先取协调锁：与区域变更、审核、容量调整、其他转配严格串行
                    airspaceRepo.getGlobalVersionForUpdate();
                    if (transferRepo.findTransfer(request.transferKey()) != null) {
                        throw new ApiException(HttpStatus.CONFLICT, "TRANSFER_KEY_EXISTS",
                                "转配单标识已存在: " + request.transferKey());
                    }
                    Evaluation eval = evaluate(request.items(), true);
                    if (!eval.violations().isEmpty()) {
                        throw toException(eval.violations());
                    }
                    // 逐航线增版并一次性替换全部占用
                    List<TransferRouteResultDto> routeResults = new ArrayList<>();
                    for (String routeId : eval.sortedRouteIds()) {
                        RoutePo route = eval.routes.get(routeId);
                        int updated = routeRepo.compareAndIncrementVersion(routeId, route.version());
                        if (updated == 0) {
                            // 兜底：持锁期间不应发生，防御并发实现变化
                            throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                                    "航线版本已变化，请使用最新 expectedVersion 重试: " + routeId);
                        }
                        capacityRepo.deleteOccupancyByRoute(routeId);
                        List<Bucket> after = eval.afterPaths.get(routeId);
                        for (int i = 0; i < after.size(); i++) {
                            capacityRepo.insertOccupancy(new OccupancyPo(routeId,
                                    route.version() + 1, i, after.get(i).cellId(),
                                    after.get(i).bucketStart(), eval.reviewIds.get(routeId)));
                        }
                        routeResults.add(new TransferRouteResultDto(routeId, route.version(),
                                route.version() + 1, eval.reviewIds.get(routeId)));
                    }
                    // 冻结证据：转配单头、转配项、逐航线前后路径与审查依据、受影响桶余量
                    long createdAt = nowMillis();
                    transferRepo.insertTransfer(
                            new TransferPo(request.transferKey(), request.requestId(), createdAt));
                    int seq = 0;
                    for (TransferItemDto item : request.items()) {
                        transferRepo.insertItem(new TransferItemPo(request.transferKey(), seq++,
                                item.routeId(), item.expectedVersion(),
                                item.source().cellId(), item.source().bucketStart(),
                                item.target().cellId(), item.target().bucketStart()));
                    }
                    for (String routeId : eval.sortedRouteIds()) {
                        RoutePo route = eval.routes.get(routeId);
                        transferRepo.insertRoute(new TransferRoutePo(request.transferKey(), routeId,
                                route.version(), route.version() + 1, eval.reviewIds.get(routeId),
                                eval.beforePaths.get(routeId), eval.afterPaths.get(routeId)));
                    }
                    for (BucketMarginDto margin : eval.margins()) {
                        transferRepo.insertBucket(new TransferBucketPo(request.transferKey(),
                                margin.cellId(), margin.bucketStart(), margin.maxFlights(),
                                margin.beforeCount(), margin.afterCount()));
                    }
                    return new MutationResponse(request.requestId(), false,
                            new TransferResultDto(request.transferKey(), routeResults, createdAt));
                });
    }

    /** 查询转配冻结证据（只读，按桶、航线稳定排序）。 */
    public TransferEvidenceDto getTransferEvidence(String transferKey) {
        return txTemplate.execute(status -> {
            TransferPo po = transferRepo.findTransfer(transferKey);
            if (po == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND",
                        "转配单不存在: " + transferKey);
            }
            List<TransferItemDto> items = new ArrayList<>();
            for (TransferItemPo item : transferRepo.findItems(transferKey)) {
                items.add(new TransferItemDto(item.routeId(), item.expectedVersion(),
                        new BucketDto(item.sourceCell(), item.sourceBucket()),
                        new BucketDto(item.targetCell(), item.targetBucket())));
            }
            List<TransferRouteEvidenceDto> routes = new ArrayList<>();
            for (TransferRoutePo route : transferRepo.findRoutes(transferKey)) {
                routes.add(new TransferRouteEvidenceDto(route.routeId(), route.oldVersion(),
                        route.newVersion(), route.reviewId(),
                        toBucketDtos(route.beforePath()), toBucketDtos(route.afterPath())));
            }
            List<BucketMarginDto> buckets = new ArrayList<>();
            for (TransferBucketPo bucket : transferRepo.findBuckets(transferKey)) {
                buckets.add(new BucketMarginDto(bucket.cellId(), bucket.bucketStart(),
                        bucket.maxFlights(), bucket.beforeCount(), bucket.afterCount(),
                        bucket.maxFlights() == null ? null
                                : bucket.maxFlights() - bucket.afterCount()));
            }
            return new TransferEvidenceDto(po.transferKey(), po.createdAt(), items, routes,
                    buckets);
        });
    }

    // ============================ 转配评估（预览与激活共用） ============================

    /**
     * 按完整后态评估转配项集合。
     *
     * @param locked true 时对参与航线行加写锁（激活路径，调用方须已持协调锁）；
     *               false 为只读预览
     */
    private Evaluation evaluate(List<TransferItemDto> items, boolean locked) {
        Evaluation eval = new Evaluation();

        // ---- 集合与格式校验 ----
        Map<String, List<TransferItemDto>> itemsByRoute = new LinkedHashMap<>();
        for (TransferItemDto item : items) {
            itemsByRoute.computeIfAbsent(item.routeId(), k -> new ArrayList<>()).add(item);
            validateItemFormat(item, eval);
        }
        if (itemsByRoute.size() < MIN_TRANSFER_ROUTES || itemsByRoute.size() > MAX_TRANSFER_ROUTES) {
            eval.violate("TRANSFER_SET_INCOMPLETE",
                    "参与航线版本数须为 " + MIN_TRANSFER_ROUTES + "~" + MAX_TRANSFER_ROUTES
                            + "，当前为 " + itemsByRoute.size());
        }
        Map<String, Bucket> seenSources = new HashMap<>();
        for (TransferItemDto item : items) {
            Bucket source = new Bucket(item.source().cellId(), item.source().bucketStart());
            String key = item.routeId() + "|" + source.cellId() + "@" + source.bucketStart();
            if (seenSources.put(key, source) != null) {
                eval.violate("DUPLICATE_ITEM",
                        "同一航线的源桶重复: " + item.routeId() + " "
                                + source.cellId() + "@" + source.bucketStart());
            }
        }

        // ---- 逐航线读取与版本/激活校验（按 routeId 字典序，保证加锁顺序确定） ----
        for (String routeId : itemsByRoute.keySet().stream().sorted().toList()) {
            RoutePo route = locked
                    ? routeRepo.findRouteForUpdate(routeId)
                    : routeRepo.findRoute(routeId);
            eval.routeVersions.add(new RouteVersionDto(routeId,
                    route == null ? null : route.version()));
            if (route == null) {
                eval.violate("ROUTE_NOT_FOUND", "航线不存在: " + routeId);
                continue;
            }
            eval.routes.put(routeId, route);
            for (TransferItemDto item : itemsByRoute.get(routeId)) {
                if (item.expectedVersion() != route.version()) {
                    eval.violate("VERSION_CONFLICT",
                            "航线版本不匹配: " + routeId + " expected=" + item.expectedVersion()
                                    + ", current=" + route.version());
                }
            }
            List<OccupancyPo> occupancy = capacityRepo.findOccupancy(routeId);
            if (occupancy.isEmpty()) {
                eval.violate("ROUTE_NOT_ACTIVE", "航线未激活或已停用: " + routeId);
                continue;
            }
            List<Bucket> before = new ArrayList<>();
            for (OccupancyPo po : occupancy) {
                before.add(new Bucket(po.cellId(), po.bucketStart()));
            }
            eval.beforePaths.put(routeId, before);
            eval.reviewIds.put(routeId, occupancy.get(0).reviewId());

            // 应用该航线的全部转配项得到后态路径
            List<Bucket> after = new ArrayList<>(before);
            for (TransferItemDto item : itemsByRoute.get(routeId)) {
                Bucket source = new Bucket(item.source().cellId(), item.source().bucketStart());
                int index = before.indexOf(source);
                if (index < 0) {
                    eval.violate("SOURCE_BUCKET_NOT_FOUND",
                            "源桶不在该航线当前占用集合中: " + routeId + " "
                                    + source.cellId() + "@" + source.bucketStart());
                    continue;
                }
                Bucket target = new Bucket(item.target().cellId(), item.target().bucketStart());
                after.set(index, target);
                eval.appliedItems.computeIfAbsent(routeId, k -> new LinkedHashMap<>())
                        .put(index, target);
            }
            eval.afterPaths.put(routeId, after);
        }

        // ---- 路径连续性与禁飞校验（按完整后态） ----
        List<ZonePo> activeZones = airspaceRepo.findActiveZones();
        for (Map.Entry<String, List<Bucket>> entry : eval.afterPaths.entrySet()) {
            String routeId = entry.getKey();
            List<Bucket> after = entry.getValue();
            for (Map.Entry<Integer, Bucket> applied : eval.appliedItems
                    .getOrDefault(routeId, Map.of()).entrySet()) {
                int index = applied.getKey();
                Bucket target = applied.getValue();
                Bucket source = eval.beforePaths.get(routeId).get(index);
                if (target.bucketStart() != source.bucketStart()) {
                    eval.violate("PATH_NOT_CONTINUOUS",
                            "目标桶时间须与源桶一致以保持序列连续: " + routeId + " "
                                    + target.cellId() + "@" + target.bucketStart());
                }
                if (index > 0
                        && !Cells.adjacentOrSame(after.get(index - 1).cellId(), target.cellId())) {
                    eval.violate("PATH_NOT_CONTINUOUS",
                            "目标桶与前序路径不连续: " + routeId + " " + target.cellId()
                                    + " 与前序单元 " + after.get(index - 1).cellId());
                }
                if (index + 1 < after.size()
                        && !Cells.adjacentOrSame(target.cellId(), after.get(index + 1).cellId())) {
                    eval.violate("PATH_NOT_CONTINUOUS",
                            "目标桶与后续路径不连续: " + routeId + " " + target.cellId()
                                    + " 与后续单元 " + after.get(index + 1).cellId());
                }
            }
            // 转配后完整路径不得穿越有效禁飞区
            for (Bucket bucket : after) {
                for (ZonePo zone : activeZones) {
                    if (Cells.cellIntersectsRectangle(bucket.cellId(), zone.xMin(), zone.yMin(),
                            zone.xMax(), zone.yMax())) {
                        eval.violate("NO_FLY_CONFLICT",
                                "转配后路径穿越禁飞区: " + routeId + " " + bucket.cellId()
                                        + "@" + bucket.bucketStart() + " 命中 " + zone.zoneId());
                    }
                }
            }
        }

        // ---- 容量校验：汇总全部项的净变化（闭环友好），计入全量占用 ----
        Map<Bucket, Integer> delta = new TreeMap<>(Comparator
                .comparing(Bucket::cellId).thenComparing(Bucket::bucketStart));
        for (Map.Entry<String, List<Bucket>> entry : eval.afterPaths.entrySet()) {
            String routeId = entry.getKey();
            for (Map.Entry<Integer, Bucket> applied : eval.appliedItems
                    .getOrDefault(routeId, Map.of()).entrySet()) {
                Bucket source = eval.beforePaths.get(routeId).get(applied.getKey());
                delta.merge(source, -1, Integer::sum);
                delta.merge(applied.getValue(), 1, Integer::sum);
            }
        }
        for (Map.Entry<Bucket, Integer> entry : delta.entrySet()) {
            Bucket bucket = entry.getKey();
            int change = entry.getValue();
            // 净变化为零的桶（闭环互换）同样属于受影响桶，须计入余量明细
            int before = capacityRepo.countByBucket(bucket.cellId(), bucket.bucketStart());
            int after = before + change;
            CapacityConfigPo config = capacityRepo.findConfig(bucket.cellId(),
                    bucket.bucketStart());
            Integer max = config == null ? null : config.maxFlights();
            if (max != null && after > max) {
                eval.violate("CAPACITY_EXCEEDED",
                        "时空桶容量超限: " + bucket.cellId() + "@" + bucket.bucketStart()
                                + " 后态占用=" + after + ", 上限=" + max);
            }
            eval.margins.add(new BucketMarginDto(bucket.cellId(), bucket.bucketStart(), max,
                    before, after, max == null ? null : max - after));
        }

        eval.sortAndFreeze();
        return eval;
    }

    /** 评估结果可变中间态；sortAndFreeze 后 violations/margins/routeVersions 稳定有序。 */
    private static final class Evaluation {
        private final Map<String, RoutePo> routes = new TreeMap<>();
        private final Map<String, List<Bucket>> beforePaths = new TreeMap<>();
        private final Map<String, List<Bucket>> afterPaths = new TreeMap<>();
        private final Map<String, String> reviewIds = new TreeMap<>();
        private final Map<String, Map<Integer, Bucket>> appliedItems = new TreeMap<>();
        private final List<RouteVersionDto> routeVersions = new ArrayList<>();
        private final List<BucketMarginDto> margins = new ArrayList<>();
        private final List<ViolationDto> violations = new ArrayList<>();

        void violate(String code, String message) {
            violations.add(new ViolationDto(code, message));
        }

        List<ViolationDto> violations() {
            return violations;
        }

        List<RouteVersionDto> routeVersions() {
            return routeVersions;
        }

        List<BucketMarginDto> margins() {
            return margins;
        }

        List<String> sortedRouteIds() {
            return List.copyOf(routes.keySet());
        }

        void sortAndFreeze() {
            routeVersions.sort(Comparator.comparing(RouteVersionDto::routeId));
            margins.sort(Comparator.comparing(BucketMarginDto::cellId)
                    .thenComparing(BucketMarginDto::bucketStart));
            violations.sort(Comparator.comparing(ViolationDto::code)
                    .thenComparing(ViolationDto::message));
        }
    }

    /** 违规映射为激活响应：版本冲突 409，其余 422；附全部违规明细。 */
    private static ApiException toException(List<ViolationDto> violations) {
        boolean conflict = violations.stream()
                .anyMatch(v -> "VERSION_CONFLICT".equals(v.code()));
        ViolationDto first = violations.get(0);
        StringBuilder message = new StringBuilder(first.message());
        if (violations.size() > 1) {
            message.append("（共 ").append(violations.size()).append(" 项违规）");
        }
        return new ApiException(
                conflict ? HttpStatus.CONFLICT : HttpStatus.UNPROCESSABLE_ENTITY,
                first.code(), message.toString());
    }

    // ============================ 工具方法 ============================

    private static void validateBucket(String cellId, long bucketStart) {
        if (!Cells.isValidCellId(cellId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CELL_ID",
                    "空域单元标识格式非法（应为 C<gx>_<gy>）: " + cellId);
        }
        if (!Cells.isAlignedBucketStart(bucketStart)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNALIGNED_BUCKET",
                    "时间桶起始必须对齐 15 分钟 UTC 时间桶: " + bucketStart);
        }
    }

    private static void validateItemFormat(TransferItemDto item, Evaluation eval) {
        if (!Cells.isValidCellId(item.source().cellId())
                || !Cells.isValidCellId(item.target().cellId())) {
            eval.violate("INVALID_CELL_ID",
                    "空域单元标识格式非法（应为 C<gx>_<gy>）: " + item.routeId());
        }
        if (!Cells.isAlignedBucketStart(item.source().bucketStart())
                || !Cells.isAlignedBucketStart(item.target().bucketStart())) {
            eval.violate("UNALIGNED_BUCKET",
                    "时间桶起始必须对齐 15 分钟 UTC 时间桶: " + item.routeId());
        }
        if (item.source().cellId().equals(item.target().cellId())
                && item.source().bucketStart().equals(item.target().bucketStart())) {
            eval.violate("TARGET_EQUALS_SOURCE",
                    "目标桶与源桶相同，不构成转配: " + item.routeId() + " "
                            + item.source().cellId() + "@" + item.source().bucketStart());
        }
    }

    private static List<BucketDto> toBucketDtos(List<Bucket> path) {
        List<BucketDto> dtos = new ArrayList<>(path.size());
        for (Bucket bucket : path) {
            dtos.add(new BucketDto(bucket.cellId(), bucket.bucketStart()));
        }
        return dtos;
    }

    private long nowMillis() {
        return clock.instant().toEpochMilli();
    }
}
