package com.example.starter.incident;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import com.example.starter.incident.dto.Requests.ActionRequest;
import com.example.starter.incident.dto.Requests.AgencyAckRequest;
import com.example.starter.incident.dto.Requests.AgencyConfigRequest;
import com.example.starter.incident.dto.Requests.EscalationAckRequest;
import com.example.starter.incident.dto.Requests.EscalationCheckRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TransferAcceptRequest;
import com.example.starter.incident.dto.Requests.TransferRequest;
import com.example.starter.incident.dto.Responses.ActionView;
import com.example.starter.incident.dto.Responses.AgencyAckView;
import com.example.starter.incident.dto.Responses.AgencyConfigVersionView;
import com.example.starter.incident.dto.Responses.AgencyGateView;
import com.example.starter.incident.dto.Responses.EscalationHistoryView;
import com.example.starter.incident.dto.Responses.EscalationView;
import com.example.starter.incident.dto.Responses.HistoryView;
import com.example.starter.incident.dto.Responses.IncidentTasksView;
import com.example.starter.incident.dto.Responses.IncidentView;
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
    private final AgencyRepository agencies;
    private final AgencyAckKeyRepository ackKeys;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IncidentService(IncidentRepository incidents, EscalationRepository escalations,
                           IncidentTaskRepository tasks, CommandKeyRepository commandKeys,
                           AgencyRepository agencies, AgencyAckKeyRepository ackKeys,
                           ObjectMapper objectMapper, Clock clock) {
        this.incidents = incidents;
        this.escalations = escalations;
        this.tasks = tasks;
        this.commandKeys = commandKeys;
        this.agencies = agencies;
        this.ackKeys = ackKeys;
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
                IncidentStatus.REPORTED, null, now, now, null, null);
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
                        // 解决门禁：全部处置任务进入 DONE/CANCELLED 后才可解决
                        List<UnfinishedTaskView> unfinished = tasks
                                .listOpenByIncident(incident.id()).stream()
                                .map(t -> new UnfinishedTaskView(t.groupCode(), t.taskKey()))
                                .toList();
                        if (!unfinished.isEmpty()) {
                            throw ApiException.conflict(
                                    "仍有未完成的处置任务，不能进入 RESOLVED", unfinished);
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
        final TaskPriority priority;
        try {
            priority = TaskPriority.parse(req.priority());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("priority 必须为 HIGH 或 NORMAL");
        }
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
                hash(incidentKey, actor, taskKey, groupCode, title, priority.name(),
                        String.join(",", blockerKeys)),
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
                                || found.priority() != priority
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
                            groupCode, title, priority, TaskStatus.OPEN, actor, null, null, null,
                            null, now, now));
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
                    if (task.status() != TaskStatus.OPEN) {
                        throw ApiException.conflict(
                                "任务已处于终态 " + task.status() + "，不能完成");
                    }
                    List<String> unresolved = unresolvedBlockers(task.id());
                    if (!unresolved.isEmpty()) {
                        throw ApiException.conflict("存在未解除阻塞的事件: "
                                + String.join(",", unresolved), List.copyOf(unresolved));
                    }
                    if (task.priority() == TaskPriority.HIGH) {
                        AgencyGateState gate = evaluateGate(incident.id());
                        if (gate.rejected()) {
                            throw ApiException.agencyGate("存在必需外部机构拒绝回执，HIGH 任务不可完成",
                                    List.copyOf(gate.unconfirmed()));
                        }
                        if (!gate.unconfirmed().isEmpty()) {
                            throw ApiException.agencyGate("仍有必需外部机构未确认: "
                                    + String.join(",", gate.unconfirmed()),
                                    List.copyOf(gate.unconfirmed()));
                        }
                    }
                    tasks.markDone(task.id(), actor, now());
                    return toTaskView(tasks.findByKey(incident.id(), taskKey).orElseThrow());
                });
    }

    /**
     * 取消任务：仅当前指挥人；仅 OPEN 可取消；DONE/CANCELLED 为终态，
     * 重复操作按 commandKey 幂等规则返回首次结果。
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
                    if (task.status() != TaskStatus.OPEN) {
                        throw ApiException.conflict(
                                "任务已处于终态 " + task.status() + "，不能取消");
                    }
                    tasks.markCancelled(task.id(), actor, now());
                    return toTaskView(tasks.findByKey(incident.id(), taskKey).orElseThrow());
                });
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
     * 组装任务视图：阻塞状态按目标事件查询时的当前状态计算，不写回依赖任务。
     * gateReason 仅 HIGH 任务返回：为当前配置版本下尚未确认（含已拒绝）的机构代码列表，
     * 全部确认后为空列表；NORMAL 任务不受机构门禁，返回 null。
     */
    private TaskView toTaskView(IncidentTask task) {
        List<TaskBlockerView> blockers = incidents.listBlockingIncidents(task.id()).stream()
                .map(b -> new TaskBlockerView(b.incidentKey(), b.status().name(),
                        UNBLOCKING_STATUSES.contains(b.status())))
                .toList();
        List<String> gateReason = null;
        if (task.priority() == TaskPriority.HIGH) {
            gateReason = List.copyOf(evaluateGate(task.incidentId()).unconfirmed());
        }
        return new TaskView(task.taskKey(), task.groupCode(), task.title(),
                task.priority().name(), task.status().name(), blockers, task.createdBy(),
                task.createdAt(), task.doneBy(), task.doneAt(), task.cancelledBy(),
                task.cancelledAt(), gateReason);
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

    /**
     * 当前配置版本的门禁评估结果：version 为评估的配置版本（未配置为 0）；
     * rejected 表示该版本存在任一拒绝回执；unconfirmed 为尚未确认（含已拒绝）的机构代码，
     * 已按字典序排序。未配置或空配置时 unconfirmed 为空。
     */
    private record AgencyGateState(int version, boolean rejected, List<String> unconfirmed) {
    }

    /**
     * 按事件当前（最新）配置版本评估机构门禁：必需机构中无 CONFIRM 终态回执的即为未确认。
     * 只读计算，不写库。
     */
    private AgencyGateState evaluateGate(long incidentId) {
        var latest = agencies.findLatestConfig(incidentId);
        if (latest.isEmpty()) {
            return new AgencyGateState(0, false, List.of());
        }
        AgencyConfig config = latest.get();
        List<AgencyAck> acks = agencies.listAcksByVersion(incidentId, config.version());
        Set<String> confirmed = new HashSet<>();
        boolean rejected = false;
        for (AgencyAck ack : acks) {
            if (ack.ackType() == AgencyAckType.CONFIRM) {
                confirmed.add(ack.agencyCode());
            } else {
                rejected = true;
            }
        }
        List<String> unconfirmed = config.agencyCodes().stream()
                .filter(code -> !confirmed.contains(code))
                .sorted()
                .toList();
        return new AgencyGateState(config.version(), rejected, unconfirmed);
    }

    /**
     * 修改必需外部机构配置：仅当前指挥人；CLOSED 事件返回 409。
     * expectedVersion 为指挥人上次见到的版本（首次配置传 0 或 null），
     * 与当前版本不一致返回 409（乐观并发）；机构代码去重后按字典序落库，
     * 空集合合法，至多 5 个。修改成功新增一个配置版本，旧版本与旧回执永久保留；
     * 若事件处于 EXTERNAL_BLOCKED 且新版本门禁已满足（如无必需机构），
     * 同事务恢复到阻断前状态。
     */
    @Transactional
    public AgencyGateView configureAgencies(String incidentKey, String actor,
                                            AgencyConfigRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        int expectedVersion = req.expectedVersion() == null ? 0 : req.expectedVersion();
        if (expectedVersion < 0) {
            throw ApiException.badRequest("expectedVersion 不能为负数");
        }
        List<String> codes = req.agencyCodes() == null ? List.of()
                : req.agencyCodes().stream()
                        .map(c -> requireText(c, "agencyCode"))
                        .distinct()
                        .sorted()
                        .toList();
        if (codes.size() > 5) {
            throw ApiException.badRequest("必需外部机构最多 5 个");
        }
        Incident incident = lockIncident(incidentKey);
        return runIdempotent(commandKey, "agency_config",
                hash(incidentKey, actor, String.valueOf(expectedVersion), String.join(",", codes)),
                AgencyGateView.class, () -> {
                    requireCommander(incident, actor);
                    if (incident.status() == IncidentStatus.CLOSED) {
                        throw ApiException.conflict("事件已关闭，不能修改机构配置");
                    }
                    int currentVersion = agencies.findLatestConfig(incident.id())
                            .map(AgencyConfig::version).orElse(0);
                    if (expectedVersion != currentVersion) {
                        throw ApiException.conflict("配置版本已变化：期望 " + expectedVersion
                                + "，当前 " + currentVersion + "，请刷新后重试");
                    }
                    int newVersion = currentVersion + 1;
                    Instant now = now();
                    agencies.insertConfig(new AgencyConfig(0L, incident.id(), newVersion,
                            codes, actor, now));
                    // 阻断态下替换配置：旧回执仅归属旧版本（不回写），按新版本重新计算门禁；
                    // 新版本无必需机构即视为门禁满足，同事务恢复阻断前状态
                    if (incident.status() == IncidentStatus.EXTERNAL_BLOCKED
                            && incident.blockedFromStatus() != null) {
                        AgencyGateState gate = evaluateGate(incident.id());
                        if (!gate.rejected() && gate.unconfirmed().isEmpty()) {
                            incidents.restoreFromExternalBlocked(incident.id(),
                                    incident.blockedFromStatus(), now);
                            incidents.insertStatusChange(new StatusChange(0L, incident.id(),
                                    IncidentStatus.EXTERNAL_BLOCKED, incident.blockedFromStatus(),
                                    actor, now));
                        }
                    }
                    return toAgencyGateView(incident.incidentKey(), incident.id());
                });
    }

    /**
     * 提交机构终态回执：ackKey 为机构侧幂等键（独立命名空间），指纹含机构、事件版本、
     * 回执类型与说明，成功重放首个响应，业务失败事务回滚不占键。
     * 回执归属提交时的当前配置版本；同一机构每版本仅一条终态回执，重复提交返回 409。
     * REJECT 必须携带非空说明；任一必需机构拒绝时事件转为 EXTERNAL_BLOCKED。
     * CLOSED 事件不再受理回执，返回 409。
     */
    @Transactional
    public AgencyAckView submitAgencyAck(String incidentKey, String actor, AgencyAckRequest req) {
        String ackKey = requireText(req.ackKey(), "ackKey");
        String agencyCode = requireText(req.agencyCode(), "agencyCode");
        String ackTypeText = requireText(req.ackType(), "ackType");
        AgencyAckType ackType;
        try {
            ackType = AgencyAckType.valueOf(ackTypeText.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("ackType 必须为 CONFIRM 或 REJECT");
        }
        String reason = req.reason() == null ? null : req.reason().strip();
        if (ackType == AgencyAckType.REJECT && (reason == null || reason.isEmpty())) {
            throw ApiException.badRequest("拒绝回执必须填写非空 reason");
        }
        if (ackType == AgencyAckType.CONFIRM) {
            reason = null;
        }
        String submittedBy = requireText(actor, "X-Actor-Id");
        Incident incident = lockIncident(incidentKey);
        AgencyConfig current = agencies.findLatestConfig(incident.id())
                .orElseThrow(() -> ApiException.conflict("事件尚未配置必需机构，不能提交回执"));
        final String finalReason = reason;
        String requestHash = hash(incidentKey, agencyCode, String.valueOf(current.version()),
                ackType.name(), finalReason == null ? "" : finalReason);
        return runAckIdempotent(ackKey, requestHash, () -> {
            if (incident.status() == IncidentStatus.CLOSED) {
                throw ApiException.conflict("事件已关闭，不再受理机构回执");
            }
            if (!current.agencyCodes().contains(agencyCode)) {
                throw ApiException.conflict("机构 " + agencyCode + " 不在当前配置版本 "
                        + current.version() + " 的必需机构列表中");
            }
            var existing = agencies.findAck(incident.id(), current.version(), agencyCode);
            if (existing.isPresent()) {
                throw ApiException.conflict("机构 " + agencyCode + " 在配置版本 "
                        + current.version() + " 已存在终态回执，不能重复提交");
            }
            Instant now = now();
            long ackId = agencies.insertAck(new AgencyAck(0L, incident.id(), current.version(),
                    agencyCode, ackType, finalReason, submittedBy, now));
            if (incident.status() != IncidentStatus.EXTERNAL_BLOCKED
                    && ackType == AgencyAckType.REJECT) {
                // 任一必需机构拒绝：事件进入外部阻断态，保存阻断前状态
                incidents.markExternalBlocked(incident.id(), incident.status(), now);
                incidents.insertStatusChange(new StatusChange(0L, incident.id(),
                        incident.status(), IncidentStatus.EXTERNAL_BLOCKED, submittedBy, now));
            } else if (incident.status() == IncidentStatus.EXTERNAL_BLOCKED
                    && incident.blockedFromStatus() != null) {
                // 阻断态下提交回执：按当前版本重新计算门禁，全部确认且无拒绝时解除阻断
                AgencyGateState gate = evaluateGate(incident.id());
                if (!gate.rejected() && gate.unconfirmed().isEmpty()) {
                    incidents.restoreFromExternalBlocked(incident.id(),
                            incident.blockedFromStatus(), now);
                    incidents.insertStatusChange(new StatusChange(0L, incident.id(),
                            IncidentStatus.EXTERNAL_BLOCKED, incident.blockedFromStatus(),
                            submittedBy, now));
                }
            }
            AgencyAck saved = agencies.listAcksByVersion(incident.id(), current.version())
                    .stream().filter(a -> a.id() == ackId).findFirst().orElseThrow();
            return toAgencyAckView(saved);
        });
    }

    /**
     * 查询机构配置版本与全部历史回执（只读，不隐式写入）。
     */
    @Transactional(readOnly = true)
    public AgencyGateView agencyGate(String incidentKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        return toAgencyGateView(incident.incidentKey(), incident.id());
    }

    /**
     * 回执幂等执行：同键同参重放首次响应，同键改参 409；并发同键由唯一约束串行化。
     * 业务失败抛异常时事务回滚，占位行随之回滚（失败不占键）。
     */
    private AgencyAckView runAckIdempotent(String ackKey, String requestHash,
                                           Supplier<AgencyAckView> business) {
        var existing = ackKeys.find(ackKey);
        if (existing.isPresent()) {
            return replayAck(existing.get(), requestHash);
        }
        try {
            ackKeys.insertPlaceholder(ackKey, requestHash, now());
        } catch (DuplicateKeyException e) {
            var committed = ackKeys.findForUpdate(ackKey)
                    .orElseThrow(() -> ApiException.conflict("ackKey 处理冲突: " + ackKey));
            return replayAck(committed, requestHash);
        }
        AgencyAckView result = business.get();
        ackKeys.fillResponse(ackKey, 200, toJson(result));
        return result;
    }

    private AgencyAckView replayAck(AgencyAckKeyRepository.AckKeyRecord record, String requestHash) {
        if (!record.requestHash().equals(requestHash)) {
            throw ApiException.conflict("ackKey 已被不同参数的回执使用: " + record.ackKey());
        }
        if (record.responseBody() == null) {
            throw ApiException.conflict("ackKey 正在处理中: " + record.ackKey());
        }
        try {
            return objectMapper.readValue(record.responseBody(), AgencyAckView.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("回执幂等响应反序列化失败", e);
        }
    }

    private AgencyGateView toAgencyGateView(String incidentKey, long incidentId) {
        List<AgencyConfig> configs = agencies.listConfigs(incidentId);
        int currentVersion = configs.isEmpty() ? 0 : configs.get(configs.size() - 1).version();
        List<AgencyConfigVersionView> configViews = configs.stream()
                .map(c -> new AgencyConfigVersionView(c.version(), c.agencyCodes(),
                        c.version() == currentVersion, c.createdBy(), c.createdAt()))
                .toList();
        List<AgencyAckView> ackViews = agencies.listAllAcks(incidentId).stream()
                .map(IncidentService::toAgencyAckView)
                .toList();
        return new AgencyGateView(incidentKey, currentVersion, configViews, ackViews);
    }

    private static AgencyAckView toAgencyAckView(AgencyAck ack) {
        return new AgencyAckView(ack.configVersion(), ack.agencyCode(), ack.ackType().name(),
                ack.reason(), ack.submittedBy(), ack.submittedAt());
    }
}
