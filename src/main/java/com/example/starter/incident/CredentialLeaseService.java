package com.example.starter.incident;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.starter.incident.dto.Requests.CredentialRegisterRequest;
import com.example.starter.incident.dto.Requests.CredentialRevokeRequest;
import com.example.starter.incident.dto.Requests.LeaseAllocateRequest;
import com.example.starter.incident.dto.Requests.LeaseItem;
import com.example.starter.incident.dto.Requests.LeaseReplaceRequest;
import com.example.starter.incident.dto.Responses.CredentialRevokeView;
import com.example.starter.incident.dto.Responses.CredentialRiskView;
import com.example.starter.incident.dto.Responses.CredentialView;
import com.example.starter.incident.dto.Responses.LeaseAllocateView;
import com.example.starter.incident.dto.Responses.LeaseView;
import com.example.starter.incident.dto.Responses.ResourceCredentialsView;
import com.example.starter.incident.dto.Responses.RiskLeasesView;
import com.example.starter.incident.dto.Responses.TaskGateView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 事件资源资质租约核心服务。
 *
 * <p>裁决顺序：资质撤销、租约分配、替换、开始和完成均先持有 lease_lock 单行全局锁，
 * 再锁事件行，按事务提交顺序串行裁决。
 *
 * <p>批量分配：一个请求为多个高危任务分配资源时，先统一校验租约冲突、依赖门禁与
 * 全部资质后态，再单事务创建全部租约，任一任务失败整单回滚（失败不占 commandKey）。
 *
 * <p>资质撤销：提前撤销时，未来有效的高危租约对应任务转为 CREDENTIAL_RISK 并写入
 * 不可变风险记录；已完成（DONE）任务不改写。处于 CREDENTIAL_RISK 的任务不得开始或
 * 完成，直到以合格资源替换租约；依赖满足也不能绕过此门禁。
 */
@Service
public class CredentialLeaseService {

    /** 依赖门禁视为已解除的目标事件状态。 */
    private static final java.util.Set<IncidentStatus> UNBLOCKING = java.util.EnumSet.of(
            IncidentStatus.CONTAINED, IncidentStatus.RESOLVED, IncidentStatus.CLOSED);

    private final IncidentRepository incidents;
    private final IncidentTaskRepository tasks;
    private final ResourceCredentialRepository credentials;
    private final ResourceLeaseRepository leases;
    private final CredentialCoverage coverage;
    private final IdempotencyRunner idempotency;
    private final Clock clock;

    public CredentialLeaseService(IncidentRepository incidents, IncidentTaskRepository tasks,
                                  ResourceCredentialRepository credentials,
                                  ResourceLeaseRepository leases, CredentialCoverage coverage,
                                  IdempotencyRunner idempotency, Clock clock) {
        this.incidents = incidents;
        this.tasks = tasks;
        this.credentials = credentials;
        this.leases = leases;
        this.coverage = coverage;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    private Instant now() {
        return clock.instant();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(field + " 不能为空");
        }
        return value.strip();
    }

    // ---------------------------------------------------------------------
    // 资质登记 / 撤销
    // ---------------------------------------------------------------------

    /**
     * 登记资源资质：(resourceId, credentialCode) 首次登记返回 201 语义视图；
     * 同键重登（续期）要求资质仍 ACTIVE，更新有效期且版本 +1；已撤销资质拒绝重登（409）。
     * validUntil 必填且须晚于当前时刻；validFrom 可空表示登记即生效。
     */
    @Transactional
    public CredentialView register(CredentialRegisterRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String resourceId = requireText(req.resourceId(), "resourceId");
        String credentialCode = requireText(req.credentialCode(), "credentialCode");
        if (req.validUntil() == null) {
            throw ApiException.badRequest("validUntil 不能为空");
        }
        Instant validUntil = req.validUntil().truncatedTo(ChronoUnit.MICROS);
        Instant validFrom = req.validFrom() == null ? null
                : req.validFrom().truncatedTo(ChronoUnit.MICROS);
        if (!validUntil.isAfter(now())) {
            throw ApiException.badRequest("validUntil 必须晚于当前时刻");
        }
        leases.lockLeases();
        return idempotency.run(commandKey, "credential_register",
                IdempotencyRunner.hash(resourceId, credentialCode,
                        validFrom == null ? "" : validFrom.toString(), validUntil.toString()),
                CredentialView.class, () -> {
                    Instant at = now();
                    var existing = credentials.find(resourceId, credentialCode);
                    if (existing.isEmpty()) {
                        credentials.insert(new ResourceCredential(0L, resourceId, credentialCode,
                                validFrom, validUntil, CredentialStatus.ACTIVE, 1L, null, null,
                                null, at, at));
                    } else {
                        ResourceCredential found = existing.get();
                        if (found.status() == CredentialStatus.REVOKED) {
                            throw ApiException.conflict(
                                    "资质已撤销，不能续期: " + resourceId + "/" + credentialCode);
                        }
                        credentials.renew(found.id(), validFrom, validUntil, found.version() + 1, at);
                    }
                    return toCredentialView(
                            credentials.find(resourceId, credentialCode).orElseThrow());
                });
    }

