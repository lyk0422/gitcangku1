package com.example.starter.incident;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.example.starter.incident.dto.Requests.ResourceAssignRequest;
import com.example.starter.incident.dto.Requests.RenewalEntry;
import com.example.starter.incident.dto.Requests.TaskRenewalRequest;
import com.example.starter.incident.dto.Responses.ExecutionLogView;
import com.example.starter.incident.dto.Responses.RenewalView;
import com.example.starter.incident.dto.Responses.RenewedTaskView;
import com.example.starter.incident.dto.Responses.ResourceAssignmentView;
import com.example.starter.incident.dto.Responses.ResourceDiagnosticsView;
import com.example.starter.incident.dto.Responses.ResourceEventView;
import com.example.starter.incident.dto.Responses.TaskResourceHistoryView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 事件资源授权服务：资源分配、批量续期与只读明细/历史/诊断。
 * 时间口径：UTC，租约区间 [assignedAt, expiresAt) 左闭右开；到期判定只在命令/访问时
 * 按注入时钟进行，不依赖后台扫描。并发约定：先锁定事件行，再按 resource_key 排序
 * 持有资源串行锁，占用校验与写入同事务提交，按提交顺序裁决。
 */
@Service
public class ResourceAuthorizationService {

    private final IncidentRepository incidents;
    private final IncidentTaskRepository tasks;
    private final TaskResourceRepository resources;
    private final ResourceEvidenceRepository evidence;
    private final Idempotency idempotency;
    private final Clock clock;

