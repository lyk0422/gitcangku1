package com.example.starter.service;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.BatchReviewItemDto;
import com.example.starter.api.dto.BatchReviewRequest;
import com.example.starter.api.dto.BatchReviewResult;
import com.example.starter.api.dto.ForecastDetailDto;
import com.example.starter.api.dto.ForecastEntryDto;
import com.example.starter.api.dto.ForecastUploadRequest;
import com.example.starter.api.dto.ForecastUploadResult;
import com.example.starter.api.dto.MinimaWindowDto;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.OccupancyRequest;
import com.example.starter.api.dto.OccupancyResult;
import com.example.starter.api.dto.ReviewDiagnosticsDto;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.ReviewResultDto;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.RouteReplaceRequest;
import com.example.starter.api.dto.RouteResult;
import com.example.starter.api.dto.WeatherRegionConfigRequest;
import com.example.starter.api.dto.WeatherRegionDetailDto;
import com.example.starter.api.dto.WeatherRegionResult;
import com.example.starter.api.dto.WeatherViolationDto;
import com.example.starter.api.dto.ZoneCreateRequest;
import com.example.starter.api.dto.ZoneResult;
import com.example.starter.api.dto.ZoneRevokeRequest;
import com.example.starter.domain.Geometry;
import com.example.starter.domain.Point;
import com.example.starter.domain.ReviewConclusion;
import com.example.starter.domain.ZoneStatus;
import com.example.starter.repo.AirspaceRepository;
import com.example.starter.repo.DedupPo;
import com.example.starter.repo.ForecastEntryPo;
import com.example.starter.repo.MinimaWindowPo;
import com.example.starter.repo.OccupancyPo;
import com.example.starter.repo.ReviewPo;
import com.example.starter.repo.ReviewRepository;
import com.example.starter.repo.RoutePo;
import com.example.starter.repo.RouteRepository;
import com.example.starter.repo.WeatherRegionPo;
import com.example.starter.repo.WeatherRepository;
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
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 禁飞区、气象最低标准与航线审查业务服务。
 *
 * <p>所有写操作在同一数据库事务内完成业务变更与幂等去重记录的原子提交；
 * 失败（含业务冲突）回滚事务，不占用 requestId。</p>
 *
 * <p>审核事务先锁全局协调行、再锁航线行：区域变更、气象标准配置、预测上传
 * 与占用创建事务都必须先更新协调行，因而与审核互斥，保证审核使用的空域版本、
 * 气象值班版本、全部禁飞区与气象标准来自同一已提交状态，按提交顺序裁决。</p>
 *
 * <p>审核快照固化气象标准、预测与值班版本；后续气象修订只影响新审核，
 * 旧 CLEAR 结果不被改写，但当前查询与占用创建会将其判定为 STALE。</p>
 */
@Service
public class AirspaceReviewService {

    static final String KIND_ZONE_CREATE = "ZONE_CREATE";
    static final String KIND_ZONE_REVOKE = "ZONE_REVOKE";
    static final String KIND_ROUTE_CREATE = "ROUTE_CREATE";
    static final String KIND_ROUTE_REPLACE = "ROUTE_REPLACE";
    static final String KIND_REVIEW = "REVIEW";
    static final String KIND_REVIEW_BATCH = "REVIEW_BATCH";
    static final String KIND_WEATHER_REGION_CONFIGURE = "WEATHER_REGION_CONFIGURE";
    static final String KIND_FORECAST_UPLOAD = "FORECAST_UPLOAD";
    static final String KIND_OCCUPANCY_CREATE = "OCCUPANCY_CREATE";

    private final AirspaceRepository airspaceRepo;
    private final RouteRepository routeRepo;
    private final ReviewRepository reviewRepo;
    private final WeatherRepository weatherRepo;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate txTemplate;

