package com.example.starter.service;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.BandConfigRequest;
import com.example.starter.api.dto.BandConfigResult;
import com.example.starter.api.dto.BandSpecDto;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.OccupancyCancelRequest;
import com.example.starter.api.dto.OccupancyCreateRequest;
import com.example.starter.api.dto.OccupancyResult;
import com.example.starter.api.dto.VerticalDetailDto;
import com.example.starter.domain.Altitudes;
import com.example.starter.domain.ZoneStatus;
import com.example.starter.repo.AirspaceRepository;
import com.example.starter.repo.BandPo;
import com.example.starter.repo.BandRepository;
import com.example.starter.repo.OccupancyPo;
import com.example.starter.repo.OccupancyRepository;
import com.example.starter.repo.ReviewPo;
import com.example.starter.repo.ReviewRepository;
import com.example.starter.repo.RoutePo;
import com.example.starter.repo.RouteRepository;
import com.example.starter.repo.VerticalDetailPo;
import com.example.starter.repo.ZonePo;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 高度层容量与航线垂直分离业务服务。
 *
 * <p>占用创建在单事务内按固定加锁顺序（协调锁 → 航线行 → 高度带行）串行裁决：
 * 协调锁与区域创建/撤销/高度带修改/审查互斥，高度带行锁使同一带的
 * “计数重叠 ACTIVE 占用 + 插入新占用”原子化，容量绝不超卖。</p>
 *
 * <p>关联审查因航线或空域版本变化变为 STALE 时拒绝创建占用（422）；
 * 容量已满返回 429，业务失败回滚、不占用 requestId。</p>
 */
@Service
public class AltitudeService {

    static final String OCCUPANCY_ACTIVE = "ACTIVE";
    static final String OCCUPANCY_CANCELLED = "CANCELLED";

