package com.example.starter.plan.service;

import com.example.starter.plan.model.ConflictPair;
import com.example.starter.plan.model.PublishedRouteSlot;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.repo.RouteRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.ConflictPairRequest;
import com.example.starter.plan.web.dto.ConflictPairView;
import com.example.starter.plan.web.dto.CreateRouteRequest;
import com.example.starter.plan.web.dto.RouteMatrixResponse;
import com.example.starter.plan.web.dto.RouteResponse;
import com.example.starter.plan.web.dto.UpdateRouteMatrixRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 进路注册与冲突矩阵维护。矩阵版本从 1 起每次更新加一，更新须带 expectedVersion；
 * 矩阵更新与发布/改签共用全局发布锁，竞争时按事务提交顺序裁决，不会留下冲突占用。
 *
 * <p>幂等约定：写操作按 (操作类型, requestId) 幂等，身份含操作、操作者、资源及全部参数
 * 和期望版本，经结构化（JSON）序列化后取 SHA-256，避免分隔符碰撞；同键同参重放首次
 * 完整响应，同键异参 409；仅成功结果落键，失败不占键，业务与去重同事务原子提交。
 */
@Service
public class RouteService {

    private static final String OP_ROUTE_CREATE = "ROUTE_CREATE";
    private static final String OP_MATRIX_UPDATE = "MATRIX_UPDATE";

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
     * 注册新进路，全局最多 20 条；重复进路 409。
     */
    public RouteResponse createRoute(CreateRouteRequest req) {
        String hash = hashCreateRoute(req);
        Optional<RouteResponse> replay =
                replayIfPresent(OP_ROUTE_CREATE, req.requestId(), hash, RouteResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                if (routeRepo.existsRoute(req.routeId())) {
                    throw conflict("ROUTE_EXISTS", "进路已存在: " + req.routeId());
                }
                if (routeRepo.countRoutes() >= RouteRepository.MAX_ROUTES) {
                    throw conflict("ROUTE_LIMIT_EXCEEDED",
                            "进路数量已达上限: " + RouteRepository.MAX_ROUTES);
                }
                long now = System.currentTimeMillis();
                routeRepo.insertRoute(req.routeId(), now);
                RouteResponse response = new RouteResponse(req.routeId());
                idemRepo.insert(OP_ROUTE_CREATE, req.requestId(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_ROUTE_CREATE, req.requestId(), hash, RouteResponse.class)
                    .orElseThrow(() -> conflict("ROUTE_EXISTS", "进路已存在: " + req.routeId()));
        }
    }

    /**
     * 查询当前冲突矩阵（版本、全部进路与规范化冲突对）。
     */
    public RouteMatrixResponse getMatrix() {
        return new RouteMatrixResponse(routeRepo.matrixVersion(), routeRepo.findAllRoutes(),
                toPairViews(routeRepo.findAllConflictPairs()));
    }

    /**
     * 更新冲突矩阵：发布锁内校验版本与进路存在性，新增冲突对若撞上已发布占用则 409 拒绝；
     * 通过后同一事务应用增删、版本加一并写入幂等记录。
     */
    public RouteMatrixResponse updateMatrix(UpdateRouteMatrixRequest req) {
        Set<ConflictPair> adds = normalize(req.addConflicts());
        Set<ConflictPair> removes = normalize(req.removeConflicts());
        for (ConflictPair pair : union(adds, removes)) {
            if (pair.routeA().equals(pair.routeB())) {
                throw badRequest("进路自身默认冲突，无需声明: " + pair.routeA());
            }
        }
        String hash = hashMatrix(req, adds, removes);
        Optional<RouteMatrixResponse> replay =
                replayIfPresent(OP_MATRIX_UPDATE, req.requestId(), hash, RouteMatrixResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                int version = routeRepo.lockMatrixVersion();
                if (req.expectedVersion() != version) {
                    throw conflict("VERSION_CONFLICT",
                            "expectedVersion=" + req.expectedVersion()
                                    + " 与矩阵当前版本 " + version + " 不一致");
                }
                for (ConflictPair pair : union(adds, removes)) {
                    if (!routeRepo.existsRoute(pair.routeA())) {
                        throw routeNotFound(pair.routeA());
                    }
                    if (!routeRepo.existsRoute(pair.routeB())) {
                        throw routeNotFound(pair.routeB());
                    }
                }
                Set<ConflictPair> existing = new TreeSet<>(routeRepo.findAllConflictPairs());
                Set<ConflictPair> effectiveAdds = new TreeSet<>(adds);
                effectiveAdds.removeAll(existing);
                List<Map<String, Object>> collisions = new ArrayList<>();
                for (ConflictPair pair : effectiveAdds) {
                    collisions.addAll(findPublishedCollisions(pair));
                }
                if (!collisions.isEmpty()) {
                    throw new ApiException(HttpStatus.CONFLICT, "ROUTE_MATRIX_CONFLICT",
                            "新增冲突对与已发布占用冲突，矩阵未更新", collisions);
                }
                for (ConflictPair pair : removes) {
                    routeRepo.deleteConflictPair(pair);
                }
                for (ConflictPair pair : effectiveAdds) {
                    routeRepo.insertConflictPair(pair);
                }
                int newVersion = version + 1;
                routeRepo.updateMatrixVersion(newVersion);
                long now = System.currentTimeMillis();
                RouteMatrixResponse response = new RouteMatrixResponse(newVersion,
                        routeRepo.findAllRoutes(),
                        toPairViews(routeRepo.findAllConflictPairs()));
                idemRepo.insert(OP_MATRIX_UPDATE, req.requestId(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_MATRIX_UPDATE, req.requestId(), hash, RouteMatrixResponse.class)
                    .orElseThrow(() -> conflict("ROUTE_MATRIX_CONFLICT", "冲突对写入竞争失败"));
        }
    }

    // ---------- 内部实现 ----------

    /**
     * 新增冲突对 (A, B) 是否撞上已发布占用：同运营日内 A、B 两进路的已发布区间
     * （左闭右开）不得重叠。
     */
    private List<Map<String, Object>> findPublishedCollisions(ConflictPair pair) {
        List<PublishedRouteSlot> slots = planRepo.findPublishedSlotsOnRoutes(
                List.of(pair.routeA(), pair.routeB()));
        Map<LocalDate, List<PublishedRouteSlot>> byDate = new LinkedHashMap<>();
        for (PublishedRouteSlot slot : slots) {
            byDate.computeIfAbsent(slot.opDate(), d -> new ArrayList<>()).add(slot);
        }
        List<Map<String, Object>> collisions = new ArrayList<>();
        byDate.forEach((opDate, daySlots) -> {
            List<PublishedRouteSlot> aSlots = daySlots.stream()
                    .filter(s -> s.routeId().equals(pair.routeA())).toList();
            List<PublishedRouteSlot> bSlots = daySlots.stream()
                    .filter(s -> s.routeId().equals(pair.routeB())).toList();
            for (PublishedRouteSlot a : aSlots) {
                for (PublishedRouteSlot b : bSlots) {
                    boolean overlap = a.startUtc().isBefore(b.endUtc())
                            && b.startUtc().isBefore(a.endUtc());
                    if (overlap) {
                        Map<String, Object> detail = new LinkedHashMap<>();
                        detail.put("type", "ROUTE_MATRIX_CONFLICT");
                        detail.put("routeA", pair.routeA());
                        detail.put("routeB", pair.routeB());
                        detail.put("scheduleKeyA", a.scheduleKey());
                        detail.put("scheduleKeyB", b.scheduleKey());
                        detail.put("opDate", opDate.toString());
                        detail.put("startUtc", a.startUtc().toString());
                        detail.put("endUtc", a.endUtc().toString());
                        collisions.add(detail);
                    }
                }
            }
        });
        return collisions;
    }

    private Set<ConflictPair> normalize(List<ConflictPairRequest> pairs) {
        Set<ConflictPair> result = new TreeSet<>();
        for (ConflictPairRequest p : pairs) {
            result.add(new ConflictPair(p.routeA(), p.routeB()));
        }
        return result;
    }

    private Set<ConflictPair> union(Set<ConflictPair> a, Set<ConflictPair> b) {
        Set<ConflictPair> result = new TreeSet<>(a);
        result.addAll(b);
        return result;
    }

    private List<ConflictPairView> toPairViews(List<ConflictPair> pairs) {
        return pairs.stream()
                .map(p -> new ConflictPairView(p.routeA(), p.routeB()))
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

    /**
     * 幂等身份：操作、操作者、资源与全部参数（含期望版本）经结构化 JSON 序列化后取
     * SHA-256，避免分隔符拼接碰撞。
     */
    private String hashCreateRoute(CreateRouteRequest req) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("op", OP_ROUTE_CREATE);
        identity.put("operator", req.operator());
        identity.put("routeId", req.routeId());
        return sha256(toJson(identity));
    }

    private String hashMatrix(UpdateRouteMatrixRequest req, Set<ConflictPair> adds,
                              Set<ConflictPair> removes) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("op", OP_MATRIX_UPDATE);
        identity.put("operator", req.operator());
        identity.put("expectedVersion", req.expectedVersion());
        identity.put("addConflicts", pairsJson(adds));
        identity.put("removeConflicts", pairsJson(removes));
        return sha256(toJson(identity));
    }

    private List<List<String>> pairsJson(Set<ConflictPair> pairs) {
        return pairs.stream()
                .map(p -> List.of(p.routeA(), p.routeB()))
                .toList();
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化失败", e);
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

    private ApiException routeNotFound(String routeId) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ROUTE_NOT_FOUND",
                "进路不存在: " + routeId);
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}