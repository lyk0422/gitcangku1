package com.example.starter.service;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.ClosureCreateRequest;
import com.example.starter.api.dto.ClosureResult;
import com.example.starter.api.dto.FlightActionRequest;
import com.example.starter.api.dto.FlightBatchReviewRequest;
import com.example.starter.api.dto.FlightBatchReviewResult;
import com.example.starter.api.dto.FlightCreateRequest;
import com.example.starter.api.dto.FlightDetailResult;
import com.example.starter.api.dto.FlightEmergencyConvertRequest;
import com.example.starter.api.dto.FlightResult;
import com.example.starter.api.dto.FlightReviewItemDto;
import com.example.starter.api.dto.FlightReviewReasonDto;
import com.example.starter.api.dto.FlightRerouteRequest;
import com.example.starter.api.dto.FlightRiskDto;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.RunwayCreateRequest;
import com.example.starter.api.dto.RunwayResult;
import com.example.starter.api.dto.RunwayWindowsResult;
import com.example.starter.domain.FlightRejectReason;
import com.example.starter.domain.FlightStatus;
import com.example.starter.domain.RouteType;
import com.example.starter.repo.AirspaceRepository;
import com.example.starter.repo.FlightPo;
import com.example.starter.repo.FlightRepository;
import com.example.starter.repo.FlightReviewItemPo;
import com.example.starter.repo.FlightRiskPo;
import com.example.starter.repo.RouteRepository;
import com.example.starter.repo.RunwayClosurePo;
import com.example.starter.repo.RunwayPo;
import com.example.starter.repo.RunwayRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 跑道关闭与航班起降段审查业务服务。
 *
 * <p>关闭变更、批量审查、起飞与改航事务都先取协调锁行，
 * 按事务提交顺序串行裁决；全部写操作幂等：关闭窗口以服务端计算的
 * closureKey 指纹（跑道标识与版本、规范化时段、例外标志、操作者）为幂等键，
 * 其余写操作以客户端 requestId 为键；同键重放原结果，失败回滚不占键。</p>
 *
 * <p>批量审查在同一事务内先计算全部航班的最终容量与关闭影响，
 * 任一航线拒绝则整批不批准、不产生任何航班状态变更；
 * 审查结论与逐航线原因不可变持久化，供事后查询。</p>
 */
@Service
public class RunwayClosureService {

    static final String KIND_RUNWAY_CREATE = "RUNWAY_CREATE";
    static final String KIND_CLOSURE_CREATE = "CLOSURE_CREATE";
    static final String KIND_FLIGHT_CREATE = "FLIGHT_CREATE";
    static final String KIND_FLIGHT_REVIEW = "FLIGHT_REVIEW";
    static final String KIND_FLIGHT_DEPART = "FLIGHT_DEPART";
    static final String KIND_FLIGHT_REROUTE = "FLIGHT_REROUTE";
    static final String KIND_FLIGHT_CANCEL = "FLIGHT_CANCEL";
    static final String KIND_FLIGHT_EMERGENCY_CONVERT = "FLIGHT_EMERGENCY_CONVERT";

    private static final long HOUR_MILLIS = 3_600_000L;

    private final RunwayRepository runwayRepo;
    private final FlightRepository flightRepo;
    private final RouteRepository routeRepo;
    private final AirspaceRepository airspaceRepo;
    private final IdempotentMutationExecutor idempotency;
    private final Clock clock;

