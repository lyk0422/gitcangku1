package com.example.starter.incident;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.starter.incident.dto.Requests.DispatchItem;
import com.example.starter.incident.dto.Requests.DispatchRequest;
import com.example.starter.incident.dto.Requests.ExemptionGrantRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.ZoneRegisterRequest;
import com.example.starter.incident.dto.Requests.ZoneReviseRequest;
import com.example.starter.incident.dto.Responses.DispatchView;
import com.example.starter.incident.dto.Responses.ExemptionView;
import com.example.starter.incident.dto.Responses.IncidentExemptionsView;
import com.example.starter.incident.dto.Responses.IncidentZoneBlocksView;
import com.example.starter.incident.dto.Responses.IncidentZonesView;
import com.example.starter.incident.dto.Responses.TaskView;
import com.example.starter.incident.dto.Responses.ZoneBlockView;
import com.example.starter.incident.dto.Responses.ZoneView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 事件疏散区域与高危任务进入门禁服务。
 * 并发约定：所有写接口先 SELECT ... FOR UPDATE 锁定事件行，同事务内完成幂等键占位、
 * 业务校验与写入；批量派工额外持有 resource_lease_lock 全局锁，串行化资源校验与租约写入。
 * 区域效果（阻断/恢复）按注入 Clock 惰性物化：写操作与查询入口先 refresh 再裁决，
 * 保证区域、豁免、派工、开始、完成按事务提交顺序生效。
 * zoneKey 指纹：事件键+事件版本+谱系键+规范化网格+窗口+等级+操作者+谱系版本号的
 * SHA-256 截断；同键重放首次结果，失败不占键（事务回滚）。
 */
@Service
public class EvacuationService {

    /** 风险等级取值。 */
    private static final Set<String> RISK_LEVELS = Set.of("LOW", "MEDIUM", "HIGH");

    private final IncidentRepository incidents;
    private final IncidentTaskRepository tasks;
    private final EvacuationRepository evacuation;
    private final IdempotentExecutor idempotent;
    private final TaskViewMapper taskViewMapper;
    private final Clock clock;

    public EvacuationService(IncidentRepository incidents, IncidentTaskRepository tasks,
                             EvacuationRepository evacuation, IdempotentExecutor idempotent,
                             TaskViewMapper taskViewMapper, Clock clock) {
        this.incidents = incidents;
        this.tasks = tasks;
        this.evacuation = evacuation;
        this.idempotent = idempotent;
        this.taskViewMapper = taskViewMapper;
        this.clock = clock;
    }

    private Instant now() {
        return clock.instant();
    }

    // ---------- 区域登记与修订 ----------

    /**
     * 登记疏散区域：事件须为 OPEN（REPORTED/COMMANDING/CONTAINED）；网格规范化排序；
     * 同事件同等级的当前区域窗口网格不可重叠（409）。zoneKey 由服务端按指纹生成，
     * 同键重放首次登记结果；校验失败事务回滚不占键。登记后惰性物化区域效果。
     */
    @Transactional
    public ZoneView registerZone(String incidentKey, String actor, ZoneRegisterRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String operator = requireText(actor, "X-Actor-Id");
        List<String> grids = Grids.normalize(req.grids(), "grids");
        Instant from = requireWindowStart(req.effectiveFrom());
        Instant to = requireWindowEnd(req.effectiveTo(), from);
        String level = requireRiskLevel(req.riskLevel());
        Incident incident = lockIncident(incidentKey);
        return idempotent.run(commandKey, "zone_register",
                IdempotentExecutor.hash(incidentKey, operator, Grids.canonical(grids),
                        from.toString(), to.toString(), level),
                ZoneView.class, () -> {
                    requireIncidentOpen(incident);
                    String zoneKey = zoneFingerprint(incident, "", grids, from, to, level,
                            operator, 1);
                    var existing = evacuation.findZoneByKey(zoneKey);
                    if (existing.isPresent()) {
                        return toZoneView(existing.get(), now());
                    }
                    checkOverlap(incident.id(), null, level, grids, from, to);
                    Instant now = now();
                    evacuation.insertZone(new EvacuationZone(0L, incident.id(), zoneKey, zoneKey,
                            1, grids, from, to, level, operator, null, now));
                    refresh(incident);
                    return toZoneView(evacuation.findZoneByKey(zoneKey).orElseThrow(), now);
                });
    }

