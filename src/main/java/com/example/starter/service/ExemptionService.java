package com.example.starter.service;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.FlightReviewRequest;
import com.example.starter.api.dto.FlightReviewResultDto;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.PermitItemRequest;
import com.example.starter.api.dto.PermitItemResult;
import com.example.starter.api.dto.PermitIssueRequest;
import com.example.starter.api.dto.PermitRedeemDto;
import com.example.starter.api.dto.PermitResult;
import com.example.starter.api.dto.PermitRevokeRequest;
import com.example.starter.api.dto.RedeemResultDto;
import com.example.starter.api.dto.RegionDefectDto;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.domain.DefectReason;
import com.example.starter.domain.Geometry;
import com.example.starter.domain.PermitStatus;
import com.example.starter.domain.Point;
import com.example.starter.domain.ReviewConclusion;
import com.example.starter.repo.AirspaceRepository;
import com.example.starter.repo.DedupPo;
import com.example.starter.repo.FlightReviewPo;
import com.example.starter.repo.FlightReviewRepository;
import com.example.starter.repo.PermitItemPo;
import com.example.starter.repo.PermitPo;
import com.example.starter.repo.PermitRedeemPo;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 多区域豁免包签发/撤销/核销与飞行审核业务服务。
 *
 * <p><b>提交时一致视图：</b>飞行审核事务依次锁定全局协调锁行（与区域创建/撤销互斥）、
 * 航线行（与航线替换互斥），再按 permitId 顺序锁定候选豁免包行（与同包核销/撤销互斥）。
 * 加锁顺序固定为 coord_lock → route → permit，不存在反向边，避免死锁；
 * 空域版本、航线版本点列、禁飞区集合、豁免包余额全部来自同一已提交状态。</p>
 *
 * <p><b>原子核销：</b>命中的每个区域在持有豁免包行写锁期间完成“余额校验→条件扣 1→
 * 写核销流水”；任一区域无法核销则结论 BLOCKED，整事务回滚，任何额度都不扣。</p>
 *
 * <p><b>幂等：</b>签发/撤销/审核均复用 request_dedup：同 requestId 同参重放原结果，
 * 异参/异种操作 409，业务异常回滚不占键。同 flightKey 的 CLEAR 最终审核由
 * flight_review.final_key 唯一约束保证只形成一次；BLOCKED 为正常落库的评估快照，
 * 不扣额度、不锁定 flightKey，修正条件后可以新 requestId 同键再审核。</p>
 */
@Service
public class ExemptionService {

    public static final String KIND_PERMIT_ISSUE = "PERMIT_ISSUE";
    public static final String KIND_PERMIT_REVOKE = "PERMIT_REVOKE";
    public static final String KIND_FLIGHT_REVIEW = "FLIGHT_REVIEW";

    private final AirspaceRepository airspaceRepo;
    private final RouteRepository routeRepo;
    private final PermitRepository permitRepo;
    private final FlightReviewRepository flightReviewRepo;
    private final ReviewRepository reviewRepo;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate txTemplate;

