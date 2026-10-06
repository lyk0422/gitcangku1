package com.example.starter.service;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.ObstacleCreateRequest;
import com.example.starter.api.dto.ObstacleResult;
import com.example.starter.api.dto.ObstacleReviseRequest;
import com.example.starter.api.dto.ObstacleRevokeRequest;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.ReviewResultDto;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.RouteReplaceRequest;
import com.example.starter.api.dto.RouteResult;
import com.example.starter.api.dto.ZoneCreateRequest;
import com.example.starter.api.dto.ZoneResult;
import com.example.starter.api.dto.ZoneRevokeRequest;
import com.example.starter.domain.EnvelopeHit;
import com.example.starter.domain.Geometry;
import com.example.starter.domain.Point;
import com.example.starter.domain.ReviewConclusion;
import com.example.starter.domain.ZoneStatus;
import com.example.starter.repo.AirspaceRepository;
import com.example.starter.repo.DedupPo;
import com.example.starter.repo.ObstaclePo;
import com.example.starter.repo.ObstacleRepository;
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
import java.util.List;
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
 */
@Service
public class AirspaceReviewService {

    static final String KIND_ZONE_CREATE = "ZONE_CREATE";
    static final String KIND_ZONE_REVOKE = "ZONE_REVOKE";
    static final String KIND_ROUTE_CREATE = "ROUTE_CREATE";
    static final String KIND_ROUTE_REPLACE = "ROUTE_REPLACE";
    static final String KIND_REVIEW = "REVIEW";
    static final String KIND_OBSTACLE_CREATE = "OBSTACLE_CREATE";
    static final String KIND_OBSTACLE_REVISE = "OBSTACLE_REVISE";
    static final String KIND_OBSTACLE_REVOKE = "OBSTACLE_REVOKE";

    private final AirspaceRepository airspaceRepo;
    private final RouteRepository routeRepo;
    private final ReviewRepository reviewRepo;
    private final ObstacleRepository obstacleRepo;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate txTemplate;

    public AirspaceReviewService(AirspaceRepository airspaceRepo,
                                 RouteRepository routeRepo,
                                 ReviewRepository reviewRepo,
                                 ObstacleRepository obstacleRepo,
                                 ObjectMapper objectMapper,
                                 Clock clock,
                                 PlatformTransactionManager transactionManager) {
        this.airspaceRepo = airspaceRepo;
        this.routeRepo = routeRepo;
        this.reviewRepo = reviewRepo;
        this.obstacleRepo = obstacleRepo;
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

    // ============================ 圆形障碍 ============================

    /** 登记圆形障碍（初始版本 1）；同键同参重放原结果，异参 409。 */
    public MutationResponse createObstacle(ObstacleCreateRequest request) {
        return withIdempotency(request.requestId(), KIND_OBSTACLE_CREATE, canonicalHash(request), () -> {
            validateObstacleGeometry(request.cx(), request.cy(), request.radius());
            if (obstacleRepo.findObstacle(request.obstacleId()) != null) {
                throw new ApiException(HttpStatus.CONFLICT, "OBSTACLE_ALREADY_EXISTS",
                        "障碍已存在: " + request.obstacleId());
            }
            long newVersion = airspaceRepo.incrementGlobalVersion();
            obstacleRepo.insertObstacle(new ObstaclePo(request.obstacleId(), 1,
                    request.cx(), request.cy(), request.radius(), ZoneStatus.ACTIVE.name()));
            return new MutationResponse(request.requestId(), false,
                    new ObstacleResult(request.obstacleId(), ZoneStatus.ACTIVE.name(), 1, newVersion));
        });
    }

    /** 修订圆形障碍几何（圆心/半径），expectedVersion 不匹配返回 409；成功版本加一。 */
    public MutationResponse reviseObstacle(ObstacleReviseRequest request) {
        return withIdempotency(request.requestId(), KIND_OBSTACLE_REVISE, canonicalHash(request), () -> {
            validateObstacleGeometry(request.cx(), request.cy(), request.radius());
            ObstaclePo obstacle = requireActiveObstacle(request.obstacleId());
            ensureObstacleVersion(obstacle, request.expectedVersion());
            long newVersion = airspaceRepo.incrementGlobalVersion();
            // 条件更新兜底并发修订：更新 0 行说明版本已被其他事务推进
            int updated = obstacleRepo.reviseObstacle(request.obstacleId(),
                    request.expectedVersion(), request.cx(), request.cy(), request.radius());
            if (updated == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                        "障碍版本已变化，请使用最新 expectedVersion 重试");
            }
            return new MutationResponse(request.requestId(), false,
                    new ObstacleResult(request.obstacleId(), ZoneStatus.ACTIVE.name(),
                            request.expectedVersion() + 1, newVersion));
        });
    }

