package com.example.starter.incident;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.example.starter.incident.dto.Requests.RenewItem;
import com.example.starter.incident.dto.Requests.ResourceAssignRequest;
import com.example.starter.incident.dto.Requests.ResourceRenewRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Responses.RenewedLeaseView;
import com.example.starter.incident.dto.Responses.ResourceDiagnosticsItem;
import com.example.starter.incident.dto.Responses.ResourceDiagnosticsView;
import com.example.starter.incident.dto.Responses.ResourceEventView;
import com.example.starter.incident.dto.Responses.ResourceLeaseView;
import com.example.starter.incident.dto.Responses.ResourceRenewalView;
import com.example.starter.incident.dto.Responses.TaskBlockerView;
import com.example.starter.incident.dto.Responses.TaskView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 事件资源授权服务：分配、执行门禁（开始/继续/完成经由 ResourceLifecycle）、
 * 到期判定、续期、回收与诊断查询。
 * 并发约定：写接口先 SELECT ... FOR UPDATE 锁定事件行，涉及资源占用判定时
 * 再按资源键排序持有 resource_locks 行锁，所有操作按事务提交顺序裁决，
 * 同一资源并发续期/分配不产生重叠授权。
 * 幂等约定：commandKey 全局唯一，同键同参重放首次响应，同键改参 409，失败不占键。
 * 时间约定：全部 UTC；有效期按左闭右开 [assignedAt, expiresAt) 解释；
 * 到期只在命令或访问时按注入时钟判定，不依赖后台扫描。
 */
@Service
public class ResourceService {

    /** 视为阻塞已解除的目标事件状态（与 IncidentService 一致，用于组装 TaskView）。 */
    private static final Set<IncidentStatus> UNBLOCKING_STATUSES = Set.of(
            IncidentStatus.CONTAINED, IncidentStatus.RESOLVED, IncidentStatus.CLOSED);

    private final IncidentRepository incidents;
    private final IncidentTaskRepository tasks;
    private final ResourceLeaseRepository leases;
    private final ResourceLifecycle lifecycle;
    private final CommandIdempotency idempotency;
    private final Clock clock;