    public ExemptionService(AirspaceRepository airspaceRepo,
                            RouteRepository routeRepo,
                            PermitRepository permitRepo,
                            FlightReviewRepository flightReviewRepo,
                            ReviewRepository reviewRepo,
                            ObjectMapper objectMapper,
                            Clock clock,
                            PlatformTransactionManager transactionManager) {
        this.airspaceRepo = airspaceRepo;
        this.routeRepo = routeRepo;
        this.permitRepo = permitRepo;
        this.flightReviewRepo = flightReviewRepo;
        this.reviewRepo = reviewRepo;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    // ============================ 签发 / 撤销 ============================

    /** 签发豁免包；permitKey 唯一，区域项 1~10 个且 regionKey 不重复。 */
    public MutationResponse issuePermit(PermitIssueRequest request) {
        return withIdempotency(request.requestId(), KIND_PERMIT_ISSUE, canonicalHash(request),
                () -> doIssue(request), null);
    }

    private MutationResponse doIssue(PermitIssueRequest request) {
        // 参数级校验：有效区间必须为非空区间，regionKey 包内不可重复
        TreeSet<String> seen = new TreeSet<>();
        for (PermitItemRequest item : request.items()) {
            if (item.validFrom() >= item.validTo()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_VALIDITY_WINDOW",
                        "区域项 " + item.regionKey() + " 的 UTC 有效区间必须满足 validFrom < validTo");
            }
            if (!seen.add(item.regionKey())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "DUPLICATE_REGION_ITEM",
                        "豁免包内区域项不可重复: " + item.regionKey());
            }
        }
        // 锁航线行：与航线替换互斥，校验绑定的精确版本当前存在
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
        // permitKey 全局唯一：签发后不可再用（不支持修改）
        if (permitRepo.findPermit(request.permitKey()) != null) {
            throw new ApiException(HttpStatus.CONFLICT, "PERMIT_ALREADY_EXISTS",
                    "豁免包已存在且不可修改: " + request.permitKey());
        }
        long now = nowMillis();
        PermitPo permit = new PermitPo(request.permitKey(), request.routeId(),
                request.routeVersion(), PermitStatus.ACTIVE.name(), 1,
                request.requestId(), now, null);
        permitRepo.insertPermit(permit);
        List<PermitItemPo> items = new ArrayList<>(request.items().size());
        for (PermitItemRequest item : request.items()) {
            items.add(new PermitItemPo(request.permitKey(), item.regionKey(),
                    item.regionVersion(), item.validFrom(), item.validTo(),
                    item.quota(), item.quota()));
        }
        permitRepo.insertItems(items);
        return new MutationResponse(request.requestId(), false, toPermitResult(permit, items));
    }

    /** 撤销豁免包未使用余额；历史核销保留不回写；同 requestId 同参重放。 */
    public MutationResponse revokePermit(PermitRevokeRequest request) {
        return withIdempotency(request.requestId(), KIND_PERMIT_REVOKE, canonicalHash(request),
                () -> doRevoke(request), null);
    }

    private MutationResponse doRevoke(PermitRevokeRequest request) {
        // 锁豁免包行：与核销事务互斥，撤销与已提交核销不会互相覆盖
        PermitPo permit = permitRepo.findPermitForUpdate(request.permitKey());
        if (permit == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PERMIT_NOT_FOUND",
                    "豁免包不存在: " + request.permitKey());
        }
        if (PermitStatus.REVOKED.name().equals(permit.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "PERMIT_ALREADY_REVOKED",
                    "豁免包已撤销: " + request.permitKey());
        }
        int updated = permitRepo.markRevoked(request.permitKey(), nowMillis());
        if (updated == 0) {
            // 兜底：持锁状态下被其他路径撤销不应发生，发生则按并发冲突处理
            throw new ApiException(HttpStatus.CONFLICT, "RESOURCE_CONFLICT",
                    "豁免包状态已变化，请刷新后重试: " + request.permitKey());
        }
        PermitPo revoked = new PermitPo(permit.permitId(), permit.routeId(), permit.routeVersion(),
                PermitStatus.REVOKED.name(), 2, permit.requestId(), permit.issuedAt(), nowMillis());
        return new MutationResponse(request.requestId(), false,
                toPermitResult(revoked, permitRepo.findItems(request.permitKey())));
    }

    // ============================ 飞行审核与核销 ============================

    /**
     * 带豁免核销的飞行审核。
     * 先在提交时一致视图内计算全部命中区域；存在一个对每个命中区域都版本匹配、
     * 未撤销、在 reviewAt 有效且余额大于 0 的同一豁免包时 CLEAR 并同事务每项扣 1；
     * 否则 BLOCKED 且不扣任何额度。
     */
    public MutationResponse flightReview(FlightReviewRequest request) {
        // 并发落败且 requestId 尚无赢家去重记录时（例如同 flightKey 的最终 CLEAR 由别的
        // requestId 抢先提交），在新事务中按 flightKey 裁决：同参重放、异参 409
        Supplier<MutationResponse> raceResolver = () -> {
            FlightReviewPo finalPo = flightReviewRepo.findFinalByFlightKey(request.flightKey());
            if (finalPo == null) {
                return null;
            }
            if (!isSameFlightParams(finalPo, request)) {
                throw new ApiException(HttpStatus.CONFLICT, "FLIGHT_ALREADY_REVIEWED",
                        "flightKey 已形成参数不同的最终审核: " + request.flightKey());
            }
            return replayFlightResult(request.requestId(), finalPo);
        };
        return withIdempotency(request.requestId(), KIND_FLIGHT_REVIEW, canonicalHash(request),
                () -> doFlightReview(request), raceResolver);
    }

    private MutationResponse doFlightReview(FlightReviewRequest request) {
        // 1. 锁空域协调行：与禁飞区创建/撤销互斥
        long globalVersion = airspaceRepo.getGlobalVersionForUpdate();
        // 2. 锁航线行：与航线替换互斥，版本号与点列一致
        RoutePo route = routeRepo.findRouteForUpdate(request.routeId());
        if (route == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND",
                    "航线不存在: " + request.routeId());
        }
        // 3. 同 flightKey 已有最终 CLEAR：同参重放原快照（不重复扣额），异参 409
        FlightReviewPo finalized = flightReviewRepo.findFinalByFlightKey(request.flightKey());
        if (finalized != null) {
            if (isSameFlightParams(finalized, request)) {
                return replayFlightResult(request.requestId(), finalized);
            }
            throw new ApiException(HttpStatus.CONFLICT, "FLIGHT_ALREADY_REVIEWED",
                    "flightKey 已形成参数不同的最终审核: " + request.flightKey());
        }
        // 4. 持锁状态下读取全部有效禁飞区并计算几何命中（字典序去重）
        List<ZonePo> activeZones = airspaceRepo.findActiveZones();
        Map<String, ZonePo> zoneById = new HashMap<>();
        TreeSet<String> hitKeys = new TreeSet<>();
        for (ZonePo zone : activeZones) {
            zoneById.put(zone.zoneId(), zone);
            if (Geometry.polylineHitsRectangle(route.points(),
                    zone.xMin(), zone.yMin(), zone.xMax(), zone.yMax())) {
                hitKeys.add(zone.zoneId());
            }
        }
        List<String> hits = List.copyOf(hitKeys);

        String reviewId = "fr_" + UUID.randomUUID();
        long now = nowMillis();
        FlightReviewResultDto dto;

        if (hits.isEmpty()) {
            // 未命中任何区域：天然 CLEAR，不消费任何豁免额度
            dto = buildResult(reviewId, request, route, globalVersion, null, null,
                    hits, List.of(), List.of());
            insertSnapshot(reviewId, request, route, globalVersion, null, null, dto, true, now);
            return new MutationResponse(request.requestId(), false, dto);
        }

        // 5. 锁定该航线精确版本下全部豁免包（含已撤销，查询按 permitId 排序，按序加锁防死锁）。
        //    持锁读取区域项与余额：ACTIVE 包可核销，REVOKED 包产生 REVOKED 缺陷并参与串行化。
        List<PermitPo> candidates = permitRepo.findPermitsForRoute(
                request.routeId(), route.version());
        List<LockedPermit> locked = new ArrayList<>(candidates.size());
        for (PermitPo candidate : candidates) {
            PermitPo held = permitRepo.findPermitForUpdate(candidate.permitId());
            if (held != null) {
                locked.add(new LockedPermit(held, indexItems(permitRepo.findItems(held.permitId()))));
            }
        }

        // 6. 寻找首个覆盖全部命中区域的豁免包；同时统计各包可覆盖数用于缺陷展示
        LockedPermit cover = null;
        LockedPermit bestForDefects = null;
        int bestOkCount = -1;
        Map<String, RegionDefectDto> bestDefects = null;
        for (LockedPermit lp : locked) {
            int okCount = 0;
            Map<String, RegionDefectDto> defects = new LinkedHashMap<>();
            boolean allOk = true;
            for (String regionKey : hits) {
                RegionDefectDto defect = evaluateRegion(lp, regionKey,
                        zoneById.get(regionKey), request.reviewAt());
                if (defect == null) {
                    okCount++;
                } else {
                    allOk = false;
                    defects.put(regionKey, defect);
                }
            }
            // 可覆盖命中数最多者作为 BLOCKED 缺陷的展示对象，并列取 permitId 较小者
            if (okCount > bestOkCount || (okCount == bestOkCount && bestForDefects != null
                    && lp.permit.permitId().compareTo(bestForDefects.permit.permitId()) < 0)) {
                bestOkCount = okCount;
                bestForDefects = lp;
                bestDefects = defects;
            }
            if (allOk) {
                cover = lp;
                break;
            }
        }

        if (cover != null) {
            // 7. CLEAR：一个事务内对每个命中区域扣 1 并写核销流水（冻结核销前后余额）
            List<RedeemResultDto> redeems = new ArrayList<>(hits.size());
            for (String regionKey : hits) {
                PermitItemPo item = cover.items.get(regionKey);
                int before = item.remaining();
                int decremented = permitRepo.decrementRemaining(cover.permit.permitId(), regionKey);
                if (decremented == 0) {
                    // 兜底：持锁且已校验余额为正，0 行说明状态被破坏，整体回滚不产生结论
                    throw new ApiException(HttpStatus.CONFLICT, "RESOURCE_CONFLICT",
                            "豁免余额状态已变化，请刷新后重试: " + regionKey);
                }
                PermitRedeemPo redeem = new PermitRedeemPo("rd_" + UUID.randomUUID(),
                        cover.permit.permitId(), regionKey, request.flightKey(),
                        before, before - 1, request.reviewAt(), now);
                permitRepo.insertRedeem(redeem);
                // 内存视图同步，避免同事务内重复读到旧值（本方法内不会再读，保持一致即可）
                cover.items.put(regionKey, new PermitItemPo(item.permitId(), item.regionKey(),
                        item.regionVersion(), item.validFrom(), item.validTo(),
                        item.quota(), before - 1));
                redeems.add(new RedeemResultDto(regionKey, before, before - 1));
            }
            dto = buildResult(reviewId, request, route, globalVersion,
                    cover.permit.permitId(), cover.permit.version(),
                    hits, List.of(), redeems);
            insertSnapshot(reviewId, request, route, globalVersion,
                    cover.permit.permitId(), cover.permit.version(), dto, true, now);
            return new MutationResponse(request.requestId(), false, dto);
        }

        // 8. BLOCKED：任何额度都不扣（事务内未做任何扣减），落库失败尝试快照供历史与复用
        List<RegionDefectDto> defectList;
        if (bestForDefects == null) {
            // 不存在任何候选豁免包：每个命中区域均为缺失项
            defectList = new ArrayList<>(hits.size());
            for (String regionKey : hits) {
                defectList.add(new RegionDefectDto(regionKey, DefectReason.MISSING.name(),
                        "航线版本 " + route.version() + " 没有有效豁免包覆盖该区域"));
            }
        } else {
            defectList = new ArrayList<>(hits.size());
            for (String regionKey : hits) {
                RegionDefectDto defect = bestDefects.get(regionKey);
                if (defect == null) {
                    // 该区域在此包中可核销，但没有任何单一豁免包覆盖全部命中区域
                    defect = new RegionDefectDto(regionKey, DefectReason.MISSING.name(),
                            "区域在豁免包 " + bestForDefects.permit.permitId()
                                    + " 中可核销，但不存在覆盖全部命中区域的同一有效豁免包");
                }
                defectList.add(defect);
            }
        }
        dto = buildResult(reviewId, request, route, globalVersion, null, null,
                hits, defectList, List.of());
        insertSnapshot(reviewId, request, route, globalVersion, null, null, dto, false, now);
        return new MutationResponse(request.requestId(), false, dto);
    }

    // ============================ 只读查询 ============================

    /** 只读查询豁免包及其区域项当前余额。 */
    public PermitResult getPermit(String permitKey) {
        PermitPo permit = permitRepo.findPermit(permitKey);
        if (permit == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PERMIT_NOT_FOUND",
                    "豁免包不存在: " + permitKey);
        }
        return toPermitResult(permit, permitRepo.findItems(permitKey));
    }

    /** 只读查询豁免包核销流水（时间正序）。 */
    public List<PermitRedeemDto> getRedeems(String permitKey) {
        if (permitRepo.findPermit(permitKey) == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PERMIT_NOT_FOUND",
                    "豁免包不存在: " + permitKey);
        }
        List<PermitRedeemDto> result = new ArrayList<>();
        for (PermitRedeemPo po : permitRepo.findRedeemsByPermit(permitKey)) {
            result.add(new PermitRedeemDto(po.redeemId(), po.permitId(), po.regionKey(),
                    po.flightKey(), po.balanceBefore(), po.balanceAfter(),
                    po.reviewAt(), po.createdAt()));
        }
        return result;
    }

    /** 只读查询单条飞行审核快照。 */
    public FlightReviewResultDto getFlightReview(String reviewId) {
        FlightReviewPo po = flightReviewRepo.findByReviewId(reviewId);
        if (po == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "FLIGHT_REVIEW_NOT_FOUND",
                    "飞行审核记录不存在: " + reviewId);
        }
        return deserializeFlightResult(po.responseJson());
    }

    /** 只读查询某 flightKey 的全部审核快照（含 BLOCKED 尝试，时间正序）。 */
    public List<FlightReviewResultDto> getFlightHistory(String flightKey) {
        List<FlightReviewResultDto> result = new ArrayList<>();
        for (FlightReviewPo po : flightReviewRepo.findHistoryByFlightKey(flightKey)) {
            result.add(deserializeFlightResult(po.responseJson()));
        }
        return result;
    }

    // ============================ 幂等与事务 ============================

    /**
     * 在事务内执行幂等写操作：同键同参返回首次成功结果（replayed=true）；
     * 同键异参/异种操作 409；业务异常回滚不占 requestId。
     * 并发落败（唯一约束冲突或持锁后读到赢家状态）时在新事务中裁决：
     * 先查 requestId 去重记录；无赢家去重记录时调用 raceResolver 处理业务唯一键
     * （如同 flightKey 最终审核、同 permitKey），仍无法裁决则抛出原始冲突，不占键。
     */
    private MutationResponse withIdempotency(String requestId, String kind, String paramHash,
                                             Supplier<MutationResponse> action,
                                             Supplier<MutationResponse> raceResolver) {
        try {
            return txTemplate.execute(status -> doIdempotent(requestId, kind, paramHash, action));
        } catch (DuplicateKeyException dup) {
            return resolveAfterRace(requestId, kind, paramHash,
                    new ApiException(HttpStatus.CONFLICT, "RESOURCE_CONFLICT",
                            "并发资源冲突，请稍后使用相同 requestId 与参数重试"),
                    raceResolver);
        } catch (ApiException api) {
            if (api.status() == HttpStatus.CONFLICT) {
                return resolveAfterRace(requestId, kind, paramHash, api, raceResolver);
            }
            throw api;
        }
    }

    private MutationResponse doIdempotent(String requestId, String kind, String paramHash,
                                          Supplier<MutationResponse> action) {
        DedupPo existing = reviewRepo.findDedup(requestId);
        if (existing != null) {
            ensureSameRequest(existing, kind, paramHash);
            return deserializeReplay(existing);
        }
        MutationResponse result = action.get();
        reviewRepo.insertDedup(new DedupPo(requestId, kind, paramHash,
                writeJson(result), nowMillis()));
        return result;
    }

    /**
     * 并发落败裁决：去重表存在赢家记录则按重放/异参冲突处理；不存在时交给业务
     * raceResolver（按业务唯一键查找赢家结果）；仍无结果则抛出原始业务冲突，不占键。
     */
    private MutationResponse resolveAfterRace(String requestId, String kind, String paramHash,
                                              ApiException original,
                                              Supplier<MutationResponse> raceResolver) {
        DedupPo winnerDedup = txTemplate.execute(status -> reviewRepo.findDedup(requestId));
        if (winnerDedup != null) {
            ensureSameRequest(winnerDedup, kind, paramHash);
            return deserializeReplay(winnerDedup);
        }
        if (raceResolver != null) {
            MutationResponse resolved = txTemplate.execute(status -> raceResolver.get());
            if (resolved != null) {
                return resolved;
            }
        }
        throw original;
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

    // ============================ 内部模型与工具 ============================

    /** 已持有行写锁的豁免包及其区域项（按 regionKey 索引）。 */
    private static final class LockedPermit {
        private final PermitPo permit;
        private final Map<String, PermitItemPo> items;

        private LockedPermit(PermitPo permit, Map<String, PermitItemPo> items) {
            this.permit = permit;
            this.items = items;
        }
    }

    private static Map<String, PermitItemPo> indexItems(List<PermitItemPo> items) {
        Map<String, PermitItemPo> map = new HashMap<>();
        for (PermitItemPo item : items) {
            map.put(item.regionKey(), item);
        }
        return map;
    }

    /**
     * 评估单个命中区域在该豁免包下是否可核销。
     *
     * @return null 表示可核销（版本匹配、未撤销、有效区间内、余额大于 0）；否则为缺陷项
     */
    private static RegionDefectDto evaluateRegion(LockedPermit lp, String regionKey,
                                                  ZonePo zone, long reviewAt) {
        String permitId = lp.permit.permitId();
        if (PermitStatus.REVOKED.name().equals(lp.permit.status())) {
            return new RegionDefectDto(regionKey, DefectReason.REVOKED.name(),
                    "豁免包已撤销: " + permitId);
        }
        PermitItemPo item = lp.items.get(regionKey);
        if (item == null) {
            return new RegionDefectDto(regionKey, DefectReason.MISSING.name(),
                    "豁免包 " + permitId + " 缺少该区域项");
        }
        long currentRegionVersion = zone == null ? -1L : zone.createdVersion();
        if (zone == null || item.regionVersion() != currentRegionVersion) {
            return new RegionDefectDto(regionKey, DefectReason.VERSION_MISMATCH.name(),
                    "区域版本不匹配：permit=" + item.regionVersion()
                            + ", current=" + currentRegionVersion);
        }
        if (reviewAt < item.validFrom() || reviewAt > item.validTo()) {
            return new RegionDefectDto(regionKey, DefectReason.EXPIRED.name(),
                    "reviewAt=" + reviewAt + " 不在 UTC 有效区间 ["
                            + item.validFrom() + ", " + item.validTo() + "] 内");
        }
        if (item.remaining() <= 0) {
            return new RegionDefectDto(regionKey, DefectReason.EXHAUSTED.name(),
                    "豁免额度已耗尽: " + permitId);
        }
        return null;
    }

    private boolean isSameFlightParams(FlightReviewPo po, FlightReviewRequest request) {
        return po.flightKey().equals(request.flightKey())
                && po.routeId().equals(request.routeId())
                && po.reviewAt() == request.reviewAt();
    }

    private MutationResponse replayFlightResult(String requestId, FlightReviewPo po) {
        return new MutationResponse(requestId, true, deserializeFlightResult(po.responseJson()));
    }

    private FlightReviewResultDto deserializeFlightResult(String json) {
        try {
            return objectMapper.readValue(json, FlightReviewResultDto.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("无法解析飞行审核快照", ex);
        }
    }

    private void insertSnapshot(String reviewId, FlightReviewRequest request, RoutePo route,
                                long globalVersion, String permitId, Integer permitVersion,
                                FlightReviewResultDto dto, boolean finalized, long now) {
        FlightReviewPo po = new FlightReviewPo(reviewId, request.flightKey(), finalized,
                request.requestId(), request.routeId(), route.version(), globalVersion,
                permitId, permitVersion, request.reviewAt(),
                finalized ? ReviewConclusion.CLEAR.name() : ReviewConclusion.BLOCKED.name(),
                writeJson(dto), now);
        flightReviewRepo.insertFlightReview(po);
    }

    private static FlightReviewResultDto buildResult(String reviewId, FlightReviewRequest request,
                                                     RoutePo route, long globalVersion,
                                                     String permitId, Integer permitVersion,
                                                     List<String> hits,
                                                     List<RegionDefectDto> defects,
                                                     List<RedeemResultDto> redeems) {
        List<RoutePointDto> snapshot = new ArrayList<>(route.points().size());
        for (Point p : route.points()) {
            snapshot.add(new RoutePointDto(p.x(), p.y()));
        }
        String conclusion = defects.isEmpty()
                ? ReviewConclusion.CLEAR.name()
                : ReviewConclusion.BLOCKED.name();
        return new FlightReviewResultDto(reviewId, request.flightKey(), request.routeId(),
                route.version(), globalVersion, permitId, permitVersion, request.reviewAt(),
                conclusion, List.copyOf(hits), List.copyOf(defects), List.copyOf(redeems),
                List.copyOf(snapshot));
    }

    private static PermitResult toPermitResult(PermitPo permit, List<PermitItemPo> items) {
        List<PermitItemResult> results = items.stream()
                .sorted(Comparator.comparing(PermitItemPo::regionKey))
                .map(item -> new PermitItemResult(item.regionKey(), item.regionVersion(),
                        item.validFrom(), item.validTo(), item.quota(), item.remaining()))
                .toList();
        return new PermitResult(permit.permitId(), permit.routeId(), permit.routeVersion(),
                permit.status(), permit.version(), results);
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
     * 计算请求参数的规范化哈希（SHA-256）：对象字段按键名字典序递归排序，
     * 列表保持顺序，使相同语义参数产生相同哈希。
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
