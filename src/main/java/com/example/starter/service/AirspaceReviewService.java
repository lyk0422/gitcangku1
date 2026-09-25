package com.example.starter.service;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.BandConfigDto;
import com.example.starter.api.dto.BandConfigureRequest;
import com.example.starter.api.dto.BandConfigureResult;
import com.example.starter.api.dto.BandResultDto;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.OccupationCancelRequest;
import com.example.starter.api.dto.OccupationCreateRequest;
import com.example.starter.api.dto.OccupationResultDto;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.ReviewResultDto;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.RouteReplaceRequest;
import com.example.starter.api.dto.RouteResult;
import com.example.starter.api.dto.VerticalBandDto;
import com.example.starter.api.dto.VerticalZoneDto;
import com.example.starter.api.dto.ZoneBandsView;
import com.example.starter.api.dto.ZoneCreateRequest;
import com.example.starter.api.dto.ZoneResult;
import com.example.starter.api.dto.ZoneRevokeRequest;
import com.example.starter.domain.AltitudeIntervals;
import com.example.starter.domain.Geometry;
import com.example.starter.domain.OccupationStatus;
import com.example.starter.domain.Point;
import com.example.starter.domain.ReviewConclusion;
import com.example.starter.domain.ZoneStatus;
import com.example.starter.repo.AirspaceRepository;
import com.example.starter.repo.BandPo;
import com.example.starter.repo.DedupPo;
import com.example.starter.repo.OccupationPo;
import com.example.starter.repo.OccupationRepository;
import com.example.starter.repo.ReviewPo;
import com.example.starter.repo.ReviewRepository;
import com.example.starter.repo.RoutePo;
import com.example.starter.repo.RouteRepository;
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
 * 禁飞区、航线审查与高度层容量业务服务。
 *
 * <p>所有写操作在同一数据库事务内完成业务变更与幂等去重记录的原子提交；
 * 失败（含业务冲突）回滚事务，不占用 requestId。</p>
 *
 * <p>审查命中语义：未登记高度带的区域是纯禁飞区，二维路径相交即拦截（BLOCKED）；
 * 登记了高度带的区域是容量管理空域，不拦截——审查仅对二维路径相交的区域逐高度带
 * 做垂直分离判定并保存明细；巡航高度进入某左闭右开高度带的 CLEAR 航线可创建该带
 * 占用并消耗容量，高度不相交（垂直分离）的航线不消耗该带容量。</p>
 *
 * <p>锁顺序（避免死锁）：协调锁行 → 航线行 → 区域行 → 高度带行。
 * 审核、占用与区域/高度带变更事务都先更新协调锁行串行化，保证版本号与
 * 区域、高度带集合来自同一已提交状态；占用创建/取消再对高度带行加锁，
 * 使同一高度带的容量计数与占用写入串行，容量不超卖。</p>
 */
@Service
public class AirspaceReviewService {

    static final String KIND_ZONE_CREATE = "ZONE_CREATE";
    static final String KIND_ZONE_REVOKE = "ZONE_REVOKE";
    static final String KIND_ROUTE_CREATE = "ROUTE_CREATE";
    static final String KIND_ROUTE_REPLACE = "ROUTE_REPLACE";
    static final String KIND_REVIEW = "REVIEW";
    static final String KIND_BAND_CONFIGURE = "BAND_CONFIGURE";
    static final String KIND_OCCUPATION_CREATE = "OCCUPATION_CREATE";
    static final String KIND_OCCUPATION_CANCEL = "OCCUPATION_CANCEL";

    private final AirspaceRepository airspaceRepo;
    private final RouteRepository routeRepo;
    private final ReviewRepository reviewRepo;
    private final OccupationRepository occupationRepo;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate txTemplate;

