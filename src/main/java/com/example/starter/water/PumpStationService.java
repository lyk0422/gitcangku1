package com.example.starter.water;

import com.example.starter.water.PumpStationRepository.RequestRow;
import com.example.starter.water.PumpStationRepository.StationRow;
import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.WindowRow;
import com.example.starter.water.dto.Dtos.AllocationResponse;
import com.example.starter.water.dto.Dtos.CurveNode;
import com.example.starter.water.dto.Dtos.StationResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 泵站与流量曲线业务服务。
 *
 * <p>幂等：新增写入携带 requestId，身份为操作、操作者、资源与全部参数（含期望版本）的
 * 结构化 JSON，避免分隔符碰撞；同键同身份重放返回首次完整响应，异参返回 409，
 * 业务失败回滚不占键，并发同键不泄露唯一键异常。</p>
 *
 * <p>并发：配置写入锁定泵站行（FOR UPDATE），与曲线批准、取消、改配额共同判定；
 * 参数调小若使已有批准曲线非法返回 409。判断前不舍入。</p>
 */
@Service
public class PumpStationService {

    private static final long NANOS_PER_MINUTE = 60_000_000_000L;
    private static final Pattern FLOW_PATTERN = Pattern.compile("\\d{1,16}(\\.\\d{1,3})?");

    private final WaterRepository waterRepository;
    private final PumpStationRepository pumpRepository;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;