    /**
     * 修订区域：仅最新版本可修订，仅可改网格与窗口（等级沿用谱系）；产生新版本，
     * 旧版本标记取代，旧版本豁免随之失效。同键（同内容同版本）重放首次结果。
     */
    @Transactional
    public ZoneView reviseZone(String incidentKey, String zoneKey, String actor,
                               ZoneReviseRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String operator = requireText(actor, "X-Actor-Id");
        List<String> grids = Grids.normalize(req.grids(), "grids");
        Instant from = requireWindowStart(req.effectiveFrom());
        Instant to = requireWindowEnd(req.effectiveTo(), from);
        Incident incident = lockIncident(incidentKey);
        return idempotent.run(commandKey, "zone_revise",
                IdempotentExecutor.hash(incidentKey, zoneKey, operator, Grids.canonical(grids),
                        from.toString(), to.toString()),
                ZoneView.class, () -> {
                    requireIncidentOpen(incident);
                    EvacuationZone current = findZoneOfIncident(incident, zoneKey);
                    if (current.supersededAt() != null) {
                        throw ApiException.conflict("区域版本已被修订取代，不能再次修订: " + zoneKey);
                    }
                    int newVersion = current.version() + 1;
                    String newKey = zoneFingerprint(incident, current.groupKey(), grids, from, to,
                            current.riskLevel(), operator, newVersion);
                    var existing = evacuation.findZoneByKey(newKey);
                    if (existing.isPresent()) {
                        return toZoneView(existing.get(), now());
                    }
                    checkOverlap(incident.id(), current.groupKey(), current.riskLevel(), grids,
                            from, to);
                    Instant now = now();
                    evacuation.supersedeZone(current.id(), now);
                    evacuation.insertZone(new EvacuationZone(0L, incident.id(), newKey,
                            current.groupKey(), newVersion, grids, from, to, current.riskLevel(),
                            operator, null, now));
                    refresh(incident);
                    return toZoneView(evacuation.findZoneByKey(newKey).orElseThrow(), now);
                });
    }

    /**
     * 查询事件疏散区域（含全部谱系版本）。查询前惰性物化区域效果。
     */
    @Transactional
    public IncidentZonesView listZones(String incidentKey) {
        Incident incident = lockIncident(incidentKey);
        refresh(incident);
        Instant now = now();
        List<ZoneView> zones = evacuation.listZonesByIncident(incident.id()).stream()
                .map(z -> toZoneView(z, now))
                .toList();
        return new IncidentZonesView(incident.incidentKey(), zones);
    }

    // ---------- 撤离豁免 ----------