    public ResourceService(IncidentRepository incidents, IncidentTaskRepository tasks,
                           ResourceLeaseRepository leases, ResourceLifecycle lifecycle,
                           CommandIdempotency idempotency, Clock clock) {
        this.incidents = incidents;
        this.tasks = tasks;
        this.leases = leases;
        this.lifecycle = lifecycle;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    private Instant now() {
        return clock.instant();
    }

    /**
     * 资源分配：仅当前指挥人；任务须为 OPEN/IN_PROGRESS 且未持有 ACTIVE 租约；
     * 必须携带 UTC 到期时刻（必须晚于分配时刻，否则 400 返回实际值与要求）与
     * 初始授权版本（>= 1）；资源不得被其他事件的有效租约占用（409）。
     * 分配前先按注入时钟做授权到期门禁：旧授权已到期则固化 AUTHORIZATION_EXPIRED
     * 并 422（禁止再次分配），不占键。成功记录 ASSIGN 证据事件。
     */
    @Transactional(noRollbackFor = AuthorizationExpiredException.class)
    public ResourceLeaseView assign(String incidentKey, String taskKey, String actor,
                                    ResourceAssignRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String resourceKey = requireText(req.resourceKey(), "resourceKey");
        if (req.authorizationVersion() == null || req.authorizationVersion() < 1) {
            throw ApiException.badRequest("authorizationVersion 必须为 >= 1 的整数");
        }
        int version = req.authorizationVersion();
        if (req.expiresAt() == null) {
            throw ApiException.badRequest("expiresAt 不能为空");
        }
        Instant expiresAt = req.expiresAt().truncatedTo(ChronoUnit.MICROS);
        Incident incident = lockIncident(incidentKey);
        IncidentTask task = findTask(incident, taskKey);
        return idempotency.run(commandKey, "resource_assign",
                CommandIdempotency.hash(incidentKey, taskKey, actor, resourceKey,
                        String.valueOf(version), expiresAt.toString()),
                ResourceLeaseView.class,
                () -> {
                    requireCommander(incident, actor);
                    lifecycle.assertAuthorizationValid(task, actor, "assign");
                },
                () -> {
                    if (task.status() == TaskStatus.AUTHORIZATION_EXPIRED) {
                        throw ApiException.conflict("授权已到期的任务禁止再次分配资源");
                    }
                    if (task.status() == TaskStatus.DONE || task.status() == TaskStatus.CANCELLED) {
                        throw ApiException.conflict(
                                "任务已处于终态 " + task.status() + "，不能分配资源");
                    }
                    Instant now = now();
                    if (!expiresAt.isAfter(now)) {
                        throw ApiException.badRequest("到期时刻必须晚于分配时刻: expiresAt="
                                + expiresAt + ", assignedAt=" + now);
                    }
                    if (leases.findActiveByTask(task.id()).isPresent()) {
                        throw ApiException.conflict("任务已持有生效中的资源授权");
                    }
                    leases.lockResource(resourceKey, now);
                    List<ResourceLease> occupying = leases.listOccupying(resourceKey, now)
                            .stream()
                            .filter(l -> l.incidentId() != incident.id())
                            .toList();
                    if (!occupying.isEmpty()) {
                        throw ApiException.conflict("资源仍被其他事件占用: " + resourceKey,
                                occupyingDetails(occupying));
                    }
                    leases.insertLease(new ResourceLease(0L, task.id(), incident.id(), resourceKey,
                            version, actor, now, expiresAt, LeaseStatus.ACTIVE, null, null,
                            now, now));
                    leases.insertEvent(new ResourceEvent(0L, incident.id(), task.id(), resourceKey,
                            ResourceEventType.ASSIGN, null, version, actor, now, null, now));
                    return toLeaseView(leases.findLatestByTask(task.id()).orElseThrow(), taskKey);
                });
    }

    /**
     * 开始执行：仅当前指挥人；仅 OPEN 可开始；必须持有 ACTIVE 租约且授权未到期。
     * 到期则固化 AUTHORIZATION_EXPIRED 与证据事件（提交）并返回 422，不占键。
     */
    @Transactional(noRollbackFor = AuthorizationExpiredException.class)
    public TaskView startTask(String incidentKey, String taskKey, String actor,
                              TaskActionRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        Incident incident = lockIncident(incidentKey);
        IncidentTask task = findTask(incident, taskKey);
        return idempotency.run(commandKey, "task_start",
                CommandIdempotency.hash(incidentKey, taskKey, actor), TaskView.class,
                () -> {
                    requireCommander(incident, actor);
                    lifecycle.assertAuthorizationValid(task, actor, "start");
                },
                () -> {
                    if (task.status() != TaskStatus.OPEN) {
                        throw ApiException.conflict(
                                "仅 OPEN 任务可开始执行，当前状态: " + task.status());
                    }
                    ResourceLease lease = leases.findActiveByTask(task.id())
                            .orElseThrow(() -> ApiException.conflict("任务未分配资源，不能开始执行"));
                    tasks.markStarted(task.id(), now());
                    lifecycle.recordExecution(task, lease, ResourceEventType.EXECUTE_START, actor);
                    return toTaskView(tasks.findByKey(incident.id(), taskKey).orElseThrow());
                });
    }

    /**
     * 继续执行：仅当前指挥人；仅 IN_PROGRESS；授权未到期（到期固化并 422，不占键）。
     * 每次继续执行记录 EXECUTE_CONTINUE 证据事件。
     */
    @Transactional(noRollbackFor = AuthorizationExpiredException.class)
    public TaskView continueTask(String incidentKey, String taskKey, String actor,
                                 TaskActionRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        Incident incident = lockIncident(incidentKey);
        IncidentTask task = findTask(incident, taskKey);
        return idempotency.run(commandKey, "task_continue",
                CommandIdempotency.hash(incidentKey, taskKey, actor), TaskView.class,
                () -> {
                    requireCommander(incident, actor);
                    lifecycle.assertAuthorizationValid(task, actor, "continue");
                },
                () -> {
                    if (task.status() != TaskStatus.IN_PROGRESS) {
                        throw ApiException.conflict(
                                "仅 IN_PROGRESS 任务可继续执行，当前状态: " + task.status());
                    }
                    ResourceLease lease = leases.findActiveByTask(task.id())
                            .orElseThrow(() -> ApiException.conflict("任务未分配资源，不能继续执行"));
                    lifecycle.recordExecution(task, lease, ResourceEventType.EXECUTE_CONTINUE, actor);
                    return toTaskView(tasks.findByKey(incident.id(), taskKey).orElseThrow());
                });
    }

    /**
     * 续期：一次可包含多个依赖任务。先在依赖闭包（请求任务 + 同事件内共享资源的
     * 未终结任务）上验证全部资源与授权版本：任务须 OPEN/IN_PROGRESS、租约 ACTIVE
     * 且未到期、期望版本等于当前版本、续期人不同于原分配人、新版本更大、
     * 新到期时刻晚于续期时刻、事件无待接受交接、闭包资源未被其他事件占用。
     * 任一冲突 422 且不改变任何任务状态或租约；全部通过后在一个事务中提交，
     * 逐项记录 RENEW 证据事件（固化旧新授权版本、时刻与操作人）。
     */
    @Transactional(noRollbackFor = AuthorizationExpiredException.class)
    public ResourceRenewalView renew(String incidentKey, String actor,
                                     ResourceRenewRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String renewer = requireText(actor, "actor");
        if (req.items() == null || req.items().isEmpty()) {
            throw ApiException.badRequest("items 不能为空");
        }
        Set<String> seen = new HashSet<>();
        List<String> hashParts = new ArrayList<>(List.of(incidentKey, renewer));
        for (RenewItem item : req.items()) {
            String taskKey = requireText(item.taskKey(), "taskKey");
            if (!seen.add(taskKey)) {
                throw ApiException.badRequest("续期项任务重复: " + taskKey);
            }
            if (item.expectedVersion() == null || item.newVersion() == null) {
                throw ApiException.badRequest("expectedVersion/newVersion 不能为空");
            }
            if (item.newExpiresAt() == null) {
                throw ApiException.badRequest("newExpiresAt 不能为空");
            }
            hashParts.add(taskKey + ":" + item.expectedVersion() + ":" + item.newVersion()
                    + ":" + item.newExpiresAt().truncatedTo(ChronoUnit.MICROS));
        }
        Incident incident = lockIncident(incidentKey);
        Map<String, IncidentTask> requested = new LinkedHashMap<>();
        for (RenewItem item : req.items()) {
            requested.put(item.taskKey(), findTask(incident, item.taskKey()));
        }
        return idempotency.run(commandKey, "resource_renew",
                CommandIdempotency.hash(hashParts.toArray(new String[0])),
                ResourceRenewalView.class,
                () -> {
                    // 到期门禁：任一请求任务授权已到期则先固化到期证据再 422（不占键）
                    for (IncidentTask task : requested.values()) {
                        lifecycle.assertAuthorizationValid(task, renewer, "renew");
                    }
                },
                () -> doRenew(incident, renewer, req.items(), requested));
    }

    private ResourceRenewalView doRenew(Incident incident, String renewer, List<RenewItem> items,
                                        Map<String, IncidentTask> requested) {
        Instant now = now();
        if (incidents.findPendingTransfer(incident.id()).isPresent()) {
            throw ApiException.unprocessable("事件存在待接受的交接，交接中的任务不可续期",
                    Map.of("incidentKey", incident.incidentKey()));
        }
        Map<String, ResourceLease> leaseByTask = new LinkedHashMap<>();
        for (Map.Entry<String, IncidentTask> entry : requested.entrySet()) {
            ResourceLease lease = leases.findActiveByTask(entry.getValue().id())
                    .orElseThrow(() -> ApiException.unprocessable(
                            "任务没有生效中的资源授权: " + entry.getKey(),
                            Map.of("taskKey", entry.getKey())));
            leaseByTask.put(entry.getKey(), lease);
        }
        // 依赖闭包：请求任务 + 同事件内共享资源的未终结任务（传递闭包）
        List<ResourceLease> incidentLeases = leases.listByIncident(incident.id());
        Set<Long> closureTaskIds = new LinkedHashSet<>();
        requested.values().forEach(t -> closureTaskIds.add(t.id()));
        Set<String> closureResources = new LinkedHashSet<>();
        leaseByTask.values().forEach(l -> closureResources.add(l.resourceKey()));
        boolean changed = true;
        while (changed) {
            changed = false;
            for (ResourceLease lease : incidentLeases) {
                if (lease.status() != LeaseStatus.ACTIVE) {
                    continue;
                }
                if (closureResources.contains(lease.resourceKey())
                        && closureTaskIds.add(lease.taskId())) {
                    changed = true;
                }
                if (closureTaskIds.contains(lease.taskId())
                        && closureResources.add(lease.resourceKey())) {
                    changed = true;
                }
            }
        }
        // 闭包资源按键排序加锁（避免死锁），并验证未被其他事件占用
        for (String resourceKey : new TreeSet<>(closureResources)) {
            leases.lockResource(resourceKey, now);
        }
        for (String resourceKey : closureResources) {
            List<ResourceLease> occupying = leases.listOccupying(resourceKey, now).stream()
                    .filter(l -> l.incidentId() != incident.id())
                    .toList();
            if (!occupying.isEmpty()) {
                throw ApiException.unprocessable("闭包资源仍被其他事件占用: " + resourceKey,
                        occupyingDetails(occupying));
            }
        }
        // 逐项校验（全部通过前不写任何状态）
        for (RenewItem item : items) {
            IncidentTask task = requested.get(item.taskKey());
            ResourceLease lease = leaseByTask.get(item.taskKey());
            if (task.status() != TaskStatus.OPEN && task.status() != TaskStatus.IN_PROGRESS) {
                throw ApiException.unprocessable("任务状态 " + task.status() + " 不可续期",
                        Map.of("taskKey", item.taskKey(), "status", task.status().name()));
            }
            if (item.expectedVersion() != lease.authVersion()) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("taskKey", item.taskKey());
                details.put("expectedVersion", item.expectedVersion());
                details.put("actualVersion", lease.authVersion());
                throw ApiException.unprocessable("授权版本与当前版本不一致: " + item.taskKey(), details);
            }
            if (renewer.equals(lease.assignedBy())) {
                throw ApiException.unprocessable("续期人必须不同于原分配人",
                        Map.of("taskKey", item.taskKey(), "assignedBy", lease.assignedBy()));
            }
            if (item.newVersion() <= lease.authVersion()) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("taskKey", item.taskKey());
                details.put("currentVersion", lease.authVersion());
                details.put("requestedVersion", item.newVersion());
                throw ApiException.unprocessable("新授权版本必须大于当前版本", details);
            }
            Instant newExpiresAt = item.newExpiresAt().truncatedTo(ChronoUnit.MICROS);
            if (!newExpiresAt.isAfter(now)) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("taskKey", item.taskKey());
                details.put("renewedAt", now);
                details.put("newExpiresAt", newExpiresAt);
                details.put("requirement", "newExpiresAt 必须晚于续期时刻 renewedAt");
                throw ApiException.unprocessable("新到期时刻必须晚于续期时刻", details);
            }
        }
        // 全部校验通过，同事务提交全部续期
        List<RenewedLeaseView> renewed = new ArrayList<>();
        for (RenewItem item : items) {
            ResourceLease lease = leaseByTask.get(item.taskKey());
            Instant newExpiresAt = item.newExpiresAt().truncatedTo(ChronoUnit.MICROS);
            leases.renewLease(lease.id(), item.newVersion(), newExpiresAt, now);
            leases.insertEvent(new ResourceEvent(0L, incident.id(), lease.taskId(),
                    lease.resourceKey(), ResourceEventType.RENEW, lease.authVersion(),
                    item.newVersion(), renewer, now, null, now));
            renewed.add(new RenewedLeaseView(item.taskKey(), lease.resourceKey(),
                    lease.authVersion(), item.newVersion(), lease.expiresAt(), newExpiresAt,
                    ChronoUnit.SECONDS.between(lease.expiresAt(), newExpiresAt)));
        }
        return new ResourceRenewalView(incident.incidentKey(), renewer, now, renewed);
    }

    /**
     * 显式回收：仅当前指挥人；任务须持有 ACTIVE 租约。记录 RELEASE 证据事件。
     * 回收前先按注入时钟做授权到期门禁：已到期则固化 AUTHORIZATION_EXPIRED 并 422。
     */
    @Transactional(noRollbackFor = AuthorizationExpiredException.class)
    public ResourceLeaseView release(String incidentKey, String taskKey, String actor,
                                     TaskActionRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        Incident incident = lockIncident(incidentKey);
        IncidentTask task = findTask(incident, taskKey);
        return idempotency.run(commandKey, "resource_release",
                CommandIdempotency.hash(incidentKey, taskKey, actor), ResourceLeaseView.class,
                () -> {
                    requireCommander(incident, actor);
                    lifecycle.assertAuthorizationValid(task, actor, "release");
                },
                () -> {
                    ResourceLease lease = leases.findActiveByTask(task.id())
                            .orElseThrow(() -> ApiException.conflict("任务没有生效中的资源授权"));
                    Instant now = now();
                    leases.markReleased(lease.id(), actor, now);
                    leases.insertEvent(new ResourceEvent(0L, incident.id(), task.id(),
                            lease.resourceKey(), ResourceEventType.RELEASE, lease.authVersion(),
                            null, actor, now, "显式回收", now));
                    return toLeaseView(leases.findLatestByTask(task.id()).orElseThrow(), taskKey);
                });
    }

    /**
     * 授权到期检查：以注入时钟评估，ACTIVE 租约且当前时刻 >= expiresAt 时固化
     * 任务 AUTHORIZATION_EXPIRED 与租约 EXPIRED 并记录 EXPIRE 证据事件；
     * 否则只返回当前租约视图。同键重放首次结果；需要重新评估必须更换 commandKey。
     */
    @Transactional
    public ResourceLeaseView checkAuthorization(String incidentKey, String taskKey,
                                                TaskActionRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        Incident incident = lockIncident(incidentKey);
        IncidentTask task = findTask(incident, taskKey);
        return idempotency.run(commandKey, "resource_check",
                CommandIdempotency.hash(incidentKey, taskKey), ResourceLeaseView.class,
                () -> {
                    ResourceLease lease = leases.findLatestByTask(task.id())
                            .orElseThrow(() -> ApiException.notFound("任务没有资源授权记录"));
                    Instant now = now();
                    if (lease.status() == LeaseStatus.ACTIVE && lease.expiredAt(now)
                            && (task.status() == TaskStatus.OPEN
                            || task.status() == TaskStatus.IN_PROGRESS)) {
                        String evaluator = incident.commander() == null
                                ? "system" : incident.commander();
                        tasks.markAuthorizationExpired(task.id(), now);
                        leases.markExpired(lease.id(), now);
                        leases.insertEvent(new ResourceEvent(0L, incident.id(), task.id(),
                                lease.resourceKey(), ResourceEventType.EXPIRE,
                                lease.authVersion(), null, evaluator, now, "授权到期判定", now));
                    }
                    return toLeaseView(leases.findLatestByTask(task.id()).orElseThrow(), taskKey);
                });
    }

    /**
     * 查询任务当前资源租约明细（expired 为按查询时刻计算的派生标志）。只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public ResourceLeaseView getLease(String incidentKey, String taskKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        IncidentTask task = findTask(incident, taskKey);
        ResourceLease lease = leases.findLatestByTask(task.id())
                .orElseThrow(() -> ApiException.notFound("任务没有资源授权记录"));
        return toLeaseView(lease, taskKey);
    }

    /**
     * 查询任务资源证据事件历史（按落库顺序）。只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public List<ResourceEventView> listEvents(String incidentKey, String taskKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        IncidentTask task = findTask(incident, taskKey);
        return leases.listEventsByTask(task.id()).stream()
                .map(e -> new ResourceEventView(e.id(), task.taskKey(), e.eventType().name(),
                        e.resourceKey(), e.oldVersion(), e.newVersion(), e.actor(),
                        e.occurredAt(), e.detail()))
                .toList();
    }

    /**
     * 事件级资源诊断：全部租约 + 任务当前状态 + 按查询时刻计算的 expired 派生标志。
     * 只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public ResourceDiagnosticsView diagnostics(String incidentKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        Map<Long, IncidentTask> taskById = new LinkedHashMap<>();
        tasks.listByIncident(incident.id()).forEach(t -> taskById.put(t.id(), t));
        Instant now = now();
        List<ResourceDiagnosticsItem> items = leases.listByIncident(incident.id()).stream()
                .map(l -> {
                    IncidentTask task = taskById.get(l.taskId());
                    return new ResourceDiagnosticsItem(
                            task == null ? null : task.taskKey(),
                            task == null ? null : task.status().name(),
                            l.resourceKey(), l.authVersion(), l.expiresAt(), l.status().name(),
                            l.expiredAt(now));
                })
                .toList();
        return new ResourceDiagnosticsView(incident.incidentKey(), items);
    }

    private List<Map<String, Object>> occupyingDetails(List<ResourceLease> occupying) {
        List<Map<String, Object>> details = new ArrayList<>();
        for (ResourceLease lease : occupying) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("resourceKey", lease.resourceKey());
            item.put("occupyingIncidentKey", incidents.findById(lease.incidentId())
                    .map(Incident::incidentKey).orElse(null));
            item.put("authVersion", lease.authVersion());
            item.put("expiresAt", lease.expiresAt());
            details.add(item);
        }
        return details;
    }

    private ResourceLeaseView toLeaseView(ResourceLease lease, String taskKey) {
        return new ResourceLeaseView(taskKey, lease.resourceKey(), lease.authVersion(),
                lease.assignedBy(), lease.assignedAt(), lease.expiresAt(), lease.status().name(),
                lease.expiredAt(now()), lease.releasedBy(), lease.releasedAt());
    }

    private TaskView toTaskView(IncidentTask task) {
        List<TaskBlockerView> blockers = incidents.listBlockingIncidents(task.id()).stream()
                .map(b -> new TaskBlockerView(b.incidentKey(), b.status().name(),
                        UNBLOCKING_STATUSES.contains(b.status())))
                .toList();
        return new TaskView(task.taskKey(), task.groupCode(), task.title(), task.status().name(),
                blockers, task.createdBy(), task.createdAt(), task.doneBy(), task.doneAt(),
                task.cancelledBy(), task.cancelledAt());
    }

    private IncidentTask findTask(Incident incident, String taskKey) {
        return tasks.findByKey(incident.id(), taskKey)
                .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskKey));
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
}
