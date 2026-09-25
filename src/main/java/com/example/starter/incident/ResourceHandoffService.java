package com.example.starter.incident;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.example.starter.incident.ResourceHandoffRepository.UnsettledOutgoing;
import com.example.starter.incident.dto.Requests.DelegateRegisterRequest;
import com.example.starter.incident.dto.Requests.HandoffCreateRequest;
import com.example.starter.incident.dto.Requests.HandoffItemRequest;
import com.example.starter.incident.dto.Requests.HandoffSettleRequest;
import com.example.starter.incident.dto.Requests.ResourceAcquireRequest;
import com.example.starter.incident.dto.Responses.CloseBlockerView;
import com.example.starter.incident.dto.Responses.CloseBlockersView;
import com.example.starter.incident.dto.Responses.DelegateView;
import com.example.starter.incident.dto.Responses.HandoffItemView;
import com.example.starter.incident.dto.Responses.HandoffSettleView;
import com.example.starter.incident.dto.Responses.HandoffView;
import com.example.starter.incident.dto.Responses.IncidentDelegatesView;
import com.example.starter.incident.dto.Responses.IncidentHandoffsView;
import com.example.starter.incident.dto.Responses.IncidentResourcesView;
import com.example.starter.incident.dto.Responses.IncidentSettlementsView;
import com.example.starter.incident.dto.Responses.ResourceResponsibilityView;
import com.example.starter.incident.dto.Responses.ResourceView;
import com.example.starter.incident.dto.Responses.SettlementView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 跨事件互助资源交接服务。
 * 并发约定：交接创建先按事件键序锁定双方事件行、再按资源键序锁定资源行；
 * 交接结束（目标关闭/租约到期）与任务终态结算均在已锁定事件行的事务内锁定交接行，
 * 保证交接、任务开始、两侧关闭与租约结算按事务提交顺序裁决。
 * 幂等约定：handoffKey 全局唯一，指纹含两事件版本、资源、时段与操作者，
 * 同键同参重放首次响应，失败整体回滚不占键。
 */
@Service
public class ResourceHandoffService {

    private final IncidentRepository incidents;
    private final IncidentTaskRepository tasks;
    private final ResourceHandoffRepository handoffs;
    private final IdempotentExecutor idempotency;
    private final Clock clock;