    public AirspaceReviewService(AirspaceRepository airspaceRepo,
                                 RouteRepository routeRepo,
                                 ReviewRepository reviewRepo,
                                 OccupationRepository occupationRepo,
                                 ObjectMapper objectMapper,
                                 Clock clock,
                                 PlatformTransactionManager transactionManager) {
        this.airspaceRepo = airspaceRepo;
        this.routeRepo = routeRepo;
        this.reviewRepo = reviewRepo;
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
                    request.xMax(), request.yMax(), ZoneStatus.ACTIVE.name(), newVersion, null, 1));
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
     * 配置区域高度带：只允许上调已存在带容量或新增不重叠带；
     * 携带区域 expectedVersion，冲突 409；成功使区域配置版本与全局空域版本各加一。
     * 不追溯改写已有占用记录。
     */
    public MutationResponse configureBands(BandConfigureRequest request) {
        return withIdempotency(request.requestId(), KIND_BAND_CONFIGURE, canonicalHash(request), () -> {
            // 先锁协调行：与审核/占用及其他区域变更事务互斥
            airspaceRepo.getGlobalVersionForUpdate();
            // 再锁区域行：同一区域的高度带配置串行
            ZonePo zone = airspaceRepo.findZoneForUpdate(request.zoneId());
            if (zone == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "ZONE_NOT_FOUND",
                        "禁飞区不存在: " + request.zoneId());
            }
            if (ZoneStatus.REVOKED.name().equals(zone.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "ZONE_ALREADY_REVOKED",
                        "禁飞区已撤销，不能配置高度带: " + request.zoneId());
            }
            if (zone.zoneVersion() != request.expectedVersion()) {
                throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                        "区域配置版本不匹配：expected=" + request.expectedVersion()
                                + ", current=" + zone.zoneVersion());
            }
            List<BandPo> existing = airspaceRepo.findBandsByZone(zone.zoneId());
            Map<String, BandPo> existingById = new HashMap<>();
            for (BandPo band : existing) {
                existingById.put(band.bandId(), band);
            }
            List<BandConfigDto> desired = validateBandConfig(request, existing, existingById);

            long newGlobalVersion = airspaceRepo.incrementGlobalVersion();
            // 条件更新兜底并发配置：更新 0 行说明区域版本已被其他事务推进
            int updated = airspaceRepo.compareAndIncrementZoneVersion(
                    zone.zoneId(), request.expectedVersion());
            if (updated == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                        "区域配置版本已变化，请使用最新 expectedVersion 重试");
            }
            List<BandResultDto> results = new ArrayList<>(desired.size());
            for (BandConfigDto dto : desired) {
                BandPo prior = existingById.get(dto.bandId());
                if (prior == null) {
                    airspaceRepo.insertBand(new BandPo(dto.bandId(), zone.zoneId(),
                            dto.lowerAltitude(), dto.upperAltitude(), dto.capacity(),
                            newGlobalVersion));
                    results.add(new BandResultDto(dto.bandId(), dto.lowerAltitude(),
                            dto.upperAltitude(), dto.capacity(), true));
                } else {
                    if (dto.capacity() > prior.capacity()) {
                        airspaceRepo.raiseBandCapacity(dto.bandId(), dto.capacity());
                    }
                    results.add(new BandResultDto(dto.bandId(), prior.lowerAltitude(),
                            prior.upperAltitude(), Math.max(dto.capacity(), prior.capacity()),
                            false));
                }
            }
            return new MutationResponse(request.requestId(), false,
                    new BandConfigureResult(zone.zoneId(), request.expectedVersion() + 1,
                            newGlobalVersion, List.copyOf(results)));
        });
    }

    /**
     * 校验高度带配置：范围合法、请求内不重叠、已存在带只能上调容量且边界不可变、
     * 不允许删除带、新增带不与已有带重叠。
     *
     * @return 规范化后的配置项列表（按 bandId 字典序，保证并发落库顺序一致）
     */
    private List<BandConfigDto> validateBandConfig(BandConfigureRequest request,
                                                   List<BandPo> existing,
                                                   Map<String, BandPo> existingById) {
        if (request.bands() == null || request.bands().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BAND_CONFIG",
                    "高度带配置不能为空");
        }
        List<BandConfigDto> desired = new ArrayList<>(request.bands());
        desired.sort((a, b) -> a.bandId().compareTo(b.bandId()));
        Set<String> seenIds = new TreeSet<>();
        for (BandConfigDto dto : desired) {
            if (dto.bandId() == null || dto.bandId().isBlank() || dto.bandId().length() > 64) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BAND_ID",
                        "高度带标识不能为空且不超过 64 字符");
            }
            if (!seenIds.add(dto.bandId())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "DUPLICATE_BAND_ID",
                        "请求内高度带标识重复: " + dto.bandId());
            }
            if (dto.lowerAltitude() >= dto.upperAltitude()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BAND_RANGE",
                        "高度带必须满足 lowerAltitude < upperAltitude: " + dto.bandId());
            }
            if (dto.capacity() < 1 || dto.capacity() > 50) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BAND_CAPACITY",
                        "高度带容量必须在 1~50 之间: " + dto.bandId());
            }
        }
        // 已存在带：必须全部保留、边界不可变、容量只升不降
        for (BandPo prior : existing) {
            BandConfigDto dto = null;
            for (BandConfigDto candidate : desired) {
                if (candidate.bandId().equals(prior.bandId())) {
                    dto = candidate;
                    break;
                }
            }
            if (dto == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "BAND_REMOVAL_NOT_ALLOWED",
                        "不允许删除已存在高度带: " + prior.bandId());
            }
            if (dto.lowerAltitude() != prior.lowerAltitude()
                    || dto.upperAltitude() != prior.upperAltitude()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "BAND_RANGE_IMMUTABLE",
                        "不允许修改已存在高度带边界: " + prior.bandId());
            }
            if (dto.capacity() < prior.capacity()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "BAND_CAPACITY_NOT_RAISABLE",
                        "高度带容量只允许上调: " + prior.bandId()
                                + ", current=" + prior.capacity());
            }
        }
        // 新增带：bandId 全局唯一，且与已有带、其他新增带均不重叠（端点相接合法）
        List<BandConfigDto> additions = new ArrayList<>();
        for (BandConfigDto dto : desired) {
            if (existingById.containsKey(dto.bandId())) {
                continue;
            }
            BandPo elsewhere = airspaceRepo.findBand(dto.bandId());
            if (elsewhere != null) {
                throw new ApiException(HttpStatus.CONFLICT, "BAND_ALREADY_EXISTS",
                        "高度带标识已存在: " + dto.bandId());
            }
            additions.add(dto);
        }
        List<long[]> ranges = new ArrayList<>();
        for (BandPo prior : existing) {
            ranges.add(new long[]{prior.lowerAltitude(), prior.upperAltitude()});
        }
        for (BandConfigDto add : additions) {
            for (long[] range : ranges) {
                if (AltitudeIntervals.overlaps(add.lowerAltitude(), add.upperAltitude(),
                        range[0], range[1])) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "BAND_OVERLAP",
                            "新增高度带与已有带重叠: " + add.bandId());
                }
            }
            ranges.add(new long[]{add.lowerAltitude(), add.upperAltitude()});
        }
        return desired;
    }

    /** 查询区域高度带配置（含区域配置版本，供下次配置携带 expectedVersion）。 */
    public ZoneBandsView getZoneBands(String zoneId) {
        ZonePo zone = airspaceRepo.findZone(zoneId);
        if (zone == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ZONE_NOT_FOUND",
                    "禁飞区不存在: " + zoneId);
        }
        List<BandResultDto> bands = new ArrayList<>();
        for (BandPo band : airspaceRepo.findBandsByZone(zoneId)) {
            bands.add(new BandResultDto(band.bandId(), band.lowerAltitude(),
                    band.upperAltitude(), band.capacity(), false));
        }
        return new ZoneBandsView(zone.zoneId(), zone.status(), zone.zoneVersion(),
                List.copyOf(bands));
    }

    // ============================ 航线 ============================

    /** 创建航线（初始版本 1），携带巡航高度与 UTC 时间窗。 */
    public MutationResponse createRoute(RouteCreateRequest request) {
        return withIdempotency(request.requestId(), KIND_ROUTE_CREATE, canonicalHash(request), () -> {
            List<Point> points = toPoints(request.points());
            validatePointsDistinct(points);
            validateTimeWindow(request.startUtc(), request.endUtc());
            if (routeRepo.findRoute(request.routeId()) != null) {
                throw new ApiException(HttpStatus.CONFLICT, "ROUTE_ALREADY_EXISTS",
                        "航线已存在: " + request.routeId());
            }
            routeRepo.insertRoute(request.routeId(), points,
                    request.cruiseAltitude(), request.startUtc(), request.endUtc());
            return new MutationResponse(request.requestId(), false,
                    new RouteResult(request.routeId(), 1));
        });
    }

    /** 替换航线点列与飞行剖面，expectedVersion 不匹配返回 409；成功版本加一。 */
    public MutationResponse replaceRoute(RouteReplaceRequest request) {
        return withIdempotency(request.requestId(), KIND_ROUTE_REPLACE, canonicalHash(request), () -> {
            List<Point> points = toPoints(request.points());
            validatePointsDistinct(points);
            validateTimeWindow(request.startUtc(), request.endUtc());
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
            routeRepo.updateFlightProfile(request.routeId(),
                    request.cruiseAltitude(), request.startUtc(), request.endUtc());
            return new MutationResponse(request.requestId(), false,
                    new RouteResult(request.routeId(), request.expectedVersion() + 1));
        });
    }

    // ============================ 审核 ============================

    /**
     * 提交审核：任一指定版本不是当前版本返回 409；结果不可变。
     * 命中 = 二维路径与未登记高度带的纯禁飞区相交；登记高度带的容量管理空域
     * 不拦截，仅对二维相交区域逐带生成垂直分离明细并随结果不可变保存。
     */
    public MutationResponse review(ReviewRequest request) {
        return withIdempotency(request.requestId(), KIND_REVIEW, canonicalHash(request), () -> {
            // 先锁空域版本行：与任何区域创建/撤销/高度带配置事务互斥
            long globalVersion = airspaceRepo.getGlobalVersionForUpdate();
            // 再锁航线行：与航线替换事务互斥，保证版本号与点列、剖面一致
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
            Set<String> hits = new TreeSet<>();
            List<VerticalZoneDto> detail = new ArrayList<>();
            for (ZonePo zone : activeZones) {
                if (!Geometry.polylineHitsRectangle(route.points(),
                        zone.xMin(), zone.yMin(), zone.xMax(), zone.yMax())) {
                    continue;
                }
                List<BandPo> bands = airspaceRepo.findBandsByZone(zone.zoneId());
                // 未登记高度带的区域是纯禁飞区：二维相交即拦截；
                // 登记了高度带的区域是容量管理空域：不拦截，逐带做垂直分离判定，
                // 高度相交的带可被 CLEAR 航线占用并消耗容量
                boolean blocked = bands.isEmpty();
                if (blocked) {
                    hits.add(zone.zoneId());
                }
                List<VerticalBandDto> bandDtos = new ArrayList<>(bands.size());
                for (BandPo band : bands) {
                    boolean overlap = AltitudeIntervals.contains(route.cruiseAltitude(),
                            band.lowerAltitude(), band.upperAltitude());
                    bandDtos.add(new VerticalBandDto(band.bandId(), band.lowerAltitude(),
                            band.upperAltitude(), band.capacity(), overlap));
                }
                detail.add(new VerticalZoneDto(zone.zoneId(), true, blocked,
                        List.copyOf(bandDtos)));
            }
            String conclusion = hits.isEmpty()
                    ? ReviewConclusion.CLEAR.name()
                    : ReviewConclusion.BLOCKED.name();
            String reviewId = "rv_" + UUID.randomUUID();
            ReviewPo po = new ReviewPo(reviewId, request.routeId(), route.version(), globalVersion,
                    conclusion, new ArrayList<>(hits), List.copyOf(route.points()),
                    route.cruiseAltitude(), route.startUtc(), route.endUtc(),
                    writeJson(detail), request.requestId(), nowMillis());
            reviewRepo.insertReview(po);
            return new MutationResponse(request.requestId(), false, toDto(po, conclusion, true));
        });
    }

    /** 按 reviewId 查询历史审核，保留原结论与垂直分离明细。 */
    public ReviewResultDto getReview(String reviewId) {
        ReviewPo po = reviewRepo.findReview(reviewId);
        if (po == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND",
                    "审核记录不存在: " + reviewId);
        }
        // 历史查询永远返回保存时的原结论，不重新计算
        return toDto(po, po.conclusion(), null);
    }

    /** 查询某审核的垂直分离明细（二维相交区域逐高度带判定快照）。 */
    public List<VerticalZoneDto> getVerticalSeparation(String reviewId) {
        ReviewPo po = reviewRepo.findReview(reviewId);
        if (po == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND",
                    "审核记录不存在: " + reviewId);
        }
        return parseVerticalDetail(po.verticalDetail());
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

    // ============================ 高度层占用 ============================

    /**
     * 创建高度层占用：仅当关联审查当前仍为 CLEAR 时允许；
     * 关联审查因航线或空域版本变化变为 STALE 时返回 422。
     *
     * <p>在持锁事务内按高度带与时间重叠统计 ACTIVE 占用数，达到容量返回 429
     * 并给出区域、高度带与占用数；否则创建 ACTIVE 占用并关联该审查版本。
     * 巡航高度不进入目标高度带（高度不相交）时不消耗容量，返回 422 拒绝。</p>
     */
    public MutationResponse createOccupation(OccupationCreateRequest request) {
        return withIdempotency(request.requestId(), KIND_OCCUPATION_CREATE,
                canonicalHash(request), () -> {
            ReviewPo review = reviewRepo.findReview(request.reviewId());
            if (review == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND",
                        "审核记录不存在: " + request.reviewId());
            }
            if (!ReviewConclusion.CLEAR.name().equals(review.conclusion())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REVIEW_NOT_CLEAR",
                        "仅审查结论为 CLEAR 的航线可创建高度层占用: " + request.reviewId());
            }
            // 锁协调行与航线行：与区域变更、航线替换事务互斥，
            // 保证 STALE 判定与占用写入基于同一已提交状态
            long globalVersion = airspaceRepo.getGlobalVersionForUpdate();
            RoutePo route = routeRepo.findRouteForUpdate(review.routeId());
            boolean current = route != null
                    && route.version() == review.routeVersion()
                    && globalVersion == review.airspaceVersion();
            if (!current) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REVIEW_STALE",
                        "关联审查已因航线或空域版本变化失效（STALE），请重新审查后再创建占用: "
                                + request.reviewId());
            }
            // 锁区域行与高度带行：容量计数与占用写入串行
            ZonePo zone = airspaceRepo.findZoneForUpdate(request.zoneId());
            if (zone == null || !ZoneStatus.ACTIVE.name().equals(zone.status())) {
                throw new ApiException(HttpStatus.NOT_FOUND, "ZONE_NOT_FOUND",
                        "禁飞区不存在或已撤销: " + request.zoneId());
            }
            if (!Geometry.polylineHitsRectangle(review.pointsSnapshot(),
                    zone.xMin(), zone.yMin(), zone.xMax(), zone.yMax())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ZONE_NOT_INTERSECTED",
                        "审查航线的二维路径不与该区域相交: " + request.zoneId());
            }
            BandPo band = airspaceRepo.findBandForUpdate(request.bandId());
            if (band == null || !band.zoneId().equals(zone.zoneId())) {
                throw new ApiException(HttpStatus.NOT_FOUND, "BAND_NOT_FOUND",
                        "高度带不存在或不属于该区域: " + request.bandId());
            }
            if (!AltitudeIntervals.contains(review.cruiseAltitude(),
                    band.lowerAltitude(), band.upperAltitude())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ALTITUDE_NOT_OVERLAPPING",
                        "巡航高度不进入该高度带，不消耗其容量: band=" + request.bandId()
                                + ", cruiseAltitude=" + review.cruiseAltitude());
            }
            int activeCount = occupationRepo.countActiveOverlapping(
                    band.bandId(), review.startUtc(), review.endUtc());
            if (activeCount >= band.capacity()) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("zoneId", zone.zoneId());
                details.put("bandId", band.bandId());
                details.put("activeCount", activeCount);
                details.put("capacity", band.capacity());
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "CAPACITY_EXCEEDED",
                        "高度带容量已满: zone=" + zone.zoneId() + ", band=" + band.bandId()
                                + ", active=" + activeCount + ", capacity=" + band.capacity(),
                        details);
            }
            OccupationPo po = new OccupationPo("occ_" + UUID.randomUUID(), review.reviewId(),
                    review.routeId(), zone.zoneId(), band.bandId(),
                    review.startUtc(), review.endUtc(), review.cruiseAltitude(),
                    OccupationStatus.ACTIVE.name(), request.requestId(), nowMillis(), null);
            occupationRepo.insertOccupation(po);
            return new MutationResponse(request.requestId(), false, toDto(po));
        });
    }

    /** 取消占用：立即释放容量，记录作为历史保留；重复取消 409。 */
    public MutationResponse cancelOccupation(OccupationCancelRequest request) {
        return withIdempotency(request.requestId(), KIND_OCCUPATION_CANCEL,
                canonicalHash(request), () -> {
            OccupationPo existing = occupationRepo.findOccupation(request.occupationId());
            if (existing == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "OCCUPATION_NOT_FOUND",
                        "占用记录不存在: " + request.occupationId());
            }
            if (OccupationStatus.CANCELLED.name().equals(existing.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "OCCUPATION_ALREADY_CANCELLED",
                        "占用已取消: " + request.occupationId());
            }
            // 条件更新兜底并发取消：更新 0 行说明已被其他事务取消
            int updated = occupationRepo.cancelIfActive(request.occupationId(), nowMillis());
            if (updated == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "OCCUPATION_ALREADY_CANCELLED",
                        "占用已被并发取消: " + request.occupationId());
            }
            OccupationPo cancelled = occupationRepo.findOccupation(request.occupationId());
            return new MutationResponse(request.requestId(), false, toDto(cancelled));
        });
    }

    /** 按时段查询占用（含已取消历史）；zoneId 为空时不限区域。 */
    public List<OccupationResultDto> listOccupations(String zoneId, Long fromUtc, Long toUtc) {
        if (fromUtc == null || toUtc == null || fromUtc >= toUtc) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TIME_WINDOW",
                    "查询时段必须满足 fromUtc < toUtc");
        }
        List<OccupationResultDto> result = new ArrayList<>();
        for (OccupationPo po : occupationRepo.findOverlapping(zoneId, fromUtc, toUtc)) {
            result.add(toDto(po));
        }
        return List.copyOf(result);
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

    private static void validateTimeWindow(long startUtc, long endUtc) {
        if (startUtc >= endUtc) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TIME_WINDOW",
                    "航线 UTC 时间窗必须满足 startUtc < endUtc");
        }
    }

    private ReviewResultDto toDto(ReviewPo po, String conclusion, Boolean current) {
        List<RoutePointDto> snapshot = new ArrayList<>(po.pointsSnapshot().size());
        for (Point p : po.pointsSnapshot()) {
            snapshot.add(new RoutePointDto(p.x(), p.y()));
        }
        return new ReviewResultDto(po.reviewId(), po.routeId(), po.routeVersion(),
                po.airspaceVersion(), conclusion, List.copyOf(po.hitZoneIds()), snapshot,
                po.cruiseAltitude(), po.startUtc(), po.endUtc(),
                parseVerticalDetail(po.verticalDetail()), current);
    }

    private List<VerticalZoneDto> parseVerticalDetail(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<VerticalZoneDto>>() {
            });
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("无法解析垂直分离明细快照", ex);
        }
    }

    private static OccupationResultDto toDto(OccupationPo po) {
        return new OccupationResultDto(po.occupationId(), po.reviewId(), po.routeId(),
                po.zoneId(), po.bandId(), po.startUtc(), po.endUtc(), po.cruiseAltitude(),
                po.status(), po.createdAt(), po.cancelledAt());
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
