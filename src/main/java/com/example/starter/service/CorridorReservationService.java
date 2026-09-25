package com.example.starter.service;

import com.example.starter.api.ApiException;
import com.example.starter.api.CapacityExceededException;
import com.example.starter.api.dto.CorridorCapacityRequest;
import com.example.starter.api.dto.CorridorCreateRequest;
import com.example.starter.api.dto.CorridorResult;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.OccupancyView;
import com.example.starter.api.dto.ProbeResult;
import com.example.starter.api.dto.ReservationCancelRequest;
import com.example.starter.api.dto.ReservationCreateRequest;
import com.example.starter.api.dto.ReservationHistoryDto;
import com.example.starter.api.dto.ReservationResultDto;
import com.example.starter.api.dto.ReservationWindowDto;
import com.example.starter.domain.Geometry;
import com.example.starter.domain.ReservationStatus;
import com.example.starter.repo.AirspaceRepository;
import com.example.starter.repo.CorridorPo;
import com.example.starter.repo.CorridorRepository;
import com.example.starter.repo.DedupPo;
import com.example.starter.repo.ReservationPo;
import com.example.starter.repo.ReviewPo;
import com.example.starter.repo.ReviewRepository;
import com.example.starter.repo.RoutePo;
import com.example.starter.repo.RouteRepository;
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
import java.util.function.Supplier;

/**
 * 航路走廊与时段预约业务服务。
 *
 * <p>容量裁决在业务事务内先锁走廊行再统计重叠预约，同一走廊的并发创建/取消按
 * 事务提交顺序串行化，保证不超卖、不重复释放。所有写操作沿用 requestId
 * 幂等语义：同键同参重放首次结果、异参 409、失败不占键。</p>
 *
 * <p>预约只引用审核结果，不消费、不改写审核记录；审核结果一旦因航线或空域
 * 版本变化变为 STALE，预约创建返回 422，要求先重新审核。</p>
 */
@Service
public class CorridorReservationService {

    static final String KIND_CORRIDOR_CREATE = "CORRIDOR_CREATE";
    static final String KIND_CORRIDOR_CAPACITY = "CORRIDOR_CAPACITY";
    static final String KIND_RESERVATION_CREATE = "RESERVATION_CREATE";
    static final String KIND_RESERVATION_CANCEL = "RESERVATION_CANCEL";

    static final long MIN_DURATION_MILLIS = 60_000L;
    static final long MAX_DURATION_MILLIS = 120 * 60_000L;

    private final AirspaceRepository airspaceRepo;
    private final RouteRepository routeRepo;
    private final CorridorRepository corridorRepo;
    private final ReviewRepository reviewRepo;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate txTemplate;

    public CorridorReservationService(AirspaceRepository airspaceRepo,
                                      RouteRepository routeRepo,
                                      CorridorRepository corridorRepo,
                                      ReviewRepository reviewRepo,
                                      ObjectMapper objectMapper,
                                      Clock clock,
                                      PlatformTransactionManager transactionManager) {
        this.airspaceRepo = airspaceRepo;
        this.routeRepo = routeRepo;
        this.corridorRepo = corridorRepo;
        this.reviewRepo = reviewRepo;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    // ============================ 走廊 ============================

    /** 创建走廊；同键同参重放原结果，异参 409。 */
    public MutationResponse createCorridor(CorridorCreateRequest request) {
        return withIdempotency(request.requestId(), KIND_CORRIDOR_CREATE, canonicalHash(request), () -> {
            if (request.xMin() >= request.xMax() || request.yMin() >= request.yMax()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CORRIDOR_RECTANGLE",
                        "走廊必须是非退化轴对齐矩形：xMin < xMax 且 yMin < yMax");
            }
            if (corridorRepo.findCorridor(request.corridorId()) != null) {
                throw new ApiException(HttpStatus.CONFLICT, "CORRIDOR_ALREADY_EXISTS",
                        "走廊已存在: " + request.corridorId());
            }
            long now = nowMillis();
            corridorRepo.insertCorridor(new CorridorPo(request.corridorId(),
                    request.xMin(), request.yMin(), request.xMax(), request.yMax(),
                    request.capacity(), 0L, now, now));
            return new MutationResponse(request.requestId(), false, toCorridorResult(
                    request.corridorId(), request.xMin(), request.yMin(),
                    request.xMax(), request.yMax(), request.capacity()));
        });
    }

