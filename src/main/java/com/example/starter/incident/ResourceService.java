package com.example.starter.incident;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.starter.incident.dto.Requests.LeaseAcquireRequest;
import com.example.starter.incident.dto.Requests.PreemptRequest;
import com.example.starter.incident.dto.Requests.PreemptionClosureRequest;
import com.example.starter.incident.dto.Requests.PreemptionVictimRequest;
import com.example.starter.incident.dto.Requests.ResourceCreateRequest;
import com.example.starter.incident.dto.Requests.TaskStartRequest;
import com.example.starter.incident.dto.Responses.LeaseView;
import com.example.starter.incident.dto.Responses.PreemptionClosureView;
import com.example.starter.incident.dto.Responses.PreemptionView;
import com.example.starter.incident.dto.Responses.ResourceUsageView;
import com.example.starter.incident.dto.Responses.ResourceView;
import com.example.starter.incident.dto.Responses.TaskView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 跨事件共享资源租约与依赖感知抢占服务。
 *
 * <p>并发约定：申请、抢占、任务开始及任务终态均先持有 resource_lock 单行锁，
 * 同事务内完成幂等占位、容量判定、反向依赖闭包与租约/任务写入，按事务提交顺序生效，
 * 容量永不超限；任务开始另用版本条件更新兜底，任务不带失效租约启动。
 *
 * <p>抢占：仅更严重事件（S 数字更小）可抢占；受害任务必须尚未 STARTED；
 * 沿跨事件阻塞反向边求传递闭包，任何未列入计划的 OPEN/STARTED 依赖任务的相关
 * ACTIVE 租约必须一并抢占，否则 422；依赖链上的 STARTED 任务不可抢占（422）。
 * 任一租约版本、任务版本、优先级、容量或闭包在锁定后变化，整单 409 且无任何变更。
 */
@Service
public class ResourceService {

    private static final String SEP = "\u001F";

    private final IncidentRepository incidents;
    private final IncidentTaskRepository tasks;
    private final SharedResourceRepository resources;
    private final IdempotentExecutor idempotency;
    private final Clock clock;