    public AirspaceReviewService(AirspaceRepository airspaceRepo,
                                 RouteRepository routeRepo,
                                 ReviewRepository reviewRepo,
                                 WeatherRepository weatherRepo,
                                 ObjectMapper objectMapper,
                                 Clock clock,
                                 PlatformTransactionManager transactionManager) {
        this.airspaceRepo = airspaceRepo;
        this.routeRepo = routeRepo;
        this.reviewRepo = reviewRepo;
        this.weatherRepo = weatherRepo;
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
    // ============================ 航线 ============================

    /** 创建航线（初始版本 1）。 */
    public MutationResponse createRoute(RouteCreateRequest request) {
        return withIdempotency(request.requestId(), KIND_ROUTE_CREATE, canonicalHash(request), () -> {
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
        return withIdempotency(request.requestId(), KIND_ROUTE_REPLACE, canonicalHash(request), () -> {
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
        return withIdempotency(request.requestId(), KIND_REVIEW, canonicalHash(request), () -> {
            // 先锁协调行：与任何区域/气象变更事务互斥，按提交顺序裁决
            airspaceRepo.getGlobalVersionForUpdate();
            ReviewPo po = prepareReview(request.routeId(), request.routeVersion(),
                    request.airspaceVersion(), request.forecastVersion(), request.requestId());
            reviewRepo.insertReview(po);
            return new MutationResponse(request.requestId(), false,
                    toDto(po, po.conclusion(), true));
        });
    }

    /**
     * 批量审核：先对全部航线校验并生成完整结果，再在同一事务统一落库；
     * 任一航线缺预测或校验失败则整批不创建任何结果，失败原因可区分（携带 routeId）。
     */
    public MutationResponse reviewBatch(BatchReviewRequest request) {
        return withIdempotency(request.requestId(), KIND_REVIEW_BATCH, canonicalHash(request), () -> {
            airspaceRepo.getGlobalVersionForUpdate();
            List<ReviewPo> prepared = new ArrayList<>();
            for (BatchReviewItemDto item : request.items()) {
                try {
                    prepared.add(prepareReview(item.routeId(), item.routeVersion(),
                            item.airspaceVersion(), request.forecastVersion(), request.requestId()));
                } catch (ApiException ex) {
                    throw new ApiException(ex.status(), ex.code(),
                            "航线 " + item.routeId() + ": " + ex.getMessage());
                }
            }
            List<ReviewResultDto> results = new ArrayList<>();
            for (ReviewPo po : prepared) {
                reviewRepo.insertReview(po);
                results.add(toDto(po, po.conclusion(), true));
            }
            return new MutationResponse(request.requestId(), false,
                    new BatchReviewResult(request.items().size(), results.size(),
                            List.copyOf(results)));
        });
    }

    /**
     * 在持锁事务内生成一条审核结果（不落库）：校验航线与空域版本、判定禁飞命中、
     * 评估气象标准，并固化气象标准、预测与值班版本快照。
     */
    private ReviewPo prepareReview(String routeId, int routeVersion, long airspaceVersion,
                                   Long forecastVersion, String requestId) {
        long globalVersion = airspaceRepo.getGlobalVersion();
        // 锁航线行：与航线替换事务互斥，保证版本号与点列一致
        RoutePo route = routeRepo.findRouteForUpdate(routeId);
        if (route == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND",
                    "航线不存在: " + routeId);
        }
        if (route.version() != routeVersion) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                    "航线版本不是当前版本：submitted=" + routeVersion
                            + ", current=" + route.version());
        }
        if (globalVersion != airspaceVersion) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                    "空域版本不是当前版本：submitted=" + airspaceVersion
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
        long dutyVersion = weatherRepo.getDutyVersion();
        WeatherEvaluation weather = evaluateWeather(route.points(), forecastVersion, dutyVersion);
        String conclusion;
        if (!weather.violations().isEmpty()) {
            conclusion = ReviewConclusion.WEATHER_BLOCKED.name();
        } else if (!hits.isEmpty()) {
            conclusion = ReviewConclusion.BLOCKED.name();
        } else {
            conclusion = ReviewConclusion.CLEAR.name();
        }
        String reviewId = "rv_" + UUID.randomUUID();
        return new ReviewPo(reviewId, routeId, route.version(), globalVersion,
                conclusion, new ArrayList<>(hits), List.copyOf(route.points()),
                dutyVersion, forecastVersion, weather.snapshotJson(),
                requestId, nowMillis());
    }
    /**
     * 评估航线路径经过的气象区域：任一预测值低于该时段最低标准即产生违规明细。
     * 路径不经过任何气象区域时不需要预测版本；经过但预测缺失返回 422。
     */
    private WeatherEvaluation evaluateWeather(List<Point> points, Long forecastVersion,
                                              long dutyVersion) {
        List<WeatherRegionPo> crossed = new ArrayList<>();
        for (WeatherRegionPo region : weatherRepo.findAllRegions()) {
            if (Geometry.polylineHitsRectangle(points,
                    region.xMin(), region.yMin(), region.xMax(), region.yMax())) {
                crossed.add(region);
            }
        }
        if (crossed.isEmpty()) {
            return new WeatherEvaluation(List.of(), null);
        }
        if (forecastVersion == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "FORECAST_MISSING",
                    "路径经过气象区域但未提交预测气象版本");
        }
        if (forecastVersion < 1 || forecastVersion > weatherRepo.getLatestForecastVersion()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "FORECAST_MISSING",
                    "预测气象版本不存在: " + forecastVersion);
        }
        List<WeatherViolationDto> violations = new ArrayList<>();
        ArrayNode regionsNode = JsonNodeFactory.instance.arrayNode();
        for (WeatherRegionPo region : crossed) {
            for (MinimaWindowPo window : weatherRepo.findWindows(region.regionId())) {
                ForecastEntryPo forecast = weatherRepo.findForecastEntry(
                        forecastVersion, region.regionId(), window.windowStart());
                if (forecast == null || forecast.windowEnd() != window.windowEnd()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "FORECAST_MISSING",
                            "预测版本 " + forecastVersion + " 缺少区域 " + region.regionId()
                                    + " 时段 [" + window.windowStart() + ", " + window.windowEnd()
                                    + ") 的预测");
                }
                ObjectNode regionNode = regionsNode.addObject();
                regionNode.put("regionId", region.regionId());
                regionNode.put("windowStartUtc", window.windowStart());
                regionNode.put("windowEndUtc", window.windowEnd());
                regionNode.put("visibilityMinM", window.visibilityMinM());
                regionNode.put("cloudBaseMinM", window.cloudBaseMinM());
                regionNode.put("forecastVisibilityM", forecast.visibilityM());
                regionNode.put("forecastCloudBaseM", forecast.cloudBaseM());
                if (forecast.visibilityM() < window.visibilityMinM()) {
                    violations.add(new WeatherViolationDto(region.regionId(),
                            window.windowStart(), window.windowEnd(), "VISIBILITY",
                            forecast.visibilityM(), window.visibilityMinM(),
                            window.visibilityMinM() - forecast.visibilityM()));
                }
                if (forecast.cloudBaseM() < window.cloudBaseMinM()) {
                    violations.add(new WeatherViolationDto(region.regionId(),
                            window.windowStart(), window.windowEnd(), "CLOUD_BASE",
                            forecast.cloudBaseM(), window.cloudBaseMinM(),
                            window.cloudBaseMinM() - forecast.cloudBaseM()));
                }
            }
        }
        ObjectNode snapshot = JsonNodeFactory.instance.objectNode();
        snapshot.put("dutyVersion", dutyVersion);
        snapshot.put("forecastVersion", forecastVersion);
        snapshot.set("regions", regionsNode);
        ArrayNode violationsNode = snapshot.putArray("violations");
        for (WeatherViolationDto v : violations) {
            violationsNode.add(objectMapper.valueToTree(v));
        }
        return new WeatherEvaluation(List.copyOf(violations), writeJson(snapshot));
    }

    /** 气象评估结果：违规明细（按 regionId、时段起点、指标排序）与固化快照 JSON。 */
    private record WeatherEvaluation(List<WeatherViolationDto> violations, String snapshotJson) {
    }

    // ============================ 气象标准与预测 ============================

    /**
     * 配置（或修订）气象区域：负值标准、非法或重叠时段返回 422；
     * 成功后气象值班版本加一，只影响新审核。
     */
    public MutationResponse configureWeatherRegion(WeatherRegionConfigRequest request) {
        return withIdempotency(request.requestId(), KIND_WEATHER_REGION_CONFIGURE,
                canonicalHash(request), () -> {
            if (request.xMin() >= request.xMax() || request.yMin() >= request.yMax()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REGION_RECTANGLE",
                        "气象区域必须是非退化轴对齐矩形：xMin < xMax 且 yMin < yMax");
            }
            List<MinimaWindowDto> windows = new ArrayList<>(request.windows());
            for (MinimaWindowDto w : windows) {
                if (w.windowStartUtc() >= w.windowEndUtc()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "WEATHER_WINDOW_INVALID",
                            "时段必须满足 windowStartUtc < windowEndUtc（左闭右开）: " + w.windowStartUtc());
                }
                if (w.visibilityMinM() < 0 || w.cloudBaseMinM() < 0) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "WEATHER_MINIMA_NEGATIVE",
                            "能见度与云底最低标准必须非负: " + w.visibilityMinM()
                                    + ", " + w.cloudBaseMinM());
                }
            }
            // 左闭右开区间：下一窗口起点早于上一窗口终点即重叠（相邻首尾相等合法）
            windows.sort(Comparator.comparingLong(MinimaWindowDto::windowStartUtc));
            for (int i = 1; i < windows.size(); i++) {
                if (windows.get(i).windowStartUtc() < windows.get(i - 1).windowEndUtc()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "WEATHER_WINDOW_OVERLAP",
                            "同一区域内时段重叠: [" + windows.get(i - 1).windowStartUtc() + ", "
                                    + windows.get(i - 1).windowEndUtc() + ") 与 ["
                                    + windows.get(i).windowStartUtc() + ", "
                                    + windows.get(i).windowEndUtc() + ")");
                }
            }
            long dutyVersion = weatherRepo.incrementDutyVersion();
            weatherRepo.upsertRegion(new WeatherRegionPo(request.regionId(),
                    request.xMin(), request.yMin(), request.xMax(), request.yMax(),
                    dutyVersion, nowMillis()));
            weatherRepo.deleteWindows(request.regionId());
            for (MinimaWindowDto w : windows) {
                weatherRepo.insertWindow(new MinimaWindowPo(request.regionId(),
                        w.windowStartUtc(), w.windowEndUtc(),
                        w.visibilityMinM(), w.cloudBaseMinM(), dutyVersion));
            }
            return new MutationResponse(request.requestId(), false,
                    new WeatherRegionResult(request.regionId(), dutyVersion, windows.size()));
        });
    }

    /** 上传预测气象：整体固化为新版本；未知区域、负值或重复条目返回 422。 */
    public MutationResponse uploadForecast(ForecastUploadRequest request) {
        return withIdempotency(request.requestId(), KIND_FORECAST_UPLOAD, canonicalHash(request), () -> {
            // 先校验完整最终状态，再事务提交：全部条目合法后才分配版本并写入
            record EntryKey(String regionId, long windowStart) {
            }
            Set<EntryKey> seen = new HashSet<>();
            for (ForecastEntryDto e : request.entries()) {
                if (e.windowStartUtc() >= e.windowEndUtc()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "WEATHER_WINDOW_INVALID",
                            "时段必须满足 windowStartUtc < windowEndUtc（左闭右开）: " + e.regionId());
                }
                if (e.visibilityM() < 0 || e.cloudBaseM() < 0) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "WEATHER_FORECAST_NEGATIVE",
                            "预测能见度与云底必须非负: " + e.regionId());
                }
                if (weatherRepo.findRegion(e.regionId()) == null) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "FORECAST_REGION_UNKNOWN",
                            "预测引用了未知气象区域: " + e.regionId());
                }
                if (!seen.add(new EntryKey(e.regionId(), e.windowStartUtc()))) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "FORECAST_ENTRY_DUPLICATE",
                            "同一区域内时段起点重复: " + e.regionId() + " " + e.windowStartUtc());
                }
            }
            long forecastVersion = weatherRepo.incrementForecastVersion();
            for (ForecastEntryDto e : request.entries()) {
                weatherRepo.insertForecastEntry(new ForecastEntryPo(forecastVersion, e.regionId(),
                        e.windowStartUtc(), e.windowEndUtc(), e.visibilityM(), e.cloudBaseM()));
            }
            return new MutationResponse(request.requestId(), false,
                    new ForecastUploadResult(forecastVersion, request.entries().size()));
        });
    }
    // ============================ 空域占用 ============================

    /**
     * 创建空域占用：只允许引用仍为当前结论的 CLEAR 审核；
     * 占用固化审核时的气象版本，STALE 审核拒绝占用（409）。
     */
    public MutationResponse createOccupancy(OccupancyRequest request) {
        return withIdempotency(request.requestId(), KIND_OCCUPANCY_CREATE, canonicalHash(request), () -> {
            // 加协调锁：与审核、气象标准配置、预测上传按提交顺序串行裁决
            airspaceRepo.getGlobalVersionForUpdate();
            ReviewPo review = reviewRepo.findReview(request.reviewId());
            if (review == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND",
                        "审核记录不存在: " + request.reviewId());
            }
            if (!ReviewConclusion.CLEAR.name().equals(review.conclusion())) {
                throw new ApiException(HttpStatus.CONFLICT, "OCCUPANCY_NOT_CLEAR",
                        "审核结论为 " + review.conclusion() + "，不能创建占用");
            }
            List<String> reasons = stalenessReasons(review);
            if (!reasons.isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, "OCCUPANCY_REVIEW_STALE",
                        "审核结果已失效（" + String.join(", ", reasons) + "），请重新审核后再占用");
            }
            if (weatherRepo.findOccupancyByReview(review.reviewId()) != null) {
                throw new ApiException(HttpStatus.CONFLICT, "OCCUPANCY_ALREADY_EXISTS",
                        "该审核已创建过占用: " + review.reviewId());
            }
            OccupancyPo po = new OccupancyPo("oc_" + UUID.randomUUID(), review.reviewId(),
                    review.routeId(), review.routeVersion(), review.airspaceVersion(),
                    review.dutyVersion(), review.forecastVersion(),
                    request.operator(), request.requestId(), nowMillis());
            weatherRepo.insertOccupancy(po);
            return new MutationResponse(request.requestId(), false, toOccupancyResult(po));
        });
    }

    // ============================ 查询（只读，不改变状态） ============================

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

    /** 查询某航线全部审核历史（按创建时间、reviewId 倒序，排序稳定）。 */
    public List<ReviewResultDto> getRouteReviewHistory(String routeId) {
        List<ReviewResultDto> history = new ArrayList<>();
        for (ReviewPo po : reviewRepo.findReviewsByRoute(routeId)) {
            history.add(toDto(po, po.conclusion(), null));
        }
        return List.copyOf(history);
    }

    /** 查询某航线当前可用结论；任何相关版本不再匹配返回 STALE。 */
    public ReviewResultDto getCurrentReview(String routeId) {
        return txTemplate.execute(status -> {
            ReviewPo latest = reviewRepo.findLatestReview(routeId);
            if (latest == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND",
                        "该航线尚无审核记录: " + routeId);
            }
            boolean current = stalenessReasons(latest).isEmpty();
            // 不匹配时绝不能把旧 CLEAR/BLOCKED 当成当前结论，统一返回 STALE
            String conclusion = current ? latest.conclusion() : "STALE";
            return toDto(latest, conclusion, current);
        });
    }

    /** 审核时效诊断：固化版本与当前版本对照及失效原因；只读。 */
    public ReviewDiagnosticsDto getReviewDiagnostics(String reviewId) {
        return txTemplate.execute(status -> {
            ReviewPo po = reviewRepo.findReview(reviewId);
            if (po == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND",
                        "审核记录不存在: " + reviewId);
            }
            List<String> reasons = stalenessReasons(po);
            RoutePo currentRoute = routeRepo.findRoute(po.routeId());
            long latestForecast = weatherRepo.getLatestForecastVersion();
            Object snapshot = null;
            if (po.weatherSnapshot() != null) {
                try {
                    snapshot = objectMapper.readValue(po.weatherSnapshot(), Object.class);
                } catch (JsonProcessingException ex) {
                    throw new IllegalStateException("无法解析气象快照: " + reviewId, ex);
                }
            }
            return new ReviewDiagnosticsDto(po.reviewId(), po.routeId(), po.conclusion(),
                    reasons.isEmpty(), List.copyOf(reasons),
                    po.routeVersion(), currentRoute == null ? null : currentRoute.version(),
                    po.airspaceVersion(), airspaceRepo.getGlobalVersion(),
                    po.dutyVersion(), weatherRepo.getDutyVersion(),
                    po.forecastVersion(), latestForecast == 0 ? null : latestForecast,
                    snapshot);
        });
    }

    /** 查询气象区域明细（含当前最低标准窗口，按时段起点升序）。 */
    public WeatherRegionDetailDto getWeatherRegion(String regionId) {
        WeatherRegionPo region = weatherRepo.findRegion(regionId);
        if (region == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "WEATHER_REGION_NOT_FOUND",
                    "气象区域不存在: " + regionId);
        }
        List<MinimaWindowDto> windows = new ArrayList<>();
        for (MinimaWindowPo w : weatherRepo.findWindows(regionId)) {
            windows.add(new MinimaWindowDto(w.windowStart(), w.windowEnd(),
                    w.visibilityMinM(), w.cloudBaseMinM()));
        }
        return new WeatherRegionDetailDto(region.regionId(), region.xMin(), region.yMin(),
                region.xMax(), region.yMax(), region.dutyVersion(), List.copyOf(windows));
    }

    /** 查询某预测气象版本明细（条目按 regionId、时段起点升序）。 */
    public ForecastDetailDto getForecast(long forecastVersion) {
        long latest = weatherRepo.getLatestForecastVersion();
        if (forecastVersion < 1 || forecastVersion > latest) {
            throw new ApiException(HttpStatus.NOT_FOUND, "FORECAST_NOT_FOUND",
                    "预测气象版本不存在: " + forecastVersion);
        }
        List<ForecastEntryDto> entries = new ArrayList<>();
        for (ForecastEntryPo e : weatherRepo.findForecastEntries(forecastVersion)) {
            entries.add(new ForecastEntryDto(e.regionId(), e.windowStart(), e.windowEnd(),
                    e.visibilityM(), e.cloudBaseM()));
        }
        return new ForecastDetailDto(forecastVersion, forecastVersion == latest,
                List.copyOf(entries));
    }

    /** 按 occupancyId 查询占用记录。 */
    public OccupancyResult getOccupancy(String occupancyId) {
        OccupancyPo po = weatherRepo.findOccupancy(occupancyId);
        if (po == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "OCCUPANCY_NOT_FOUND",
                    "占用记录不存在: " + occupancyId);
        }
        return toOccupancyResult(po);
    }

    /**
     * 计算审核结果的失效原因（只读）：航线、空域、气象值班或预测版本任一
     * 与审核时固化的版本不再一致即失效；空列表表示仍是当前结论。
     */
    private List<String> stalenessReasons(ReviewPo po) {
        List<String> reasons = new ArrayList<>();
        RoutePo currentRoute = routeRepo.findRoute(po.routeId());
        if (currentRoute == null || currentRoute.version() != po.routeVersion()) {
            reasons.add("ROUTE_VERSION_CHANGED");
        }
        if (airspaceRepo.getGlobalVersion() != po.airspaceVersion()) {
            reasons.add("AIRSPACE_VERSION_CHANGED");
        }
        if (weatherRepo.getDutyVersion() != po.dutyVersion()) {
            reasons.add("WEATHER_DUTY_VERSION_CHANGED");
        }
        long latestForecast = weatherRepo.getLatestForecastVersion();
        Long frozen = po.forecastVersion();
        if (frozen == null ? latestForecast != 0 : frozen.longValue() != latestForecast) {
            reasons.add("FORECAST_VERSION_CHANGED");
        }
        return reasons;
    }

    private static OccupancyResult toOccupancyResult(OccupancyPo po) {
        return new OccupancyResult(po.occupancyId(), po.reviewId(), po.routeId(),
                po.routeVersion(), po.airspaceVersion(), po.dutyVersion(),
                po.forecastVersion(), po.createdAt());
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

    private ReviewResultDto toDto(ReviewPo po, String conclusion, Boolean current) {
        List<RoutePointDto> snapshot = new ArrayList<>(po.pointsSnapshot().size());
        for (Point p : po.pointsSnapshot()) {
            snapshot.add(new RoutePointDto(p.x(), p.y()));
        }
        // null 语义稳定：路径不经过气象区域的审核没有气象快照，违规明细为 null
        List<WeatherViolationDto> violations =
                po.weatherSnapshot() == null ? null : parseViolations(po.weatherSnapshot());
        return new ReviewResultDto(po.reviewId(), po.routeId(), po.routeVersion(),
                po.airspaceVersion(), conclusion, List.copyOf(po.hitZoneIds()), snapshot, current,
                po.dutyVersion(), po.forecastVersion(), violations);
    }

    private List<WeatherViolationDto> parseViolations(String snapshotJson) {
        try {
            JsonNode node = objectMapper.readTree(snapshotJson).path("violations");
            List<WeatherViolationDto> list = new ArrayList<>();
            if (node.isArray()) {
                for (JsonNode v : node) {
                    list.add(new WeatherViolationDto(v.path("regionId").asText(),
                            v.path("windowStartUtc").asLong(), v.path("windowEndUtc").asLong(),
                            v.path("metric").asText(), v.path("actualM").asInt(),
                            v.path("requiredM").asInt(), v.path("deficitM").asInt()));
                }
            }
            return list;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("无法解析气象快照", ex);
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
     * 计算请求参数的规范化哈希（SHA-256），作为幂等指纹。
     * 指纹覆盖操作者、操作类型、规范化集合与全部结果相关字段：
     * 对象字段按键名字典序递归排序，列表保持顺序，使相同语义参数产生相同哈希、
     * 字段书写顺序不同不影响比对结果；基于 JSON 结构而非分隔符拼接，
     * 不存在未转义分隔符导致的指纹碰撞。
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