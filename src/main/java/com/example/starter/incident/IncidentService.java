package com.example.starter.incident;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import com.example.starter.incident.dto.Requests.ActionRequest;
import com.example.starter.incident.dto.Requests.EscalationAckRequest;
import com.example.starter.incident.dto.Requests.EscalationCheckRequest;
import com.example.starter.incident.dto.Requests.LeaseRenewRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.ResourceAssignRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TaskExecutionRequest;
import com.example.starter.incident.dto.Requests.TransferAcceptRequest;
import com.example.starter.incident.dto.Requests.TransferRequest;
import com.example.starter.incident.dto.Responses.ActionView;
import com.example.starter.incident.dto.Responses.EscalationHistoryView;
import com.example.starter.incident.dto.Responses.EscalationView;
import com.example.starter.incident.dto.Responses.ExecutionLogView;
import com.example.starter.incident.dto.Responses.HistoryView;
import com.example.starter.incident.dto.Responses.IncidentTasksView;
import com.example.starter.incident.dto.Responses.IncidentView;
import com.example.starter.incident.dto.Responses.LeaseEventView;
import com.example.starter.incident.dto.Responses.StatusChangeView;
import com.example.starter.incident.dto.Responses.TaskBlockerView;
import com.example.starter.incident.dto.Responses.TaskLeaseView;
import com.example.starter.incident.dto.Responses.TaskResourceHistoryView;
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
    private final ResourceLeaseRepository leases;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IncidentService(IncidentRepository incidents, EscalationRepository escalations,
                           IncidentTaskRepository tasks, CommandKeyRepository commandKeys,
                           ResourceLeaseRepository leases,
                           ObjectMapper objectMapper, Clock clock) {
        this.incidents = incidents;
        this.escalations = escalations;
        this.tasks = tasks;
        this.commandKeys = commandKeys;
        this.leases = leases;
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
                    // 命令时按注入时钟物化已到期的授权（OPEN → AUTHORIZATION_EXPIRED 并回收租约）
                    materializeExpirations(incident.id(), actor, now());
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
                            now, now));
                    for (Incident blocker : blockers) {
                        tasks.insertBlocker(taskId, blocker.id(), now);
                    }
                    return toTaskView(tasks.findByKey(incident.id(), taskKey).orElseThrow());
                });
    }

    /**
     * 完成任务：仅当前指挥人；仅 OPEN 可完成；全部阻塞事件进入
     * CONTAINED/RESOLVED/CLOSED 后才可完成，否则 409 并返回未解除事件列表。
     * 完成时按注入时钟校验授权：活动租约已到期的任务在本次命令内转为
     * AUTHORIZATION_EXPIRED 并回收租约（记录 EXPIRE 与 EXECUTION_BLOCKED 证据），
     * 返回拦截后的任务视图，不置为 DONE；已处于终态的任务重复操作 409。
     * 完成成功时同事务回收活动租约（RELEASE 证据）。DONE/CANCELLED/AUTHORIZATION_EXPIRED
     * 为终态，重复操作按 commandKey 幂等规则返回首次结果。
     */
    @Transactional
    public TaskView completeTask(String incidentKey, String taskKey, String actor,
                                 TaskActionRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        Incident incident = lockIncident(incidentKey);
        return runIdempotent(commandKey, "task_complete", hash(incidentKey, taskKey, actor),
                TaskView.class, () -> {
                    requireCommander(incident, actor);
                    Instant now = now();
                    Set<Long> expiredNow = materializeExpirations(incident.id(), actor, now);
                    IncidentTask task = tasks.findByKey(incident.id(), taskKey)
                            .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskKey));
                    if (expiredNow.contains(task.id())) {
                        recordExecutionBlocked(incident.id(), task.id(), actor, "COMPLETE", now);
                        return toTaskView(tasks.findByKey(incident.id(), taskKey).orElseThrow());
                    }
                    if (task.status() != TaskStatus.OPEN) {
                        throw ApiException.conflict(
                                "任务已处于终态 " + task.status() + "，不能完成");
                    }
                    List<String> unresolved = unresolvedBlockers(task.id());
                    if (!unresolved.isEmpty()) {
                        throw ApiException.conflict("存在未解除阻塞的事件: "
                                + String.join(",", unresolved), List.copyOf(unresolved));
                    }
                    tasks.markDone(task.id(), actor, now);
                    releaseActiveLease(task.id(), actor, now, "TASK_DONE");
                    return toTaskView(tasks.findByKey(incident.id(), taskKey).orElseThrow());
                });
    }

    /**
     * 取消任务：仅当前指挥人；仅 OPEN 可取消；取消成功时同事务回收活动租约
     * （RELEASE 证据）。活动租约已到期的任务在本次命令内转为 AUTHORIZATION_EXPIRED
     * 并返回拦截后的任务视图；DONE/CANCELLED/AUTHORIZATION_EXPIRED 为终态，
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
                    Instant now = now();
                    Set<Long> expiredNow = materializeExpirations(incident.id(), actor, now);
                    IncidentTask task = tasks.findByKey(incident.id(), taskKey)
                            .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskKey));
                    if (expiredNow.contains(task.id())) {
                        recordExecutionBlocked(incident.id(), task.id(), actor, "CANCEL", now);
                        return toTaskView(tasks.findByKey(incident.id(), taskKey).orElseThrow());
                    }
                    if (task.status() != TaskStatus.OPEN) {
                        throw ApiException.conflict(
                                "任务已处于终态 " + task.status() + "，不能取消");
                    }
                    tasks.markCancelled(task.id(), actor, now);
                    releaseActiveLease(task.id(), actor, now, "TASK_CANCELLED");
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
     * 任务状态与租约状态按读取时钟判定：OPEN 任务的活动租约到期时显示
     * AUTHORIZATION_EXPIRED（只读判定，不写回）；lease 为当前活动租约，无则为 null。
     */
    private TaskView toTaskView(IncidentTask task) {
        List<TaskBlockerView> blockers = incidents.listBlockingIncidents(task.id()).stream()
                .map(b -> new TaskBlockerView(b.incidentKey(), b.status().name(),
                        UNBLOCKING_STATUSES.contains(b.status())))
                .toList();
        TaskLeaseView leaseView = leases.findActiveByTask(task.id())
                .map(lease -> toLeaseView(lease, now())).orElse(null);
        String status = task.status().name();
        if (task.status() == TaskStatus.OPEN && leaseView != null
                && LeaseStatus.EXPIRED.name().equals(leaseView.status())) {
            status = TaskStatus.AUTHORIZATION_EXPIRED.name();
        }
        return new TaskView(task.taskKey(), task.groupCode(), task.title(), status,
                blockers, task.createdBy(), task.createdAt(), task.doneBy(), task.doneAt(),
                task.cancelledBy(), task.cancelledAt(), leaseView);
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
     * 资源分配：仅当前指挥人；任务须为 OPEN 且无活动租约；必须携带 UTC 到期时刻
     * （严格晚于分配时刻，区间左闭右开）与授权版本（初始版本 1）。
     * 同一资源全局至多一条活动租约：被其他事件占用时 409 并返回占用方明细。
     * 任务活动租约刚到期的，本次命令先物化为 AUTHORIZATION_EXPIRED 并返回拦截视图，
     * 禁止再次分配。分配证据（ASSIGN）固化资源、授权版本、时刻与操作人。
     */
    @Transactional
    public TaskView assignResource(String incidentKey, String taskKey, String actor,
                                   ResourceAssignRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String resourceKey = requireText(req.resourceKey(), "resourceKey");
        String authVersion = requireText(req.authVersion(), "authVersion");
        if (req.expiresAt() == null) {
            throw ApiException.badRequest("expiresAt 不能为空");
        }
        Instant expiresAt = req.expiresAt().truncatedTo(ChronoUnit.MICROS);
        Incident incident = lockIncident(incidentKey);
        return runIdempotent(commandKey, "task_resource_assign",
                hash(incidentKey, taskKey, actor, resourceKey, authVersion, expiresAt.toString()),
                TaskView.class, () -> {
                    requireCommander(incident, actor);
                    Instant now = now();
                    Set<Long> expiredNow = materializeExpirations(incident.id(), actor, now);
                    IncidentTask task = tasks.findByKey(incident.id(), taskKey)
                            .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskKey));
                    if (expiredNow.contains(task.id())) {
                        return toTaskView(tasks.findByKey(incident.id(), taskKey).orElseThrow());
                    }
                    if (task.status() != TaskStatus.OPEN) {
                        throw ApiException.conflict(
                                "任务已处于终态 " + task.status() + "，不能分配资源");
                    }
                    requireExpiresAfter(expiresAt, now);
                    if (leases.findActiveByTask(task.id()).isPresent()) {
                        throw ApiException.conflict("任务已有活动租约，请使用续期接口");
                    }
                    LeasedResource resource = leases.lockResource(resourceKey, now);
                    if (resource.activeLeaseId() != null) {
                        ResourceLease occupying = leases.findLease(resource.activeLeaseId())
                                .orElseThrow();
                        String occupier = incidents.findById(occupying.incidentId())
                                .map(Incident::incidentKey).orElse("?");
                        Map<String, Object> details = new LinkedHashMap<>();
                        details.put("resourceKey", resourceKey);
                        details.put("occupiedByIncidentKey", occupier);
                        throw ApiException.conflict("资源已被其他事件占用: " + resourceKey, details);
                    }
                    ResourceLease lease = new ResourceLease(0L, resource.id(), resourceKey,
                            incident.id(), task.id(), 1, authVersion, actor, actor, null,
                            now, expiresAt, LeaseStatus.ACTIVE, null, now);
                    long leaseId = leases.insertLease(lease);
                    leases.pointActiveLease(resource.id(), leaseId);
                    leases.insertEvent(new LeaseEvent(0L, LeaseEventType.ASSIGN, resourceKey,
                            incident.id(), task.id(), leaseId, null, authVersion, actor, now, null));
                    return toTaskView(tasks.findByKey(incident.id(), taskKey).orElseThrow());
                });
    }

    /**
     * 任务执行（START/CONTINUE）：仅当前指挥人；任务须为 OPEN；
     * START 每任务至多一次，CONTINUE 须已 START。执行时按注入时钟校验授权：
     * 活动租约已到期的任务在本次命令内转为 AUTHORIZATION_EXPIRED
     * （记录 EXPIRE 与 EXECUTION_BLOCKED 证据），返回拦截后的任务视图，不记执行日志；
     * 已执行日志在任务到期后保留。
     */
    @Transactional
    public TaskView recordExecution(String incidentKey, String taskKey, String actor,
                                    TaskExecutionRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String actionText = requireText(req.action(), "action");
        ExecutionAction action;
        try {
            action = ExecutionAction.valueOf(actionText);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("未知执行动作: " + actionText);
        }
        Incident incident = lockIncident(incidentKey);
        return runIdempotent(commandKey, "task_execute", hash(incidentKey, taskKey, actor, actionText),
                TaskView.class, () -> {
                    requireCommander(incident, actor);
                    Instant now = now();
                    Set<Long> expiredNow = materializeExpirations(incident.id(), actor, now);
                    IncidentTask task = tasks.findByKey(incident.id(), taskKey)
                            .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskKey));
                    if (expiredNow.contains(task.id())) {
                        recordExecutionBlocked(incident.id(), task.id(), actor, action.name(), now);
                        return toTaskView(tasks.findByKey(incident.id(), taskKey).orElseThrow());
                    }
                    if (task.status() != TaskStatus.OPEN) {
                        throw ApiException.conflict(
                                "任务已处于终态 " + task.status() + "，不能执行");
                    }
                    if (action == ExecutionAction.START
                            && tasks.hasExecution(task.id(), ExecutionAction.START)) {
                        throw ApiException.conflict("任务已开始执行，不能重复 START");
                    }
                    if (action == ExecutionAction.CONTINUE
                            && !tasks.hasExecution(task.id(), ExecutionAction.START)) {
                        throw ApiException.conflict("任务尚未 START，不能 CONTINUE");
                    }
                    tasks.insertExecution(task.id(), action, actor, now);
                    return toTaskView(tasks.findByKey(incident.id(), taskKey).orElseThrow());
                });
    }

    /**
     * 批量续期：taskKeys 必须恰好覆盖事件的资源依赖闭包（同一事件内全部持有活动租约的
     * OPEN 任务，同事件任务共享共享资源池与指挥链，互为依赖闭包）。先验证闭包内全部
     * 租约与授权版本：租约未到期、操作人不同于原分配人、提交不同于当前版本的新版本、
     * 资源活动指针仍指向本租约（未被其他事件占用）、事件未交接出去（无已接受交接）、
     * 新到期时刻严格晚于续期时刻；任一冲突 422 且不改变任何任务状态或租约。
     * 全部通过后统一生效：旧版本置 SUPERSEDED，插入 leaseVersion+1 的 ACTIVE 版本，
     * 记录 RENEW 证据（固化资源、旧新授权版本、时刻与操作人）。
     */
    @Transactional
    public IncidentTasksView renewLeases(String incidentKey, String actor, LeaseRenewRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String newAuthVersion = requireText(req.newAuthVersion(), "newAuthVersion");
        String authorizerRole = requireText(req.authorizerRole(), "authorizerRole");
        if (req.expiresAt() == null) {
            throw ApiException.badRequest("expiresAt 不能为空");
        }
        Instant expiresAt = req.expiresAt().truncatedTo(ChronoUnit.MICROS);
        List<String> requested = req.taskKeys() == null ? List.of()
                : req.taskKeys().stream().map(k -> requireText(k, "taskKey")).distinct().sorted()
                        .toList();
        if (requested.isEmpty()) {
            throw ApiException.badRequest("taskKeys 不能为空");
        }
        Incident incident = lockIncident(incidentKey);
        return runIdempotent(commandKey, "task_resource_renew",
                hash(incidentKey, actor, String.join(",", requested), newAuthVersion,
                        authorizerRole, expiresAt.toString()),
                IncidentTasksView.class, () -> {
                    Instant now = now();
                    requireExpiresAfter(expiresAt, now);
                    List<ResourceLease> closure = leases.listActiveLeasesOfOpenTasks(incident.id());
                    Map<Long, String> taskKeys = new HashMap<>();
                    for (IncidentTask t : tasks.listByIncident(incident.id())) {
                        taskKeys.put(t.id(), t.taskKey());
                    }
                    List<String> closureKeys = closure.stream()
                            .map(l -> taskKeys.get(l.taskId())).sorted().toList();
                    if (!closureKeys.equals(requested)) {
                        List<String> missing = new ArrayList<>(closureKeys);
                        missing.removeAll(requested);
                        List<String> unexpected = new ArrayList<>(requested);
                        unexpected.removeAll(closureKeys);
                        Map<String, Object> details = new LinkedHashMap<>();
                        details.put("required", closureKeys);
                        details.put("actual", requested);
                        details.put("missing", missing);
                        details.put("unexpected", unexpected);
                        throw ApiException.renewalConflict(
                                "续期必须恰好覆盖资源依赖闭包，要求 " + closureKeys.size()
                                        + " 个，实际 " + requested.size() + " 个", details);
                    }
                    boolean handedOver = incidents.listTransfers(incident.id()).stream()
                            .anyMatch(t -> t.status() == TransferStatus.ACCEPTED);
                    if (handedOver) {
                        throw ApiException.renewalConflict("事件已交接出去，其任务不可续期", null);
                    }
                    for (ResourceLease lease : closure) {
                        validateRenewable(lease, taskKeys.get(lease.taskId()), actor,
                                newAuthVersion, now);
                    }
                    for (ResourceLease lease : closure) {
                        leases.endLease(lease.id(), LeaseStatus.SUPERSEDED, now);
                        ResourceLease next = new ResourceLease(0L, lease.resourceId(),
                                lease.resourceKey(), lease.incidentId(), lease.taskId(),
                                lease.leaseVersion() + 1, newAuthVersion, lease.assignedBy(),
                                actor, authorizerRole, now, expiresAt, LeaseStatus.ACTIVE,
                                null, now);
                        long nextId = leases.insertLease(next);
                        leases.pointActiveLease(lease.resourceId(), nextId);
                        leases.insertEvent(new LeaseEvent(0L, LeaseEventType.RENEW,
                                lease.resourceKey(), lease.incidentId(), lease.taskId(), nextId,
                                lease.authVersion(), newAuthVersion, actor, now, null));
                    }
                    List<TaskView> views = closure.stream()
                            .map(l -> toTaskView(tasks.findByKey(incident.id(),
                                    taskKeys.get(l.taskId())).orElseThrow()))
                            .toList();
                    return new IncidentTasksView(incident.incidentKey(), views);
                });
    }

    /**
     * 查询任务资源明细与历史：当前活动租约、全部租约版本、资源使用证据与执行日志。
     * 只读，不改变任何状态；到期状态按读取时钟判定。
     */
    @Transactional(readOnly = true)
    public TaskResourceHistoryView taskResourceHistory(String incidentKey, String taskKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        IncidentTask task = tasks.findByKey(incident.id(), taskKey)
                .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskKey));
        Instant now = now();
        TaskLeaseView current = leases.findActiveByTask(task.id())
                .map(lease -> toLeaseView(lease, now)).orElse(null);
        List<TaskLeaseView> versions = leases.listByTask(task.id()).stream()
                .map(lease -> toLeaseView(lease, now)).toList();
        List<LeaseEventView> events = leases.listEventsByTask(task.id()).stream()
                .map(e -> new LeaseEventView(e.id(), e.eventType().name(), e.resourceKey(),
                        e.oldAuthVersion(), e.newAuthVersion(), e.actor(), e.occurredAt(),
                        e.detail()))
                .toList();
        List<ExecutionLogView> executions = tasks.listExecutions(task.id()).stream()
                .map(x -> new ExecutionLogView(x.action().name(), x.actor(), x.occurredAt()))
                .toList();
        return new TaskResourceHistoryView(task.taskKey(), current, versions, events, executions);
    }

    /**
     * 命令时到期物化：将事件内活动租约已到期（expires_at <= now）的 OPEN 任务转为
     * AUTHORIZATION_EXPIRED，租约置 EXPIRED 并释放资源指针，记录 EXPIRE 证据。
     * 返回本次转为到期的任务 id 集合；不做后台扫描。
     */
    private Set<Long> materializeExpirations(long incidentId, String actor, Instant now) {
        Set<Long> transitioned = new HashSet<>();
        for (ResourceLease lease : leases.listExpiredActiveLeases(incidentId, now)) {
            tasks.markAuthorizationExpired(lease.taskId(), now);
            leases.endLease(lease.id(), LeaseStatus.EXPIRED, now);
            leases.clearActiveLease(lease.resourceId(), lease.id());
            leases.insertEvent(new LeaseEvent(0L, LeaseEventType.EXPIRE, lease.resourceKey(),
                    incidentId, lease.taskId(), lease.id(), lease.authVersion(), null,
                    actor, now, null));
            transitioned.add(lease.taskId());
        }
        return transitioned;
    }

    /**
     * 回收任务的活动租约（任务完成/取消时）：租约置 RELEASED 并释放资源指针，记录 RELEASE 证据。
     */
    private void releaseActiveLease(long taskId, String actor, Instant now, String reason) {
        leases.findActiveByTask(taskId).ifPresent(lease -> {
            leases.endLease(lease.id(), LeaseStatus.RELEASED, now);
            leases.clearActiveLease(lease.resourceId(), lease.id());
            leases.insertEvent(new LeaseEvent(0L, LeaseEventType.RELEASE, lease.resourceKey(),
                    lease.incidentId(), taskId, lease.id(), lease.authVersion(), null,
                    actor, now, reason));
        });
    }

    /**
     * 记录执行拦截证据（EXECUTION_BLOCKED）：关联任务最近一个租约版本与被拦截的操作。
     */
    private void recordExecutionBlocked(long incidentId, long taskId, String actor,
                                        String attemptedAction, Instant now) {
        List<ResourceLease> versions = leases.listByTask(taskId);
        if (versions.isEmpty()) {
            return;
        }
        ResourceLease last = versions.get(versions.size() - 1);
        leases.insertEvent(new LeaseEvent(0L, LeaseEventType.EXECUTION_BLOCKED, last.resourceKey(),
                incidentId, taskId, last.id(), last.authVersion(), null, actor, now,
                attemptedAction));
    }

    /**
     * 续期单租约校验：未到期、操作人不同于原分配人、新版本不同于当前版本、
     * 资源活动指针仍指向本租约（未被其他事件占用）；任一不满足 422 且不带部分写入。
     */
    private void validateRenewable(ResourceLease lease, String taskKey, String actor,
                                   String newAuthVersion, Instant now) {
        if (lease.expiredAt(now)) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("taskKey", taskKey);
            details.put("resourceKey", lease.resourceKey());
            details.put("expiresAt", lease.expiresAt().toString());
            details.put("now", now.toString());
            details.put("overdueSeconds", ChronoUnit.SECONDS.between(lease.expiresAt(), now));
            throw ApiException.renewalConflict("任务 " + taskKey + " 的授权已到期，不能续期", details);
        }
        if (actor.equals(lease.assignedBy())) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("taskKey", taskKey);
            details.put("assignedBy", lease.assignedBy());
            details.put("actor", actor);
            throw ApiException.renewalConflict(
                    "续期须由不同于原分配人 " + lease.assignedBy() + " 的授权角色提交", details);
        }
        if (newAuthVersion.equals(lease.authVersion())) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("taskKey", taskKey);
            details.put("currentVersion", lease.authVersion());
            details.put("requestedVersion", newAuthVersion);
            throw ApiException.renewalConflict(
                    "任务 " + taskKey + " 的续期必须提交不同于当前版本的新授权版本", details);
        }
        Long pointer = leases.findResource(lease.resourceKey())
                .map(LeasedResource::activeLeaseId).orElse(null);
        if (pointer == null || pointer != lease.id()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("taskKey", taskKey);
            details.put("resourceKey", lease.resourceKey());
            throw ApiException.renewalConflict(
                    "资源 " + lease.resourceKey() + " 的活动租约已变更，不能续期", details);
        }
    }

    /**
     * 校验到期时刻严格晚于分配/续期时刻（区间左闭右开），不晚于时 400 并返回实际值、要求值与差额。
     */
    private static void requireExpiresAfter(Instant expiresAt, Instant now) {
        if (!expiresAt.isAfter(now)) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("assignedAt", now.toString());
            details.put("expiresAt", expiresAt.toString());
            details.put("required", "expiresAt > assignedAt");
            details.put("diffSeconds", ChronoUnit.SECONDS.between(now, expiresAt));
            throw ApiException.badRequest("到期时刻必须晚于分配时刻", details);
        }
    }

    /**
     * 组装租约视图：status 为按读取时钟判定的有效状态（ACTIVE 且已到期显示 EXPIRED）；
     * remainingSeconds 仅活动租约有值（到期为 0），其余状态为 null。
     */
    private TaskLeaseView toLeaseView(ResourceLease lease, Instant now) {
        String status = lease.status().name();
        Long remaining = null;
        if (lease.status() == LeaseStatus.ACTIVE) {
            if (lease.expiredAt(now)) {
                status = LeaseStatus.EXPIRED.name();
                remaining = 0L;
            } else {
                remaining = ChronoUnit.SECONDS.between(now, lease.expiresAt());
            }
        }
        return new TaskLeaseView(lease.resourceKey(), lease.authVersion(), lease.leaseVersion(),
                lease.assignedBy(), lease.authorizedBy(), lease.authorizerRole(),
                lease.assignedAt(), lease.expiresAt(), status, remaining);
    }
}
