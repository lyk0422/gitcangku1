package com.example.starter.service;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.BucketCapacityDto;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.RouteEvidenceDto;
import com.example.starter.api.dto.RoutePreviewDto;
import com.example.starter.api.dto.RouteVersionAfterDto;
import com.example.starter.api.dto.TransferActivateRequest;
import com.example.starter.api.dto.TransferActivateResult;
import com.example.starter.api.dto.TransferEvidenceResult;
import com.example.starter.api.dto.TransferItemRequest;
import com.example.starter.api.dto.TransferPreviewResult;
import com.example.starter.api.dto.ViolationDto;
import com.example.starter.domain.BucketSlot;
import com.example.starter.repo.AirspaceRepository;
import com.example.starter.repo.CapacityConfigPo;
import com.example.starter.repo.CapacityConfigRepository;
import com.example.starter.repo.DedupPo;
import com.example.starter.repo.OccupancyPlanRepository;
import com.example.starter.repo.PlanSlotPo;
import com.example.starter.repo.ReviewPo;
import com.example.starter.repo.ReviewRepository;
import com.example.starter.repo.RoutePo;
import com.example.starter.repo.RouteRepository;
import com.example.starter.repo.TransferBucketEvidencePo;
import com.example.starter.repo.TransferPo;
import com.example.starter.repo.TransferRepository;
import com.example.starter.repo.TransferRouteEvidencePo;
import com.example.starter.repo.ZonePo;
import com.example.starter.service.TransferPlanner.Directive;
import com.example.starter.service.TransferPlanner.Outcome;
import com.example.starter.service.TransferPlanner.Violation;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * 容量转配服务：预览（只读）与激活（单事务原子闭环转配）。
 *
 * <p>激活事务先锁全局协调行（与禁飞区变更、审核、容量配置互斥），
 * 再按 routeId 字典序锁全部参与航线行（与航线替换互斥），随后在持锁状态下
 * 重新读取航线、禁飞区、容量配置与全量占用，按完整后态一次性校验并替换：
 * 任一版本变化、航线停用、路径不连续、禁飞冲突、集合遗漏或容量超限都整体回滚，
 * 不会形成部分转配。</p>
 */
@Service
public class CapacityTransferService {

    static final String KIND_TRANSFER_ACTIVATE = "TRANSFER_ACTIVATE";

    private final CapacityConfigRepository configRepo;
    private final OccupancyPlanRepository planRepo;
    private final RouteRepository routeRepo;
    private final ReviewRepository reviewRepo;
    private final AirspaceRepository airspaceRepo;
    private final TransferRepository transferRepo;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate txTemplate;