    private final AirspaceRepository airspaceRepo;
    private final RouteRepository routeRepo;
    private final ReviewRepository reviewRepo;
    private final BandRepository bandRepo;
    private final OccupancyRepository occupancyRepo;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public AltitudeService(AirspaceRepository airspaceRepo,
                           RouteRepository routeRepo,
                           ReviewRepository reviewRepo,
                           BandRepository bandRepo,
                           OccupancyRepository occupancyRepo,
                           IdempotencyService idempotency,
                           Clock clock) {
        this.airspaceRepo = airspaceRepo;
        this.routeRepo = routeRepo;
        this.reviewRepo = reviewRepo;
        this.bandRepo = bandRepo;
        this.occupancyRepo = occupancyRepo;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    // ============================ 高度带配置 ============================

    /**
     * 修改区域高度带配置：仅允许上调既有带容量或新增不重叠带；
     * 携带区域 expectedVersion，冲突 409；不追溯改写已有占用记录。
     * 修改成功推进全局空域版本，使关联旧审查变为 STALE。
     */
    public MutationResponse configureBands(BandConfigRequest request) {
        return idempotency.execute(request.requestId(), IdempotencyService.KIND_BAND_CONFIG,
                idempotency.canonicalHash(request), () -> {
            // 先取协调锁：与审查、占用、区域创建/撤销互斥，并推进全局空域版本
            airspaceRepo.incrementGlobalVersion();
            // 再锁区域行：串行化同一区域的并发配置修改
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
                throw new ApiException(HttpStatus.CONFLICT, "ZONE_VERSION_CONFLICT",
                        "区域高度带配置版本不匹配：expected=" + request.expectedVersion()
                                + ", current=" + zone.zoneVersion());
            }
            List<BandPo> existing = bandRepo.findBands(request.zoneId());
            List<BandSpecDto> requested = normalizeAndValidate(request.bands());
            applyBandChanges(request.zoneId(), existing, requested);

            // 乐观锁兜底并发：更新 0 行说明区域版本已被其他事务推进
            int updated = airspaceRepo.compareAndIncrementZoneVersion(
                    request.zoneId(), request.expectedVersion());
            if (updated == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "ZONE_VERSION_CONFLICT",
                        "区域高度带配置版本已变化，请使用最新 expectedVersion 重试");
            }
            List<BandSpecDto> current = bandRepo.findBands(request.zoneId()).stream()
                    .map(b -> new BandSpecDto(b.bandLower(), b.bandUpper(), b.capacity()))
                    .toList();
            return new MutationResponse(request.requestId(), false,
                    new BandConfigResult(request.zoneId(),
                            request.expectedVersion() + 1, current));
        });
    }

    /** 查询区域当前高度带配置（按下限升序）；区域不存在 404。 */
    public BandConfigResult getBands(String zoneId) {
        ZonePo zone = airspaceRepo.findZone(zoneId);
        if (zone == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ZONE_NOT_FOUND",
                    "禁飞区不存在: " + zoneId);
        }
        List<BandSpecDto> bands = bandRepo.findBands(zoneId).stream()
                .map(b -> new BandSpecDto(b.bandLower(), b.bandUpper(), b.capacity()))
                .toList();
        return new BandConfigResult(zoneId, zone.zoneVersion(), bands);
    }

    /**
     * 规范化并校验请求的完整配置：带必须非退化、同区域不重叠（端点相接合法）。
     */
    private static List<BandSpecDto> normalizeAndValidate(List<BandSpecDto> bands) {
        List<BandSpecDto> sorted = new ArrayList<>(bands);
        sorted.sort(Comparator.comparingInt(BandSpecDto::bandLower));
        for (BandSpecDto band : sorted) {
            if (band.bandLower() >= band.bandUpper()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ALTITUDE_BAND",
                        "高度带必须满足 bandLower < bandUpper: "
                                + band.bandLower() + "~" + band.bandUpper());
            }
        }
        for (int i = 1; i < sorted.size(); i++) {
            BandSpecDto prev = sorted.get(i - 1);
            BandSpecDto cur = sorted.get(i);
            if (Altitudes.bandsOverlap(prev.bandLower(), prev.bandUpper(),
                    cur.bandLower(), cur.bandUpper())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "BANDS_OVERLAP",
                        "同一区域高度带不得重叠：" + prev.bandLower() + "~" + prev.bandUpper()
                                + " 与 " + cur.bandLower() + "~" + cur.bandUpper());
            }
        }
        return sorted;
    }

    /**
     * 应用配置变更：既有带必须保留且边界不变、容量只能上调；其余视为新增带，
     * 必须与全部既有带不重叠（端点相接合法）。
     */
    private void applyBandChanges(String zoneId, List<BandPo> existing,
                                  List<BandSpecDto> requested) {
        Map<Integer, BandPo> existingByLower = new HashMap<>();
        for (BandPo band : existing) {
            existingByLower.put(band.bandLower(), band);
        }
        for (BandSpecDto band : requested) {
            BandPo old = existingByLower.remove(band.bandLower());
            if (old != null) {
                if (old.bandUpper() != band.bandUpper()) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "BAND_BOUNDARY_IMMUTABLE",
                            "高度带边界不允许修改：下限 " + band.bandLower()
                                    + " 的带上限原为 " + old.bandUpper());
                }
                if (band.capacity() < old.capacity()) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "CAPACITY_ONLY_INCREASE",
                            "高度带容量只允许上调：下限 " + band.bandLower()
                                    + " 当前容量 " + old.capacity()
                                    + "，请求容量 " + band.capacity());
                }
                if (band.capacity() > old.capacity()) {
                    bandRepo.updateCapacity(zoneId, band.bandLower(), band.capacity());
                }
            } else {
                // 新增带：与所有未被请求覆盖的既有带也不得重叠
                for (BandPo other : existingByLower.values()) {
                    if (Altitudes.bandsOverlap(band.bandLower(), band.bandUpper(),
                            other.bandLower(), other.bandUpper())) {
                        throw new ApiException(HttpStatus.BAD_REQUEST, "BANDS_OVERLAP",
                                "新增高度带与既有带重叠：" + band.bandLower() + "~" + band.bandUpper()
                                        + " 与 " + other.bandLower() + "~" + other.bandUpper());
                    }
                }
                bandRepo.insertBand(new BandPo(zoneId, band.bandLower(),
                        band.bandUpper(), band.capacity()));
            }
        }
        if (!existingByLower.isEmpty()) {
            int missingLower = existingByLower.keySet().stream()
                    .min(Integer::compareTo).orElseThrow();
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAND_REMOVAL_FORBIDDEN",
                    "高度带不允许删除，配置缺少既有的下限为 " + missingLower + " 的高度带");
        }
    }

    // ============================ 占用 ============================

    /**
     * 为 CLEAR 审查创建高度层占用。容量满返回 429（不占键）；
     * 审查已 STALE 返回 422；审查/区域/高度带不匹配返回相应错误。
     */
    public MutationResponse createOccupancy(OccupancyCreateRequest request) {
        return idempotency.execute(request.requestId(), IdempotencyService.KIND_OCCUPANCY_CREATE,
                idempotency.canonicalHash(request), () -> {
            // 1) 协调锁：与审查、区域/高度带变更互斥，保证版本判定与容量裁决基于同一已提交状态
            long globalVersion = airspaceRepo.getGlobalVersionForUpdate();
            ReviewPo review = reviewRepo.findReview(request.reviewId());
            if (review == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND",
                        "审核记录不存在: " + request.reviewId());
            }
            if (!"CLEAR".equals(review.conclusion())) {
                throw new ApiException(HttpStatus.CONFLICT, "REVIEW_NOT_CLEAR",
                        "仅审查结果为 CLEAR 的航线可创建高度层占用: " + request.reviewId());
            }
            // 2) 锁航线行并复核版本：任一版本变化即 STALE，拒绝占用（422）
            RoutePo route = routeRepo.findRouteForUpdate(review.routeId());
            if (route == null || route.version() != review.routeVersion()
                    || globalVersion != review.airspaceVersion()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REVIEW_STALE",
                        "关联审查已因航线或空域版本变化失效，请重新审查后再占用: "
                                + request.reviewId());
            }
            // 3) 区域与高度带必须与审查时的垂直命中明细一致
            VerticalDetailPo detail = reviewRepo.findVerticalDetails(request.reviewId()).stream()
                    .filter(d -> d.zoneId().equals(request.zoneId()))
                    .findFirst()
                    .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "ZONE_NOT_REVIEWED",
                            "审查明细中不存在该二维相交区域: " + request.zoneId()));
            if (!detail.verticalHit() || detail.bandLower() == null
                    || !detail.bandLower().equals(request.bandLower())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VERTICAL_SEPARATION",
                        "该区域与航线在指定高度带垂直分离，不能占用该高度带: "
                                + request.zoneId() + "@" + request.bandLower());
            }
            if (review.startTime() == null || review.endTime() == null) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "OCCUPANCY_WINDOW_MISSING",
                        "审查航线未登记 UTC 时段，不能创建高度层占用");
            }
            // 4) 锁高度带行：同一带的并发占用在此串行，计数+插入原子
            BandPo band = bandRepo.findBandForUpdate(request.zoneId(), request.bandLower());
            if (band == null) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "BAND_NOT_FOUND",
                        "高度带不存在: " + request.zoneId() + "@" + request.bandLower());
            }
            OccupancyPo sameReviewBand = occupancyRepo.findForReviewBand(
                    request.reviewId(), request.zoneId(), request.bandLower());
            if (sameReviewBand != null && OCCUPANCY_ACTIVE.equals(sameReviewBand.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "OCCUPANCY_ALREADY_ACTIVE",
                        "该审查在此高度带已有 ACTIVE 占用: " + sameReviewBand.occupancyId());
            }
            int activeCount = occupancyRepo.countActiveOverlaps(request.zoneId(),
                    request.bandLower(), review.startTime(), review.endTime());
            if (activeCount >= band.capacity()) {
                // 容量已满：429 给出区域、高度带与现有 ACTIVE 占用数；回滚不占 requestId
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "BAND_CAPACITY_EXCEEDED",
                        "高度带容量已满：zone=" + request.zoneId()
                                + ", bandLower=" + request.bandLower()
                                + ", activeOccupancies=" + activeCount
                                + ", capacity=" + band.capacity());
            }
            String occupancyId = "oc_" + UUID.randomUUID();
            OccupancyPo po = new OccupancyPo(occupancyId, request.reviewId(), review.routeId(),
                    request.zoneId(), band.bandLower(), band.bandUpper(),
                    review.startTime(), review.endTime(), OCCUPANCY_ACTIVE, nowMillis(), null);
            occupancyRepo.insertOccupancy(po);
            return new MutationResponse(request.requestId(), false,
                    toResult(po, band.capacity(), activeCount + 1));
        });
    }

    /** 取消占用：立即释放容量（状态置 CANCELLED），历史保留；重复取消 409。 */
    public MutationResponse cancelOccupancy(OccupancyCancelRequest request) {
        return idempotency.execute(request.requestId(), IdempotencyService.KIND_OCCUPANCY_CANCEL,
                idempotency.canonicalHash(request), () -> {
            OccupancyPo occupancy = occupancyRepo.findOccupancy(request.occupancyId());
            if (occupancy == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "OCCUPANCY_NOT_FOUND",
                        "占用记录不存在: " + request.occupancyId());
            }
            // 锁对应高度带行：与并发占用的计数裁决互斥，取消释放容量对后者立即可见
            BandPo band = bandRepo.findBandForUpdate(occupancy.zoneId(), occupancy.bandLower());
            int capacity = band == null ? 0 : band.capacity();
            int updated = occupancyRepo.cancelIfActive(request.occupancyId(), nowMillis());
            if (updated == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "OCCUPANCY_ALREADY_CANCELLED",
                        "占用已取消: " + request.occupancyId());
            }
            OccupancyPo cancelled = new OccupancyPo(occupancy.occupancyId(), occupancy.reviewId(),
                    occupancy.routeId(), occupancy.zoneId(), occupancy.bandLower(),
                    occupancy.bandUpper(), occupancy.startTime(), occupancy.endTime(),
                    OCCUPANCY_CANCELLED, occupancy.createdAt(), nowMillis());
            int activeCount = occupancyRepo.countActiveOverlaps(occupancy.zoneId(),
                    occupancy.bandLower(), occupancy.startTime(), occupancy.endTime());
            return new MutationResponse(request.requestId(), false,
                    toResult(cancelled, capacity, activeCount));
        });
    }

    /** 按 occupancyId 查询占用，不存在 404。 */
    public OccupancyResult getOccupancy(String occupancyId) {
        OccupancyPo po = occupancyRepo.findOccupancy(occupancyId);
        if (po == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "OCCUPANCY_NOT_FOUND",
                    "占用记录不存在: " + occupancyId);
        }
        BandPo band = bandRepo.findBands(po.zoneId()).stream()
                .filter(b -> b.bandLower() == po.bandLower()).findFirst().orElse(null);
        int capacity = band == null ? 0 : band.capacity();
        int activeCount = OCCUPANCY_ACTIVE.equals(po.status())
                ? occupancyRepo.countActiveOverlaps(po.zoneId(), po.bandLower(),
                        po.startTime(), po.endTime())
                : 0;
        return toResult(po, capacity, activeCount);
    }

    /**
     * 按时段查询占用：时间窗（左闭右开，epoch 毫秒 UTC）与占用时段相交即返回，
     * 可按区域与高度带下限过滤；含 ACTIVE 与 CANCELLED 历史。
     */
    public List<OccupancyResult> findOccupancies(Long windowStart, Long windowEnd,
                                                 String zoneId, Integer bandLower) {
        if (windowStart == null || windowEnd == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TIME_WINDOW_REQUIRED",
                    "查询必须提供 startTime 与 endTime（epoch 毫秒，UTC）");
        }
        if (windowStart >= windowEnd) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TIME_WINDOW",
                    "查询时间窗必须满足 startTime < endTime");
        }
        return occupancyRepo.findOccupanciesInWindow(zoneId, bandLower, windowStart, windowEnd)
                .stream().map(po -> {
                    Optional<BandPo> band = bandRepo.findBands(po.zoneId()).stream()
                            .filter(b -> b.bandLower() == po.bandLower()).findFirst();
                    int capacity = band.map(BandPo::capacity).orElse(0);
                    int activeCount = OCCUPANCY_ACTIVE.equals(po.status())
                            ? occupancyRepo.countActiveOverlaps(po.zoneId(), po.bandLower(),
                                    po.startTime(), po.endTime())
                            : 0;
                    return toResult(po, capacity, activeCount);
                }).toList();
    }

    /** 查询某条审查的航线垂直分离明细（按明细序号升序）。 */
    public List<VerticalDetailDto> getVerticalDetails(String reviewId) {
        if (reviewRepo.findReview(reviewId) == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND",
                    "审核记录不存在: " + reviewId);
        }
        return reviewRepo.findVerticalDetails(reviewId).stream()
                .map(d -> new VerticalDetailDto(d.zoneId(), d.bandLower(), d.bandUpper(),
                        d.verticalHit()))
                .toList();
    }

    private static OccupancyResult toResult(OccupancyPo po, int capacity, int activeCount) {
        return new OccupancyResult(po.occupancyId(), po.reviewId(), po.routeId(), po.zoneId(),
                po.bandLower(), po.bandUpper(), po.startTime(), po.endTime(), po.status(),
                capacity, activeCount);
    }

    private long nowMillis() {
        return clock.instant().toEpochMilli();
    }
}
