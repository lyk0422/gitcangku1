package com.example.starter.service;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.AltitudeBandDto;
import com.example.starter.api.dto.BandCapacityUpdateDto;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.OccupancyCancelRequest;
import com.example.starter.api.dto.OccupancyCreateRequest;
import com.example.starter.api.dto.OccupancyListResult;
import com.example.starter.api.dto.OccupancyResult;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.ReviewResultDto;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.RouteReplaceRequest;
import com.example.starter.api.dto.RouteResult;
import com.example.starter.api.dto.VerticalSeparationResult;
import com.example.starter.api.dto.ZoneBandsModifyRequest;
import com.example.starter.api.dto.ZoneBandsResult;
import com.example.starter.api.dto.ZoneCreateRequest;
import com.example.starter.api.dto.ZoneResult;
import com.example.starter.api.dto.ZoneRevokeRequest;
import com.example.starter.domain.AltitudeBands;
import com.example.starter.domain.Geometry;
import com.example.starter.domain.OccupancyStatus;
import com.example.starter.domain.Point;
import com.example.starter.domain.ReviewConclusion;
import com.example.starter.domain.ZoneStatus;
import com.example.starter.repo.AirspaceRepository;
import com.example.starter.repo.BandPo;
import com.example.starter.repo.DedupPo;
import com.example.starter.repo.OccupancyPo;
import com.example.starter.repo.OccupancyRepository;
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
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 *
 * <p>高度层语义：无高度带的区域保持二维相交即拦截；登记了高度带的区域仅当
 * 二维路径相交且巡航高度落入某高度带（左闭右开）时才拦截。高度带配置修改
 * （只允许上调容量或新增不重叠带）推进区域级配置版本，不推进全局空域版本，
 * 且不追溯改写已有占用记录。占用创建在同一事务内锁协调行、航线行与高度带行后
 * 按时间重叠统计 ACTIVE 消耗数，达到容量返回 429，容量不得超卖。</p>
 */
@Service
public class AirspaceReviewService {

    static final String KIND_ZONE_CREATE = "ZONE_CREATE";
    static final String KIND_ZONE_REVOKE = "ZONE_REVOKE";
    static final String KIND_ROUTE_CREATE = "ROUTE_CREATE";
    static final String KIND_ROUTE_REPLACE = "ROUTE_REPLACE";
    static final String KIND_REVIEW = "REVIEW";
    static final String KIND_ZONE_BANDS_MODIFY = "ZONE_BANDS_MODIFY";
    static final String KIND_OCCUPANCY_CREATE = "OCCUPANCY_CREATE";
    static final String KIND_OCCUPANCY_CANCEL = "OCCUPANCY_CANCEL";

    private final AirspaceRepository airspaceRepo;
    private final RouteRepository routeRepo;
    private final ReviewRepository reviewRepo;
    private final OccupancyRepository occupancyRepo;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate txTemplate;

