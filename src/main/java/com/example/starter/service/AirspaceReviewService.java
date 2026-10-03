package com.example.starter.service;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.BatchReviewItemDto;
import com.example.starter.api.dto.BatchReviewRequest;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.ReviewResultDto;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.RouteReplaceRequest;
import com.example.starter.api.dto.RouteResult;
import com.example.starter.api.dto.WeatherViolationDto;
import com.example.starter.api.dto.ZoneCreateRequest;
import com.example.starter.api.dto.ZoneResult;
import com.example.starter.api.dto.ZoneRevokeRequest;
import com.example.starter.domain.Geometry;
import com.example.starter.domain.Point;
import com.example.starter.domain.ReviewConclusion;
import com.example.starter.domain.ZoneStatus;
import com.example.starter.repo.AirspaceRepository;
import com.example.starter.repo.ReviewPo;
import com.example.starter.repo.ReviewRepository;
import com.example.starter.repo.RoutePo;
import com.example.starter.repo.RouteRepository;
import com.example.starter.repo.WeatherForecastPo;
import com.example.starter.repo.WeatherMinimaPo;
import com.example.starter.repo.WeatherRepository;
import com.example.starter.repo.ZonePo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 禁飞区与航线审查业务服务。
 *
 * <p>所有写操作经 {@link IdempotencySupport} 在同一数据库事务内完成业务变更与
 * 幂等去重记录的原子提交；失败（含业务冲突）回滚事务，不占用 requestId。</p>
 *
 * <p>审核事务先锁全局空域版本行、再锁航线行：区域变更事务必须更新版本行，
 * 因而与审核互斥，保证审核使用的空域版本号与全部禁飞区来自同一已提交状态。
 * 提交气象版本时还要求其等于当前气象（值班）版本，并对路径经过区域比对
 * 预测与最低标准；审查快照固化标准、预测与气象版本，后续修订不改写原结果，
 * 仅使旧结论在时效判断中标记为 STALE。</p>
 */
@Service
public class AirspaceReviewService {

    static final String KIND_ZONE_CREATE = "ZONE_CREATE";
    static final String KIND_ZONE_REVOKE = "ZONE_REVOKE";
    static final String KIND_ROUTE_CREATE = "ROUTE_CREATE";
    static final String KIND_ROUTE_REPLACE = "ROUTE_REPLACE";
    static final String KIND_REVIEW = "REVIEW";
    static final String KIND_BATCH_REVIEW = "BATCH_REVIEW";

    private final AirspaceRepository airspaceRepo;
    private final RouteRepository routeRepo;
    private final ReviewRepository reviewRepo;
    private final WeatherRepository weatherRepo;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate txTemplate;
    private final IdempotencySupport idempotency;

    public AirspaceReviewService(AirspaceRepository airspaceRepo,
                                 RouteRepository routeRepo,
                                 ReviewRepository reviewRepo,
                                 WeatherRepository weatherRepo,
                                 ObjectMapper objectMapper,
                                 Clock clock,
                                 PlatformTransactionManager transactionManager,
                                 IdempotencySupport idempotency) {
        this.airspaceRepo = airspaceRepo;
        this.routeRepo = routeRepo;
        this.reviewRepo = reviewRepo;
        this.weatherRepo = weatherRepo;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.idempotency = idempotency;
    }

    // ============================ 禁飞区 ============================

