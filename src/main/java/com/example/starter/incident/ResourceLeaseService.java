package com.example.starter.incident;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.example.starter.incident.dto.Requests.CredentialRegisterRequest;
import com.example.starter.incident.dto.Requests.CredentialRevokeRequest;
import com.example.starter.incident.dto.Requests.LeaseAssignRequest;
import com.example.starter.incident.dto.Requests.LeaseReplaceRequest;
import com.example.starter.incident.dto.Requests.LeaseTaskRef;
import com.example.starter.incident.dto.Requests.ResourceRegisterRequest;
import com.example.starter.incident.dto.Responses.CredentialIssueView;
import com.example.starter.incident.dto.Responses.CredentialView;
import com.example.starter.incident.dto.Responses.LeaseBatchView;
import com.example.starter.incident.dto.Responses.LeaseView;
import com.example.starter.incident.dto.Responses.ResourceCredentialsView;
import com.example.starter.incident.dto.Responses.ResourceView;
import com.example.starter.incident.dto.Responses.RiskLeaseListView;
import com.example.starter.incident.dto.Responses.RiskLeaseView;
import com.example.starter.incident.dto.Responses.RiskRecordView;
import com.example.starter.incident.dto.Responses.TaskGateView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 资源资质与租约服务。
 * 并发约定：资质登记/撤销、租约分配/替换均先持有 lease_domain_lock 全局锁，
 * 再锁定资源行与（按 incidentKey 排序的）事件行，同事务内完成校验与写入，
 * 撤销、分配、替换按事务提交顺序裁决；任务开始/完成经任务行的条件更新与撤销互斥。
 * 幂等约定：leaseKey/commandKey 全局唯一；租约键指纹含操作者、资源版本、
 * 规范化任务集合、租约时段与资质集合，同键成功重放首次响应，失败不占键。
 * 资质语义：有效期为 UTC 半开区间 [validFrom, validUntil)，必须严格覆盖任务
 * 计划完成时刻（validUntil 晚于该时刻），否则 422 并列出缺失或到期资质。
 */
@Service
public class ResourceLeaseService {

    /** 视为阻塞已解除的目标事件状态。 */
    private static final Set<IncidentStatus> UNBLOCKING_STATUSES = EnumSet.of(
            IncidentStatus.CONTAINED, IncidentStatus.RESOLVED, IncidentStatus.CLOSED);

    /** 单批租约分配的任务上限。 */
    private static final int MAX_TASKS_PER_BATCH = 20;

    private final ResourceRepository resources;
    private final LeaseRepository leases;
    private final IncidentRepository incidents;
    private final IncidentTaskRepository tasks;
    private final IdempotentExecutor idem;
    private final Clock clock;

    public ResourceLeaseService(ResourceRepository resources, LeaseRepository leases,
                                IncidentRepository incidents, IncidentTaskRepository tasks,
                                IdempotentExecutor idem, Clock clock) {
        this.resources = resources;
        this.leases = leases;
        this.incidents = incidents;
        this.tasks = tasks;
        this.idem = idem;
        this.clock = clock;
    }

    private Instant now() {
        return clock.instant();
    }

    /**
     * 登记共享资源：初始版本 1。resourceKey 重复返回 409。
     */
    @Transactional
    public ResourceView registerResource(String actor, ResourceRegisterRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String resourceKey = requireText(req.resourceKey(), "resourceKey");
        requireText(actor, "actor");
        return idem.run(commandKey, "resource_register",
                IdempotentExecutor.hash(resourceKey, actor), ResourceView.class, () -> {
                    if (resources.findByKey(resourceKey).isPresent()) {
                        throw ApiException.conflict("resourceKey 已存在: " + resourceKey);
                    }
                    Instant now = now();
                    try {
                        resources.insert(new Resource(0L, resourceKey, 1, actor, now, now));
                    } catch (DuplicateKeyException e) {
                        throw ApiException.conflict("resourceKey 已存在: " + resourceKey);
                    }
                    return toResourceView(resources.findByKey(resourceKey).orElseThrow());
                });
    }