    public ResourceService(IncidentRepository incidents, IncidentTaskRepository tasks,
                           SharedResourceRepository resources, IdempotentExecutor idempotency,
                           Clock clock) {
        this.incidents = incidents;
        this.tasks = tasks;
        this.resources = resources;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    private Instant now() {
        return clock.instant();
    }

    /**
     * 创建共享资源：resourceKey 全局唯一，capacity 必须为正整数。
     */
    @Transactional
    public ResourceView createResource(ResourceCreateRequest req) {
        String resourceKey = requireText(req.resourceKey(), "resourceKey");
        String name = requireText(req.name(), "name");
        Integer capacity = req.capacity();
        if (capacity == null || capacity <= 0) {
            throw ApiException.badRequest("capacity 必须为正整数");
        }
        if (resources.findByKey(resourceKey).isPresent()) {
            throw ApiException.conflict("resourceKey 已存在: " + resourceKey);
        }
        Instant now = now();
        try {
            resources.insert(new SharedResource(0L, resourceKey, name, capacity, now, now));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("resourceKey 已存在: " + resourceKey);
        }
        return toResourceView(resources.findByKey(resourceKey).orElseThrow());
    }

    /**
     * 查询全部共享资源及实时占用（只读）。
     */
    @Transactional(readOnly = true)
    public List<ResourceView> listResources() {
        return resources.listAll().stream().map(this::toResourceView).toList();
    }

    /**
     * 查询单资源占用明细：资源本体 + 当前全部 ACTIVE 租约（只读）。
     */
    @Transactional(readOnly = true)
    public ResourceUsageView resourceUsage(String resourceKey) {
        SharedResource resource = requireResource(resourceKey);
        List<LeaseView> active = resources.listActiveRowsForResource(resource.id()).stream()
                .map(this::toLeaseView).toList();
        return new ResourceUsageView(toResourceView(resource), active);
    }

    /**
     * 查询资源租约历史（全部状态，只读），按租约 id 返回。
     */
    @Transactional(readOnly = true)
    public List<LeaseView> leaseHistory(String resourceKey) {
        SharedResource resource = requireResource(resourceKey);
        return resources.listLeaseRowsForResource(resource.id()).stream()
                .map(this::toLeaseView).toList();
    }

    /**
     * 当前指挥人为 OPEN 且未开始的任务申请 1~容量 单位 ACTIVE 租约。
     * 提交任务版本（初始为 0）与全局唯一 leaseKey；容量不足返回 409。
     */
    @Transactional
    public LeaseView acquireLease(String incidentKey, String taskKey, String actor,
                                  LeaseAcquireRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String resourceKey = requireText(req.resourceKey(), "resourceKey");
        String leaseKey = requireText(req.leaseKey(), "leaseKey");
        Long taskVersion = req.taskVersion();
        Integer quantity = req.quantity();
        if (taskVersion == null || taskVersion < 0) {
            throw ApiException.badRequest("taskVersion 不能为空且必须非负");
        }
        if (quantity == null || quantity <= 0) {
            throw ApiException.badRequest("quantity 必须为 1~容量 的正整数");
        }
        Incident incident = lockIncident(incidentKey);
        return idempotency.run(commandKey, "lease_acquire",
                hash(incidentKey, taskKey, actor, resourceKey, taskVersion, quantity, leaseKey),
                LeaseView.class, () -> {
                    requireCommander(incident, actor);
                    resources.lockResourceDomain();
                    SharedResource resource = resources.lockById(
                            requireResource(resourceKey).id()).orElseThrow();
                    if (quantity > resource.capacity()) {
                        throw ApiException.badRequest(
                                "quantity 不能超过资源容量 " + resource.capacity());
                    }
                    IncidentTask task = requireTask(incident.id(), taskKey);
                    IncidentTask locked = tasks.lockById(task.id()).orElseThrow();
                    if (locked.version() != taskVersion) {
                        throw ApiException.conflict(
                                "任务版本过期：期望 " + taskVersion + "，当前 " + locked.version());
                    }
                    if (locked.status() != TaskStatus.OPEN) {
                        throw ApiException.illegalTransition(
                                "仅 OPEN 且未开始的任务可申请租约，当前状态: " + locked.status());
                    }
                    if (resources.findActive(resource.id(), locked.id()).isPresent()) {
                        throw ApiException.conflict("同任务同资源已存在 ACTIVE 租约");
                    }
                    int used = sumQuantity(resources.listActiveForResource(resource.id()));
                    if (used + quantity > resource.capacity()) {
                        throw ApiException.conflict("资源容量不足：容量 " + resource.capacity()
                                + "，已占用 " + used + "，本次申请 " + quantity);
                    }
                    Instant now = now();
                    ResourceLease lease = new ResourceLease(0L, leaseKey, resource.id(), locked.id(),
                            incident.id(), quantity, LeaseStatus.ACTIVE, 1L, now, null, null,
                            null, null, now, now);
                    try {
                        long id = resources.insertActiveLease(lease);
                        return toLeaseView(resources.lockLeaseByKey(leaseKey).orElseThrow(),
                                resource.resourceKey(), incident.incidentKey(), locked.taskKey());
                    } catch (DuplicateKeyException e) {
                        throw ApiException.conflict("leaseKey 已存在: " + leaseKey);
                    }
                });
    }

    /**
     * 任务开始：OPEN 任务必须提交至少一条据以启动的 ACTIVE 租约键；
     * 在资源域锁内复核全部租约仍 ACTIVE 且属于该任务，任一失效或任务版本过期即 409。
     */
    @Transactional
    public TaskView startTask(String incidentKey, String taskKey, String actor,
                              TaskStartRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        List<String> leaseKeys = req.leaseKeys() == null ? List.of()
                : req.leaseKeys().stream().filter(k -> k != null && !k.isBlank())
                        .map(String::strip).distinct().toList();
        if (leaseKeys.isEmpty()) {
            throw ApiException.badRequest("leaseKeys 至少包含一条 ACTIVE 租约");
        }
        Incident incident = lockIncident(incidentKey);
        return idempotency.run(commandKey, "task_start",
                hash(incidentKey, taskKey, actor, String.join(",", sorted(leaseKeys))),
                TaskView.class, () -> {
                    requireCommander(incident, actor);
                    resources.lockResourceDomain();
                    IncidentTask task = requireTask(incident.id(), taskKey);
                    IncidentTask locked = tasks.lockById(task.id()).orElseThrow();
                    if (locked.status() != TaskStatus.OPEN) {
                        throw ApiException.illegalTransition(
                                "仅 OPEN 任务可开始，当前状态: " + locked.status());
                    }
                    for (String leaseKey : leaseKeys) {
                        ResourceLease lease = resources.findLeaseByKey(leaseKey)
                                .orElseThrow(() -> ApiException.conflict(
                                        "租约不存在: " + leaseKey));
                        if (lease.taskId() != locked.id()
                                || lease.incidentId() != incident.id()
                                || lease.status() != LeaseStatus.ACTIVE) {
                            throw ApiException.conflict(
                                    "租约已失效或不属于该任务，任务不得带失效租约启动: " + leaseKey);
                        }
                    }
                    Instant now = now();
                    int updated = tasks.markStartedIfOpen(locked.id(), locked.version(), actor, now);
                    if (updated == 0) {
                        throw ApiException.conflict("任务已被并发处理，不能开始");
                    }
                    return toTaskView(tasks.lockById(locked.id()).orElseThrow());
                });
    }

    /**
     * 依赖感知抢占：更严重事件为其尚未 STARTED 的 OPEN 任务提交完整受害租约计划。
     * 校验全部通过后原子 REVOKE 全部受害租约并授予新租约；任一条件不满足整单 409/422。
     */
    @Transactional
    public PreemptionView preempt(String incidentKey, String actor, PreemptRequest req) {
        String requestId = requireText(req.requestId(), "requestId");
        String taskKey = requireText(req.taskKey(), "taskKey");
        String newLeaseKey = requireText(req.newLeaseKey(), "newLeaseKey");
        Long taskVersion = req.taskVersion();
        Integer quantity = req.quantity();
        List<PreemptionVictimRequest> victims = req.victims() == null ? List.of() : req.victims();
        if (taskVersion == null || taskVersion < 0) {
            throw ApiException.badRequest("taskVersion 不能为空且必须非负");
        }
        if (quantity == null || quantity <= 0) {
            throw ApiException.badRequest("quantity 必须为 1~容量 的正整数");
        }
        if (victims.isEmpty()) {
            throw ApiException.badRequest("victims 至少包含一条受害租约");
        }
        List<String> victimKeys = new ArrayList<>();
        List<Long> victimVersions = new ArrayList<>();
        for (PreemptionVictimRequest v : victims) {
            if (v == null || v.leaseKey() == null || v.leaseKey().isBlank() || v.version() == null) {
                throw ApiException.badRequest("受害租约必须包含非空 leaseKey 与 version");
            }
            victimKeys.add(v.leaseKey().strip());
            victimVersions.add(v.version());
        }
        if (new HashSet<>(victimKeys).size() != victimKeys.size()) {
            throw ApiException.badRequest("victims 中存在重复 leaseKey");
        }
        Incident incident = lockIncident(incidentKey);
        // requestId 即抢占幂等键；受害集合按 leaseKey 排序规范化，同参集合换序可重放
        String hash = hash(incidentKey, actor, taskKey, taskVersion, newLeaseKey,
                quantity, hashPairs(victimKeys, victimVersions));
        return idempotency.run(requestId, "preempt", hash, PreemptionView.class, () -> {
            requireCommander(incident, actor);
            resources.lockResourceDomain();
            // 同时持有依赖图全局锁：与创建任务（依赖边变化）串行，保证闭包计算后到提交前
            // 不会有新的反向依赖边进入已提交状态。锁序统一为 resource→graph，无死锁环。
            tasks.lockGraph();

            IncidentTask target = requireTask(incident.id(), taskKey);
            IncidentTask lockedTarget = tasks.lockById(target.id()).orElseThrow();
            if (lockedTarget.version() != taskVersion) {
                throw ApiException.conflict(
                        "任务版本过期：期望 " + taskVersion + "，当前 " + lockedTarget.version());
            }
            if (lockedTarget.status() != TaskStatus.OPEN) {
                throw ApiException.illegalTransition(
                        "仅尚未 STARTED 的 OPEN 任务可申请抢占，当前状态: "
                                + lockedTarget.status());
            }

            // 锁定并核对全部计划受害租约（仅锁租约行，业务键非锁读解析，避免与申请路径死锁）
            List<ResourceLease> victimLeases = new ArrayList<>();
            Long resourceId = null;
            int victimQuantity = 0;
            for (int i = 0; i < victimKeys.size(); i++) {
                String victimKey = victimKeys.get(i);
                ResourceLease lease = resources.lockLeaseByKeyForUpdate(victimKey)
                        .orElseThrow(() -> ApiException.conflict("受害租约不存在: " + victimKey));
                if (lease.status() != LeaseStatus.ACTIVE) {
                    throw ApiException.conflict("受害租约已非 ACTIVE: " + lease.leaseKey());
                }
                if (lease.version() != victimVersions.get(i)) {
                    throw ApiException.conflict("受害租约版本过期: " + lease.leaseKey()
                            + "，期望 " + victimVersions.get(i) + "，当前 " + lease.version());
                }
                Incident victimIncident = incidents.findById(lease.incidentId())
                        .orElseThrow(() -> ApiException.conflict("受害租约所属事件不存在"));
                if (!higherSeverity(incident.severity(), victimIncident.severity())) {
                    throw ApiException.conflict("仅严重级别更高的事件可抢占：本事件 "
                            + incident.severity() + "，受害事件 " + victimIncident.severity());
                }
                IncidentTask victimTask = tasks.findById(lease.taskId())
                        .orElseThrow(() -> ApiException.conflict("受害任务不存在"));
                if (victimTask.status() == TaskStatus.STARTED
                        || victimTask.status() == TaskStatus.DONE) {
                    throw ApiException.illegalTransition(
                            "STARTED 依赖链不得抢占: " + victimTask.taskKey());
                }
                if (resourceId == null) {
                    resourceId = lease.resourceId();
                } else if (resourceId != lease.resourceId()) {
                    throw ApiException.badRequest("一次抢占只能针对同一资源的受害租约");
                }
                victimLeases.add(lease);
                victimQuantity += lease.quantity();
            }

            SharedResource resource = resources.lockById(resourceId).orElseThrow();
            if (quantity > resource.capacity()) {
                throw ApiException.badRequest(
                        "quantity 不能超过资源容量 " + resource.capacity());
            }
            if (resources.findActive(resourceId, lockedTarget.id()).isPresent()) {
                throw ApiException.conflict("目标任务在该资源上已存在 ACTIVE 租约");
            }

            // 反向依赖传递闭包：计划必须覆盖全部受影响的依赖任务
            ClosureResult closure = computeClosure(incident.id(), victimLeases, resourceId);
            Set<String> planned = new HashSet<>(victimKeys);
            for (ResourceLease extra : closure.extraRequired()) {
                if (!planned.contains(extra.leaseKey())) {
                    throw ApiException.unprocessable(
                            "抢占闭包不完整：依赖任务 " + closure.taskKeyOf(extra.taskId())
                                    + " 的租约 " + extra.leaseKey() + " 必须同时抢占");
                }
            }

            // 容量复核：撤销后空闲容量须容纳新租约
            int used = sumQuantity(resources.listActiveForResource(resourceId));
            if (used - victimQuantity + quantity > resource.capacity()) {
                throw ApiException.conflict("抢占后资源容量仍不足：容量 " + resource.capacity()
                        + "，撤销释放 " + victimQuantity + "，本次申请 " + quantity);
            }

            // 原子撤销全部受害租约（条件更新，版本不匹配整单失败）
            Instant now = now();
            String reason = "被事件 " + incident.incidentKey() + " 的抢占请求 " + requestId + " 撤销";
            for (int i = 0; i < victimLeases.size(); i++) {
                int revoked = resources.revokeIfVersion(victimLeases.get(i).id(),
                        victimVersions.get(i), reason, requestId, now);
                if (revoked == 0) {
                    throw ApiException.conflict(
                            "受害租约已被并发修改: " + victimLeases.get(i).leaseKey());
                }
            }

            // 授予新租约
            ResourceLease granted = new ResourceLease(0L, newLeaseKey, resourceId, lockedTarget.id(),
                    incident.id(), quantity, LeaseStatus.ACTIVE, 1L, now, null, null, null,
                    requestId, now, now);
            try {
                resources.insertActiveLease(granted);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("newLeaseKey 已存在: " + newLeaseKey);
            }

            List<LeaseView> revokedViews = victimLeases.stream()
                    .map(l -> resources.findLeaseRowByKey(l.leaseKey()).orElseThrow())
                    .map(this::toLeaseView).toList();
            LeaseView grantedView = toLeaseView(
                    resources.findLeaseRowByKey(newLeaseKey).orElseThrow());
            return new PreemptionView(requestId, revokedViews, grantedView);
        });
    }

    /**
     * 只读计算抢占闭包：沿跨事件阻塞反向边，返回计划受害租约之外必须同时抢占的
     * ACTIVE 租约。依赖链上存在 STARTED 任务时抛出 422。
     */
    @Transactional(readOnly = true)
    public PreemptionClosureView preemptionClosure(String incidentKey,
                                                   PreemptionClosureRequest req) {
        List<String> victimKeys = req.victimLeaseKeys() == null ? List.of()
                : req.victimLeaseKeys().stream().filter(k -> k != null && !k.isBlank())
                        .map(String::strip).distinct().toList();
        if (victimKeys.isEmpty()) {
            throw ApiException.badRequest("victimLeaseKeys 至少包含一条受害租约");
        }
        Incident caller = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        List<ResourceLease> victimLeases = new ArrayList<>();
        Long resourceId = null;
        for (String leaseKey : victimKeys) {
            SharedResourceRepository.LeaseRow row = resources.findLeaseRowByKey(leaseKey)
                    .orElseThrow(() -> ApiException.notFound("受害租约不存在: " + leaseKey));
            ResourceLease lease = row.lease();
            if (lease.status() != LeaseStatus.ACTIVE) {
                throw ApiException.conflict("受害租约已非 ACTIVE: " + leaseKey);
            }
            IncidentTask victimTask = tasks.findById(lease.taskId()).orElseThrow();
            if (victimTask.status() == TaskStatus.STARTED) {
                throw ApiException.illegalTransition("STARTED 依赖链不得抢占: " + row.taskKey());
            }
            if (resourceId == null) {
                resourceId = lease.resourceId();
            } else if (resourceId != lease.resourceId()) {
                throw ApiException.badRequest("闭包计算只能针对同一资源的受害租约");
            }
            victimLeases.add(lease);
        }
        ClosureResult closure = computeClosure(caller.id(), victimLeases, resourceId);
        SharedResource resource = resources.findById(resourceId).orElseThrow();
        List<LeaseView> all = new ArrayList<>(victimLeases.stream()
                .map(l -> resources.findLeaseRowByKey(l.leaseKey()).orElseThrow())
                .map(this::toLeaseView).toList());
        for (ResourceLease extra : closure.extraRequired()) {
            SharedResourceRepository.LeaseRow row = resources.findLeaseRowByKey(extra.leaseKey())
                    .orElseThrow();
            all.add(toLeaseView(row));
        }
        return new PreemptionClosureView(resource.resourceKey(), all);
    }

    /**
     * 反向依赖闭包结果：extraRequired 为计划之外必须追加的 ACTIVE 租约。
     */
    private record ClosureResult(List<ResourceLease> extraRequired,
                                 Map<Long, String> taskKeys) {
        String taskKeyOf(long taskId) {
            return taskKeys.getOrDefault(taskId, String.valueOf(taskId));
        }
    }

    /**
     * 沿 incident_task_blockers 反向边（阻塞事件 → 依赖它的任务）自受害任务所属事件
     * 向上求传递闭包。对每个可达事件上尚未终态（OPEN/STARTED）的任务：
     * STARTED 任务持有该资源 ACTIVE 租约则不可抢占（422）；OPEN 任务持有的该资源
     * ACTIVE 租约若不在计划内则必须追加。
     */
    private ClosureResult computeClosure(long callerIncidentId,
                                         List<ResourceLease> victimLeases,
                                         long resourceId) {
        // 事件级反向邻接：blockerIncidentId -> 依赖它的任务
        Map<Long, List<IncidentTask>> dependentsByBlocked = new HashMap<>();
        for (IncidentTask t : tasks.listAll()) {
            for (Incident blocker : incidents.listBlockingIncidents(t.id())) {
                dependentsByBlocked.computeIfAbsent(blocker.id(), k -> new ArrayList<>()).add(t);
            }
        }

        Set<Long> victimTaskIds = new HashSet<>();
        Set<Long> seedIncidentIds = new HashSet<>();
        Map<Long, String> taskKeys = new HashMap<>();
        for (ResourceLease lease : victimLeases) {
            victimTaskIds.add(lease.taskId());
            seedIncidentIds.add(lease.incidentId());
            tasks.findById(lease.taskId()).ifPresent(t -> taskKeys.put(t.id(), t.taskKey()));
        }

        Set<Long> visited = new HashSet<>(seedIncidentIds);
        visited.add(callerIncidentId);
        Deque<Long> queue = new ArrayDeque<>(seedIncidentIds);
        List<ResourceLease> extra = new ArrayList<>();
        Set<String> plannedKeys = new HashSet<>();
        for (ResourceLease lease : victimLeases) {
            plannedKeys.add(lease.leaseKey());
        }
        Set<Long> affectedTaskIds = new HashSet<>(victimTaskIds);

        while (!queue.isEmpty()) {
            long blockedIncident = queue.poll();
            for (IncidentTask dep : dependentsByBlocked.getOrDefault(blockedIncident, List.of())) {
                if (dep.incidentId() == callerIncidentId) {
                    continue;
                }
                if (affectedTaskIds.add(dep.id())) {
                    taskKeys.put(dep.id(), dep.taskKey());
                    if (dep.status() == TaskStatus.STARTED) {
                        // STARTED 依赖链不得抢占：进行中任务直接或间接依赖受害任务即整单 422
                        throw ApiException.unprocessable(
                                "STARTED 依赖链不得抢占: " + dep.taskKey());
                    }
                    if (dep.status() == TaskStatus.OPEN) {
                        for (ResourceLease l : resources.listActiveForTask(dep.id())) {
                            if (l.resourceId() == resourceId
                                    && l.status() == LeaseStatus.ACTIVE
                                    && !plannedKeys.contains(l.leaseKey())) {
                                extra.add(l);
                                plannedKeys.add(l.leaseKey());
                            }
                        }
                    }
                }
                if (visited.add(dep.incidentId())) {
                    queue.add(dep.incidentId());
                }
            }
        }
        extra.sort(Comparator.comparingLong(ResourceLease::id));
        return new ClosureResult(extra, taskKeys);
    }

    private Incident lockIncident(String incidentKey) {
        requireText(incidentKey, "incidentKey");
        return incidents.lockByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
    }

    private SharedResource requireResource(String resourceKey) {
        return resources.findByKey(resourceKey)
                .orElseThrow(() -> ApiException.notFound("资源不存在: " + resourceKey));
    }

    private IncidentTask requireTask(long incidentId, String taskKey) {
        return tasks.findByKey(incidentId, taskKey)
                .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskKey));
    }

    private static void requireCommander(Incident incident, String actor) {
        if (incident.commander() == null || !incident.commander().equals(actor)) {
            throw ApiException.conflict("只有当前指挥人 "
                    + (incident.commander() == null ? "(无)" : incident.commander()) + " 能执行该操作");
        }
    }

    private static int sumQuantity(List<ResourceLease> leases) {
        return leases.stream().mapToInt(ResourceLease::quantity).sum();
    }

    /**
     * 严重级别比较：S 数字越小越严重。current 严格高于 other 时才可抢占。
     */
    static boolean higherSeverity(String current, String other) {
        return severityRank(current) < severityRank(other);
    }

    private static int severityRank(String severity) {
        if (severity == null || !severity.matches("S[1-4]")) {
            throw ApiException.badRequest("未知严重级别: " + severity);
        }
        return severity.charAt(1) - '0';
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(field + " 不能为空");
        }
        return value.strip();
    }

    private static List<String> sorted(List<String> values) {
        List<String> copy = new ArrayList<>(values);
        copy.sort(String::compareTo);
        return copy;
    }

    /**
     * 受害租约集合的规范化摘要：按 leaseKey 排序后连同版本一起哈希，实现同参集合换序重放。
     */
    private static String hashPairs(List<String> keys, List<Long> versions) {
        Map<String, Long> pairs = new LinkedHashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            pairs.put(keys.get(i), versions.get(i));
        }
        List<String> normalized = new ArrayList<>();
        pairs.keySet().stream().sorted().forEach(k -> normalized.add(k + "=" + pairs.get(k)));
        return String.join(",", normalized);
    }

    private static String hash(Object... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<String> strings = new ArrayList<>();
            for (Object p : parts) {
                strings.add(String.valueOf(p));
            }
            byte[] out = digest.digest(String.join(SEP, strings).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private ResourceView toResourceView(SharedResource resource) {
        int used = sumQuantity(resources.listActiveForResource(resource.id()));
        return new ResourceView(resource.resourceKey(), resource.name(), resource.capacity(),
                used, resource.capacity() - used, resource.createdAt());
    }

    private LeaseView toLeaseView(ResourceLease lease, String resourceKey, String incidentKey,
                                  String taskKey) {
        return new LeaseView(lease.leaseKey(), resourceKey, incidentKey, taskKey, lease.quantity(),
                lease.status().name(), lease.version(), lease.grantedAt(), lease.releasedAt(),
                lease.revokedAt(), lease.revokeReason(), lease.requestId(), lease.createdAt());
    }

    private LeaseView toLeaseView(SharedResourceRepository.LeaseRow row) {
        return toLeaseView(row.lease(), row.resourceKey(), row.incidentKey(), row.taskKey());
    }

    private TaskView toTaskView(IncidentTask task) {
        java.util.Set<IncidentStatus> unblocking = java.util.EnumSet.of(
                IncidentStatus.CONTAINED, IncidentStatus.RESOLVED, IncidentStatus.CLOSED);
        List<com.example.starter.incident.dto.Responses.TaskBlockerView> blockers =
                incidents.listBlockingIncidents(task.id()).stream()
                        .map(b -> new com.example.starter.incident.dto.Responses.TaskBlockerView(
                                b.incidentKey(), b.status().name(), unblocking.contains(b.status())))
                        .toList();
        return new TaskView(task.taskKey(), task.groupCode(), task.title(), task.status().name(),
                task.version(), blockers, task.createdBy(), task.createdAt(), task.startedAt(),
                task.doneBy(), task.doneAt(), task.cancelledBy(), task.cancelledAt());
    }
}