    /** 创建禁飞区；同键同参重放原结果，异参 409。 */
    public MutationResponse createZone(ZoneCreateRequest request) {
        return idempotency.execute(request.requestId(), KIND_ZONE_CREATE, request, () -> {
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
        return idempotency.execute(request.requestId(), KIND_ZONE_REVOKE, request, () -> {
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
        return idempotency.execute(request.requestId(), KIND_ROUTE_CREATE, request, () -> {
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
        return idempotency.execute(request.requestId(), KIND_ROUTE_REPLACE, request, () -> {
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
    // ============================ 审核 ============================

    /** 提交审核：任一指定版本不是当前版本返回 409；结果不可变。 */
    public MutationResponse review(ReviewRequest request) {
        return idempotency.execute(request.requestId(), KIND_REVIEW, request, () -> {
            // 先锁空域版本行：与任何区域创建/撤销、气象变更事务互斥
            long globalVersion = airspaceRepo.getGlobalVersionForUpdate();
            // 再锁航线行：与航线替换事务互斥，保证版本号与点列一致
            RoutePo route = lockAndCheckRoute(request.routeId(), request.routeVersion());
            checkAirspaceVersion(globalVersion, request.airspaceVersion());
            checkWeatherVersion(request.weatherVersion());
            // 持锁状态下读取全部有效禁飞区，与上面的版本号同属一个一致状态
            List<ZonePo> activeZones = airspaceRepo.findActiveZones();
            ReviewPo po = evaluateAndPersist(route, globalVersion, request.weatherVersion(),
                    activeZones, request.requestId());
            return new MutationResponse(request.requestId(), false, toDto(po, po.conclusion(), true));
        });
    }

    /**
     * 批量审核：全部航线校验通过才创建结果；任一航线缺预测（422）、版本不符（409）
     * 或不存在（404）则整批回滚，不创建任何审核结果。结果按提交顺序返回。
     */
    public MutationResponse reviewBatch(BatchReviewRequest request) {
        return idempotency.execute(request.requestId(), KIND_BATCH_REVIEW, request, () -> {
            long globalVersion = airspaceRepo.getGlobalVersionForUpdate();
            List<ZonePo> activeZones = airspaceRepo.findActiveZones();
            List<ReviewResultDto> results = new ArrayList<>();
            int index = 0;
            for (BatchReviewItemDto item : request.items()) {
                RoutePo route = lockAndCheckRoute(item.routeId(), item.routeVersion());
                checkAirspaceVersion(globalVersion, item.airspaceVersion());
                checkWeatherVersion(item.weatherVersion());
                // 批量内每条审查派生子请求标识（批量 requestId + 序号），
                // 满足 review.request_id 唯一约束；整批仍由批量 requestId 幂等保护
                String itemRequestId = request.requestId() + "#" + index++;
                ReviewPo po = evaluateAndPersist(route, globalVersion, item.weatherVersion(),
                        activeZones, itemRequestId);
                results.add(toDto(po, po.conclusion(), true));
            }
            return new MutationResponse(request.requestId(), false, results);
        });
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
            long weatherVersion = weatherRepo.getWeatherVersion();
            boolean current = currentRoute != null
                    && currentRoute.version() == latest.routeVersion()
                    && globalVersion == latest.airspaceVersion()
                    && (latest.weatherVersion() == null
                            || latest.weatherVersion() == weatherVersion);
            // 不匹配时绝不能把旧 CLEAR/BLOCKED 当成当前结论，统一返回 STALE
            String conclusion = current ? latest.conclusion() : "STALE";
            return toDto(latest, conclusion, current);
        });
    }

    // ============================ 审核内部 ============================

    /** 锁航线行并校验航线存在与版本。 */
    private RoutePo lockAndCheckRoute(String routeId, int expectedVersion) {
        RoutePo route = routeRepo.findRouteForUpdate(routeId);
        if (route == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND",
                    "航线不存在: " + routeId);
        }
        if (route.version() != expectedVersion) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                    "航线版本不是当前版本：submitted=" + expectedVersion
                            + ", current=" + route.version());
        }
        return route;
    }

    private void checkAirspaceVersion(long current, long submitted) {
        if (current != submitted) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                    "空域版本不是当前版本：submitted=" + submitted + ", current=" + current);
        }
    }

    /** 提交气象版本时必须等于当前气象（值班）版本；null 表示不做气象评估。 */
    private void checkWeatherVersion(Long submitted) {
        if (submitted == null) {
            return;
        }
        long current = weatherRepo.getWeatherVersion();
        if (current != submitted) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                    "气象版本不是当前版本：submitted=" + submitted + ", current=" + current);
        }
    }

