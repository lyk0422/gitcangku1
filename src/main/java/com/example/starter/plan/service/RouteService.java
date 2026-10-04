package com.example.starter.plan.service;

import com.example.starter.plan.model.PublishedSlot;
import com.example.starter.plan.model.RoutePair;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.repo.RouteRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.DeclareRouteRequest;
import com.example.starter.plan.web.dto.RouteMatrixResponse;
import com.example.starter.plan.web.dto.RoutePairRequest;
import com.example.starter.plan.web.dto.RouteResponse;
import com.example.starter.plan.web.dto.UpdateRouteMatrixRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 进路注册与冲突矩阵维护。矩阵版本从 1 开始，每次成功更新加一；
 * 矩阵更新与发布/改签共用全局发布锁，竞争时不会留下冲突占用。
 *
 * <p>幂等约定：写操作按 (操作类型, requestId) 幂等，身份含操作、操作者、资源与全部参数
 * （含期望版本），以规范化 JSON 的 SHA-256 结构化比较，避免分隔符碰撞；
 * 同键同参重放首次完整响应，同键异参 409；失败不占键，业务与去重记录同事务提交。
 */
@Service
public class RouteService {

    private static final String OP_DECLARE_ROUTE = "DECLARE_ROUTE";
    private static final String OP_MATRIX_UPDATE = "MATRIX_UPDATE";
    private static final String MATRIX_RESOURCE = "route-matrix";

