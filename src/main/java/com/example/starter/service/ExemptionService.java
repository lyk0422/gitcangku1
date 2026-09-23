package com.example.starter.service;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.ConsumedQuota;
import com.example.starter.api.dto.ConsumptionDto;
import com.example.starter.api.dto.FlightReviewRequest;
import com.example.starter.api.dto.FlightReviewResultDto;
import com.example.starter.api.dto.FlightReviewSnapshot;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.PermitBalanceItem;
import com.example.starter.api.dto.PermitIssueRequest;
import com.example.starter.api.dto.PermitItemRequest;
import com.example.starter.api.dto.PermitRevokeRequest;
import com.example.starter.api.dto.PermitView;
import com.example.starter.api.dto.RegionDeficit;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.SnapshotHit;
import com.example.starter.domain.DeficitReason;
import com.example.starter.domain.Geometry;
import com.example.starter.domain.PermitStatus;
import com.example.starter.domain.Point;
import com.example.starter.domain.ReviewConclusion;
import com.example.starter.repo.AirspaceRepository;
import com.example.starter.repo.DedupPo;
import com.example.starter.repo.FlightReviewPo;
import com.example.starter.repo.FlightReviewRepository;
import com.example.starter.repo.PermitConsumptionPo;
import com.example.starter.repo.PermitItemPo;
import com.example.starter.repo.PermitPackagePo;
import com.example.starter.repo.PermitRepository;
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
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 多区域豁免包配额核销服务。
 *
 * <p>签发/撤销/核销均在单个数据库事务内完成业务变更与幂等去重记录的原子提交。
 * 审核事务按固定顺序加锁：协调锁行（与区域创建/撤销互斥）→ 航线行（与航线修订互斥）
 * → 候选豁免包行（permitKey 升序）→ 区域项行，形成提交时一致视图；
 * 并发审核在同一区域项行锁上串行，配合 remaining&gt;0 条件扣减保证不超扣。</p>
 *
 * <p>BLOCKED 属于“未形成审核”的失败结果：不写核销、不写审核记录、不占用
 * requestId/flightKey，事务整体回滚（锁行 touch 递增一并回滚），修正额度/有效期后
 * 可用相同参数重试。</p>
 */
@Service
public class ExemptionService {

    static final String KIND_PERMIT_ISSUE = "PERMIT_ISSUE";
    static final String KIND_PERMIT_REVOKE = "PERMIT_REVOKE";
    static final String KIND_FLIGHT_REVIEW = "FLIGHT_REVIEW";

    /** 豁免包不可变版本，恒为 1。 */
    static final int PERMIT_VERSION = 1;

    private final AirspaceRepository airspaceRepo;
    private final RouteRepository routeRepo;
    private final ReviewRepository reviewRepo;
    private final PermitRepository permitRepo;
    private final FlightReviewRepository flightReviewRepo;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate txTemplate;