    public RunwayClosureService(RunwayRepository runwayRepo,
                                FlightRepository flightRepo,
                                RouteRepository routeRepo,
                                AirspaceRepository airspaceRepo,
                                IdempotentMutationExecutor idempotency,
                                Clock clock) {
        this.runwayRepo = runwayRepo;
        this.flightRepo = flightRepo;
        this.routeRepo = routeRepo;
        this.airspaceRepo = airspaceRepo;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    // ============================ 跑道 ============================

    /** 登记跑道（初始版本 0）；同键同参重放原结果，异参 409。 */
    public MutationResponse createRunway(RunwayCreateRequest request) {
        return idempotency.execute(request.requestId(), KIND_RUNWAY_CREATE,
                idempotency.canonicalHash(request), () -> {
                    if (runwayRepo.findRunway(request.runwayId()) != null) {
                        throw new ApiException(HttpStatus.CONFLICT, "RUNWAY_ALREADY_EXISTS",
                                "跑道已存在: " + request.runwayId());
                    }
                    runwayRepo.insertRunway(request.runwayId(), request.hourlyCapacity());
                    return new MutationResponse(request.requestId(), false,
                            new RunwayResult(request.runwayId(), 0, request.hourlyCapacity()));
                });
    }

    /**
     * 登记跑道关闭窗口（UTC 左闭右开）。
     *
     * <p>幂等键 closureKey 由服务端按指纹（跑道标识与版本、规范化时段、例外标志、
     * 操作者）计算：同键重放原结果，失败（版本冲突、窗口非法、重叠等）回滚不占键。</p>
     *
     * <p>登记成功后，起降段命中该窗口且未起飞的已批准 NORMAL 航班转为
     * RUNWAY_RISK 并固化窗口快照；已起飞航班不改状态。</p>
     */
    public MutationResponse createClosure(ClosureCreateRequest request) {
        String closureKey = closureKeyOf(request);
        return idempotency.execute(closureKey, KIND_CLOSURE_CREATE, closureKey, () -> {
            // 先取协调锁：与批量审查、起飞、改航按提交顺序串行裁决
            airspaceRepo.lockCoordinationRow();
            RunwayPo runway = runwayRepo.findRunwayForUpdate(request.runwayId());
            if (runway == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "RUNWAY_NOT_FOUND",
                        "跑道不存在: " + request.runwayId());
            }
            if (request.startUtc() >= request.endUtc()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CLOSURE_WINDOW",
                        "关闭窗口必须满足 startUtc < endUtc（UTC 左闭右开）");
            }
            if (runway.version() != request.expectedRunwayVersion()) {
                throw new ApiException(HttpStatus.CONFLICT, "RUNWAY_VERSION_CONFLICT",
                        "跑道版本不匹配：expected=" + request.expectedRunwayVersion()
                                + ", current=" + runway.version());
            }
            for (RunwayClosurePo existing : runwayRepo.findClosures(request.runwayId())) {
                // 左闭右开窗口重叠判定：端点相接（end == start）合法
                if (request.startUtc() < existing.endUtc()
                        && existing.startUtc() < request.endUtc()) {
                    throw new ApiException(HttpStatus.CONFLICT, "CLOSURE_WINDOW_OVERLAP",
                            "关闭窗口与既有窗口重叠: " + existing.closureId());
                }
            }
            // 条件更新兜底并发登记：更新 0 行说明版本已被其他事务推进
            int updated = runwayRepo.compareAndIncrementVersion(
                    request.runwayId(), request.expectedRunwayVersion());
            if (updated == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "RUNWAY_VERSION_CONFLICT",
                        "跑道版本已变化，请使用最新 expectedRunwayVersion 重试");
            }
            int newVersion = request.expectedRunwayVersion() + 1;
            RunwayClosurePo closure = new RunwayClosurePo("cl_" + UUID.randomUUID(),
                    request.runwayId(), request.startUtc(), request.endUtc(),
                    request.allowEmergency(), request.operator(), closureKey,
                    newVersion, nowMillis());
            runwayRepo.insertClosure(closure);
            markRiskFlights(closure);
            return new MutationResponse(closureKey, false, toDto(closure));
        });
    }

    /** 查询跑道全部关闭窗口与当前版本。 */
    public RunwayWindowsResult getRunwayWindows(String runwayId) {
        RunwayPo runway = runwayRepo.findRunway(runwayId);
        if (runway == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RUNWAY_NOT_FOUND",
                    "跑道不存在: " + runwayId);
        }
        List<ClosureResult> windows = new ArrayList<>();
        for (RunwayClosurePo po : runwayRepo.findClosures(runwayId)) {
            windows.add(toDto(po));
        }
        return new RunwayWindowsResult(runway.runwayId(), runway.version(), windows);
    }

    /**
     * 新关闭窗口生效的风险标记：命中窗口且未起飞的已批准 NORMAL 航班
     * 转为 RUNWAY_RISK 并固化窗口快照（调用方负责事务）。
     */
    private void markRiskFlights(RunwayClosurePo closure) {
        List<FlightPo> candidates = flightRepo.findByStatusAndRunway(
                FlightStatus.APPROVED.name(), closure.runwayId());
        for (FlightPo flight : candidates) {
            if (!RouteType.NORMAL.name().equals(flight.routeType())) {
                continue;
            }
            boolean hit = (closure.runwayId().equals(flight.depRunwayId())
                    && closure.contains(flight.depTimeUtc()))
                    || (closure.runwayId().equals(flight.arrRunwayId())
                    && closure.contains(flight.arrTimeUtc()));
            if (!hit) {
                continue;
            }
            flightRepo.markRunwayRisk(flight.flightId());
            flightRepo.insertRisk(new FlightRiskPo(flight.flightId(), closure.closureId(),
                    closure.runwayId(), closure.startUtc(), closure.endUtc(),
                    closure.allowEmergency(), closure.operator(), closure.runwayVersion(),
                    nowMillis()));
        }
    }

    // ============================ 航班 ============================

    /** 登记航班起降段（初始状态 PENDING）。 */
    public MutationResponse registerFlight(FlightCreateRequest request) {
        return idempotency.execute(request.requestId(), KIND_FLIGHT_CREATE,
                idempotency.canonicalHash(request), () -> {
                    RouteType routeType = parseRouteType(request.routeType());
                    if (routeType == RouteType.NORMAL && request.eventNo() != null
                            && !request.eventNo().isBlank()) {
                        throw new ApiException(HttpStatus.BAD_REQUEST, "EVENT_NO_NOT_ALLOWED",
                                "NORMAL 航班不得携带紧急事件号");
                    }
                    if (request.arrTimeUtc() < request.depTimeUtc()) {
                        throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FLIGHT_SCHEDULE",
                                "落地时刻不得早于起飞时刻");
                    }
                    if (routeRepo.findRoute(request.routeId()) == null) {
                        throw new ApiException(HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND",
                                "航线不存在: " + request.routeId());
                    }
                    requireRunway(request.depRunwayId());
                    requireRunway(request.arrRunwayId());
                    if (flightRepo.findFlight(request.flightId()) != null) {
                        throw new ApiException(HttpStatus.CONFLICT, "FLIGHT_ALREADY_EXISTS",
                                "航班已存在: " + request.flightId());
                    }
                    FlightPo po = new FlightPo(request.flightId(), request.routeId(),
                            routeType.name(), normalizeEventNo(request.eventNo()),
                            request.depRunwayId(), request.depTimeUtc(),
                            request.arrRunwayId(), request.arrTimeUtc(),
                            FlightStatus.PENDING.name());
                    flightRepo.insertFlight(po);
                    return new MutationResponse(request.requestId(), false, toDto(po));
                });
    }

    /**
     * 批量审查：同一事务内先计算全部航班的最终容量与关闭影响，
     * 任一拒绝则整批不批准（无任何航班状态变更）；结论与原因不可变持久化。
     */
    public MutationResponse reviewBatch(FlightBatchReviewRequest request) {
        return idempotency.execute(request.requestId(), KIND_FLIGHT_REVIEW,
                idempotency.canonicalHash(request), () -> {
                    airspaceRepo.lockCoordinationRow();
                    List<String> flightIds = request.flightIds();
                    Set<String> distinct = new HashSet<>(flightIds);
                    if (distinct.size() != flightIds.size()) {
                        throw new ApiException(HttpStatus.BAD_REQUEST, "DUPLICATE_FLIGHT_ID",
                                "flightIds 含重复航班标识");
                    }
                    // 按 flightId 升序锁定并评估，保证结果确定、锁顺序一致
                    List<String> sorted = new ArrayList<>(distinct);
                    sorted.sort(String::compareTo);
                    List<FlightPo> flights = new ArrayList<>(sorted.size());
                    for (String flightId : sorted) {
                        FlightPo flight = flightRepo.findFlightForUpdate(flightId);
                        if (flight == null) {
                            throw new ApiException(HttpStatus.NOT_FOUND, "FLIGHT_NOT_FOUND",
                                    "航班不存在: " + flightId);
                        }
                        flights.add(flight);
                    }

                    Map<String, List<RunwayClosurePo>> closuresByRunway = new HashMap<>();
                    Map<String, RunwayPo> runwayById = new HashMap<>();
                    Map<String, FlightReviewItemPo> items = new LinkedHashMap<>();

                    // 第一步：状态与关闭影响
                    for (FlightPo flight : flights) {
                        if (!FlightStatus.PENDING.name().equals(flight.status())) {
                            reject(items, flight, FlightRejectReason.FLIGHT_NOT_REVIEWABLE,
                                    "当前状态不可普通审查: " + flight.status());
                            continue;
                        }
                        List<RunwayClosurePo> hits = intersectingClosures(
                                flight, closuresByRunway);
                        if (hits.isEmpty()) {
                            continue;
                        }
                        if (RouteType.NORMAL.name().equals(flight.routeType())) {
                            reject(items, flight, FlightRejectReason.RUNWAY_CLOSED,
                                    "起降段命中关闭窗口: " + closureIds(hits));
                        } else {
                            List<RunwayClosurePo> notAllowed = hits.stream()
                                    .filter(c -> !c.allowEmergency()).toList();
                            if (!notAllowed.isEmpty()) {
                                reject(items, flight,
                                        FlightRejectReason.EMERGENCY_EXCEPTION_NOT_ALLOWED,
                                        "命中窗口不允许紧急例外: " + closureIds(notAllowed));
                            } else if (flight.eventNo() == null) {
                                reject(items, flight, FlightRejectReason.MISSING_EVENT_NO,
                                        "EMERGENCY 航班经关闭窗口例外通过须附事件号");
                            }
                        }
                    }

                    // 第二步：最终容量（既有占用 + 本批未被拒绝航班，按序占用槽位）
                    Map<String, Integer> slotUsage = capacityUsage();
                    for (FlightPo flight : flights) {
                        if (items.containsKey(flight.flightId())) {
                            continue;
                        }
                        String depSlot = slotKey(flight.depRunwayId(), flight.depTimeUtc());
                        String arrSlot = slotKey(flight.arrRunwayId(), flight.arrTimeUtc());
                        int depCapacity = capacityOf(runwayById, flight.depRunwayId());
                        int arrCapacity = capacityOf(runwayById, flight.arrRunwayId());
                        boolean depExceeded = slotUsage.getOrDefault(depSlot, 0) + 1 > depCapacity;
                        boolean arrExceeded = slotUsage.getOrDefault(arrSlot, 0) + 1 > arrCapacity;
                        if (depExceeded || arrExceeded) {
                            List<String> exceeded = new ArrayList<>();
                            if (depExceeded) {
                                exceeded.add(depSlot);
                            }
                            if (arrExceeded) {
                                exceeded.add(arrSlot);
                            }
                            reject(items, flight, FlightRejectReason.CAPACITY_EXCEEDED,
                                    "跑道小时容量超限槽位: " + String.join(",", exceeded));
                            continue;
                        }
                        slotUsage.merge(depSlot, 1, Integer::sum);
                        slotUsage.merge(arrSlot, 1, Integer::sum);
                    }

                    boolean approved = items.isEmpty();
                    String reviewId = "fr_" + UUID.randomUUID();
                    flightRepo.insertReview(reviewId, request.requestId(), approved, nowMillis());
                    List<FlightReviewItemDto> itemDtos = new ArrayList<>(flights.size());
                    for (FlightPo flight : flights) {
                        FlightReviewItemPo item = items.get(flight.flightId());
                        if (item == null) {
                            item = new FlightReviewItemPo(reviewId, flight.flightId(),
                                    "APPROVED", null, null);
                        } else {
                            // 评估阶段 reviewId 尚未生成，插入前补齐
                            item = new FlightReviewItemPo(reviewId, item.flightId(),
                                    item.result(), item.reason(), item.detail());
                        }
                        flightRepo.insertReviewItem(item);
                        itemDtos.add(new FlightReviewItemDto(item.flightId(), item.result(),
                                item.reason(), item.detail()));
                    }
                    if (approved) {
                        for (FlightPo flight : flights) {
                            flightRepo.markApproved(flight.flightId(), nowMillis());
                        }
                    }
                    return new MutationResponse(request.requestId(), false,
                            new FlightBatchReviewResult(reviewId, approved, itemDtos));
                });
    }

    /** 起飞：仅 APPROVED 航班可起飞；已起飞航班不再受后续关闭窗口影响。 */
    public MutationResponse depart(FlightActionRequest request) {
        return idempotency.execute(request.requestId(), KIND_FLIGHT_DEPART,
                idempotency.canonicalHash(request), () -> {
                    airspaceRepo.lockCoordinationRow();
                    FlightPo flight = requireFlightForUpdate(request.flightId());
                    if (!FlightStatus.APPROVED.name().equals(flight.status())) {
                        throw new ApiException(HttpStatus.CONFLICT, "FLIGHT_NOT_DEPARTABLE",
                                "仅 APPROVED 航班可起飞，当前状态: " + flight.status());
                    }
                    int updated = flightRepo.compareAndSetStatus(request.flightId(),
                            FlightStatus.APPROVED.name(), FlightStatus.DEPARTED.name());
                    if (updated == 0) {
                        throw new ApiException(HttpStatus.CONFLICT, "FLIGHT_NOT_DEPARTABLE",
                                "航班状态已变化，请重试");
                    }
                    return new MutationResponse(request.requestId(), false,
                            toDto(flightRepo.findFlight(request.flightId())));
                });
    }

    /** 取消：PENDING/APPROVED/RUNWAY_RISK 可取消；DEPARTED/CANCELLED 为终态。 */
    public MutationResponse cancel(FlightActionRequest request) {
        return idempotency.execute(request.requestId(), KIND_FLIGHT_CANCEL,
                idempotency.canonicalHash(request), () -> {
                    airspaceRepo.lockCoordinationRow();
                    FlightPo flight = requireFlightForUpdate(request.flightId());
                    if (FlightStatus.DEPARTED.name().equals(flight.status())) {
                        throw new ApiException(HttpStatus.CONFLICT, "FLIGHT_ALREADY_DEPARTED",
                                "已起飞航班不可取消: " + request.flightId());
                    }
                    if (FlightStatus.CANCELLED.name().equals(flight.status())) {
                        throw new ApiException(HttpStatus.CONFLICT, "FLIGHT_ALREADY_CANCELLED",
                                "航班已取消: " + request.flightId());
                    }
                    int updated = flightRepo.compareAndSetStatus(request.flightId(),
                            flight.status(), FlightStatus.CANCELLED.name());
                    if (updated == 0) {
                        throw new ApiException(HttpStatus.CONFLICT, "FLIGHT_NOT_CANCELLABLE",
                                "航班状态已变化，请重试");
                    }
                    return new MutationResponse(request.requestId(), false,
                            toDto(flightRepo.findFlight(request.flightId())));
                });
    }

    /** 改航：仅 RUNWAY_RISK 航班可改航；改航后回到 PENDING 并清除风险快照。 */
    public MutationResponse reroute(FlightRerouteRequest request) {
        return idempotency.execute(request.requestId(), KIND_FLIGHT_REROUTE,
                idempotency.canonicalHash(request), () -> {
                    airspaceRepo.lockCoordinationRow();
                    FlightPo flight = requireFlightForUpdate(request.flightId());
                    if (!FlightStatus.RUNWAY_RISK.name().equals(flight.status())) {
                        throw new ApiException(HttpStatus.CONFLICT, "FLIGHT_NOT_REROUTABLE",
                                "仅 RUNWAY_RISK 航班可改航，当前状态: " + flight.status());
                    }
                    if (request.arrTimeUtc() < request.depTimeUtc()) {
                        throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FLIGHT_SCHEDULE",
                                "落地时刻不得早于起飞时刻");
                    }
                    requireRunway(request.depRunwayId());
                    requireRunway(request.arrRunwayId());
                    flightRepo.reroute(request.flightId(), request.depRunwayId(),
                            request.depTimeUtc(), request.arrRunwayId(), request.arrTimeUtc());
                    flightRepo.deleteRisks(request.flightId());
                    return new MutationResponse(request.requestId(), false,
                            toDto(flightRepo.findFlight(request.flightId())));
                });
    }

    /**
     * 风险航班转为合格紧急例外：仅 RUNWAY_RISK 航班可执行；
     * 全部风险快照窗口都必须允许紧急例外，否则 422；
     * 转换后航线类型变为 EMERGENCY、附事件号并回到 PENDING，须重新批量审查。
     */
    public MutationResponse convertToEmergency(FlightEmergencyConvertRequest request) {
        return idempotency.execute(request.requestId(), KIND_FLIGHT_EMERGENCY_CONVERT,
                idempotency.canonicalHash(request), () -> {
                    airspaceRepo.lockCoordinationRow();
                    FlightPo flight = requireFlightForUpdate(request.flightId());
                    if (!FlightStatus.RUNWAY_RISK.name().equals(flight.status())) {
                        throw new ApiException(HttpStatus.CONFLICT, "FLIGHT_NOT_CONVERTIBLE",
                                "仅 RUNWAY_RISK 航班可转为紧急例外，当前状态: " + flight.status());
                    }
                    List<FlightRiskPo> risks = flightRepo.findRisks(request.flightId());
                    List<String> notAllowed = new ArrayList<>();
                    for (FlightRiskPo risk : risks) {
                        if (!risk.allowEmergency()) {
                            notAllowed.add(risk.closureId());
                        }
                    }
                    if (!notAllowed.isEmpty()) {
                        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                                FlightRejectReason.EMERGENCY_EXCEPTION_NOT_ALLOWED,
                                "风险窗口不允许紧急例外: " + String.join(",", notAllowed));
                    }
                    flightRepo.convertToEmergency(request.flightId(), request.eventNo());
                    flightRepo.deleteRisks(request.flightId());
                    return new MutationResponse(request.requestId(), false,
                            toDto(flightRepo.findFlight(request.flightId())));
                });
    }

    // ============================ 查询 ============================

    /** 查询航班详情（含跑道风险固化快照）。 */
    public FlightDetailResult getFlight(String flightId) {
        FlightPo flight = flightRepo.findFlight(flightId);
        if (flight == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "FLIGHT_NOT_FOUND",
                    "航班不存在: " + flightId);
        }
        List<FlightRiskDto> risks = new ArrayList<>();
        for (FlightRiskPo risk : flightRepo.findRisks(flightId)) {
            risks.add(new FlightRiskDto(risk.closureId(), risk.runwayId(), risk.runwayVersion(),
                    risk.startUtc(), risk.endUtc(), risk.allowEmergency(), risk.operator()));
        }
        return new FlightDetailResult(toDto(flight), risks);
    }

    /** 按 reviewId 查询批量审查结果（含逐航线原因，不可变）。 */
    public FlightBatchReviewResult getFlightReview(String reviewId) {
        Boolean approved = flightRepo.findReviewApproved(reviewId);
        if (approved == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND",
                    "批量审查记录不存在: " + reviewId);
        }
        List<FlightReviewItemDto> items = new ArrayList<>();
        for (FlightReviewItemPo item : flightRepo.findReviewItems(reviewId)) {
            items.add(new FlightReviewItemDto(item.flightId(), item.result(),
                    item.reason(), item.detail()));
        }
        return new FlightBatchReviewResult(reviewId, approved, items);
    }

    /** 查询航班最近一次审查原因。 */
    public FlightReviewReasonDto getFlightReviewReason(String flightId) {
        if (flightRepo.findFlight(flightId) == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "FLIGHT_NOT_FOUND",
                    "航班不存在: " + flightId);
        }
        FlightReviewItemPo item = flightRepo.findLatestReviewItem(flightId);
        if (item == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND",
                    "该航班尚无审查记录: " + flightId);
        }
        return new FlightReviewReasonDto(item.reviewId(), item.flightId(), item.result(),
                item.reason(), item.detail());
    }

    // ============================ 工具方法 ============================

    /**
     * 计算 closureKey 指纹：含跑道标识与携带版本、规范化时段（epoch 毫秒）、
     * 例外标志与操作者；相同语义提交产生相同键，同键重放，失败不占键。
     */
    private static String closureKeyOf(ClosureCreateRequest request) {
        String fingerprint = String.join("|",
                request.runwayId(),
                String.valueOf(request.expectedRunwayVersion()),
                String.valueOf(request.startUtc()),
                String.valueOf(request.endUtc()),
                String.valueOf(request.allowEmergency()),
                request.operator());
        return IdempotentMutationExecutor.sha256Hex(fingerprint);
    }

    /** 汇总航班起降段命中的关闭窗口（按跑道缓存窗口列表）。 */
    private List<RunwayClosurePo> intersectingClosures(
            FlightPo flight, Map<String, List<RunwayClosurePo>> closuresByRunway) {
        List<RunwayClosurePo> hits = new ArrayList<>();
        for (RunwayClosurePo closure : closuresByRunway.computeIfAbsent(
                flight.depRunwayId(), runwayRepo::findClosures)) {
            if (closure.contains(flight.depTimeUtc())) {
                hits.add(closure);
            }
        }
        for (RunwayClosurePo closure : closuresByRunway.computeIfAbsent(
                flight.arrRunwayId(), runwayRepo::findClosures)) {
            if (closure.contains(flight.arrTimeUtc())) {
                hits.add(closure);
            }
        }
        return hits;
    }

    /** 既有容量占用：APPROVED/DEPARTED 航班的起降槽位计数。 */
    private Map<String, Integer> capacityUsage() {
        Map<String, Integer> usage = new HashMap<>();
        for (FlightPo consumer : flightRepo.findCapacityConsumers()) {
            usage.merge(slotKey(consumer.depRunwayId(), consumer.depTimeUtc()), 1, Integer::sum);
            usage.merge(slotKey(consumer.arrRunwayId(), consumer.arrTimeUtc()), 1, Integer::sum);
        }
        return usage;
    }

    /** 容量槽位键：跑道 + UTC 小时桶。 */
    private static String slotKey(String runwayId, long timeUtc) {
        return runwayId + "@" + Math.floorDiv(timeUtc, HOUR_MILLIS);
    }

    private int capacityOf(Map<String, RunwayPo> runwayById, String runwayId) {
        RunwayPo runway = runwayById.computeIfAbsent(runwayId, runwayRepo::findRunway);
        if (runway == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RUNWAY_NOT_FOUND",
                    "跑道不存在: " + runwayId);
        }
        return runway.hourlyCapacity();
    }

    private void requireRunway(String runwayId) {
        if (runwayRepo.findRunway(runwayId) == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RUNWAY_NOT_FOUND",
                    "跑道不存在: " + runwayId);
        }
    }

    private FlightPo requireFlightForUpdate(String flightId) {
        FlightPo flight = flightRepo.findFlightForUpdate(flightId);
        if (flight == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "FLIGHT_NOT_FOUND",
                    "航班不存在: " + flightId);
        }
        return flight;
    }

    private static RouteType parseRouteType(String routeType) {
        try {
            return RouteType.valueOf(routeType);
        } catch (IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ROUTE_TYPE",
                    "航线类型必须是 NORMAL 或 EMERGENCY: " + routeType);
        }
    }

    private static String normalizeEventNo(String eventNo) {
        return eventNo == null || eventNo.isBlank() ? null : eventNo;
    }

    private static void reject(Map<String, FlightReviewItemPo> items, FlightPo flight,
                               String reason, String detail) {
        items.put(flight.flightId(), new FlightReviewItemPo(null, flight.flightId(),
                "REJECTED", reason, detail));
    }

    private static String closureIds(List<RunwayClosurePo> closures) {
        Set<String> ids = new TreeSet<>();
        for (RunwayClosurePo closure : closures) {
            ids.add(closure.closureId());
        }
        return String.join(",", ids);
    }

    private static ClosureResult toDto(RunwayClosurePo po) {
        return new ClosureResult(po.closureId(), po.runwayId(), po.runwayVersion(),
                po.startUtc(), po.endUtc(), po.allowEmergency(), po.operator(), po.closureKey());
    }

    private static FlightResult toDto(FlightPo po) {
        return new FlightResult(po.flightId(), po.routeId(), po.routeType(), po.eventNo(),
                po.depRunwayId(), po.depTimeUtc(), po.arrRunwayId(), po.arrTimeUtc(), po.status());
    }

    private long nowMillis() {
        return clock.instant().toEpochMilli();
    }
}