    /**
     * 登记资源资质：有效期须为合法半开区间；同代码未撤销重复登记 409，
     * 已撤销的同代码资质可重新登记（覆盖有效期并复位撤销标记）。
     * 登记使资源版本递增。
     */
    @Transactional
    public CredentialView registerCredential(String resourceKey, String actor,
                                             CredentialRegisterRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String code = requireText(req.credentialCode(), "credentialCode");
        requireText(actor, "actor");
        if (req.validFrom() == null || req.validUntil() == null) {
            throw ApiException.badRequest("validFrom/validUntil 不能为空");
        }
        Instant validFrom = req.validFrom().truncatedTo(ChronoUnit.MICROS);
        Instant validUntil = req.validUntil().truncatedTo(ChronoUnit.MICROS);
        if (!validFrom.isBefore(validUntil)) {
            throw ApiException.badRequest("validFrom 必须早于 validUntil");
        }
        leases.lockDomain();
        Resource resource = lockResource(resourceKey);
        return idem.run(commandKey, "credential_register",
                IdempotentExecutor.hash(resourceKey, actor, code, validFrom.toString(),
                        validUntil.toString()),
                CredentialView.class, () -> {
                    var existing = resources.findCredential(resource.id(), code);
                    Instant now = now();
                    if (existing.isPresent()) {
                        ResourceCredential found = existing.get();
                        if (!found.revoked()) {
                            throw ApiException.conflict("资质代码已登记且未撤销: " + code);
                        }
                        resources.reregisterCredential(found.id(), validFrom, validUntil);
                    } else {
                        resources.insertCredential(new ResourceCredential(0L, resource.id(), code,
                                validFrom, validUntil, false, null, now));
                    }
                    resources.bumpVersion(resource.id(), now);
                    return toCredentialView(
                            resources.findCredential(resource.id(), code).orElseThrow());
                });
    }

    /**
     * 提前撤销资源资质：资源版本递增；同事务内将该资源未来有效（lease_end 晚于当前
     * 时刻）且覆盖该资质的高危租约转为 CREDENTIAL_RISK，任务条件更新为
     * CREDENTIAL_RISK（已完成/已取消任务不改写，其租约直接释放），
     * 并为每条受影响租约写入不可变风险记录。
     */
    @Transactional
    public CredentialView revokeCredential(String resourceKey, String credentialCode,
                                           String actor, CredentialRevokeRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String code = requireText(credentialCode, "credentialCode");
        requireText(actor, "actor");
        leases.lockDomain();
        Resource resource = lockResource(resourceKey);
        return idem.run(commandKey, "credential_revoke",
                IdempotentExecutor.hash(resourceKey, actor, code), CredentialView.class, () -> {
                    ResourceCredential credential = resources.findCredential(resource.id(), code)
                            .orElseThrow(() -> ApiException.notFound(
                                    "资质不存在: " + resourceKey + "/" + code));
                    if (credential.revoked()) {
                        throw ApiException.conflict("资质已撤销: " + code);
                    }
                    Instant now = now();
                    resources.revokeCredential(resource.id(), code, now);
                    resources.bumpVersion(resource.id(), now);
                    for (ResourceLease lease
                            : leases.listFutureHoldingByResource(resource.id(), now)) {
                        if (!lease.credentialCodes().contains(code)) {
                            continue;
                        }
                        int updated = tasks.markCredentialRisk(lease.taskId(), now);
                        if (updated == 0) {
                            // 任务已并发进入终态：已完成任务不改写，租约直接释放
                            leases.updateStatus(lease.id(), LeaseStatus.RELEASED, null, now);
                            continue;
                        }
                        leases.updateStatus(lease.id(), LeaseStatus.CREDENTIAL_RISK, null, now);
                        leases.insertRiskRecord(new CredentialRiskRecord(0L, lease.id(),
                                lease.taskId(), resource.id(), code, now, now));
                    }
                    return toCredentialView(
                            resources.findCredential(resource.id(), code).orElseThrow());
                });
    }

