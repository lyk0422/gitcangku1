package com.example.starter.incident;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.starter.incident.dto.Requests.ActionRequest;
import com.example.starter.incident.dto.Requests.EscalationAckRequest;
import com.example.starter.incident.dto.Requests.EscalationCheckRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskBatchDispatchRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TransferAcceptRequest;
import com.example.starter.incident.dto.Requests.TransferRequest;
import com.example.starter.incident.dto.Responses.ActionView;
import com.example.starter.incident.dto.Responses.BatchDispatchView;
import com.example.starter.incident.dto.Responses.EscalationHistoryView;
import com.example.starter.incident.dto.Responses.EscalationView;
import com.example.starter.incident.dto.Responses.EvacuationBlockerView;
import com.example.starter.incident.dto.Responses.HistoryView;
import com.example.starter.incident.dto.Responses.IncidentTasksView;
import com.example.starter.incident.dto.Responses.IncidentView;
import com.example.starter.incident.dto.Responses.StatusChangeView;
import com.example.starter.incident.dto.Responses.TaskBlockerView;
import com.example.starter.incident.dto.Responses.TaskView;
import com.example.starter.incident.dto.Responses.TransferView;
import com.example.starter.incident.dto.Responses.UnfinishedTaskView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 事件指挥核心服务。
 * 并发约定：所有写接口先 SELECT ... FOR UPDATE 锁定事件行，同事务内完成
 * 幂等键占位、业务校验与写入，保证并发请求按事务提交顺序生效。
 * 幂等约定：commandKey 全局唯一，同键同参重放首次响应，同键改参返回 409。
 * 遏制期限：首次进入 COMMANDING 时以该次接管 UTC 时刻按等级确定
 * （S1=5分钟、S2=15分钟、S3=60分钟、S4=240分钟），交接不重置。
 *
 * <p>疏散门禁：任务写事务在业务校验前先由 {@link EvacuationGate#evaluate} 按当前
 * UTC 时刻完成区域生效/结束裁决（区域生效阻断未开始命中任务，区域结束恢复），
 * 再校验创建/开始/派工/完成/撤离的豁免与资源依赖，任一失败随事务回滚不留半成品。
 */
@Service
public class IncidentService {

    /** 各严重等级的遏制时限（分钟），等级沿用上报值且不可修改。 */
    private static final Map<String, Long> CONTAINMENT_MINUTES = Map.of(
            "S1", 5L, "S2", 15L, "S3", 60L, "S4", 240L);

    /** 每事件处置任务上限。 */
    private static final int MAX_TASKS_PER_INCIDENT = 20;

    /** 每任务阻塞事件上限。 */
    private static final int MAX_BLOCKERS_PER_TASK = 5;

    /** 批量派工单次任务数上限。 */
    private static final int MAX_DISPATCH_BATCH = 20;

    /** 视为阻塞已解除的目标事件状态。 */
    private static final Set<IncidentStatus> UNBLOCKING_STATUSES = EnumSet.of(
            IncidentStatus.CONTAINED, IncidentStatus.RESOLVED, IncidentStatus.CLOSED);

    private final IncidentRepository incidents;
    private final EscalationRepository escalations;
    private final IncidentTaskRepository tasks;
    private final CommandKeyRepository commandKeys;
    private final EvacuationGate gate;
    private final Idempotency idempotency;
    private final Clock clock;

    public IncidentService(IncidentRepository incidents, EscalationRepository escalations,
                           IncidentTaskRepository tasks, CommandKeyRepository commandKeys,
                           EvacuationGate gate, Idempotency idempotency, Clock clock) {
        this.incidents = incidents;
        this.escalations = escalations;
        this.tasks = tasks;
        this.commandKeys = commandKeys;
        this.gate = gate;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    private Instant now() {
        return clock.instant();
    }

    /**
     * 事件上报：初始状态 REPORTED，无指挥人。incidentKey 重复返回 409。
     */
    @Transactional
    public IncidentView report(ReportRequest req) {
        String incidentKey = requireText(req.incidentKey(), "incidentKey");
        String severity = requireText(req.severity(), "severity");
        if (!severity.matches("S[1-4]")) {
            throw ApiException.badRequest("severity 必须为 S1~S4");
        }
        String summary = requireText(req.summary(), "summary");
        String reporter = requireText(req.reporter(), "reporter");
        if (incidents.findByKey(incidentKey).isPresent()) {
            throw ApiException.conflict("incidentKey 已存在: " + incidentKey);
        }
        Instant now = now();
        Incident incident = new Incident(0L, incidentKey, severity, summary, reporter,
                IncidentStatus.REPORTED, null, now, now, null);
        long id;
        try {
            id = incidents.insert(incident);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("incidentKey 已存在: " + incidentKey);
        }
        incidents.insertStatusChange(new StatusChange(0L, id, null, IncidentStatus.REPORTED, reporter, now));
        return toView(incidents.findByKey(incidentKey).orElseThrow(), null);
    }

    /**
     * 首次接管：REPORTED → COMMANDING，记录当前指挥人，
     * 并以该次接管 UTC 时刻按等级确定遏制期限（只写一次，交接不重置）。
     */
    @Transactional
    public IncidentView takeover(String incidentKey, String actor, TakeoverRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        Incident incident = lockIncident(incidentKey);
        return idempotency.run(commandKeys, commandKey, "takeover",
                Idempotency.hash(incidentKey, actor), IncidentView.class, now(),
                () -> {
                    if (incident.status() != IncidentStatus.REPORTED) {
                        throw ApiException.illegalTransition(
                                "仅 REPORTED 状态可接管，当前状态: " + incident.status());
                    }
                    Instant now = now();
                    Instant deadline = now.plus(CONTAINMENT_MINUTES.get(incident.severity()),
                            ChronoUnit.MINUTES);
                    incidents.updateState(incident.id(), IncidentStatus.COMMANDING, actor, now);
                    incidents.updateDeadline(incident.id(), deadline, now);
                    incidents.insertStatusChange(new StatusChange(0L, incident.id(),
                            IncidentStatus.REPORTED, IncidentStatus.COMMANDING, actor, now));
                    return toView(incidents.findByKey(incidentKey).orElseThrow(), null);
                });
    }

    /**
     * 发起交接：仅当前指挥人可发起，目标人必须不同；RESOLVED/CLOSED 禁止发起。
     */
    @Transactional
    public TransferView initiateTransfer(String incidentKey, String actor, TransferRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String toCommander = requireText(req.toCommander(), "toCommander");
        Incident incident = lockIncident(incidentKey);
        return idempotency.run(commandKeys, commandKey, "transfer_initiate",
                Idempotency.hash(incidentKey, actor, toCommander), TransferView.class, now(),
                () -> {
                    requireCommanderStatic(incident, actor);
                    if (incident.status() == IncidentStatus.RESOLVED
                            || incident.status() == IncidentStatus.CLOSED) {
                        throw ApiException.illegalTransition(
                                incident.status() + " 状态不允许发起交接");
                    }
                    if (incident.status() == IncidentStatus.REPORTED) {
                        throw ApiException.illegalTransition("尚未接管的事件不能发起交接");
                    }
                    if (toCommander.equals(incident.commander())) {
                        throw ApiException.badRequest("目标指挥人必须与当前指挥人不同");
                    }
                    if (incidents.findPendingTransfer(incident.id()).isPresent()) {
                        throw ApiException.conflict("已存在待接受的交接，不能重复发起");
                    }
                    Instant now = now();
                    incidents.insertTransfer(new IncidentTransfer(0L, incident.id(),
                            incident.commander(), toCommander, TransferStatus.PENDING, now, null));
                    return toTransferView(incidents.findPendingTransfer(incident.id()).orElseThrow());
                });
    }

    /**
     * 接受交接：仅待接受目标人可接受；接受后原子切换当前指挥人。
     * RESOLVED/CLOSED 禁止接受；待接受期间目标人无其他操作权限。
     */
    @Transactional
    public IncidentView acceptTransfer(String incidentKey, String actor, TransferAcceptRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        Incident incident = lockIncident(incidentKey);
        return idempotency.run(commandKeys, commandKey, "transfer_accept",
                Idempotency.hash(incidentKey, actor), IncidentView.class, now(),
                () -> {
                    if (incident.status() == IncidentStatus.RESOLVED
                            || incident.status() == IncidentStatus.CLOSED) {
                        throw ApiException.illegalTransition(
                                incident.status() + " 状态不允许接受交接");
                    }
                    IncidentTransfer pending = incidents.findPendingTransfer(incident.id())
                            .orElseThrow(() -> ApiException.conflict("当前没有待接受的交接"));
                    if (!pending.toCommander().equals(actor)) {
                        throw ApiException.conflict("只有交接目标人 " + pending.toCommander() + " 能接受交接");
                    }
                    Instant now = now();
                    incidents.acceptTransfer(pending.id(), now);
                    incidents.updateState(incident.id(), incident.status(), pending.toCommander(), now);
                    return toView(incidents.findByKey(incidentKey).orElseThrow(), null);
                });
    }

    /**
     * 追加处置记录：仅当前指挥人可写；CLOSED 后禁止写入。
     * actionKey 事件内唯一：同键同内容幂等返回首次记录，同键不同内容返回 409。
     */
    @Transactional
    public ActionView addAction(String incidentKey, String actor, ActionRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String actionKey = requireText(req.actionKey(), "actionKey");
        String actionType = requireText(req.actionType(), "actionType");
        String note = requireText(req.note(), "note");
        if (req.occurredAt() == null) {
            throw ApiException.badRequest("occurredAt 不能为空");
        }
        Instant occurredAt = req.occurredAt().truncatedTo(ChronoUnit.MICROS);
        Incident incident = lockIncident(incidentKey);
        return idempotency.run(commandKeys, commandKey, "action",
                Idempotency.hash(incidentKey, actor, actionKey, actionType, note, occurredAt.toString()),
                ActionView.class, now(), () -> {
                    requireCommanderStatic(incident, actor);
                    if (incident.status() == IncidentStatus.CLOSED) {
                        throw ApiException.illegalTransition("事件已关闭，不能再追加处置记录");
                    }
                    var existing = incidents.findAction(incident.id(), actionKey);
                    if (existing.isPresent()) {
                        IncidentAction found = existing.get();
                        if (!found.sameContent(actionType, note, occurredAt)) {
                            throw ApiException.conflict("actionKey 已被不同内容使用: " + actionKey);
                        }
                        return toActionView(found);
                    }
                    Instant now = now();
                    incidents.insertAction(new IncidentAction(0L, incident.id(), actionKey, actionType,
                            note, occurredAt, actor, now));
                    return toActionView(incidents.findAction(incident.id(), actionKey).orElseThrow());
                });
    }

    /**
     * 状态变更：仅当前指挥人可操作，仅允许 COMMANDING→CONTAINED→RESOLVED→CLOSED 逐级前进。
     * 进入 CONTAINED 时同事务将仍 OPEN 的逾期升级记录原子置为 CANCELLED，已确认记录保留。
     */
    @Transactional
    public IncidentView changeStatus(String incidentKey, String actor, StatusRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String target = requireText(req.targetStatus(), "targetStatus");
        IncidentStatus targetStatus;
        try {
            targetStatus = IncidentStatus.valueOf(target);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("未知目标状态: " + target);
        }
        Incident incident = lockIncident(incidentKey);
        return idempotency.run(commandKeys, commandKey, "status",
                Idempotency.hash(incidentKey, actor, target), IncidentView.class, now(),
                () -> {
                    requireCommanderStatic(incident, actor);
                    IncidentStatus next = incident.status().next();
                    if (next == null || next != targetStatus) {
                        throw ApiException.illegalTransition(
                                "不允许从 " + incident.status() + " 流转到 " + targetStatus);
                    }
                    if (targetStatus == IncidentStatus.RESOLVED) {
                        // 解决门禁：全部处置任务进入终态（DONE/CANCELLED/EVACUATED）后才可解决
                        List<UnfinishedTaskView> unfinished = tasks
                                .listUnfinishedByIncident(incident.id()).stream()
                                .map(t -> new UnfinishedTaskView(t.groupCode(), t.taskKey()))
                                .toList();
                        if (!unfinished.isEmpty()) {
                            throw ApiException.conflict(
                                    "仍有未终结的处置任务，不能进入 RESOLVED", unfinished);
                        }
                    }
                    Instant now = now();
                    incidents.updateState(incident.id(), targetStatus, incident.commander(), now);
                    incidents.insertStatusChange(new StatusChange(0L, incident.id(),
                            incident.status(), targetStatus, actor, now));
                    if (targetStatus == IncidentStatus.CONTAINED) {
                        escalations.cancelOpenForIncident(incident.id(), now);
                    }
                    return toView(incidents.findByKey(incidentKey).orElseThrow(), null);
                });
    }

    /**
     * 单事件遏制逾期检查：以注入 Clock 的当前时刻评估，不做定时扫描。
     * 仅当事件仍为 COMMANDING、尚无升级记录且当前时刻 ≥ 接管时确定的期限时，
     * 追加一条 OPEN 记录（保存期限、触发时刻、当时指挥人），每事件至多一条。
     * 期限前或其他状态不产生记录；同键重放首次结果（含期限前的无记录结果），
     * 失败不占键；需要重新评估必须更换 commandKey。
     */
    @Transactional
    public EscalationHistoryView checkEscalation(String incidentKey, EscalationCheckRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        Incident incident = lockIncident(incidentKey);
        return idempotency.run(commandKeys, commandKey, "escalation_check",
                Idempotency.hash(incidentKey), EscalationHistoryView.class, now(), () -> {
                    var existing = escalations.findByIncident(incident.id());
                    if (existing.isPresent()) {
                        return toEscalationHistory(incident, List.of(existing.get()));
                    }
                    Instant currentTime = now();
                    if (incident.status() == IncidentStatus.COMMANDING
                            && incident.deadlineAt() != null
                            && !currentTime.isBefore(incident.deadlineAt())) {
                        escalations.insert(new Escalation(0L, incident.id(), incident.deadlineAt(),
                                currentTime, incident.commander(), EscalationStatus.OPEN,
                                null, null, null, currentTime, currentTime));
                    }
                    return toEscalationHistory(incident,
                            escalations.listByIncident(incident.id()));
                });
    }

    /**
     * 确认 OPEN 升级记录：仅操作当时的当前指挥人可确认（待接受期间目标人无确认权，
     * 交接接受后旧指挥人失去确认权），提交非空处置说明后进入 ACKNOWLEDGED，
     * 记录确认人与 UTC 时刻。已 CANCELLED/ACKNOWLEDGED 或不存在记录均为 409。
     */
    @Transactional
    public EscalationView acknowledgeEscalation(String incidentKey, String actor,
                                                EscalationAckRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String note = requireText(req.note(), "note");
        Incident incident = lockIncident(incidentKey);
        return idempotency.run(commandKeys, commandKey, "escalation_ack",
                Idempotency.hash(incidentKey, actor, note), EscalationView.class, now(), () -> {
                    requireCommanderStatic(incident, actor);
                    Escalation escalation = escalations.findByIncident(incident.id())
                            .orElseThrow(() -> ApiException.conflict("当前没有可确认的升级记录"));
                    if (escalation.status() != EscalationStatus.OPEN) {
                        throw ApiException.conflict(
                                "升级记录状态为 " + escalation.status() + "，不能确认");
                    }
                    Instant ackedAt = now();
                    int updated = escalations.acknowledge(escalation.id(), note, actor, ackedAt);
                    if (updated == 0) {
                        throw ApiException.conflict("升级记录已被并发处理，不能确认");
                    }
                    return toEscalationView(
                            escalations.findByIncident(incident.id()).orElseThrow());
                });
    }

    /**
     * 查询事件当前状态（含当前指挥人与待接受交接目标人）。只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public IncidentView get(String incidentKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        String pendingTo = incidents.findPendingTransfer(incident.id())
                .map(IncidentTransfer::toCommander).orElse(null);
        return toView(incident, pendingTo);
    }

    /**
     * 查询遏制期限、当前升级及完整升级历史；只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public EscalationHistoryView escalationHistory(String incidentKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        return toEscalationHistory(incident, escalations.listByIncident(incident.id()));
    }

    /**
     * 查询完整历史：事件本体、状态流转、处置记录、交接记录、升级记录。
     */
    @Transactional(readOnly = true)
    public HistoryView history(String incidentKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        String pendingTo = incidents.findPendingTransfer(incident.id())
                .map(IncidentTransfer::toCommander).orElse(null);
        List<StatusChangeView> statusHistory = incidents.listStatusHistory(incident.id()).stream()
                .map(s -> new StatusChangeView(
                        s.fromStatus() == null ? null : s.fromStatus().name(),
                        s.toStatus().name(), s.actor(), s.occurredAt()))
                .toList();
        List<ActionView> actions = incidents.listActions(incident.id()).stream()
                .map(this::toActionView).toList();
        List<TransferView> transfers = incidents.listTransfers(incident.id()).stream()
                .map(IncidentService::toTransferView).toList();
        List<EscalationView> escalationList = escalations.listByIncident(incident.id()).stream()
                .map(this::toEscalationView).toList();
        return new HistoryView(toView(incident, pendingTo), statusHistory, actions, transfers,
                escalationList);
    }

    /**
     * 创建处置任务：仅当前指挥人；taskKey 事件内唯一；workGrid 为作业网格（创建后固定）；
     * 阻塞事件 0~5 个、必须存在且非自身；创建时在依赖图全局锁内做环检测，拒绝环。
     * 高危门禁：作业网格与当前有效疏散区域相交时，必须持有该区域版本的撤离豁免，否则 422
     * （EXEMPTION_REQUIRED，返回缺失豁免的区域键），任务与依赖边一并回滚。
     * taskKey 幂等：同键同内容（含网格与阻塞集合）返回首次任务，同键不同内容返回 409。
     */
    @Transactional
    public TaskView createTask(String incidentKey, String actor, TaskCreateRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String taskKey = requireText(req.taskKey(), "taskKey");
        String groupCode = requireText(req.groupCode(), "groupCode");
        String title = requireText(req.title(), "title");
        // 未显式给出作业网格的历史调用方落到默认网格（不与任何疏散区域相交）。
        String workGrid = (req.workGrid() == null || req.workGrid().isBlank())
                ? "GRID-DEFAULT" : req.workGrid().strip();
        List<String> blockerKeys = req.blockerIncidentKeys() == null ? List.of()
                : req.blockerIncidentKeys().stream()
                        .map(k -> requireText(k, "blockerIncidentKey"))
                        .distinct()
                        .toList();
        if (blockerKeys.size() > MAX_BLOCKERS_PER_TASK) {
            throw ApiException.badRequest("阻塞事件最多 " + MAX_BLOCKERS_PER_TASK + " 个");
        }
        if (blockerKeys.contains(incidentKey)) {
            throw ApiException.badRequest("阻塞事件不能是事件自身: " + incidentKey);
        }
        Incident incident = lockIncident(incidentKey);
        return idempotency.run(commandKeys, commandKey, "task_create",
                Idempotency.hash(incidentKey, actor, taskKey, groupCode, title, workGrid,
                        String.join(",", blockerKeys)),
                TaskView.class, now(), () -> {
                    requireCommanderStatic(incident, actor);
                    if (incident.status() == IncidentStatus.CLOSED) {
                        throw ApiException.illegalTransition("事件已关闭，不能再创建处置任务");
                    }
                    // 先让区域按当前时刻生效/结束：既有未开始命中任务据此阻断或恢复。
                    gate.evaluate(incident);
                    var existing = tasks.findByKey(incident.id(), taskKey);
                    if (existing.isPresent()) {
                        IncidentTask found = existing.get();
                        List<String> existingBlockers = incidents.listBlockingIncidents(found.id())
                                .stream().map(Incident::incidentKey).sorted().toList();
                        List<String> requested = blockerKeys.stream().sorted().toList();
                        if (!found.sameContent(groupCode, title, workGrid)
                                || !existingBlockers.equals(requested)) {
                            throw ApiException.conflict("taskKey 已被不同内容使用: " + taskKey);
                        }
                        return toTaskView(found);
                    }
                    if (tasks.countByIncident(incident.id()) >= MAX_TASKS_PER_INCIDENT) {
                        throw ApiException.conflict("每个事件最多创建 " + MAX_TASKS_PER_INCIDENT
                                + " 个处置任务");
                    }
                    List<Incident> blockers = new ArrayList<>();
                    for (String blockerKey : blockerKeys) {
                        blockers.add(incidents.findByKey(blockerKey)
                                .orElseThrow(() -> ApiException.notFound(
                                        "阻塞事件不存在: " + blockerKey)));
                    }
                    // 全局图锁：串行化环检测与边写入，并发反向依赖下最终图无环
                    tasks.lockGraph();
                    for (Incident blocker : blockers) {
                        if (tasks.isReachable(blocker.id(), incident.id())) {
                            throw ApiException.conflict("阻塞关系会形成环: "
                                    + blocker.incidentKey() + " 已直接或间接依赖 " + incidentKey);
                        }
                    }
                    Instant now = now();
                    long taskId = tasks.insert(new IncidentTask(0L, incident.id(), taskKey,
                            groupCode, title, TaskStatus.OPEN, workGrid, actor, null, null, null,
                            null, null, null, now, now));
                    for (Incident blocker : blockers) {
                        tasks.insertBlocker(taskId, blocker.id(), now);
                    }
                    // 高危门禁：新任务直接创建进有效疏散区域网格必须持豁免，否则 422 回滚。
                    List<String> missingZones = gate.violatingZonesNow(incident.id(), taskKey, workGrid)
                            .stream().map(EvacuationZone::zoneKey).toList();
                    if (!missingZones.isEmpty()) {
                        throw ApiException.unprocessable("EXEMPTION_REQUIRED",
                                "任务作业网格命中有效疏散区域，必须持有该区域版本的撤离豁免: "
                                        + String.join(",", missingZones),
                                List.copyOf(missingZones));
                    }
                    return toTaskView(tasks.findById(taskId).orElseThrow());
                });
    }

    /**
     * 批量派工：先统一校验所有任务的最终位置（作业网格）、资源依赖（阻塞事件全部解除）
     * 与撤离豁免（命中有效区域须持对应版本豁免），任一不满足整体 422
     * （DISPATCH_VALIDATION_FAILED，逐条返回原因），任务状态与派工租约全部回滚。
     * 仅 OPEN 任务可派工；全部通过后原子置 DISPATCHED 并为每任务写入未消费租约。
     */
    @Transactional
    public BatchDispatchView batchDispatch(String incidentKey, String actor,
                                           TaskBatchDispatchRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        List<String> requestedKeys = req.taskKeys() == null ? List.of() : req.taskKeys().stream()
                .map(k -> requireText(k, "taskKey")).distinct().toList();
        if (requestedKeys.isEmpty()) {
            throw ApiException.badRequest("taskKeys 至少包含一个任务");
        }
        if (requestedKeys.size() > MAX_DISPATCH_BATCH) {
            throw ApiException.badRequest("单次派工最多 " + MAX_DISPATCH_BATCH + " 个任务");
        }
        Incident incident = lockIncident(incidentKey);
        return idempotency.run(commandKeys, commandKey, "task_batch_dispatch",
                Idempotency.hash(incidentKey, actor, String.join(",", requestedKeys)),
                BatchDispatchView.class, now(), () -> {
                    requireCommanderStatic(incident, actor);
                    gate.evaluate(incident);
                    List<IncidentTask> toDispatch = new ArrayList<>();
                    List<Map<String, Object>> failures = new ArrayList<>();
                    for (String taskKey : requestedKeys) {
                        IncidentTask task = tasks.findByKey(incident.id(), taskKey).orElse(null);
                        if (task == null) {
                            failures.add(dispatchFailure(taskKey, "TASK_NOT_FOUND", List.of(), List.of()));
                            continue;
                        }
                        if (task.status() != TaskStatus.OPEN) {
                            // 非 OPEN（已派工/阻断/进行中/终态）不可派工，仅报告状态原因。
                            String stateReason = switch (task.status()) {
                                case DISPATCHED -> "ALREADY_DISPATCHED";
                                case EVACUATION_BLOCKED -> "EVACUATION_BLOCKED";
                                case IN_PROGRESS -> "ALREADY_STARTED";
                                case DONE, CANCELLED, EVACUATED -> "TASK_TERMINAL";
                                default -> "NOT_DISPATCHABLE";
                            };
                            failures.add(dispatchFailure(taskKey, stateReason, List.of(), List.of()));
                            continue;
                        }
                        List<String> unresolved = unresolvedBlockers(task.id());
                        List<String> missingZones = gate.violatingZonesNow(
                                incident.id(), task.taskKey(), task.workGrid()).stream()
                                .map(EvacuationZone::zoneKey).toList();
                        List<String> failureReasons = new ArrayList<>();
                        if (!unresolved.isEmpty()) {
                            failureReasons.add("RESOURCE_DEPENDENCY");
                        }
                        if (!missingZones.isEmpty()) {
                            failureReasons.add("EXEMPTION_REQUIRED");
                        }
                        if (!failureReasons.isEmpty()) {
                            failures.add(dispatchFailure(taskKey, String.join(",", failureReasons),
                                    missingZones, unresolved));
                        } else {
                            toDispatch.add(task);
                        }
                    }
                    if (!failures.isEmpty()) {
                        throw ApiException.unprocessable("DISPATCH_VALIDATION_FAILED",
                                "批量派工存在未通过门禁的任务，全部回滚", List.copyOf(failures));
                    }
                    Instant now = now();
                    List<Long> ids = toDispatch.stream().map(IncidentTask::id).toList();
                    int updated = tasks.markDispatchedIfIn(ids, now, List.of(TaskStatus.OPEN));
                    if (updated != ids.size()) {
                        // 并发状态漂移：条件更新数量不符则整体失败回滚，不留部分派工
                        throw ApiException.conflict("派工并发冲突，任务状态已变化");
                    }
                    for (IncidentTask task : toDispatch) {
                        tasks.insertLease(task.id(), actor, commandKey, now);
                    }
                    List<TaskView> views = toDispatch.stream()
                            .map(t -> tasks.findById(t.id()).orElseThrow())
                            .map(this::toTaskView).toList();
                    return new BatchDispatchView(incidentKey,
                            toDispatch.stream().map(IncidentTask::taskKey).toList(), views);
                });
    }

    /**
     * 开始任务：仅 OPEN/DISPATCHED 可开始；EVACUATION_BLOCKED 必须等区域结束恢复后才可开始。
     * 作业网格与有效疏散区域相交时必须持有该区域版本豁免，否则 422（EXEMPTION_REQUIRED）。
     * 派工任务开始时消费其派工租约。
     */
    @Transactional
    public TaskView startTask(String incidentKey, String taskKey, String actor,
                              TaskActionRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        Incident incident = lockIncident(incidentKey);
        return idempotency.run(commandKeys, commandKey, "task_start",
                Idempotency.hash(incidentKey, taskKey, actor), TaskView.class, now(), () -> {
                    requireCommanderStatic(incident, actor);
                    gate.evaluate(incident);
                    IncidentTask task = tasks.findByKey(incident.id(), taskKey)
                            .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskKey));
                    if (task.status() == TaskStatus.EVACUATION_BLOCKED) {
                        throw ApiException.unprocessable("TASK_EVACUATION_BLOCKED",
                                "任务处于疏散阻断，区域结束恢复前不能开始: " + taskKey,
                                blockedDetails(task));
                    }
                    if (task.status() != TaskStatus.OPEN && task.status() != TaskStatus.DISPATCHED) {
                        throw ApiException.conflict(
                                "任务当前状态 " + task.status() + " 不能开始");
                    }
                    List<String> missingZones = gate.violatingZonesNow(
                                    incident.id(), task.taskKey(), task.workGrid()).stream()
                            .map(EvacuationZone::zoneKey).toList();
                    if (!missingZones.isEmpty()) {
                        throw ApiException.unprocessable("EXEMPTION_REQUIRED",
                                "任务作业网格命中有效疏散区域，必须持有该区域版本的撤离豁免: "
                                        + String.join(",", missingZones),
                                List.copyOf(missingZones));
                    }
                    Instant now = now();
                    int updated = tasks.markInProgressIfIn(task.id(), now,
                            List.of(TaskStatus.OPEN, TaskStatus.DISPATCHED));
                    if (updated == 0) {
                        throw ApiException.conflict("任务已被并发处理，不能开始: " + taskKey);
                    }
                    tasks.consumeLease(task.id(), now);
                    return toTaskView(tasks.findByKey(incident.id(), taskKey).orElseThrow());
                });
    }

    /**
     * 完成任务：仅当前指挥人。
     * OPEN/DISPATCHED 须先过资源依赖门禁（全部阻塞事件 CONTAINED/RESOLVED/CLOSED）；
     * 命中有效区域的未开始任务在写事务开始时已被阻断（EVACUATION_BLOCKED）不可完成；
     * IN_PROGRESS 任务若命中有效区域且无对应版本豁免，不能完成（须撤离），422；
     * 持有效豁免的进行中任务可继续并完成。EVACUATED 为终态不可完成。
     */
    @Transactional
    public TaskView completeTask(String incidentKey, String taskKey, String actor,
                                 TaskActionRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        Incident incident = lockIncident(incidentKey);
        return idempotency.run(commandKeys, commandKey, "task_complete",
                Idempotency.hash(incidentKey, taskKey, actor), TaskView.class, now(), () -> {
                    requireCommanderStatic(incident, actor);
                    gate.evaluate(incident);
                    IncidentTask task = tasks.findByKey(incident.id(), taskKey)
                            .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskKey));
                    if (task.status() == TaskStatus.EVACUATED) {
                        throw ApiException.conflict("任务已撤离（EVACUATED 终态），不能完成");
                    }
                    if (task.status() == TaskStatus.CANCELLED || task.status() == TaskStatus.DONE) {
                        throw ApiException.conflict(
                                "任务已处于终态 " + task.status() + "，不能完成");
                    }
                    if (task.status() == TaskStatus.EVACUATION_BLOCKED) {
                        throw ApiException.unprocessable("TASK_EVACUATION_BLOCKED",
                                "任务处于疏散阻断，区域结束恢复前不能完成: " + taskKey,
                                blockedDetails(task));
                    }
                    List<String> unresolved = unresolvedBlockers(task.id());
                    if (!unresolved.isEmpty()) {
                        throw ApiException.conflict("存在未解除阻塞的事件: "
                                + String.join(",", unresolved), List.copyOf(unresolved));
                    }
                    if (task.status() == TaskStatus.IN_PROGRESS) {
                        List<String> missingZones = gate.violatingZonesNow(
                                        incident.id(), task.taskKey(), task.workGrid()).stream()
                                .map(EvacuationZone::zoneKey).toList();
                        if (!missingZones.isEmpty()) {
                            throw ApiException.unprocessable("EVACUATE_OR_EXEMPT",
                                    "进行中任务命中有效疏散区域且无豁免，不能完成，请登记撤离或取得豁免: "
                                            + String.join(",", missingZones),
                                    List.copyOf(missingZones));
                        }
                    }
                    Instant now = now();
                    int updated = tasks.markDoneIfIn(task.id(), actor, now, List.of(
                            TaskStatus.OPEN, TaskStatus.DISPATCHED, TaskStatus.IN_PROGRESS));
                    if (updated == 0) {
                        throw ApiException.conflict("任务已被并发处理，不能完成: " + taskKey);
                    }
                    return toTaskView(tasks.findByKey(incident.id(), taskKey).orElseThrow());
                });
    }

    /**
     * 撤离登记：仅当前指挥人；仅进行中（IN_PROGRESS）且当前命中有效疏散区域、
     * 又未持有该区域版本豁免的任务可登记。登记后任务转 EVACUATED 终态，不可完成。
     * 持有效豁免（未命中违规区域）的进行中任务应继续作业，不允许登记撤离。
     */
    @Transactional
    public TaskView evacuateTask(String incidentKey, String taskKey, String actor,
                                 TaskActionRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        Incident incident = lockIncident(incidentKey);
        return idempotency.run(commandKeys, commandKey, "task_evacuate",
                Idempotency.hash(incidentKey, taskKey, actor), TaskView.class, now(), () -> {
                    requireCommanderStatic(incident, actor);
                    gate.evaluate(incident);
                    IncidentTask task = tasks.findByKey(incident.id(), taskKey)
                            .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskKey));
                    if (task.status() == TaskStatus.EVACUATED) {
                        throw ApiException.conflict("任务已撤离（EVACUATED 终态）");
                    }
                    if (task.status() != TaskStatus.IN_PROGRESS) {
                        throw ApiException.unprocessable("EVACUATION_ONLY_IN_PROGRESS",
                                "仅进行中的任务可登记撤离，当前状态: " + task.status(), null);
                    }
                    List<EvacuationZone> hit = gate.violatingZonesNow(
                            incident.id(), task.taskKey(), task.workGrid());
                    if (hit.isEmpty()) {
                        throw ApiException.unprocessable("NOT_IN_EVACUATION_ZONE",
                                "进行中任务未命中缺少豁免的有效疏散区域，不能登记撤离（持豁免任务应继续完成）",
                                null);
                    }
                    Instant now = now();
                    int updated = tasks.markEvacuatedIfInProgress(task.id(), now);
                    if (updated == 0) {
                        throw ApiException.conflict("任务已被并发处理，不能登记撤离: " + taskKey);
                    }
                    return toTaskView(tasks.findByKey(incident.id(), taskKey).orElseThrow());
                });
    }

    /**
     * 取消任务：仅当前指挥人；仅 OPEN/DISPATCHED 可取消；
     * EVACUATION_BLOCKED 在区域结束恢复前冻结，不可取消；DONE/CANCELLED/EVACUATED 为终态。
     */
    @Transactional
    public TaskView cancelTask(String incidentKey, String taskKey, String actor,
                               TaskActionRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        Incident incident = lockIncident(incidentKey);
        return idempotency.run(commandKeys, commandKey, "task_cancel",
                Idempotency.hash(incidentKey, taskKey, actor), TaskView.class, now(), () -> {
                    requireCommanderStatic(incident, actor);
                    gate.evaluate(incident);
                    IncidentTask task = tasks.findByKey(incident.id(), taskKey)
                            .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskKey));
                    if (task.status() == TaskStatus.EVACUATION_BLOCKED) {
                        throw ApiException.unprocessable("TASK_EVACUATION_BLOCKED",
                                "任务处于疏散阻断，区域结束恢复前不能取消: " + taskKey,
                                blockedDetails(task));
                    }
                    if (task.status() != TaskStatus.OPEN && task.status() != TaskStatus.DISPATCHED) {
                        throw ApiException.conflict(
                                "任务当前状态 " + task.status() + " 不能取消");
                    }
                    Instant now = now();
                    int updated = tasks.markCancelledIfIn(task.id(), actor, now,
                            List.of(TaskStatus.OPEN, TaskStatus.DISPATCHED));
                    if (updated == 0) {
                        throw ApiException.conflict("任务已被并发处理，不能取消: " + taskKey);
                    }
                    return toTaskView(tasks.findByKey(incident.id(), taskKey).orElseThrow());
                });
    }

    /**
     * 按事件分组查询任务（含阻塞状态与疏散阻断快照，按查询时状态计算）。只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public IncidentTasksView listTasks(String incidentKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        List<TaskView> views = tasks.listByIncident(incident.id()).stream()
                .map(this::toTaskView).toList();
        return new IncidentTasksView(incident.incidentKey(), views);
    }

    /**
     * 查询单任务明细（含阻塞状态与疏散阻断快照）。只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public TaskView getTask(String incidentKey, String taskKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        IncidentTask task = tasks.findByKey(incident.id(), taskKey)
                .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskKey));
        return toTaskView(task);
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

    private Incident lockIncident(String incidentKey) {
        requireText(incidentKey, "incidentKey");
        return incidents.lockByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
    }

    /**
     * 指挥人校验（供本服务与疏散域共用）：actor 必须为事件当前指挥人。
     */
    static void requireCommanderStatic(Incident incident, String actor) {
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

    private static Map<String, Object> dispatchFailure(String taskKey, String reason,
                                                       List<String> missingZones,
                                                       List<String> unresolvedBlockers) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("taskKey", taskKey);
        detail.put("reason", reason);
        detail.put("missingExemptionZones", List.copyOf(missingZones));
        detail.put("unresolvedBlockers", List.copyOf(unresolvedBlockers));
        return detail;
    }

    private EvacuationBlockerView blockedDetails(IncidentTask task) {
        if (task.blockedSnapshot() == null) {
            return null;
        }
        EvacuationGate.ZoneSnapshot snapshot = gate.readSnapshot(task.blockedSnapshot());
        return new EvacuationBlockerView(snapshot.zoneKey(), snapshot.version(),
                snapshot.riskLevel(), snapshot.grids(), snapshot.effectiveFrom(),
                snapshot.effectiveTo());
    }

    private IncidentView toView(Incident incident, String pendingTransferTo) {
        return new IncidentView(incident.incidentKey(), incident.severity(), incident.summary(),
                incident.reporter(), incident.status().name(), incident.commander(), pendingTransferTo,
                incident.createdAt(), incident.updatedAt(), incident.deadlineAt());
    }

    private ActionView toActionView(IncidentAction action) {
        return new ActionView(action.actionKey(), action.actionType(), action.note(),
                action.occurredAt(), action.actor(), action.createdAt());
    }

    /**
     * 组装任务视图：阻塞状态按目标事件查询时当前状态计算，不写回依赖任务；
     * blocked 仅 EVACUATION_BLOCKED 状态返回固化的区域快照。
     */
    private TaskView toTaskView(IncidentTask task) {
        List<TaskBlockerView> blockers = incidents.listBlockingIncidents(task.id()).stream()
                .map(b -> new TaskBlockerView(b.incidentKey(), b.status().name(),
                        UNBLOCKING_STATUSES.contains(b.status())))
                .toList();
        EvacuationBlockerView blocked = task.status() == TaskStatus.EVACUATION_BLOCKED
                ? blockedDetails(task) : null;
        return new TaskView(task.taskKey(), task.groupCode(), task.title(), task.workGrid(),
                task.status().name(), blockers, blocked, task.createdBy(), task.createdAt(),
                task.doneBy(), task.doneAt(), task.cancelledBy(), task.cancelledAt());
    }

    private static TransferView toTransferView(IncidentTransfer transfer) {
        return new TransferView(transfer.id(), transfer.fromCommander(), transfer.toCommander(),
                transfer.status().name(), transfer.createdAt(), transfer.acceptedAt());
    }

    private EscalationView toEscalationView(Escalation escalation) {
        return new EscalationView(escalation.id(), escalation.deadlineAt(), escalation.triggeredAt(),
                escalation.triggeredCommander(), escalation.status().name(), escalation.note(),
                escalation.acknowledgedBy(), escalation.acknowledgedAt(), escalation.createdAt());
    }

    /**
     * 组装升级查询视图：current 为该事件当前（唯一）升级记录，无则 null。
     */
    private EscalationHistoryView toEscalationHistory(Incident incident, List<Escalation> list) {
        List<EscalationView> views = list.stream().map(this::toEscalationView).toList();
        EscalationView current = views.isEmpty() ? null : views.get(views.size() - 1);
        return new EscalationHistoryView(incident.deadlineAt(), current, views);
    }
}
