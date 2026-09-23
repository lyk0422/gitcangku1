package com.example.starter.incident;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.example.starter.incident.ResourceLeaseRepository.LeaseDetail;
import com.example.starter.incident.dto.Requests.LeaseRequest;
import com.example.starter.incident.dto.Requests.PreemptRequest;
import com.example.starter.incident.dto.Requests.ResourceCreateRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.VictimRef;
import com.example.starter.incident.dto.Responses.LeaseHistoryView;
import com.example.starter.incident.dto.Responses.LeaseView;
import com.example.starter.incident.dto.Responses.PreemptionClosureView;
import com.example.starter.incident.dto.Responses.PreemptionView;
import com.example.starter.incident.dto.Responses.ResourceView;
import com.example.starter.incident.dto.Responses.TaskBlockerView;
import com.example.starter.incident.dto.Responses.TaskRefView;
import com.example.starter.incident.dto.Responses.TaskView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 跨事件共享资源租约与依赖感知抢占服务。
 * 并发约定：租约申请/抢占/任务启动在任何其他锁之前先持有资源池全局锁
 * （抢占再按 事件行锁 → 依赖图锁 的顺序加锁），容量核算与租约状态变更
 * 在同事务内完成，保证容量永不超限且任务不带失效租约启动。
 * 幂等约定：requestId 全局唯一，同键同参（受害集合换序等价）重放首次响应，
 * 同键改参 409，失败不占键；leaseKey 全局唯一。
 */
@Service
public class ResourceService {

    /** 视为阻塞已解除的目标事件状态（与 IncidentService 一致）。 */
    private static final Set<IncidentStatus> UNBLOCKING_STATUSES = EnumSet.of(
            IncidentStatus.CONTAINED, IncidentStatus.RESOLVED, IncidentStatus.CLOSED);

    private final SharedResourceRepository resources;
    private final ResourceLeaseRepository leases;
    private final IncidentRepository incidents;
    private final IncidentTaskRepository tasks;
    private final CommandIdempotency idempotency;
    private final Clock clock;