    private final RouteRepository routeRepo;
    private final PlanRepository planRepo;
    private final IdempotencyRepository idemRepo;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public RouteService(RouteRepository routeRepo, PlanRepository planRepo,
                        IdempotencyRepository idemRepo,
                        ObjectMapper objectMapper, PlatformTransactionManager txManager) {
        this.routeRepo = routeRepo;
        this.planRepo = planRepo;
        this.idemRepo = idemRepo;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 声明进路（最多 20 条）。已存在时幂等返回当前状态；超出上限返回 409。
     */
    public RouteResponse declareRoute(String routeId, DeclareRouteRequest req) {
        String hash = hashDeclare(routeId, req);
        Optional<RouteResponse> replay = replayIfPresent(OP_DECLARE_ROUTE, req.requestId(), hash,
                RouteResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                // 与发布/矩阵更新同一把全局锁：串行化进路集合变更，保证上限检查与
                // 并发声明按提交顺序生效，同键竞争由幂等记录裁决
                planRepo.acquirePublishLock();
                long now = System.currentTimeMillis();
                Optional<Long> existing = routeRepo.findRouteCreatedAt(routeId);
                RouteResponse response;
                if (existing.isPresent()) {
                    response = new RouteResponse(routeId, Instant.ofEpochMilli(existing.get()));
                } else {
                    if (routeRepo.countRoutes() >= RouteRepository.MAX_ROUTES) {
                        throw conflict("ROUTE_LIMIT_EXCEEDED",
                                "进路数量已达上限 " + RouteRepository.MAX_ROUTES);
                    }
                    routeRepo.insertRoute(routeId, now);
                    response = new RouteResponse(routeId, Instant.ofEpochMilli(now));
                }
                idemRepo.insert(OP_DECLARE_ROUTE, req.requestId(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_DECLARE_ROUTE, req.requestId(), hash, RouteResponse.class)
                    .orElseThrow(() -> conflict("ROUTE_CONCURRENT_DECLARE", "进路并发声明冲突"));
        }
    }

    /**
     * 查询全部已注册进路，按编号升序。
     */
    public List<RouteResponse> listRoutes() {
        List<RouteResponse> routes = new ArrayList<>();
        for (String routeId : routeRepo.findAllRouteIds()) {
            long createdAt = routeRepo.findRouteCreatedAt(routeId)
                    .orElseThrow(() -> new IllegalStateException("进路注册信息缺失: " + routeId));
            routes.add(new RouteResponse(routeId, Instant.ofEpochMilli(createdAt)));
        }
        return routes;
    }

    /**
     * 查询当前冲突矩阵（版本与全部对称冲突对）。
     */
    public RouteMatrixResponse getMatrix() {
        return new RouteMatrixResponse(routeRepo.matrixVersion(), routeRepo.findAllPairs());
    }

    /**
     * 更新冲突矩阵：expectedVersion 乐观校验，版本加一；引用的进路必须已注册（422）；
     * 新增冲突对若撞上已发布占用则整体拒绝（409），矩阵保持不变。
     * 全程持有全局发布锁，与发布/改签互斥，竞争时不会留下冲突占用。
     */
    public RouteMatrixResponse updateMatrix(UpdateRouteMatrixRequest req) {
        List<RoutePair> adds = normalizePairs(req.addPairs());
        List<RoutePair> removes = normalizePairs(req.removePairs());
        String hash = hashMatrix(req, adds, removes);
        Optional<RouteMatrixResponse> replay = replayIfPresent(OP_MATRIX_UPDATE, req.requestId(),
                hash, RouteMatrixResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                long version = routeRepo.matrixVersion();
                if (req.expectedVersion() != version) {
                    // 并发下同键请求可能已由先提交的事务完成：持锁后重查幂等记录，
                    // 同参重放首次完整响应，异参由重放逻辑判 409，均不泄露版本冲突
                    Optional<RouteMatrixResponse> replayed = replayIfPresent(OP_MATRIX_UPDATE,
                            req.requestId(), hash, RouteMatrixResponse.class);
                    if (replayed.isPresent()) {
                        return replayed.get();
                    }
                    throw conflict("VERSION_CONFLICT",
                            "expectedVersion=" + req.expectedVersion() + " 与当前矩阵版本 "
                                    + version + " 不一致");
                }
                validatePairRoutesExist(adds, removes);
                Set<RoutePair> existing = new TreeSet<>(RouteService::comparePairs);
                existing.addAll(routeRepo.findAllPairs());
                List<RoutePair> effectiveAdds = adds.stream()
                        .filter(pair -> !existing.contains(pair)).toList();
                validateNewPairsAgainstPublished(effectiveAdds);
                long now = System.currentTimeMillis();
                for (RoutePair pair : removes) {
                    routeRepo.deletePair(pair);
                }
                for (RoutePair pair : effectiveAdds) {
                    routeRepo.insertPair(pair, now);
                }
                routeRepo.updateMatrixVersion(version + 1);
                RouteMatrixResponse response =
                        new RouteMatrixResponse(version + 1, routeRepo.findAllPairs());
                idemRepo.insert(OP_MATRIX_UPDATE, req.requestId(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_MATRIX_UPDATE, req.requestId(), hash,
                    RouteMatrixResponse.class)
                    .orElseThrow(() -> conflict("ROUTE_MATRIX_CONFLICT", "冲突矩阵并发更新冲突"));
        }
    }

    // ---------- 内部实现 ----------

    /**
     * 冲突对规范化：字典序排序、去重；同一进路自对非法（进路自身默认冲突，无需声明）。
     */
    private List<RoutePair> normalizePairs(List<RoutePairRequest> requests) {
        Set<RoutePair> pairs = new TreeSet<>(RouteService::comparePairs);
        for (RoutePairRequest r : requests) {
            if (r.routeA().equals(r.routeB())) {
                throw badRequest("冲突对两个进路编号必须不同: " + r.routeA());
            }
            pairs.add(RoutePair.of(r.routeA(), r.routeB()));
        }
        return List.copyOf(pairs);
    }

    private static int comparePairs(RoutePair first, RoutePair second) {
        int byA = first.routeA().compareTo(second.routeA());
        return byA != 0 ? byA : first.routeB().compareTo(second.routeB());
    }

    /**
     * 冲突对引用的进路必须全部已注册，否则 422。
     */
    private void validatePairRoutesExist(List<RoutePair> adds, List<RoutePair> removes) {
        Set<String> routeIds = new TreeSet<>();
        for (RoutePair pair : adds) {
            routeIds.add(pair.routeA());
            routeIds.add(pair.routeB());
        }
        for (RoutePair pair : removes) {
            routeIds.add(pair.routeA());
            routeIds.add(pair.routeB());
        }
        if (routeIds.isEmpty()) {
            return;
        }
        Set<String> existing = routeRepo.findExistingRouteIds(routeIds);
        List<Map<String, Object>> missing = new ArrayList<>();
        for (String routeId : routeIds) {
            if (!existing.contains(routeId)) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("type", "ROUTE_NOT_FOUND");
                detail.put("routeId", routeId);
                missing.add(detail);
            }
        }
        if (!missing.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ROUTE_NOT_FOUND",
                    "冲突对引用了未注册的进路", missing);
        }
    }

    /**
     * 新增冲突对不得撞上任何已发布占用：两条进路各自存在 UTC 区间重叠的已发布时隙时，
     * 整体拒绝本次矩阵更新（409），矩阵版本与冲突对保持不变。
     */
    private void validateNewPairsAgainstPublished(List<RoutePair> effectiveAdds) {
        if (effectiveAdds.isEmpty()) {
            return;
        }
        List<PublishedSlot> published = planRepo.findAllPublishedRouteSlots();
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (RoutePair pair : effectiveAdds) {
            for (PublishedSlot first : published) {
                for (PublishedSlot second : published) {
                    boolean match = first.routeId().equals(pair.routeA())
                            && second.routeId().equals(pair.routeB());
                    boolean overlap = first.startUtc().isBefore(second.endUtc())
                            && second.startUtc().isBefore(first.endUtc());
                    if (match && overlap) {
                        Map<String, Object> detail = new LinkedHashMap<>();
                        detail.put("type", "ROUTE_MATRIX_CONFLICT");
                        detail.put("routeA", pair.routeA());
                        detail.put("routeB", pair.routeB());
                        detail.put("scheduleKeyA", first.scheduleKey());
                        detail.put("scheduleKeyB", second.scheduleKey());
                        detail.put("startUtcA", first.startUtc().toString());
                        detail.put("endUtcA", first.endUtc().toString());
                        detail.put("startUtcB", second.startUtc().toString());
                        detail.put("endUtcB", second.endUtc().toString());
                        conflicts.add(detail);
                    }
                }
            }
        }
        if (!conflicts.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "ROUTE_MATRIX_CONFLICT",
                    "新增冲突对撞上已发布占用，矩阵未更新", conflicts);
        }
    }
    /**
     * 幂等身份：操作 + 操作者 + 资源 + 全部参数（含期望版本），
     * 以规范化 JSON 的 SHA-256 结构化比较，避免分隔符碰撞。
     */
    private String hashDeclare(String routeId, DeclareRouteRequest req) {
        Map<String, Object> identity = new TreeMap<>();
        identity.put("op", OP_DECLARE_ROUTE);
        identity.put("operator", req.operator());
        identity.put("resource", routeId);
        return sha256Json(identity);
    }