    /**
     * 签发撤离豁免：针对（任务，区域当前版本），允许任务创建前预授权；
     * 同任务同区域版本重复签发同内容幂等返回，不同理由 409。
     * 补发有效豁免时同事务解除该任务对该区域的阻断。
     */
    @Transactional
    public ExemptionView grantExemption(String incidentKey, String actor,
                                        ExemptionGrantRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String operator = requireText(actor, "X-Actor-Id");
        String taskKey = requireText(req.taskKey(), "taskKey");
        String zoneKey = requireText(req.zoneKey(), "zoneKey");
        String reason = requireText(req.reason(), "reason");
        Incident incident = lockIncident(incidentKey);
        return idempotent.run(commandKey, "exemption_grant",
                IdempotentExecutor.hash(incidentKey, operator, taskKey, zoneKey, reason),
                ExemptionView.class, () -> {
                    requireIncidentOpen(incident);
                    EvacuationZone zone = findZoneOfIncident(incident, zoneKey);
                    if (zone.supersededAt() != null) {
                        throw ApiException.conflict("区域版本已被修订取代，不能对其签发豁免: "
                                + zoneKey);
                    }
                    var existing = evacuation.findExemption(incident.id(), taskKey, zone.id(),
                            zone.version());
                    if (existing.isPresent()) {
                        ZoneExemption found = existing.get();
                        if (!found.reason().equals(reason)) {
                            throw ApiException.conflict(
                                    "该任务已持有该区域版本的不同豁免: " + taskKey);
                        }
                        return toExemptionView(found, true);
                    }
                    Instant now = now();
                    try {
                        evacuation.insertExemption(new ZoneExemption(0L, incident.id(), taskKey,
                                zone.id(), zone.zoneKey(), zone.version(), reason, operator, now));
                    } catch (DuplicateKeyException e) {
                        return toExemptionView(evacuation.findExemption(incident.id(), taskKey,
                                zone.id(), zone.version()).orElseThrow(), true);
                    }
                    // 补发有效豁免：解除该任务对该区域的阻断，无其他阻断时恢复 OPEN
                    tasks.findByKey(incident.id(), taskKey).ifPresent(task -> {
                        if (task.status() == TaskStatus.EVACUATION_BLOCKED) {
                            evacuation.releaseBlock(task.id(), zone.id(), now);
                            if (!evacuation.hasAnyActiveBlock(task.id())) {
                                tasks.restoreOpen(task.id(), now);
                            }
                        }
                    });
                    return toExemptionView(evacuation.findExemption(incident.id(), taskKey,
                            zone.id(), zone.version()).orElseThrow(), true);
                });
    }

    /**
     * 查询事件全部豁免及版本有效性（豁免版本查询）。只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public IncidentExemptionsView listExemptions(String incidentKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        Map<Long, EvacuationZone> zonesById = new HashMap<>();
        for (EvacuationZone zone : evacuation.listZonesByIncident(incident.id())) {
            zonesById.put(zone.id(), zone);
        }
        List<ExemptionView> views = evacuation.listExemptionsByIncident(incident.id()).stream()
                .map(e -> {
                    EvacuationZone zone = zonesById.get(e.zoneId());
                    boolean valid = zone != null && zone.supersededAt() == null
                            && zone.version() == e.zoneVersion();
                    return toExemptionView(e, valid);
                })
                .toList();
        return new IncidentExemptionsView(incident.incidentKey(), views);
    }

    // ---------- 任务阻断查询 ----------

    /**
     * 查询事件任务阻断快照（含已解除历史）。查询前惰性物化区域效果。
     */
    @Transactional
    public IncidentZoneBlocksView listBlocks(String incidentKey) {
        Incident incident = lockIncident(incidentKey);
        refresh(incident);
        Map<Long, String> taskKeys = new HashMap<>();
        for (IncidentTask task : tasks.listByIncident(incident.id())) {
            taskKeys.put(task.id(), task.taskKey());
        }
        List<ZoneBlockView> blocks = evacuation.listBlocksByIncident(incident.id()).stream()
                .map(b -> new ZoneBlockView(taskKeys.get(b.taskId()), b.zoneKey(), b.zoneVersion(),
                        b.zoneGrids(), b.zoneEffectiveFrom(), b.zoneEffectiveTo(), b.riskLevel(),
                        b.blockedAt(), b.releasedAt(), b.releasedAt() == null))
                .toList();
        return new IncidentZoneBlocksView(incident.incidentKey(), blocks);
    }

    // ---------- 高危任务门禁：开始 / 批量派工 / 撤离登记 ----------

