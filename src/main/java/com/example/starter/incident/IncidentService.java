package com.example.starter.incident;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import com.example.starter.incident.dto.Requests.ActionRequest;
import com.example.starter.incident.dto.Requests.DelegateRegisterRequest;
import com.example.starter.incident.dto.Requests.EscalationAckRequest;
import com.example.starter.incident.dto.Requests.EscalationCheckRequest;
import com.example.starter.incident.dto.Requests.HandoffCreateRequest;
import com.example.starter.incident.dto.Requests.HandoffItemRequest;
import com.example.starter.incident.dto.Requests.HandoffSettleRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.ResourceRegisterRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskAssignResourceRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TaskStartRequest;
import com.example.starter.incident.dto.Requests.TransferAcceptRequest;
import com.example.starter.incident.dto.Requests.TransferRequest;
import com.example.starter.incident.dto.Responses.ActionView;
import com.example.starter.incident.dto.Responses.CloseBlockerView;
import com.example.starter.incident.dto.Responses.DelegateView;
import com.example.starter.incident.dto.Responses.EscalationHistoryView;
import com.example.starter.incident.dto.Responses.EscalationView;
import com.example.starter.incident.dto.Responses.HandoffBatchView;
import com.example.starter.incident.dto.Responses.HandoffSettlementListHolder;
import com.example.starter.incident.dto.Responses.HandoffSettlementView;
import com.example.starter.incident.dto.Responses.HandoffView;
import com.example.starter.incident.dto.Responses.HistoryView;
import com.example.starter.incident.dto.Responses.IncidentTasksView;
import com.example.starter.incident.dto.Responses.IncidentView;
import com.example.starter.incident.dto.Responses.ResourceResponsibilityView;
import com.example.starter.incident.dto.Responses.ResourceView;
import com.example.starter.incident.dto.Responses.StatusChangeView;
import com.example.starter.incident.dto.Responses.TaskBlockerView;
import com.example.starter.incident.dto.Responses.TaskView;
import com.example.starter.incident.dto.Responses.TransferView;
import com.example.starter.incident.dto.Responses.UnfinishedTaskView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 */
@Service
public class IncidentService {

    private static final String SEP = "\\u001F";

    /** 各严重等级的遏制时限（分钟），等级沿用上报值且不可修改。 */
    private static final Map<String, Long> CONTAINMENT_MINUTES = Map.of(
            "S1", 5L, "S2", 15L, "S3", 60L, "S4", 240L);

    /** 每事件处置任务上限。 */
    private static final int MAX_TASKS_PER_INCIDENT = 20;

    /** 每任务阻塞事件上限。 */
    private static final int MAX_BLOCKERS_PER_TASK = 5;

    /** 视为阻塞已解除的目标事件状态。 */
    private static final Set<IncidentStatus> UNBLOCKING_STATUSES = EnumSet.of(
            IncidentStatus.CONTAINED, IncidentStatus.RESOLVED, IncidentStatus.CLOSED);