    /** 上调走廊容量（仅可上调，立即生效，不影响已存在的生效预约）。 */
    public MutationResponse adjustCapacity(CorridorCapacityRequest request) {
        return withIdempotency(request.requestId(), KIND_CORRIDOR_CAPACITY,
                canonicalHash(request), () -> {
                    // 锁走廊行串行化，读到的容量与条件更新来自同一已提交状态
                    CorridorPo corridor = corridorRepo.lockCorridor(request.corridorKey());
                    if (corridor == null) {
                        throw new ApiException(HttpStatus.NOT_FOUND, "CORRIDOR_NOT_FOUND",
                                "走廊不存在: " + request.corridorKey());
                    }
                    if (request.newCapacity() <= corridor.capacity()) {
                        throw new ApiException(HttpStatus.CONFLICT, "CAPACITY_ONLY_INCREASABLE",
                                "走廊容量只能上调：current=" + corridor.capacity()
                                        + ", requested=" + request.newCapacity());
                    }
                    corridorRepo.increaseCapacity(request.corridorKey(),
                            request.newCapacity(), nowMillis());
                    return new MutationResponse(request.requestId(), false, toCorridorResult(
                            corridor.corridorId(), corridor.xMin(), corridor.yMin(),
                            corridor.xMax(), corridor.yMax(), request.newCapacity()));
                });
    }

    // ============================ 预约 ============================