    /**
     * 提前撤销资源资质：仅 ACTIVE 可撤销，版本 +1；随后在同一把全局租约锁内扫描该资源
     * 未来有效的当前租约，对应非终态任务转 CREDENTIAL_RISK 并写不可变风险记录，
     * 已完成（DONE）任务不改写。撤销与分配/开始/完成按提交顺序裁决。
     */
    @Transactional
    public CredentialRevokeView revoke(String resourceIdArg, String credentialCodeArg,
                                       String actorArg, CredentialRevokeRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        final String reason = requireText(req.reason(), "reason");
        final String resourceId = requireText(resourceIdArg, "resourceId");
        final String credentialCode = requireText(credentialCodeArg, "credentialCode");
        final String actor = requireText(actorArg, "X-Actor-Id");
        leases.lockLeases();
        return idempotency.run(commandKey, "credential_revoke",
                IdempotencyRunner.hash(resourceId, credentialCode, reason),
                CredentialRevokeView.class, () -> {
                    ResourceCredential found = credentials.find(resourceId, credentialCode)
                            .orElseThrow(() -> ApiException.notFound(
                                    "资源资质不存在: " + resourceId + "/" + credentialCode));
                    Instant at = now();
                    List<CredentialRiskView> triggered = new ArrayList<>();
                    if (found.status() == CredentialStatus.ACTIVE) {
                        int updated = credentials.revoke(found.id(), actor, at, reason,
                                found.version() + 1, at);
                        if (updated == 0) {
                            throw ApiException.conflict("资质已被并发撤销");
                        }
                        // 扫描未来有效的当前租约：lease_end 严格晚于撤销时刻
                        for (ResourceLease lease : leases.listCurrentByResourceValidAfter(
                                resourceId, at)) {
                            if (!lease.requiredCredentials().contains(credentialCode)) {
                                continue;
                            }
                            IncidentTask task = tasks.findById(lease.taskId()).orElse(null);
                            if (task == null || task.status() == TaskStatus.DONE
                                    || task.status() == TaskStatus.CANCELLED) {
                                // 已完成/已取消不改写、不记风险
                                continue;
                            }
                            long riskId = leases.insertRisk(new CredentialRisk(0L, lease.id(),
                                    task.id(), task.incidentId(), resourceId, credentialCode,
                                    reason, actor, at, at));
                            if (task.status() != TaskStatus.CREDENTIAL_RISK) {
                                tasks.markCredentialRisk(task.id(), task.status(), at);
                            }
                            triggered.add(new CredentialRiskView(riskId, lease.id(),
                                    incidents.findById(task.incidentId()).orElseThrow()
                                            .incidentKey(),
                                    task.taskKey(), resourceId, credentialCode, reason, actor,
                                    at, at));
                        }
                    }
                    ResourceCredential reloaded =
                            credentials.find(resourceId, credentialCode).orElseThrow();
                    return new CredentialRevokeView(toCredentialView(reloaded), triggered);
                });
    }

    // ---------------------------------------------------------------------
    // 租约分配 / 替换
    // ---------------------------------------------------------------------

