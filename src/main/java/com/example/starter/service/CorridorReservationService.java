package com.example.starter.service;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.AvailabilityResult;
import com.example.starter.api.dto.CorridorCapacityAdjustRequest;
import com.example.starter.api.dto.CorridorCreateRequest;
import com.example.starter.api.dto.CorridorResult;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.OccupancyResult;
import com.example.starter.api.dto.ReservationCancelRequest;
import com.example.starter.api.dto.ReservationCreateRequest;
import com.example.starter.api.dto.ReservationResult;
import com.example.starter.domain.Geometry;
import com.example.starter.domain.ReviewConclusion;
import com.example.starter.repo.AirspaceRepository;
import com.example.starter.repo.CorridorPo;
import com.example.starter.repo.CorridorRepository;
import com.example.starter.repo.ReservationPo;
import com.example.starter.repo.ReviewPo;
import com.example.starter.repo.ReviewRepository;
import com.example.starter.repo.RoutePo;
import com.example.starter.repo.RouteRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 航路走廊时段预约与容量占用核验业务服务。
 *
 * <p>预约创建、取消与调容事务都先对走廊行做真实更新取得行级排他锁，
 * 彼此按事务提交顺序串行：容量计数不会并发超卖，取消不会重复释放。
 * 所有写操作经 {@link IdempotentExecutor} 保证 reservationKey/corridorKey
 * 同键同参重放、异参 409、失败不占键。</p>
 *
 * <p>预约仅引用已 CLEAR 且未 STALE 的审核结果，不消费、不改写审核记录；
 * 占用查询与可预约探测均为只读，不改变任何状态。</p>
 */
@Service
public class CorridorReservationService {

    static final String KIND_CORRIDOR_CREATE = "CORRIDOR_CREATE";
    static final String KIND_CORRIDOR_CAPACITY = "CORRIDOR_CAPACITY";
    static final String KIND_RESERVATION_CREATE = "RESERVATION_CREATE";
    static final String KIND_RESERVATION_CANCEL = "RESERVATION_CANCEL";

    /** 预约状态：生效中（计入容量占用）。 */
    static final String STATUS_ACTIVE = "ACTIVE";
    /** 预约状态：已取消（保留历史，不计占用）。 */
    static final String STATUS_CANCELLED = "CANCELLED";

    private static final long MIN_DURATION_MILLIS = 60_000L;
    private static final long MAX_DURATION_MILLIS = 120L * 60_000L;

    private final CorridorRepository corridorRepo;
    private final ReviewRepository reviewRepo;
    private final RouteRepository routeRepo;
    private final AirspaceRepository airspaceRepo;
    private final IdempotentExecutor idempotency;
    private final Clock clock;

    public CorridorReservationService(CorridorRepository corridorRepo,
                                      ReviewRepository reviewRepo,
                                      RouteRepository routeRepo,
                                      AirspaceRepository airspaceRepo,
                                      IdempotentExecutor idempotency,
                                      Clock clock) {
        this.corridorRepo = corridorRepo;
        this.reviewRepo = reviewRepo;
        this.routeRepo = routeRepo;
        this.airspaceRepo = airspaceRepo;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    // ============================ 走廊 ============================

    /** 创建走廊：非退化矩形、容量 1~50；corridorId 唯一。 */
    public MutationResponse createCorridor(CorridorCreateRequest request) {
        return idempotency.execute(request.corridorKey(), KIND_CORRIDOR_CREATE, request, () -> {
            if (request.xMin() >= request.xMax() || request.yMin() >= request.yMax()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CORRIDOR_RECTANGLE",
                        "走廊必须是非退化轴对齐矩形：xMin < xMax 且 yMin < yMax");
            }
            validateCapacity(request.capacity());
            if (corridorRepo.findCorridor(request.corridorId()) != null) {
                throw new ApiException(HttpStatus.CONFLICT, "CORRIDOR_ALREADY_EXISTS",
                        "走廊已存在: " + request.corridorId());
            }
            CorridorPo corridor = new CorridorPo(request.corridorId(), request.xMin(),
                    request.yMin(), request.xMax(), request.yMax(), request.capacity(),
                    nowMillis());
            corridorRepo.insertCorridor(corridor);
            return new MutationResponse(request.corridorKey(), false, toDto(corridor));
        });
    }