    private String hashMatrix(UpdateRouteMatrixRequest req, List<RoutePair> adds,
                              List<RoutePair> removes) {
        Map<String, Object> identity = new TreeMap<>();
        identity.put("op", OP_MATRIX_UPDATE);
        identity.put("operator", req.operator());
        identity.put("resource", MATRIX_RESOURCE);
        identity.put("expectedVersion", req.expectedVersion());
        identity.put("addPairs", pairStrings(adds));
        identity.put("removePairs", pairStrings(removes));
        return sha256Json(identity);
    }

    private static List<List<String>> pairStrings(List<RoutePair> pairs) {
        return pairs.stream()
                .map(pair -> List.of(pair.routeA(), pair.routeB()))
                .toList();
    }

    /**
     * 幂等重放：存在记录且参数一致返回首次结果；参数不一致抛 409。
     */
    private <T> Optional<T> replayIfPresent(String opType, String requestId, String hash,
                                            Class<T> type) {
        return idemRepo.find(opType, requestId).map(record -> {
            if (!record.requestHash().equals(hash)) {
                throw conflict("IDEMPOTENT_KEY_REUSED",
                        "requestId 已用于其他参数: " + requestId);
            }
            return fromJson(record.responseJson(), type);
        });
    }

    private String sha256Json(Object identity) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(
                    objectMapper.writeValueAsString(identity).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("幂等身份序列化失败", e);
        }
    }

    private String toJson(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    private ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", message);
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}