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
import java.util.Set;
import java.util.function.Supplier;

import com.example.starter.incident.dto.Requests.ActionRequest;
import com.example.starter.incident.dto.Requests.CredentialRegisterRequest;
import com.example.starter.incident.dto.Requests.CredentialRevokeRequest;
import com.example.starter.incident.dto.Requests.EscalationAckRequest;
import com.example.starter.incident.dto.Requests.EscalationCheckRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TaskDispatchRequest;
import com.example.starter.incident.dto.Requests.TaskStartRequest;
import com.example.starter.incident.dto.Requests.TransferAcceptRequest;
import com.example.starter.incident.dto.Requests.TransferRequest;
import com.example.starter.incident.dto.Responses.ActionView;
import com.example.starter.incident.dto.Responses.CredentialView;
import com.example.starter.incident.dto.Responses.EscalationHistoryView;
import com.example.starter.incident.dto.Responses.EscalationView;
import com.example.starter.incident.dto.Responses.HistoryView;
import com.example.starter.incident.dto.Responses.IncidentTasksView;
import com.example.starter.incident.dto.Responses.IncidentView;
import com.example.starter.incident.dto.Responses.StatusChangeView;
import com.example.starter.incident.dto.Responses.TaskBlockerView;
import com.example.starter.incident.dto.Responses.TaskCredentialView;
import com.example.starter.incident.dto.Responses.TaskDispatchView;
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
    private final ResourceCredentialRepository credentials;
    private final TaskDispatchRepository dispatches;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IncidentService(IncidentRepository incidents, EscalationRepository escalations,
                           IncidentTaskRepository tasks, CommandKeyRepository commandKeys,
                           ResourceCredentialRepository credentials,
                           TaskDispatchRepository dispatches,
                           ObjectMapper objectMapper, Clock clock) {
        this.incidents = incidents;
        this.escalations = escalations;
        this.tasks = tasks;
        this.commandKeys = commandKeys;
        this.credentials = credentials;
        this.dispatches = dispatches;
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
                            groupCode, title, TaskStatus.OPEN, actor, null, null, null, null,
                            now, now, null, null, null));
                    for (Incident blocker : blockers) {
                        tasks.insertBlocker(taskId, blocker.id(), now);
                    }
                    return toTaskView(tasks.findByKey(incident.id(), taskKey).orElseThrow());
                });
    }

    /**
     * 完成任务：仅当前指挥人；未派发的 OPEN 任务或已通过执行前检查的 EXECUTING 任务可完成；
     * DISPATCHED 必须先通过执行前检查进入 EXECUTING；BLOCKED 必须凭有效新凭据再派发并通过检查。
     * 全部阻塞事件进入 CONTAINED/RESOLVED/CLOSED 后才可完成，否则 409 并返回未解除事件列表。
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
                    if (task.status() == TaskStatus.DISPATCHED) {
                        throw ApiException.conflict("任务已派发但尚未通过执行前凭据检查，不能完成");
                    }
                    if (task.status() == TaskStatus.BLOCKED) {
                        throw ApiException.conflict("任务因凭据问题被阻断，不能完成；请使用有效新凭据重新派发");
                    }
                    if (task.status() != TaskStatus.OPEN && task.status() != TaskStatus.EXECUTING) {
                        throw ApiException.conflict(
                                "任务已处于终态 " + task.status() + "，不能完成");
                    }
                    List<String> unresolved = unresolvedBlockers(task.id());
                    if (!unresolved.isEmpty()) {
                        throw ApiException.conflict("存在未解除阻塞的事件: "
                                + String.join(",", unresolved), List.copyOf(unresolved));
                    }
                    int updated = tasks.markDone(task.id(), actor, now());
                    if (updated == 0) {
                        throw ApiException.conflict("任务状态已被并发改变，不能完成");
                    }
                    return toTaskView(tasks.findByKey(incident.id(), taskKey).orElseThrow());
                });
    }

    /**
     * 取消任务：仅当前指挥人；OPEN/DISPATCHED/BLOCKED 可取消；EXECUTING 执行已启动不可取消；
     * DONE/CANCELLED 为终态，重复操作按 commandKey 幂等规则返回首次结果。
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
                    if (task.status() == TaskStatus.EXECUTING) {
                        throw ApiException.conflict("任务执行已启动，不能取消");
                    }
                    if (task.status() != TaskStatus.OPEN
                            && task.status() != TaskStatus.DISPATCHED
                            && task.status() != TaskStatus.BLOCKED) {
                        throw ApiException.conflict(
                                "任务已处于终态 " + task.status() + "，不能取消");
                    }
                    int updated = tasks.markCancelled(task.id(), actor, now());
                    if (updated == 0) {
                        throw ApiException.conflict("任务状态已被并发改变，不能取消");
                    }
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
     * 登记资源凭据新版本：(resourceKey, version) 唯一，版本内容不可变；
     * 更新有效凭据通过登记新版本实现。同 commandKey 同参重放首次结果，同键改参 409；
     * 不同 commandKey 但同资源同版本且内容一致亦幂等返回，内容冲突 409。
     */
    @Transactional
    public CredentialView registerCredential(CredentialRegisterRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String resourceKey = requireText(req.resourceKey(), "resourceKey");
        String version = requireText(req.version(), "version");
        String credentialType = requireText(req.credentialType(), "credentialType");
        String region = requireText(req.region(), "region");
        if (req.expiresAt() == null) {
            throw ApiException.badRequest("expiresAt 不能为空");
        }
        Instant expiresAt = req.expiresAt();
        return runIdempotent(commandKey, "credential_register",
                hash(resourceKey, version, credentialType, region, expiresAt.toString()),
                CredentialView.class, () -> {
                    var existing = credentials.findByKey(resourceKey, version);
                    if (existing.isPresent()) {
                        ResourceCredential found = existing.get();
                        if (!found.credentialType().equals(credentialType)
                                || !found.region().equals(region)
                                || !found.expiresAt().equals(expiresAt)) {
                            throw ApiException.conflict(
                                    "资源凭据版本已被不同内容使用: " + resourceKey + "/" + version);
                        }
                        return toCredentialView(found);
                    }
                    Instant now = now();
                    ResourceCredential credential = new ResourceCredential(0L, resourceKey, version,
                            credentialType, region, CredentialStatus.ACTIVE, expiresAt, null, now, now);
                    try {
                        credentials.insert(credential);
                    } catch (DuplicateKeyException e) {
                        ResourceCredential committed = credentials.findByKey(resourceKey, version)
                                .orElseThrow();
                        if (!committed.credentialType().equals(credentialType)
                                || !committed.region().equals(region)
                                || !committed.expiresAt().equals(expiresAt)) {
                            throw ApiException.conflict(
                                    "资源凭据版本已被不同内容使用: " + resourceKey + "/" + version);
                        }
                        return toCredentialView(committed);
                    }
                    return toCredentialView(credentials.findByKey(resourceKey, version).orElseThrow());
                });
    }

    /**
     * 撤销资源凭据（不可逆）：与派发/执行前检查互斥，只按已提交状态生效。
     * 重复撤销同版本幂等返回当前视图；凭据不存在 404。
     */
    @Transactional
    public CredentialView revokeCredential(String resourceKey, String version, String actor,
                                           CredentialRevokeRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        requireText(resourceKey, "resourceKey");
        requireText(version, "version");
        return runIdempotent(commandKey, "credential_revoke",
                hash(resourceKey, version, actor), CredentialView.class, () -> {
                    List<ResourceCredential> locked = credentials.lockByResource(resourceKey);
                    ResourceCredential credential = locked.stream()
                            .filter(c -> c.version().equals(version)).findFirst()
                            .orElseThrow(() -> ApiException.notFound(
                                    "资源凭据不存在: " + resourceKey + "/" + version));
                    if (credential.status() == CredentialStatus.ACTIVE) {
                        int updated = credentials.revoke(credential.id(), now());
                        if (updated == 0) {
                            throw ApiException.conflict("凭据已被并发撤销: "
                                    + resourceKey + "/" + version);
                        }
                    }
                    return toCredentialView(
                            credentials.findByKey(resourceKey, version).orElseThrow());
                });
    }

    /**
     * 查询资源全部凭据版本（按登记顺序）。只读。
     */
    @Transactional(readOnly = true)
    public List<CredentialView> listCredentials(String resourceKey) {
        requireText(resourceKey, "resourceKey");
        return credentials.listByResource(resourceKey).stream()
                .map(IncidentService::toCredentialView).toList();
    }

    /**
     * 派发任务（首次派发或 BLOCKED 后再派发）：仅当前指挥人；任务须为 OPEN 或 BLOCKED。
     * 派发时校验凭据同类型、覆盖任务区域、ACTIVE 且 UTC 有效期未结束；通过后追加不可变派发记录
     * （快照资源、版本、类型、区域、失效时刻）并将任务置为 DISPATCHED。
     * 以 requestId 作为派发幂等键：同键重放首次任务与凭据版本（原样返回首次响应），
     * 换资源/换版本/换任务/换区域返回 409；失败回滚不占用 requestId。
     */
    @Transactional
    public TaskView dispatchTask(String incidentKey, String taskKey, String actor,
                                 TaskDispatchRequest req) {
        String requestId = requireText(req.requestId(), "requestId");
        String resourceKey = requireText(req.resourceKey(), "resourceKey");
        String credentialVersion = requireText(req.credentialVersion(), "credentialVersion");
        String requiredType = requireText(req.requiredType(), "requiredType");
        String region = requireText(req.region(), "region");
        Incident incident = lockIncident(incidentKey);
        return runIdempotent(requestId, "task_dispatch",
                hash(incidentKey, taskKey, actor, resourceKey, credentialVersion,
                        requiredType, region),
                TaskView.class, () -> {
                    requireCommander(incident, actor);
                    IncidentTask task = tasks.findByKey(incident.id(), taskKey)
                            .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskKey));
                    if (task.status() != TaskStatus.OPEN && task.status() != TaskStatus.BLOCKED) {
                        if (task.status() == TaskStatus.DISPATCHED
                                || task.status() == TaskStatus.EXECUTING) {
                            throw ApiException.conflict(
                                    "任务已派发且在执行流程中（状态 " + task.status()
                                            + "），不能重复派发");
                        }
                        throw ApiException.conflict(
                                "任务已处于终态 " + task.status() + "，不能派发");
                    }
                    // 锁定凭据资源全部版本行，与撤销互斥，按已提交状态校验
                    ResourceCredential credential = requireUsableCredential(
                            resourceKey, credentialVersion, requiredType, region);
                    Instant now = now();
                    int seq = dispatches.maxSeq(task.id()) + 1;
                    TaskDispatch dispatch = new TaskDispatch(0L, task.id(), seq, requestId,
                            resourceKey, credentialVersion, requiredType, region,
                            credential.expiresAt(), DispatchStatus.DISPATCHED, null, null,
                            now, actor, null);
                    long dispatchId;
                    try {
                        dispatchId = dispatches.insert(dispatch);
                    } catch (DuplicateKeyException e) {
                        // 并发同 requestId 已提交：由 runIdempotent 占位兜底，此处防御性转冲突
                        throw ApiException.conflict("派发 requestId 处理冲突: " + requestId);
                    }
                    int updated = tasks.applyDispatch(task.id(), dispatchId, now);
                    if (updated == 0) {
                        throw ApiException.conflict("任务状态已被并发改变，不能派发");
                    }
                    return toTaskView(tasks.findByKey(incident.id(), taskKey).orElseThrow());
                });
    }

    /**
     * 执行前检查与执行启动：仅当前指挥人；任务须为 DISPATCHED。
     * 锁定任务行与凭据资源行后，只按已提交的凭据状态重新校验：同类型、覆盖任务区域、
     * 未撤销且 UTC 有效期未结束才允许 DISPATCHED → EXECUTING；
     * 凭据已撤销或已过期则将当前派发记录与任务一并转为 BLOCKED 并落库阻断原因，
     * 返回 status=BLOCKED 的任务视图（不抛错，状态转移随事务提交）。
     * BLOCKED 后只能凭有效新凭据重新派发；旧凭据不会自动解锁。
     */
    @Transactional
    public TaskView startTaskExecution(String incidentKey, String taskKey, String actor,
                                       TaskStartRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        Incident incident = lockIncident(incidentKey);
        return runIdempotent(commandKey, "task_start", hash(incidentKey, taskKey, actor),
                TaskView.class, () -> {
                    requireCommander(incident, actor);
                    IncidentTask task = tasks.findByKey(incident.id(), taskKey)
                            .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskKey));
                    if (task.status() == TaskStatus.OPEN) {
                        throw ApiException.conflict("任务尚未派发资源凭据，不能启动执行");
                    }
                    if (task.status() == TaskStatus.BLOCKED) {
                        throw ApiException.conflict(
                                "任务已被凭据检查阻断，需使用有效新凭据重新派发后再启动");
                    }
                    if (task.status() == TaskStatus.EXECUTING) {
                        throw ApiException.conflict("任务执行已启动");
                    }
                    if (task.status() == TaskStatus.DONE
                            || task.status() == TaskStatus.CANCELLED) {
                        throw ApiException.conflict(
                                "任务已处于终态 " + task.status() + "，不能启动执行");
                    }
                    // 锁定任务行后再锁凭据行，与撤销/派发互斥，只按已提交状态转移
                    IncidentTask locked = tasks.lockById(task.id()).orElseThrow();
                    if (locked.status() != TaskStatus.DISPATCHED) {
                        throw ApiException.conflict(
                                "任务状态已被并发改变，当前状态: " + locked.status());
                    }
                    TaskDispatch current = dispatches.listByTask(task.id()).stream()
                            .filter(d -> d.id() == task.currentDispatchId())
                            .findFirst()
                            .orElseThrow(() -> ApiException.conflict("任务缺少当前派发记录"));
                    List<ResourceCredential> credentialRows =
                            credentials.lockByResource(current.resourceKey());
                    ResourceCredential credential = credentialRows.stream()
                            .filter(c -> c.version().equals(current.credentialVersion()))
                            .findFirst()
                            .orElseThrow(() -> ApiException.conflict(
                                    "派发所依据的凭据版本不存在: " + current.resourceKey()
                                            + "/" + current.credentialVersion()));
                    Instant now = now();
                    BlockReason reason = null;
                    if (credential.status() == CredentialStatus.REVOKED) {
                        reason = BlockReason.CREDENTIAL_REVOKED;
                    } else if (!now.isBefore(credential.expiresAt())) {
                        reason = BlockReason.CREDENTIAL_EXPIRED;
                    } else if (!credential.credentialType().equals(current.credentialType())
                            || !credential.region().equals(current.region())) {
                        // 版本内容不可变，正常流程不可达；防御性按过期阻断处理
                        reason = BlockReason.CREDENTIAL_EXPIRED;
                    }
                    if (reason != null) {
                        int blockDispatch = dispatches.markBlocked(current.id(), reason, now);
                        int blockTask = tasks.markBlocked(task.id(), reason, now);
                        if (blockDispatch == 0 || blockTask == 0) {
                            throw ApiException.conflict("任务状态已被并发改变，阻断未生效");
                        }
                        return toTaskView(tasks.findByKey(incident.id(), taskKey).orElseThrow());
                    }
                    int execDispatch = dispatches.markExecuting(current.id(), now);
                    int execTask = tasks.markExecuting(task.id(), now);
                    if (execDispatch == 0 || execTask == 0) {
                        throw ApiException.conflict("任务状态已被并发改变，不能启动执行");
                    }
                    return toTaskView(tasks.findByKey(incident.id(), taskKey).orElseThrow());
                });
    }

    /**
     * 在已持有资源凭据行锁的前提下，校验指定版本凭据可用于任务：
     * 凭据存在、ACTIVE 未撤销、类型一致、覆盖区域且 UTC 有效期未结束。
     */
    private ResourceCredential requireUsableCredential(String resourceKey, String version,
                                                       String requiredType, String region) {
        List<ResourceCredential> rows = credentials.lockByResource(resourceKey);
        ResourceCredential credential = rows.stream()
                .filter(c -> c.version().equals(version)).findFirst()
                .orElseThrow(() -> ApiException.notFound(
                        "资源凭据不存在: " + resourceKey + "/" + version));
        if (credential.status() == CredentialStatus.REVOKED) {
            throw ApiException.conflict("凭据已被撤销: " + resourceKey + "/" + version);
        }
        if (!credential.credentialType().equals(requiredType)) {
            throw ApiException.conflict("凭据类型不匹配：要求 " + requiredType + "，凭据为 "
                    + credential.credentialType());
        }
        if (!credential.region().equals(region)) {
            throw ApiException.conflict("凭据未覆盖任务区域 " + region + "（凭据区域: "
                    + credential.region() + "）");
        }
        if (!now().isBefore(credential.expiresAt())) {
            throw ApiException.conflict("凭据已过 UTC 有效期: 失效时刻 "
                    + credential.expiresAt() + "，当前 " + now());
        }
        return credential;
    }

    private static CredentialView toCredentialView(ResourceCredential c) {
        return new CredentialView(c.resourceKey(), c.version(), c.credentialType(), c.region(),
                c.status().name(), c.expiresAt(), c.revokedAt(), c.createdAt());
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
     * 组装任务视图：阻塞状态按目标事件查询时的当前状态计算，不写回依赖任务；
     * credential 取当前派发指针所指记录的快照，dispatches 为全部派发历史（不可变）。
     */
    private TaskView toTaskView(IncidentTask task) {
        List<TaskBlockerView> blockers = incidents.listBlockingIncidents(task.id()).stream()
                .map(b -> new TaskBlockerView(b.incidentKey(), b.status().name(),
                        UNBLOCKING_STATUSES.contains(b.status())))
                .toList();
        List<TaskDispatch> dispatchList = dispatches.listByTask(task.id());
        TaskDispatch current = dispatchList.isEmpty() || task.currentDispatchId() == null ? null
                : dispatchList.stream()
                        .filter(d -> d.id() == task.currentDispatchId())
                        .findFirst().orElse(null);
        TaskCredentialView credential = current == null ? null
                : new TaskCredentialView(current.resourceKey(), current.credentialVersion(),
                        current.credentialType(), current.region(), current.expiresAt(),
                        current.status().name(), current.dispatchedAt(), current.startedAt());
        List<TaskDispatchView> dispatchViews = dispatchList.stream()
                .map(IncidentService::toDispatchView).toList();
        return new TaskView(task.taskKey(), task.groupCode(), task.title(), task.status().name(),
                blockers, task.createdBy(), task.createdAt(), task.doneBy(), task.doneAt(),
                task.cancelledBy(), task.cancelledAt(), credential,
                task.blockReason() == null ? null : task.blockReason().name(), task.blockedAt(),
                dispatchViews);
    }

    private static TaskDispatchView toDispatchView(TaskDispatch d) {
        return new TaskDispatchView(d.seq(), d.requestId(), d.resourceKey(), d.credentialVersion(),
                d.credentialType(), d.region(), d.expiresAt(), d.status().name(),
                d.blockReason() == null ? null : d.blockReason().name(), d.blockedAt(),
                d.dispatchedAt(), d.dispatchedBy(), d.startedAt());
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