    /**
     * 批量租约分配：为多个高危任务分配同一资源。先校验资源租约冲突、
     * 各任务依赖门禁（状态可租、无持有中租约、阻塞全部解除）与全部资质后态
     * （资源拥有全部必需资质且严格覆盖各任务计划完成时刻），再单事务创建全部
     * 租约；任一任务失败整单回滚。leaseKey 指纹含操作者、资源版本、规范化任务
     * 集合、租约时段与资质集合，同键成功重放首次响应，失败不占键。
     */
    @Transactional
    public LeaseBatchView assignLeases(String actor, LeaseAssignRequest req) {
        String leaseKey = requireText(req.leaseKey(), "leaseKey");
        String resourceKey = requireText(req.resourceKey(), "resourceKey");
        requireText(actor, "actor");
        if (req.leaseStart() == null || req.leaseEnd() == null) {
            throw ApiException.badRequest("leaseStart/leaseEnd 不能为空");
        }
        Instant leaseStart = req.leaseStart().truncatedTo(ChronoUnit.MICROS);
        Instant leaseEnd = req.leaseEnd().truncatedTo(ChronoUnit.MICROS);
        if (!leaseStart.isBefore(leaseEnd)) {
            throw ApiException.badRequest("leaseStart 必须早于 leaseEnd");
        }
        List<LeaseTaskRef> refs = normalizeTaskRefs(req.tasks());
        leases.lockDomain();
        Resource resource = lockResource(resourceKey);
        List<LoadedTask> loaded = loadTasks(refs);
        String taskSet = refs.stream()
                .map(r -> r.incidentKey() + "/" + r.taskKey())
                .collect(Collectors.joining(","));
        String credentialUnion = loaded.stream()
                .flatMap(t -> t.requiredCredentials().stream())
                .distinct().sorted().collect(Collectors.joining(","));
        String fingerprint = IdempotentExecutor.hash(actor, resourceKey,
                String.valueOf(resource.version()), taskSet, leaseStart.toString(),
                leaseEnd.toString(), credentialUnion);
        return idem.run(leaseKey, "lease_assign", fingerprint, LeaseBatchView.class, () -> {
            // 1) 资源租约冲突：持有中租约与请求时段相交即冲突
            List<ResourceLease> conflicts =
                    leases.listConflicting(resource.id(), leaseStart, leaseEnd);
            if (!conflicts.isEmpty()) {
                throw ApiException.conflict("资源 " + resourceKey + " 在租约时段内存在冲突租约",
                        conflicts.stream().map(ResourceLease::leaseKey).toList());
            }
            // 2) 依赖门禁：任务状态可租、无持有中租约、阻塞全部解除
            for (LoadedTask loadedTask : loaded) {
                IncidentTask task = loadedTask.task();
                if (task.status() == TaskStatus.CREDENTIAL_RISK) {
                    throw ApiException.conflict("任务 " + task.taskKey()
                            + " 处于 CREDENTIAL_RISK，需先替换合格租约");
                }
                if (task.status() != TaskStatus.OPEN && task.status() != TaskStatus.IN_PROGRESS) {
                    throw ApiException.conflict("任务 " + task.taskKey() + " 已处于终态 "
                            + task.status() + "，不能分配租约");
                }
                if (leases.findHoldingByTask(task.id()).isPresent()) {
                    throw ApiException.conflict("任务 " + task.taskKey() + " 已持有生效租约");
                }
                List<String> unresolved = unresolvedBlockers(task.id());
                if (!unresolved.isEmpty()) {
                    throw ApiException.conflict("任务 " + task.taskKey() + " 存在未解除阻塞的事件: "
                            + String.join(",", unresolved), List.copyOf(unresolved));
                }
            }
            // 3) 全部资质后态：资源拥有全部必需资质且严格覆盖各任务计划完成时刻
            Map<String, ResourceCredential> byCode = resources.listCredentials(resource.id())
                    .stream().collect(Collectors.toMap(ResourceCredential::credentialCode,
                            Function.identity()));
            List<CredentialIssueView> issues = new ArrayList<>();
            for (LoadedTask loadedTask : loaded) {
                collectCredentialIssues(loadedTask, byCode, issues);
            }
            if (!issues.isEmpty()) {
                throw ApiException.credentialViolation(
                        "资源 " + resourceKey + " 资质不满足全部高危任务要求", issues);
            }
            // 4) 单事务创建全部租约
            Instant now = now();
            List<LeaseView> views = new ArrayList<>();
            for (LoadedTask loadedTask : loaded) {
                long leaseId = leases.insert(new ResourceLease(0L, leaseKey, resource.id(),
                        resource.version(), loadedTask.task().id(), loadedTask.requiredCredentials(),
                        leaseStart, leaseEnd, LeaseStatus.ACTIVE, null, actor, now, now));
                views.add(toLeaseView(leases.findHoldingByTask(loadedTask.task().id())
                        .filter(l -> l.id() == leaseId).orElseThrow(), loadedTask));
            }
            return new LeaseBatchView(leaseKey, resourceKey, views);
        });
    }