    public ExemptionService(AirspaceRepository airspaceRepo,
                            RouteRepository routeRepo,
                            ReviewRepository reviewRepo,
                            PermitRepository permitRepo,
                            FlightReviewRepository flightReviewRepo,
                            ObjectMapper objectMapper,
                            Clock clock,
                            PlatformTransactionManager transactionManager) {
        this.airspaceRepo = airspaceRepo;
        this.routeRepo = routeRepo;
        this.reviewRepo = reviewRepo;
        this.permitRepo = permitRepo;
        this.flightReviewRepo = flightReviewRepo;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    // ============================ 豁免包签发/撤销 ============================

    /** 签发豁免包；permitKey 唯一，同 requestId 同参重放，异参/异种操作 409。 */
    public MutationResponse issuePermit(PermitIssueRequest request) {
        return withIdempotency(request.requestId(), KIND_PERMIT_ISSUE, canonicalHash(request), () -> {
            // 与审核/区域变更共用同一协调锁串行点：审核持锁期间签发不能提交，
            // 保证审核发现的候选豁免包集合在其提交前不被新增包改变（提交时一致视图）
            airspaceRepo.getGlobalVersionForUpdate();
            validateIssueItems(request.items());
            if (permitRepo.findPackage(request.permitKey()) != null) {
                throw new ApiException(HttpStatus.CONFLICT, "PERMIT_ALREADY_EXISTS",
                        "豁免包已存在: " + request.permitKey());
            }
            long now = nowMillis();
            permitRepo.insertPackage(new PermitPackagePo(request.permitKey(),
                    request.routeVersion(), PermitStatus.ISSUED.name(), PERMIT_VERSION,
                    request.requestId(), null, now, 0L));
            List<PermitBalanceItem> viewItems = new ArrayList<>(request.items().size());
            for (PermitItemRequest item : request.items()) {
                permitRepo.insertItem(new PermitItemPo(request.permitKey(), item.regionKey(),
                        item.regionVersion(), item.validFrom(), item.validTo(),
                        item.quota(), item.quota(), 0L));
                viewItems.add(new PermitBalanceItem(item.regionKey(), item.regionVersion(),
                        item.validFrom(), item.validTo(), item.quota(), item.quota()));
            }
            return new MutationResponse(request.requestId(), false, new PermitView(
                    request.permitKey(), request.routeVersion(), PERMIT_VERSION,
                    PermitStatus.ISSUED.name(), viewItems));
        });
    }

    /** 撤销豁免包未使用余额；历史核销不回写。 */
    public MutationResponse revokePermit(PermitRevokeRequest request) {
        return withIdempotency(request.requestId(), KIND_PERMIT_REVOKE, canonicalHash(request), () -> {
            PermitPackagePo locked = permitRepo.findPackageForUpdate(request.permitKey());
            if (locked == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "PERMIT_NOT_FOUND",
                        "豁免包不存在: " + request.permitKey());
            }
            if (PermitStatus.REVOKED.name().equals(locked.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "PERMIT_ALREADY_REVOKED",
                        "豁免包已撤销: " + request.permitKey());
            }
            long now = nowMillis();
            permitRepo.markRevoked(request.permitKey(), now);
            return new MutationResponse(request.requestId(), false,
                    toView(new PermitPackagePo(locked.permitKey(), locked.routeVersion(),
                            PermitStatus.REVOKED.name(), locked.permitVersion(),
                            locked.requestId(), now, locked.createdAt(), locked.touch()),
                            permitRepo.findItems(request.permitKey())));
        });
    }

