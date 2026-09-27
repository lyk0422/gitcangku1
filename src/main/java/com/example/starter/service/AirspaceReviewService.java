package com.example.starter.service;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.ForecastEntryDto;
import com.example.starter.api.dto.ForecastUploadRequest;
import com.example.starter.api.dto.ForecastUploadResult;
import com.example.starter.api.dto.ForecastVersionDto;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.OccupationCreateRequest;
import com.example.starter.api.dto.OccupationResult;
import com.example.starter.api.dto.ReviewBatchItem;
import com.example.starter.api.dto.ReviewBatchRequest;
import com.example.starter.api.dto.ReviewBatchResult;
import com.example.starter.api.dto.ReviewDiagnosticsDto;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.ReviewResultDto;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.RouteReplaceRequest;
import com.example.starter.api.dto.RouteResult;
import com.example.starter.api.dto.WeatherMinimaResult;
import com.example.starter.api.dto.WeatherMinimaSetRequest;
import com.example.starter.api.dto.WeatherMinimaWindowDto;
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
import com.example.starter.repo.OccupationPo;
import com.example.starter.repo.OccupationRepository;
import com.example.starter.repo.ReviewPo;
import com.example.starter.repo.ReviewRepository;
import com.example.starter.repo.RoutePo;
import com.example.starter.repo.RouteRepository;
import com.example.starter.repo.WeatherForecastPo;
import com.example.starter.repo.WeatherMinimaPo;
import com.example.starter.repo.WeatherRepository;
import com.example.starter.repo.ZonePo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 禁飞区、航线审查与空域气象最低标准业务服务。
 *
 * <p>所有写操作在同一数据库事务内完成业务变更与幂等去重记录的原子提交；
 * 失败（含业务冲突）回滚事务，不占用 requestId。多对象写入（标准整体替换、
 * 预测上传、批量审核）都先校验完整最终状态，再在一个事务中提交。</p>
 *
 * <p>审核事务先锁全局协调行、再锁航线行：区域变更与气象标准/预测变更事务都必须
 * 先更新协调行，因而与审核互斥，保证审核固化的空域版本、气象标准版本、预测版本
 * 与对应内容来自同一已提交状态。</p>
 *
 * <p>所有时间统一使用 UTC epoch 毫秒，时段按左闭右开解释。</p>
 */
@Service
public class AirspaceReviewService {

    static final String KIND_ZONE_CREATE = "ZONE_CREATE";
    static final String KIND_ZONE_REVOKE = "ZONE_REVOKE";
    static final String KIND_ROUTE_CREATE = "ROUTE_CREATE";
    static final String KIND_ROUTE_REPLACE = "ROUTE_REPLACE";
    static final String KIND_REVIEW = "REVIEW";
    static final String KIND_REVIEW_BATCH = "REVIEW_BATCH";
    static final String KIND_WEATHER_MINIMA_SET = "WEATHER_MINIMA_SET";
    static final String KIND_WEATHER_FORECAST_UPLOAD = "WEATHER_FORECAST_UPLOAD";
    static final String KIND_OCCUPATION_CREATE = "OCCUPATION_CREATE";

    private final AirspaceRepository airspaceRepo;
    private final RouteRepository routeRepo;
    private final ReviewRepository reviewRepo;
    private final WeatherRepository weatherRepo;
    private final OccupationRepository occupationRepo;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate txTemplate;