    /**
     * 以合格资源替换 CREDENTIAL_RISK 任务的当前租约：新资源必须拥有全部必需资质
     * 且严格覆盖任务计划完成时刻（否则 422），同时段无冲突租约（否则 409）；
     * 原租约置为 REPLACED，新租约沿用原时段生效，任务恢复到风险前状态。
     */
    @Transactional
    public LeaseView replaceLease(String incidentKey, String taskKey, String actor,
                                  LeaseReplaceRequest req) {
        String leaseKey = requireText(req.leaseKey(), "leaseKey");
        String resourceKey = requireText(req.resourceKey(), "resourceKey");
        requireText(actor, "actor");
        leases.lockDomain();
        Resource resource = lockResource(resourceKey);
        Incident incident = lockIncident(incidentKey);
        IncidentTask task = tasks.findByKey(incident.id(), requireText(taskKey, "taskKey"))
                .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskKey));
        List<String> required = tasks.listRequiredCredentials(task.id());
        var holding = leases.findHoldingByTask(task.id());
        String fingerprint = IdempotentExecutor.hash(actor, incidentKey, taskKey, resourceKey,
                String.valueOf(resource.version()),
                holding.map(l -> l.leaseStart().toString()).orElse(""),
                holding.map(l -> l.leaseEnd().toString()).orElse(""),
                String.join(",", required));
        return idem.run(leaseKey, "lease_replace", fingerprint, LeaseView.class, () -> {
            requireCommander(incident, actor);
            if (task.status() != TaskStatus.CREDENTIAL_RISK) {
                throw ApiException.conflict("任务 " + taskKey
                        + " 未处于 CREDENTIAL_RISK，不能替换租约");
            }
            ResourceLease old = holding.orElseThrow(() -> ApiException.conflict(
                    "任务 " + taskKey + " 没有可替换的持有中租约"));
            Map<String, ResourceCredential> byCode = resources.listCredentials(resource.id())
                    .stream().collect(Collectors.toMap(ResourceCredential::credentialCode,
                            Function.identity()));
            List<CredentialIssueView> issues = new ArrayList<>();
            collectCredentialIssues(new LoadedTask(incident, task, required), byCode, issues);
            if (!issues.isEmpty()) {
                throw ApiException.credentialViolation(
                        "资源 " + resourceKey + " 资质不满足任务 " + taskKey + " 的必需资质", issues);
            }
            List<ResourceLease> conflicts = leases.listConflicting(resource.id(),
                    old.leaseStart(), old.leaseEnd());
            if (!conflicts.isEmpty()) {
                throw ApiException.conflict("资源 " + resourceKey + " 在租约时段内存在冲突租约",
                        conflicts.stream().map(ResourceLease::leaseKey).toList());
            }
            Instant now = now();
            leases.updateStatus(old.id(), LeaseStatus.REPLACED, leaseKey, now);
            long newLeaseId = leases.insert(new ResourceLease(0L, leaseKey, resource.id(),
                    resource.version(), task.id(), required, old.leaseStart(), old.leaseEnd(),
                    LeaseStatus.ACTIVE, null, actor, now, now));
            tasks.restoreFromCredentialRisk(task.id(), now);
            IncidentTask restored = tasks.findById(task.id()).orElseThrow();
            return toLeaseView(leases.findHoldingByTask(task.id())
                    .filter(l -> l.id() == newLeaseId).orElseThrow(),
                    new LoadedTask(incident, restored, required));
        });
    }

    /**
     * 查询资源全部资质（含已撤销）与当前版本。只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public ResourceCredentialsView listCredentials(String resourceKey) {
        Resource resource = resources.findByKey(requireText(resourceKey, "resourceKey"))
                .orElseThrow(() -> ApiException.notFound("资源不存在: " + resourceKey));
        List<CredentialView> credentials = resources.listCredentials(resource.id()).stream()
                .map(ResourceLeaseService::toCredentialView).toList();
        return new ResourceCredentialsView(resource.resourceKey(), resource.version(), credentials);
    }

    /**
     * 查询资源处于 CREDENTIAL_RISK 的租约及关联不可变风险记录。只读。
     */
    @Transactional(readOnly = true)
    public RiskLeaseListView riskLeasesByResource(String resourceKey) {
        Resource resource = resources.findByKey(requireText(resourceKey, "resourceKey"))
                .orElseThrow(() -> ApiException.notFound("资源不存在: " + resourceKey));
        return toRiskLeaseList(leases.listRiskByResource(resource.id()));
    }

    /**
     * 查询事件下处于 CREDENTIAL_RISK 的租约及关联不可变风险记录。只读。
     */
    @Transactional(readOnly = true)
    public RiskLeaseListView riskLeasesByIncident(String incidentKey) {
        Incident incident = incidents.findByKey(requireText(incidentKey, "incidentKey"))
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        return toRiskLeaseList(leases.listRiskByIncident(incident.id()));
    }

    /**
     * 查询任务门禁原因：开始/完成准入结果、阻止原因、未解除阻塞事件与
     * 导致 CREDENTIAL_RISK 的被撤销资质。只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public TaskGateView taskGate(String incidentKey, String taskKey) {
        Incident incident = incidents.findByKey(requireText(incidentKey, "incidentKey"))
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        IncidentTask task = tasks.findByKey(incident.id(), requireText(taskKey, "taskKey"))
                .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskKey));
        List<String> unresolved = unresolvedBlockers(task.id());
        List<String> riskCredentials = List.of();
        if (task.status() == TaskStatus.CREDENTIAL_RISK) {
            riskCredentials = leases.findHoldingByTask(task.id())
                    .map(l -> leases.listRiskRecordsByLease(l.id()).stream()
                            .map(CredentialRiskRecord::credentialCode)
                            .distinct().sorted().toList())
                    .orElse(List.of());
        }
        List<String> reasons = new ArrayList<>();
        boolean canStart = false;
        boolean canComplete = false;
        switch (task.status()) {
            case OPEN -> {
                canStart = unresolved.isEmpty();
                canComplete = unresolved.isEmpty();
            }
            case IN_PROGRESS -> {
                reasons.add("任务已开始，不能重复开始");
                canComplete = unresolved.isEmpty();
            }
            case CREDENTIAL_RISK -> reasons.add(
                    "任务处于 CREDENTIAL_RISK：必需资质被提前撤销，需以合格资源替换租约后才能开始或完成");
            default -> reasons.add("任务已处于终态 " + task.status());
        }
        if (!unresolved.isEmpty()
                && (task.status() == TaskStatus.OPEN || task.status() == TaskStatus.IN_PROGRESS)) {
            reasons.add("存在未解除阻塞的事件: " + String.join(",", unresolved));
        }
        return new TaskGateView(incident.incidentKey(), task.taskKey(), task.status().name(),
                canStart, canComplete, List.copyOf(reasons), unresolved, riskCredentials);
    }

    /**
     * 释放任务持有中的租约（任务进入 DONE/CANCELLED 终态时由 IncidentService
     * 在同一事务内调用）。
     */
    public void releaseLeasesForTerminalTask(long taskId, Instant at) {
        leases.releaseHoldingByTask(taskId, at);
    }

    /** 加载并校验的租约目标任务。 */
    private record LoadedTask(Incident incident, IncidentTask task,
                              List<String> requiredCredentials) {
    }

    /**
     * 规范化任务集合：逐项非空校验、去重、按 (incidentKey, taskKey) 排序；
     * 集合换序视为同参。
     */
    private static List<LeaseTaskRef> normalizeTaskRefs(List<LeaseTaskRef> taskRefs) {
        if (taskRefs == null || taskRefs.isEmpty()) {
            throw ApiException.badRequest("tasks 不能为空");
        }
        Map<String, LeaseTaskRef> deduped = new LinkedHashMap<>();
        for (LeaseTaskRef ref : taskRefs) {
            if (ref == null) {
                throw ApiException.badRequest("tasks 不能包含空项");
            }
            String incidentKey = requireText(ref.incidentKey(), "incidentKey");
            String taskKey = requireText(ref.taskKey(), "taskKey");
            deduped.putIfAbsent(incidentKey + "\\u001F" + taskKey,
                    new LeaseTaskRef(incidentKey, taskKey));
        }
        List<LeaseTaskRef> refs = new ArrayList<>(deduped.values());
        refs.sort(Comparator.comparing(LeaseTaskRef::incidentKey)
                .thenComparing(LeaseTaskRef::taskKey));
        if (refs.size() > MAX_TASKS_PER_BATCH) {
            throw ApiException.badRequest("单批租约任务最多 " + MAX_TASKS_PER_BATCH + " 个");
        }
        return refs;
    }

    /**
     * 按事件键排序锁定事件行并加载任务：任务必须存在且为声明了必需资质
     * 与计划完成时刻的高危任务。
     */
    private List<LoadedTask> loadTasks(List<LeaseTaskRef> refs) {
        List<String> incidentKeys = refs.stream().map(LeaseTaskRef::incidentKey)
                .distinct().sorted().toList();
        Map<String, Incident> locked = new LinkedHashMap<>();
        for (String incidentKey : incidentKeys) {
            Incident incident = incidents.lockByKey(incidentKey)
                    .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
            locked.put(incidentKey, incident);
        }
        List<LoadedTask> loaded = new ArrayList<>();
        for (LeaseTaskRef ref : refs) {
            Incident incident = locked.get(ref.incidentKey());
            IncidentTask task = tasks.findByKey(incident.id(), ref.taskKey())
                    .orElseThrow(() -> ApiException.notFound("任务不存在: "
                            + ref.incidentKey() + "/" + ref.taskKey()));
            List<String> required = tasks.listRequiredCredentials(task.id());
            if (required.isEmpty() || task.plannedCompleteAt() == null) {
                throw ApiException.badRequest("任务 " + ref.incidentKey() + "/" + ref.taskKey()
                        + " 未声明必需资质，不是高危任务");
            }
            loaded.add(new LoadedTask(incident, task, required));
        }
        return loaded;
    }

    /**
     * 收集单个任务的资质问题：未登记或已撤销记 MISSING；有效期未严格覆盖
     * 任务计划完成时刻记 NOT_COVERING。
     */
    private static void collectCredentialIssues(LoadedTask loadedTask,
                                                Map<String, ResourceCredential> byCode,
                                                List<CredentialIssueView> issues) {
        IncidentTask task = loadedTask.task();
        for (String code : loadedTask.requiredCredentials()) {
            ResourceCredential credential = byCode.get(code);
            if (credential == null || credential.revoked()) {
                issues.add(new CredentialIssueView(loadedTask.incident().incidentKey(),
                        task.taskKey(), code, "MISSING", null, null));
            } else if (!credential.covers(task.plannedCompleteAt())) {
                issues.add(new CredentialIssueView(loadedTask.incident().incidentKey(),
                        task.taskKey(), code, "NOT_COVERING", credential.validFrom(),
                        credential.validUntil()));
            }
        }
    }

    /**
     * 仍未解除阻塞的事件键列表：目标事件未进入 CONTAINED/RESOLVED/CLOSED 即未解除。
     */
    private List<String> unresolvedBlockers(long taskId) {
        return incidents.listBlockingIncidents(taskId).stream()
                .filter(b -> !UNBLOCKING_STATUSES.contains(b.status()))
                .map(Incident::incidentKey)
                .toList();
    }

    private Resource lockResource(String resourceKey) {
        requireText(resourceKey, "resourceKey");
        return resources.lockByKey(resourceKey)
                .orElseThrow(() -> ApiException.notFound("资源不存在: " + resourceKey));
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

    private RiskLeaseListView toRiskLeaseList(List<ResourceLease> riskLeases) {
        Map<Long, List<CredentialRiskRecord>> recordsByLease = leases
                .listRiskRecordsByLeases(riskLeases.stream().map(ResourceLease::id).toList())
                .stream().collect(Collectors.groupingBy(CredentialRiskRecord::leaseId));
        List<RiskLeaseView> views = new ArrayList<>();
        for (ResourceLease lease : riskLeases) {
            IncidentTask task = tasks.findById(lease.taskId()).orElseThrow();
            Incident incident = incidents.findById(task.incidentId()).orElseThrow();
            Resource resource = resources.findById(lease.resourceId()).orElseThrow();
            List<RiskRecordView> records = recordsByLease
                    .getOrDefault(lease.id(), List.of()).stream()
                    .map(r -> new RiskRecordView(r.leaseId(), resource.resourceKey(),
                            incident.incidentKey(), task.taskKey(), r.credentialCode(),
                            r.revokedAt(), r.detectedAt()))
                    .toList();
            views.add(new RiskLeaseView(
                    toLeaseView(lease, new LoadedTask(incident, task,
                            tasks.listRequiredCredentials(task.id())), resource.resourceKey()),
                    records));
        }
        return new RiskLeaseListView(views);
    }

    private LeaseView toLeaseView(ResourceLease lease, LoadedTask loadedTask) {
        Resource resource = resources.findById(lease.resourceId()).orElseThrow();
        return toLeaseView(lease, loadedTask, resource.resourceKey());
    }

    private LeaseView toLeaseView(ResourceLease lease, LoadedTask loadedTask, String resourceKey) {
        return new LeaseView(lease.leaseKey(), resourceKey, lease.resourceVersion(),
                loadedTask.incident().incidentKey(), loadedTask.task().taskKey(),
                lease.credentialCodes(), lease.leaseStart(), lease.leaseEnd(),
                lease.status().name(), lease.replacedBy(), lease.operator(), lease.createdAt());
    }

    private static ResourceView toResourceView(Resource resource) {
        return new ResourceView(resource.resourceKey(), resource.version(), resource.createdBy(),
                resource.createdAt(), resource.updatedAt());
    }

    private static CredentialView toCredentialView(ResourceCredential credential) {
        return new CredentialView(credential.credentialCode(), credential.validFrom(),
                credential.validUntil(), credential.revoked(), credential.revokedAt(),
                credential.createdAt());
    }
}