    public PumpStationService(WaterRepository waterRepository, PumpStationRepository pumpRepository,
                              PlatformTransactionManager transactionManager, ObjectMapper objectMapper) {
        this.waterRepository = waterRepository;
        this.pumpRepository = pumpRepository;
        this.tx = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    /**
     * 写入泵站配置：expectedVersion 为 0 表示新建（版本从 1 起），否则须等于当前版本；
     * 版本不一致返回 409。参数调小使已有批准曲线非法时返回 409，已有批准结果不变。
     */
    public StationResponse writeConfig(String stationKey, String requestId, String maxFlow,
                                       String maxRampPerMinute, Long expectedVersion, String actor) {
        WaterService.requireKey("stationKey", stationKey);
        WaterService.requireKey("requestId", requestId);
        BigDecimal flow = WaterService.parseAmount("maxFlow", maxFlow);
        BigDecimal ramp = WaterService.parseAmount("maxRampPerMinute", maxRampPerMinute);
        if (expectedVersion == null || expectedVersion < 0) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "expectedVersion 不能为空且必须大于等于 0");
        }
        Map<String, Object> params = new TreeMap<>();
        params.put("maxFlow", flow.toPlainString());
        params.put("maxRampPerMinute", ramp.toPlainString());
        params.put("expectedVersion", expectedVersion);
        return runRequest("STATION_CONFIG", requestId, actor, stationKey, params, StationResponse.class, () -> {
            StationRow station = pumpRepository.lockByKey(stationKey);
            long now = WaterService.nowNanos();
            if (station == null) {
                if (expectedVersion != 0) {
                    throw ApiException.conflict("VERSION_CONFLICT",
                            "泵站不存在，新建须使用 expectedVersion=0");
                }
                pumpRepository.insertStation(stationKey, flow, ramp, now);
                return toStationResponse(pumpRepository.findByKey(stationKey));
            }
            if (expectedVersion != station.version()) {
                throw ApiException.conflict("VERSION_CONFLICT",
                        "expectedVersion=" + expectedVersion + " 与当前版本 " + station.version() + " 不一致");
            }
            // 参数调小若使已有批准曲线非法则 409；判断前不舍入
            List<List<FlowCurve.Node>> approved = pumpRepository.listApprovedCurves(stationKey, 0L);
            try {
                FlowCurve.assertWithinLimits(approved, flow, ramp);
            } catch (ApiException e) {
                throw ApiException.conflict("CONFIG_VIOLATES_APPROVED",
                        "新配置使已有批准曲线非法: " + e.getMessage());
            }
            pumpRepository.updateConfig(station.id(), flow, ramp, station.version() + 1, now);
            return toStationResponse(pumpRepository.findByKey(stationKey));
        });
    }

    /** 查询泵站配置，不存在返回 404。 */
    public StationResponse getStation(String stationKey) {
        StationRow station = pumpRepository.findByKey(stationKey);
        if (station == null) {
            throw ApiException.notFound("STATION_NOT_FOUND", "泵站不存在: " + stationKey);
        }
        return toStationResponse(station);
    }

    /**
     * 提交带流量曲线的配水申请。结构校验（节点数、整分钟、递增、首末等于窗口边界、
     * 流量非负最多 3 位小数）失败返回 400；梯形积分与容量越限在批准时判定（422）。
     */
    public AllocationResponse submitCurveAllocation(String requestId, String allocationKey, Long windowId,
                                                    String userId, String amount, List<CurveNode> curve,
                                                    String actor) {
        WaterService.requireKey("requestId", requestId);
        WaterService.requireKey("allocationKey", allocationKey);
        WaterService.requireKey("userId", userId);
        WaterService.requireKey("X-Actor-Id", actor);
        if (windowId == null) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "windowId 不能为空");
        }
        BigDecimal qty = WaterService.parseAmount("amount", amount);
        List<FlowCurve.Node> nodes = parseCurve(curve);
        Map<String, Object> params = new TreeMap<>();
        params.put("allocationKey", allocationKey);
        params.put("windowId", windowId);
        params.put("userId", userId);
        params.put("amount", qty.toPlainString());
        List<Map<String, Object>> curveParams = new ArrayList<>();
        for (FlowCurve.Node node : nodes) {
            Map<String, Object> nodeParam = new TreeMap<>();
            nodeParam.put("minuteUtc", node.minute());
            nodeParam.put("flow", node.flow().toPlainString());
            curveParams.add(nodeParam);
        }
        params.put("curve", curveParams);
        return runRequest("CURVE_ALLOCATION_SUBMIT", requestId, actor, allocationKey, params,
                AllocationResponse.class, () -> {
                    WindowRow window = waterRepository.lockWindowById(windowId);
                    if (window == null) {
                        throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
                    }
                    if (window.stationKey() == null) {
                        throw ApiException.badRequest("CURVE_REQUIRES_STATION",
                                "窗口未关联泵站，不能提交流量曲线申请");
                    }
                    long firstNanos = Math.multiplyExact(nodes.get(0).minute(), NANOS_PER_MINUTE);
                    long lastNanos = Math.multiplyExact(nodes.get(nodes.size() - 1).minute(),
                            NANOS_PER_MINUTE);
                    if (firstNanos != window.startNanos() || lastNanos != window.endNanos()) {
                        throw ApiException.badRequest("CURVE_BOUNDARY_MISMATCH",
                                "曲线首末节点必须等于供水窗口边界");
                    }
                    long id = waterRepository.insertAllocation(allocationKey, windowId, userId, qty, actor,
                            WaterService.nowNanos());
                    for (int i = 0; i < nodes.size(); i++) {
                        FlowCurve.Node node = nodes.get(i);
                        pumpRepository.insertCurveNode(id, i, node.minute(), node.flow());
                    }
                    return toAllocationResponse(waterRepository.findAllocationByKey(allocationKey));
                });
    }

    /** 解析并结构校验曲线：2 至 20 个 UTC 整分钟节点，时刻严格递增，流量非负最多 3 位小数。 */
    private List<FlowCurve.Node> parseCurve(List<CurveNode> curve) {
        if (curve == null || curve.size() < 2 || curve.size() > 20) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "流量曲线须包含 2 至 20 个节点");
        }
        List<FlowCurve.Node> nodes = new ArrayList<>();
        long previous = Long.MIN_VALUE;
        for (int i = 0; i < curve.size(); i++) {
            CurveNode dto = curve.get(i);
            long nanos = WaterService.parseInstant("curve[" + i + "].timeUtc",
                    dto == null ? null : dto.timeUtc());
            if (nanos % NANOS_PER_MINUTE != 0) {
                throw ApiException.badRequest("INVALID_ARGUMENT",
                        "curve[" + i + "].timeUtc 必须为 UTC 整分钟");
            }
            long minute = nanos / NANOS_PER_MINUTE;
            if (minute <= previous) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "曲线节点时刻必须严格递增");
            }
            previous = minute;
            nodes.add(new FlowCurve.Node(minute,
                    parseFlow("curve[" + i + "].flow", dto == null ? null : dto.flow())));
        }
        return nodes;
    }

    /** 解析节点流量：十进制字符串，非负，最多 3 位小数。 */
    private static BigDecimal parseFlow(String field, String value) {
        if (value == null || value.isBlank() || !FLOW_PATTERN.matcher(value.trim()).matches()) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    field + " 必须为非负十进制字符串，最多 3 位小数: " + value);
        }
        return new BigDecimal(value.trim());
    }

    // ------------------------------------------------------------------
    // 幂等请求框架
    // ------------------------------------------------------------------

    /**
     * 在单事务内执行幂等请求：先占位插入请求行，再执行业务并写回响应，业务与去重原子提交。
     * 并发同键时占位插入因唯一约束失败，随后读取已提交的首次结果重放或报 409，不泄露唯一键异常。
     */
    private <T> T runRequest(String operation, String requestId, String actor, String resource,
                             Map<String, Object> params, Class<T> type, Supplier<T> business) {
        String safeActor = actor == null ? "" : actor;
        String paramsJson = toJson(params);
        try {
            return tx.execute(status -> {
                RequestRow existing = pumpRepository.findRequest(requestId);
                if (existing != null) {
                    return replay(existing, operation, safeActor, resource, paramsJson, type);
                }
                pumpRepository.insertRequest(requestId, operation, safeActor, resource, paramsJson,
                        WaterService.nowNanos());
                T result = business.get();
                pumpRepository.updateRequestResponse(requestId, toJson(result));
                return result;
            });
        } catch (DuplicateKeyException e) {
            RequestRow committed = pumpRepository.findRequest(requestId);
            if (committed == null || committed.response() == null) {
                throw ApiException.conflict("REQUEST_CONFLICT", "相同 requestId 的请求正在处理，请重试");
            }
            return replay(committed, operation, safeActor, resource, paramsJson, type);
        }
    }

    private <T> T replay(RequestRow existing, String operation, String actor, String resource,
                         String paramsJson, Class<T> type) {
        if (!existing.operation().equals(operation) || !existing.actor().equals(actor)
                || !existing.resource().equals(resource) || !existing.paramsJson().equals(paramsJson)) {
            throw ApiException.conflict("REQUEST_KEY_REUSED", "相同 requestId 但请求身份或参数不同，拒绝重放");
        }
        return fromJson(existing.response(), type);
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private StationResponse toStationResponse(StationRow row) {
        return new StationResponse(row.stationKey(), WaterService.fmt(row.maxFlow()),
                WaterService.fmt(row.maxRamp()), row.version(), WaterService.toIso(row.createdNanos()),
                WaterService.toIso(row.updatedNanos()));
    }

    private AllocationResponse toAllocationResponse(AllocationRow row) {
        List<CurveNode> curve = pumpRepository.listCurveNodes(row.id()).stream()
                .map(n -> new CurveNode(WaterService.toIso(n.minuteUtc() * NANOS_PER_MINUTE),
                        WaterService.fmt(n.flow())))
                .toList();
        return new AllocationResponse(row.allocationKey(), row.windowId(), row.userId(),
                WaterService.fmt(row.amount()), WaterService.fmt(row.heldAmount()), row.requester(),
                row.status(), WaterService.toIso(row.createdNanos()), WaterService.toIso(row.updatedNanos()),
                curve.isEmpty() ? null : curve, row.stationVersion());
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化失败", e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("响应反序列化失败", e);
        }
    }
}