    public AirspaceReviewService(AirspaceRepository airspaceRepo,
                                 RouteRepository routeRepo,
                                 ReviewRepository reviewRepo,
                                 WeatherRepository weatherRepo,
                                 OccupationRepository occupationRepo,
                                 ObjectMapper objectMapper,
                                 Clock clock,
                                 PlatformTransactionManager transactionManager) {
        this.airspaceRepo = airspaceRepo;
        this.routeRepo = routeRepo;
        this.reviewRepo = reviewRepo;
        this.weatherRepo = weatherRepo;
        this.occupationRepo = occupationRepo;
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
     * 整体替换某区域的气象最低标准。先校验完整最终状态（时段退化、负值、重叠
     * 都返回 422），再在一个事务中删除旧集合、写入新集合并推进标准版本。
     */
    public MutationResponse setWeatherMinima(WeatherMinimaSetRequest request) {
        return withIdempotency(request.requestId(), KIND_WEATHER_MINIMA_SET,
                canonicalHash(request), () -> {
                    // 先校验完整最终状态，任何失败都不产生写入
                    List<WeatherMinimaWindowDto> windows = validateMinimaWindows(request.windows());
                    if (airspaceRepo.findZone(request.zoneId()) == null) {
                        throw new ApiException(HttpStatus.NOT_FOUND, "ZONE_NOT_FOUND",
                                "禁飞区不存在: " + request.zoneId());
                    }
                    // 与审核事务互斥：先取协调锁再推进标准版本
                    airspaceRepo.getGlobalVersionForUpdate();
                    long standardsVersion = weatherRepo.incrementStandardsVersion();
                    weatherRepo.deleteMinima(request.zoneId());
                    for (WeatherMinimaWindowDto w : windows) {
                        weatherRepo.insertMinima(new WeatherMinimaPo(request.zoneId(),
                                w.effectiveFrom(), w.effectiveTo(),
                                w.minVisibilityMeters(), w.minCloudBaseMeters(), standardsVersion));
                    }
                    return new MutationResponse(request.requestId(), false,
                            new WeatherMinimaResult(request.zoneId(), standardsVersion,
                                    windows.size(), windows));
                });
    }

    /** 查询某区域当前生效的气象最低标准（按时段起点升序）。 */
    public WeatherMinimaResult getWeatherMinima(String zoneId) {
        if (airspaceRepo.findZone(zoneId) == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ZONE_NOT_FOUND",
                    "禁飞区不存在: " + zoneId);
        }
        List<WeatherMinimaPo> minima = weatherRepo.findMinima(zoneId);
        List<WeatherMinimaWindowDto> windows = new ArrayList<>(minima.size());
        for (WeatherMinimaPo po : minima) {
            windows.add(new WeatherMinimaWindowDto(po.effectiveFrom(), po.effectiveTo(),
                    po.minVisibilityM(), po.minCloudBaseM()));
        }
        return new WeatherMinimaResult(zoneId, weatherRepo.getStandardsVersion(),
                windows.size(), windows);
    }