    public CapacityTransferService(CapacityConfigRepository configRepo,
                                   OccupancyPlanRepository planRepo,
                                   RouteRepository routeRepo,
                                   ReviewRepository reviewRepo,
                                   AirspaceRepository airspaceRepo,
                                   TransferRepository transferRepo,
                                   ObjectMapper objectMapper,
                                   Clock clock,
                                   PlatformTransactionManager transactionManager) {
        this.configRepo = configRepo;
        this.planRepo = planRepo;
        this.routeRepo = routeRepo;
        this.reviewRepo = reviewRepo;
        this.airspaceRepo = airspaceRepo;
        this.transferRepo = transferRepo;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    // ============================ 预览（只读） ============================

    /**
     * 预览转配：按完整后态计算各航线穿越序列与全部桶占用，只读返回版本、
     * 容量余量与违规明细，不产生任何写入。
     */
    public TransferPreviewResult preview(TransferActivateRequest request) {
        return txTemplate.execute(status -> {
            // 与激活相同的加锁顺序：先全局协调行、再参与航线行，
            // 保证预览读到的空域版本、航线点列、容量配置与全量占用来自同一已提交状态。
            // touch 更新只产生行锁，预览不做任何业务写入。
            airspaceRepo.getGlobalVersionForUpdate();
            Snapshot snapshot = loadSnapshot(request.items(), true);
            Outcome outcome = TransferPlanner.plan(snapshot.routes(), snapshot.beforePlans(),
                    snapshot.directives(), snapshot.baselineUsed(), snapshot.capacityByBucket(),
                    snapshot.activeZones());
            List<ViolationDto> violations = new ArrayList<>();
            violations.addAll(snapshot.preViolations());
            for (Violation v : outcome.violations) {
                violations.add(new ViolationDto(v.type(), v.routeId(), v.detail()));
            }
            List<RoutePreviewDto> routes = new ArrayList<>();
            for (String routeId : snapshot.participantRouteIds()) {
                RoutePo route = snapshot.routes().get(routeId);
                List<PlanSlotPo> before = snapshot.beforePlans()
                        .getOrDefault(routeId, List.of());
                List<PlanSlotPo> after = outcome.afterPlans.getOrDefault(routeId, before);
                List<ViolationDto> routeViolations = violations.stream()
                        .filter(v -> routeId.equals(v.routeId())).toList();
                routes.add(new RoutePreviewDto(routeId,
                        route == null ? null : route.version(),
                        CapacityService.toSlotDtos(before), CapacityService.toSlotDtos(after),
                        routeViolations));
            }
            List<BucketCapacityDto> buckets = toBucketDtos(outcome, snapshot.capacityByBucket());
            boolean feasible = violations.isEmpty();
            return new TransferPreviewResult(request.transferKey(), snapshot.airspaceVersion(),
                    feasible, routes, buckets, violations);
        });
    }

    // ============================ 激活 ============================

    /**
     * 激活转配：单事务内重读全部状态并原子替换全部占用、逐航线增版、冻结证据。
     * 同 requestId 同参（项换序视为同参）重放首次快照；异参 409；失败不占键。
     */
    public MutationResponse activate(TransferActivateRequest request) {
        String paramHash = transferHash(request);
        try {
            return txTemplate.execute(status ->
                    doActivate(request, paramHash));
        } catch (DuplicateKeyException dup) {
            return resolveActivateRace(request, paramHash, dup);
        } catch (ApiException api) {
            if (api.status() == HttpStatus.CONFLICT) {
                return resolveActivateRace(request, paramHash, api);
            }
            throw api;
        }
    }

    private MutationResponse doActivate(TransferActivateRequest request, String paramHash) {
        DedupPo existing = reviewRepo.findDedup(request.requestId());
        if (existing != null) {
            ensureSameRequest(existing, KIND_TRANSFER_ACTIVATE, paramHash);
            return deserializeReplay(existing);
        }
        if (transferRepo.findTransfer(request.transferKey()) != null) {
            throw new ApiException(HttpStatus.CONFLICT, "TRANSFER_KEY_EXISTS",
                    "transferKey 已存在: " + request.transferKey());
        }
        // 锁全局协调行：与禁飞区变更、审核、容量配置、另一转配互斥
        long airspaceVersion = airspaceRepo.getGlobalVersionForUpdate();
        Snapshot snapshot = loadSnapshot(request.items(), true);
        // 前置校验：版本变化 / 航线停用 / 未登记序列 → 409/422，整体回滚
        if (!snapshot.preViolations().isEmpty()) {
            throw toActivationException(snapshot.preViolations().get(0));
        }
        Outcome outcome = TransferPlanner.plan(snapshot.routes(), snapshot.beforePlans(),
                snapshot.directives(), snapshot.baselineUsed(), snapshot.capacityByBucket(),
                snapshot.activeZones());
        if (!outcome.feasible()) {
            throw toActivationException(toDto(outcome.violations.get(0)));
        }
        // 一次性替换全部占用：逐航线条件增版（兜底并发替换），写入新版本序列
        List<RouteVersionAfterDto> routeResults = new ArrayList<>();
        List<TransferRouteEvidencePo> routeEvidence = new ArrayList<>();
        for (String routeId : snapshot.participantRouteIds()) {
            RoutePo route = snapshot.routes().get(routeId);
            int updated = routeRepo.compareAndIncrementVersion(routeId, route.version());
            if (updated == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                        "航线版本已变化，请使用最新 expectedVersion 重试: " + routeId);
            }
            List<PlanSlotPo> after = outcome.afterPlans.get(routeId);
            planRepo.insertPlan(routeId, route.version() + 1, after);
            // 新版本在持锁状态下已验证不穿越任何有效禁飞区：写入系统 CLEAR 审核，
            // 使新版本保持 ACTIVE（容量占用自增版起生效，且可参与后续转配）
            String sysReviewId = "rv_" + java.util.UUID.randomUUID();
            reviewRepo.insertReview(new ReviewPo(sysReviewId, routeId, route.version() + 1,
                    airspaceVersion, "CLEAR", List.of(), List.copyOf(route.points()),
                    "trf-" + java.util.UUID.randomUUID(), nowMillis()));
            routeResults.add(new RouteVersionAfterDto(routeId, route.version(),
                    route.version() + 1, CapacityService.toSlotDtos(after)));
            ReviewPo review = snapshot.currentReviews().get(routeId);
            routeEvidence.add(new TransferRouteEvidencePo(request.transferKey(), routeId,
                    route.version(), route.version() + 1,
                    review.reviewId(), review.routeVersion(), review.airspaceVersion(),
                    snapshot.beforePlans().get(routeId), after));
        }
        // 冻结转配单与证据
        transferRepo.insertTransfer(new TransferPo(request.transferKey(), request.requestId(),
                request.items().size(), nowMillis()));
        for (TransferRouteEvidencePo po : routeEvidence) {
            transferRepo.insertRouteEvidence(po);
        }
        List<BucketCapacityDto> buckets = toBucketDtos(outcome, snapshot.capacityByBucket());
        for (BucketCapacityDto bucket : buckets) {
            transferRepo.insertBucketEvidence(new TransferBucketEvidencePo(request.transferKey(),
                    bucket.cellX(), bucket.cellY(), bucket.bucketStart(), bucket.maxFlights(),
                    bucket.usedBefore(), bucket.usedAfter()));
        }
        MutationResponse result = new MutationResponse(request.requestId(), false,
                new TransferActivateResult(request.transferKey(), routeResults, buckets));
        reviewRepo.insertDedup(new DedupPo(request.requestId(), KIND_TRANSFER_ACTIVATE,
                paramHash, writeJson(result), nowMillis()));
        return result;
    }