    /**
     * 批量分配租约：先校验（空单/重复任务、任务存在且为高危、任务状态、已存在租约、
     * 租约时段、依赖门禁、资源租约冲突、全部资质后态），全部通过后单事务创建全部租约；
     * 任一任务失败整单回滚。leaseKey 指纹含操作者（X-Actor-Id）、资源版本、
     * 规范化任务集合、租约时段与资质集合。
     */
    @Transactional
    public LeaseAllocateView allocate(String actorArg, LeaseAllocateRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        final String actor = requireText(actorArg, "X-Actor-Id");
        List<LeaseItem> items = req.items() == null ? List.of() : req.items();
        if (items.isEmpty()) {
            throw ApiException.badRequest("items 不能为空");
        }
        leases.lockLeases();
        // 规范化任务集合：按事件键+任务键字典序，集合换序视为同参
        List<LeaseItem> canonical = canonicalize(items);
        String requestHash = allocateFingerprint(actor, canonical);
        return idempotency.run(commandKey, "lease_allocate", requestHash,
                LeaseAllocateView.class, () -> doAllocate(actor, canonical));
    }

    private LeaseAllocateView doAllocate(String actor, List<LeaseItem> items) {
        Instant at = now();
        List<ResourceLease> created = new ArrayList<>();
        for (LeaseItem item : items) {
            Incident incident = incidents.findByKey(item.incidentKey())
                    .orElseThrow(() -> ApiException.notFound(
                            "事件不存在: " + item.incidentKey()));
            IncidentTask task = tasks.findByKey(incident.id(), item.taskKey())
                    .orElseThrow(() -> ApiException.notFound(
                            "任务不存在: " + item.incidentKey() + "/" + item.taskKey()));
            if (task.requiredCredentials().isEmpty()) {
                throw ApiException.unprocessable("任务不是高危任务，无需资质租约: "
                        + item.taskKey());
            }
            if (task.status() == TaskStatus.DONE || task.status() == TaskStatus.CANCELLED) {
                throw ApiException.unprocessable("任务已处于终态，不能分配租约: "
                        + item.taskKey());
            }
            if (task.status() == TaskStatus.CREDENTIAL_RISK) {
                throw ApiException.unprocessable(
                        "任务处于资质风险门禁，请使用替换租约接口: " + item.taskKey());
            }
            if (leases.findCurrentByTask(task.id()).isPresent()) {
                throw ApiException.conflict("任务已存在当前租约: " + item.taskKey());
            }
            Instant start = item.leaseStart() == null ? at
                    : item.leaseStart().truncatedTo(ChronoUnit.MICROS);
            Instant end = item.leaseEnd() == null ? null
                    : item.leaseEnd().truncatedTo(ChronoUnit.MICROS);
            if (end == null) {
                throw ApiException.badRequest("leaseEnd 不能为空: " + item.taskKey());
            }
            if (!end.isAfter(start)) {
                throw ApiException.badRequest(
                        "leaseEnd 必须晚于 leaseStart: " + item.taskKey());
            }
            // 依赖门禁：阻塞事件未解除不能为高危任务分配租约
            List<String> unresolved = unresolvedBlockers(task);
            if (!unresolved.isEmpty()) {
                throw ApiException.conflict("存在未解除阻塞的事件: "
                        + String.join(",", unresolved), List.copyOf(unresolved));
            }
            // 资源租约冲突：同一资源时段重叠（含本批内已分配项）
            List<ResourceLease> overlapping = leases.listCurrentOverlapping(
                    item.resourceId(), start, end);
            for (ResourceLease lease : created) {
                if (lease.resourceId().equals(item.resourceId())
                        && lease.leaseStart().isBefore(end) && start.isBefore(lease.leaseEnd())) {
                    overlapping = new ArrayList<>(overlapping);
                    overlapping.add(lease);
                }
            }
            if (!overlapping.isEmpty()) {
                throw ApiException.conflict("资源在该时段已存在冲突租约: " + item.resourceId()
                        + " 任务 " + overlapping.get(0).taskId());
            }
            // 全部资质后态：资源拥有全部必需资质且有效期严格覆盖计划完成时刻
            List<ResourceCredential> owned = credentials.listByResource(item.resourceId());
            long resourceVersion = owned.stream().mapToLong(ResourceCredential::version).max()
                    .orElse(0L);
            ResourceLease candidate = new ResourceLease(0L, task.id(), item.resourceId(),
                    resourceVersion, start, end, task.requiredCredentials(), LeaseStatus.ACTIVE,
                    1L, actor, at, null, null);
            CredentialCoverage.Failure failure = coverage.evaluate(candidate, at);
            if (failure.hasFailure()) {
                throw ApiException.credentialNotCovered(
                        "资源资质不满足任务要求: " + item.taskKey(),
                        Map.of("incidentKey", item.incidentKey(), "taskKey", item.taskKey(),
                                "resourceId", item.resourceId(), "missing", failure.missing(),
                                "expired", failure.expired()));
            }
            created.add(candidate);
        }
        List<LeaseView> views = new ArrayList<>();
        for (ResourceLease lease : created) {
            long id = leases.insert(lease);
            views.add(toLeaseView(leases.findById(id).orElseThrow()));
        }
        return new LeaseAllocateView(views);
    }