    /**
     * 开始任务：仅当前指挥人；仅 OPEN 可开始；EVACUATION_BLOCKED 拒绝（422）；
     * 高危任务作业网格命中有效疏散区域时须持有该区域版本豁免（422）。
     */
    @Transactional
    public TaskView startTask(String incidentKey, String taskKey, String actor,
                              TaskActionRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        Incident incident = lockIncident(incidentKey);
        return idempotent.run(commandKey, "task_start",
                IdempotentExecutor.hash(incidentKey, taskKey, actor),
                TaskView.class, () -> {
                    requireCommander(incident, actor);
                    refresh(incident);
                    IncidentTask task = tasks.findByKey(incident.id(), taskKey)
                            .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskKey));
                    switch (task.status()) {
                        case OPEN -> {
                        }
                        case EVACUATION_BLOCKED -> throw ApiException.unprocessable(
                                "任务被有效疏散区域阻断，不能开始", List.of(taskKey));
                        case IN_PROGRESS -> throw ApiException.conflict("任务已开始: " + taskKey);
                        default -> throw ApiException.conflict(
                                "任务已处于终态 " + task.status() + "，不能开始");
                    }
                    requireExemptionsIfHit(incident, task);
                    tasks.markStarted(task.id(), actor, now());
                    return taskViewMapper.toTaskView(
                            tasks.findByKey(incident.id(), taskKey).orElseThrow());
                });
    }

    /**
     * 批量派工：仅当前指挥人；先校验全部任务的最终位置、资源依赖与撤离豁免，
     * 任一缺失 422（details 逐任务给出原因），租约与任务状态全部回滚；
     * 全部通过后原子置 IN_PROGRESS 并获取 ACTIVE 租约。
     */
    @Transactional
    public DispatchView dispatch(String incidentKey, String actor, DispatchRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        if (req.items() == null || req.items().isEmpty()) {
            throw ApiException.badRequest("items 不能为空");
        }
        Incident incident = lockIncident(incidentKey);
        String fingerprint = req.items().stream()
                .map(i -> (i.taskKey() == null ? "" : i.taskKey()) + "|"
                        + (i.finalPosition() == null ? "" : i.finalPosition()) + "|"
                        + (i.resources() == null ? "" : String.join(",", i.resources())))
                .reduce("", (a, b) -> a + ";" + b);
        return idempotent.run(commandKey, "task_dispatch",
                IdempotentExecutor.hash(incidentKey, actor, fingerprint),
                DispatchView.class, () -> {
                    requireCommander(incident, actor);
                    refresh(incident);
                    // 全局资源锁：串行化跨事件资源可用性校验与租约写入
                    evacuation.lockLeases();
                    List<String> violations = new ArrayList<>();
                    List<IncidentTask> validated = new ArrayList<>();
                    List<String> finalPositions = new ArrayList<>();
                    List<List<String>> resourcesPerItem = new ArrayList<>();
                    Set<String> seenTaskKeys = new HashSet<>();
                    Set<String> batchResources = new HashSet<>();
                    for (DispatchItem item : req.items()) {
                        String taskKey = item.taskKey() == null || item.taskKey().isBlank()
                                ? null : item.taskKey().strip();
                        if (taskKey == null) {
                            throw ApiException.badRequest("taskKey 不能为空");
                        }
                        if (!seenTaskKeys.add(taskKey)) {
                            violations.add(taskKey + ": 批次内重复派工");
                            continue;
                        }
                        var taskOpt = tasks.findByKey(incident.id(), taskKey);
                        if (taskOpt.isEmpty()) {
                            throw ApiException.notFound("任务不存在: " + taskKey);
                        }
                        IncidentTask task = taskOpt.get();
                        if (task.status() != TaskStatus.OPEN) {
                            violations.add(taskKey + ": 任务状态 " + task.status() + " 不可派工");
                        }
                        String finalPosition = item.finalPosition() == null
                                || item.finalPosition().isBlank()
                                ? task.finalPosition()
                                : Grids.normalizeOne(item.finalPosition(), "finalPosition");
                        if (finalPosition == null) {
                            violations.add(taskKey + ": 缺少最终位置");
                        }
                        for (String missing : missingExemptions(incident.id(), task.taskKey(),
                                task.workGrids(), task.highRisk())) {
                            violations.add(taskKey + ": 缺少撤离豁免 " + missing);
                        }
                        List<String> resources = new ArrayList<>();
                        if (item.resources() != null) {
                            for (String raw : item.resources()) {
                                if (raw == null || raw.isBlank()) {
                                    throw ApiException.badRequest("resources 含空资源键");
                                }
                                resources.add(raw.strip());
                            }
                        }
                        for (String resource : new LinkedHashSet<>(resources)) {
                            if (!batchResources.add(resource)) {
                                violations.add(taskKey + ": 批次内资源重复 " + resource);
                            } else if (evacuation.findActiveLease(resource).isPresent()) {
                                violations.add(taskKey + ": 资源被占用 " + resource);
                            }
                        }
                        validated.add(task);
                        finalPositions.add(finalPosition);
                        resourcesPerItem.add(resources);
                    }
                    if (!violations.isEmpty()) {
                        throw ApiException.unprocessable("批量派工校验失败",
                                List.copyOf(violations));
                    }
                    Instant now = now();
                    List<TaskView> views = new ArrayList<>();
                    List<String> leased = new ArrayList<>();
                    for (int i = 0; i < validated.size(); i++) {
                        IncidentTask task = validated.get(i);
                        String finalPosition = finalPositions.get(i);
                        if (finalPosition != null
                                && !finalPosition.equals(task.finalPosition())) {
                            tasks.updateFinalPosition(task.id(), finalPosition, now);
                        }
                        tasks.markStarted(task.id(), actor, now);
                        for (String resource : new LinkedHashSet<>(resourcesPerItem.get(i))) {
                            evacuation.insertLease(incident.id(), task.id(), resource, now);
                            leased.add(resource);
                        }
                        views.add(taskViewMapper.toTaskView(
                                tasks.findByKey(incident.id(), task.taskKey()).orElseThrow()));
                    }
                    return new DispatchView(incident.incidentKey(), views,
                            List.copyOf(leased));
                });
    }

    /**
     * 撤离登记：仅当前指挥人；只允许进行中且命中有效疏散区域的任务（422）；
     * 登记后任务转 EVACUATED 终态，不可完成。持有有效豁免的进行中任务可不登记，
     * 继续作业并完成。
     */
    @Transactional
    public TaskView evacuate(String incidentKey, String taskKey, String actor,
                             TaskActionRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        Incident incident = lockIncident(incidentKey);
        return idempotent.run(commandKey, "task_evacuate",
                IdempotentExecutor.hash(incidentKey, taskKey, actor),
                TaskView.class, () -> {
                    requireCommander(incident, actor);
                    refresh(incident);
                    IncidentTask task = tasks.findByKey(incident.id(), taskKey)
                            .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskKey));
                    switch (task.status()) {
                        case IN_PROGRESS -> {
                        }
                        case OPEN, EVACUATION_BLOCKED -> throw ApiException.unprocessable(
                                "仅进行中任务可登记撤离，当前状态: " + task.status(),
                                List.of(taskKey));
                        default -> throw ApiException.conflict(
                                "任务已处于终态 " + task.status() + "，不能登记撤离");
                    }
                    if (!hitsEffectiveZone(incident.id(), task.workGrids())) {
                        throw ApiException.unprocessable("任务未命中有效疏散区域，不能登记撤离",
                                List.of(taskKey));
                    }
                    tasks.markEvacuated(task.id(), actor, now());
                    return taskViewMapper.toTaskView(
                            tasks.findByKey(incident.id(), taskKey).orElseThrow());
                });
    }

    // ---------- 供 IncidentService 复用的门禁与区域效果 ----------

    /**
     * 惰性物化区域效果（调用方须已锁定事件行并处于写事务内）：
     * 生效中的当前区域阻断未开始命中任务（固化区域快照，持有有效豁免者除外）；
     * 已结束或被取代区域的阻断解除；无其他阻断的 EVACUATION_BLOCKED 任务恢复 OPEN。
     */
    public void refresh(Incident incident) {
        Instant now = now();
        for (EvacuationZone zone : evacuation.listZonesByIncident(incident.id())) {
            boolean current = zone.supersededAt() == null;
            if (current && zone.effectiveAt(now)) {
                activateBlocks(incident, zone, now);
            } else if (!current || zone.endedAt(now)) {
                evacuation.releaseBlocksByZone(zone.id(), now);
            }
        }
        for (IncidentTask task : tasks.listEvacuationBlockedByIncident(incident.id())) {
            if (!evacuation.hasAnyActiveBlock(task.id())) {
                tasks.restoreOpen(task.id(), now);
            }
        }
    }

    /**
     * 创建高危任务门禁：作业网格命中有效疏散区域时须持有该区域版本豁免（422）。
     */
    public void requireHighRiskCreateGate(Incident incident, String taskKey, boolean highRisk,
                                          List<String> workGrids) {
        List<String> missing = missingExemptions(incident.id(), taskKey, workGrids, highRisk);
        if (!missing.isEmpty()) {
            throw ApiException.unprocessable(
                    "高危任务作业网格命中有效疏散区域，缺少区域版本撤离豁免", List.copyOf(missing));
        }
    }

    private void activateBlocks(Incident incident, EvacuationZone zone, Instant now) {
        for (IncidentTask task : tasks.listOpenByIncident(incident.id())) {
            if (!Grids.intersects(task.workGrids(), zone.grids())) {
                continue;
            }
            if (evacuation.hasActiveBlock(task.id(), zone.id())) {
                continue;
            }
            if (hasValidExemption(incident.id(), task.taskKey(), zone)) {
                continue;
            }
            evacuation.insertBlock(new TaskZoneBlock(0L, incident.id(), task.id(), zone.id(),
                    zone.zoneKey(), zone.version(), zone.grids(), zone.effectiveFrom(),
                    zone.effectiveTo(), zone.riskLevel(), now, null));
            tasks.markEvacuationBlocked(task.id(), now);
        }
    }

    private void requireExemptionsIfHit(Incident incident, IncidentTask task) {
        List<String> missing = missingExemptions(incident.id(), task.taskKey(), task.workGrids(),
                task.highRisk());
        if (!missing.isEmpty()) {
            throw ApiException.unprocessable(
                    "高危任务作业网格命中有效疏散区域，缺少区域版本撤离豁免", List.copyOf(missing));
        }
    }

    /**
     * 高危任务缺失的豁免列表：作业网格与当前有效区域相交且未持有该区域版本豁免。
     * 非高危任务或无作业网格时恒为空。
     */
    private List<String> missingExemptions(long incidentId, String taskKey, List<String> workGrids,
                                           boolean highRisk) {
        if (!highRisk || workGrids.isEmpty()) {
            return List.of();
        }
        Instant now = now();
        List<String> missing = new ArrayList<>();
        for (EvacuationZone zone : evacuation.listCurrentZonesByIncident(incidentId)) {
            if (!zone.effectiveAt(now) || !Grids.intersects(workGrids, zone.grids())) {
                continue;
            }
            if (!hasValidExemption(incidentId, taskKey, zone)) {
                missing.add(zone.zoneKey() + "@v" + zone.version());
            }
        }
        return missing;
    }

    private boolean hasValidExemption(long incidentId, String taskKey, EvacuationZone zone) {
        return evacuation.findExemption(incidentId, taskKey, zone.id(), zone.version())
                .isPresent();
    }

    private boolean hitsEffectiveZone(long incidentId, List<String> workGrids) {
        if (workGrids.isEmpty()) {
            return false;
        }
        Instant now = now();
        return evacuation.listCurrentZonesByIncident(incidentId).stream()
                .filter(z -> z.effectiveAt(now))
                .anyMatch(z -> Grids.intersects(workGrids, z.grids()));
    }

    // ---------- 内部辅助 ----------

    /**
     * zoneKey 指纹：事件键+事件版本+谱系键+规范化网格+窗口+等级+操作者+谱系版本号。
     */
    private static String zoneFingerprint(Incident incident, String groupKey, List<String> grids,
                                          Instant from, Instant to, String level, String operator,
                                          int version) {
        String hash = IdempotentExecutor.hash(incident.incidentKey(),
                String.valueOf(incident.version()), groupKey, Grids.canonical(grids),
                from.toString(), to.toString(), level, operator, String.valueOf(version));
        return "Z-" + hash.substring(0, 24);
    }

    /**
     * 同事件同等级的当前区域（可排除指定谱系）窗口网格不可重叠。
     */
    private void checkOverlap(long incidentId, String excludeGroupKey, String level,
                              List<String> grids, Instant from, Instant to) {
        for (EvacuationZone zone : evacuation.listCurrentZonesByIncident(incidentId)) {
            if (excludeGroupKey != null && zone.groupKey().equals(excludeGroupKey)) {
                continue;
            }
            if (!zone.riskLevel().equals(level)) {
                continue;
            }
            if (!Grids.intersects(zone.grids(), grids)) {
                continue;
            }
            if (zone.effectiveFrom().isBefore(to) && from.isBefore(zone.effectiveTo())) {
                throw ApiException.conflict("同事件同等级区域的窗口网格不可重叠，与区域 "
                        + zone.zoneKey() + " 冲突");
            }
        }
    }

    private EvacuationZone findZoneOfIncident(Incident incident, String zoneKey) {
        EvacuationZone zone = evacuation.findZoneByKey(zoneKey)
                .orElseThrow(() -> ApiException.notFound("区域不存在: " + zoneKey));
        if (zone.incidentId() != incident.id()) {
            throw ApiException.notFound("区域不属于事件 " + incident.incidentKey() + ": " + zoneKey);
        }
        return zone;
    }

    private Incident lockIncident(String incidentKey) {
        requireText(incidentKey, "incidentKey");
        return incidents.lockByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
    }

    private static void requireIncidentOpen(Incident incident) {
        if (incident.status() == IncidentStatus.RESOLVED
                || incident.status() == IncidentStatus.CLOSED) {
            throw ApiException.illegalTransition(
                    "事件已" + incident.status() + "，不能登记或修订疏散区域/豁免");
        }
    }

    private static void requireCommander(Incident incident, String actor) {
        if (incident.commander() == null || !incident.commander().equals(actor)) {
            throw ApiException.conflict("只有当前指挥人 "
                    + (incident.commander() == null ? "(无)" : incident.commander()) + " 能执行该操作");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(field + " 不能为空");
        }
        return value.strip();
    }

    private static Instant requireWindowStart(Instant from) {
        if (from == null) {
            throw ApiException.badRequest("effectiveFrom 不能为空");
        }
        return from.truncatedTo(ChronoUnit.MICROS);
    }

    private static Instant requireWindowEnd(Instant to, Instant from) {
        if (to == null) {
            throw ApiException.badRequest("effectiveTo 不能为空");
        }
        Instant truncated = to.truncatedTo(ChronoUnit.MICROS);
        if (!from.isBefore(truncated)) {
            throw ApiException.badRequest("effectiveFrom 必须早于 effectiveTo（左闭右开窗口）");
        }
        return truncated;
    }

    private static String requireRiskLevel(String riskLevel) {
        String level = requireText(riskLevel, "riskLevel")
                .toUpperCase(java.util.Locale.ROOT);
        if (!RISK_LEVELS.contains(level)) {
            throw ApiException.badRequest("riskLevel 必须为 LOW/MEDIUM/HIGH");
        }
        return level;
    }

    private ZoneView toZoneView(EvacuationZone zone, Instant now) {
        return new ZoneView(zone.zoneKey(), zone.groupKey(), zone.version(), zone.grids(),
                zone.effectiveFrom(), zone.effectiveTo(), zone.riskLevel(),
                zone.supersededAt() == null, zone.effectiveAt(now), zone.operator(),
                zone.createdAt());
    }

    private static ExemptionView toExemptionView(ZoneExemption exemption, boolean valid) {
        return new ExemptionView(exemption.taskKey(), exemption.zoneKey(), exemption.zoneVersion(),
                exemption.reason(), exemption.grantedBy(), exemption.createdAt(), valid);
    }
}