    /**
     * 并发落败后的裁决：去重表存在同键记录则按重放/异参处理；
     * 否则若是 transferKey 撞键则报 TRANSFER_KEY_EXISTS，其余抛原错误。
     */
    private MutationResponse resolveActivateRace(TransferActivateRequest request, String paramHash,
                                                 Exception original) {
        DedupPo winner = txTemplate.execute(status -> reviewRepo.findDedup(request.requestId()));
        if (winner != null) {
            ensureSameRequest(winner, KIND_TRANSFER_ACTIVATE, paramHash);
            return deserializeReplay(winner);
        }
        TransferPo existing = txTemplate.execute(status ->
                transferRepo.findTransfer(request.transferKey()));
        if (existing != null) {
            throw new ApiException(HttpStatus.CONFLICT, "TRANSFER_KEY_EXISTS",
                    "transferKey 已存在: " + request.transferKey());
        }
        if (original instanceof ApiException api) {
            throw api;
        }
        throw new ApiException(HttpStatus.CONFLICT, "RESOURCE_CONFLICT",
                "并发资源冲突，请稍后使用相同 requestId 与参数重试");
    }

    // ============================ 证据查询（只读） ============================

    /** 按 transferKey 查询冻结证据；不存在 404。按航线、桶稳定排序。 */
    public TransferEvidenceResult getEvidence(String transferKey) {
        return txTemplate.execute(status -> {
            TransferPo transfer = transferRepo.findTransfer(transferKey);
            if (transfer == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND",
                        "转配单不存在: " + transferKey);
            }
            List<RouteEvidenceDto> routes = new ArrayList<>();
            for (TransferRouteEvidencePo po : transferRepo.findRouteEvidences(transferKey)) {
                routes.add(new RouteEvidenceDto(po.routeId(), po.fromVersion(), po.toVersion(),
                        po.reviewId(), po.reviewRouteVersion(), po.reviewAirspaceVersion(),
                        CapacityService.toSlotDtos(po.beforePlan()),
                        CapacityService.toSlotDtos(po.afterPlan())));
            }
            List<BucketCapacityDto> buckets = new ArrayList<>();
            for (TransferBucketEvidencePo po : transferRepo.findBucketEvidences(transferKey)) {
                buckets.add(new BucketCapacityDto(po.cellX(), po.cellY(), po.bucketStart(),
                        po.maxFlights(), po.usedBefore(), po.usedAfter()));
            }
            return new TransferEvidenceResult(transfer.transferKey(), transfer.requestId(),
                    transfer.activatedAt(), routes, buckets);
        });
    }

    // ============================ 快照装载与校验 ============================

    /**
     * 激活/预览的一致性输入快照。
     *
     * @param routes             参与航线当前状态（含点列）
     * @param beforePlans        参与航线当前版本穿越序列
     * @param directives         规范化转配指令
     * @param baselineUsed       相关桶转配前全量占用（含未参与航线）
     * @param capacityByBucket   相关桶容量上限（未配置按 0）
     * @param activeZones        全部有效禁飞区
     * @param currentReviews     各参与航线当前 CLEAR 审核（激活时冻结为审查依据）
     * @param preViolations      前置违规（版本变化/航线停用/未登记序列）
     * @param participantRouteIds 参与航线（字典序）
     * @param airspaceVersion    快照空域版本
     */
    private record Snapshot(Map<String, RoutePo> routes,
                            Map<String, List<PlanSlotPo>> beforePlans,
                            List<Directive> directives,
                            Map<BucketSlot, Integer> baselineUsed,
                            Map<BucketSlot, Integer> capacityByBucket,
                            List<ZonePo> activeZones,
                            Map<String, ReviewPo> currentReviews,
                            List<ViolationDto> preViolations,
                            List<String> participantRouteIds,
                            long airspaceVersion) {
    }

    /**
     * 装载一致性快照。forUpdate=true 时按 routeId 字典序对参与航线行加写锁
     *（调用方须已持有全局协调锁），与航线替换事务互斥。
     */
    private Snapshot loadSnapshot(List<TransferItemRequest> items, boolean forUpdate) {
        List<Directive> directives = TransferPlanner.normalizeDirectives(items);
        Map<String, Integer> expectedVersions = new LinkedHashMap<>();
        Map<String, Integer> planVersions = new LinkedHashMap<>();
        for (TransferItemRequest item : items) {
            mergeVersion(expectedVersions, item.routeId(), item.expectedVersion());
            mergeVersion(planVersions, item.routeId(), item.routeVersion());
        }
        List<String> routeIds = new ArrayList<>(expectedVersions.keySet());
        routeIds.sort(String::compareTo);

        List<ViolationDto> preViolations = new ArrayList<>();
        Map<String, RoutePo> routes = new HashMap<>();
        Map<String, List<PlanSlotPo>> beforePlans = new HashMap<>();
        Map<String, ReviewPo> currentReviews = new HashMap<>();
        long airspaceVersion = airspaceRepo.getGlobalVersion();

        for (String routeId : routeIds) {
            RoutePo route = forUpdate
                    ? routeRepo.findRouteForUpdate(routeId)
                    : routeRepo.findRoute(routeId);
            if (route == null) {
                preViolations.add(new ViolationDto("ROUTE_NOT_FOUND", routeId,
                        "航线不存在: " + routeId));
                continue;
            }
            routes.put(routeId, route);
            int expected = expectedVersions.get(routeId);
            int planVersion = planVersions.get(routeId);
            if (route.version() != expected || route.version() != planVersion) {
                preViolations.add(new ViolationDto("VERSION_CONFLICT", routeId,
                        "航线 " + routeId + " 版本已变化：submitted routeVersion=" + planVersion
                                + ", expectedVersion=" + expected
                                + ", current=" + route.version()));
                continue;
            }
            // 航线停用判定：当前版本必须持有与当前航线版本、当前空域版本一致的 CLEAR 审核。
            // 预览时该违规与几何/容量违规并列返回，不影响后态计算；激活时任一前置违规即拒绝。
            ReviewPo latest = reviewRepo.findLatestReview(routeId);
            boolean active = latest != null
                    && "CLEAR".equals(latest.conclusion())
                    && latest.routeVersion() == route.version()
                    && latest.airspaceVersion() == airspaceVersion;
            if (!active) {
                preViolations.add(new ViolationDto("ROUTE_INACTIVE", routeId,
                        "航线 " + routeId + " 当前版本无有效 CLEAR 审核（已停用或审核失效）"));
            } else {
                currentReviews.put(routeId, latest);
            }
            List<PlanSlotPo> plan = planRepo.findPlan(routeId, route.version());
            if (plan.isEmpty()) {
                preViolations.add(new ViolationDto("ITEM_NOT_IN_PLAN", routeId,
                        "航线 " + routeId + " 当前版本未登记穿越序列"));
                continue;
            }
            beforePlans.put(routeId, plan);
        }

        // 相关桶：参与航线前态序列 ∪ 全部转配目标桶
        TreeMap<BucketSlot, Boolean> affected = new TreeMap<>();
        for (List<PlanSlotPo> plan : beforePlans.values()) {
            for (PlanSlotPo slot : plan) {
                affected.put(new BucketSlot(slot.cellX(), slot.cellY(), slot.bucketStart()),
                        Boolean.TRUE);
            }
        }
        for (Directive directive : directives) {
            affected.put(directive.target(), Boolean.TRUE);
            affected.put(directive.source(), Boolean.TRUE);
        }
        List<BucketSlot> bucketList = new ArrayList<>(affected.keySet());

        Map<BucketSlot, Integer> baselineUsed = new HashMap<>();
        if (!bucketList.isEmpty()) {
            for (Object[] row : configRepo.countCurrentOccupancyGrouped(bucketList)) {
                baselineUsed.put(new BucketSlot((Integer) row[0], (Integer) row[1], (Long) row[2]),
                        (Integer) row[3]);
            }
        }
        Map<BucketSlot, Integer> capacityByBucket = new HashMap<>();
        for (BucketSlot bucket : bucketList) {
            CapacityConfigPo config = configRepo.find(bucket.cellX(), bucket.cellY(),
                    bucket.bucketStart());
            capacityByBucket.put(bucket, config == null ? 0 : config.maxFlights());
        }
        List<ZonePo> activeZones = airspaceRepo.findActiveZones();
        return new Snapshot(routes, beforePlans, directives, baselineUsed, capacityByBucket,
                activeZones, currentReviews, preViolations, routeIds, airspaceVersion);
    }

    private static List<BucketCapacityDto> toBucketDtos(Outcome outcome,
                                                        Map<BucketSlot, Integer> capacity) {
        List<BucketCapacityDto> buckets = new ArrayList<>();
        for (BucketSlot bucket : outcome.affectedBuckets) {
            buckets.add(new BucketCapacityDto(bucket.cellX(), bucket.cellY(), bucket.bucketStart(),
                    capacity.getOrDefault(bucket, 0),
                    outcome.usedBefore.getOrDefault(bucket, 0),
                    outcome.usedAfter.getOrDefault(bucket, 0)));
        }
        return buckets;
    }

    private static ViolationDto toDto(Violation v) {
        return new ViolationDto(v.type(), v.routeId(), v.detail());
    }

    /** 激活失败映射：版本/停用/键冲突 → 409；路径/禁飞/集合/容量 → 422。 */
    private static ApiException toActivationException(ViolationDto violation) {
        HttpStatus status = switch (violation.type()) {
            case "VERSION_CONFLICT", "ROUTE_INACTIVE", "ROUTE_NOT_FOUND" -> HttpStatus.CONFLICT;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        String code = switch (violation.type()) {
            case TransferPlanner.V_DISCONTINUOUS_PATH -> "DISCONTINUOUS_PATH";
            case TransferPlanner.V_NO_FLY_CONFLICT -> "NO_FLY_CONFLICT";
            case TransferPlanner.V_ITEM_NOT_IN_PLAN -> "ITEM_NOT_IN_PLAN";
            case TransferPlanner.V_CAPACITY_EXCEEDED -> "CAPACITY_EXCEEDED";
            default -> violation.type();
        };
        return new ApiException(status, code, violation.detail());
    }

    private static void mergeVersion(Map<String, Integer> versions, String routeId, int version) {
        Integer existing = versions.get(routeId);
        if (existing != null && existing != version) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INCONSISTENT_ITEM_VERSION",
                    "同一航线的转配项必须指定相同版本: " + routeId);
        }
        versions.put(routeId, version);
    }

    // ============================ 幂等与哈希 ============================

    /**
     * 转配请求规范化哈希：转配项先按 (routeId, 版本, 源桶, 目标桶) 全字段排序，
     * 项换序视为同参；其余字段参与规范化哈希。
     */
    private String transferHash(TransferActivateRequest request) {
        List<TransferItemRequest> sorted = new ArrayList<>(request.items());
        sorted.sort(Comparator.comparing(TransferItemRequest::routeId)
                .thenComparing(TransferItemRequest::routeVersion)
                .thenComparing(TransferItemRequest::expectedVersion)
                .thenComparing(i -> i.source().cellX())
                .thenComparing(i -> i.source().cellY())
                .thenComparing(i -> i.source().bucketStart())
                .thenComparing(i -> i.target().cellX())
                .thenComparing(i -> i.target().cellY())
                .thenComparing(i -> i.target().bucketStart()));
        return canonicalHash(new TransferActivateRequest(request.transferKey(),
                request.requestId(), sorted));
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