    private final IncidentRepository incidents;
    private final EscalationRepository escalations;
    private final IncidentTaskRepository tasks;
    private final CommandKeyRepository commandKeys;
    private final ResourceRepository resources;
    private final HandoffRepository handoffs;
    private final DelegateRepository delegates;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IncidentService(IncidentRepository incidents, EscalationRepository escalations,
                           IncidentTaskRepository tasks, CommandKeyRepository commandKeys,
                           ResourceRepository resources, HandoffRepository handoffs,
                           DelegateRepository delegates,
                           ObjectMapper objectMapper, Clock clock) {
        this.incidents = incidents;
        this.escalations = escalations;
        this.tasks = tasks;
        this.commandKeys = commandKeys;
        this.resources = resources;
        this.handoffs = handoffs;
        this.delegates = delegates;
        this.objectMapper = objectMapper;
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
                IncidentStatus.REPORTED, null, 0L, now, now, null);
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
        return runIdempotent(commandKey, "takeover", hash(incidentKey, actor), IncidentView.class,
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
        return runIdempotent(commandKey, "transfer_initiate", hash(incidentKey, actor, toCommander),
                TransferView.class, () -> {
                    requireCommander(incident, actor);
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
        return runIdempotent(commandKey, "transfer_accept", hash(incidentKey, actor), IncidentView.class,
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
        return runIdempotent(commandKey, "action",
                hash(incidentKey, actor, actionKey, actionType, note, occurredAt.toString()),
                ActionView.class, () -> {
                    requireCommander(incident, actor);
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
        return runIdempotent(commandKey, "status", hash(incidentKey, actor, target),
                IncidentView.class, () -> {
                    requireCommander(incident, actor);
                    IncidentStatus next = incident.status().next();
                    if (next == null || next != targetStatus) {
                        throw ApiException.illegalTransition(
                                "不允许从 " + incident.status() + " 流转到 " + targetStatus);
                    }
                    if (targetStatus == IncidentStatus.RESOLVED) {
                        // 解决门禁：普通处置任务须全部进入 DONE/CANCELLED；
                        // 占用互助借用资源的未终态任务按互助规则在关闭时处理
                        // （未开始解绑归还、已开始继续至终态自动归还），故不在此阻断。
                        List<UnfinishedTaskView> unfinished = tasks
                                .listUnfinishedByIncident(incident.id()).stream()
                                .filter(t -> t.assignedHandoffId() == null)
                                .map(t -> new UnfinishedTaskView(t.groupCode(), t.taskKey()))
                                .toList();
                        if (!unfinished.isEmpty()) {
                            throw ApiException.conflict(
                                    "仍有未完成的处置任务，不能进入 RESOLVED", unfinished);
                        }
                    }
                    if (targetStatus == IncidentStatus.CLOSED) {
                        // 来源关闭阻断：尚有借出未归还（ACTIVE）资源时禁止关闭
                        List<Handoff> lent = handoffs.listActiveBySource(incident.id());
                        if (!lent.isEmpty()) {
                            List<String> resourceKeys = lent.stream()
                                    .map(h -> resources.findById(h.resourceId())
                                            .map(Resource::resourceKey).orElse("?"))
                                    .distinct().toList();
                            throw ApiException.unprocessable("SOURCE_CLOSE_BLOCKED",
                                    "仍有借出未归还的资源，来源事件不能关闭: "
                                            + String.join(",", resourceKeys));
                        }
                    }
                    Instant now = now();
                    incidents.updateState(incident.id(), targetStatus, incident.commander(), now);
                    incidents.insertStatusChange(new StatusChange(0L, incident.id(),
                            incident.status(), targetStatus, actor, now));
                    if (targetStatus == IncidentStatus.CONTAINED) {
                        escalations.cancelOpenForIncident(incident.id(), now);
                    }
                    if (targetStatus == IncidentStatus.CLOSED) {
                        // 目标关闭：同事务解除未开始任务占用并归还来源；已开始任务继续占用至终态
                        settleOnTargetClosed(incident.id(), now);
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
        return runIdempotent(commandKey, "escalation_check", hash(incidentKey),
                EscalationHistoryView.class, () -> {
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
        return runIdempotent(commandKey, "escalation_ack", hash(incidentKey, actor, note),
                EscalationView.class, () -> {
                    requireCommander(incident, actor);
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
     * 创建处置任务：仅当前指挥人；taskKey 事件内唯一；阻塞事件 0~5 个、必须存在且非自身；
     * 创建时在依赖图全局锁内做环检测，拒绝直接或间接环（409 且不留部分任务或边）。
     * taskKey 幂等：同键同内容（含阻塞集合）返回首次任务，同键不同内容返回 409。
     */
    @Transactional
    public TaskView createTask(String incidentKey, String actor, TaskCreateRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String taskKey = requireText(req.taskKey(), "taskKey");
        String groupCode = requireText(req.groupCode(), "groupCode");
        String title = requireText(req.title(), "title");
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
        return runIdempotent(commandKey, "task_create",
                hash(incidentKey, actor, taskKey, groupCode, title, String.join(",", blockerKeys)),
                TaskView.class, () -> {
                    requireCommander(incident, actor);
                    if (incident.status() == IncidentStatus.CLOSED) {
                        throw ApiException.illegalTransition("事件已关闭，不能再创建处置任务");
                    }
                    var existing = tasks.findByKey(incident.id(), taskKey);
                    if (existing.isPresent()) {
                        IncidentTask found = existing.get();
                        List<String> existingBlockers = incidents.listBlockingIncidents(found.id())
                                .stream().map(Incident::incidentKey).sorted().toList();
                        List<String> requested = blockerKeys.stream().sorted().toList();
                        if (!found.sameContent(groupCode, title)
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
                            groupCode, title, TaskStatus.OPEN, null, null, null, null, actor,
                            null, null, null, null, now, now));
                    for (Incident blocker : blockers) {
                        tasks.insertBlocker(taskId, blocker.id(), now);
                    }
                    return toTaskView(tasks.findByKey(incident.id(), taskKey).orElseThrow());
                });
    }

    /**
     * 完成任务：仅当前指挥人；仅 OPEN 可完成；全部阻塞事件进入
     * CONTAINED/RESOLVED/CLOSED 后才可完成，否则 409 并返回未解除事件列表。
     * DONE/CANCELLED 为终态，重复操作按 commandKey 幂等规则返回首次结果。
     */
    @Transactional
    public TaskView completeTask(String incidentKey, String taskKey, String actor,
                                 TaskActionRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        Incident incident = lockIncident(incidentKey);
        return runIdempotent(commandKey, "task_complete", hash(incidentKey, taskKey, actor),
                TaskView.class, () -> {
                    requireCommander(incident, actor);
                    IncidentTask task = tasks.findByKey(incident.id(), taskKey)
                            .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskKey));
                    if (task.status() != TaskStatus.OPEN && task.status() != TaskStatus.STARTED) {
                        throw ApiException.conflict(
                                "任务已处于终态 " + task.status() + "，不能完成");
                    }
                    List<String> unresolved = unresolvedBlockers(task.id());
                    if (!unresolved.isEmpty()) {
                        throw ApiException.conflict("存在未解除阻塞的事件: "
                                + String.join(",", unresolved), List.copyOf(unresolved));
                    }
                    Instant finishAt = now();
                    tasks.markDone(task.id(), actor, finishAt);
                    // 任务终态：若占用互助借用资源，同事务解绑并在无其他占用任务时归还来源
                    settleBorrowedResourceOnTaskFinish(task, SettlementReason.TASK_DONE, finishAt);
                    return toTaskView(tasks.findByKey(incident.id(), taskKey).orElseThrow());
                });
    }

    /**
     * 取消任务：仅当前指挥人；OPEN/STARTED 可取消；DONE/CANCELLED 为终态，
     * 重复操作按 commandKey 幂等规则返回首次结果。取消借用资源的已开始任务同样触发自动归还。
     */
    @Transactional
    public TaskView cancelTask(String incidentKey, String taskKey, String actor,
                               TaskActionRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        Incident incident = lockIncident(incidentKey);
        return runIdempotent(commandKey, "task_cancel", hash(incidentKey, taskKey, actor),
                TaskView.class, () -> {
                    requireCommander(incident, actor);
                    IncidentTask task = tasks.findByKey(incident.id(), taskKey)
                            .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskKey));
                    if (task.status() != TaskStatus.OPEN && task.status() != TaskStatus.STARTED) {
                        throw ApiException.conflict(
                                "任务已处于终态 " + task.status() + "，不能取消");
                    }
                    Instant finishAt = now();
                    tasks.markCancelled(task.id(), actor, finishAt);
                    // 任务终态：若占用互助借用资源，同事务解绑并在无其他占用任务时归还来源
                    settleBorrowedResourceOnTaskFinish(task, SettlementReason.TASK_CANCELLED,
                            finishAt);
                    return toTaskView(tasks.findByKey(incident.id(), taskKey).orElseThrow());
                });
    }

    /**
     * 登记可互助资源：仅来源事件当前指挥人；事件未关闭；resourceKey 全局唯一，重复 409。
     */
    @Transactional
    public ResourceView registerResource(String incidentKey, String actor, ResourceRegisterRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String resourceKey = requireText(req.resourceKey(), "resourceKey");
        String label = requireText(req.label(), "label");
        Incident incident = lockIncident(incidentKey);
        return runIdempotent(commandKey, "resource_register",
                hash(incidentKey, actor, resourceKey, label), ResourceView.class, () -> {
                    requireCommander(incident, actor);
                    if (incident.status() == IncidentStatus.CLOSED) {
                        throw ApiException.illegalTransition("事件已关闭，不能登记资源");
                    }
                    if (resources.findByKey(resourceKey).isPresent()) {
                        throw ApiException.conflict("resourceKey 已存在: " + resourceKey);
                    }
                    Instant now = now();
                    long id;
                    try {
                        id = resources.insert(new Resource(0L, resourceKey, incident.id(), label,
                                actor, ResourceStatus.AVAILABLE, now, now));
                    } catch (DuplicateKeyException e) {
                        throw ApiException.conflict("resourceKey 已存在: " + resourceKey);
                    }
                    return toResourceView(resources.findById(id).orElseThrow());
                });
    }

    /**
     * 登记目标事件接收代理人：仅当前指挥人；代理人不能是指挥人本人；重复登记幂等。
     */
    @Transactional
    public DelegateView registerDelegate(String incidentKey, String actor, DelegateRegisterRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String delegate = requireText(req.delegate(), "delegate");
        Incident incident = lockIncident(incidentKey);
        return runIdempotent(commandKey, "delegate_register",
                hash(incidentKey, actor, delegate), DelegateView.class, () -> {
                    requireCommander(incident, actor);
                    if (incident.status() == IncidentStatus.CLOSED) {
                        throw ApiException.illegalTransition("事件已关闭，不能登记接收代理人");
                    }
                    if (delegate.equals(incident.commander())) {
                        throw ApiException.badRequest("代理人不能是当前指挥人本人");
                    }
                    Instant now = now();
                    delegates.insertIfAbsent(incident.id(), delegate, actor, now);
                    return new DelegateView(incident.incidentKey(), delegate, actor, now);
                });
    }

    /**
     * 开始任务：仅当前指挥人；仅 OPEN 可开始，进入 STARTED 并记录开始人/时刻。
     * 已开始任务占用的借用资源此后不因租约到期或目标关闭而解除，直至任务终态。
     */
    @Transactional
    public TaskView startTask(String incidentKey, String taskKey, String actor, TaskStartRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        Incident incident = lockIncident(incidentKey);
        return runIdempotent(commandKey, "task_start", hash(incidentKey, taskKey, actor),
                TaskView.class, () -> {
                    requireCommander(incident, actor);
                    IncidentTask task = tasks.findByKey(incident.id(), taskKey)
                            .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskKey));
                    if (task.status() == TaskStatus.DONE || task.status() == TaskStatus.CANCELLED) {
                        throw ApiException.conflict(
                                "任务已处于终态 " + task.status() + "，不能开始");
                    }
                    if (task.status() == TaskStatus.STARTED) {
                        throw ApiException.conflict("任务已开始，不能重复开始");
                    }
                    int updated = tasks.markStarted(task.id(), actor, now());
                    if (updated == 0) {
                        throw ApiException.conflict("任务已被并发开始或已终态");
                    }
                    return toTaskView(tasks.findByKey(incident.id(), taskKey).orElseThrow());
                });
    }

    /**
     * 批量互助交接：来源事件当前指挥人把空闲资源以 handoffKey 借给另一 OPEN 事件。
     * 目标不得等于来源；租约 UTC 左闭右开且结束晚于开始；接收方须为目标当前指挥人或其登记代理人；
     * 两事件均未关闭。批量先统一校验资源最终归属、空闲与重叠租约，任一冲突 422，整批回滚。
     * handoffKey 指纹含两事件版本、资源、时段与操作者，同键同参重放，同键改参 409，失败不占键。
     */
    @Transactional
    public HandoffBatchView createHandoffs(String sourceKey, String actor, HandoffCreateRequest req) {
        String targetKey = requireText(req.targetIncidentKey(), "targetIncidentKey");
        String receiver = requireText(req.receiver(), "receiver");
        List<HandoffItemRequest> rawItems = req.items();
        if (rawItems == null || rawItems.isEmpty()) {
            throw ApiException.badRequest("items 不能为空");
        }
        if (targetKey.equals(requireText(sourceKey, "incidentKey"))) {
            throw ApiException.unprocessable("HANDOFF_SAME_INCIDENT",
                    "目标事件不能等于来源事件: " + targetKey);
        }
        // 规范化并校验请求项（时段、批内键/资源去重）
        List<HandoffItemRequest> items = new ArrayList<>();
        Set<String> seenKeys = new java.util.HashSet<>();
        Set<String> seenResources = new java.util.HashSet<>();
        for (HandoffItemRequest raw : rawItems) {
            String handoffKey = requireText(raw.handoffKey(), "handoffKey");
            String resourceKey = requireText(raw.resourceKey(), "resourceKey");
            if (raw.leaseStart() == null || raw.leaseEnd() == null) {
                throw ApiException.badRequest("leaseStart/leaseEnd 不能为空");
            }
            Instant leaseStart = raw.leaseStart();
            Instant leaseEnd = raw.leaseEnd();
            if (!leaseEnd.isAfter(leaseStart)) {
                throw ApiException.unprocessable("HANDOFF_INVALID_LEASE",
                        "租约结束必须晚于开始: " + handoffKey);
            }
            if (!seenKeys.add(handoffKey)) {
                throw ApiException.badRequest("批内 handoffKey 重复: " + handoffKey);
            }
            if (!seenResources.add(resourceKey)) {
                throw ApiException.badRequest("批内同一资源只能交接一次: " + resourceKey);
            }
            items.add(new HandoffItemRequest(handoffKey, resourceKey, leaseStart, leaseEnd));
        }

        // 先非加锁确认两事件存在，再按事件主键升序加锁，
        // 保证 A→B 与 B→A 的并发交接以相同顺序持锁，避免死锁。
        Incident sourceProbe = incidents.findByKey(sourceKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + sourceKey));
        Incident targetProbe = incidents.findByKey(targetKey)
                .orElseThrow(() -> ApiException.notFound("目标事件不存在: " + targetKey));
        List<Long> incidentLockOrder = List.of(sourceProbe.id(), targetProbe.id()).stream()
                .sorted().toList();
        Map<Long, Incident> lockedIncidents = new java.util.LinkedHashMap<>();
        for (Long id : incidentLockOrder) {
            lockedIncidents.put(id, incidents.lockById(id).orElseThrow());
        }
        Incident source = lockedIncidents.get(sourceProbe.id());
        Incident target = lockedIncidents.get(targetProbe.id());
        requireCommander(source, actor);
        if (source.status() == IncidentStatus.CLOSED) {
            throw ApiException.unprocessable("HANDOFF_SOURCE_CLOSED",
                    "来源事件已关闭，不能交接资源: " + sourceKey);
        }
        if (target.status() == IncidentStatus.CLOSED) {
            throw ApiException.unprocessable("HANDOFF_TARGET_CLOSED",
                    "目标事件已关闭，不能接收资源: " + targetKey);
        }
        if (!receiver.equals(target.commander()) && !delegates.isDelegate(target.id(), receiver)) {
            throw ApiException.unprocessable("HANDOFF_RECEIVER_UNAUTHORIZED",
                    "接收人 " + receiver + " 不是目标事件当前指挥人或其登记接收代理人");
        }

        Instant now = now();
        List<Handoff> result = new ArrayList<>();
        // 第一阶段：区分已存在（重放）与新建项，已存在项校验指纹一致；新建项解析资源并确认归属
        List<HandoffItemRequest> newItems = new ArrayList<>();
        for (HandoffItemRequest item : items) {
            var existing = handoffs.findByKey(item.handoffKey());
            if (existing.isPresent()) {
                result.add(verifyReplay(existing.get(), item, source, target, actor, receiver));
            } else {
                Resource resource = resources.findByKey(item.resourceKey())
                        .orElseThrow(() -> ApiException.notFound(
                                "资源不存在: " + item.resourceKey()));
                if (resource.ownerIncidentId() != source.id()) {
                    throw ApiException.unprocessable("HANDOFF_RESOURCE_NOT_OWNED",
                            "来源事件不持有资源 " + resource.resourceKey());
                }
                newItems.add(item);
            }
        }
        // 第二阶段：按资源主键顺序一次性加锁，统一校验最终归属、空闲与重叠租约后才允许写入
        Map<String, Resource> lockedByKey = new java.util.LinkedHashMap<>();
        List<Long> newResourceIds = newItems.stream()
                .map(i -> resources.findByKey(i.resourceKey()).orElseThrow().id())
                .sorted().toList();
        for (Long rid : newResourceIds) {
            Resource locked = resources.lockById(rid).orElseThrow();
            if (locked.ownerIncidentId() != source.id()) {
                throw ApiException.unprocessable("HANDOFF_RESOURCE_NOT_OWNED",
                        "来源事件不持有资源 " + locked.resourceKey());
            }
            lockedByKey.put(locked.resourceKey(), locked);
        }
        // 先判定更具体的重叠租约，再判定资源仍被占用（LEASED_OUT 但租约不相邻/未归还）
        for (HandoffItemRequest item : newItems) {
            Resource locked = lockedByKey.get(item.resourceKey());
            if (handoffs.existsActiveOverlap(locked.id(), item.leaseStart(), item.leaseEnd())) {
                throw ApiException.unprocessable("HANDOFF_LEASE_OVERLAP",
                        "资源存在重叠的生效租约: " + item.resourceKey());
            }
            if (locked.status() != ResourceStatus.AVAILABLE) {
                throw ApiException.unprocessable("HANDOFF_RESOURCE_BUSY",
                        "资源已借出且未归还，不能重复转借: " + locked.resourceKey());
            }
        }
        // 第三阶段：全部校验通过，逐资源创建 ACTIVE 交接并置为 LEASED_OUT
        for (HandoffItemRequest item : newItems) {
            Resource locked = lockedByKey.get(item.resourceKey());
            long handoffId;
            try {
                handoffId = handoffs.insert(new Handoff(0L, item.handoffKey(), locked.id(),
                        source.id(), target.id(), source.version(), target.version(),
                        item.leaseStart(), item.leaseEnd(), actor, receiver,
                        HandoffStatus.ACTIVE, null, now));
            } catch (DuplicateKeyException e) {
                // 并发同键已由事件行锁与唯一约束串行化；此处兜底重放校验，读不到时给出明确冲突
                Handoff concurrent = handoffs.findByKey(item.handoffKey())
                        .orElseThrow(() -> ApiException.conflict(
                                "handoffKey 正被并发请求处理: " + item.handoffKey()));
                result.add(verifyReplay(concurrent, item, source, target, actor, receiver));
                continue;
            }
            resources.updateStatus(locked.id(), ResourceStatus.LEASED_OUT, now);
            result.add(handoffs.lockById(handoffId).orElseThrow());
        }
        List<HandoffView> views = result.stream().map(this::toHandoffView).toList();
        return new HandoffBatchView(sourceKey, targetKey, views);
    }

    /**
     * 校验同 handoffKey 重放与首次请求指纹（两事件版本、资源、时段、操作者）完全一致。
     */
    private Handoff verifyReplay(Handoff existing, HandoffItemRequest item,
                                 Incident source, Incident target, String actor,
                                 String receiver) {
        Resource resource = resources.findById(existing.resourceId())
                .orElseThrow(() -> ApiException.conflict("交接资源已不存在: " + item.resourceKey()));
        boolean same = existing.sourceIncidentId() == source.id()
                && existing.targetIncidentId() == target.id()
                && existing.sourceVersion() == source.version()
                && existing.targetVersion() == target.version()
                && resource.resourceKey().equals(item.resourceKey())
                && existing.leaseStart().equals(item.leaseStart())
                && existing.leaseEnd().equals(item.leaseEnd())
                && existing.operator().equals(actor)
                && existing.receiver().equals(receiver);
        if (!same) {
            throw ApiException.conflict(
                    "handoffKey 已被不同参数（含事件版本/资源/时段/操作者）的请求使用: "
                            + item.handoffKey());
        }
        return existing;
    }

    /**
     * 将通过交接借入的资源分配给目标事件的任务：仅目标当前指挥人；任务属目标事件且未终态；
     * 交接须 ACTIVE；当前时刻落在租约 [leaseStart, leaseEnd) 内（左闭右开）；
     * 任务同一时刻至多占用一个资源，重复分配同一交接幂等。
     */
    @Transactional
    public TaskView assignResourceToTask(String targetKey, String taskKey, String actor,
                                         TaskAssignResourceRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String handoffKey = requireText(req.handoffKey(), "handoffKey");
        Incident target = lockIncident(targetKey);
        return runIdempotent(commandKey, "task_assign_resource",
                hash(targetKey, taskKey, handoffKey, actor), TaskView.class, () -> {
                    requireCommander(target, actor);
                    IncidentTask task = tasks.findByKey(target.id(), taskKey)
                            .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskKey));
                    if (task.status() == TaskStatus.DONE || task.status() == TaskStatus.CANCELLED) {
                        throw ApiException.conflict("任务已终态，不能分配资源: " + taskKey);
                    }
                    Handoff handoff = handoffs.lockByKey(handoffKey)
                            .orElseThrow(() -> ApiException.notFound("交接不存在: " + handoffKey));
                    if (handoff.targetIncidentId() != target.id()) {
                        throw ApiException.unprocessable("HANDOFF_TARGET_MISMATCH",
                                "交接目标事件与路径事件不一致: " + handoffKey);
                    }
                    if (handoff.status() != HandoffStatus.ACTIVE) {
                        throw ApiException.unprocessable("HANDOFF_NOT_ACTIVE",
                                "交接已结算，不能再分配资源: " + handoffKey);
                    }
                    Instant now = now();
                    if (now.isBefore(handoff.leaseStart()) || !now.isBefore(handoff.leaseEnd())) {
                        throw ApiException.unprocessable("HANDOFF_OUT_OF_LEASE",
                                "当前时刻不在租约 [leaseStart, leaseEnd) 内: " + handoffKey);
                    }
                    if (task.assignedHandoffId() != null
                            && task.assignedHandoffId() != handoff.id()) {
                        throw ApiException.unprocessable("TASK_RESOURCE_CONFLICT",
                                "任务已占用其他互助资源，不能重复分配: " + taskKey);
                    }
                    if (task.assignedHandoffId() == null) {
                        // 资源同一时刻至多被目标事件的一个非终态任务占用（目标事件行已锁，串行化分配）
                        if (tasks.countActiveByResource(handoff.resourceId()) > 0) {
                            Resource occupied = resources.findById(handoff.resourceId()).orElseThrow();
                            throw ApiException.unprocessable("RESOURCE_IN_USE",
                                    "资源正被其他未终态任务占用，不能重复分配: "
                                            + occupied.resourceKey());
                        }
                        tasks.assignBorrowedResource(task.id(), handoff.resourceId(),
                                handoff.id(), now);
                    }
                    return toTaskView(tasks.findByKey(target.id(), taskKey).orElseThrow());
                });
    }

    /**
     * 租约到期结算检查：以注入 Clock 的当前时刻评估，不做定时扫描。
     * 结算目标事件所有当前时刻 ≥ leaseEnd 的 ACTIVE 交接：未开始任务同事务解绑归还，
     * 写入不可变结算（LEASE_EXPIRED）；若仍有已开始任务占用，则交接保持 ACTIVE 不归还。
     * 返回本次新结算的交接视图。同 commandKey 重放首次结果，失败不占键。
     */
    @Transactional
    public List<HandoffSettlementView> settleExpiredLeases(String targetKey,
                                                           HandoffSettleRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        Incident target = lockIncident(targetKey);
        return runIdempotent(commandKey, "lease_settle", hash(targetKey),
                HandoffSettlementListHolder.class, () -> {
                    Instant now = now();
                    List<HandoffSettlementView> settled = new ArrayList<>();
                    for (Handoff active : handoffs.listActiveByTarget(target.id())) {
                        if (!now.isBefore(active.leaseEnd())) {
                            settleIfNoStartedTask(active, SettlementReason.LEASE_EXPIRED, now)
                                    .ifPresent(settled::add);
                        }
                    }
                    return new HandoffSettlementListHolder(settled);
                }).views();
    }

    /**
     * 解绑一条 ACTIVE 交接下全部未开始（OPEN）任务，并返回是否仍有已开始（STARTED）任务占用。
     * 无论资源最终是否归还，未开始任务都须在同事务解除该资源。
     */
    private boolean detachOpenAndHasStarted(Handoff locked, Instant now) {
        tasks.unassignOpenTasksByHandoff(locked.id(), now);
        return !tasks.listStartedByHandoff(locked.id()).isEmpty();
    }

    /**
     * 租约到期结算一条交接：先解绑未开始任务；若仍有已开始任务占用则不归还（返回空），
     * 否则交接置 SETTLED、资源归还来源 AVAILABLE，并写一条不可变 LEASE_EXPIRED 结算。
     * 调用方须持有目标事件行锁，交接行在此加锁防并发重复结算。
     */
    private Optional<HandoffSettlementView> settleIfNoStartedTask(Handoff handoff,
                                                                  SettlementReason reason,
                                                                  Instant now) {
        Handoff locked = handoffs.lockById(handoff.id()).orElseThrow();
        if (locked.status() != HandoffStatus.ACTIVE) {
            return Optional.empty();
        }
        List<String> openTaskKeys = tasks.listOpenTaskKeysByHandoff(locked.id());
        boolean hasStarted = detachOpenAndHasStarted(locked, now);
        if (hasStarted) {
            return Optional.empty();
        }
        int updated = handoffs.markSettled(locked.id(), now);
        if (updated == 0) {
            // 并发已结算：保守放弃本次结算，交由唯一约束/状态裁决
            return Optional.empty();
        }
        Resource resource = resources.lockById(locked.resourceId()).orElseThrow();
        resources.updateStatus(resource.id(), ResourceStatus.AVAILABLE, now);
        String detail = openTaskKeys.isEmpty() ? "无未开始任务" : "解绑未开始任务: "
                + String.join(",", openTaskKeys);
        try {
            handoffs.insertSettlement(new HandoffSettlement(0L, locked.id(), reason.name(),
                    resource.resourceKey(), detail, now, now));
        } catch (DuplicateKeyException e) {
            // 结算已存在（并发/重放），不重复写入
        }
        return Optional.of(toSettlementView(handoffs.findSettlement(locked.id()).orElseThrow(),
                locked));
    }

    /**
     * 目标关闭结算：无论是否有已开始任务，未开始任务一律解绑归还来源；
     * 无已开始任务占用的交接立即 SETTLED（TARGET_CLOSED），仍被已开始任务占用的交接
     * 保持 ACTIVE，待任务终态自动归还。
     */
    private void settleOnTargetClosed(long targetIncidentId, Instant now) {
        for (Handoff active : handoffs.listActiveByTarget(targetIncidentId)) {
            Handoff locked = handoffs.lockById(active.id()).orElseThrow();
            if (locked.status() != HandoffStatus.ACTIVE) {
                continue;
            }
            List<String> openTaskKeys = tasks.listOpenTaskKeysByHandoff(locked.id());
            boolean hasStarted = detachOpenAndHasStarted(locked, now);
            if (!hasStarted) {
                completeSettlement(locked, SettlementReason.TARGET_CLOSED, openTaskKeys, now);
            }
            // 有已开始任务：交接保持 ACTIVE，资源仍 LEASED_OUT，待任务终态结算
        }
    }

    /**
     * 落库一条交接的最终结算：交接 SETTLED、资源归还来源 AVAILABLE、写不可变结算记录。
     */
    private void completeSettlement(Handoff locked, SettlementReason reason,
                                    List<String> detachedTaskKeys, Instant now) {
        handoffs.markSettled(locked.id(), now);
        Resource resource = resources.lockById(locked.resourceId()).orElseThrow();
        resources.updateStatus(resource.id(), ResourceStatus.AVAILABLE, now);
        String detail = detachedTaskKeys.isEmpty() ? "无未开始任务"
                : "解绑未开始任务: " + String.join(",", detachedTaskKeys);
        try {
            handoffs.insertSettlement(new HandoffSettlement(0L, locked.id(), reason.name(),
                    resource.resourceKey(), detail, now, now));
        } catch (DuplicateKeyException e) {
            // 结算记录已存在时不重复写入
        }
    }

    /**
     * 任务终态（DONE/CANCELLED）自动归还：清除任务占用；若该交接再无其他未终态任务占用，
     * 立即结算归还来源（原因按任务终态）。否则交接保持 ACTIVE。
     */
    private void settleBorrowedResourceOnTaskFinish(IncidentTask finishedTask,
                                                    SettlementReason reason, Instant now) {
        if (finishedTask.assignedHandoffId() == null) {
            return;
        }
        long handoffId = finishedTask.assignedHandoffId();
        tasks.unassignTask(finishedTask.id(), now);
        Handoff locked = handoffs.lockById(handoffId).orElseThrow();
        if (locked.status() != HandoffStatus.ACTIVE) {
            return;
        }
        if (!tasks.listActiveByHandoff(handoffId).isEmpty()) {
            return;
        }
        completeSettlement(locked, reason, List.of(), now);
    }

    /**
     * 按事件分组查询任务（含阻塞状态，按目标事件当前状态计算）。只读，不隐式写入。
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
     * 查询单任务明细（含阻塞状态）。只读，不隐式写入。
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
     * 仍未解除阻塞的事件键列表：目标事件已进入的状态说明阻塞已解除。
     */
    private List<String> unresolvedBlockers(long taskId) {
        return incidents.listBlockingIncidents(taskId).stream()
                .filter(b -> !UNBLOCKING_STATUSES.contains(b.status()))
                .map(Incident::incidentKey)
                .toList();
    }

    /**
     * 查询事件登记的互助资源列表。只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public List<ResourceView> listResources(String incidentKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        return resources.listByOwner(incident.id()).stream().map(this::toResourceView).toList();
    }

    /**
     * 查询资源当前责任：来源自持（SOURCE）或目标事件借用中（TARGET，含超期但被已开始任务占用）。
     * 只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public ResourceResponsibilityView getResourceResponsibility(String resourceKey) {
        Resource resource = resources.findByKey(resourceKey)
                .orElseThrow(() -> ApiException.notFound("资源不存在: " + resourceKey));
        Incident owner = incidents.findById(resource.ownerIncidentId()).orElseThrow();
        var activeOpt = handoffs.findActiveByResource(resource.id());
        if (activeOpt.isEmpty()) {
            return new ResourceResponsibilityView(toResourceView(resource), "SOURCE",
                    owner.incidentKey(), null, null, null, null);
        }
        Handoff active = activeOpt.get();
        Incident responsibleIncident = incidents.findById(active.targetIncidentId()).orElseThrow();
        return new ResourceResponsibilityView(toResourceView(resource), "TARGET",
                responsibleIncident.incidentKey(), active.handoffKey(), active.leaseStart(),
                active.leaseEnd(), active.operator());
    }

    /**
     * 查询资源的交接与不可变结算历史。只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public List<HandoffView> getResourceHandoffs(String resourceKey) {
        Resource resource = resources.findByKey(resourceKey)
                .orElseThrow(() -> ApiException.notFound("资源不存在: " + resourceKey));
        return handoffs.listByResource(resource.id()).stream().map(this::toHandoffView).toList();
    }

    /**
     * 查询资源的不可变结算记录。只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public List<HandoffSettlementView> getResourceSettlements(String resourceKey) {
        Resource resource = resources.findByKey(resourceKey)
                .orElseThrow(() -> ApiException.notFound("资源不存在: " + resourceKey));
        return handoffs.listSettlementsByResource(resource.id()).stream()
                .map(s -> {
                    Handoff h = handoffs.findById(s.handoffId()).orElseThrow();
                    return toSettlementView(s, h);
                }).toList();
    }

    /**
     * 查询关闭阻断原因：返回是否阻断及可区分原因（借出未归还资源、事件状态/门禁）。
     * 只做检查，不改变状态，也不执行结算。
     */
    @Transactional(readOnly = true)
    public CloseBlockerView getCloseBlockers(String incidentKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        List<String> reasons = new ArrayList<>();
        List<String> resourceKeys = new ArrayList<>();
        if (incident.status() != IncidentStatus.RESOLVED) {
            reasons.add("事件当前状态为 " + incident.status() + "，仅 RESOLVED 可关闭");
        }
        List<Handoff> lent = handoffs.listActiveBySource(incident.id());
        if (!lent.isEmpty()) {
            reasons.add("仍有借出未归还（ACTIVE）的互助资源，来源事件不可关闭");
            for (Handoff h : lent) {
                resources.findById(h.resourceId()).map(Resource::resourceKey)
                        .ifPresent(resourceKeys::add);
            }
        }
        return new CloseBlockerView(incident.incidentKey(), incident.status().name(),
                !reasons.isEmpty(), List.copyOf(reasons),
                resourceKeys.stream().distinct().toList());
    }

    private ResourceView toResourceView(Resource resource) {
        Incident owner = incidents.findById(resource.ownerIncidentId()).orElseThrow();
        return new ResourceView(resource.resourceKey(), owner.incidentKey(), resource.label(),
                resource.registeredBy(), resource.status().name(), resource.createdAt(),
                resource.updatedAt());
    }

    private HandoffView toHandoffView(Handoff handoff) {
        Resource resource = resources.findById(handoff.resourceId()).orElseThrow();
        Incident source = incidents.findById(handoff.sourceIncidentId()).orElseThrow();
        Incident target = incidents.findById(handoff.targetIncidentId()).orElseThrow();
        return new HandoffView(handoff.handoffKey(), resource.resourceKey(),
                source.incidentKey(), target.incidentKey(), handoff.sourceVersion(),
                handoff.targetVersion(), handoff.leaseStart(), handoff.leaseEnd(),
                handoff.operator(), handoff.receiver(), handoff.status().name(),
                handoff.settledAt(), handoff.createdAt());
    }

    private HandoffSettlementView toSettlementView(HandoffSettlement settlement, Handoff handoff) {
        return new HandoffSettlementView(handoff.handoffKey(), settlement.reason(),
                settlement.returnedResourceKey(), settlement.detail(),
                settlement.settledAt(), settlement.createdAt());
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

    /**
     * 幂等执行：同键同参重放首次响应，同键改参 409；并发同键由唯一约束串行化。
     */
    private <T> T runIdempotent(String commandKey, String operation, String requestHash,
                                Class<T> type, Supplier<T> business) {
        var existing = commandKeys.find(commandKey);
        if (existing.isPresent()) {
            return replay(existing.get(), operation, requestHash, type);
        }
        try {
            commandKeys.insertPlaceholder(commandKey, operation, requestHash, now());
        } catch (DuplicateKeyException e) {
            var committed = commandKeys.findForUpdate(commandKey)
                    .orElseThrow(() -> ApiException.conflict("commandKey 处理冲突: " + commandKey));
            return replay(committed, operation, requestHash, type);
        }
        T result = business.get();
        commandKeys.fillResponse(commandKey, 200, toJson(result));
        return result;
    }

    private <T> T replay(CommandKeyRecord record, String operation, String requestHash, Class<T> type) {
        if (!record.operation().equals(operation) || !record.requestHash().equals(requestHash)) {
            throw ApiException.conflict("commandKey 已被不同参数的请求使用: " + record.commandKey());
        }
        if (record.responseBody() == null) {
            throw ApiException.conflict("commandKey 正在处理中: " + record.commandKey());
        }
        try {
            return objectMapper.readValue(record.responseBody(), type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private static String hash(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(String.join(SEP, parts).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
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
     * 组装任务视图：阻塞状态按目标事件查询时的当前状态计算，不写回依赖任务；
     * 借用资源/交接在仍占用时返回业务键，解绑归还后为空。
     */
    private TaskView toTaskView(IncidentTask task) {
        List<TaskBlockerView> blockers = incidents.listBlockingIncidents(task.id()).stream()
                .map(b -> new TaskBlockerView(b.incidentKey(), b.status().name(),
                        UNBLOCKING_STATUSES.contains(b.status())))
                .toList();
        String assignedResourceKey = null;
        String assignedHandoffKey = null;
        if (task.assignedHandoffId() != null) {
            assignedHandoffKey = handoffs.findById(task.assignedHandoffId())
                    .map(Handoff::handoffKey).orElse(null);
        }
        if (task.assignedResourceId() != null) {
            assignedResourceKey = resources.findById(task.assignedResourceId())
                    .map(Resource::resourceKey).orElse(null);
        }
        return new TaskView(task.taskKey(), task.groupCode(), task.title(), task.status().name(),
                blockers, task.createdBy(), task.createdAt(),
                task.startedBy(), task.startedAt(), assignedResourceKey, assignedHandoffKey,
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