    public ResourceService(SharedResourceRepository resources, ResourceLeaseRepository leases,
                           IncidentRepository incidents, IncidentTaskRepository tasks,
                           CommandIdempotency idempotency, Clock clock) {
        this.resources = resources;
        this.leases = leases;
        this.incidents = incidents;
        this.tasks = tasks;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    private Instant now() {
        return clock.instant();
    }

    /**
     * 创建共享资源：capacity 必须为正整数；resourceKey 重复返回 409。
     */
    @Transactional
    public ResourceView createResource(String actor, ResourceCreateRequest req) {
        String resourceKey = requireText(req.resourceKey(), "resourceKey");
        String createdBy = requireText(actor, "X-Actor-Id");
        if (req.capacity() == null || req.capacity() < 1) {
            throw ApiException.badRequest("capacity 必须为正整数");
        }
        if (resources.findByKey(resourceKey).isPresent()) {
            throw ApiException.conflict("resourceKey 已存在: " + resourceKey);
        }
        Instant now = now();
        try {
            resources.insert(new SharedResource(0L, resourceKey, req.capacity(), createdBy,
                    now, now));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("resourceKey 已存在: " + resourceKey);
        }
        return getResource(resourceKey);
    }

    /**
     * 查询资源占用：容量、ACTIVE 单位合计、剩余单位及 ACTIVE 租约列表。只读。
     */
    @Transactional(readOnly = true)
    public ResourceView getResource(String resourceKey) {
        SharedResource resource = resources.findByKey(resourceKey)
                .orElseThrow(() -> ApiException.notFound("资源不存在: " + resourceKey));
        List<LeaseView> active = leases.listDetailsByResource(resource.id()).stream()
                .filter(d -> d.lease().status() == LeaseStatus.ACTIVE)
                .map(ResourceService::toLeaseView)
                .toList();
        int activeUnits = active.stream().mapToInt(LeaseView::units).sum();
        return new ResourceView(resource.resourceKey(), resource.capacity(), activeUnits,
                resource.capacity() - activeUnits, active);
    }

    /**
     * 查询资源全部租约历史（含 RELEASED/REVOKED），按创建顺序返回。只读。
     */
    @Transactional(readOnly = true)
    public LeaseHistoryView listLeases(String resourceKey) {
        SharedResource resource = resources.findByKey(resourceKey)
                .orElseThrow(() -> ApiException.notFound("资源不存在: " + resourceKey));
        List<LeaseView> views = leases.listDetailsByResource(resource.id()).stream()
                .map(ResourceService::toLeaseView)
                .toList();
        return new LeaseHistoryView(resource.resourceKey(), views);
    }

    /**
     * 抢占闭包查询（只读）：给定拟撤销的受害租约，计算反向依赖闭包——
     * 闭包中 OPEN 任务在本资源上的 ACTIVE 租约（未列出部分）及已 STARTED 的任务。
     */
    @Transactional(readOnly = true)
    public PreemptionClosureView preemptionClosure(String resourceKey, List<String> leaseKeys) {
        SharedResource resource = resources.findByKey(resourceKey)
                .orElseThrow(() -> ApiException.notFound("资源不存在: " + resourceKey));
        if (leaseKeys == null || leaseKeys.isEmpty()) {
            throw ApiException.badRequest("leases 不能为空");
        }
        List<ResourceLease> listed = new ArrayList<>();
        for (String leaseKey : leaseKeys) {
            ResourceLease lease = leases.findByKey(requireText(leaseKey, "leaseKey"))
                    .orElseThrow(() -> ApiException.notFound("租约不存在: " + leaseKey));
            if (lease.resourceId() != resource.id()) {
                throw ApiException.notFound("租约不属于资源 " + resourceKey + ": " + leaseKey);
            }
            listed.add(lease);
        }
        List<IncidentTask> victimTasks = listed.stream()
                .map(l -> tasks.findById(l.taskId()).orElseThrow())
                .toList();
        List<IncidentTask> dependents = reverseDependentTasks(victimTasks);
        Map<Long, String> incidentKeys = incidentKeysOf(dependents);
        List<TaskRefView> startedTasks = dependents.stream()
                .filter(t -> t.status() == TaskStatus.STARTED)
                .map(t -> new TaskRefView(incidentKeys.get(t.incidentId()), t.taskKey()))
                .toList();
        Set<String> listedKeys = listed.stream().map(ResourceLease::leaseKey)
                .collect(Collectors.toSet());
        Map<Long, ResourceLease> activeByTask = activeLeaseByTask(resource.id());
        List<Long> requiredIds = dependents.stream()
                .filter(t -> t.status() == TaskStatus.OPEN)
                .map(t -> activeByTask.get(t.id()))
                .filter(Objects::nonNull)
                .filter(l -> !listedKeys.contains(l.leaseKey()))
                .map(ResourceLease::id)
                .toList();
        List<LeaseView> required = leases.listDetailsByIds(requiredIds).stream()
                .map(ResourceService::toLeaseView)
                .toList();
        List<LeaseView> listedViews = leases.listDetailsByIds(
                        listed.stream().map(ResourceLease::id).toList()).stream()
                .map(ResourceService::toLeaseView)
                .toList();
        return new PreemptionClosureView(resource.resourceKey(), listedViews, required,
                startedTasks);
    }

    /**
     * 申请租约：仅当前指挥人；任务须为 OPEN 且版本一致；units 取 1~容量；
     * 同任务同资源至多一条 ACTIVE 租约；容量不足 409。
     * leaseKey 幂等：同键同内容返回既有租约，同键不同内容 409。
     */
    @Transactional
    public LeaseView requestLease(String incidentKey, String taskKey, String actor,
                                  LeaseRequest req) {
        String requestId = requireText(req.requestId(), "requestId");
        String resourceKey = requireText(req.resourceKey(), "resourceKey");
        String leaseKey = requireText(req.leaseKey(), "leaseKey");
        if (req.units() == null) {
            throw ApiException.badRequest("units 不能为空");
        }
        if (req.taskVersion() == null) {
            throw ApiException.badRequest("taskVersion 不能为空");
        }
        resources.lockPool();
        Incident incident = lockIncident(incidentKey);
        return idempotency.run(requestId, "lease_request",
                CommandIdempotency.hash(incidentKey, taskKey, actor, resourceKey, leaseKey,
                        String.valueOf(req.units()), String.valueOf(req.taskVersion())),
                LeaseView.class, () -> {
                    requireCommander(incident, actor);
                    IncidentTask task = tasks.findByKey(incident.id(), taskKey)
                            .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskKey));
                    if (task.status() != TaskStatus.OPEN) {
                        throw ApiException.conflict("仅 OPEN 且未开始的任务可申请租约，当前状态: "
                                + task.status());
                    }
                    if (task.version() != req.taskVersion()) {
                        throw ApiException.conflict("任务版本已变化，请刷新后重试: 当前版本 "
                                + task.version());
                    }
                    SharedResource resource = resources.findByKey(resourceKey)
                            .orElseThrow(() -> ApiException.notFound("资源不存在: " + resourceKey));
                    int units = req.units();
                    if (units < 1 || units > resource.capacity()) {
                        throw ApiException.badRequest("units 必须为 1~" + resource.capacity());
                    }
                    var existing = leases.findByKey(leaseKey);
                    if (existing.isPresent()) {
                        ResourceLease found = existing.get();
                        if (!found.sameContent(resource.id(), task.id(), units)) {
                            throw ApiException.conflict("leaseKey 已被不同内容使用: " + leaseKey);
                        }
                        return toLeaseView(found, resource.resourceKey(), incident.incidentKey(),
                                task.taskKey());
                    }
                    if (leases.findActiveByTaskAndResource(task.id(), resource.id()).isPresent()) {
                        throw ApiException.conflict("同任务同资源至多一条 ACTIVE 租约");
                    }
                    int activeUnits = leases.sumActiveUnits(resource.id());
                    if (activeUnits + units > resource.capacity()) {
                        throw ApiException.conflict("资源容量不足: 剩余 "
                                + (resource.capacity() - activeUnits) + " 单位，申请 " + units
                                + " 单位");
                    }
                    Instant now = now();
                    try {
                        leases.insert(new ResourceLease(0L, leaseKey, resource.id(), incident.id(),
                                task.id(), units, LeaseStatus.ACTIVE, 1L, requestId, actor,
                                now, now, null, null));
                    } catch (DuplicateKeyException e) {
                        throw ApiException.conflict("leaseKey 已存在: " + leaseKey);
                    }
                    return toLeaseView(leases.findByKey(leaseKey).orElseThrow(),
                            resource.resourceKey(), incident.incidentKey(), task.taskKey());
                });
    }

    /**
     * 标记任务 STARTED：仅当前指挥人；仅 OPEN 可启动；
     * 任务须持有 ACTIVE 租约（资源池全局锁内校验，不带失效租约启动）。
     */
    @Transactional
    public TaskView startTask(String incidentKey, String taskKey, String actor,
                              TaskActionRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        resources.lockPool();
        Incident incident = lockIncident(incidentKey);
        return idempotency.run(commandKey, "task_start",
                CommandIdempotency.hash(incidentKey, taskKey, actor), TaskView.class, () -> {
                    requireCommander(incident, actor);
                    IncidentTask task = tasks.findByKey(incident.id(), taskKey)
                            .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskKey));
                    if (task.status() != TaskStatus.OPEN) {
                        throw ApiException.conflict("任务当前状态为 " + task.status()
                                + "，不能标记 STARTED");
                    }
                    if (leases.listActiveByTask(task.id()).isEmpty()) {
                        throw ApiException.conflict("任务没有 ACTIVE 租约，不能标记 STARTED");
                    }
                    tasks.markStarted(task.id(), actor, now());
                    return toTaskView(tasks.findByKey(incident.id(), taskKey).orElseThrow());
                });
    }

    /**
     * 提交抢占计划：仅当前指挥人；请求事件严重级别须严格高于全部受害租约所属事件；
     * 受害任务不得 STARTED；反向依赖闭包中 OPEN 任务的本资源 ACTIVE 租约必须全部列出，
     * 否则 422；闭包中存在 STARTED 任务时 409。成功后同事务原子 REVOKE 全部受害租约
     * 并授予新租约，任一版本/优先级/容量/闭包校验失败整单回滚。
     */
    @Transactional
    public PreemptionView preempt(String incidentKey, String taskKey, String actor,
                                  PreemptRequest req) {
        String requestId = requireText(req.requestId(), "requestId");
        String resourceKey = requireText(req.resourceKey(), "resourceKey");
        String leaseKey = requireText(req.leaseKey(), "leaseKey");
        if (req.units() == null) {
            throw ApiException.badRequest("units 不能为空");
        }
        if (req.taskVersion() == null) {
            throw ApiException.badRequest("taskVersion 不能为空");
        }
        List<VictimRef> victims = req.victims() == null ? List.of() : req.victims();
        if (victims.isEmpty()) {
            throw ApiException.badRequest("victims 不能为空");
        }
        List<String> victimKeys = new ArrayList<>();
        for (VictimRef victim : victims) {
            victimKeys.add(requireText(victim.leaseKey(), "victims.leaseKey"));
            if (victim.version() == null) {
                throw ApiException.badRequest("victims.version 不能为空");
            }
        }
        if (new HashSet<>(victimKeys).size() != victimKeys.size()) {
            throw ApiException.badRequest("victims 存在重复 leaseKey");
        }
        resources.lockPool();
        Incident incident = lockIncident(incidentKey);
        // 依赖图锁：串行化闭包计算与并发依赖变化
        tasks.lockGraph();
        String victimHash = victims.stream()
                .map(v -> v.leaseKey().strip() + ":" + v.version())
                .sorted()
                .collect(Collectors.joining(","));
        return idempotency.run(requestId, "lease_preempt",
                CommandIdempotency.hash(incidentKey, taskKey, actor, resourceKey, leaseKey,
                        String.valueOf(req.units()), String.valueOf(req.taskVersion()),
                        victimHash),
                PreemptionView.class, () -> doPreempt(incident, taskKey, actor, requestId,
                        resourceKey, leaseKey, req.units(), req.taskVersion(), victims));
    }

    private PreemptionView doPreempt(Incident incident, String taskKey, String actor,
                                     String requestId, String resourceKey, String leaseKey,
                                     int units, long taskVersion, List<VictimRef> victims) {
        requireCommander(incident, actor);
        IncidentTask task = tasks.findByKey(incident.id(), taskKey)
                .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskKey));
        if (task.status() != TaskStatus.OPEN) {
            throw ApiException.conflict("仅 OPEN 且未开始的任务可提交抢占计划，当前状态: "
                    + task.status());
        }
        if (task.version() != taskVersion) {
            throw ApiException.conflict("任务版本已变化，请刷新后重试: 当前版本 " + task.version());
        }
        SharedResource resource = resources.findByKey(resourceKey)
                .orElseThrow(() -> ApiException.notFound("资源不存在: " + resourceKey));
        if (units < 1 || units > resource.capacity()) {
            throw ApiException.badRequest("units 必须为 1~" + resource.capacity());
        }
        if (leases.findActiveByTaskAndResource(task.id(), resource.id()).isPresent()) {
            throw ApiException.conflict("同任务同资源至多一条 ACTIVE 租约");
        }
        if (leases.findByKey(leaseKey).isPresent()) {
            throw ApiException.conflict("leaseKey 已存在: " + leaseKey);
        }
        int requesterRank = severityRank(incident.severity());
        // 受害租约：存在、属于本资源、ACTIVE、版本一致；受害任务未 STARTED；优先级严格更低
        List<ResourceLease> victimLeases = new ArrayList<>();
        List<IncidentTask> victimTasks = new ArrayList<>();
        for (VictimRef ref : victims) {
            String victimKey = ref.leaseKey().strip();
            ResourceLease victim = leases.findByKey(victimKey)
                    .orElseThrow(() -> ApiException.notFound("受害租约不存在: " + victimKey));
            if (victim.resourceId() != resource.id()) {
                throw ApiException.conflict("受害租约不属于资源 " + resourceKey + ": " + victimKey);
            }
            if (victim.status() != LeaseStatus.ACTIVE) {
                throw ApiException.conflict("受害租约已非 ACTIVE: " + victimKey);
            }
            if (victim.version() != ref.version()) {
                throw ApiException.conflict("受害租约版本已变化: " + victimKey + "，当前版本 "
                        + victim.version());
            }
            IncidentTask victimTask = tasks.findById(victim.taskId())
                    .orElseThrow(() -> ApiException.notFound("受害任务不存在: " + victim.taskId()));
            if (victimTask.status() == TaskStatus.STARTED) {
                throw ApiException.conflict("受害任务已 STARTED，不得抢占: "
                        + victimTask.taskKey());
            }
            Incident victimIncident = incidents.findById(victim.incidentId())
                    .orElseThrow(() -> ApiException.notFound("受害事件不存在: "
                            + victim.incidentId()));
            if (severityRank(victimIncident.severity()) <= requesterRank) {
                throw ApiException.conflict("只能抢占严重级别更低的事件: "
                        + victimIncident.incidentKey() + " 为 " + victimIncident.severity());
            }
            victimLeases.add(victim);
            victimTasks.add(victimTask);
        }
        // 容量：抢占须必要（当前容量不足）
        int activeUnits = leases.sumActiveUnits(resource.id());
        if (activeUnits + units <= resource.capacity()) {
            throw ApiException.conflict("当前容量充足，无需抢占，请直接申请租约");
        }
        // 反向依赖闭包：STARTED 链不得抢占；未列出的 OPEN 依赖任务租约必须包含，否则 422
        List<IncidentTask> dependents = reverseDependentTasks(victimTasks);
        Map<Long, String> incidentKeys = incidentKeysOf(dependents);
        List<TaskRefView> startedBlocked = dependents.stream()
                .filter(t -> t.status() == TaskStatus.STARTED)
                .map(t -> new TaskRefView(incidentKeys.get(t.incidentId()), t.taskKey()))
                .toList();
        if (!startedBlocked.isEmpty()) {
            throw ApiException.conflict("依赖链上存在已 STARTED 任务，不得抢占", startedBlocked);
        }
        Set<String> listedKeys = victimLeases.stream().map(ResourceLease::leaseKey)
                .collect(Collectors.toSet());
        Map<Long, ResourceLease> activeByTask = activeLeaseByTask(resource.id());
        List<String> missingKeys = dependents.stream()
                .filter(t -> t.status() == TaskStatus.OPEN)
                .map(t -> activeByTask.get(t.id()))
                .filter(Objects::nonNull)
                .map(ResourceLease::leaseKey)
                .filter(k -> !listedKeys.contains(k))
                .sorted()
                .toList();
        if (!missingKeys.isEmpty()) {
            throw ApiException.unprocessable(
                    "抢占计划未包含依赖闭包要求的租约: " + String.join(",", missingKeys),
                    List.copyOf(missingKeys));
        }
        // 容量：撤销所列受害租约后须足够
        int freed = victimLeases.stream().mapToInt(ResourceLease::units).sum();
        if (activeUnits - freed + units > resource.capacity()) {
            throw ApiException.conflict("撤销所列受害租约后容量仍不足: 释放 " + freed
                    + " 单位，仍需 " + (activeUnits + units - resource.capacity()) + " 单位");
        }
        // 执行：原子 REVOKE 全部受害租约并授予新租约
        Instant now = now();
        for (ResourceLease victim : victimLeases) {
            int updated = leases.revoke(victim.id(), now);
            if (updated == 0) {
                throw ApiException.conflict("受害租约已被并发处理: " + victim.leaseKey());
            }
        }
        try {
            leases.insert(new ResourceLease(0L, leaseKey, resource.id(), incident.id(),
                    task.id(), units, LeaseStatus.ACTIVE, 1L, requestId, actor, now, now,
                    null, null));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("leaseKey 已存在: " + leaseKey);
        }
        LeaseView granted = toLeaseView(leases.findByKey(leaseKey).orElseThrow(),
                resource.resourceKey(), incident.incidentKey(), task.taskKey());
        List<LeaseView> revoked = leases.listDetailsByIds(
                        victimLeases.stream().map(ResourceLease::id).toList()).stream()
                .map(ResourceService::toLeaseView)
                .toList();
        return new PreemptionView(granted, revoked);
    }

    /**
     * 任务级反向依赖闭包：从受害任务出发，找出全部直接/间接依赖受害任务的
     * OPEN/STARTED 任务（不含受害任务自身）。调用方须已持有依赖图锁（写路径）
     * 或处于只读事务（查询路径）。
     */
    private List<IncidentTask> reverseDependentTasks(Collection<IncidentTask> victimTasks) {
        List<IncidentTask> activeTasks = tasks.listActiveAll();
        Map<Long, Long> incidentByTask = new HashMap<>();
        for (IncidentTask t : activeTasks) {
            incidentByTask.put(t.id(), t.incidentId());
        }
        for (IncidentTask t : victimTasks) {
            incidentByTask.put(t.id(), t.incidentId());
        }
        Map<Long, List<Long>> blockersByTask = new HashMap<>();
        for (IncidentTaskRepository.TaskBlockerEdge edge : tasks.listActiveBlockerEdges()) {
            blockersByTask.computeIfAbsent(edge.taskId(), k -> new ArrayList<>())
                    .add(edge.blockerIncidentId());
        }
        Set<Long> victimIds = victimTasks.stream().map(IncidentTask::id)
                .collect(Collectors.toSet());
        Set<Long> inClosure = new HashSet<>(victimIds);
        boolean changed = true;
        while (changed) {
            changed = false;
            Set<Long> closureIncidents = inClosure.stream()
                    .map(incidentByTask::get).collect(Collectors.toSet());
            for (IncidentTask candidate : activeTasks) {
                if (inClosure.contains(candidate.id())) {
                    continue;
                }
                List<Long> blockers = blockersByTask.getOrDefault(candidate.id(), List.of());
                if (blockers.stream().anyMatch(closureIncidents::contains)) {
                    inClosure.add(candidate.id());
                    changed = true;
                }
            }
        }
        return activeTasks.stream()
                .filter(t -> inClosure.contains(t.id()) && !victimIds.contains(t.id()))
                .toList();
    }

    /**
     * 资源上 ACTIVE 租约按任务 id 索引（同任务同资源至多一条）。
     */
    private Map<Long, ResourceLease> activeLeaseByTask(long resourceId) {
        Map<Long, ResourceLease> byTask = new HashMap<>();
        for (ResourceLease lease : leases.listActiveByResource(resourceId)) {
            byTask.put(lease.taskId(), lease);
        }
        return byTask;
    }

    private Map<Long, String> incidentKeysOf(List<IncidentTask> taskList) {
        Map<Long, String> keys = new HashMap<>();
        for (IncidentTask t : taskList) {
            keys.computeIfAbsent(t.incidentId(), id -> incidents.findById(id)
                    .orElseThrow(() -> ApiException.notFound("事件不存在: " + id))
                    .incidentKey());
        }
        return keys;
    }

    /**
     * 严重级别数值：S1 最高（1），S4 最低（4）；数值更小优先级更高。
     */
    private static int severityRank(String severity) {
        return Integer.parseInt(severity.substring(1));
    }

    private Incident lockIncident(String incidentKey) {
        requireText(incidentKey, "incidentKey");
        return incidents.lockByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
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

    private static LeaseView toLeaseView(LeaseDetail detail) {
        return toLeaseView(detail.lease(), detail.resourceKey(), detail.incidentKey(),
                detail.taskKey());
    }

    private static LeaseView toLeaseView(ResourceLease lease, String resourceKey,
                                         String incidentKey, String taskKey) {
        return new LeaseView(lease.leaseKey(), resourceKey, incidentKey, taskKey,
                lease.units(), lease.status().name(), lease.version(), lease.createdBy(),
                lease.createdAt(), lease.releasedAt(), lease.revokedAt());
    }

    /**
     * 组装任务视图：阻塞状态按目标事件查询时的当前状态计算，不写回依赖任务。
     */
    private TaskView toTaskView(IncidentTask task) {
        List<TaskBlockerView> blockers = incidents.listBlockingIncidents(task.id()).stream()
                .map(b -> new TaskBlockerView(b.incidentKey(), b.status().name(),
                        UNBLOCKING_STATUSES.contains(b.status())))
                .toList();
        return new TaskView(task.taskKey(), task.groupCode(), task.title(), task.status().name(),
                blockers, task.createdBy(), task.createdAt(), task.startedBy(), task.startedAt(),
                task.doneBy(), task.doneAt(), task.cancelledBy(), task.cancelledAt(),
                task.version());
    }
}