    public AirspaceReviewService(AirspaceRepository airspaceRepo,
                                 RouteRepository routeRepo,
                                 ReviewRepository reviewRepo,
                                 OccupancyRepository occupancyRepo,
                                 ObjectMapper objectMapper,
                                 Clock clock,
                                 PlatformTransactionManager transactionManager) {
        this.airspaceRepo = airspaceRepo;
        this.routeRepo = routeRepo;
        this.reviewRepo = reviewRepo;
        this.occupancyRepo = occupancyRepo;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    // ============================ 禁飞区 ============================

    /** 创建禁飞区（可同时登记初始高度带）；同键同参重放原结果，异参 409。 */
    public MutationResponse createZone(ZoneCreateRequest request) {
        return withIdempotency(request.requestId(), KIND_ZONE_CREATE, canonicalHash(request), () -> {
            if (request.xMin() >= request.xMax() || request.yMin() >= request.yMax()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ZONE_RECTANGLE",
                        "禁飞区必须是非退化轴对齐矩形：xMin < xMax 且 yMin < yMax");
            }
            List<AltitudeBandDto> bands = request.bands() == null ? List.of() : request.bands();
            validateBandList(bands);
            if (airspaceRepo.findZone(request.zoneId()) != null) {
                throw new ApiException(HttpStatus.CONFLICT, "ZONE_ALREADY_EXISTS",
                        "禁飞区已存在: " + request.zoneId());
            }
            long newVersion = airspaceRepo.incrementGlobalVersion();
            airspaceRepo.insertZone(new ZonePo(request.zoneId(), request.xMin(), request.yMin(),
                    request.xMax(), request.yMax(), ZoneStatus.ACTIVE.name(), newVersion, null, 1));
            for (AltitudeBandDto band : bands) {
                airspaceRepo.insertBand(new BandPo(request.zoneId(), band.bandId(),
                        band.lowerM(), band.upperM(), band.capacity()), nowMillis());
            }
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

    // ============================ 高度带配置 ============================

    /**
     * 修改区域高度带配置：只允许上调已有高度带容量或新增不重叠高度带。
     * 携带区域高度带配置 expectedVersion，冲突 409；推进区域配置版本，
     * 不推进全局空域版本，不追溯改写已有占用记录。
     */
    public MutationResponse modifyZoneBands(ZoneBandsModifyRequest request) {
        return withIdempotency(request.requestId(), KIND_ZONE_BANDS_MODIFY, canonicalHash(request), () -> {
            List<AltitudeBandDto> newBands = request.newBands() == null
                    ? List.of() : request.newBands();
            List<BandCapacityUpdateDto> updates = request.capacityUpdates() == null
                    ? List.of() : request.capacityUpdates();
            if (newBands.isEmpty() && updates.isEmpty()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_BAND_MODIFICATION",
                        "高度带修改必须至少包含一个新增高度带或容量上调项");
            }
            validateBandList(newBands);
            ZonePo zone = airspaceRepo.findZone(request.zoneId());
            if (zone == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "ZONE_NOT_FOUND",
                        "禁飞区不存在: " + request.zoneId());
            }
            if (ZoneStatus.REVOKED.name().equals(zone.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "ZONE_ALREADY_REVOKED",
                        "禁飞区已撤销，不能修改高度带配置: " + request.zoneId());
            }
            if (zone.configVersion() != request.expectedVersion()) {
                throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                        "高度带配置版本不匹配：expected=" + request.expectedVersion()
                                + ", current=" + zone.configVersion());
            }
            List<BandPo> existing = airspaceRepo.findBands(request.zoneId());
            Map<String, BandPo> existingById = new HashMap<>();
            for (BandPo band : existing) {
                existingById.put(band.bandId(), band);
            }
            // 新增带：标识不得与已有带重复，且不得与已有带重叠（端点相接合法）
            for (AltitudeBandDto band : newBands) {
                if (existingById.containsKey(band.bandId())) {
                    throw new ApiException(HttpStatus.CONFLICT, "BAND_ALREADY_EXISTS",
                            "高度带已存在: " + band.bandId());
                }
                for (BandPo old : existing) {
                    if (AltitudeBands.overlaps(band.lowerM(), band.upperM(),
                            old.lowerM(), old.upperM())) {
                        throw new ApiException(HttpStatus.CONFLICT, "BAND_OVERLAP",
                                "新增高度带 [" + band.lowerM() + "," + band.upperM()
                                        + ") 与已有高度带 " + old.bandId()
                                        + " [" + old.lowerM() + "," + old.upperM() + ") 重叠");
                    }
                }
            }
            // 容量上调：目标必须是已有高度带（不能是本次新增的带）
            for (BandCapacityUpdateDto update : updates) {
                BandPo target = existingById.get(update.bandId());
                if (target == null) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "BAND_NOT_FOUND",
                            "高度带不存在: " + update.bandId());
                }
                if (update.capacity() <= target.capacity()) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "CAPACITY_NOT_INCREASED",
                            "只允许上调容量：高度带 " + update.bandId() + " 当前容量 "
                                    + target.capacity() + "，请求容量 " + update.capacity());
                }
            }
            // 条件推进配置版本：同时对区域行加写锁，与并发修改互斥；
            // 0 行说明配置版本已被其他事务推进
            int updated = airspaceRepo.compareAndIncrementConfigVersion(
                    request.zoneId(), request.expectedVersion());
            if (updated == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                        "高度带配置版本已变化，请使用最新 expectedVersion 重试");
            }
            for (BandCapacityUpdateDto update : updates) {
                int raised = airspaceRepo.increaseCapacity(
                        request.zoneId(), update.bandId(), update.capacity());
                if (raised == 0) {
                    throw new ApiException(HttpStatus.CONFLICT, "CAPACITY_NOT_INCREASED",
                            "高度带容量已被并发修改，请重试: " + update.bandId());
                }
            }
            for (AltitudeBandDto band : newBands) {
                airspaceRepo.insertBand(new BandPo(request.zoneId(), band.bandId(),
                        band.lowerM(), band.upperM(), band.capacity()), nowMillis());
            }
            return new MutationResponse(request.requestId(), false,
                    toBandsResult(zone, request.expectedVersion() + 1));
        });
    }

    /** 查询区域高度带配置。 */
    public ZoneBandsResult getZoneBands(String zoneId) {
        ZonePo zone = airspaceRepo.findZone(zoneId);
        if (zone == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ZONE_NOT_FOUND",
                    "禁飞区不存在: " + zoneId);
        }
        return toBandsResult(zone, zone.configVersion());
    }

    // ============================ 航线 ============================

    /** 创建航线（初始版本 1）。 */
    public MutationResponse createRoute(RouteCreateRequest request) {
        return withIdempotency(request.requestId(), KIND_ROUTE_CREATE, canonicalHash(request), () -> {
            List<Point> points = toPoints(request.points());
            validatePointsDistinct(points);
            validateTimeRange(request.startAt(), request.endAt());
            if (routeRepo.findRoute(request.routeId()) != null) {
                throw new ApiException(HttpStatus.CONFLICT, "ROUTE_ALREADY_EXISTS",
                        "航线已存在: " + request.routeId());
            }
            routeRepo.insertRoute(request.routeId(), points,
                    request.cruiseAltitudeM(), request.startAt(), request.endAt());
            return new MutationResponse(request.requestId(), false,
                    new RouteResult(request.routeId(), 1));
        });
    }

    /** 替换航线点列与巡航参数，expectedVersion 不匹配返回 409；成功版本加一。 */
    public MutationResponse replaceRoute(RouteReplaceRequest request) {
        return withIdempotency(request.requestId(), KIND_ROUTE_REPLACE, canonicalHash(request), () -> {
            List<Point> points = toPoints(request.points());
            validatePointsDistinct(points);
            validateTimeRange(request.startAt(), request.endAt());
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
                    request.routeId(), request.expectedVersion(),
                    request.cruiseAltitudeM(), request.startAt(), request.endAt());
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
            // 持锁状态下读取全部有效禁飞区与高度带，与上面的版本号同属一个一致状态
            List<ZonePo> activeZones = airspaceRepo.findActiveZones();
            Map<String, List<BandPo>> bandsByZone = groupBandsByZone(airspaceRepo.findAllBands());
            Set<String> hits = new TreeSet<>();
            for (ZonePo zone : activeZones) {
                if (!Geometry.polylineHitsRectangle(route.points(),
                        zone.xMin(), zone.yMin(), zone.xMax(), zone.yMax())) {
                    continue;
                }
                List<BandPo> bands = bandsByZone.getOrDefault(zone.zoneId(), List.of());
                // 无高度带的区域保持二维相交即拦截；有高度带的区域仅当巡航高度
                // 落入某高度带（左闭右开）时才拦截
                boolean hit = bands.isEmpty() || bands.stream().anyMatch(band ->
                        AltitudeBands.contains(route.cruiseAltitudeM(),
                                band.lowerM(), band.upperM()));
                if (hit) {
                    hits.add(zone.zoneId());
                }
            }
            String conclusion = hits.isEmpty()
                    ? ReviewConclusion.CLEAR.name()
                    : ReviewConclusion.BLOCKED.name();
            String reviewId = "rv_" + UUID.randomUUID();
            ReviewPo po = new ReviewPo(reviewId, request.routeId(), route.version(), globalVersion,
                    conclusion, new ArrayList<>(hits), List.copyOf(route.points()),
                    route.cruiseAltitudeM(), route.startAt(), route.endAt(),
                    request.requestId(), nowMillis());
            reviewRepo.insertReview(po);
            return new MutationResponse(request.requestId(), false, toDto(po, conclusion, true));
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
            boolean current = currentRoute != null
                    && currentRoute.version() == latest.routeVersion()
                    && globalVersion == latest.airspaceVersion();
            // 不匹配时绝不能把旧 CLEAR/BLOCKED 当成当前结论，统一返回 STALE
            String conclusion = current ? latest.conclusion() : "STALE";
            return toDto(latest, conclusion, current);
        });
    }

    /**
     * 航线垂直分离审查明细：基于审核不可变快照（航点、巡航高度、时刻）与
     * 当前有效区域及高度带，列出二维路径相交区域的逐高度带垂直间隔。
     */
    public VerticalSeparationResult getVerticalSeparation(String reviewId) {
        return txTemplate.execute(status -> {
            ReviewPo review = reviewRepo.findReview(reviewId);
            if (review == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND",
                        "审核记录不存在: " + reviewId);
            }
            Map<String, List<BandPo>> bandsByZone = groupBandsByZone(airspaceRepo.findAllBands());
            List<VerticalSeparationResult.ZoneVerticalDetail> zones = new ArrayList<>();
            for (ZonePo zone : airspaceRepo.findActiveZones()) {
                if (!Geometry.polylineHitsRectangle(review.pointsSnapshot(),
                        zone.xMin(), zone.yMin(), zone.xMax(), zone.yMax())) {
                    continue;
                }
                List<VerticalSeparationResult.BandVerticalDetail> bandDetails = new ArrayList<>();
                boolean blocks = false;
                for (BandPo band : bandsByZone.getOrDefault(zone.zoneId(), List.of())) {
                    boolean intersects = AltitudeBands.contains(
                            review.cruiseAltitudeM(), band.lowerM(), band.upperM());
                    blocks = blocks || intersects;
                    bandDetails.add(new VerticalSeparationResult.BandVerticalDetail(
                            band.bandId(), band.lowerM(), band.upperM(), band.capacity(),
                            intersects,
                            AltitudeBands.verticalSeparation(review.cruiseAltitudeM(),
                                    band.lowerM(), band.upperM())));
                }
                // 无高度带的区域按二维相交即拦截处理
                zones.add(new VerticalSeparationResult.ZoneVerticalDetail(
                        zone.zoneId(), blocks || bandDetails.isEmpty(), bandDetails));
            }
            return new VerticalSeparationResult(review.reviewId(), review.routeId(),
                    review.routeVersion(), review.airspaceVersion(), review.cruiseAltitudeM(),
                    review.startAt(), review.endAt(), zones);
        });
    }

    // ============================ 高度层占用 ============================

    /**
     * 创建高度层占用：关联审核必须为 CLEAR 且未失效（STALE 返回 422）。
     * 同一事务内锁协调行、航线行与高度带行后，按区域、高度带与时间重叠统计
     * ACTIVE 且巡航高度落入带内的占用数，达到容量返回 429 并给出区域、高度带
     * 与占用数；否则创建占用并关联该审查版本。巡航高度不落入高度带的航线
     * 不消耗该高度带容量。
     */
    public MutationResponse createOccupancy(OccupancyCreateRequest request) {
        return withIdempotency(request.requestId(), KIND_OCCUPANCY_CREATE, canonicalHash(request), () -> {
            ReviewPo review = reviewRepo.findReview(request.reviewId());
            if (review == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND",
                        "审核记录不存在: " + request.reviewId());
            }
            if (!ReviewConclusion.CLEAR.name().equals(review.conclusion())) {
                throw new ApiException(HttpStatus.CONFLICT, "REVIEW_NOT_CLEAR",
                        "只有审查结果为 CLEAR 的航线才能创建高度层占用: " + request.reviewId());
            }
            // 锁协调行与航线行，与区域变更、航线替换事务互斥后再判定 STALE
            long globalVersion = airspaceRepo.getGlobalVersionForUpdate();
            RoutePo route = routeRepo.findRouteForUpdate(review.routeId());
            boolean current = route != null
                    && route.version() == review.routeVersion()
                    && globalVersion == review.airspaceVersion();
            if (!current) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REVIEW_STALE",
                        "关联审查结果已失效（航线或空域版本已变化），请重新审查后再创建占用: "
                                + request.reviewId());
            }
            ZonePo zone = airspaceRepo.findZone(request.zoneId());
            if (zone == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "ZONE_NOT_FOUND",
                        "禁飞区不存在: " + request.zoneId());
            }
            if (ZoneStatus.REVOKED.name().equals(zone.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "ZONE_ALREADY_REVOKED",
                        "禁飞区已撤销，不能创建占用: " + request.zoneId());
            }
            // 锁高度带行：串行化同一高度带上的占用创建，保证计数与插入之间无并发插入
            if (airspaceRepo.lockBandForUpdate(request.zoneId(), request.bandId()) == 0) {
                throw new ApiException(HttpStatus.NOT_FOUND, "BAND_NOT_FOUND",
                        "高度带不存在: " + request.zoneId() + "/" + request.bandId());
            }
            // 持锁后重新读取，容量以最新已提交值为准
            BandPo band = airspaceRepo.findBand(request.zoneId(), request.bandId());
            int activeCount = occupancyRepo.countActiveConsumingOverlap(
                    request.zoneId(), request.bandId(), review.startAt(), review.endAt());
            if (activeCount >= band.capacity()) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("zoneId", request.zoneId());
                details.put("bandId", request.bandId());
                details.put("activeCount", activeCount);
                details.put("capacity", band.capacity());
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "CAPACITY_EXCEEDED",
                        "高度带容量不足：区域 " + request.zoneId() + " 高度带 " + request.bandId()
                                + " 当前时段重叠占用 " + activeCount + "，容量 " + band.capacity(),
                        details);
            }
            String occupancyId = "oc_" + UUID.randomUUID();
            OccupancyPo po = new OccupancyPo(occupancyId, review.reviewId(), review.routeId(),
                    review.routeVersion(), review.airspaceVersion(),
                    request.zoneId(), request.bandId(), review.cruiseAltitudeM(),
                    review.startAt(), review.endAt(), OccupancyStatus.ACTIVE.name(),
                    request.requestId(), nowMillis(), null);
            occupancyRepo.insertOccupancy(po);
            return new MutationResponse(request.requestId(), false, toOccupancyResult(po, band));
        });
    }

    /** 取消占用：立即释放容量，历史记录保留；重复取消返回 409。 */
    public MutationResponse cancelOccupancy(OccupancyCancelRequest request) {
        return withIdempotency(request.requestId(), KIND_OCCUPANCY_CANCEL, canonicalHash(request), () -> {
            OccupancyPo po = occupancyRepo.findOccupancy(request.occupancyId());
            if (po == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "OCCUPANCY_NOT_FOUND",
                        "占用记录不存在: " + request.occupancyId());
            }
            if (OccupancyStatus.CANCELLED.name().equals(po.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "OCCUPANCY_ALREADY_CANCELLED",
                        "占用已取消: " + request.occupancyId());
            }
            // 条件更新兜底并发取消：更新 0 行说明已被其他事务取消
            int updated = occupancyRepo.cancelIfActive(request.occupancyId(), nowMillis());
            if (updated == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "OCCUPANCY_ALREADY_CANCELLED",
                        "占用已被并发取消: " + request.occupancyId());
            }
            BandPo band = airspaceRepo.findBand(po.zoneId(), po.bandId());
            OccupancyPo cancelled = new OccupancyPo(po.occupancyId(), po.reviewId(), po.routeId(),
                    po.routeVersion(), po.airspaceVersion(), po.zoneId(), po.bandId(),
                    po.cruiseAltitudeM(), po.startAt(), po.endAt(),
                    OccupancyStatus.CANCELLED.name(), po.requestId(), po.createdAt(), nowMillis());
            return new MutationResponse(request.requestId(), false,
                    toOccupancyResult(cancelled, band));
        });
    }

    /** 按时段查询区域（可选限定高度带）的占用记录，含已取消历史。 */
    public OccupancyListResult getOccupancies(String zoneId, String bandId,
                                              Long fromAt, Long toAt) {
        if (airspaceRepo.findZone(zoneId) == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ZONE_NOT_FOUND",
                    "禁飞区不存在: " + zoneId);
        }
        if (fromAt == null || toAt == null || fromAt >= toAt) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TIME_RANGE",
                    "查询时段必须满足 fromAt < toAt");
        }
        Map<String, BandPo> bandsById = new HashMap<>();
        for (BandPo band : airspaceRepo.findBands(zoneId)) {
            bandsById.put(band.bandId(), band);
        }
        if (bandId != null && !bandsById.containsKey(bandId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "BAND_NOT_FOUND",
                    "高度带不存在: " + zoneId + "/" + bandId);
        }
        List<OccupancyResult> results = new ArrayList<>();
        for (OccupancyPo po : occupancyRepo.findOverlapping(zoneId, bandId, fromAt, toAt)) {
            results.add(toOccupancyResult(po, bandsById.get(po.bandId())));
        }
        return new OccupancyListResult(zoneId, bandId, fromAt, toAt, results);
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

    private static void validateTimeRange(long startAt, long endAt) {
        if (startAt >= endAt) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ROUTE_TIME_RANGE_INVALID",
                    "巡航起止时刻必须满足 startAt < endAt（左闭右开）");
        }
    }

    /**
     * 校验一组高度带请求参数：下限必须小于上限、标识不得重复、两两不得重叠
     * （端点相接合法）。容量范围由 Bean Validation 保证。
     */
    private static void validateBandList(List<AltitudeBandDto> bands) {
        Set<String> ids = new TreeSet<>();
        List<int[]> ranges = new ArrayList<>();
        for (AltitudeBandDto band : bands) {
            if (band.lowerM() >= band.upperM()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BAND_RANGE",
                        "高度带必须满足 lowerM < upperM（左闭右开）: " + band.bandId());
            }
            if (!ids.add(band.bandId())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "BAND_ID_DUPLICATED",
                        "高度带标识重复: " + band.bandId());
            }
            ranges.add(new int[]{band.lowerM(), band.upperM()});
        }
        if (AltitudeBands.anyOverlap(ranges)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAND_OVERLAP",
                    "同一区域高度带不得重叠（端点相接合法）");
        }
    }

    private static Map<String, List<BandPo>> groupBandsByZone(List<BandPo> bands) {
        Map<String, List<BandPo>> byZone = new HashMap<>();
        for (BandPo band : bands) {
            byZone.computeIfAbsent(band.zoneId(), k -> new ArrayList<>()).add(band);
        }
        return byZone;
    }

    private ZoneBandsResult toBandsResult(ZonePo zone, int configVersion) {
        List<AltitudeBandDto> bands = new ArrayList<>();
        for (BandPo band : airspaceRepo.findBands(zone.zoneId())) {
            bands.add(new AltitudeBandDto(band.bandId(), band.lowerM(), band.upperM(),
                    band.capacity()));
        }
        return new ZoneBandsResult(zone.zoneId(), zone.status(), configVersion, bands);
    }

    private static OccupancyResult toOccupancyResult(OccupancyPo po, BandPo band) {
        boolean consumes = band != null && AltitudeBands.contains(
                po.cruiseAltitudeM(), band.lowerM(), band.upperM());
        return new OccupancyResult(po.occupancyId(), po.reviewId(), po.routeId(),
                po.zoneId(), po.bandId(), po.cruiseAltitudeM(), po.startAt(), po.endAt(),
                consumes, po.status());
    }

    private static ReviewResultDto toDto(ReviewPo po, String conclusion, Boolean current) {
        List<RoutePointDto> snapshot = new ArrayList<>(po.pointsSnapshot().size());
        for (Point p : po.pointsSnapshot()) {
            snapshot.add(new RoutePointDto(p.x(), p.y()));
        }
        return new ReviewResultDto(po.reviewId(), po.routeId(), po.routeVersion(),
                po.airspaceVersion(), conclusion, List.copyOf(po.hitZoneIds()), snapshot, current);
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