    /** 撤销圆形障碍，expectedVersion 不匹配返回 409；成功版本加一并置为 REVOKED。 */
    public MutationResponse revokeObstacle(ObstacleRevokeRequest request) {
        return withIdempotency(request.requestId(), KIND_OBSTACLE_REVOKE, canonicalHash(request), () -> {
            ObstaclePo obstacle = requireActiveObstacle(request.obstacleId());
            ensureObstacleVersion(obstacle, request.expectedVersion());
            long newVersion = airspaceRepo.incrementGlobalVersion();
            int updated = obstacleRepo.revokeObstacle(request.obstacleId(), request.expectedVersion());
            if (updated == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                        "障碍版本已变化，请使用最新 expectedVersion 重试");
            }
            return new MutationResponse(request.requestId(), false,
                    new ObstacleResult(request.obstacleId(), ZoneStatus.REVOKED.name(),
                            request.expectedVersion() + 1, newVersion));
        });
    }

    /** 读取障碍并校验存在且未撤销。 */
    private ObstaclePo requireActiveObstacle(String obstacleId) {
        ObstaclePo obstacle = obstacleRepo.findObstacle(obstacleId);
        if (obstacle == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "OBSTACLE_NOT_FOUND",
                    "障碍不存在: " + obstacleId);
        }
        if (ZoneStatus.REVOKED.name().equals(obstacle.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "OBSTACLE_ALREADY_REVOKED",
                    "障碍已撤销，旧版本不可改: " + obstacleId);
        }
        return obstacle;
    }

    private static void ensureObstacleVersion(ObstaclePo obstacle, int expectedVersion) {
        if (obstacle.version() != expectedVersion) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                    "障碍版本不匹配：expected=" + expectedVersion
                            + ", current=" + obstacle.version());
        }
    }

    /** 圆心为平面米坐标、半径为非负整数米；非法参数 400。 */
    private static void validateObstacleGeometry(Integer cx, Integer cy, Integer radius) {
        if (cx == null || cy == null || radius == null
                || cx < -100000 || cx > 100000 || cy < -100000 || cy > 100000
                || radius < 0 || radius > 100000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_OBSTACLE",
                    "障碍圆心须在 [-100000,100000] 米内，半径须为 0~100000 的非负整数米");
        }
    }

    // ============================ 航线 ============================

    /** 创建航线（初始版本 1）；envelopeRadius 缺省视为 0（旧无包络航线）。 */
    public MutationResponse createRoute(RouteCreateRequest request) {
        return withIdempotency(request.requestId(), KIND_ROUTE_CREATE, canonicalHash(request), () -> {
            List<Point> points = toPoints(request.points());
            validatePointsDistinct(points);
            int envelopeRadius = normalizeEnvelopeRadius(request.envelopeRadius());
            if (routeRepo.findRoute(request.routeId()) != null) {
                throw new ApiException(HttpStatus.CONFLICT, "ROUTE_ALREADY_EXISTS",
                        "航线已存在: " + request.routeId());
            }
            routeRepo.insertRoute(request.routeId(), points, envelopeRadius);
            return new MutationResponse(request.requestId(), false,
                    new RouteResult(request.routeId(), 1));
        });
    }

    /** 替换航线点列与包络半径，expectedVersion 不匹配返回 409；成功版本加一。 */
    public MutationResponse replaceRoute(RouteReplaceRequest request) {
        return withIdempotency(request.requestId(), KIND_ROUTE_REPLACE, canonicalHash(request), () -> {
            List<Point> points = toPoints(request.points());
            validatePointsDistinct(points);
            int envelopeRadius = normalizeEnvelopeRadius(request.envelopeRadius());
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
                    request.routeId(), request.expectedVersion(), envelopeRadius);
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

    // ============================ 审核 ============================

    /** 提交审核：任一指定版本不是当前版本返回 409；结果不可变。 */
    public MutationResponse review(ReviewRequest request) {
        return withIdempotency(request.requestId(), KIND_REVIEW, canonicalHash(request), () -> {
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
            // 持锁状态下读取全部有效禁飞区，与上面的版本号同属一个一致状态
            List<ZonePo> activeZones = airspaceRepo.findActiveZones();
            Set<String> hits = new TreeSet<>();
            for (ZonePo zone : activeZones) {
                if (Geometry.polylineHitsRectangle(route.points(),
                        zone.xMin(), zone.yMin(), zone.xMax(), zone.yMax())) {
                    hits.add(zone.zoneId());
                }
            }
            // 水平包络审查：只使用审核时登记的航线几何（点列与包络半径）与有效障碍，
            // 调用方不能替换几何；逐线段精确平方距离比较，接触也阻断
            List<EnvelopeHit> envelopeHits = computeEnvelopeHits(route);
            String conclusion = hits.isEmpty() && envelopeHits.isEmpty()
                    ? ReviewConclusion.CLEAR.name()
                    : ReviewConclusion.BLOCKED.name();
            String reviewId = "rv_" + UUID.randomUUID();
            ReviewPo po = new ReviewPo(reviewId, request.routeId(), route.version(), globalVersion,
                    conclusion, new ArrayList<>(hits), List.copyOf(route.points()),
                    route.envelopeRadius(), List.copyOf(envelopeHits),
                    request.requestId(), nowMillis());
            reviewRepo.insertReview(po);
            return new MutationResponse(request.requestId(), false, toDto(po, conclusion, true));
        });
    }

    /**
     * 计算航线每条线段（含内部投影与端点）与全部有效圆形障碍的包络命中：
     * 线段到圆心最小距离 ≤ 障碍半径 + 航线包络半径 即命中（接触也阻断）。
     */
    private List<EnvelopeHit> computeEnvelopeHits(RoutePo route) {
        List<EnvelopeHit> envelopeHits = new ArrayList<>();
        List<Point> points = route.points();
        int routeRadius = route.envelopeRadius();
        for (ObstaclePo obstacle : obstacleRepo.findActiveObstacles()) {
            long limit = (long) obstacle.radius() + routeRadius;
            for (int i = 0; i + 1 < points.size(); i++) {
                Point a = points.get(i);
                Point b = points.get(i + 1);
                if (Geometry.segmentWithinDistance(a.x(), a.y(), b.x(), b.y(),
                        obstacle.cx(), obstacle.cy(), limit)) {
                    envelopeHits.add(new EnvelopeHit(obstacle.obstacleId(), obstacle.version(),
                            i, routeRadius, obstacle.radius()));
                }
            }
        }
        return envelopeHits;
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

    private static ReviewResultDto toDto(ReviewPo po, String conclusion, Boolean current) {
        List<RoutePointDto> snapshot = new ArrayList<>(po.pointsSnapshot().size());
        for (Point p : po.pointsSnapshot()) {
            snapshot.add(new RoutePointDto(p.x(), p.y()));
        }
        return new ReviewResultDto(po.reviewId(), po.routeId(), po.routeVersion(),
                po.airspaceVersion(), conclusion, List.copyOf(po.hitZoneIds()), snapshot,
                po.envelopeRadius(), List.copyOf(po.envelopeHits()), current);
    }

    /** 包络半径缺省（旧无包络航线）视为 0；非法值 400。 */
    private static int normalizeEnvelopeRadius(Integer envelopeRadius) {
        if (envelopeRadius == null) {
            return 0;
        }
        if (envelopeRadius < 0 || envelopeRadius > 100000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ENVELOPE_RADIUS",
                    "包络半径须为 0~100000 的非负整数米");
        }
        return envelopeRadius;
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
