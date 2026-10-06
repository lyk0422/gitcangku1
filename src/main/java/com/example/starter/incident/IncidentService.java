package com.example.starter.incident;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import com.example.starter.incident.dto.Requests.ActionRequest;
import com.example.starter.incident.dto.Requests.EscalationAckRequest;
import com.example.starter.incident.dto.Requests.EscalationCheckRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.ResourceAssignRequest;
import com.example.starter.incident.dto.Requests.ResourceRenewItem;
import com.example.starter.incident.dto.Requests.ResourceRenewRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TaskExecuteRequest;
import com.example.starter.incident.dto.Requests.TaskStartRequest;
import com.example.starter.incident.dto.Requests.TransferAcceptRequest;
import com.example.starter.incident.dto.Requests.TransferRequest;
import com.example.starter.incident.dto.Responses.ActionView;
import com.example.starter.incident.dto.Responses.EscalationHistoryView;
import com.example.starter.incident.dto.Responses.EscalationView;
import com.example.starter.incident.dto.Responses.ExecutionLogView;
import com.example.starter.incident.dto.Responses.ExpiryRequirementView;
import com.example.starter.incident.dto.Responses.HistoryView;
import com.example.starter.incident.dto.Responses.IncidentTasksView;
import com.example.starter.incident.dto.Responses.IncidentView;
import com.example.starter.incident.dto.Responses.RenewConflictView;
import com.example.starter.incident.dto.Responses.ResourceAssignmentView;
import com.example.starter.incident.dto.Responses.ResourceEventView;
import com.example.starter.incident.dto.Responses.ResourceRenewView;
import com.example.starter.incident.dto.Responses.StatusChangeView;
import com.example.starter.incident.dto.Responses.TaskBlockerView;
import com.example.starter.incident.dto.Responses.TaskView;
import com.example.starter.incident.dto.Responses.TransferView;
import com.example.starter.incident.dto.Responses.UnfinishedTaskView;
import com.example.starter.incident.dto.Responses.VersionRequirementView;
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
    private final ResourceEvidenceRepository evidence;
    private final ExpiryMarker expiryMarker;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IncidentService(IncidentRepository incidents, EscalationRepository escalations,
                           IncidentTaskRepository tasks, CommandKeyRepository commandKeys,
                           ResourceRepository resources, ResourceEvidenceRepository evidence,
                           ExpiryMarker expiryMarker, ObjectMapper objectMapper, Clock clock) {
        this.incidents = incidents;
        this.escalations = escalations;
        this.tasks = tasks;
        this.commandKeys = commandKeys;
        this.resources = resources;
        this.evidence = evidence;
        this.expiryMarker = expiryMarker;
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
                        // （OPEN 但租约已到期、或已落库 AUTHORIZATION_EXPIRED 的任务同样阻塞解决）
                        List<UnfinishedTaskView> unfinished = tasks
                                .listByIncident(incident.id()).stream()
                                .filter(t -> {
                                    TaskStatus effective = effectiveStatus(t);
                                    return effective != TaskStatus.DONE
                                            && effective != TaskStatus.CANCELLED;
                                })
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
     * 持有租约的任务完成时以注入时钟校验授权尚未到期：已到期则任务转为
     * AUTHORIZATION_EXPIRED（独立事务固化状态与证据），本次完成以 422 拒绝。
     * 完成成功时同事务回收租约并写 RELEASE 证据。
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
                    if (task.status() == TaskStatus.DONE || task.status() == TaskStatus.CANCELLED) {
                        throw ApiException.conflict(
                                "任务已处于终态 " + task.status() + "，不能完成");
                    }
                    requireAuthorizationValid(task, actor, "complete");
                    List<String> unresolved = unresolvedBlockers(task.id());
                    if (!unresolved.isEmpty()) {
                        throw ApiException.conflict("存在未解除阻塞的事件: "
                                + String.join(",", unresolved), List.copyOf(unresolved));
                    }
                    tasks.markDone(task.id(), actor, now());
                    releaseAssignment(task, actor);
                    return toTaskView(tasks.findByKey(incident.id(), taskKey).orElseThrow());
                });
    }

    /**
     * 取消任务：仅当前指挥人；仅 OPEN/AUTHORIZATION_EXPIRED 可取消；
     * 取消成功时同事务回收租约并写 RELEASE 证据。
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
                    if (task.status() == TaskStatus.DONE || task.status() == TaskStatus.CANCELLED) {
                        throw ApiException.conflict(
                                "任务已处于终态 " + task.status() + "，不能取消");
                    }
                    tasks.markCancelled(task.id(), actor, now());
                    releaseAssignment(task, actor);
                    return toTaskView(tasks.findByKey(incident.id(), taskKey).orElseThrow());
                });
    }

    /**
     * 资源分配：仅当前指挥人；任务须为 OPEN（已完成/已取消/授权已到期均禁止分配），
     * 且尚无 ACTIVE 租约。必须携带 UTC 到期时刻（晚于分配时刻，左闭右开）与
     * 初始授权版本（>=1）。资源被其他事件占用返回 409；同事务写 ASSIGN 证据。
     */
    @Transactional
    public ResourceAssignmentView assignResource(String incidentKey, String taskKey, String actor,
                                                 ResourceAssignRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String resourceKey = requireText(req.resourceKey(), "resourceKey");
        if (req.authorizationVersion() == null || req.authorizationVersion() < 1) {
            throw ApiException.badRequest("authorizationVersion 必须为 >= 1 的整数");
        }
        if (req.expiresAt() == null) {
            throw ApiException.badRequest("expiresAt 不能为空");
        }
        Instant expiresAt = req.expiresAt().truncatedTo(ChronoUnit.MICROS);
        Incident incident = lockIncident(incidentKey);
        return runIdempotent(commandKey, "task_resource_assign",
                hash(incidentKey, taskKey, actor, resourceKey,
                        req.authorizationVersion().toString(), expiresAt.toString()),
                ResourceAssignmentView.class, () -> {
                    requireCommander(incident, actor);
                    IncidentTask task = tasks.findByKey(incident.id(), taskKey)
                            .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskKey));
                    if (task.status() != TaskStatus.OPEN) {
                        throw ApiException.conflict(
                                "任务状态为 " + task.status() + "，禁止分配资源");
                    }
                    if (resources.findActiveByTask(task.id()).isPresent()) {
                        throw ApiException.conflict("任务已持有生效中的资源租约，禁止再次分配");
                    }
                    Instant now = now();
                    if (!expiresAt.isAfter(now)) {
                        throw ApiException.badRequest("expiresAt 必须晚于分配时刻",
                                new ExpiryRequirementView(expiresAt, now,
                                        Duration.between(expiresAt, now).getSeconds()));
                    }
                    // 资源行锁：串行化占用判定与租约写入
                    ResourceRepository.ResourceRow row = resources.lockResource(resourceKey, now);
                    if (row.holderAssignmentId() != null) {
                        throw ApiException.conflict("资源仍被其他事件占用: " + resourceKey);
                    }
                    ResourceAssignment assignment = new ResourceAssignment(0L, task.id(),
                            incident.id(), resourceKey, req.authorizationVersion(), actor, now,
                            expiresAt, AssignmentStatus.ACTIVE, null, null, now, now);
                    long assignmentId = resources.insertAssignment(assignment);
                    resources.updateHolder(resourceKey, assignmentId, incident.id(), task.id(), now);
                    evidence.insertEvent(new ResourceEvent(0L, assignmentId, incident.id(),
                            task.id(), resourceKey, ResourceEventType.ASSIGN, null,
                            req.authorizationVersion(), actor, now, null));
                    return toAssignmentView(taskKey,
                            resources.findAssignment(assignmentId).orElseThrow());
                });
    }

    /**
     * 续期：一次可包含多个依赖任务。先在完整依赖闭包（本事件沿阻塞边可达的全部事件）
     * 内验证所有 ACTIVE 租约的资源占用与授权版本：任一冲突 422 且不改变任何任务状态
     * 或租约。续期人必须不同于各租约原分配人；已完成、已取消或正在交接出去
     * （存在待接受交接）的任务不可续期。全部校验通过后统一提交：
     * 写入新版本与新到期时刻、恢复 AUTHORIZATION_EXPIRED 任务为 OPEN、写 RENEW 证据。
     */
    @Transactional
    public ResourceRenewView renewResources(String incidentKey, String actor,
                                            ResourceRenewRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        if (req.items() == null || req.items().isEmpty()) {
            throw ApiException.badRequest("items 不能为空");
        }
        List<ResourceRenewItem> items = req.items().stream()
                .map(item -> {
                    if (item == null) {
                        throw ApiException.badRequest("items 不能包含空条目");
                    }
                    return new ResourceRenewItem(requireText(item.taskKey(), "taskKey"),
                            item.newAuthorizationVersion(), item.newExpiresAt());
                })
                .toList();
        List<String> taskKeys = items.stream().map(ResourceRenewItem::taskKey).toList();
        if (taskKeys.stream().distinct().count() != taskKeys.size()) {
            throw ApiException.badRequest("items 中 taskKey 重复");
        }
        for (ResourceRenewItem item : items) {
            if (item.newAuthorizationVersion() == null) {
                throw ApiException.badRequest("newAuthorizationVersion 不能为空");
            }
            if (item.newExpiresAt() == null) {
                throw ApiException.badRequest("newExpiresAt 不能为空");
            }
        }
        String canonical = items.stream()
                .map(i -> i.taskKey() + "=" + i.newAuthorizationVersion() + "@"
                        + i.newExpiresAt().truncatedTo(ChronoUnit.MICROS))
                .sorted().reduce((a, b) -> a + "," + b).orElse("");
        Incident incident = lockIncident(incidentKey);
        return runIdempotent(commandKey, "task_resource_renew",
                hash(incidentKey, actor, canonical), ResourceRenewView.class, () -> {
                    Instant now = now();
                    List<RenewConflictView> conflicts = new ArrayList<>();
                    // 请求任务：必须属于本事件且可续期
                    Map<String, IncidentTask> requested = new LinkedHashMap<>();
                    for (ResourceRenewItem item : items) {
                        IncidentTask task = tasks.findByKey(incident.id(), item.taskKey())
                            .orElseThrow(() -> ApiException.notFound(
                                    "任务不存在: " + item.taskKey()));
                        requested.put(item.taskKey(), task);
                        if (task.status() == TaskStatus.DONE || task.status() == TaskStatus.CANCELLED) {
                            conflicts.add(new RenewConflictView(item.taskKey(), null,
                                    "TASK_TERMINAL", "任务已处于终态 " + task.status() + "，不可续期"));
                        }
                    }
                    if (incidents.findPendingTransfer(incident.id()).isPresent()) {
                        for (ResourceRenewItem item : items) {
                            conflicts.add(new RenewConflictView(item.taskKey(), null,
                                    "PENDING_TRANSFER", "事件正在交接出去，任务不可续期"));
                        }
                    }
                    // 完整依赖闭包中的全部 ACTIVE 租约
                    Set<Long> closure = tasks.listClosureIncidentIds(incident.id());
                    List<ResourceAssignment> closureAssignments =
                            resources.listActiveByIncidents(closure);
                    Map<Long, ResourceAssignment> byTaskId = new HashMap<>();
                    for (ResourceAssignment a : closureAssignments) {
                        byTaskId.put(a.taskId(), a);
                    }
                    // 资源行锁（按 resourceKey 排序，避免交叉死锁）后重新校验占用
                    List<ResourceAssignment> sorted = closureAssignments.stream()
                            .sorted(Comparator.comparing(ResourceAssignment::resourceKey)).toList();
                    Map<String, ResourceRepository.ResourceRow> lockedRows = new LinkedHashMap<>();
                    for (ResourceAssignment a : sorted) {
                        lockedRows.put(a.resourceKey(),
                                resources.lockResource(a.resourceKey(), now));
                    }
                    for (ResourceAssignment a : sorted) {
                        ResourceRepository.ResourceRow row = lockedRows.get(a.resourceKey());
                        if (!Objects.equals(row.holderAssignmentId(), a.id())) {
                            String taskKeyOf = requested.entrySet().stream()
                                    .filter(e -> e.getValue().id() == a.taskId())
                                    .map(Map.Entry::getKey).findFirst().orElse(null);
                            conflicts.add(new RenewConflictView(taskKeyOf, a.resourceKey(),
                                    "RESOURCE_OCCUPIED",
                                    "资源已被其他事件占用: " + a.resourceKey()));
                        }
                    }
                    // 请求任务的租约级校验
                    for (ResourceRenewItem item : items) {
                        IncidentTask task = requested.get(item.taskKey());
                        ResourceAssignment a = byTaskId.get(task.id());
                        if (a == null) {
                            conflicts.add(new RenewConflictView(item.taskKey(), null,
                                    "NO_ACTIVE_ASSIGNMENT", "任务没有生效中的资源租约"));
                            continue;
                        }
                        if (a.assignedBy().equals(actor)) {
                            conflicts.add(new RenewConflictView(item.taskKey(), a.resourceKey(),
                                    "NOT_ASSIGNER_ROLE", "续期人必须不同于原分配人 " + a.assignedBy()));
                        }
                        if (item.newAuthorizationVersion() <= a.authorizationVersion()) {
                            conflicts.add(new RenewConflictView(item.taskKey(), a.resourceKey(),
                                    "VERSION_NOT_INCREASING",
                                    "新授权版本必须大于当前版本: " + toJson(new VersionRequirementView(
                                            a.authorizationVersion(), item.newAuthorizationVersion()))));
                        }
                        Instant newExpiresAt = item.newExpiresAt().truncatedTo(ChronoUnit.MICROS);
                        if (!newExpiresAt.isAfter(now)) {
                            conflicts.add(new RenewConflictView(item.taskKey(), a.resourceKey(),
                                    "EXPIRES_NOT_FUTURE",
                                    "新到期时刻必须晚于续期时刻: " + toJson(new ExpiryRequirementView(
                                            newExpiresAt, now,
                                            Duration.between(newExpiresAt, now).getSeconds()))));
                        }
                    }
                    if (!conflicts.isEmpty()) {
                        throw ApiException.unprocessable(
                                "续期校验失败，未改变任何任务状态或租约", conflicts);
                    }
                    // 全部校验通过：统一应用
                    List<ResourceAssignmentView> renewed = new ArrayList<>();
                    for (ResourceRenewItem item : items) {
                        IncidentTask task = requested.get(item.taskKey());
                        ResourceAssignment a = byTaskId.get(task.id());
                        Instant newExpiresAt = item.newExpiresAt().truncatedTo(ChronoUnit.MICROS);
                        resources.renewAssignment(a.id(), item.newAuthorizationVersion(),
                                newExpiresAt, now);
                        evidence.insertEvent(new ResourceEvent(0L, a.id(), a.incidentId(),
                                a.taskId(), a.resourceKey(), ResourceEventType.RENEW,
                                a.authorizationVersion(), item.newAuthorizationVersion(),
                                actor, now, null));
                        if (task.status() == TaskStatus.AUTHORIZATION_EXPIRED) {
                            tasks.reopenFromExpired(task.id(), now);
                        }
                        renewed.add(toAssignmentView(item.taskKey(),
                                resources.findAssignment(a.id()).orElseThrow()));
                    }
                    int validatedOnly = (int) closureAssignments.stream()
                            .filter(a -> requested.values().stream()
                                    .noneMatch(t -> t.id() == a.taskId()))
                            .count();
                    return new ResourceRenewView(incident.incidentKey(), renewed, validatedOnly);
                });
    }

    /**
     * 开始执行：仅当前指挥人；任务须为 OPEN 且尚未开始；持有租约时以注入时钟
     * 校验授权尚未到期（到期则固化 AUTHORIZATION_EXPIRED 并 422 拦截）。
     * 成功后写 START 执行日志。
     */
    @Transactional
    public TaskView startTask(String incidentKey, String taskKey, String actor,
                              TaskStartRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String note = req.note() == null ? "开始执行" : req.note().strip();
        Incident incident = lockIncident(incidentKey);
        return runIdempotent(commandKey, "task_start", hash(incidentKey, taskKey, actor, note),
                TaskView.class, () -> {
                    requireCommander(incident, actor);
                    IncidentTask task = tasks.findByKey(incident.id(), taskKey)
                            .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskKey));
                    if (task.status() == TaskStatus.DONE || task.status() == TaskStatus.CANCELLED) {
                        throw ApiException.conflict(
                                "任务已处于终态 " + task.status() + "，不能开始执行");
                    }
                    requireAuthorizationValid(task, actor, "start");
                    if (evidence.hasStartLog(task.id())) {
                        throw ApiException.conflict("任务已开始执行，不能重复开始");
                    }
                    evidence.insertExecutionLog(new TaskExecutionLog(0L, task.id(), "START",
                            note, actor, now()));
                    return toTaskView(tasks.findByKey(incident.id(), taskKey).orElseThrow());
                });
    }

    /**
     * 继续执行：仅当前指挥人；任务须已开始且未终态；持有租约时以注入时钟
     * 校验授权尚未到期（到期则固化 AUTHORIZATION_EXPIRED 并 422 拦截）。
     * 成功后写 PROGRESS 执行日志；已执行日志在授权到期后保留。
     */
    @Transactional
    public TaskView logExecution(String incidentKey, String taskKey, String actor,
                                 TaskExecuteRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String note = requireText(req.note(), "note");
        Incident incident = lockIncident(incidentKey);
        return runIdempotent(commandKey, "task_execute", hash(incidentKey, taskKey, actor, note),
                TaskView.class, () -> {
                    requireCommander(incident, actor);
                    IncidentTask task = tasks.findByKey(incident.id(), taskKey)
                            .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskKey));
                    if (task.status() == TaskStatus.DONE || task.status() == TaskStatus.CANCELLED) {
                        throw ApiException.conflict(
                                "任务已处于终态 " + task.status() + "，不能继续执行");
                    }
                    requireAuthorizationValid(task, actor, "execute");
                    if (!evidence.hasStartLog(task.id())) {
                        throw ApiException.conflict("任务尚未开始执行，不能记录继续执行");
                    }
                    evidence.insertExecutionLog(new TaskExecutionLog(0L, task.id(), "PROGRESS",
                            note, actor, now()));
                    return toTaskView(tasks.findByKey(incident.id(), taskKey).orElseThrow());
                });
    }

    /**
     * 查询任务当前租约（含派生到期状态与剩余秒数）。只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public ResourceAssignmentView getTaskResource(String incidentKey, String taskKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        IncidentTask task = tasks.findByKey(incident.id(), taskKey)
                .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskKey));
        ResourceAssignment assignment = resources.findActiveByTask(task.id())
                .orElseThrow(() -> ApiException.notFound("任务没有生效中的资源租约: " + taskKey));
        return toAssignmentView(taskKey, assignment);
    }

    /**
     * 查询任务的全部租约证据（按发生顺序）。只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public List<ResourceEventView> listResourceEvents(String incidentKey, String taskKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        IncidentTask task = tasks.findByKey(incident.id(), taskKey)
                .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskKey));
        return evidence.listEventsByTask(task.id()).stream()
                .map(IncidentService::toEventView).toList();
    }

    /**
     * 查询任务的全部执行日志（按发生顺序）。只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public List<ExecutionLogView> listExecutionLogs(String incidentKey, String taskKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        IncidentTask task = tasks.findByKey(incident.id(), taskKey)
                .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskKey));
        return evidence.listExecutionLogs(task.id()).stream()
                .map(l -> new ExecutionLogView(l.id(), l.entryType(), l.note(), l.actor(),
                        l.occurredAt()))
                .toList();
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
     * status 为按当前时钟计算的有效状态（OPEN 且租约已到期时显示
     * AUTHORIZATION_EXPIRED）；读取本身不改变状态。
     */
    private TaskView toTaskView(IncidentTask task) {
        List<TaskBlockerView> blockers = incidents.listBlockingIncidents(task.id()).stream()
                .map(b -> new TaskBlockerView(b.incidentKey(), b.status().name(),
                        UNBLOCKING_STATUSES.contains(b.status())))
                .toList();
        ResourceAssignment active = resources.findActiveByTask(task.id()).orElse(null);
        return new TaskView(task.taskKey(), task.groupCode(), task.title(),
                effectiveStatus(task).name(),
                blockers, task.createdBy(), task.createdAt(), task.doneBy(), task.doneAt(),
                task.cancelledBy(), task.cancelledAt(),
                active == null ? null : active.resourceKey(),
                active == null ? null : active.authorizationVersion(),
                active == null ? null : active.expiresAt());
    }

    /**
     * 任务有效状态：存储状态为 OPEN 但租约已到期时按 AUTHORIZATION_EXPIRED 计；
     * 其余与存储状态一致。仅计算，不写回。
     */
    private TaskStatus effectiveStatus(IncidentTask task) {
        if (task.status() == TaskStatus.OPEN) {
            return resources.findActiveByTask(task.id())
                    .filter(a -> a.expiredAt(now()))
                    .map(a -> TaskStatus.AUTHORIZATION_EXPIRED)
                    .orElse(TaskStatus.OPEN);
        }
        return task.status();
    }

    /**
     * 命令路径授权校验：任务已落库 AUTHORIZATION_EXPIRED，或 OPEN 但租约按当前
     * 时钟已到期时，经独立事务固化到期状态与 EXPIRE/EXECUTE_DENIED 证据后
     * 以 422 拦截；无租约或未到期则直接放行。
     */
    private void requireAuthorizationValid(IncidentTask task, String actor, String action) {
        var active = resources.findActiveByTask(task.id());
        if (active.isEmpty()) {
            return;
        }
        ResourceAssignment assignment = active.get();
        if (task.status() == TaskStatus.AUTHORIZATION_EXPIRED || assignment.expiredAt(now())) {
            expiryMarker.recordExpiryAndDenial(assignment, actor, action, now());
            throw ApiException.unprocessable("资源授权已到期，任务转为 AUTHORIZATION_EXPIRED，"
                    + "禁止" + action + "；续期后方可恢复",
                    new ExpiryRequirementView(assignment.expiresAt(), now(),
                            Duration.between(assignment.expiresAt(), now()).getSeconds()));
        }
    }

    /**
     * 回收任务的 ACTIVE 租约：置 RELEASED、清空资源持有方并写 RELEASE 证据。
     * 调用方必须已持有事件行锁；资源行锁在本方法内获取。
     */
    private void releaseAssignment(IncidentTask task, String actor) {
        var active = resources.findActiveByTask(task.id());
        if (active.isEmpty()) {
            return;
        }
        ResourceAssignment assignment = active.get();
        Instant now = now();
        resources.markReleased(assignment.id(), actor, now);
        resources.lockResource(assignment.resourceKey(), now);
        resources.clearHolder(assignment.resourceKey(), assignment.id(), now);
        evidence.insertEvent(new ResourceEvent(0L, assignment.id(), assignment.incidentId(),
                assignment.taskId(), assignment.resourceKey(), ResourceEventType.RELEASE,
                assignment.authorizationVersion(), null, actor, now, null));
    }

    private ResourceAssignmentView toAssignmentView(String taskKey, ResourceAssignment a) {
        Instant now = now();
        return new ResourceAssignmentView(a.id(), taskKey, a.resourceKey(),
                a.authorizationVersion(), a.assignedBy(), a.assignedAt(), a.expiresAt(),
                a.status().name(), a.expiredAt(now),
                Duration.between(now, a.expiresAt()).getSeconds(),
                a.releasedBy(), a.releasedAt());
    }

    private static ResourceEventView toEventView(ResourceEvent e) {
        return new ResourceEventView(e.id(), e.eventType().name(), e.resourceKey(),
                e.oldVersion(), e.newVersion(), e.actor(), e.occurredAt(), e.detail());
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