    public ResourceAuthorizationService(IncidentRepository incidents, IncidentTaskRepository tasks,
                                        TaskResourceRepository resources,
                                        ResourceEvidenceRepository evidence,
                                        Idempotency idempotency, Clock clock) {
        this.incidents = incidents;
        this.tasks = tasks;
        this.resources = resources;
        this.evidence = evidence;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    private Instant now() {
        return clock.instant();
    }

    /**
     * 资源分配：仅当前指挥人；任务须为 OPEN/IN_PROGRESS 且尚未分配资源；
     * 到期时刻必须晚于分配时刻（否则 400 并返回实际值）；同一资源在有效区间内
     * 不得被其他事件占用（否则 409）。成功后固化 ASSIGN 证据。
     */
    @Transactional
    public ResourceAssignmentView assign(String incidentKey, String taskKey, String actor,
                                         ResourceAssignRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String resourceKey = requireText(req.resourceKey(), "resourceKey");
        if (req.authorizationVersion() == null || req.authorizationVersion() < 1) {
            throw ApiException.badRequest("authorizationVersion 必须为 ≥ 1 的整数");
        }
        long version = req.authorizationVersion();
        if (req.expiresAt() == null) {
            throw ApiException.badRequest("expiresAt 不能为空");
        }
        Instant expiresAt = req.expiresAt().truncatedTo(ChronoUnit.MICROS);
        Incident incident = lockIncident(incidentKey);
        return idempotency.run(commandKey, "resource_assign",
                Idempotency.hash(incidentKey, taskKey, actor, resourceKey,
                        String.valueOf(version), expiresAt.toString()),
                ResourceAssignmentView.class, () -> {
                    requireCommander(incident, actor);
                    IncidentTask task = tasks.findByKey(incident.id(), taskKey)
                            .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskKey));
                    if (task.status() == TaskStatus.DONE || task.status() == TaskStatus.CANCELLED) {
                        throw ApiException.conflict("任务已处于终态 " + task.status() + "，不能分配资源");
                    }
                    if (task.status() == TaskStatus.AUTHORIZATION_EXPIRED) {
                        throw ApiException.conflict("授权已到期的任务禁止再次分配，须由续期恢复");
                    }
                    Instant now = now();
                    if (!expiresAt.isAfter(now)) {
                        throw ApiException.badRequest("到期时刻必须晚于分配时刻",
                                Map.of("assignedAt", now.toString(), "expiresAt",
                                        expiresAt.toString(), "required", "expiresAt > assignedAt"));
                    }
                    if (resources.findByTaskId(task.id()).isPresent()) {
                        throw ApiException.conflict("任务已分配资源，不能重复分配；如需变更请提交续期");
                    }
                    resources.lockResource(resourceKey);
                    List<TaskResourceAssignment> occupied = resources
                            .listActiveByResource(resourceKey, now).stream()
                            .filter(a -> a.incidentId() != incident.id())
                            .toList();
                    if (!occupied.isEmpty()) {
                        TaskResourceAssignment holder = occupied.get(0);
                        throw ApiException.conflict("资源仍被其他事件占用: " + resourceKey,
                                Map.of("resourceKey", resourceKey,
                                        "occupiedByIncidentId", holder.incidentId(),
                                        "occupiedUntil", holder.expiresAt().toString()));
                    }
                    resources.insert(new TaskResourceAssignment(0L, task.id(), incident.id(),
                            resourceKey, version, actor, now, expiresAt, now, now));
                    evidence.insertEvent(new ResourceAuthorizationEvent(0L, ResourceEventType.ASSIGN,
                            task.id(), incident.id(), taskKey, resourceKey, null, version, actor,
                            null, now));
                    return toAssignmentView(
                            resources.findByTaskId(task.id()).orElseThrow(), task);
                });
    }

    /**
     * 查询单任务资源明细与历史（当前租约 + 授权证据 + 执行日志）。只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public TaskResourceHistoryView taskResourceHistory(String incidentKey, String taskKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        IncidentTask task = tasks.findByKey(incident.id(), taskKey)
                .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskKey));
        var assignment = resources.findByTaskId(task.id()).orElse(null);
        List<ResourceEventView> events = evidence.listEventsByTask(task.id()).stream()
                .map(ResourceAuthorizationService::toEventView).toList();
        List<ExecutionLogView> logs = evidence.listExecutionLogsByTask(task.id()).stream()
                .map(l -> new ExecutionLogView(l.action(), l.note(), l.actor(), l.occurredAt()))
                .toList();
        return new TaskResourceHistoryView(
                assignment == null ? null : toAssignmentView(assignment, task), events, logs);
    }

    /**
     * 查询事件全部资源授权证据（按发生顺序）。只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public List<ResourceEventView> listEvents(String incidentKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        return evidence.listEventsByIncident(incident.id()).stream()
                .map(ResourceAuthorizationService::toEventView).toList();
    }

    /**
     * 资源诊断：按评估时刻给出每个租约是否到期、是否仍占用资源。只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public ResourceDiagnosticsView diagnostics(String incidentKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        List<ResourceAssignmentView> views = new ArrayList<>();
        for (TaskResourceAssignment assignment : resources.listByIncident(incident.id())) {
            IncidentTask task = tasks.findById(assignment.taskId()).orElse(null);
            views.add(toAssignmentView(assignment, task));
        }
        return new ResourceDiagnosticsView(incidentKey, now(), views);
    }

    private ResourceAssignmentView toAssignmentView(TaskResourceAssignment assignment,
                                                    IncidentTask task) {
        boolean expired = assignment.expiredAt(now());
        String status = task == null ? null : task.status().name();
        if (expired && task != null && task.status() != TaskStatus.DONE
                && task.status() != TaskStatus.CANCELLED) {
            status = TaskStatus.AUTHORIZATION_EXPIRED.name();
        }
        return new ResourceAssignmentView(task == null ? null : task.taskKey(),
                assignment.resourceKey(), assignment.authorizationVersion(),
                assignment.assignedBy(), assignment.assignedAt(), assignment.expiresAt(),
                expired, status);
    }

    private static ResourceEventView toEventView(ResourceAuthorizationEvent event) {
        return new ResourceEventView(event.id(), event.eventType().name(), event.taskKey(),
                event.resourceKey(), event.oldAuthorizationVersion(),
                event.newAuthorizationVersion(), event.actor(), event.attemptedAction(),
                event.occurredAt());
    }

    /**
     * 批量续期：一次可包含多个依赖任务。提交人必须不同于各任务原分配人；
     * 已完成/已取消/已交接出去（存在不早于租约创建时刻的已接受交接）的任务不可续期；
     * 新授权版本必须严格大于当前版本，新到期时刻必须晚于续期时刻。
     * 先验证完整依赖闭包内所有资源与授权版本（闭包内未续期任务的授权必须仍有效、
     * 资源不得被其他事件占用），任一冲突 422 且不改变任何任务状态或租约；
     * 全部通过后统一提交，AUTHORIZATION_EXPIRED 任务恢复到期前状态。
     */
    @Transactional
    public RenewalView renew(String incidentKey, String actor, TaskRenewalRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String renewer = requireText(actor, "actor");
        if (req.entries() == null || req.entries().isEmpty()) {
            throw ApiException.badRequest("entries 不能为空");
        }
        record ParsedEntry(String taskKey, long newVersion, Instant newExpiresAt) {
        }
        List<ParsedEntry> entries = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (RenewalEntry entry : req.entries()) {
            String taskKey = requireText(entry.taskKey(), "entries[].taskKey");
            if (!seen.add(taskKey)) {
                throw ApiException.badRequest("entries 存在重复 taskKey: " + taskKey);
            }
            if (entry.newAuthorizationVersion() == null) {
                throw ApiException.badRequest("newAuthorizationVersion 不能为空");
            }
            if (entry.newExpiresAt() == null) {
                throw ApiException.badRequest("newExpiresAt 不能为空");
            }
            entries.add(new ParsedEntry(taskKey, entry.newAuthorizationVersion(),
                    entry.newExpiresAt().truncatedTo(ChronoUnit.MICROS)));
        }
        Incident incident = lockIncident(incidentKey);
        String requestHash = Idempotency.hash(incidentKey, renewer, entries.stream()
                .map(e -> e.taskKey() + ":" + e.newVersion() + ":" + e.newExpiresAt())
                .reduce((a, b) -> a + ";" + b).orElse(""));
        return idempotency.run(commandKey, "resource_renew", requestHash, RenewalView.class, () -> {
            Instant now = now();
            Map<String, IncidentTask> entryTasks = new LinkedHashMap<>();
            Map<String, TaskResourceAssignment> entryAssignments = new LinkedHashMap<>();
            for (ParsedEntry entry : entries) {
                IncidentTask task = tasks.findByKey(incident.id(), entry.taskKey())
                        .orElseThrow(() -> ApiException.notFound("任务不存在: " + entry.taskKey()));
                entryTasks.put(entry.taskKey(), task);
                entryAssignments.put(entry.taskKey(), resources.findByTaskId(task.id())
                        .orElseThrow(() -> ApiException.unprocessable(
                                "任务未分配资源，不能续期: " + entry.taskKey(), null)));
            }
            List<IncidentTransfer> transfers = incidents.listTransfers(incident.id());
            for (ParsedEntry entry : entries) {
                IncidentTask task = entryTasks.get(entry.taskKey());
                TaskResourceAssignment assignment = entryAssignments.get(entry.taskKey());
                if (task.status() == TaskStatus.DONE || task.status() == TaskStatus.CANCELLED) {
                    throw ApiException.unprocessable("任务已处于终态 " + task.status()
                            + "，不可续期: " + entry.taskKey(), null);
                }
                boolean transferredOut = transfers.stream()
                        .anyMatch(t -> t.status() == TransferStatus.ACCEPTED
                                && t.acceptedAt() != null
                                && !t.acceptedAt().isBefore(assignment.assignedAt()));
                if (transferredOut) {
                    throw ApiException.unprocessable(
                            "任务已随事件交接出去，不可续期: " + entry.taskKey(), null);
                }
                if (renewer.equals(assignment.assignedBy())) {
                    throw ApiException.unprocessable("续期人必须不同于原分配人: "
                            + assignment.assignedBy(), Map.of("taskKey", entry.taskKey(),
                            "assignedBy", assignment.assignedBy(), "renewer", renewer));
                }
                if (entry.newVersion() <= assignment.authorizationVersion()) {
                    throw ApiException.unprocessable("新授权版本必须大于当前版本",
                            Map.of("taskKey", entry.taskKey(), "resourceKey",
                                    assignment.resourceKey(), "currentVersion",
                                    assignment.authorizationVersion(), "requestedVersion",
                                    entry.newVersion()));
                }
                if (!entry.newExpiresAt().isAfter(now)) {
                    throw ApiException.unprocessable("新到期时刻必须晚于续期时刻",
                            Map.of("taskKey", entry.taskKey(), "newExpiresAt",
                                    entry.newExpiresAt().toString(), "now", now.toString(),
                                    "required", "newExpiresAt > now"));
                }
            }
            // 完整依赖闭包：条目任务沿阻塞边可达的全部任务（跨事件）
            Set<Long> closureIds = new LinkedHashSet<>();
            Deque<Long> queue = new ArrayDeque<>();
            entryTasks.values().forEach(t -> queue.add(t.id()));
            while (!queue.isEmpty()) {
                long taskId = queue.poll();
                if (!closureIds.add(taskId)) {
                    continue;
                }
                for (long blockerIncidentId : tasks.listBlockingIncidentIds(taskId)) {
                    for (IncidentTask t : tasks.listByIncident(blockerIncidentId)) {
                        queue.add(t.id());
                    }
                }
            }
            Map<Long, IncidentTask> closureTasks = new LinkedHashMap<>();
            for (long taskId : closureIds) {
                IncidentTask found = tasks.findById(taskId).orElse(null);
                if (found != null) {
                    closureTasks.put(taskId, found);
                }
            }
            Map<Long, TaskResourceAssignment> closureAssignments = new LinkedHashMap<>();
            for (TaskResourceAssignment a : resources.findByTaskIds(closureIds)) {
                closureAssignments.put(a.taskId(), a);
            }
            Set<Long> entryTaskIds = new LinkedHashSet<>();
            entryTasks.values().forEach(t -> entryTaskIds.add(t.id()));
            // 闭包校验：未在本次续期内的任务授权必须仍有效
            for (IncidentTask task : closureTasks.values()) {
                TaskResourceAssignment a = closureAssignments.get(task.id());
                if (a == null || task.status() == TaskStatus.DONE
                        || task.status() == TaskStatus.CANCELLED) {
                    continue;
                }
                if (!entryTaskIds.contains(task.id()) && a.expiredAt(now)) {
                    throw ApiException.unprocessable(
                            "依赖闭包中任务授权已到期，须一并续期: " + task.taskKey(),
                            Map.of("taskKey", task.taskKey(), "resourceKey", a.resourceKey(),
                                    "expiresAt", a.expiresAt().toString(),
                                    "now", now.toString()));
                }
            }
            // 资源占用校验：按 resource_key 排序加串行锁后，闭包内资源不得被其他事件占用
            Set<String> resourceKeys = new TreeSet<>();
            for (IncidentTask task : closureTasks.values()) {
                TaskResourceAssignment a = closureAssignments.get(task.id());
                if (a != null && task.status() != TaskStatus.DONE
                        && task.status() != TaskStatus.CANCELLED) {
                    resourceKeys.add(a.resourceKey());
                }
            }
            resourceKeys.forEach(resources::lockResource);
            for (IncidentTask task : closureTasks.values()) {
                TaskResourceAssignment a = closureAssignments.get(task.id());
                if (a == null || task.status() == TaskStatus.DONE
                        || task.status() == TaskStatus.CANCELLED) {
                    continue;
                }
                for (TaskResourceAssignment other
                        : resources.listActiveByResource(a.resourceKey(), now)) {
                    if (other.incidentId() != task.incidentId()) {
                        throw ApiException.unprocessable(
                                "资源仍被其他事件占用: " + a.resourceKey(),
                                Map.of("taskKey", task.taskKey(), "resourceKey", a.resourceKey(),
                                        "occupiedByIncidentId", other.incidentId(),
                                        "occupiedUntil", other.expiresAt().toString()));
                    }
                }
            }
            // 全部校验通过：统一写回租约、固化 RENEW 证据、恢复到期任务状态
            List<RenewedTaskView> renewed = new ArrayList<>();
            for (ParsedEntry entry : entries) {
                IncidentTask task = entryTasks.get(entry.taskKey());
                TaskResourceAssignment assignment = entryAssignments.get(entry.taskKey());
                resources.updateAuthorization(assignment.id(), entry.newVersion(),
                        entry.newExpiresAt(), now);
                evidence.insertEvent(new ResourceAuthorizationEvent(0L, ResourceEventType.RENEW,
                        task.id(), incident.id(), entry.taskKey(), assignment.resourceKey(),
                        assignment.authorizationVersion(), entry.newVersion(), renewer, null, now));
                TaskStatus status = task.status();
                if (status == TaskStatus.AUTHORIZATION_EXPIRED) {
                    status = task.preExpiryStatus() == null ? TaskStatus.OPEN
                            : task.preExpiryStatus();
                    tasks.restoreFromExpiry(task.id(), status, now);
                }
                renewed.add(new RenewedTaskView(entry.taskKey(), assignment.resourceKey(),
                        assignment.authorizationVersion(), entry.newVersion(),
                        entry.newExpiresAt(), status.name()));
            }
            return new RenewalView(incidentKey, List.copyOf(renewed));
        });
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