    /**
     * 创建预约。前置校验：
     * 时间窗左闭右开且时长 1～120 分钟；走廊存在；关联审核存在、结论 CLEAR、
     * 仍为当前版本（否则 422，要求先重新审核）；审核航线与走廊相交（否则 422）；
     * 时间重叠的生效预约数达到容量上限返回 429 并携带当前占用数。
     */
    public MutationResponse createReservation(ReservationCreateRequest request) {
        return withIdempotency(request.requestId(), KIND_RESERVATION_CREATE,
                canonicalHash(request), () -> {
                    validateWindow(request.startTime(), request.endTime());
                    if (corridorRepo.findReservation(request.reservationKey()) != null) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "RESERVATION_KEY_ALREADY_EXISTS",
                                "reservationKey 已存在: " + request.reservationKey());
                    }
                    // 先锁走廊行：与同走廊的并发创建/取消/容量调整按提交顺序串行化
                    CorridorPo corridor = corridorRepo.lockCorridor(request.corridorId());
                    if (corridor == null) {
                        throw new ApiException(HttpStatus.NOT_FOUND, "CORRIDOR_NOT_FOUND",
                                "走廊不存在: " + request.corridorId());
                    }
                    ReviewPo review = reviewRepo.findReview(request.reviewId());
                    if (review == null) {
                        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                                "REVIEW_REQUIRED", "关联审核结果不存在: " + request.reviewId());
                    }
                    if (!"CLEAR".equals(review.conclusion())) {
                        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                                "REVIEW_NOT_CLEAR",
                                "仅可关联 CLEAR 审核结果，当前结论: " + review.conclusion());
                    }
                    // STALE：航线或空域版本已变化，必须先重新审核
                    RoutePo route = routeRepo.findRoute(review.routeId());
                    long globalVersion = airspaceRepo.getGlobalVersion();
                    boolean current = route != null
                            && route.version() == review.routeVersion()
                            && globalVersion == review.airspaceVersion();
                    if (!current) {
                        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                                "REVIEW_STALE",
                                "关联审核结果已失效（STALE），请先重新审核航线: "
                                        + review.routeId());
                    }
                    // 审核对应的航线（审核时点快照）必须与走廊区域相交，边界接触也算
                    if (!Geometry.polylineHitsRectangle(review.pointsSnapshot(),
                            corridor.xMin(), corridor.yMin(), corridor.xMax(), corridor.yMax())) {
                        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                                "ROUTE_NOT_INTERSECT_CORRIDOR",
                                "关联审核航线与走廊区域不相交: corridor="
                                        + corridor.corridorId());
                    }
                    // 持锁状态下统计重叠占用，裁决与插入在同一事务内提交
                    int overlapping = corridorRepo.countOverlappingActive(
                            corridor.corridorId(), request.startTime(), request.endTime());
                    if (overlapping >= corridor.capacity()) {
                        throw new CapacityExceededException(overlapping, corridor.capacity());
                    }
                    long now = nowMillis();
                    ReservationPo po = new ReservationPo(request.reservationKey(),
                            corridor.corridorId(), request.startTime(), request.endTime(),
                            review.reviewId(), ReservationStatus.ACTIVE.name(),
                            request.requestId(), now, null);
                    corridorRepo.insertReservation(po);
                    // 不消费、不改写审核记录，仅保存引用
                    return new MutationResponse(request.requestId(), false, toReservationDto(po));
                });
    }

    /** 取消预约：立即从容量计数移除，历史保留；不存在 404，重复取消 409。 */
    public MutationResponse cancelReservation(ReservationCancelRequest request) {
        return withIdempotency(request.requestId(), KIND_RESERVATION_CANCEL,
                canonicalHash(request), () -> {
                    ReservationPo reservation = corridorRepo.findReservation(
                            request.reservationKey());
                    if (reservation == null) {
                        throw new ApiException(HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND",
                                "预约不存在: " + request.reservationKey());
                    }
                    if (ReservationStatus.CANCELLED.name().equals(reservation.status())) {
                        throw new ApiException(HttpStatus.CONFLICT, "RESERVATION_ALREADY_CANCELLED",
                                "预约已取消: " + request.reservationKey());
                    }
                    // 锁走廊行：与并发创建事务互斥，使“取消释放容量”严格按提交顺序裁决
                    CorridorPo corridor = corridorRepo.lockCorridor(reservation.corridorId());
                    if (corridor == null) {
                        throw new ApiException(HttpStatus.NOT_FOUND, "CORRIDOR_NOT_FOUND",
                                "走廊不存在: " + reservation.corridorId());
                    }
                    // 条件更新兜底并发取消：仅 ACTIVE 可置 CANCELLED，杜绝重复释放
                    int updated = corridorRepo.markCancelled(
                            request.reservationKey(), nowMillis());
                    if (updated == 0) {
                        throw new ApiException(HttpStatus.CONFLICT, "RESERVATION_ALREADY_CANCELLED",
                                "预约已被并发取消: " + request.reservationKey());
                    }
                    ReservationPo cancelled = corridorRepo.findReservation(
                            request.reservationKey());
                    return new MutationResponse(request.requestId(), false,
                            toReservationDto(cancelled));
                });
    }

    // ============================ 只读查询 ============================

    /** 查询走廊在某时刻的生效占用（只读，不改变状态）。 */
    public OccupancyView occupancyAt(String corridorId, long at) {
        return txTemplate.execute(status -> {
            CorridorPo corridor = requireCorridor(corridorId);
            List<ReservationPo> active = corridorRepo.findActiveAt(corridorId, at);
            return new OccupancyView(corridorId, at, corridor.capacity(), active.size(),
                    toReservationDtos(active));
        });
    }

    /** 查询走廊与 [from,to) 重叠的生效预约（只读）。 */
    public ReservationWindowDto activeWindow(String corridorId, long from, long to) {
        if (from >= to) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TIME_WINDOW",
                    "查询时段必须满足 from < to");
        }
        return txTemplate.execute(status -> {
            requireCorridor(corridorId);
            List<ReservationPo> active = corridorRepo.findActiveOverlapping(corridorId, from, to);
            return new ReservationWindowDto(corridorId, from, to, active.size(),
                    toReservationDtos(active));
        });
    }

    /**
     * 探测时间窗是否可预约（只读，不建立预约）。
     * 判定标准：窗内任意时刻的重叠生效预约数均未达到容量上限。
     * 由于预约计数在整个重叠区间内为常数的最大值出现在各预约端点处，
     * 这里对重叠 ACTIVE 预约做扫描线，求窗内峰值占用。
     */
    public ProbeResult probe(String corridorId, long start, long end) {
        validateWindow(start, end);
        return txTemplate.execute(status -> {
            CorridorPo corridor = requireCorridor(corridorId);
            List<ReservationPo> overlapping =
                    corridorRepo.findActiveOverlapping(corridorId, start, end);
            int peak = peakOccupancy(overlapping, start, end);
            boolean available = peak < corridor.capacity();
            return new ProbeResult(corridorId, start, end, corridor.capacity(), peak, available);
        });
    }

    /** 查询走廊预约历史（ACTIVE 与 CANCELLED 均保留）。 */
    public ReservationHistoryDto history(String corridorId) {
        return txTemplate.execute(status -> {
            requireCorridor(corridorId);
            List<ReservationPo> all = corridorRepo.findHistory(corridorId);
            return new ReservationHistoryDto(corridorId, all.size(), toReservationDtos(all));
        });
    }

    // ============================ 幂等与事务 ============================

    /**
     * 在事务内执行幂等写操作：同键同参返回首次成功结果（replayed=true）；
     * 同键异参/异种操作抛 409；业务异常回滚、不占用 requestId。
     *
     * <p>并发落败可能先撞唯一约束，也可能在持锁后读到赢家状态而抛业务冲突；
     * 两种情况都在回滚后用新事务查询去重表：赢家同键同参已提交则重放原结果，
     * 否则按原错误抛出。</p>
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
            MutationResponse original =
                    objectMapper.readValue(po.responseJson(), MutationResponse.class);
            return new MutationResponse(original.requestId(), true, original.data());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("无法解析幂等重放结果: " + po.requestId(), ex);
        }
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

    private static void validateWindow(long start, long end) {
        if (start >= end) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TIME_WINDOW",
                    "预约时段必须为左闭右开：startTime < endTime");
        }
        long duration = end - start;
        if (duration < MIN_DURATION_MILLIS || duration > MAX_DURATION_MILLIS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DURATION",
                    "预约时长须在 1～120 分钟（含），当前时长（毫秒）: " + duration);
        }
    }

    /**
     * 扫描线求 [start,end) 窗内的峰值同时占用数。
     * 对每个与窗重叠的 ACTIVE 预约，裁剪到窗内得到 [max(r.start,start), min(r.end,end))，
     * 在左端点 +1、右端点 -1；同点先处理结束再处理开始（左闭右开，首尾相接不叠加）。
     */
    static int peakOccupancy(List<ReservationPo> reservations, long start, long end) {
        record Event(long at, int delta) {
        }
        List<Event> events = new ArrayList<>(reservations.size() * 2);
        for (ReservationPo r : reservations) {
            long s = Math.max(r.startTime(), start);
            long e = Math.min(r.endTime(), end);
            if (s < e) {
                events.add(new Event(s, 1));
                events.add(new Event(e, -1));
            }
        }
        events.sort((a, b) -> a.at() == b.at() ? Integer.compare(a.delta(), b.delta())
                : Long.compare(a.at(), b.at()));
        int current = 0;
        int peak = 0;
        for (Event event : events) {
            current += event.delta();
            peak = Math.max(peak, current);
        }
        return peak;
    }

    private static CorridorResult toCorridorResult(String corridorId, int xMin, int yMin,
                                                   int xMax, int yMax, int capacity) {
        return new CorridorResult(corridorId, xMin, yMin, xMax, yMax, capacity);
    }

    private static ReservationResultDto toReservationDto(ReservationPo po) {
        return new ReservationResultDto(po.reservationKey(), po.corridorId(),
                po.startTime(), po.endTime(), po.reviewId(), po.status(),
                po.createdAt(), po.cancelledAt());
    }

    private static List<ReservationResultDto> toReservationDtos(List<ReservationPo> pos) {
        List<ReservationResultDto> dtos = new ArrayList<>(pos.size());
        for (ReservationPo po : pos) {
            dtos.add(toReservationDto(po));
        }
        return dtos;
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
     * 计算请求参数的规范化哈希（SHA-256）。对象字段按键名字典序递归排序，
     * 列表保持顺序，使相同语义参数产生相同哈希。
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
