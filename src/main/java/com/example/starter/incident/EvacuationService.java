package com.example.starter.incident;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.example.starter.incident.dto.Requests.ExemptionGrantRequest;
import com.example.starter.incident.dto.Requests.ZoneRegisterRequest;
import com.example.starter.incident.dto.Responses.ExemptionListView;
import com.example.starter.incident.dto.Responses.ExemptionView;
import com.example.starter.incident.dto.Responses.TaskBlockStatusListView;
import com.example.starter.incident.dto.Responses.TaskBlockStatusView;
import com.example.starter.incident.dto.Responses.ZoneListView;
import com.example.starter.incident.dto.Responses.ZoneView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 疏散区域与撤离豁免服务。
 *
 * <p>区域不设显式生效/结束动作：登记后由 UTC 左闭右开窗口在每次写事务
 * （区域登记、豁免授予、派工、开始、完成、撤离及结束裁决）内按提交顺序裁决。
 * zoneKey 指纹含事件版本（事件 updatedAt）、规范化网格、窗口、等级和操作者，
 * 同键（commandKey）重放首次响应，同指纹登记重放首次区域，失败不占键。
 */
@Service
public class EvacuationService {

    private final IncidentRepository incidents;
    private final IncidentTaskRepository tasks;
    private final EvacuationRepository evacuation;
    private final EvacuationGate gate;
    private final Idempotency idempotency;
    private final ZoneCommandKeyRepository zoneCommandKeys;
    private final Clock clock;

    public EvacuationService(IncidentRepository incidents, IncidentTaskRepository tasks,
                             EvacuationRepository evacuation, EvacuationGate gate,
                             Idempotency idempotency, ZoneCommandKeyRepository zoneCommandKeys,
                             Clock clock) {
        this.incidents = incidents;
        this.tasks = tasks;
        this.evacuation = evacuation;
        this.gate = gate;
        this.idempotency = idempotency;
        this.zoneCommandKeys = zoneCommandKeys;
        this.clock = clock;
    }

    private Instant now() {
        return clock.instant();
    }

    /**
     * 登记疏散区域：仅事件当前指挥人、事件处于开放处置状态（COMMANDING/CONTAINED）；
     * 网格规范化后非空，窗口 UTC 左闭右开（from &lt; to）；同事件同等级窗口网格不可重叠。
     * 登记提交后立即裁决：若窗口已覆盖当前时刻，未开始命中且无豁免任务转 EVACUATION_BLOCKED。
     */
    @Transactional
    public ZoneView registerZone(String incidentKey, String actor, ZoneRegisterRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String zoneKey = requireText(req.zoneKey(), "zoneKey");
        RiskLevel riskLevel = RiskLevel.parse(req.riskLevel());
        if (riskLevel == null) {
            throw ApiException.badRequest("riskLevel 必须为 HIGH/MEDIUM/LOW");
        }
        List<String> grids = GridSets.normalize(req.grids());
        if (grids.isEmpty()) {
            throw ApiException.badRequest("grids 至少包含一个有效网格");
        }
        if (req.effectiveFrom() == null || req.effectiveTo() == null) {
            throw ApiException.badRequest("effectiveFrom/effectiveTo 不能为空");
        }
        Instant from = req.effectiveFrom().truncatedTo(ChronoUnit.MICROS);
        Instant to = req.effectiveTo().truncatedTo(ChronoUnit.MICROS);
        if (!from.isBefore(to)) {
            throw ApiException.badRequest("生效窗口必须满足 effectiveFrom < effectiveTo");
        }
        Incident incident = lockIncident(incidentKey);
        String requestHash = Idempotency.hash(incidentKey, actor, zoneKey, riskLevel.name(),
                String.join(",", grids), from.toString(), to.toString());
        return idempotency.run(zoneCommandKeys, commandKey, "zone_register", requestHash,
                ZoneView.class, now(), () -> {
                    IncidentService.requireCommanderStatic(incident, actor);
                    requireOpenForZone(incident);
                    var sameKey = evacuation.findZoneByKey(incident.id(), zoneKey);
                    if (sameKey.isPresent()) {
                        EvacuationZone existing = sameKey.get();
                        if (sameShape(existing, riskLevel, grids, from, to, actor)) {
                            return toZoneView(existing);
                        }
                        throw ApiException.conflict("zoneKey 已被不同内容使用: " + zoneKey);
                    }
                    int version = evacuation.nextVersion(incident.id());
                    String fingerprint = GridSets.fingerprint(
                            incident.updatedAt().truncatedTo(ChronoUnit.MICROS).toString(),
                            grids, from.toString(), to.toString(), riskLevel.name(), actor);
                    // 同指纹（事件版本+规范化网格+窗口+等级+操作者）重放首次区域，即便 zoneKey 不同。
                    var sameFingerprint = evacuation.findZoneByFingerprint(incident.id(), fingerprint);
                    if (sameFingerprint.isPresent()) {
                        return toZoneView(sameFingerprint.get());
                    }
                    for (EvacuationZone other : evacuation.listZones(incident.id())) {
                        if (other.riskLevel() == riskLevel && windowsOverlap(from, to, other)
                                && GridSets.intersects(grids, other.grids())) {
                            throw ApiException.unprocessable("ZONE_WINDOW_OVERLAP",
                                    "同等级疏散区域的生效窗口与网格不可重叠: " + other.zoneKey(),
                                    Map.of("conflictZoneKey", other.zoneKey(),
                                            "version", other.version()));
                        }
                    }
                    Instant now = now();
                    EvacuationZone zone = new EvacuationZone(0L, incident.id(), zoneKey, version,
                            riskLevel, grids, from, to, ZoneStatus.REGISTERED, actor, fingerprint,
                            null, now, now);
                    long zoneId;
                    try {
                        zoneId = evacuation.insertZone(zone);
                    } catch (DuplicateKeyException e) {
                        EvacuationZone byFingerprint = evacuation
                                .findZoneByFingerprint(incident.id(), fingerprint).orElse(null);
                        if (byFingerprint != null) {
                            return toZoneView(byFingerprint);
                        }
                        throw ApiException.conflict("zoneKey 或区域指纹冲突: " + zoneKey);
                    }
                    // 登记后立即按当前时刻裁决（覆盖当前时刻的窗口即刻阻断命中任务）。
                    gate.evaluate(incident);
                    return toZoneView(evacuation.findZoneById(zoneId).orElseThrow());
                });
    }