    /**
     * 调整走廊容量：仅可上调不可下调，立即生效，不影响已存在的生效预约。
     * 持走廊行锁与预约创建/取消串行，避免调容与占用统计交错。
     */
    public MutationResponse adjustCapacity(CorridorCapacityAdjustRequest request) {
        return idempotency.execute(request.corridorKey(), KIND_CORRIDOR_CAPACITY, request, () -> {
            validateCapacity(request.capacity());
            CorridorPo corridor = corridorRepo.findCorridorForUpdate(request.corridorId());
            if (corridor == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "CORRIDOR_NOT_FOUND",
                        "走廊不存在: " + request.corridorId());
            }
            if (request.capacity() <= corridor.capacity()) {
                throw new ApiException(HttpStatus.CONFLICT, "CAPACITY_NOT_INCREASED",
                        "走廊容量仅可上调：当前容量 " + corridor.capacity()
                                + "，提交容量 " + request.capacity());
            }
            corridorRepo.updateCapacity(request.corridorId(), request.capacity());
            CorridorPo updated = new CorridorPo(corridor.corridorId(), corridor.xMin(),
                    corridor.yMin(), corridor.xMax(), corridor.yMax(), request.capacity(),
                    corridor.createdAt());
            return new MutationResponse(request.corridorKey(), false, toDto(updated));
        });
    }

    // ============================ 预约 ============================

    /**
     * 创建时段预约：关联审核必须为 CLEAR 且未 STALE，其航线必须与走廊区域相交；
     * 提交时与时段重叠的生效预约数达到容量上限返回 429 并携带当前占用数。
     */
    public MutationResponse createReservation(ReservationCreateRequest request) {
        return idempotency.execute(request.reservationKey(), KIND_RESERVATION_CREATE, request, () -> {
            Instant start = parseUtc(request.startTime());
            Instant end = parseUtc(request.endTime());
            validateTimeRange(start, end);
            // 锁走廊行：与同一走廊的其他创建/取消/调容事务按提交顺序串行
            CorridorPo corridor = corridorRepo.findCorridorForUpdate(request.corridorId());
            if (corridor == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "CORRIDOR_NOT_FOUND",
                        "走廊不存在: " + request.corridorId());
            }
            ReviewPo review = reviewRepo.findReview(request.reviewId());
            if (review == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND",
                        "审核记录不存在: " + request.reviewId());
            }
            RoutePo route = routeRepo.findRoute(review.routeId());
            long airspaceVersion = airspaceRepo.getGlobalVersion();
            if (!AirspaceReviewService.isReviewCurrent(review, route, airspaceVersion)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REVIEW_STALE",
                        "关联审核已失效（STALE），请先重新审核: " + request.reviewId());
            }
            if (!ReviewConclusion.CLEAR.name().equals(review.conclusion())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REVIEW_NOT_CLEAR",
                        "关联审核结论不是 CLEAR: " + request.reviewId());
            }
            if (!Geometry.polylineHitsRectangle(route.points(), corridor.xMin(), corridor.yMin(),
                    corridor.xMax(), corridor.yMax())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "ROUTE_NOT_INTERSECT_CORRIDOR",
                        "关联审核对应的航线与走廊区域不相交: " + review.routeId());
            }
            long startMillis = start.toEpochMilli();
            long endMillis = end.toEpochMilli();
            int occupancy = corridorRepo.countActiveOverlapping(
                    request.corridorId(), startMillis, endMillis);
            if (occupancy >= corridor.capacity()) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "CORRIDOR_CAPACITY_EXCEEDED",
                        "走廊时段容量已满：当前占用 " + occupancy + "，容量上限 " + corridor.capacity(),
                        Map.of("occupancy", occupancy, "capacity", corridor.capacity()));
            }
            ReservationPo po = new ReservationPo("rsv_" + UUID.randomUUID(),
                    corridor.corridorId(), review.reviewId(), review.routeId(),
                    startMillis, endMillis, STATUS_ACTIVE, request.reservationKey(),
                    nowMillis(), null);
            corridorRepo.insertReservation(po);
            return new MutationResponse(request.reservationKey(), false, toDto(po));
        });
    }

    /** 取消预约：立即从容量计数中移除，历史记录保留；重复取消 409。 */
    public MutationResponse cancelReservation(ReservationCancelRequest request) {
        return idempotency.execute(request.requestId(), KIND_RESERVATION_CANCEL, request, () -> {
            ReservationPo existing = corridorRepo.findReservation(request.reservationId());
            if (existing == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND",
                        "预约不存在: " + request.reservationId());
            }
            // 锁走廊行后在锁内重读，与同一走廊的创建/取消按提交顺序串行，防止重复释放
            corridorRepo.findCorridorForUpdate(existing.corridorId());
            ReservationPo locked = corridorRepo.findReservation(request.reservationId());
            if (STATUS_CANCELLED.equals(locked.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "RESERVATION_ALREADY_CANCELLED",
                        "预约已取消: " + request.reservationId());
            }
            corridorRepo.markCancelled(request.reservationId(), nowMillis());
            ReservationPo cancelled = corridorRepo.findReservation(request.reservationId());
            return new MutationResponse(request.requestId(), false, toDto(cancelled));
        });
    }

    /** 按 reservationId 查询预约（含已取消历史）。 */
    public ReservationResult getReservation(String reservationId) {
        ReservationPo po = corridorRepo.findReservation(reservationId);
        if (po == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND",
                    "预约不存在: " + reservationId);
        }
        return toDto(po);
    }

    // ============================ 只读查询 ============================

    /** 查询走廊在某一时刻的容量占用：生效预约列表与数量。只读，不改变状态。 */
    public OccupancyResult getOccupancy(String corridorId, String atText) {
        CorridorPo corridor = requireCorridor(corridorId);
        Instant at = parseUtc(atText);
        List<ReservationPo> active = corridorRepo.findActiveAt(corridorId, at.toEpochMilli());
        return new OccupancyResult(corridorId, at.toString(), corridor.capacity(),
                active.size(), toDtos(active));
    }

    /** 探测某时段是否可预约（重叠生效预约数未达上限）。只读，不建立预约。 */
    public AvailabilityResult probeAvailability(String corridorId, String startText,
                                                String endText) {
        CorridorPo corridor = requireCorridor(corridorId);
        Instant start = parseUtc(startText);
        Instant end = parseUtc(endText);
        validateTimeRange(start, end);
        int occupancy = corridorRepo.countActiveOverlapping(corridorId,
                start.toEpochMilli(), end.toEpochMilli());
        return new AvailabilityResult(corridorId, start.toString(), end.toString(),
                occupancy < corridor.capacity(), occupancy, corridor.capacity());
    }

    /** 查询与指定时段重叠的全部预约（含已取消历史）。只读。 */
    public List<ReservationResult> listReservations(String corridorId, String fromText,
                                                    String toText) {
        requireCorridor(corridorId);
        Instant from = parseUtc(fromText);
        Instant to = parseUtc(toText);
        if (!to.isAfter(from)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TIME_RANGE",
                    "查询时段必须满足 from < to");
        }
        return toDtos(corridorRepo.findOverlapping(corridorId,
                from.toEpochMilli(), to.toEpochMilli()));
    }

    // ============================ 工具方法 ============================

    private CorridorPo requireCorridor(String corridorId) {
        CorridorPo corridor = corridorRepo.findCorridor(corridorId);
        if (corridor == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CORRIDOR_NOT_FOUND",
                    "走廊不存在: " + corridorId);
        }
        return corridor;
    }

    private static void validateCapacity(int capacity) {
        if (capacity < 1 || capacity > 50) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CAPACITY",
                    "走廊容量上限必须在 1~50 之间: " + capacity);
        }
    }

    /** 解析 ISO-8601 UTC 时刻（支持 Z 与带偏移量形式），格式非法返回 400。 */
    private static Instant parseUtc(String text) {
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException ex) {
            try {
                return OffsetDateTime.parse(text).toInstant();
            } catch (DateTimeParseException ex2) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TIME_FORMAT",
                        "时刻必须是 ISO-8601 UTC 格式（如 2026-09-25T10:00:00Z）: " + text);
            }
        }
    }

    /** 时段校验：左闭右开，时长 1~120 分钟。 */
    private static void validateTimeRange(Instant start, Instant end) {
        long duration = end.toEpochMilli() - start.toEpochMilli();
        if (duration < MIN_DURATION_MILLIS || duration > MAX_DURATION_MILLIS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TIME_RANGE",
                    "预约时段必须满足 end > start 且时长在 1~120 分钟之间");
        }
    }

    private static CorridorResult toDto(CorridorPo po) {
        return new CorridorResult(po.corridorId(), po.xMin(), po.yMin(), po.xMax(), po.yMax(),
                po.capacity());
    }

    private static ReservationResult toDto(ReservationPo po) {
        return new ReservationResult(po.reservationId(), po.corridorId(), po.reviewId(),
                po.routeId(), Instant.ofEpochMilli(po.startMillis()).toString(),
                Instant.ofEpochMilli(po.endMillis()).toString(), po.status());
    }

    private static List<ReservationResult> toDtos(List<ReservationPo> pos) {
        List<ReservationResult> results = new ArrayList<>(pos.size());
        for (ReservationPo po : pos) {
            results.add(toDto(po));
        }
        return results;
    }

    private long nowMillis() {
        return clock.instant().toEpochMilli();
    }
}
