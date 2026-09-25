package com.example.starter.service;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.ClosureCreateRequest;
import com.example.starter.api.dto.ClosureResult;
import com.example.starter.api.dto.ClosureWindowDto;
import com.example.starter.api.dto.EmergencyExceptionRequest;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.RouteRiskResult;
import com.example.starter.api.dto.RouteStateRequest;
import com.example.starter.api.dto.RouteStateResult;
import com.example.starter.api.dto.RunwayClosuresResult;
import com.example.starter.api.dto.RunwayCreateRequest;
import com.example.starter.api.dto.RunwayResult;
import com.example.starter.domain.FlightPlan;
import com.example.starter.domain.RouteStatus;
import com.example.starter.repo.AirspaceRepository;
import com.example.starter.repo.ClosurePo;
import com.example.starter.repo.RoutePo;
import com.example.starter.repo.RouteRepository;
import com.example.starter.repo.RouteRiskPo;
import com.example.starter.repo.RunwayPo;
import com.example.starter.repo.RunwayRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 跑道关闭与航线跑道风险业务服务。
 *
 * <p>关闭窗口为 UTC 左闭右开 [start, end)；同一跑道窗口不得重叠，端点相接合法；
 * 关闭变更携带跑道版本（乐观并发），closureKey 幂等键的指纹含跑道版本、
 * 规范化时段、例外标志与操作者，同键重放返回首次结果，失败不占键。</p>
 *
 * <p>新增关闭窗口时，未来已批准 NORMAL 航线（未起飞）命中窗口即转为
 * RUNWAY_RISK 并固化窗口快照；已起飞航线不改状态。风险航线只能改航、
 * 取消或按规则转为合格紧急例外，不能普通再次批准。</p>
 *
 * <p>关闭变更、起飞与改航事务都先更新协调锁行，与审查按提交顺序串行裁决；
 * 所有失败回滚事务，不留下半成品状态。</p>
 */
@Service
public class RunwayClosureService {

    static final String KIND_RUNWAY_CREATE = "RUNWAY_CREATE";
    static final String KIND_CLOSURE_CREATE = "CLOSURE_CREATE";
    static final String KIND_ROUTE_DEPART = "ROUTE_DEPART";
    static final String KIND_ROUTE_CANCEL = "ROUTE_CANCEL";
    static final String KIND_ROUTE_EMERGENCY_EXCEPTION = "ROUTE_EMERGENCY_EXCEPTION";

    private final RunwayRepository runwayRepo;
    private final RouteRepository routeRepo;
    private final AirspaceRepository airspaceRepo;
    private final IdempotencySupport idempotency;
    private final Clock clock;

    public RunwayClosureService(RunwayRepository runwayRepo,
                                RouteRepository routeRepo,
                                AirspaceRepository airspaceRepo,
                                IdempotencySupport idempotency,
                                Clock clock) {
        this.runwayRepo = runwayRepo;
        this.routeRepo = routeRepo;
        this.airspaceRepo = airspaceRepo;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    // ============================ 跑道 ============================

    /** 登记跑道（初始版本 1）；同键同参重放原结果，异参 409。 */
    public MutationResponse createRunway(RunwayCreateRequest request) {
        return idempotency.execute(request.requestId(), KIND_RUNWAY_CREATE, request, () -> {
            if (runwayRepo.findRunway(request.runwayId()) != null) {
                throw new ApiException(HttpStatus.CONFLICT, "RUNWAY_ALREADY_EXISTS",
                        "跑道已存在: " + request.runwayId());
            }
            runwayRepo.insertRunway(request.runwayId(), request.capacityPerHour());
            return new MutationResponse(request.requestId(), false,
                    new RunwayResult(request.runwayId(), 1, request.capacityPerHour()));
        });
    }

    /** 查询跑道全部关闭窗口与当前版本。 */
    public RunwayClosuresResult getRunwayClosures(String runwayId) {
        return idempotency.readOnly(() -> {
            RunwayPo runway = runwayRepo.findRunway(runwayId);
            if (runway == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "RUNWAY_NOT_FOUND",
                        "跑道不存在: " + runwayId);
            }
            List<ClosureWindowDto> windows = new ArrayList<>();
            for (ClosurePo c : runwayRepo.findClosures(runwayId)) {
                windows.add(new ClosureWindowDto(c.closureId(), c.startUtc(), c.endUtc(),
                        c.allowEmergency(), c.operator(), c.runwayVersion(), c.closureKey(),
                        c.createdAt()));
            }
            return new RunwayClosuresResult(runway.runwayId(), runway.version(),
                    runway.capacityPerHour(), windows);
        });
    }