    /**
     * 显式结束裁决：按当前时刻结束到期区域并恢复/改挂阻断任务；无到期区域时为幂等空操作。
     * 供需要在不进行其他写操作时推动“区域结束 → 任务恢复”的调用方使用。
     */
    @Transactional
    public ZoneListView endZones(String incidentKey, String commandKey) {
        String key = requireText(commandKey, "commandKey");
        Incident incident = lockIncident(incidentKey);
        return idempotency.run(zoneCommandKeys, key, "zone_end",
                Idempotency.hash(incidentKey), ZoneListView.class, now(), () -> {
                    gate.evaluate(incident);
                    return toZoneListView(incident.incidentKey(),
                            evacuation.listZones(incident.id()));
                });
    }

    /**
     * 授予撤离豁免：为任务授予指定区域当前版本的豁免；同一区域同一任务重复授予幂等返回首次豁免。
     * 区域须仍 REGISTERED；任务已存在时以其作业网格为准，任务尚未创建（创建前预授权）时
     * 必须显式给出 workGrid；作业网格必须落在区域网格集合内（豁免作用域）。
     * 授予后若该任务正因此区域处于 EVACUATION_BLOCKED 且无其他命中区域，则恢复为 OPEN。
     */
    @Transactional
    public ExemptionView grantExemption(String incidentKey, String zoneKey, String actor,
                                        ExemptionGrantRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String taskKey = requireText(req.taskKey(), "taskKey");
        String requestGrid = req.workGrid() == null ? null : req.workGrid().strip();
        Incident incident = lockIncident(incidentKey);
        return idempotency.run(zoneCommandKeys, commandKey, "exemption_grant",
                Idempotency.hash(incidentKey, actor, zoneKey, taskKey,
                        requestGrid == null ? "" : requestGrid),
                ExemptionView.class, now(), () -> {
                    IncidentService.requireCommanderStatic(incident, actor);
                    requireOpenForZone(incident);
                    EvacuationZone zone = evacuation.findZoneByKey(incident.id(), zoneKey)
                            .orElseThrow(() -> ApiException.notFound("疏散区域不存在: " + zoneKey));
                    if (zone.status() != ZoneStatus.REGISTERED) {
                        throw ApiException.unprocessable("ZONE_ENDED",
                                "疏散区域已结束，不能再授予豁免: " + zoneKey, null);
                    }
                    IncidentTask task = tasks.findByKey(incident.id(), taskKey).orElse(null);
                    String workGrid;
                    if (task != null) {
                        workGrid = task.workGrid();
                    } else {
                        if (requestGrid == null || requestGrid.isBlank()) {
                            throw ApiException.badRequest(
                                    "任务尚未创建时授予豁免必须提供 workGrid");
                        }
                        workGrid = requestGrid;
                    }
                    if (!zone.containsGrid(workGrid)) {
                        throw ApiException.unprocessable("EXEMPTION_SCOPE_GRID",
                                "作业网格不在区域网格内，豁免无作用域: " + workGrid,
                                Map.of("taskKey", taskKey, "workGrid", workGrid));
                    }
                    var existing = evacuation.findExemption(zone.id(), taskKey);
                    if (existing.isPresent()) {
                        return toExemptionView(zone.zoneKey(), existing.get());
                    }
                    Instant now = now();
                    long id = evacuation.insertExemption(new EvacuationExemption(0L, incident.id(),
                            zone.id(), zone.version(), taskKey, actor, commandKey, now));
                    // 持有效豁免后，解除该任务因本区域产生的未开始阻断（仍命中其他区域则改挂）。
                    if (task != null && task.status() == TaskStatus.EVACUATION_BLOCKED
                            && task.blockedZoneId() != null && task.blockedZoneId() == zone.id()) {
                        List<EvacuationZone> others = gate.violatingZones(
                                incident.id(), taskKey, task.workGrid(), now, zone.id());
                        if (others.isEmpty()) {
                            tasks.reopenBlocked(task.id(), now);
                        } else {
                            EvacuationZone next = others.get(0);
                            tasks.repointBlocked(task.id(), next.id(), gate.snapshotJson(next), now);
                        }
                    }
                    return toExemptionView(zone.zoneKey(),
                            evacuation.listExemptions(incident.id()).stream()
                                    .filter(e -> e.id() == id).findFirst().orElseThrow());
                });
    }

