package com.example.starter.service;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.OccupancyCreateRequest;
import com.example.starter.api.dto.OccupancyResult;
import com.example.starter.api.dto.ReviewDiagnosticsDto;
import com.example.starter.api.dto.WeatherForecastDetailDto;
import com.example.starter.api.dto.WeatherForecastEntryDto;
import com.example.starter.api.dto.WeatherForecastUploadRequest;
import com.example.starter.api.dto.WeatherForecastUploadResult;
import com.example.starter.api.dto.WeatherMinimaConfigRequest;
import com.example.starter.api.dto.WeatherMinimaDetailDto;
import com.example.starter.api.dto.WeatherMinimaResult;
import com.example.starter.api.dto.WeatherWindowDto;
import com.example.starter.domain.ReviewConclusion;
import com.example.starter.repo.AirspaceRepository;
import com.example.starter.repo.OccupancyPo;
import com.example.starter.repo.ReviewPo;
import com.example.starter.repo.ReviewRepository;
import com.example.starter.repo.RoutePo;
import com.example.starter.repo.RouteRepository;
import com.example.starter.repo.WeatherForecastPo;
import com.example.starter.repo.WeatherMinimaPo;
import com.example.starter.repo.WeatherRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 空域气象最低标准、预测气象与占用业务服务。
 *
 * <p>标准配置与预测上传都是多对象写入：先校验完整最终状态（无负值、无重叠、
 * 窗口合法、区域存在），再在同一事务内提交并推进全局气象（值班）版本；
 * 失败回滚、不留半成品、不占用 requestId。气象修订只影响新审查，旧审查
 * 记录与结论不被改写，仅在时效判断中标记 STALE。占用必须使用审查时固化的
 * 气象版本，STALE 或非 CLEAR 审查拒绝创建。</p>
 */
@Service
public class WeatherService {

    static final String KIND_WEATHER_MINIMA_CONFIG = "WEATHER_MINIMA_CONFIG";
    static final String KIND_WEATHER_FORECAST_UPLOAD = "WEATHER_FORECAST_UPLOAD";
    static final String KIND_OCCUPANCY_CREATE = "OCCUPANCY_CREATE";

    private final AirspaceRepository airspaceRepo;
    private final RouteRepository routeRepo;
    private final ReviewRepository reviewRepo;
    private final WeatherRepository weatherRepo;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate txTemplate;
    private final IdempotencySupport idempotency;

    public WeatherService(AirspaceRepository airspaceRepo,
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

    // ============================ 气象最低标准 ============================

    /**
     * 整体替换某区域的标准时段集合。窗口先按（起点、终点）排序规范化，
     * 使集合书写顺序不影响幂等指纹；校验完整最终状态后事务提交。
     */
    public MutationResponse configureMinima(WeatherMinimaConfigRequest request) {
        List<WeatherWindowDto> sorted = new ArrayList<>(request.windows());
        sorted.sort(Comparator.comparing(WeatherWindowDto::startUtc)
                .thenComparing(WeatherWindowDto::endUtc));
        WeatherMinimaConfigRequest normalized = new WeatherMinimaConfigRequest(
                request.requestId(), request.operator(), request.zoneId(), sorted);
        return idempotency.execute(request.requestId(), KIND_WEATHER_MINIMA_CONFIG, normalized, () -> {
            if (airspaceRepo.findZone(request.zoneId()) == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "ZONE_NOT_FOUND",
                        "区域不存在: " + request.zoneId());
            }
            validateMinimaWindows(sorted);
            long newVersion = weatherRepo.incrementWeatherVersion();
            List<WeatherMinimaPo> windows = new ArrayList<>(sorted.size());
            for (WeatherWindowDto w : sorted) {
                windows.add(new WeatherMinimaPo(request.zoneId(), w.startUtc(), w.endUtc(),
                        w.minVisibilityM(), w.minCloudBaseM(), newVersion));
            }
            weatherRepo.replaceMinima(request.zoneId(), windows);
            return new MutationResponse(request.requestId(), false,
                    new WeatherMinimaResult(request.zoneId(), newVersion, windows.size()));
        });
    }