    // ============================ 关闭窗口 ============================

    /**
     * 登记跑道关闭窗口。同一跑道窗口不得重叠（端点相接合法）；
     * 生效后未来已批准 NORMAL 航线命中窗口即转为 RUNWAY_RISK 并固化快照。
     */
    public MutationResponse createClosure(ClosureCreateRequest request) {
        return idempotency.execute(request.closureKey(), KIND_CLOSURE_CREATE, request, () -> {
            if (request.startUtc() >= request.endUtc()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CLOSURE_WINDOW",
                        "关闭窗口必须满足 startUtc < endUtc（UTC 左闭右开）");
            }
            // 先锁协调行：与审查、起飞、改航按提交顺序串行裁决
            airspaceRepo.getGlobalVersionForUpdate();
            // 再锁跑道行：关闭变更互斥，版本号与窗口集合来自一致状态
            RunwayPo runway = runwayRepo.findRunwayForUpdate(request.runwayId());
            if (runway == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "RUNWAY_NOT_FOUND",
                        "跑道不存在: " + request.runwayId());
            }
            if (runway.version() != request.expectedRunwayVersion()) {
                throw new ApiException(HttpStatus.CONFLICT, "RUNWAY_VERSION_CONFLICT",
                        "跑道版本不匹配：expected=" + request.expectedRunwayVersion()
                                + ", current=" + runway.version());
            }
            List<ClosurePo> overlapping = runwayRepo.findOverlappingClosures(
                    request.runwayId(), request.startUtc(), request.endUtc());
            if (!overlapping.isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, "CLOSURE_OVERLAP",
                        "同一跑道关闭窗口不得重叠（端点相接合法），与既有窗口冲突: "
                                + overlapping.get(0).closureId());
            }
            runwayRepo.incrementRunwayVersion(request.runwayId());
            int newVersion = runway.version() + 1;
            ClosurePo closure = new ClosurePo("rc_" + UUID.randomUUID(), request.runwayId(),
                    request.startUtc(), request.endUtc(), request.allowEmergency(),
                    request.operator(), newVersion, request.closureKey(),
                    clock.instant().toEpochMilli());
            runwayRepo.insertClosure(closure);
            List<String> riskRouteIds = markRiskRoutes(closure);
            return new MutationResponse(request.closureKey(), false,
                    new ClosureResult(closure.closureId(), closure.runwayId(), newVersion,
                            riskRouteIds));
        });
    }

    /**
     * 风险固化：未来已批准 NORMAL 航线（未起飞）的起降段命中新窗口时，
     * 转为 RUNWAY_RISK 并固化窗口快照；已起飞航线不改状态。
     */
    private List<String> markRiskRoutes(ClosurePo closure) {
        long now = clock.instant().toEpochMilli();
        List<RoutePo> affected = runwayRepo.findApprovedNormalRoutesIntersecting(
                closure.runwayId(), closure.startUtc(), closure.endUtc(), now);
        List<String> riskRouteIds = new ArrayList<>(affected.size());
        for (RoutePo route : affected) {
            FlightPlan plan = route.flightPlan();
            String segment = plan.depRunwayId() != null
                    && plan.depRunwayId().equals(closure.runwayId())
                    && plan.depTimeUtc() != null
                    && plan.depTimeUtc() >= closure.startUtc()
                    && plan.depTimeUtc() < closure.endUtc()
                    ? "DEPARTURE" : "ARRIVAL";
            runwayRepo.insertRisk(new RouteRiskPo(route.routeId(), closure.closureId(),
                    closure.runwayId(), segment, closure.startUtc(), closure.endUtc(),
                    closure.allowEmergency(), closure.operator(), closure.runwayVersion(),
                    clock.instant().toEpochMilli()));
            routeRepo.updateStatus(route.routeId(), RouteStatus.RUNWAY_RISK.name());
            riskRouteIds.add(route.routeId());
        }
        return riskRouteIds;
    }

    // ============================ 航线状态操作 ============================

    /** 起飞：仅已批准航线可起飞；与关闭变更按提交顺序裁决，已起飞航线不再受关闭影响。 */
    public MutationResponse depart(RouteStateRequest request) {
        return idempotency.execute(request.requestId(), KIND_ROUTE_DEPART, request, () -> {
            airspaceRepo.getGlobalVersionForUpdate();
            RoutePo route = lockRoute(request.routeId());
            if (RouteStatus.RUNWAY_RISK.name().equals(route.status())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ROUTE_RUNWAY_RISK",
                        "跑道风险航线不能起飞，只能改航、取消或转合格紧急例外: " + route.routeId());
            }
            if (RouteStatus.DEPARTED.name().equals(route.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "ROUTE_ALREADY_DEPARTED",
                        "航线已起飞: " + route.routeId());
            }
            if (RouteStatus.CANCELLED.name().equals(route.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "ROUTE_ALREADY_CANCELLED",
                        "航线已取消: " + route.routeId());
            }
            if (!RouteStatus.APPROVED.name().equals(route.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "ROUTE_NOT_APPROVED",
                        "航线尚未批准，不能起飞: " + route.routeId());
            }
            routeRepo.updateStatus(route.routeId(), RouteStatus.DEPARTED.name());
            return new MutationResponse(request.requestId(), false,
                    new RouteStateResult(route.routeId(), route.version(),
                            RouteStatus.DEPARTED.name()));
        });
    }

    /** 取消：待批准、已批准或风险航线可取消；取消后清除风险快照。 */
    public MutationResponse cancel(RouteStateRequest request) {
        return idempotency.execute(request.requestId(), KIND_ROUTE_CANCEL, request, () -> {
            airspaceRepo.getGlobalVersionForUpdate();
            RoutePo route = lockRoute(request.routeId());
            if (RouteStatus.DEPARTED.name().equals(route.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "ROUTE_ALREADY_DEPARTED",
                        "航线已起飞，不可取消: " + route.routeId());
            }
            if (RouteStatus.CANCELLED.name().equals(route.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "ROUTE_ALREADY_CANCELLED",
                        "航线已取消: " + route.routeId());
            }
            routeRepo.updateStatus(route.routeId(), RouteStatus.CANCELLED.name());
            runwayRepo.deleteRisk(route.routeId());
            return new MutationResponse(request.requestId(), false,
                    new RouteStateResult(route.routeId(), route.version(),
                            RouteStatus.CANCELLED.name()));
        });
    }

    /**
     * 风险航线转合格紧急例外：仅当固化的关闭窗口快照允许紧急例外时，
     * 附事件号转为 EMERGENCY 并恢复已批准状态；否则 422。
     */
    public MutationResponse convertToEmergencyException(EmergencyExceptionRequest request) {
        return idempotency.execute(request.requestId(), KIND_ROUTE_EMERGENCY_EXCEPTION, request,
                () -> {
                    airspaceRepo.getGlobalVersionForUpdate();
                    RoutePo route = lockRoute(request.routeId());
                    if (!RouteStatus.RUNWAY_RISK.name().equals(route.status())) {
                        throw new ApiException(HttpStatus.CONFLICT, "ROUTE_NOT_AT_RISK",
                                "仅跑道风险航线可转紧急例外: " + route.routeId()
                                        + ", status=" + route.status());
                    }
                    RouteRiskPo risk = runwayRepo.findRisk(route.routeId());
                    if (risk == null) {
                        throw new ApiException(HttpStatus.CONFLICT, "ROUTE_RISK_NOT_FOUND",
                                "航线风险快照不存在: " + route.routeId());
                    }
                    if (!risk.allowEmergency()) {
                        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                                "EMERGENCY_EXCEPTION_NOT_ALLOWED",
                                "固化的关闭窗口不允许紧急例外: " + risk.closureId());
                    }
                    routeRepo.convertToEmergency(route.routeId(), request.eventNo());
                    runwayRepo.deleteRisk(route.routeId());
                    return new MutationResponse(request.requestId(), false,
                            new RouteStateResult(route.routeId(), route.version(),
                                    RouteStatus.APPROVED.name()));
                });
    }

    /** 查询航线跑道风险快照。 */
    public RouteRiskResult getRouteRisk(String routeId) {
        return idempotency.readOnly(() -> {
            RouteRiskPo risk = runwayRepo.findRisk(routeId);
            if (risk == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "ROUTE_RISK_NOT_FOUND",
                        "航线无跑道风险记录: " + routeId);
            }
            RoutePo route = routeRepo.findRoute(routeId);
            String status = route == null ? null : route.status();
            return new RouteRiskResult(risk.routeId(), status, risk.closureId(), risk.runwayId(),
                    risk.segment(), risk.startUtc(), risk.endUtc(), risk.allowEmergency(),
                    risk.operator(), risk.runwayVersion(), risk.createdAt());
        });
    }

    private RoutePo lockRoute(String routeId) {
        RoutePo route = routeRepo.findRouteForUpdate(routeId);
        if (route == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND",
                    "航线不存在: " + routeId);
        }
        return route;
    }
}