    /**
     * 查询事件全部疏散区域（effective 为查询时刻按窗口计算）。只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public ZoneListView listZones(String incidentKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        return toZoneListView(incidentKey, evacuation.listZones(incident.id()));
    }

    /**
     * 查询事件全部撤离豁免（含版本）。只读。
     */
    @Transactional(readOnly = true)
    public ExemptionListView listExemptions(String incidentKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        Map<Long, String> zoneKeys = evacuation.listZones(incident.id()).stream()
                .collect(Collectors.toMap(EvacuationZone::id, EvacuationZone::zoneKey));
        List<ExemptionView> views = evacuation.listExemptions(incident.id()).stream()
                .map(e -> toExemptionView(zoneKeys.get(e.zoneId()), e))
                .toList();
        return new ExemptionListView(incidentKey, views);
    }

    /**
     * 查询任务阻断与豁免情况：逐任务返回当前有效命中但缺少豁免的区域键及已持豁免。只读。
     */
    @Transactional(readOnly = true)
    public TaskBlockStatusListView taskBlockStatus(String incidentKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        Map<Long, String> zoneKeys = evacuation.listZones(incident.id()).stream()
                .collect(Collectors.toMap(EvacuationZone::id, EvacuationZone::zoneKey));
        Instant now = now();
        List<TaskBlockStatusView> views = tasks.listByIncident(incident.id()).stream()
                .map(task -> {
                    List<String> blockedBy = gate.violatingZones(
                                    incident.id(), task.taskKey(), task.workGrid(), now, -1L).stream()
                            .map(z -> zoneKeys.getOrDefault(z.id(), z.zoneKey()))
                            .toList();
                    List<ExemptionView> exemptions = evacuation
                            .listExemptionsForTask(incident.id(), task.taskKey()).stream()
                            .map(e -> toExemptionView(zoneKeys.get(e.zoneId()), e))
                            .toList();
                    return new TaskBlockStatusView(task.taskKey(), task.workGrid(),
                            task.status().name(), blockedBy, exemptions);
                })
                .toList();
        return new TaskBlockStatusListView(incidentKey, views);
    }

    private static boolean windowsOverlap(Instant from, Instant to, EvacuationZone other) {
        return from.isBefore(other.effectiveTo()) && other.effectiveFrom().isBefore(to);
    }

    private static boolean sameShape(EvacuationZone zone, RiskLevel riskLevel, List<String> grids,
                                     Instant from, Instant to, String actor) {
        return zone.riskLevel() == riskLevel && zone.grids().equals(grids)
                && zone.effectiveFrom().equals(from) && zone.effectiveTo().equals(to)
                && zone.registeredBy().equals(actor);
    }

    private static void requireOpenForZone(Incident incident) {
        if (incident.status() != IncidentStatus.COMMANDING
                && incident.status() != IncidentStatus.CONTAINED) {
            throw ApiException.illegalTransition(
                    "仅开放处置状态（COMMANDING/CONTAINED）的事件可登记疏散区域或授予豁免，当前状态: "
                            + incident.status());
        }
    }

    private Incident lockIncident(String incidentKey) {
        requireText(incidentKey, "incidentKey");
        return incidents.lockByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(field + " 不能为空");
        }
        return value.strip();
    }

    private ZoneView toZoneView(EvacuationZone zone) {
        return new ZoneView(zone.zoneKey(), zone.version(), zone.riskLevel().name(), zone.grids(),
                zone.effectiveFrom(), zone.effectiveTo(), zone.status().name(),
                gate.effectiveNow(zone), zone.registeredBy(), zone.endedAt(), zone.createdAt());
    }

    private ZoneListView toZoneListView(String incidentKey, List<EvacuationZone> zones) {
        return new ZoneListView(incidentKey, zones.stream().map(this::toZoneView).toList());
    }

    private static ExemptionView toExemptionView(String zoneKey, EvacuationExemption exemption) {
        return new ExemptionView(exemption.id(), zoneKey, exemption.version(),
                exemption.exemptTaskKey(), exemption.grantedBy(), exemption.createdAt());
    }
}
