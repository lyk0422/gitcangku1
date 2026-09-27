package com.example.starter.service;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.BatchReviewItemDto;
import com.example.starter.api.dto.BatchReviewRequest;
import com.example.starter.api.dto.BatchReviewResult;
import com.example.starter.api.dto.DutyAdvanceRequest;
import com.example.starter.api.dto.DutyAdvanceResult;
import com.example.starter.api.dto.ForecastEntryDto;
import com.example.starter.api.dto.ForecastUploadRequest;
import com.example.starter.api.dto.ForecastUploadResult;
import com.example.starter.api.dto.ForecastView;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.OccupationCreateRequest;
import com.example.starter.api.dto.OccupationResult;
import com.example.starter.api.dto.ReviewDiagnosticsDto;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.ReviewResultDto;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.RouteReplaceRequest;
import com.example.starter.api.dto.RouteResult;
import com.example.starter.api.dto.VersionDiagnosticDto;
import com.example.starter.api.dto.WeatherMinimaConfigRequest;
import com.example.starter.api.dto.WeatherMinimaResult;
import com.example.starter.api.dto.WeatherViolationDto;
import com.example.starter.api.dto.WeatherWindowDto;
import com.example.starter.api.dto.ZoneCreateRequest;
import com.example.starter.api.dto.ZoneResult;
import com.example.starter.api.dto.ZoneRevokeRequest;
import com.example.starter.domain.Geometry;
import com.example.starter.domain.Point;
import com.example.starter.domain.ReviewConclusion;
import com.example.starter.domain.WeatherViolation;
import com.example.starter.domain.ZoneStatus;
import com.example.starter.repo.AirspaceRepository;
import com.example.starter.repo.DedupPo;
import com.example.starter.repo.OccupationPo;
import com.example.starter.repo.ReviewPo;
import com.example.starter.repo.ReviewRepository;
import com.example.starter.repo.RoutePo;
import com.example.starter.repo.RouteRepository;
import com.example.starter.repo.WeatherForecastPo;
import com.example.starter.repo.WeatherMinimaPo;
import com.example.starter.repo.WeatherRepository;
import com.example.starter.repo.WeatherVersions;
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
 * 失败（含业务冲突与 422 校验失败）回滚事务，不占用 requestId。</p>
 *
 * <p>审核事务先锁全局协调行、再锁航线行：区域变更、气象标准配置、预测上传与
 * 换班事务都必须先更新同一协调行，因而与审核互斥，保证审核使用的全部版本号
 * （空域/气象标准/预测/值班）与底层数据来自同一已提交状态。</p>
 *
 * <p>区域分两类：未配置气象最低标准的区域为绝对禁飞区（命中即 BLOCKED）；
 * 配置了标准的区域为条件区域，任一时段预测低于标准即 WEATHER_BLOCKED。
 * 结论优先级：BLOCKED &gt; WEATHER_BLOCKED &gt; CLEAR。所有时段为 UTC 一日内
 * 分钟，左闭右开 [start, end)。</p>
 */
@Service
public class AirspaceReviewService {

    static final String KIND_ZONE_CREATE = "ZONE_CREATE";
    static final String KIND_ZONE_REVOKE = "ZONE_REVOKE";
    static final String KIND_ROUTE_CREATE = "ROUTE_CREATE";
    static final String KIND_ROUTE_REPLACE = "ROUTE_REPLACE";
    static final String KIND_REVIEW = "REVIEW";
    static final String KIND_REVIEW_BATCH = "REVIEW_BATCH";
    static final String KIND_WEATHER_MINIMA_CONFIG = "WEATHER_MINIMA_CONFIG";
    static final String KIND_FORECAST_UPLOAD = "FORECAST_UPLOAD";
    static final String KIND_DUTY_ADVANCE = "DUTY_ADVANCE";
    static final String KIND_OCCUPATION_CREATE = "OCCUPATION_CREATE";

    /** UTC 时段上界（一日分钟数），左闭右开区间允许 end = 1440。 */
    static final int DAY_MINUTES = 1440;

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

    // ============================ 气象最低标准 ============================