    /**
     * 替换租约：以合格新资源替换任务当前租约；仅任务当前处于 CREDENTIAL_RISK 时需要替换。
     * 旧租约置 REPLACED、新租约校验资质全部覆盖后插入，并清除任务风险门禁
     * （恢复 OPEN/IN_PROGRESS）。同键同参重放首次响应。
     */
    @Transactional
    public LeaseView replace(String incidentKeyArg, String taskKeyArg, String actorArg,
                             LeaseReplaceRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        final String actor = requireText(actorArg, "X-Actor-Id");
        final String resourceId = requireText(req.resourceId(), "resourceId");
        final String incidentKey = requireText(incidentKeyArg, "incidentKey");
        final String taskKey = requireText(taskKeyArg, "taskKey");
        leases.lockLeases();
        final Instant start0 = req.leaseStart() == null ? null
                : req.leaseStart().truncatedTo(ChronoUnit.MICROS);
        final Instant end0 = req.leaseEnd() == null ? null
                : req.leaseEnd().truncatedTo(ChronoUnit.MICROS);
        return idempotency.run(commandKey, "lease_replace",
                IdempotencyRunner.hash(incidentKey, taskKey, actor, resourceId,
                        start0 == null ? "" : start0.toString(),
                        end0 == null ? "" : end0.toString()),
                LeaseView.class, () -> {
                    Incident incident = incidents.findByKey(incidentKey)
                            .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
                    IncidentTask task = tasks.findByKey(incident.id(), taskKey)
                            .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskKey));
                    ResourceLease old = leases.lockCurrentByTask(task.id())
                            .orElseThrow(() -> ApiException.notFound(
                                    "任务没有可替换的当前租约: " + taskKey));
                    if (task.status() != TaskStatus.CREDENTIAL_RISK) {
                        throw ApiException.unprocessable(
                                "任务不处于资质风险门禁，无需替换租约: " + taskKey);
                    }
                    Instant at = now();
                    Instant start = start0 == null ? at : start0;
                    if (end0 == null) {
                        throw ApiException.badRequest("leaseEnd 不能为空");
                    }
                    if (!end0.isAfter(start)) {
                        throw ApiException.badRequest("leaseEnd 必须晚于 leaseStart");
                    }
                    // 资源租约冲突：排除本任务被替换的旧租约（其窗口与新租约天然重叠）
                    List<ResourceLease> overlapping = leases.listCurrentOverlapping(
                            resourceId, start, end0).stream()
                            .filter(l -> l.taskId() != task.id())
                            .toList();
                    if (!overlapping.isEmpty()) {
                        throw ApiException.conflict(
                                "资源在该时段已存在冲突租约: " + resourceId);
                    }
                    List<ResourceCredential> owned = credentials.listByResource(resourceId);
                    long resourceVersion = owned.stream().mapToLong(ResourceCredential::version)
                            .max().orElse(0L);
                    ResourceLease candidate = new ResourceLease(0L, task.id(), resourceId,
                            resourceVersion, start, end0, task.requiredCredentials(),
                            LeaseStatus.ACTIVE, 1L, actor, at, null, null);
                    CredentialCoverage.Failure failure = coverage.evaluate(candidate, at);
                    if (failure.hasFailure()) {
                        throw ApiException.credentialNotCovered(
                                "替换资源资质不满足任务要求: " + taskKey,
                                Map.of("incidentKey", incidentKey, "taskKey", taskKey,
                                        "resourceId", resourceId, "missing", failure.missing(),
                                        "expired", failure.expired()));
                    }
                    leases.markReplaced(old.id(), actor, at);
                    long id = leases.insert(candidate);
                    TaskStatus restore = task.preRiskStatus() == null
                            ? TaskStatus.IN_PROGRESS : task.preRiskStatus();
                    tasks.clearCredentialRisk(task.id(), restore, at);
                    return toLeaseView(leases.findById(id).orElseThrow());
                });
    }

    // ---------------------------------------------------------------------
    // 查询：资源资质 / 风险租约 / 任务门禁原因
    // ---------------------------------------------------------------------

    /**
     * 查询资源全部资质，按资质代码字典序返回。只读。
     */
    @Transactional(readOnly = true)
    public ResourceCredentialsView listCredentials(String resourceId) {
        resourceId = requireText(resourceId, "resourceId");
        List<CredentialView> views = credentials.listByResource(resourceId).stream()
                .map(this::toCredentialView).toList();
        return new ResourceCredentialsView(resourceId, views);
    }

    /**
     * 查询事件全部资质风险记录（风险租约），按写入顺序返回。只读。
     */
    @Transactional(readOnly = true)
    public RiskLeasesView listRisks(String incidentKey) {
        Incident incident = incidents.findByKey(requireText(incidentKey, "incidentKey"))
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        List<CredentialRiskView> views = leases.listRisksByIncident(incident.id()).stream()
                .map(this::toRiskView).toList();
        return new RiskLeasesView(incidentKey, views);
    }

    /**
     * 查询任务门禁原因：给出当前是否处于资质风险、可否开始/完成及人读原因列表。
     * 只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public TaskGateView taskGate(String incidentKey, String taskKey) {
        Incident incident = incidents.findByKey(requireText(incidentKey, "incidentKey"))
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        IncidentTask task = tasks.findByKey(incident.id(), requireText(taskKey, "taskKey"))
                .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskKey));
        List<String> reasons = new ArrayList<>();
        boolean risk = task.status() == TaskStatus.CREDENTIAL_RISK;
        if (risk) {
            reasons.add("CREDENTIAL_RISK: 资源资质已被撤销，必须以合格资源替换租约后才能开始或完成");
        }
        List<String> unresolved = unresolvedBlockers(task);
        if (!unresolved.isEmpty()) {
            reasons.add("依赖门禁未解除: " + String.join(",", unresolved));
        }
        boolean canStart = !risk && task.status() == TaskStatus.OPEN && unresolved.isEmpty()
                && startCredentialReady(task);
        boolean canComplete = !risk && task.status() == TaskStatus.IN_PROGRESS
                && unresolved.isEmpty();
        if (task.status() == TaskStatus.OPEN && !task.requiredCredentials().isEmpty()) {
            var lease = leases.findCurrentByTask(task.id());
            if (lease.isEmpty()) {
                reasons.add("高危任务尚未分配资质租约");
            } else {
                CredentialCoverage.Failure failure = coverage.evaluate(lease.get(), now());
                if (failure.hasFailure()) {
                    reasons.add("租约资质不满足: missing=" + failure.missing()
                            + " expired=" + failure.expired());
                }
            }
        }
        if (task.status() == TaskStatus.DONE || task.status() == TaskStatus.CANCELLED) {
            reasons.add("任务已处于终态: " + task.status());
        }
        return new TaskGateView(incidentKey, taskKey, task.status().name(), risk, canStart,
                canComplete, List.copyOf(reasons));
    }

    // ---------------------------------------------------------------------
    // 辅助方法
    // ---------------------------------------------------------------------

    private boolean startCredentialReady(IncidentTask task) {
        if (task.requiredCredentials().isEmpty()) {
            return true;
        }
        var lease = leases.findCurrentByTask(task.id());
        return lease.isPresent() && !coverage.evaluate(lease.get(), now()).hasFailure();
    }

    private List<String> unresolvedBlockers(IncidentTask task) {
        return incidents.listBlockingIncidents(task.id()).stream()
                .filter(b -> !UNBLOCKING.contains(b.status()))
                .map(Incident::incidentKey).toList();
    }

    /**
     * 规范化批量项：校验时段字段、按 (incidentKey,taskKey) 去重并字典序排序；
     * 同键出现不同内容（资源/时段）报 400。集合换序视为同参。
     */
    private List<LeaseItem> canonicalize(List<LeaseItem> items) {
        Map<String, LeaseItem> byTask = new LinkedHashMap<>();
        for (LeaseItem raw : items) {
            if (raw == null) {
                throw ApiException.badRequest("items 含空元素");
            }
            String incidentKey = requireText(raw.incidentKey(), "incidentKey");
            String taskKey = requireText(raw.taskKey(), "taskKey");
            String resourceId = requireText(raw.resourceId(), "resourceId");
            if (raw.leaseStart() == null || raw.leaseEnd() == null) {
                throw ApiException.badRequest(
                        "leaseStart/leaseEnd 不能为空: " + taskKey);
            }
            Instant start = raw.leaseStart().truncatedTo(ChronoUnit.MICROS);
            Instant end = raw.leaseEnd().truncatedTo(ChronoUnit.MICROS);
            LeaseItem item = new LeaseItem(incidentKey, taskKey, resourceId, start, end);
            String mapKey = incidentKey + "\u001F" + taskKey;
            LeaseItem previous = byTask.putIfAbsent(mapKey, item);
            if (previous != null && !previous.equals(item)) {
                throw ApiException.badRequest("同一任务在批量中出现且参数不一致: " + taskKey);
            }
        }
        return byTask.values().stream()
                .sorted((a, b) -> (a.incidentKey() + "\u001F" + a.taskKey())
                        .compareTo(b.incidentKey() + "\u001F" + b.taskKey()))
                .toList();
    }

    /**
     * leaseKey 请求指纹：操作者 + 规范化任务集合（每项含任务键、资源、资源版本后态、
     * 租约时段与任务必需资质集合）。资源版本在分配时取资源当前版本，纳入指纹。
     */
    private String allocateFingerprint(String actor, List<LeaseItem> canonical) {
        List<String> parts = new ArrayList<>();
        parts.add(actor);
        for (LeaseItem item : canonical) {
            Incident incident = incidents.findByKey(item.incidentKey()).orElse(null);
            IncidentTask task = incident == null ? null
                    : tasks.findByKey(incident.id(), item.taskKey()).orElse(null);
            String required = task == null ? ""
                    : CredentialCodec.encode(task.requiredCredentials());
            long version = credentials.listByResource(item.resourceId()).stream()
                    .mapToLong(ResourceCredential::version).max().orElse(0L);
            parts.add(String.join("\u001F", item.incidentKey(), item.taskKey(),
                    item.resourceId(), Long.toString(version),
                    item.leaseStart().toString(), item.leaseEnd().toString(), required));
        }
        return IdempotencyRunner.hash(parts.toArray(String[]::new));
    }

    private CredentialView toCredentialView(ResourceCredential c) {
        return new CredentialView(c.resourceId(), c.credentialCode(), c.validFrom(),
                c.validUntil(), c.status().name(), c.version(), c.revokedBy(), c.revokedAt(),
                c.revokeReason());
    }

    private LeaseView toLeaseView(ResourceLease lease) {
        IncidentTask task = tasks.findById(lease.taskId()).orElseThrow();
        Incident incident = incidents.findById(task.incidentId()).orElseThrow();
        return new LeaseView(lease.id(), incident.incidentKey(), task.taskKey(),
                lease.resourceId(), lease.resourceVersion(), lease.leaseStart(), lease.leaseEnd(),
                lease.requiredCredentials(), lease.status().name(),
                lease.currentFlag() != null && lease.currentFlag() == 1L,
                lease.createdBy(), lease.createdAt());
    }

    private CredentialRiskView toRiskView(CredentialRisk risk) {
        IncidentTask task = tasks.findById(risk.taskId()).orElseThrow();
        Incident incident = incidents.findById(risk.incidentId()).orElseThrow();
        return new CredentialRiskView(risk.id(), risk.leaseId(), incident.incidentKey(),
                task.taskKey(), risk.resourceId(), risk.credentialCode(), risk.reason(),
                risk.triggeredBy(), risk.triggeredAt(), risk.createdAt());
    }
}