    /**
     * 评估并持久化一条不可变审核结果：命中有效禁飞区为 BLOCKED；否则提交气象版本时
     * 对路径经过区域比对预测与标准，任一预测值低于标准为 WEATHER_BLOCKED。
     */
    private ReviewPo evaluateAndPersist(RoutePo route, long globalVersion, Long weatherVersion,
                                        List<ZonePo> activeZones, String requestId) {
        Set<String> hits = new TreeSet<>();
        for (ZonePo zone : activeZones) {
            if (Geometry.polylineHitsRectangle(route.points(),
                    zone.xMin(), zone.yMin(), zone.xMax(), zone.yMax())) {
                hits.add(zone.zoneId());
            }
        }
        String conclusion;
        String weatherSnapshot = null;
        if (!hits.isEmpty()) {
            conclusion = ReviewConclusion.BLOCKED.name();
        } else if (weatherVersion != null) {
            WeatherEvaluation evaluation = evaluateWeather(route.points(), weatherVersion);
            weatherSnapshot = evaluation.snapshotJson();
            conclusion = evaluation.violations().isEmpty()
                    ? ReviewConclusion.CLEAR.name()
                    : ReviewConclusion.WEATHER_BLOCKED.name();
        } else {
            conclusion = ReviewConclusion.CLEAR.name();
        }
        String reviewId = "rv_" + UUID.randomUUID();
        ReviewPo po = new ReviewPo(reviewId, route.routeId(), route.version(), globalVersion,
                conclusion, new ArrayList<>(hits), List.copyOf(route.points()),
                requestId, nowMillis(), weatherVersion, weatherSnapshot);
        reviewRepo.insertReview(po);
        return po;
    }
    /**
     * 气象评估：对路径经过（几何相交）且配置了最低标准的区域，逐时段取截至
     * 提交版本的最新预测与标准比对。预测缺失返回 422；快照固化标准、预测与气象版本。
     */
    private WeatherEvaluation evaluateWeather(List<Point> points, long weatherVersion) {
        // findAllMinima 按 zoneId、窗口起点升序，分组后顺序稳定
        Map<String, List<WeatherMinimaPo>> minimaByZone = new LinkedHashMap<>();
        for (WeatherMinimaPo m : weatherRepo.findAllMinima()) {
            minimaByZone.computeIfAbsent(m.zoneId(), k -> new ArrayList<>()).add(m);
        }
        List<WeatherViolationDto> violations = new ArrayList<>();
        ObjectNode root = objectMapper.createObjectNode();
        root.put("weatherVersion", weatherVersion);
        ArrayNode zonesNode = root.putArray("zones");
        for (Map.Entry<String, List<WeatherMinimaPo>> entry : minimaByZone.entrySet()) {
            String zoneId = entry.getKey();
            ZonePo zone = airspaceRepo.findZone(zoneId);
            if (zone == null || !Geometry.polylineHitsRectangle(points,
                    zone.xMin(), zone.yMin(), zone.xMax(), zone.yMax())) {
                continue;
            }
            ObjectNode zoneNode = zonesNode.addObject();
            zoneNode.put("zoneId", zoneId);
            ArrayNode windowsNode = zoneNode.putArray("windows");
            for (WeatherMinimaPo m : entry.getValue()) {
                WeatherForecastPo forecast = weatherRepo.findForecastAsOf(
                        weatherVersion, zoneId, m.windowStartUtc(), m.windowEndUtc());
                if (forecast == null) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "FORECAST_MISSING",
                            "预测缺失：区域 " + zoneId + " 时段 [" + m.windowStartUtc()
                                    + ", " + m.windowEndUtc() + ") 在气象版本 " + weatherVersion
                                    + " 下没有预测值");
                }
                List<WeatherViolationDto> windowViolations = new ArrayList<>();
                if (forecast.visibilityM() < m.minVisibilityM()) {
                    windowViolations.add(new WeatherViolationDto(zoneId, m.windowStartUtc(),
                            m.windowEndUtc(), "visibility", forecast.visibilityM(),
                            m.minVisibilityM(), m.minVisibilityM() - forecast.visibilityM()));
                }
                if (forecast.cloudBaseM() < m.minCloudBaseM()) {
                    windowViolations.add(new WeatherViolationDto(zoneId, m.windowStartUtc(),
                            m.windowEndUtc(), "cloudBase", forecast.cloudBaseM(),
                            m.minCloudBaseM(), m.minCloudBaseM() - forecast.cloudBaseM()));
                }
                violations.addAll(windowViolations);
                ObjectNode windowNode = windowsNode.addObject();
                windowNode.put("startUtc", m.windowStartUtc());
                windowNode.put("endUtc", m.windowEndUtc());
                windowNode.put("minVisibilityM", m.minVisibilityM());
                windowNode.put("minCloudBaseM", m.minCloudBaseM());
                windowNode.put("forecastVisibilityM", forecast.visibilityM());
                windowNode.put("forecastCloudBaseM", forecast.cloudBaseM());
                ArrayNode violationsNode = windowNode.putArray("violations");
                for (WeatherViolationDto v : windowViolations) {
                    violationsNode.add(objectMapper.valueToTree(v));
                }
            }
        }
        return new WeatherEvaluation(violations, root.toString());
    }

    /** 气象评估结果：违规明细（按 zoneId、窗口起点、字段顺序）与固化快照 JSON。 */
    private record WeatherEvaluation(List<WeatherViolationDto> violations, String snapshotJson) {
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

    private ReviewResultDto toDto(ReviewPo po, String conclusion, Boolean current) {
        List<RoutePointDto> snapshot = new ArrayList<>(po.pointsSnapshot().size());
        for (Point p : po.pointsSnapshot()) {
            snapshot.add(new RoutePointDto(p.x(), p.y()));
        }
        // 未做气象评估的历史记录保持 null；做过评估的从固化快照还原违规明细
        List<WeatherViolationDto> violations = po.weatherSnapshot() == null
                ? null : parseViolations(po.weatherSnapshot());
        return new ReviewResultDto(po.reviewId(), po.routeId(), po.routeVersion(),
                po.airspaceVersion(), conclusion, List.copyOf(po.hitZoneIds()), snapshot, current,
                po.weatherVersion(), violations);
    }

    /** 从固化快照 JSON 还原违规明细（顺序与评估时一致）。 */
    private List<WeatherViolationDto> parseViolations(String snapshotJson) {
        try {
            List<WeatherViolationDto> violations = new ArrayList<>();
            JsonNode root = objectMapper.readTree(snapshotJson);
            for (JsonNode zone : root.path("zones")) {
                for (JsonNode window : zone.path("windows")) {
                    for (JsonNode v : window.path("violations")) {
                        violations.add(objectMapper.treeToValue(v, WeatherViolationDto.class));
                    }
                }
            }
            return violations;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("无法解析审核气象快照", ex);
        }
    }

    private long nowMillis() {
        return clock.instant().toEpochMilli();
    }
}