    /**
     * 配置/修订区域气象最低标准：整组替换该区域的标准时段并推进气象标准版本。
     * 标准负值、时段非法或重叠返回 422；区域不存在返回 404。
     */
    public MutationResponse configureMinima(WeatherMinimaConfigRequest request) {
        return withIdempotency(request.requestId(), KIND_WEATHER_MINIMA_CONFIG,
                canonicalHash(request), () -> {
                    if (airspaceRepo.findZone(request.zoneId()) == null) {
                        throw new ApiException(HttpStatus.NOT_FOUND, "ZONE_NOT_FOUND",
                                "区域不存在: " + request.zoneId());
                    }
                    List<WeatherWindowDto> windows = new ArrayList<>(request.windows());
                    windows.sort(Comparator.comparing(WeatherWindowDto::startMinute)
                            .thenComparing(WeatherWindowDto::endMinute));
                    validateMinimaWindows(request.zoneId(), windows);
                    long newVersion = weatherRepo.incrementWeatherVersion();
                    List<WeatherMinimaPo> pos = new ArrayList<>(windows.size());
                    for (WeatherWindowDto w : windows) {
                        pos.add(new WeatherMinimaPo(request.zoneId(), w.startMinute(), w.endMinute(),
                                w.minVisibilityM(), w.minCloudBaseM(), newVersion));
                    }
                    weatherRepo.replaceMinima(request.zoneId(), pos);
                    return new MutationResponse(request.requestId(), false,
                            new WeatherMinimaResult(request.zoneId(), newVersion,
                                    List.copyOf(windows)));
                });
    }