    private static void validateIssueItems(List<PermitItemRequest> items) {
        Set<String> seen = new TreeSet<>();
        for (PermitItemRequest item : items) {
            if (item.validFrom() >= item.validTo()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PERMIT_WINDOW",
                        "区域项 UTC 有效区间必须满足 validFrom < validTo: " + item.regionKey());
            }
            if (!seen.add(item.regionKey())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "DUPLICATE_PERMIT_ITEM",
                        "同一豁免包内 regionKey 不可重复: " + item.regionKey());
            }
        }
    }

    // ============================ 航班审核与核销 ============================

    /**
     * 执行带 flightKey 与 reviewAt 的航线审核。
     *
     * <p>BLOCKED 通过 {@link BlockedReviewException} 触发事务回滚后把结果返回给调用方，
     * 因而不写入任何额度扣减、核销流水、审核记录与去重记录。</p>
     */
    public MutationResponse reviewFlight(FlightReviewRequest request) {
        try {
            return withIdempotency(request.requestId(), KIND_FLIGHT_REVIEW,
                    canonicalHash(request), () -> doReview(request));
        } catch (BlockedReviewException blocked) {
            // 事务已整体回滚：未扣任何额度、未占用 flightKey/requestId
            return new MutationResponse(request.requestId(), false, blocked.result());
        }
    }

    private MutationResponse doReview(FlightReviewRequest request) {
        // 0. 同 flightKey 只能形成一次成功审核：记录不可变，无需加锁即可先判定；
        //    同参重放原结果（不重复扣额），routeId/reviewAt 不同直接 409
        FlightReviewPo existing = flightReviewRepo.findByFlightKey(request.flightKey());
        if (existing != null) {
            return resolveExistingFlightReview(existing, request);
        }

        // 1. 协调锁行：与禁飞区创建/撤销、豁免包签发事务互斥，
        //    空域版本、全部区域与候选豁免包集合来自同一提交时一致视图
        long globalVersion = airspaceRepo.getGlobalVersionForUpdate();
        // 2. 航线行锁：与航线替换互斥，版本号与点列一致
        RoutePo route = routeRepo.findRouteForUpdate(request.routeId());
        if (route == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND",
                    "航线不存在: " + request.routeId());
        }
        // 持锁后复查：与并发首审在协调锁上串行，落败方此时必能读到赢家已提交记录
        existing = flightReviewRepo.findByFlightKey(request.flightKey());
        if (existing != null) {
            return resolveExistingFlightReview(existing, request);
        }

        // 4. 持锁读取全部有效禁飞区并计算几何命中；区域版本取该区域创建生效的空域版本
        List<ZonePo> activeZones = airspaceRepo.findActiveZones();
        TreeMap<String, Long> hitVersions = new TreeMap<>();
        for (ZonePo zone : activeZones) {
            if (Geometry.polylineHitsRectangle(route.points(),
                    zone.xMin(), zone.yMin(), zone.xMax(), zone.yMax())) {
                hitVersions.put(zone.zoneId(), zone.createdVersion());
            }
        }
        List<String> hitRegionKeys = new ArrayList<>(hitVersions.keySet());
        List<Point> pointsSnapshot = List.copyOf(route.points());
        long reviewAt = request.reviewAt();

        // 5. 未命中任何区域：直接 CLEAR，不消费任何豁免项
        if (hitRegionKeys.isEmpty()) {
            return finalizeClear(request, "fr_" + UUID.randomUUID(), null, route, globalVersion,
                    hitRegionKeys, hitVersions, pointsSnapshot, List.of());
        }

        // 6. 发现候选豁免包后按 permitKey 升序逐个加包行锁与项行锁；
        //    发现后被并发撤销的包在此处重新读取状态并跳过
        List<PermitPackagePo> discovered =
                permitRepo.findIssuedPackagesByRouteVersion(route.version());
        List<PermitPackagePo> candidates = new ArrayList<>();
        Map<String, List<PermitItemPo>> candidateItems = new LinkedHashMap<>();
        for (PermitPackagePo found : discovered) {
            PermitPackagePo locked = permitRepo.findPackageForUpdate(found.permitKey());
            if (locked == null || !PermitStatus.ISSUED.name().equals(locked.status())) {
                continue;
            }
            permitRepo.lockItems(locked.permitKey());
            List<PermitItemPo> items = permitRepo.findItems(locked.permitKey());
            candidates.add(locked);
            candidateItems.put(locked.permitKey(), items);
        }

        // 7. 逐包寻找覆盖全部命中区域的同一个有效豁免包（确定性：permitKey 最小者优先）
        for (PermitPackagePo pkg : candidates) {
            List<PermitItemPo> items = candidateItems.get(pkg.permitKey());
            Map<String, PermitItemPo> matched = matchAllHits(items, hitVersions, reviewAt);
            if (matched != null) {
                return deductAndCommitClear(request, pkg, items, matched, route, globalVersion,
                        hitRegionKeys, hitVersions, pointsSnapshot);
            }
        }

        // 8. 无豁免包覆盖全部命中区域：BLOCKED，返回缺口并整体回滚，不扣任何额度
        PermitPackagePo reported = candidates.isEmpty() ? null : candidates.get(0);
        List<PermitItemPo> reportedItems =
                reported == null ? List.of() : candidateItems.get(reported.permitKey());
        throw new BlockedReviewException(buildBlockedResult(request, route, globalVersion,
                hitRegionKeys, hitVersions, pointsSnapshot, reviewAt, reported, reportedItems));
    }

    /**
     * 判断一个豁免包是否对每个命中区域都有版本匹配、在 UTC 半开有效区间内且仍有余额的项。
     *
     * @return 全部命中时返回 regionKey→项 的映射；任一项缺失/过期/耗尽返回 null
     */
    private Map<String, PermitItemPo> matchAllHits(List<PermitItemPo> items,
                                                   TreeMap<String, Long> hitVersions,
                                                   long reviewAt) {
        Map<String, PermitItemPo> byRegion = new LinkedHashMap<>();
        for (PermitItemPo item : items) {
            byRegion.put(item.regionKey(), item);
        }
        Map<String, PermitItemPo> matched = new LinkedHashMap<>();
        for (Map.Entry<String, Long> hit : hitVersions.entrySet()) {
            PermitItemPo item = byRegion.get(hit.getKey());
            if (item == null || item.regionVersion() != hit.getValue()) {
                return null;
            }
            if (reviewAt < item.validFrom() || reviewAt >= item.validTo()) {
                return null;
            }
            if (item.remaining() <= 0) {
                return null;
            }
            matched.put(hit.getKey(), item);
        }
        return matched;
    }

    /**
     * 覆盖成功：在当前事务内对每个命中区域项扣 1 并写核销流水，再写不可变审核记录；
     * 任一步骤失败由事务整体回滚（全部不扣）。
     */
    private MutationResponse deductAndCommitClear(FlightReviewRequest request, PermitPackagePo pkg,
                                                  List<PermitItemPo> allItems,
                                                  Map<String, PermitItemPo> matched, RoutePo route,
                                                  long globalVersion, List<String> hitRegionKeys,
                                                  TreeMap<String, Long> hitVersions,
                                                  List<Point> pointsSnapshot) {
        String reviewId = "fr_" + UUID.randomUUID();
        long now = nowMillis();
        List<ConsumedQuota> consumed = new ArrayList<>(matched.size());
        int seq = 0;
        for (String regionKey : hitRegionKeys) {
            PermitItemPo item = matched.get(regionKey);
            // 条件扣减兜底并发：行锁已串行化，仍要求 remaining>0，0 行说明余额被并发改变
            int updated = permitRepo.decrementRemaining(pkg.permitKey(), regionKey);
            if (updated == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "PERMIT_QUOTA_RACE",
                        "豁免额度在核销时发生并发变化，请重试: " + regionKey);
            }
            int before = item.remaining();
            int after = before - 1;
            consumed.add(new ConsumedQuota(regionKey, item.regionVersion(), before, after));
            permitRepo.insertConsumption(new PermitConsumptionPo("pc_" + UUID.randomUUID(),
                    reviewId, request.flightKey(), pkg.permitKey(), regionKey,
                    item.regionVersion(), before, after, seq++, now));
        }
        return finalizeClear(request, reviewId, pkg, route, globalVersion, hitRegionKeys,
                hitVersions, pointsSnapshot, consumed);
    }

    /** 写 CLEAR 审核记录（含冻结快照）；去重记录由外层幂等包装统一写入。 */
    private MutationResponse finalizeClear(FlightReviewRequest request, String reviewId,
                                           PermitPackagePo pkg, RoutePo route, long globalVersion,
                                           List<String> hitRegionKeys,
                                           TreeMap<String, Long> hitVersions,
                                           List<Point> pointsSnapshot,
                                           List<ConsumedQuota> consumed) {
        List<SnapshotHit> snapshotHits = new ArrayList<>(hitRegionKeys.size());
        for (String regionKey : hitRegionKeys) {
            snapshotHits.add(new SnapshotHit(regionKey, hitVersions.get(regionKey), true));
        }
        FlightReviewSnapshot snapshot = new FlightReviewSnapshot(globalVersion, route.routeId(),
                route.version(), toPointDtos(pointsSnapshot),
                pkg == null ? null : pkg.permitKey(),
                pkg == null ? null : pkg.permitVersion(), snapshotHits, consumed);
        FlightReviewResultDto dto = new FlightReviewResultDto(reviewId, request.flightKey(),
                request.routeId(), route.version(), globalVersion,
                pkg == null ? null : pkg.permitKey(),
                pkg == null ? null : pkg.permitVersion(), request.reviewAt(),
                ReviewConclusion.CLEAR.name(), hitRegionKeys, List.of(), consumed, snapshot);

        flightReviewRepo.insertReview(new FlightReviewPo(reviewId, request.flightKey(),
                request.routeId(), route.version(), globalVersion, request.reviewAt(),
                ReviewConclusion.CLEAR.name(), hitRegionKeys,
                pkg == null ? null : pkg.permitKey(), writeJson(snapshot),
                request.requestId(), canonicalHash(request), nowMillis()));
        return new MutationResponse(request.requestId(), false, dto);
    }

    /** 同 flightKey 已有成功审核：同参重放（不重复扣额），routeId/reviewAt 不同 409。 */
    private MutationResponse resolveExistingFlightReview(FlightReviewPo existing,
                                                         FlightReviewRequest request) {
        if (!existing.routeId().equals(request.routeId())
                || existing.reviewAt() != request.reviewAt()) {
            throw new ApiException(HttpStatus.CONFLICT, "FLIGHT_PARAM_MISMATCH",
                    "flightKey 已形成审核且参数不同: " + request.flightKey());
        }
        // 去重记录由外层包装写入（即便本次使用了新 requestId），重放绝不再次扣额
        return new MutationResponse(request.requestId(), true, toDtoFromPo(existing));
    }

    /** 构造 BLOCKED 结果：缺口与快照以候选中 permitKey 最小的豁免包逐项评估。 */
    private FlightReviewResultDto buildBlockedResult(FlightReviewRequest request, RoutePo route,
                                                     long globalVersion, List<String> hitRegionKeys,
                                                     TreeMap<String, Long> hitVersions,
                                                     List<Point> pointsSnapshot, long reviewAt,
                                                     PermitPackagePo reported,
                                                     List<PermitItemPo> reportedItems) {
        Map<String, PermitItemPo> byRegion = new LinkedHashMap<>();
        for (PermitItemPo item : reportedItems) {
            byRegion.put(item.regionKey(), item);
        }
        List<RegionDeficit> deficits = new ArrayList<>(hitRegionKeys.size());
        List<SnapshotHit> snapshotHits = new ArrayList<>(hitRegionKeys.size());
        for (Map.Entry<String, Long> hit : hitVersions.entrySet()) {
            PermitItemPo item = byRegion.get(hit.getKey());
            boolean versionMatch = item != null && item.regionVersion() == hit.getValue();
            boolean inWindow = versionMatch
                    && reviewAt >= item.validFrom() && reviewAt < item.validTo();
            boolean usable = inWindow && item.remaining() > 0;
            String reason;
            if (!versionMatch) {
                reason = DeficitReason.MISSING.name();
            } else if (!inWindow) {
                reason = DeficitReason.EXPIRED.name();
            } else {
                reason = DeficitReason.EXHAUSTED.name();
            }
            deficits.add(new RegionDeficit(hit.getKey(), hit.getValue(),
                    reported == null ? null : reported.permitKey(), reason));
            snapshotHits.add(new SnapshotHit(hit.getKey(), hit.getValue(), usable));
        }
        FlightReviewSnapshot snapshot = new FlightReviewSnapshot(globalVersion, route.routeId(),
                route.version(), toPointDtos(pointsSnapshot),
                reported == null ? null : reported.permitKey(),
                reported == null ? null : reported.permitVersion(), snapshotHits, List.of());
        return new FlightReviewResultDto(null, request.flightKey(), request.routeId(),
                route.version(), globalVersion,
                reported == null ? null : reported.permitKey(),
                reported == null ? null : reported.permitVersion(), reviewAt,
                ReviewConclusion.BLOCKED.name(), hitRegionKeys, deficits, List.of(), snapshot);
    }

    // ============================ 只读查询 ============================

    /** 只读查询豁免包余额，不存在 404。 */
    public PermitView getPermit(String permitKey) {
        PermitPackagePo pkg = permitRepo.findPackage(permitKey);
        if (pkg == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PERMIT_NOT_FOUND",
                    "豁免包不存在: " + permitKey);
        }
        return toView(pkg, permitRepo.findItems(permitKey));
    }

    /** 只读查询某豁免包的核销历史，豁免包不存在 404。 */
    public List<ConsumptionDto> getConsumptions(String permitKey) {
        if (permitRepo.findPackage(permitKey) == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PERMIT_NOT_FOUND",
                    "豁免包不存在: " + permitKey);
        }
        List<ConsumptionDto> result = new ArrayList<>();
        for (PermitConsumptionPo po : permitRepo.findConsumptionsByPermit(permitKey)) {
            result.add(new ConsumptionDto(po.consumptionId(), po.reviewId(), po.flightKey(),
                    po.permitKey(), po.regionKey(), po.regionVersion(), po.balanceBefore(),
                    po.balanceAfter(), po.createdAt()));
        }
        return result;
    }

    /** 只读查询航班审核历史（保留 CLEAR 原结论与冻结快照），不存在 404。 */
    public FlightReviewResultDto getFlightReview(String reviewId) {
        FlightReviewPo po = flightReviewRepo.findReview(reviewId);
        if (po == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "FLIGHT_REVIEW_NOT_FOUND",
                    "航班审核记录不存在: " + reviewId);
        }
        return toDtoFromPo(po);
    }

    // ============================ 幂等与并发裁决 ============================

    /**
     * 在事务内执行幂等写操作：同键同参返回首次成功结果（replayed=true）；
     * 同键异参/异种操作 409；业务异常与 BLOCKED 回滚、不占用 requestId。
     */
    private MutationResponse withIdempotency(String requestId, String kind, String paramHash,
                                             Supplier<MutationResponse> action) {
        try {
            return txTemplate.execute(status -> doIdempotent(requestId, kind, paramHash, action));
        } catch (BlockedReviewException blocked) {
            throw blocked;
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
     * 并发落败后的裁决：去重表中存在同键记录则按重放/异参冲突处理；不存在（该 requestId
     * 尚未成功、这是一次真实业务冲突）则抛原错误，不占用 requestId。同 flightKey 的并发
     * 审核因协调锁串行，落败事务在持锁后即可读到赢家已提交记录并走重放/异参路径。
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
        // 业务异常与 BLOCKED 触发事务回滚，去重键不会被占用
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
            MutationResponse original = objectMapper.readValue(po.responseJson(),
                    MutationResponse.class);
            return new MutationResponse(original.requestId(), true, original.data());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("无法解析幂等重放结果: " + po.requestId(), ex);
        }
    }

    // ============================ 工具方法 ============================

    private static PermitView toView(PermitPackagePo pkg, List<PermitItemPo> items) {
        List<PermitBalanceItem> viewItems = new ArrayList<>(items.size());
        for (PermitItemPo item : items) {
            viewItems.add(new PermitBalanceItem(item.regionKey(), item.regionVersion(),
                    item.validFrom(), item.validTo(), item.quota(), item.remaining()));
        }
        return new PermitView(pkg.permitKey(), pkg.routeVersion(), pkg.permitVersion(),
                pkg.status(), viewItems);
    }

    private static List<RoutePointDto> toPointDtos(List<Point> points) {
        List<RoutePointDto> dtos = new ArrayList<>(points.size());
        for (Point p : points) {
            dtos.add(new RoutePointDto(p.x(), p.y()));
        }
        return dtos;
    }

    /** 从不可变审核记录重建对外结果：快照从冻结 JSON 还原，核销余额从流水重建。 */
    private FlightReviewResultDto toDtoFromPo(FlightReviewPo po) {
        FlightReviewSnapshot snapshot;
        try {
            snapshot = objectMapper.readValue(po.snapshotJson(), FlightReviewSnapshot.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("无法解析审核快照: " + po.reviewId(), ex);
        }
        List<ConsumedQuota> consumed = new ArrayList<>();
        for (PermitConsumptionPo c : permitRepo.findConsumptionsByReview(po.reviewId())) {
            consumed.add(new ConsumedQuota(c.regionKey(), c.regionVersion(),
                    c.balanceBefore(), c.balanceAfter()));
        }
        return new FlightReviewResultDto(po.reviewId(), po.flightKey(), po.routeId(),
                po.routeVersion(), po.airspaceVersion(), po.permitKey(),
                snapshot.permitVersion(), po.reviewAt(), po.conclusion(), po.hitRegionKeys(),
                List.of(), consumed, snapshot);
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
     * 计算请求参数的规范化哈希（SHA-256）。对象字段按键名字典序递归排序，
     * 列表保持顺序，使相同语义参数产生相同哈希。
     */
    private String canonicalHash(Object request) {
        try {
            JsonNode sorted = canonicalize(objectMapper.valueToTree(request));
            String canonical = objectMapper.writeValueAsString(sorted);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
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

    /**
     * BLOCKED 内部控制异常：携带已计算好的 BLOCKED 结果触发事务回滚，
     * 由 {@link #reviewFlight} 捕获后返回，不作为错误响应。
     */
    private static final class BlockedReviewException extends RuntimeException {
        private final transient FlightReviewResultDto result;

        private BlockedReviewException(FlightReviewResultDto result) {
            this.result = result;
        }

        private FlightReviewResultDto result() {
            return result;
        }
    }
}