    public ResourceHandoffService(IncidentRepository incidents, IncidentTaskRepository tasks,
                                  ResourceHandoffRepository handoffs,
                                  IdempotentExecutor idempotency, Clock clock) {
        this.incidents = incidents;
        this.tasks = tasks;
        this.handoffs = handoffs;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    private Instant now() {
        return clock.instant();
    }

    // ---------- 资源登记 ----------

    /**
     * 登记资源：仅当前指挥人；事件 CLOSED 后禁止；resourceKey 全局唯一，重复 409。
     */
    @Transactional
    public ResourceView acquireResource(String incidentKey, String actor,
                                        ResourceAcquireRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String resourceKey = requireText(req.resourceKey(), "resourceKey");
        Incident incident = lockIncident(incidentKey);
        return idempotency.run(commandKey, "resource_acquire",
                IdempotentExecutor.hash(incidentKey, actor, resourceKey), ResourceView.class,
                () -> {
                    requireCommander(incident, actor);
                    if (incident.status() == IncidentStatus.CLOSED) {
                        throw ApiException.illegalTransition("事件已关闭，不能再登记资源");
                    }
                    if (handoffs.findResource(resourceKey).isPresent()) {
                        throw ApiException.conflict("resourceKey 已存在: " + resourceKey);
                    }
                    try {
                        handoffs.insertResource(new IncidentResource(0L, resourceKey,
                                incident.id(), actor, now()));
                    } catch (DuplicateKeyException e) {
                        throw ApiException.conflict("resourceKey 已存在: " + resourceKey);
                    }
                    IncidentResource saved = handoffs.findResource(resourceKey).orElseThrow();
                    return toResourceView(saved, incident.incidentKey());
                });
    }

    /**
     * 查询事件登记持有的全部资源。只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public IncidentResourcesView listResources(String incidentKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        List<ResourceView> views = handoffs.listResourcesByHolder(incident.id()).stream()
                .map(r -> toResourceView(r, incident.incidentKey())).toList();
        return new IncidentResourcesView(incident.incidentKey(), views);
    }

    // ---------- 代理人登记 ----------

    /**
     * 登记代理人：仅当前指挥人；事件 CLOSED 后禁止；代理人不得为当前指挥人本人；
     * 同人重复登记幂等返回首次记录。
     */
    @Transactional
    public DelegateView registerDelegate(String incidentKey, String actor,
                                         DelegateRegisterRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String delegate = requireText(req.delegate(), "delegate");
        Incident incident = lockIncident(incidentKey);
        return idempotency.run(commandKey, "delegate_register",
                IdempotentExecutor.hash(incidentKey, actor, delegate), DelegateView.class,
                () -> {
                    requireCommander(incident, actor);
                    if (incident.status() == IncidentStatus.CLOSED) {
                        throw ApiException.illegalTransition("事件已关闭，不能再登记代理人");
                    }
                    if (delegate.equals(incident.commander())) {
                        throw ApiException.badRequest("代理人不能是当前指挥人本人: " + delegate);
                    }
                    var existing = handoffs.findDelegate(incident.id(), delegate);
                    if (existing.isPresent()) {
                        return toDelegateView(existing.get());
                    }
                    try {
                        handoffs.insertDelegate(new IncidentDelegate(0L, incident.id(), delegate,
                                actor, now()));
                    } catch (DuplicateKeyException e) {
                        return toDelegateView(
                                handoffs.findDelegate(incident.id(), delegate).orElseThrow());
                    }
                    return toDelegateView(handoffs.findDelegate(incident.id(), delegate)
                            .orElseThrow());
                });
    }

    /**
     * 查询事件全部代理人。只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public IncidentDelegatesView listDelegates(String incidentKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        List<DelegateView> views = handoffs.listDelegates(incident.id()).stream()
                .map(ResourceHandoffService::toDelegateView).toList();
        return new IncidentDelegatesView(incident.incidentKey(), views);
    }

    // ---------- 互助交接 ----------

    /**
     * 创建互助交接（可批量多资源）：仅来源事件当前指挥人可发起；
     * 目标不得等于来源，租约左闭右开且结束晚于开始，两事件均未关闭；
     * 接收人须为目标事件当前指挥人或其已登记代理人；
     * 双方事件版本须与当前一致（乐观校验，不一致 409）；
     * 批量先校验资源最终归属（来源仍持有、批内不重复）与重叠租约（无未结算借出），
     * 任一冲突 422，全部资源与任务引用整体回滚。
     */
    @Transactional
    public HandoffView createHandoff(String sourceKey, String actor, HandoffCreateRequest req) {
        String handoffKey = requireText(req.handoffKey(), "handoffKey");
        String targetKey = requireText(req.targetIncidentKey(), "targetIncidentKey");
        String receiver = requireText(req.receiver(), "receiver");
        if (req.sourceVersion() == null) {
            throw ApiException.badRequest("sourceVersion 不能为空");
        }
        if (req.targetVersion() == null) {
            throw ApiException.badRequest("targetVersion 不能为空");
        }
        if (req.leaseStart() == null || req.leaseEnd() == null) {
            throw ApiException.badRequest("leaseStart/leaseEnd 不能为空");
        }
        Instant leaseStart = req.leaseStart().truncatedTo(ChronoUnit.MICROS);
        Instant leaseEnd = req.leaseEnd().truncatedTo(ChronoUnit.MICROS);
        if (sourceKey.equals(targetKey)) {
            throw ApiException.badRequest("目标事件不能等于来源事件: " + targetKey);
        }
        if (!leaseEnd.isAfter(leaseStart)) {
            throw ApiException.badRequest("租约结束必须晚于开始");
        }
        List<HandoffItemRequest> items = req.items() == null ? List.of() : req.items();
        if (items.isEmpty()) {
            throw ApiException.badRequest("交接资源项至少一项");
        }
        List<String> resourceKeys = new ArrayList<>();
        for (HandoffItemRequest item : items) {
            resourceKeys.add(requireText(item.resourceKey(), "resourceKey"));
        }
        // 批量预检：批内资源重复则最终归属不唯一，422 且整体回滚
        Set<String> distinct = new LinkedHashSet<>(resourceKeys);
        if (distinct.size() != resourceKeys.size()) {
            throw ApiException.handoffConflict("HANDOFF_CONFLICT",
                    "批量交接内资源重复，最终归属不唯一", List.copyOf(resourceKeys));
        }
        // 指纹：两事件版本、资源、时段与操作者
        StringBuilder itemsFingerprint = new StringBuilder();
        for (HandoffItemRequest item : items) {
            itemsFingerprint.append(item.resourceKey()).append('=')
                    .append(item.taskKeys() == null ? "" : String.join(",", item.taskKeys()))
                    .append(';');
        }
        String fingerprint = IdempotentExecutor.hash(sourceKey, targetKey, receiver,
                String.valueOf(req.sourceVersion()), String.valueOf(req.targetVersion()),
                leaseStart.toString(), leaseEnd.toString(), actor, itemsFingerprint.toString());
        // 按事件键序锁定双方事件行，避免并发互借死锁
        boolean sourceFirst = sourceKey.compareTo(targetKey) <= 0;
        Incident first = lockIncident(sourceFirst ? sourceKey : targetKey);
        Incident second = lockIncident(sourceFirst ? targetKey : sourceKey);
        Incident source = sourceFirst ? first : second;
        Incident target = sourceFirst ? second : first;
        return idempotency.run(handoffKey, "handoff_create", fingerprint, HandoffView.class,
                () -> {
                    requireCommander(source, actor);
                    if (source.status() == IncidentStatus.CLOSED) {
                        throw ApiException.illegalTransition("来源事件已关闭，不能发起互助交接");
                    }
                    if (target.status() == IncidentStatus.CLOSED) {
                        throw ApiException.illegalTransition("目标事件已关闭，不能接收互助交接");
                    }
                    // 接收权限：目标事件当前指挥人或其已登记代理人
                    boolean receiverOk = receiver.equals(target.commander())
                            || handoffs.findDelegate(target.id(), receiver).isPresent();
                    if (!receiverOk) {
                        throw ApiException.handoffConflict("RECEIVER_NOT_AUTHORIZED",
                                "接收人须为目标事件当前指挥人或其已登记代理人: " + receiver, null);
                    }
                    if (source.version() != req.sourceVersion()) {
                        throw ApiException.versionMismatch("来源事件版本已变更: 期望 "
                                + req.sourceVersion() + "，当前 " + source.version());
                    }
                    if (target.version() != req.targetVersion()) {
                        throw ApiException.versionMismatch("目标事件版本已变更: 期望 "
                                + req.targetVersion() + "，当前 " + target.version());
                    }
                    // 批量校验：资源最终归属与重叠租约（按资源键序锁定，任一冲突 422 整体回滚）
                    List<IncidentResource> resources = new ArrayList<>();
                    List<List<IncidentTask>> itemTasks = new ArrayList<>();
                    List<String> sortedKeys = resourceKeys.stream().sorted().toList();
                    for (String resourceKey : sortedKeys) {
                        IncidentResource resource = handoffs.lockResource(resourceKey)
                                .orElseThrow(() -> ApiException.notFound(
                                        "资源不存在: " + resourceKey));
                        if (resource.holderIncidentId() != source.id()) {
                            throw ApiException.handoffConflict("RESOURCE_NOT_HELD",
                                    "来源事件不持有资源: " + resourceKey, null);
                        }
                        if (handoffs.findUnsettledItemByResource(resourceKey).isPresent()) {
                            throw ApiException.handoffConflict("LEASE_OVERLAP",
                                    "资源存在未结算借出，租约重叠: " + resourceKey, null);
                        }
                        resources.add(resource);
                    }
                    for (HandoffItemRequest item : items) {
                        List<IncidentTask> refs = new ArrayList<>();
                        List<String> taskKeys = item.taskKeys() == null ? List.of()
                                : item.taskKeys().stream().distinct().toList();
                        for (String taskKey : taskKeys) {
                            IncidentTask task = tasks.findByKey(target.id(),
                                            requireText(taskKey, "taskKey"))
                                    .orElseThrow(() -> ApiException.notFound(
                                            "目标任务不存在: " + taskKey));
                            if (task.status() == TaskStatus.DONE
                                    || task.status() == TaskStatus.CANCELLED) {
                                throw ApiException.handoffConflict("TASK_TERMINAL",
                                        "目标任务已处于终态，不能引用资源: " + taskKey, null);
                            }
                            refs.add(task);
                        }
                        itemTasks.add(refs);
                    }
                    // 全部校验通过后一次性写入
                    Instant now = now();
                    long handoffId = handoffs.insertHandoff(new ResourceHandoff(0L, handoffKey,
                            source.id(), target.id(), receiver, req.sourceVersion(),
                            req.targetVersion(), actor, leaseStart, leaseEnd,
                            HandoffStatus.ACTIVE, null, null, now, null));
                    for (int i = 0; i < items.size(); i++) {
                        long itemId = handoffs.insertItem(new HandoffItem(0L, handoffId,
                                resourceKeys.get(i), null));
                        for (IncidentTask task : itemTasks.get(i)) {
                            handoffs.insertRef(handoffId, itemId, task.id(), now);
                        }
                    }
                    return toHandoffView(handoffs.findHandoffByKey(handoffKey).orElseThrow());
                });
    }

    /**
     * 查询事件（作为来源或目标）的全部交接及结算。只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public IncidentHandoffsView listHandoffs(String incidentKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        List<HandoffView> views = handoffs.listByIncident(incident.id()).stream()
                .map(this::toHandoffView).toList();
        return new IncidentHandoffsView(incident.incidentKey(), views);
    }

    /**
     * 查询事件相关的全部不可变交接结算。只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public IncidentSettlementsView listSettlements(String incidentKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        List<SettlementView> views = handoffs.listSettlementsByIncident(incident.id()).stream()
                .map(this::toSettlementView).toList();
        return new IncidentSettlementsView(incident.incidentKey(), views);
    }

    /**
     * 租约到期结算：以注入 Clock 的当前时刻评估本事件（作为来源或目标）的进行中交接，
     * 租约已到期（leaseEnd ≤ 当前时刻）者触发结束并结算：未开始任务解除资源引用并归还来源，
     * 已开始任务继续持有至任务终态。同键重放首次结果；需要重新评估须更换 commandKey。
     */
    @Transactional
    public HandoffSettleView settleExpired(String incidentKey, HandoffSettleRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        Incident incident = lockIncident(incidentKey);
        return idempotency.run(commandKey, "handoff_settle",
                IdempotentExecutor.hash(incidentKey), HandoffSettleView.class, () -> {
                    Instant now = now();
                    List<SettlementView> settled = new ArrayList<>();
                    for (ResourceHandoff handoff : handoffs.lockActiveByIncident(incident.id())) {
                        if (!handoff.leaseEnd().isAfter(now)) {
                            settled.addAll(settleHandoff(handoff, SettlementReason.LEASE_EXPIRED,
                                    now));
                        }
                    }
                    return new HandoffSettleView(incident.incidentKey(), settled);
                });
    }

    /**
     * 查询资源当前责任方：未借出时为持有事件；借出期间为进行中交接的目标事件。
     * 只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public ResourceResponsibilityView resourceResponsibility(String resourceKey) {
        IncidentResource resource = handoffs.findResource(resourceKey)
                .orElseThrow(() -> ApiException.notFound("资源不存在: " + resourceKey));
        Incident holder = incidents.findById(resource.holderIncidentId()).orElseThrow();
        var lentItem = handoffs.findUnsettledItemByResource(resourceKey);
        if (lentItem.isEmpty()) {
            return new ResourceResponsibilityView(resourceKey, holder.incidentKey(),
                    holder.incidentKey(), false, null, null, null);
        }
        ResourceHandoff active = handoffs.findHandoffById(lentItem.get().handoffId())
                .orElseThrow();
        Incident target = incidents.findById(active.targetIncidentId()).orElseThrow();
        return new ResourceResponsibilityView(resourceKey, holder.incidentKey(),
                target.incidentKey(), true, active.handoffKey(), active.leaseStart(),
                active.leaseEnd());
    }

    /**
     * 查询事件关闭阻断原因：借出未归还资源（LENT_RESOURCE）与未达终态任务
     * （UNFINISHED_TASK）。blockers 为空表示可关闭。只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public CloseBlockersView closeBlockers(String incidentKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        List<CloseBlockerView> blockers = new ArrayList<>(lentResourceBlockers(incident.id()));
        tasks.listUnfinishedByIncident(incident.id()).forEach(t ->
                blockers.add(new CloseBlockerView("UNFINISHED_TASK", null, null, t.taskKey(),
                        "任务未达终态: " + t.taskKey())));
        return new CloseBlockersView(incident.incidentKey(), blockers.isEmpty(), blockers);
    }

    // ---------- 供 IncidentService 同事务调用的内部钩子 ----------

    /**
     * 关闭门禁（同事务）：来源事件存在未归还的借出资源时禁止关闭，409 并返回阻断明细。
     * 调用前已按提交顺序对本事件行加锁；先结算本事件已到期的租约再评估门禁。
     */
    public void requireCloseable(Incident incident) {
        Instant now = now();
        for (ResourceHandoff handoff : handoffs.lockActiveByIncident(incident.id())) {
            if (!handoff.leaseEnd().isAfter(now)) {
                settleHandoff(handoff, SettlementReason.LEASE_EXPIRED, now);
            }
        }
        List<CloseBlockerView> blockers = lentResourceBlockers(incident.id());
        if (!blockers.isEmpty()) {
            throw ApiException.conflict("存在未归还的借出资源，不能关闭", List.copyOf(blockers));
        }
    }

    /**
     * 目标事件关闭钩子（同事务）：该事件作为目标的全部进行中交接触发结束，
     * 未开始任务解除资源引用并归还来源、写入不可变结算；已开始任务继续持有至任务终态。
     */
    public void onIncidentClosed(long incidentId) {
        Instant now = now();
        for (ResourceHandoff handoff : handoffs.lockActiveByTarget(incidentId)) {
            settleHandoff(handoff, SettlementReason.TARGET_CLOSED, now);
        }
    }

    /**
     * 任务终态钩子（同事务）：解除该任务的全部交接资源引用；
     * 对已触发结束的交接，其资源项不再被任何任务引用时自动结算归还来源。
     */
    public void onTaskTerminal(long taskId) {
        List<HandoffTaskRef> refs = handoffs.listRefsByTask(taskId);
        if (refs.isEmpty()) {
            return;
        }
        handoffs.deleteRefsByTask(taskId);
        Instant now = now();
        Set<Long> handoffIds = new LinkedHashSet<>();
        refs.forEach(ref -> handoffIds.add(ref.handoffId()));
        for (Long handoffId : handoffIds) {
            ResourceHandoff handoff = handoffs.findHandoffById(handoffId).orElse(null);
            if (handoff == null || handoff.endReason() == null
                    || handoff.status() != HandoffStatus.ACTIVE) {
                continue;
            }
            settleFinishedItems(handoff, handoff.endReason(), now);
        }
    }

    // ---------- 私有实现 ----------

    /**
     * 结束触发与结算（同事务）：记录结束原因，解除未开始任务引用，
     * 无剩余引用的资源项立即结算归还来源；全部项结算后交接进入 SETTLED。
     *
     * @return 本次新写入的结算视图
     */
    private List<SettlementView> settleHandoff(ResourceHandoff handoff, SettlementReason reason,
                                               Instant now) {
        handoffs.markEndTriggered(handoff.id(), reason, now);
        handoffs.deleteRefsNotInProgress(handoff.id());
        ResourceHandoff refreshed = findHandoffById(handoff.id());
        // 以首次触发记录的原因为准（重复触发不改写）
        return settleFinishedItems(refreshed,
                refreshed.endReason() == null ? reason : refreshed.endReason(), now);
    }

    /**
     * 结算所有已无任务引用的未结算资源项；全部结算后交接置为 SETTLED。
     *
     * @return 本次新写入的结算视图
     */
    private List<SettlementView> settleFinishedItems(ResourceHandoff handoff,
                                                     SettlementReason reason, Instant now) {
        List<SettlementView> settled = new ArrayList<>();
        boolean allSettled = true;
        for (HandoffItem item : handoffs.listItems(handoff.id())) {
            if (item.settledAt() != null) {
                continue;
            }
            if (handoffs.listRefsByItem(item.id()).isEmpty()) {
                HandoffSettlement settlement = new HandoffSettlement(0L, handoff.id(), item.id(),
                        item.resourceKey(), reason, handoff.sourceIncidentId(), now);
                handoffs.insertSettlement(settlement);
                handoffs.markItemSettled(item.id(), now);
                settled.add(toSettlementView(settlement));
            } else {
                allSettled = false;
            }
        }
        if (allSettled) {
            handoffs.markHandoffSettled(handoff.id(), now);
        }
        return settled;
    }

    private ResourceHandoff findHandoffById(long handoffId) {
        return handoffs.findHandoffById(handoffId).orElseThrow();
    }

    private List<CloseBlockerView> lentResourceBlockers(long incidentId) {
        return handoffs.listUnsettledOutgoing(incidentId).stream()
                .map(this::toLentResourceBlocker).toList();
    }

    private CloseBlockerView toLentResourceBlocker(UnsettledOutgoing outgoing) {
        return new CloseBlockerView("LENT_RESOURCE", outgoing.resourceKey(), outgoing.handoffKey(),
                null, "资源借出未归还: " + outgoing.resourceKey()
                + "（交接 " + outgoing.handoffKey() + "）");
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

    private ResourceView toResourceView(IncidentResource resource, String holderIncidentKey) {
        return new ResourceView(resource.resourceKey(), holderIncidentKey, resource.acquiredBy(),
                resource.acquiredAt());
    }

    private static DelegateView toDelegateView(IncidentDelegate delegate) {
        return new DelegateView(delegate.delegate(), delegate.registeredBy(), delegate.createdAt());
    }

    private HandoffView toHandoffView(ResourceHandoff handoff) {
        Incident source = incidents.findById(handoff.sourceIncidentId()).orElseThrow();
        Incident target = incidents.findById(handoff.targetIncidentId()).orElseThrow();
        List<HandoffItemView> items = handoffs.listItems(handoff.id()).stream()
                .map(item -> {
                    List<String> taskKeys = handoffs.listRefsByItem(item.id()).stream()
                            .map(ref -> tasks.findById(ref.taskId())
                                    .map(IncidentTask::taskKey).orElse("?"))
                            .toList();
                    return new HandoffItemView(item.resourceKey(), item.settledAt() != null,
                            taskKeys, item.settledAt());
                })
                .toList();
        List<SettlementView> settlements = handoffs.listSettlementsByHandoff(handoff.id()).stream()
                .map(this::toSettlementView).toList();
        return new HandoffView(handoff.handoffKey(), source.incidentKey(), target.incidentKey(),
                handoff.receiver(), handoff.sourceVersion(), handoff.targetVersion(),
                handoff.operator(), handoff.leaseStart(), handoff.leaseEnd(),
                handoff.status().name(),
                handoff.endReason() == null ? null : handoff.endReason().name(),
                handoff.endTriggeredAt(), items, settlements, handoff.createdAt(),
                handoff.settledAt());
    }

    private SettlementView toSettlementView(HandoffSettlement settlement) {
        ResourceHandoff handoff = findHandoffById(settlement.handoffId());
        Incident returnedTo = incidents.findById(settlement.returnedToIncidentId()).orElseThrow();
        return new SettlementView(handoff.handoffKey(), settlement.resourceKey(),
                settlement.reason().name(), returnedTo.incidentKey(), settlement.settledAt());
    }
}