    /** 查询某区域当前生效的气象最低标准（明细查询，稳定排序）。 */
    public WeatherMinimaResult getMinima(String zoneId) {
        if (airspaceRepo.findZone(zoneId) == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ZONE_NOT_FOUND",
                    "区域不存在: " + zoneId);
        }
        List<WeatherMinimaPo> pos = weatherRepo.findMinima(zoneId);
        List<WeatherWindowDto> windows = new ArrayList<>(pos.size());
        Long version = null;
        for (WeatherMinimaPo po : pos) {
            windows.add(new WeatherWindowDto(po.windowStart(), po.windowEnd(),
                    po.minVisibilityM(), po.minCloudBaseM()));
            version = po.minimaVersion();
        }
        return new WeatherMinimaResult(zoneId, version, List.copyOf(windows));
    }

    private static void validateMinimaWindows(String zoneId, List<WeatherWindowDto> sorted) {
        WeatherWindowDto prev = null;
        for (WeatherWindowDto w : sorted) {
            if (w.startMinute() < 0 || w.endMinute() > DAY_MINUTES
                    || w.startMinute() >= w.endMinute()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "WEATHER_WINDOW_INVALID",
                        "区域 " + zoneId + " 的时段非法：[" + w.startMinute() + ", " + w.endMinute()
                                + ")，要求 0 <= start < end <= " + DAY_MINUTES);
            }
            if (w.minVisibilityM() < 0 || w.minCloudBaseM() < 0) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "WEATHER_MINIMA_NEGATIVE",
                        "区域 " + zoneId + " 的最低标准不允许负值：minVisibilityM="
                                + w.minVisibilityM() + ", minCloudBaseM=" + w.minCloudBaseM());
            }
            // 左闭右开区间：前一段 end <= 后一段 start 才不重叠
            if (prev != null && prev.endMinute() > w.startMinute()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "WEATHER_WINDOW_OVERLAP",
                        "区域 " + zoneId + " 的时段重叠：[" + prev.startMinute() + ", "
                                + prev.endMinute() + ") 与 [" + w.startMinute() + ", "
                                + w.endMinute() + ")");
            }
            prev = w;
        }
    }

    // ============================ 预测气象 ============================

    /**
     * 上传预测气象：整批在一个事务内写入并分配新的全局预测版本。
     * 预测负值或时段非法返回 422；区域不存在返回 404；失败整批不生效。
     */
    public MutationResponse uploadForecast(ForecastUploadRequest request) {
        return withIdempotency(request.requestId(), KIND_FORECAST_UPLOAD, canonicalHash(request), () -> {
            List<ForecastEntryDto> entries = new ArrayList<>(request.entries());
            entries.sort(Comparator.comparing(ForecastEntryDto::zoneId)
                    .thenComparing(ForecastEntryDto::startMinute)
                    .thenComparing(ForecastEntryDto::endMinute));
            ForecastEntryDto prev = null;
            for (ForecastEntryDto e : entries) {
                if (airspaceRepo.findZone(e.zoneId()) == null) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "ZONE_NOT_FOUND",
                            "预测覆盖的区域不存在: " + e.zoneId());
                }
                if (e.startMinute() < 0 || e.endMinute() > DAY_MINUTES
                        || e.startMinute() >= e.endMinute()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "FORECAST_WINDOW_INVALID",
                            "区域 " + e.zoneId() + " 的预测时段非法：[" + e.startMinute() + ", "
                                    + e.endMinute() + ")，要求 0 <= start < end <= " + DAY_MINUTES);
                }
                if (e.visibilityM() < 0 || e.cloudBaseM() < 0) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "FORECAST_NEGATIVE",
                            "区域 " + e.zoneId() + " 的预测值不允许负值：visibilityM="
                                    + e.visibilityM() + ", cloudBaseM=" + e.cloudBaseM());
                }
                if (prev != null && prev.zoneId().equals(e.zoneId())
                        && prev.startMinute().equals(e.startMinute())) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "FORECAST_DUPLICATE_WINDOW",
                            "区域 " + e.zoneId() + " 的预测时段起点重复: " + e.startMinute());
                }
                prev = e;
            }
            long newVersion = weatherRepo.incrementForecastVersion();
            List<WeatherForecastPo> pos = new ArrayList<>(entries.size());
            for (ForecastEntryDto e : entries) {
                pos.add(new WeatherForecastPo(newVersion, e.zoneId(), e.startMinute(), e.endMinute(),
                        e.visibilityM(), e.cloudBaseM(), nowMillis()));
            }
            weatherRepo.insertForecasts(pos);
            return new MutationResponse(request.requestId(), false,
                    new ForecastUploadResult(newVersion, entries.size()));
        });
    }

    /** 按版本查询预测明细（稳定排序）；版本不存在返回 404。 */
    public ForecastView getForecast(long forecastVersion) {
        List<WeatherForecastPo> pos = weatherRepo.findForecastVersion(forecastVersion);
        if (pos.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "FORECAST_NOT_FOUND",
                    "预测版本不存在: " + forecastVersion);
        }
        List<ForecastEntryDto> entries = new ArrayList<>(pos.size());
        for (WeatherForecastPo po : pos) {
            entries.add(new ForecastEntryDto(po.zoneId(), po.windowStart(), po.windowEnd(),
                    po.visibilityM(), po.cloudBaseM()));
        }
        return new ForecastView(forecastVersion, List.copyOf(entries));
    }

    // ============================ 值班 ============================

    /** 换班：值班版本加一；此后旧审核结果的时效诊断标记值班维度 STALE。 */
    public MutationResponse advanceDuty(DutyAdvanceRequest request) {
        return withIdempotency(request.requestId(), KIND_DUTY_ADVANCE, canonicalHash(request), () -> {
            long newVersion = weatherRepo.incrementDutyVersion();
            return new MutationResponse(request.requestId(), false, new DutyAdvanceResult(newVersion));
        });
    }

    // ============================ 审核 ============================

    /** 提交审核：任一指定版本不是当前版本返回 409；结果不可变。 */
    public MutationResponse review(ReviewRequest request) {
        return withIdempotency(request.requestId(), KIND_REVIEW, canonicalHash(request), () -> {
            // 先锁协调行：与任何区域/气象标准/预测/值班变更事务互斥
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
            WeatherVersions versions = weatherRepo.getVersions();
            Long forecastVersion = requireForecastVersionIfSubmitted(request.forecastVersion());
            // 持锁状态下读取全部有效禁飞区与气象数据，与上面的版本号同属一个一致状态
            List<ZonePo> activeZones = airspaceRepo.findActiveZones();
            ReviewPo po = buildReview(route, globalVersion, versions, forecastVersion,
                    activeZones, request.requestId());
            reviewRepo.insertReview(po);
            return new MutationResponse(request.requestId(), false,
                    toDto(po, po.conclusion(), true));
        });
    }

    /**
     * 批量审查：整批在一个事务内校验并创建结果。任一航线不存在、版本不符或
     * 经过条件区域而缺预测（422），整批回滚、不创建任何结果。
     * 条目先按 routeId 排序规范化（去重冲突返回 422），再参与幂等指纹与处理。
     */
    public MutationResponse reviewBatch(BatchReviewRequest request) {
        List<BatchReviewItemDto> sorted = request.items().stream()
                .sorted(Comparator.comparing(BatchReviewItemDto::routeId))
                .toList();
        BatchReviewRequest normalized = new BatchReviewRequest(sorted, request.airspaceVersion(),
                request.forecastVersion(), request.operator(), request.requestId());
        return withIdempotency(request.requestId(), KIND_REVIEW_BATCH, canonicalHash(normalized), () -> {
            for (int i = 1; i < sorted.size(); i++) {
                if (sorted.get(i).routeId().equals(sorted.get(i - 1).routeId())) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DUPLICATE_ROUTE",
                            "批量审查包含重复航线: " + sorted.get(i).routeId());
                }
            }
            long globalVersion = airspaceRepo.getGlobalVersionForUpdate();
            if (globalVersion != request.airspaceVersion()) {
                throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                        "空域版本不是当前版本：submitted=" + request.airspaceVersion()
                                + ", current=" + globalVersion);
            }
            WeatherVersions versions = weatherRepo.getVersions();
            Long forecastVersion = requireForecastVersionIfSubmitted(request.forecastVersion());
            List<ZonePo> activeZones = airspaceRepo.findActiveZones();
            // 先对全部航线完成校验与结论计算，再统一写入：任一失败整批不创建
            List<ReviewPo> reviews = new ArrayList<>(sorted.size());
            for (BatchReviewItemDto item : sorted) {
                RoutePo route = routeRepo.findRouteForUpdate(item.routeId());
                if (route == null) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND",
                            "航线不存在: " + item.routeId());
                }
                if (route.version() != item.routeVersion()) {
                    throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                            "航线 " + item.routeId() + " 版本不是当前版本：submitted="
                                    + item.routeVersion() + ", current=" + route.version());
                }
                // 批量结果行以 requestId#routeId 留痕，保证审核表请求标识唯一
                reviews.add(buildReview(route, globalVersion, versions, forecastVersion,
                        activeZones, request.requestId() + "#" + item.routeId()));
            }
            List<ReviewResultDto> results = new ArrayList<>(reviews.size());
            for (ReviewPo po : reviews) {
                reviewRepo.insertReview(po);
                results.add(toDto(po, po.conclusion(), true));
            }
            return new MutationResponse(request.requestId(), false,
                    new BatchReviewResult(List.copyOf(results)));
        });
    }

    /**
     * 在持锁的一致状态下计算一条航线的审核结论并构造不可变记录。
     * 经过条件区域而预测缺失时抛 422，调用方事务回滚。
     */
    private ReviewPo buildReview(RoutePo route, long globalVersion, WeatherVersions versions,
                                 Long forecastVersion, List<ZonePo> activeZones, String requestId) {
        Set<String> hits = new TreeSet<>();
        List<WeatherViolation> violations = new ArrayList<>();
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("weatherVersion", versions.weatherVersion());
        if (forecastVersion != null) {
            snapshot.put("forecastVersion", forecastVersion);
        } else {
            snapshot.putNull("forecastVersion");
        }
        snapshot.put("dutyVersion", versions.dutyVersion());
        ArrayNode zonesNode = snapshot.putArray("zones");
        for (ZonePo zone : activeZones) {
            if (!Geometry.polylineHitsRectangle(route.points(),
                    zone.xMin(), zone.yMin(), zone.xMax(), zone.yMax())) {
                continue;
            }
            List<WeatherMinimaPo> minima = weatherRepo.findMinima(zone.zoneId());
            if (minima.isEmpty()) {
                // 未配置气象标准：绝对禁飞区
                hits.add(zone.zoneId());
                continue;
            }
            // 条件区域：必须提交预测版本且每个标准时段都有覆盖预测
            if (forecastVersion == null) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "FORECAST_MISSING",
                        "航线经过配置了气象最低标准的区域 " + zone.zoneId()
                                + "，必须提交预测气象版本");
            }
            List<WeatherForecastPo> forecasts =
                    weatherRepo.findForecasts(forecastVersion, zone.zoneId());
            ObjectNode zoneNode = zonesNode.addObject();
            zoneNode.put("zoneId", zone.zoneId());
            ArrayNode minimaNode = zoneNode.putArray("minima");
            ArrayNode forecastNode = zoneNode.putArray("forecasts");
            for (WeatherMinimaPo m : minima) {
                minimaNode.addObject()
                        .put("windowStart", m.windowStart())
                        .put("windowEnd", m.windowEnd())
                        .put("minVisibilityM", m.minVisibilityM())
                        .put("minCloudBaseM", m.minCloudBaseM());
                WeatherForecastPo covering = findCoveringForecast(forecasts, m);
                if (covering == null) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "FORECAST_MISSING",
                            "区域 " + zone.zoneId() + " 时段 [" + m.windowStart() + ", "
                                    + m.windowEnd() + ") 在预测版本 " + forecastVersion + " 中缺预测");
                }
                forecastNode.addObject()
                        .put("windowStart", covering.windowStart())
                        .put("windowEnd", covering.windowEnd())
                        .put("visibilityM", covering.visibilityM())
                        .put("cloudBaseM", covering.cloudBaseM());
                if (covering.visibilityM() < m.minVisibilityM()) {
                    violations.add(new WeatherViolation(zone.zoneId(), m.windowStart(),
                            m.windowEnd(), WeatherViolation.METRIC_VISIBILITY,
                            covering.visibilityM(), m.minVisibilityM()));
                }
                if (covering.cloudBaseM() < m.minCloudBaseM()) {
                    violations.add(new WeatherViolation(zone.zoneId(), m.windowStart(),
                            m.windowEnd(), WeatherViolation.METRIC_CLOUD_BASE,
                            covering.cloudBaseM(), m.minCloudBaseM()));
                }
            }
        }
        // 稳定排序：区域、时段、指标
        violations.sort(Comparator.comparing(WeatherViolation::zoneId)
                .thenComparing(WeatherViolation::windowStartMinute)
                .thenComparing(WeatherViolation::metric));
        String conclusion;
        if (!hits.isEmpty()) {
            conclusion = ReviewConclusion.BLOCKED.name();
        } else if (!violations.isEmpty()) {
            conclusion = ReviewConclusion.WEATHER_BLOCKED.name();
        } else {
            conclusion = ReviewConclusion.CLEAR.name();
        }
        String reviewId = "rv_" + UUID.randomUUID();
        return new ReviewPo(reviewId, route.routeId(), route.version(), globalVersion,
                versions.weatherVersion(), forecastVersion, versions.dutyVersion(),
                conclusion, new ArrayList<>(hits), List.copyOf(violations),
                writeJson(snapshot), List.copyOf(route.points()), requestId, nowMillis());
    }

    /** 查找覆盖标准时段的预测（左闭右开包含）；多个覆盖时取最窄时段，保证确定性。 */
    private static WeatherForecastPo findCoveringForecast(List<WeatherForecastPo> forecasts,
                                                          WeatherMinimaPo minima) {
        WeatherForecastPo best = null;
        for (WeatherForecastPo f : forecasts) {
            if (f.windowStart() <= minima.windowStart() && f.windowEnd() >= minima.windowEnd()) {
                if (best == null
                        || f.windowEnd() - f.windowStart() < best.windowEnd() - best.windowStart()
                        || (f.windowEnd() - f.windowStart() == best.windowEnd() - best.windowStart()
                            && f.windowStart() < best.windowStart())) {
                    best = f;
                }
            }
        }
        return best;
    }

    /** 提交了预测版本则必须存在（422）且为当前最新版本（409）；未提交返回 null。 */
    private Long requireForecastVersionIfSubmitted(Long forecastVersion) {
        if (forecastVersion == null) {
            return null;
        }
        if (!weatherRepo.forecastVersionExists(forecastVersion)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "FORECAST_MISSING",
                    "预测气象版本不存在: " + forecastVersion);
        }
        long current = weatherRepo.getVersions().forecastVersion();
        if (current != forecastVersion) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                    "预测气象版本不是当前版本：submitted=" + forecastVersion
                            + ", current=" + current);
        }
        return forecastVersion;
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

    /** 查询某航线当前可用结论；任何固化版本不再匹配返回 STALE。 */
    public ReviewResultDto getCurrentReview(String routeId) {
        return txTemplate.execute(status -> {
            ReviewPo latest = reviewRepo.findLatestReview(routeId);
            if (latest == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND",
                        "该航线尚无审核记录: " + routeId);
            }
            boolean current = isCurrent(latest);
            // 不匹配时绝不能把旧结论当成当前结论，统一返回 STALE
            String conclusion = current ? latest.conclusion() : "STALE";
            return toDto(latest, conclusion, current);
        });
    }

    /** 查询某航线的全部审核历史（按创建时间、reviewId 升序，稳定排序）。 */
    public List<ReviewResultDto> getReviewHistory(String routeId) {
        if (routeRepo.findRoute(routeId) == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND",
                    "航线不存在: " + routeId);
        }
        List<ReviewResultDto> history = new ArrayList<>();
        for (ReviewPo po : reviewRepo.findReviewsByRoute(routeId)) {
            history.add(toDto(po, po.conclusion(), null));
        }
        return List.copyOf(history);
    }

    /**
     * 审核时效诊断：逐维度对比固化版本与当前版本；原审核结果不被改写。
     * 维度固定顺序：ROUTE、AIRSPACE、WEATHER_MINIMA、FORECAST、DUTY。
     */
    public ReviewDiagnosticsDto getReviewDiagnostics(String reviewId) {
        return txTemplate.execute(status -> {
            ReviewPo po = reviewRepo.findReview(reviewId);
            if (po == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND",
                        "审核记录不存在: " + reviewId);
            }
            RoutePo route = routeRepo.findRoute(po.routeId());
            long globalVersion = airspaceRepo.getGlobalVersion();
            WeatherVersions versions = weatherRepo.getVersions();
            List<VersionDiagnosticDto> dimensions = new ArrayList<>(5);
            dimensions.add(new VersionDiagnosticDto("ROUTE", (long) po.routeVersion(),
                    route == null ? null : (long) route.version(),
                    route == null || route.version() != po.routeVersion()));
            dimensions.add(new VersionDiagnosticDto("AIRSPACE", po.airspaceVersion(),
                    globalVersion, globalVersion != po.airspaceVersion()));
            dimensions.add(new VersionDiagnosticDto("WEATHER_MINIMA", po.weatherVersion(),
                    versions.weatherVersion(), versions.weatherVersion() != po.weatherVersion()));
            // 审核未涉及预测时不因新预测上传而失效
            dimensions.add(new VersionDiagnosticDto("FORECAST", po.forecastVersion(),
                    versions.forecastVersion(),
                    po.forecastVersion() != null
                            && versions.forecastVersion() != po.forecastVersion()));
            dimensions.add(new VersionDiagnosticDto("DUTY", po.dutyVersion(),
                    versions.dutyVersion(), versions.dutyVersion() != po.dutyVersion()));
            boolean stale = dimensions.stream().anyMatch(d -> Boolean.TRUE.equals(d.stale()));
            return new ReviewDiagnosticsDto(po.reviewId(), po.conclusion(), stale,
                    List.copyOf(dimensions));
        });
    }

    /** 判断审核固化版本是否与当前状态一致（非 STALE）。 */
    private boolean isCurrent(ReviewPo po) {
        RoutePo currentRoute = routeRepo.findRoute(po.routeId());
        long globalVersion = airspaceRepo.getGlobalVersion();
        WeatherVersions versions = weatherRepo.getVersions();
        return currentRoute != null
                && currentRoute.version() == po.routeVersion()
                && globalVersion == po.airspaceVersion()
                && versions.weatherVersion() == po.weatherVersion()
                && versions.dutyVersion() == po.dutyVersion()
                && (po.forecastVersion() == null
                    || versions.forecastVersion() == po.forecastVersion());
    }

    // ============================ 占用 ============================

    /**
     * 创建空域占用：仅允许引用结论为 CLEAR 且未失效（非 STALE）的审核记录；
     * 占用固化审核时的气象标准/预测/值班版本。非 CLEAR 或 STALE 返回 409。
     */
    public MutationResponse createOccupation(OccupationCreateRequest request) {
        return withIdempotency(request.requestId(), KIND_OCCUPATION_CREATE, canonicalHash(request), () -> {
            ReviewPo review = reviewRepo.findReview(request.reviewId());
            if (review == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND",
                        "审核记录不存在: " + request.reviewId());
            }
            if (!ReviewConclusion.CLEAR.name().equals(review.conclusion())) {
                throw new ApiException(HttpStatus.CONFLICT, "OCCUPATION_NOT_CLEAR",
                        "仅 CLEAR 审核结果可创建占用，实际结论: " + review.conclusion());
            }
            // 持协调锁读取一致状态，判定审核是否仍有效
            airspaceRepo.getGlobalVersionForUpdate();
            routeRepo.findRouteForUpdate(review.routeId());
            if (!isCurrent(review)) {
                throw new ApiException(HttpStatus.CONFLICT, "OCCUPATION_STALE_REVIEW",
                        "审核结果已失效（STALE），拒绝创建占用: " + request.reviewId());
            }
            if (weatherRepo.findOccupationByReview(review.reviewId()) != null) {
                throw new ApiException(HttpStatus.CONFLICT, "OCCUPATION_ALREADY_EXISTS",
                        "该审核结果已创建过占用: " + request.reviewId());
            }
            String occupationId = "oc_" + UUID.randomUUID();
            OccupationPo po = new OccupationPo(occupationId, review.reviewId(), review.routeId(),
                    review.routeVersion(), review.airspaceVersion(), review.weatherVersion(),
                    review.forecastVersion(), review.dutyVersion(),
                    request.requestId(), nowMillis());
            weatherRepo.insertOccupation(po);
            return new MutationResponse(request.requestId(), false, toOccupationResult(po));
        });
    }

    /** 按 occupationId 查询占用明细，不存在返回 404。 */
    public OccupationResult getOccupation(String occupationId) {
        OccupationPo po = weatherRepo.findOccupation(occupationId);
        if (po == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "OCCUPATION_NOT_FOUND",
                    "占用记录不存在: " + occupationId);
        }
        return toOccupationResult(po);
    }

    /** 查询某航线的全部占用历史（按创建时间、occupationId 升序，稳定排序）。 */
    public List<OccupationResult> getOccupationsByRoute(String routeId) {
        if (routeRepo.findRoute(routeId) == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND",
                    "航线不存在: " + routeId);
        }
        List<OccupationResult> results = new ArrayList<>();
        for (OccupationPo po : weatherRepo.findOccupationsByRoute(routeId)) {
            results.add(toOccupationResult(po));
        }
        return List.copyOf(results);
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
        List<WeatherViolationDto> violations = new ArrayList<>(po.weatherViolations().size());
        for (WeatherViolation v : po.weatherViolations()) {
            violations.add(new WeatherViolationDto(v.zoneId(), v.windowStartMinute(),
                    v.windowEndMinute(), v.metric(), v.actualValue(), v.requiredValue(),
                    v.deficit()));
        }
        return new ReviewResultDto(po.reviewId(), po.routeId(), po.routeVersion(),
                po.airspaceVersion(), po.weatherVersion(), po.forecastVersion(), po.dutyVersion(),
                conclusion, List.copyOf(po.hitZoneIds()), List.copyOf(violations),
                snapshot, current);
    }

    private static OccupationResult toOccupationResult(OccupationPo po) {
        return new OccupationResult(po.occupationId(), po.reviewId(), po.routeId(),
                po.routeVersion(), po.airspaceVersion(), po.weatherVersion(),
                po.forecastVersion(), po.dutyVersion(), po.createdAt());
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
     * 指纹覆盖操作者、操作类型（由调用方以 kind 区分）与请求内全部影响结果的字段；
     * 对象字段按键名字典序递归排序，列表保持顺序，集合经 JSON 结构化转义，
     * 不通过未转义分隔符拼接，使相同语义参数产生相同哈希。
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