    /** 校验完整最终状态：窗口合法、标准非负、时段不重叠；任一违规返回 422。 */
    private static void validateMinimaWindows(List<WeatherWindowDto> sorted) {
        for (WeatherWindowDto w : sorted) {
            validateWindow(w.startUtc(), w.endUtc());
            if (w.minVisibilityM() < 0) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_MINIMA_VALUE",
                        "能见度标准不能为负：actual=" + w.minVisibilityM() + ", 要求 >= 0");
            }
            if (w.minCloudBaseM() < 0) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_MINIMA_VALUE",
                        "云底标准不能为负：actual=" + w.minCloudBaseM() + ", 要求 >= 0");
            }
        }
        // 已按起点升序：相邻窗口前一窗口终点大于后一窗口起点即重叠（左闭右开）
        for (int i = 1; i < sorted.size(); i++) {
            WeatherWindowDto prev = sorted.get(i - 1);
            WeatherWindowDto curr = sorted.get(i);
            if (curr.startUtc() < prev.endUtc()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "WINDOW_OVERLAP",
                        "标准时段重叠：[" + prev.startUtc() + ", " + prev.endUtc() + ") 与 ["
                                + curr.startUtc() + ", " + curr.endUtc() + ")");
            }
        }
    }

    private static void validateWindow(long startUtc, long endUtc) {
        if (startUtc >= endUtc) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_WINDOW",
                    "时段起点必须小于终点（左闭右开）：start=" + startUtc + ", end=" + endUtc);
        }
    }
    // ============================ 预测气象 ============================

    /**
     * 整批上传预测并生效为一个新的气象版本。条目先按（区域、起点、终点）排序
     * 规范化；任一条目非法（区域不存在、窗口非法、负值、同区域同时段重复）
     * 则整批失败回滚，不创建任何预测。
     */
    public MutationResponse uploadForecast(WeatherForecastUploadRequest request) {
        List<WeatherForecastEntryDto> sorted = new ArrayList<>(request.entries());
        sorted.sort(Comparator.comparing(WeatherForecastEntryDto::zoneId)
                .thenComparing(WeatherForecastEntryDto::startUtc)
                .thenComparing(WeatherForecastEntryDto::endUtc));
        WeatherForecastUploadRequest normalized = new WeatherForecastUploadRequest(
                request.requestId(), request.operator(), sorted);
        return idempotency.execute(request.requestId(), KIND_WEATHER_FORECAST_UPLOAD, normalized, () -> {
            validateForecastEntries(sorted);
            long newVersion = weatherRepo.incrementWeatherVersion();
            List<WeatherForecastPo> entries = new ArrayList<>(sorted.size());
            for (WeatherForecastEntryDto e : sorted) {
                entries.add(new WeatherForecastPo(newVersion, e.zoneId(), e.startUtc(),
                        e.endUtc(), e.visibilityM(), e.cloudBaseM()));
            }
            weatherRepo.insertForecasts(entries, nowMillis());
            return new MutationResponse(request.requestId(), false,
                    new WeatherForecastUploadResult(newVersion, entries.size()));
        });
    }

    /** 校验整批预测条目：区域存在、窗口合法、值非负、同区域同时段不重复。 */
    private void validateForecastEntries(List<WeatherForecastEntryDto> sorted) {
        Set<String> seen = new HashSet<>();
        for (WeatherForecastEntryDto e : sorted) {
            if (airspaceRepo.findZone(e.zoneId()) == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "ZONE_NOT_FOUND",
                        "区域不存在: " + e.zoneId());
            }
            validateWindow(e.startUtc(), e.endUtc());
            if (e.visibilityM() < 0) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_FORECAST_VALUE",
                        "预测能见度不能为负：actual=" + e.visibilityM() + ", 要求 >= 0");
            }
            if (e.cloudBaseM() < 0) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_FORECAST_VALUE",
                        "预测云底不能为负：actual=" + e.cloudBaseM() + ", 要求 >= 0");
            }
            // 键由区域与窗口起点组成，仅用于内存判重，不写入存储
            if (!seen.add(e.zoneId() + " " + e.startUtc())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DUPLICATE_FORECAST_WINDOW",
                        "同一区域同一时段的预测重复：zone=" + e.zoneId()
                                + ", start=" + e.startUtc());
            }
        }
    }

    // ============================ 占用 ============================

    /**
     * 依据未失效的 CLEAR 审查创建占用，固化审查时的航线/空域/气象版本。
     * 审查已失效（STALE）返回 409 OCCUPANCY_REVIEW_STALE；结论非 CLEAR 返回
     * 409 OCCUPANCY_NOT_CLEAR（消息含实际结论）。
     */
    public MutationResponse createOccupancy(OccupancyCreateRequest request) {
        return idempotency.execute(request.requestId(), KIND_OCCUPANCY_CREATE, request, () -> {
            // 锁协调行：与审查、区域变更、气象修订事务互斥，按提交顺序裁决
            long globalVersion = airspaceRepo.getGlobalVersionForUpdate();
            ReviewPo review = reviewRepo.findReview(request.reviewId());
            if (review == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND",
                        "审查记录不存在: " + request.reviewId());
            }
            RoutePo route = routeRepo.findRouteForUpdate(review.routeId());
            long weatherVersion = weatherRepo.getWeatherVersion();
            boolean current = route != null
                    && route.version() == review.routeVersion()
                    && globalVersion == review.airspaceVersion()
                    && (review.weatherVersion() == null
                            || review.weatherVersion() == weatherVersion);
            if (!current) {
                throw new ApiException(HttpStatus.CONFLICT, "OCCUPANCY_REVIEW_STALE",
                        "审查结果已失效（STALE），不能用于创建占用: " + request.reviewId());
            }
            if (!ReviewConclusion.CLEAR.name().equals(review.conclusion())) {
                throw new ApiException(HttpStatus.CONFLICT, "OCCUPANCY_NOT_CLEAR",
                        "占用要求 CLEAR 审查，实际结论: " + review.conclusion());
            }
            String occupancyId = "oc_" + UUID.randomUUID();
            OccupancyPo po = new OccupancyPo(occupancyId, review.reviewId(), review.routeId(),
                    review.routeVersion(), review.airspaceVersion(), review.weatherVersion(),
                    request.requestId(), nowMillis());
            weatherRepo.insertOccupancy(po);
            return new MutationResponse(request.requestId(), false, toOccupancyResult(po));
        });
    }
    // ============================ 只读查询（不改变状态） ============================

    /** 查询当前全局气象（值班）版本。 */
    public long getWeatherVersion() {
        return weatherRepo.getWeatherVersion();
    }

    /** 查询某区域当前生效的标准时段明细（按起点升序）。 */
    public WeatherMinimaDetailDto getMinimaDetail(String zoneId) {
        if (airspaceRepo.findZone(zoneId) == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ZONE_NOT_FOUND",
                    "区域不存在: " + zoneId);
        }
        List<WeatherWindowDto> windows = new ArrayList<>();
        for (WeatherMinimaPo m : weatherRepo.findMinima(zoneId)) {
            windows.add(new WeatherWindowDto(m.windowStartUtc(), m.windowEndUtc(),
                    m.minVisibilityM(), m.minCloudBaseM()));
        }
        return new WeatherMinimaDetailDto(zoneId, windows);
    }

    /** 查询截至指定版本可见的预测明细（每区域每时段取最新版本，排序稳定）。 */
    public WeatherForecastDetailDto getForecastDetail(long version) {
        long current = weatherRepo.getWeatherVersion();
        if (version < 0 || version > current) {
            throw new ApiException(HttpStatus.NOT_FOUND, "FORECAST_VERSION_NOT_FOUND",
                    "预测版本不存在：submitted=" + version + ", current=" + current);
        }
        List<WeatherForecastEntryDto> entries = new ArrayList<>();
        for (WeatherForecastPo e : weatherRepo.findForecastsAsOf(version)) {
            entries.add(new WeatherForecastEntryDto(e.zoneId(), e.windowStartUtc(),
                    e.windowEndUtc(), e.visibilityM(), e.cloudBaseM()));
        }
        return new WeatherForecastDetailDto(version, entries);
    }

    /** 按 occupancyId 查询占用明细。 */
    public OccupancyResult getOccupancy(String occupancyId) {
        OccupancyPo po = weatherRepo.findOccupancy(occupancyId);
        if (po == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "OCCUPANCY_NOT_FOUND",
                    "占用记录不存在: " + occupancyId);
        }
        return toOccupancyResult(po);
    }

    /**
     * 审查时效诊断：保留原结论，逐项对比审查时固化版本与当前版本。
     * 只读查询，不改变任何状态。
     */
    public ReviewDiagnosticsDto getDiagnostics(String reviewId) {
        return txTemplate.execute(status -> {
            ReviewPo po = reviewRepo.findReview(reviewId);
            if (po == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND",
                        "审查记录不存在: " + reviewId);
            }
            RoutePo route = routeRepo.findRoute(po.routeId());
            long globalVersion = airspaceRepo.getGlobalVersion();
            long weatherVersion = weatherRepo.getWeatherVersion();
            boolean routeMatch = route != null && route.version() == po.routeVersion();
            boolean airspaceMatch = globalVersion == po.airspaceVersion();
            boolean weatherMatch = po.weatherVersion() == null
                    || po.weatherVersion() == weatherVersion;
            String effective = routeMatch && airspaceMatch && weatherMatch
                    ? "CURRENT" : "STALE";
            JsonNode snapshot = null;
            if (po.weatherSnapshot() != null) {
                try {
                    snapshot = objectMapper.readTree(po.weatherSnapshot());
                } catch (JsonProcessingException ex) {
                    throw new IllegalStateException("无法解析审查气象快照", ex);
                }
            }
            return new ReviewDiagnosticsDto(po.reviewId(), po.routeId(), po.conclusion(),
                    effective, po.routeVersion(), route == null ? null : route.version(),
                    po.airspaceVersion(), globalVersion,
                    po.weatherVersion(), weatherVersion, snapshot);
        });
    }

    // ============================ 工具方法 ============================

    private static OccupancyResult toOccupancyResult(OccupancyPo po) {
        return new OccupancyResult(po.occupancyId(), po.reviewId(), po.routeId(),
                po.routeVersion(), po.airspaceVersion(), po.weatherVersion(), po.createdAt());
    }

    private long nowMillis() {
        return clock.instant().toEpochMilli();
    }
}