    /**
     * 校验标准时段集合的完整最终状态：退化时段、负值标准、时段重叠均返回 422。
     * 通过时返回按起点升序的列表（稳定排序，保证响应与快照内容确定）。
     */
    private static List<WeatherMinimaWindowDto> validateMinimaWindows(
            List<WeatherMinimaWindowDto> windows) {
        List<WeatherMinimaWindowDto> sorted = new ArrayList<>(windows);
        sorted.sort(Comparator.comparingLong(WeatherMinimaWindowDto::effectiveFrom)
                .thenComparingLong(WeatherMinimaWindowDto::effectiveTo));
        WeatherMinimaWindowDto prev = null;
        for (WeatherMinimaWindowDto w : sorted) {
            if (w.effectiveFrom() >= w.effectiveTo()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_MINIMA_WINDOW",
                        "标准时段必须满足 effectiveFrom < effectiveTo（左闭右开）：["
                                + w.effectiveFrom() + ", " + w.effectiveTo() + ")");
            }
            if (w.minVisibilityMeters() < 0 || w.minCloudBaseMeters() < 0) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NEGATIVE_MINIMA",
                        "最低标准不允许负值：minVisibilityMeters=" + w.minVisibilityMeters()
                                + ", minCloudBaseMeters=" + w.minCloudBaseMeters());
            }
            // 左闭右开区间：前一段终点 <= 本段起点即不重叠
            if (prev != null && prev.effectiveTo() > w.effectiveFrom()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MINIMA_WINDOW_OVERLAP",
                        "标准时段重叠：[" + prev.effectiveFrom() + ", " + prev.effectiveTo()
                                + ") 与 [" + w.effectiveFrom() + ", " + w.effectiveTo() + ")");
            }
            prev = w;
        }
        return sorted;
    }

    // ============================ 预测气象 ============================

    /**
     * 上传预测气象，生成新的预测版本。先校验完整最终状态（区域存在、时段退化、
     * 负值、同区域时段重叠），再在一个事务中写入全部条目并推进预测版本。
     */
    public MutationResponse uploadForecast(ForecastUploadRequest request) {
        return withIdempotency(request.requestId(), KIND_WEATHER_FORECAST_UPLOAD,
                canonicalHash(request), () -> {
                    List<ForecastEntryDto> entries = validateForecastEntries(request.entries());
                    // 与审核事务互斥：先取协调锁再推进预测版本
                    airspaceRepo.getGlobalVersionForUpdate();
                    long forecastVersion = weatherRepo.incrementForecastVersion();
                    for (ForecastEntryDto e : entries) {
                        weatherRepo.insertForecast(new WeatherForecastPo(forecastVersion,
                                e.zoneId(), e.validFrom(), e.validTo(),
                                e.visibilityMeters(), e.cloudBaseMeters()));
                    }
                    return new MutationResponse(request.requestId(), false,
                            new ForecastUploadResult(forecastVersion, entries.size()));
                });
    }

    /** 查询指定版本的预测明细；版本不存在（尚未上传过）返回 404。 */
    public ForecastVersionDto getForecast(long forecastVersion) {
        if (forecastVersion < 1 || forecastVersion > weatherRepo.getForecastVersion()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "FORECAST_NOT_FOUND",
                    "预测气象版本不存在: " + forecastVersion);
        }
        List<WeatherForecastPo> entries = weatherRepo.findForecast(forecastVersion);
        List<ForecastEntryDto> dtos = new ArrayList<>(entries.size());
        for (WeatherForecastPo po : entries) {
            dtos.add(new ForecastEntryDto(po.zoneId(), po.validFrom(), po.validTo(),
                    po.visibilityM(), po.cloudBaseM()));
        }
        return new ForecastVersionDto(forecastVersion, dtos.size(), dtos);
    }

    /** 查询当前最新预测版本明细；从未上传返回 404。 */
    public ForecastVersionDto getCurrentForecast() {
        long current = weatherRepo.getForecastVersion();
        if (current == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "FORECAST_NOT_FOUND",
                    "尚未上传任何预测气象版本");
        }
        return getForecast(current);
    }

    /**
     * 校验预测条目集合的完整最终状态：区域不存在返回 404；时段退化、负值、
     * 同区域时段重叠返回 422。通过时返回按 zoneId、起点升序的列表（稳定排序）。
     */
    private List<ForecastEntryDto> validateForecastEntries(List<ForecastEntryDto> entries) {
        List<ForecastEntryDto> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing(ForecastEntryDto::zoneId)
                .thenComparingLong(ForecastEntryDto::validFrom)
                .thenComparingLong(ForecastEntryDto::validTo));
        Map<String, ForecastEntryDto> lastByZone = new LinkedHashMap<>();
        for (ForecastEntryDto e : sorted) {
            if (airspaceRepo.findZone(e.zoneId()) == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "ZONE_NOT_FOUND",
                        "预测条目引用的禁飞区不存在: " + e.zoneId());
            }
            if (e.validFrom() >= e.validTo()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_FORECAST_WINDOW",
                        "预测时段必须满足 validFrom < validTo（左闭右开）：zone=" + e.zoneId()
                                + ", [" + e.validFrom() + ", " + e.validTo() + ")");
            }
            if (e.visibilityMeters() < 0 || e.cloudBaseMeters() < 0) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NEGATIVE_FORECAST",
                        "预测值不允许负值：zone=" + e.zoneId()
                                + ", visibilityMeters=" + e.visibilityMeters()
                                + ", cloudBaseMeters=" + e.cloudBaseMeters());
            }
            ForecastEntryDto prev = lastByZone.get(e.zoneId());
            if (prev != null && prev.validTo() > e.validFrom()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "FORECAST_WINDOW_OVERLAP",
                        "同区域预测时段重叠：zone=" + e.zoneId() + ", [" + prev.validFrom() + ", "
                                + prev.validTo() + ") 与 [" + e.validFrom() + ", " + e.validTo() + ")");
            }
            lastByZone.put(e.zoneId(), e);
        }
        return sorted;
    }

    // ============================ 审核 ============================

    /** 提交审核：任一指定版本不是当前版本返回 409；结果不可变。 */
    public MutationResponse review(ReviewRequest request) {
        return withIdempotency(request.requestId(), KIND_REVIEW, canonicalHash(request), () -> {
            ReviewPo po = doReview(request.routeId(), request.routeVersion(),
                    request.airspaceVersion(), request.forecastVersion(), request.requestId());
            return new MutationResponse(request.requestId(), false,
                    toDto(po, po.conclusion(), true));
        });
    }

    /**
     * 批量审核：先对全部条目校验完整最终状态（含任一航线缺预测返回 422），
     * 再在同一事务中创建全部结果；任一失败整批回滚，查询不到半成品。
     */
    public MutationResponse reviewBatch(ReviewBatchRequest request) {
        return withIdempotency(request.requestId(), KIND_REVIEW_BATCH, canonicalHash(request), () -> {
            List<ReviewPo> created = new ArrayList<>(request.items().size());
            for (int i = 0; i < request.items().size(); i++) {
                ReviewBatchItem item = request.items().get(i);
                // 审核记录的 request_id 有唯一约束（VARCHAR(64)）：批内条目用序号派生，
                // 过长时截断前缀，保证可追踪、互不冲突且不超列宽
                String base = request.requestId().length() > 56
                        ? request.requestId().substring(0, 56) : request.requestId();
                created.add(doReview(item.routeId(), item.routeVersion(),
                        item.airspaceVersion(), item.forecastVersion(),
                        base + "#" + i));
            }
            List<ReviewResultDto> results = new ArrayList<>(created.size());
            for (ReviewPo po : created) {
                results.add(toDto(po, po.conclusion(), true));
            }
            return new MutationResponse(request.requestId(), false, new ReviewBatchResult(results));
        });
    }

    /**
     * 在持锁事务内执行单条审核并插入不可变结果。
     * 调用方必须处于 withIdempotency 的事务中；批量审核复用本方法。
     */
    private ReviewPo doReview(String routeId, int routeVersion, long airspaceVersion,
                              Long forecastVersion, String requestId) {
        // 先锁协调行：与任何区域创建/撤销、气象标准/预测变更事务互斥
        long globalVersion = airspaceRepo.getGlobalVersionForUpdate();
        // 再锁航线行：与航线替换事务互斥，保证版本号与点列一致
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
        // 气象检查：提交预测版本时，对路径经过且配置了标准的区域逐一核对
        WeatherEvaluation weather = null;
        if (forecastVersion != null) {
            weather = evaluateWeather(forecastVersion, hits);
        }
        String conclusion;
        if (weather != null && !weather.violations().isEmpty()) {
            conclusion = ReviewConclusion.WEATHER_BLOCKED.name();
        } else if (!hits.isEmpty()) {
            conclusion = ReviewConclusion.BLOCKED.name();
        } else {
            conclusion = ReviewConclusion.CLEAR.name();
        }
        String reviewId = "rv_" + UUID.randomUUID();
        ReviewPo po = new ReviewPo(reviewId, routeId, route.version(), globalVersion,
                conclusion, new ArrayList<>(hits), List.copyOf(route.points()),
                weather == null ? null : weather.forecastVersion(),
                weather == null ? null : weather.standardsVersion(),
                weather == null ? null : weather.snapshotJson(),
                weather == null ? null : writeJson(weather.violations()),
                requestId, nowMillis());
        reviewRepo.insertReview(po);
        return po;
    }

    /**
     * 气象评估：对路径经过且配置了最低标准的区域，用指定预测版本逐时段核对。
     *
     * <p>提交的预测版本不是当前版本返回 409；任一标准时段未被预测完整覆盖
     * 返回 422（FORECAST_MISSING）；任一预测值低于标准记录不达标明细
     * （含实际值、要求值与差额）。快照固化生效的标准与预测内容。</p>
     */
    private WeatherEvaluation evaluateWeather(long forecastVersion, Set<String> hitZoneIds) {
        long currentForecast = weatherRepo.getForecastVersion();
        if (forecastVersion != currentForecast) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                    "预测气象版本不是当前版本：submitted=" + forecastVersion
                            + ", current=" + currentForecast);
        }
        long standardsVersion = weatherRepo.getStandardsVersion();
        List<WeatherViolationDto> violations = new ArrayList<>();
        ArrayNode snapshotZones = JsonNodeFactory.instance.arrayNode();
        for (String zoneId : hitZoneIds) {
            List<WeatherMinimaPo> minima = weatherRepo.findMinima(zoneId);
            if (minima.isEmpty()) {
                continue;
            }
            List<WeatherForecastPo> forecasts =
                    weatherRepo.findForecastForZone(forecastVersion, zoneId);
            for (WeatherMinimaPo m : minima) {
                // 覆盖检查：预测时段（左闭右开）的并集必须完整覆盖标准时段
                List<WeatherForecastPo> overlapping = new ArrayList<>();
                for (WeatherForecastPo f : forecasts) {
                    if (f.validFrom() < m.effectiveTo() && f.validTo() > m.effectiveFrom()) {
                        overlapping.add(f);
                    }
                }
                long cursor = m.effectiveFrom();
                int worstVisibility = Integer.MAX_VALUE;
                int worstCloudBase = Integer.MAX_VALUE;
                for (WeatherForecastPo f : overlapping) {
                    if (f.validFrom() > cursor) {
                        break; // 覆盖出现缺口
                    }
                    worstVisibility = Math.min(worstVisibility, f.visibilityM());
                    worstCloudBase = Math.min(worstCloudBase, f.cloudBaseM());
                    cursor = Math.max(cursor, f.validTo());
                    if (cursor >= m.effectiveTo()) {
                        break;
                    }
                }
                if (cursor < m.effectiveTo()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "FORECAST_MISSING",
                            "区域 " + zoneId + " 的标准时段 [" + m.effectiveFrom() + ", "
                                    + m.effectiveTo() + ") 缺少预测覆盖：缺失 ["
                                    + cursor + ", " + m.effectiveTo() + ")");
                }
                if (worstVisibility < m.minVisibilityM()) {
                    violations.add(new WeatherViolationDto(zoneId, m.effectiveFrom(),
                            m.effectiveTo(), "VISIBILITY", worstVisibility, m.minVisibilityM(),
                            m.minVisibilityM() - worstVisibility));
                }
                if (worstCloudBase < m.minCloudBaseM()) {
                    violations.add(new WeatherViolationDto(zoneId, m.effectiveFrom(),
                            m.effectiveTo(), "CLOUD_BASE", worstCloudBase, m.minCloudBaseM(),
                            m.minCloudBaseM() - worstCloudBase));
                }
            }
            snapshotZones.add(buildSnapshotZone(zoneId, minima, forecasts));
        }
        ObjectNode snapshot = JsonNodeFactory.instance.objectNode();
        snapshot.put("standardsVersion", standardsVersion);
        snapshot.put("forecastVersion", forecastVersion);
        snapshot.set("zones", snapshotZones);
        return new WeatherEvaluation(forecastVersion, standardsVersion,
                writeJson(snapshot), violations);
    }

    /** 固化单个区域在审核时生效的标准与预测内容（不可变业务证据）。 */
    private static ObjectNode buildSnapshotZone(String zoneId, List<WeatherMinimaPo> minima,
                                                List<WeatherForecastPo> forecasts) {
        ObjectNode zone = JsonNodeFactory.instance.objectNode();
        zone.put("zoneId", zoneId);
        ArrayNode minimaArray = JsonNodeFactory.instance.arrayNode();
        for (WeatherMinimaPo m : minima) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("effectiveFrom", m.effectiveFrom());
            node.put("effectiveTo", m.effectiveTo());
            node.put("minVisibilityMeters", m.minVisibilityM());
            node.put("minCloudBaseMeters", m.minCloudBaseM());
            minimaArray.add(node);
        }
        zone.set("minima", minimaArray);
        ArrayNode forecastArray = JsonNodeFactory.instance.arrayNode();
        for (WeatherForecastPo f : forecasts) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("validFrom", f.validFrom());
            node.put("validTo", f.validTo());
            node.put("visibilityMeters", f.visibilityM());
            node.put("cloudBaseMeters", f.cloudBaseM());
            forecastArray.add(node);
        }
        zone.set("forecasts", forecastArray);
        return zone;
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

    /** 查询某航线全部历史审核（按创建时间、reviewId 倒序，稳定排序），保留原结论。 */
    public List<ReviewResultDto> getRouteReviews(String routeId) {
        List<ReviewPo> reviews = reviewRepo.findReviewsByRoute(routeId);
        List<ReviewResultDto> result = new ArrayList<>(reviews.size());
        for (ReviewPo po : reviews) {
            result.add(toDto(po, po.conclusion(), null));
        }
        return result;
    }

    /** 查询某航线当前可用结论；任何相关版本不再匹配返回 STALE。 */
    public ReviewResultDto getCurrentReview(String routeId) {
        return txTemplate.execute(status -> {
            ReviewPo latest = reviewRepo.findLatestReview(routeId);
            if (latest == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND",
                        "该航线尚无审核记录: " + routeId);
            }
            boolean current = isCurrent(latest);
            // 不匹配时绝不能把旧 CLEAR/BLOCKED 当成当前结论，统一返回 STALE
            String conclusion = current ? latest.conclusion() : "STALE";
            return toDto(latest, conclusion, current);
        });
    }

    /**
     * 审核时效诊断：保留原结论，逐项比对审核时固化版本与当前版本，
     * 任一不再匹配则有效结论为 STALE；气象修订（标准或预测版本推进）同样使旧结果失效。
     */
    public ReviewDiagnosticsDto getReviewDiagnostics(String reviewId) {
        return txTemplate.execute(status -> {
            ReviewPo po = reviewRepo.findReview(reviewId);
            if (po == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND",
                        "审核记录不存在: " + reviewId);
            }
            RoutePo currentRoute = routeRepo.findRoute(po.routeId());
            List<ReviewDiagnosticsDto.VersionCheck> versions = new ArrayList<>();
            versions.add(new ReviewDiagnosticsDto.VersionCheck("routeVersion",
                    (long) po.routeVersion(),
                    currentRoute == null ? null : (long) currentRoute.version(),
                    currentRoute != null && currentRoute.version() == po.routeVersion()));
            long globalVersion = airspaceRepo.getGlobalVersion();
            versions.add(new ReviewDiagnosticsDto.VersionCheck("airspaceVersion",
                    po.airspaceVersion(), globalVersion, globalVersion == po.airspaceVersion()));
            if (po.forecastVersion() != null) {
                long currentForecast = weatherRepo.getForecastVersion();
                versions.add(new ReviewDiagnosticsDto.VersionCheck("forecastVersion",
                        po.forecastVersion(), currentForecast,
                        currentForecast == po.forecastVersion()));
            }
            if (po.standardsVersion() != null) {
                long currentStandards = weatherRepo.getStandardsVersion();
                versions.add(new ReviewDiagnosticsDto.VersionCheck("standardsVersion",
                        po.standardsVersion(), currentStandards,
                        currentStandards == po.standardsVersion()));
            }
            boolean stale = versions.stream().anyMatch(v -> !v.match());
            return new ReviewDiagnosticsDto(po.reviewId(), po.conclusion(),
                    stale ? "STALE" : po.conclusion(), stale, versions,
                    decodeViolations(po.weatherViolations()));
        });
    }

    /** 判断审核记录的固化版本是否仍与当前状态一致（气象版本仅对做过气象检查的审核生效）。 */
    private boolean isCurrent(ReviewPo review) {
        RoutePo currentRoute = routeRepo.findRoute(review.routeId());
        if (currentRoute == null || currentRoute.version() != review.routeVersion()) {
            return false;
        }
        if (airspaceRepo.getGlobalVersion() != review.airspaceVersion()) {
            return false;
        }
        if (review.forecastVersion() != null
                && weatherRepo.getForecastVersion() != review.forecastVersion()) {
            return false;
        }
        return review.standardsVersion() == null
                || weatherRepo.getStandardsVersion() == review.standardsVersion();
    }

    // ============================ 占用 ============================

    /**
     * 创建占用：仅允许引用未失效（非 STALE）的 CLEAR 审核结果；
     * 占用固化审核时的航线、空域与气象版本，后续修订不影响已创建占用。
     */
    public MutationResponse createOccupation(OccupationCreateRequest request) {
        return withIdempotency(request.requestId(), KIND_OCCUPATION_CREATE,
                canonicalHash(request), () -> {
                    ReviewPo review = reviewRepo.findReview(request.reviewId());
                    if (review == null) {
                        throw new ApiException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND",
                                "审核记录不存在: " + request.reviewId());
                    }
                    if (!review.routeId().equals(request.routeId())) {
                        throw new ApiException(HttpStatus.CONFLICT, "OCCUPATION_ROUTE_MISMATCH",
                                "审核记录属于航线 " + review.routeId()
                                        + "，不能用于航线 " + request.routeId());
                    }
                    if (!ReviewConclusion.CLEAR.name().equals(review.conclusion())) {
                        throw new ApiException(HttpStatus.CONFLICT, "OCCUPATION_NOT_CLEARED",
                                "只有 CLEAR 结论的审核结果可用于创建占用，实际结论: "
                                        + review.conclusion());
                    }
                    // 持锁复核审核结果仍未失效，拒绝使用 STALE 结果
                    airspaceRepo.getGlobalVersionForUpdate();
                    routeRepo.findRouteForUpdate(request.routeId());
                    if (!isCurrent(review)) {
                        throw new ApiException(HttpStatus.CONFLICT, "REVIEW_STALE",
                                "审核结果已失效（STALE），请重新审核后再创建占用: " + request.reviewId());
                    }
                    String occupationId = "oc_" + UUID.randomUUID();
                    OccupationPo po = new OccupationPo(occupationId, review.reviewId(),
                            review.routeId(), review.routeVersion(), review.airspaceVersion(),
                            review.forecastVersion(), review.standardsVersion(),
                            request.requestId(), nowMillis());
                    occupationRepo.insertOccupation(po);
                    return new MutationResponse(request.requestId(), false, toOccupationResult(po));
                });
    }

    /** 按 occupationId 查询占用记录。 */
    public OccupationResult getOccupation(String occupationId) {
        OccupationPo po = occupationRepo.findOccupation(occupationId);
        if (po == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "OCCUPATION_NOT_FOUND",
                    "占用记录不存在: " + occupationId);
        }
        return toOccupationResult(po);
    }

    /** 查询某航线全部占用记录（按创建时间、occupationId 升序，稳定排序）。 */
    public List<OccupationResult> getRouteOccupations(String routeId) {
        List<OccupationPo> occupations = occupationRepo.findByRoute(routeId);
        List<OccupationResult> result = new ArrayList<>(occupations.size());
        for (OccupationPo po : occupations) {
            result.add(toOccupationResult(po));
        }
        return result;
    }

    private static OccupationResult toOccupationResult(OccupationPo po) {
        return new OccupationResult(po.occupationId(), po.reviewId(), po.routeId(),
                po.routeVersion(), po.airspaceVersion(), po.forecastVersion(),
                po.standardsVersion());
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
        return new ReviewResultDto(po.reviewId(), po.routeId(), po.routeVersion(),
                po.airspaceVersion(), po.forecastVersion(), po.standardsVersion(),
                conclusion, List.copyOf(po.hitZoneIds()),
                decodeViolations(po.weatherViolations()), snapshot, current);
    }

    /** 解码固化的气象不达标明细；未做气象检查（null）返回 null，无不达标返回空列表。 */
    private List<WeatherViolationDto> decodeViolations(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("无法解析气象不达标明细", ex);
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
     * 计算请求参数的规范化哈希（SHA-256）。
     * 对象字段按键名字典序递归排序，列表保持顺序，使相同语义参数产生相同哈希、
     * 字段书写顺序不同不影响比对结果；集合以 JSON 数组参与哈希，
     * 不通过未转义分隔符拼接，操作者与全部影响结果的字段都在指纹内。
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

    /** 气象评估结果：固化的版本、快照与不达标明细。 */
    private record WeatherEvaluation(long forecastVersion, long standardsVersion,
                                     String snapshotJson, List<WeatherViolationDto> violations) {
    }
}
