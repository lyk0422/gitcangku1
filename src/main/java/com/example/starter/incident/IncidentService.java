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
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.example.starter.incident.dto.Requests.ActionRequest;
import com.example.starter.incident.dto.Requests.AttendanceRef;
import com.example.starter.incident.dto.Requests.AttendanceRevokeRequest;
import com.example.starter.incident.dto.Requests.AttendanceSignRequest;
import com.example.starter.incident.dto.Requests.EscalationAckRequest;
import com.example.starter.incident.dto.Requests.EscalationCheckRequest;
import com.example.starter.incident.dto.Requests.GuardianEnableRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCompleteRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TransferAcceptRequest;
import com.example.starter.incident.dto.Requests.TransferRequest;
import com.example.starter.incident.dto.Responses.ActionView;
import com.example.starter.incident.dto.Responses.AttendanceView;
import com.example.starter.incident.dto.Responses.EscalationHistoryView;
import com.example.starter.incident.dto.Responses.EscalationView;
import com.example.starter.incident.dto.Responses.GuardianView;
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

    /** 在场凭证有效期秒数；有效期区间 [issuedAt, issuedAt+60s) 左闭右开。 */
    private static final long ATTENDANCE_TTL_SECONDS = 60;

    /** 视为阻塞已解除的目标事件状态。 */
    private static final Set<IncidentStatus> UNBLOCKING_STATUSES = EnumSet.of(
            IncidentStatus.CONTAINED, IncidentStatus.RESOLVED, IncidentStatus.CLOSED);

    private final IncidentRepository incidents;
    private final EscalationRepository escalations;
    private final IncidentTaskRepository tasks;
    private final CommandKeyRepository commandKeys;
    private final GuardianRepository guardians;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IncidentService(IncidentRepository incidents, EscalationRepository escalations,
                           IncidentTaskRepository tasks, CommandKeyRepository commandKeys,
                           GuardianRepository guardians,
                           ObjectMapper objectMapper, Clock clock) {
        this.incidents = incidents;
        this.escalations = escalations;
        this.tasks = tasks;
        this.commandKeys = commandKeys;
        this.guardians = guardians;
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
     * 启用双人监护的任务必须显式引用监护配置期望版本与双方当前凭证代次：
     * 两份凭证均 ACTIVE 且处于 [issuedAt, expiresAt) 才转 DONE，同事务冻结双方凭证；
     * 缺失、过期、旧代次、版本不符均 422，任务与凭证均不改变。
     * DONE/CANCELLED 为终态，重复操作按 commandKey 幂等规则返回首次结果；
     * 指挥权校验先于重放，越权调用方不能借重放读取首次响应。
     */
    @Transactional
    public TaskView completeTask(String incidentKey, String taskKey, String actor,
                                 TaskCompleteRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        Incident incident = lockIncident(incidentKey);
        requireCommander(incident, actor);
        String requestHash = hash(incidentKey, taskKey, actor,
                req.expectedVersion() == null ? "" : Integer.toString(req.expectedVersion()),
                normalizeRefs(req.attendances()));
        return runIdempotent(commandKey, "task_complete", requestHash, TaskView.class, () -> {
            IncidentTask task = tasks.findByKey(incident.id(), taskKey)
                    .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskKey));
            if (task.status() != TaskStatus.OPEN) {
                throw ApiException.conflict(
                        "任务已处于终态 " + task.status() + "，不能完成");
            }
            TaskGuardian guardian = guardians.findByTaskId(task.id()).orElse(null);
            List<TaskAttendance> frozen = guardian == null ? List.of()
                    : verifyGuardAttendance(task, guardian, req);
            List<String> unresolved = unresolvedBlockers(task.id());
            if (!unresolved.isEmpty()) {
                throw ApiException.conflict("存在未解除阻塞的事件: "
                        + String.join(",", unresolved), List.copyOf(unresolved));
            }
            Instant doneAt = now();
            tasks.markDone(task.id(), actor, doneAt);
            for (TaskAttendance attendance : frozen) {
                int updated = guardians.freezeGeneration(task.id(), attendance.guardian(),
                        attendance.generation());
                if (updated != 1) {
                    // 行锁内不应发生：凭证边界在校验后被改动，保守回滚
                    throw ApiException.illegalTransition("在场凭证已失效，不能完成");
                }
            }
            return toTaskView(tasks.findByKey(incident.id(), taskKey).orElseThrow());
        });
    }

    /**
     * 原完成入口（仅 commandKey）：对未启用监护的任务等价于新入口；
     * 双人监护任务因缺少版本与双方代次引用将得到 422。
     */
    @Transactional
    public TaskView completeTask(String incidentKey, String taskKey, String actor,
                                 TaskActionRequest req) {
        return completeTask(incidentKey, taskKey, actor,
                new TaskCompleteRequest(req.commandKey(), null, null));
    }

    /**
     * 校验双人监护完成引用：期望版本必须为当前版本，且恰好显式引用固定双方各自的
     * 当前代次；凭证行锁定读取，须为 ACTIVE 且当前时刻落在有效期左闭右开区间内。
     * 任一不满足均 422，不改变任何数据。返回两份通过校验、待冻结的凭证。
     */
    private List<TaskAttendance> verifyGuardAttendance(IncidentTask task, TaskGuardian cfg,
                                                       TaskCompleteRequest req) {
        if (req.expectedVersion() == null || req.attendances() == null) {
            throw ApiException.illegalTransition(
                    "双人监护任务完成时必须显式引用期望版本与双方当前凭证代次");
        }
        if (req.expectedVersion() != cfg.version()) {
            throw ApiException.illegalTransition("监护配置版本不匹配，期望版本: "
                    + cfg.version() + "，提交版本: " + req.expectedVersion());
        }
        Map<String, Integer> refs = new HashMap<>();
        for (var ref : req.attendances()) {
            if (ref == null || ref.guardian() == null || ref.guardian().isBlank()
                    || ref.generation() == null || ref.generation() <= 0) {
                throw ApiException.illegalTransition("在场凭证引用必须包含非空监护人与正整数代次");
            }
            if (refs.put(ref.guardian().strip(), ref.generation()) != null) {
                throw ApiException.illegalTransition("同一监护人的凭证代次被重复引用: "
                        + ref.guardian());
            }
        }
        if (!refs.containsKey(cfg.guardianOne()) || !refs.containsKey(cfg.guardianTwo())
                || refs.size() != 2) {
            throw ApiException.illegalTransition("必须且只能引用固定双方 "
                    + cfg.guardianOne() + "、" + cfg.guardianTwo() + " 的当前凭证代次");
        }
        Instant now = now();
        List<TaskAttendance> verified = new ArrayList<>(2);
        for (Map.Entry<String, Integer> entry : refs.entrySet()) {
            TaskAttendance attendance = guardians
                    .findGenerationForUpdate(task.id(), entry.getKey(), entry.getValue())
                    .orElseThrow(() -> ApiException.illegalTransition(
                            "在场凭证不存在或不是当前代次: " + entry.getKey()
                                    + " 代次 " + entry.getValue()));
            if (attendance.status() != AttendanceStatus.ACTIVE) {
                throw ApiException.illegalTransition("在场凭证已失效（" + attendance.status()
                        + "）: " + entry.getKey() + " 代次 " + entry.getValue());
            }
            if (!attendance.validAt(now)) {
                throw ApiException.illegalTransition("在场凭证已过期: " + entry.getKey()
                        + " 代次 " + entry.getValue() + "，有效期至 " + attendance.expiresAt());
            }
            verified.add(attendance);
        }
        return verified;
    }

    /**
     * 启用双人监护：仅当前指挥人可对 OPEN 任务启用一次；固定两个不同合成操作者，
     * 监护配置版本从 1 开始，启用后不可更换。重复启用同 pair 幂等返回首次配置，
     * 改 pair 返回 409。
     */
    @Transactional
    public GuardianView enableGuardianship(String incidentKey, String taskKey, String actor,
                                           GuardianEnableRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String guardianOne = requireText(req.guardianOne(), "guardianOne");
        String guardianTwo = requireText(req.guardianTwo(), "guardianTwo");
        if (guardianOne.equals(guardianTwo)) {
            throw ApiException.badRequest("两个固定监护人必须不同");
        }
        Incident incident = lockIncident(incidentKey);
        requireCommander(incident, actor);
        return runIdempotent(commandKey, "guardian_enable",
                hash(incidentKey, taskKey, actor, guardianOne, guardianTwo),
                GuardianView.class, () -> {
                    IncidentTask task = tasks.findByKey(incident.id(), taskKey)
                            .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskKey));
                    if (task.status() != TaskStatus.OPEN) {
                        throw ApiException.illegalTransition(
                                "仅 OPEN 任务可启用监护，当前状态: " + task.status());
                    }
                    var existing = guardians.findByTaskId(task.id());
                    if (existing.isPresent()) {
                        TaskGuardian found = existing.get();
                        if (!found.guardianOne().equals(guardianOne)
                                || !found.guardianTwo().equals(guardianTwo)) {
                            throw ApiException.conflict(
                                    "任务已启用双人监护且固定监护人不可更换: " + taskKey);
                        }
                        return toGuardianView(taskKey, found);
                    }
                    Instant now = now();
                    guardians.insertGuardian(new TaskGuardian(0L, task.id(), guardianOne,
                            guardianTwo, 1, actor, now));
                    return toGuardianView(taskKey,
                            guardians.findByTaskId(task.id()).orElseThrow());
                });
    }

    /**
     * 在场签到：仅固定监护人本人可签（越权 403）；任务须已启用监护且仍为 OPEN，
     * DONE/CANCELLED 禁止新签到。取服务端 UTC 时刻签发 60 秒凭证 [issuedAt, expiresAt)；
     * 重新签到推进本人代次（从 1 开始）并将前代 ACTIVE 置 SUPERSEDED，前代立即失效。
     * 身份校验先于重放；同键成功重放返回首次凭证响应，不延长有效期。
     */
    @Transactional
    public AttendanceView signAttendance(String incidentKey, String taskKey, String actor,
                                         AttendanceSignRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        requireText(actor, "X-Actor-Id");
        Incident incident = lockIncident(incidentKey);
        IncidentTask taskForAuth = tasks.findByKey(incident.id(), taskKey)
                .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskKey));
        TaskGuardian cfgForAuth = guardians.findByTaskId(taskForAuth.id())
                .orElseThrow(() -> ApiException.conflict("任务未启用双人监护: " + taskKey));
        if (!cfgForAuth.isGuardian(actor)) {
            throw ApiException.forbidden("只有固定监护人本人可签到: " + actor);
        }
        return runIdempotent(commandKey, "attendance_sign", hash(incidentKey, taskKey, actor),
                AttendanceView.class, () -> {
                    IncidentTask task = tasks.findByKey(incident.id(), taskKey)
                            .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskKey));
                    if (task.status() != TaskStatus.OPEN) {
                        throw ApiException.illegalTransition(
                                task.status() + " 任务禁止新签到");
                    }
                    int generation = guardians.findLatest(task.id(), actor)
                            .map(a -> a.generation() + 1).orElse(1);
                    guardians.supersedeActive(task.id(), actor);
                    Instant issuedAt = now();
                    Instant expiresAt = issuedAt.plusSeconds(ATTENDANCE_TTL_SECONDS);
                    guardians.insertAttendance(new TaskAttendance(0L, task.id(), actor, generation,
                            issuedAt, expiresAt, AttendanceStatus.ACTIVE, null, issuedAt));
                    return toAttendanceView(
                            guardians.findLatest(task.id(), actor).orElseThrow());
                });
    }

    /**
     * 撤销在场凭证：仅当前指挥人可撤销（越权 403，且权限先于重放）；
     * 被撤销人必须是固定监护人之一，任务仍为 OPEN 且该人当前存在 ACTIVE 凭证，
     * 否则 409；撤销记录 UTC 时刻并使该代次永久失效。同键重放返回首次结果。
     */
    @Transactional
    public AttendanceView revokeAttendance(String incidentKey, String taskKey, String actor,
                                           AttendanceRevokeRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String guardian = requireText(req.guardian(), "guardian");
        Incident incident = lockIncident(incidentKey);
        requireCommanderForRevoke(incident, actor);
        return runIdempotent(commandKey, "attendance_revoke",
                hash(incidentKey, taskKey, actor, guardian), AttendanceView.class, () -> {
                    IncidentTask task = tasks.findByKey(incident.id(), taskKey)
                            .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskKey));
                    TaskGuardian cfg = guardians.findByTaskId(task.id())
                            .orElseThrow(() -> ApiException.conflict("任务未启用双人监护: " + taskKey));
                    if (!cfg.isGuardian(guardian)) {
                        throw ApiException.badRequest(
                                "被撤销人不是固定监护人: " + guardian);
                    }
                    if (task.status() != TaskStatus.OPEN) {
                        throw ApiException.illegalTransition(
                                task.status() + " 任务的在场凭证已冻结，不能撤销");
                    }
                    int updated = guardians.revokeActive(task.id(), guardian, now());
                    if (updated == 0) {
                        throw ApiException.conflict(
                                "该监护人当前没有有效凭证可撤销: " + guardian);
                    }
                    return toAttendanceView(guardians.findLatest(task.id(), guardian).orElseThrow());
                });
    }

    /**
     * 查询任务双人监护配置及双方全部代次凭证（按监护人、代次升序）；未启用监护 404。
     */
    @Transactional(readOnly = true)
    public GuardianView getGuardianship(String incidentKey, String taskKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        IncidentTask task = tasks.findByKey(incident.id(), taskKey)
                .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskKey));
        TaskGuardian cfg = guardians.findByTaskId(task.id())
                .orElseThrow(() -> ApiException.notFound("任务未启用双人监护: " + taskKey));
        return toGuardianView(taskKey, cfg);
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

    /**
     * 撤销在场凭证的指挥权校验：题干明确撤销越权返回 403（区别于其他写操作的 409 约定）。
     */
    private static void requireCommanderForRevoke(Incident incident, String actor) {
        if (incident.commander() == null || !incident.commander().equals(actor)) {
            throw ApiException.forbidden("只有当前指挥人可撤销在场凭证，当前指挥人: "
                    + (incident.commander() == null ? "(无)" : incident.commander()));
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
     */
    private TaskView toTaskView(IncidentTask task) {
        List<TaskBlockerView> blockers = incidents.listBlockingIncidents(task.id()).stream()
                .map(b -> new TaskBlockerView(b.incidentKey(), b.status().name(),
                        UNBLOCKING_STATUSES.contains(b.status())))
                .toList();
        return new TaskView(task.taskKey(), task.groupCode(), task.title(), task.status().name(),
                blockers, task.createdBy(), task.createdAt(), task.doneBy(), task.doneAt(),
                task.cancelledBy(), task.cancelledAt());
    }

    private static TransferView toTransferView(IncidentTransfer transfer) {
        return new TransferView(transfer.id(), transfer.fromCommander(), transfer.toCommander(),
                transfer.status().name(), transfer.createdAt(), transfer.acceptedAt());
    }

    /**
     * 将完成时的双方凭证引用规范化为幂等参数指纹：按监护人排序后拼接，
     * 引用集合顺序不同视为同参；null 列表归一为空串。
     */
    private static String normalizeRefs(List<AttendanceRef> refs) {
        if (refs == null) {
            return "";
        }
        return refs.stream()
                .map(r -> (r == null || r.guardian() == null ? "" : r.guardian().strip())
                        + ":" + (r == null || r.generation() == null ? "" : r.generation()))
                .sorted()
                .collect(Collectors.joining(","));
    }

    private static AttendanceView toAttendanceView(TaskAttendance attendance) {
        return new AttendanceView(attendance.guardian(), attendance.generation(),
                attendance.issuedAt(), attendance.expiresAt(), attendance.status().name(),
                attendance.revokedAt());
    }

    private GuardianView toGuardianView(String taskKey, TaskGuardian cfg) {
        List<AttendanceView> attendances = guardians.listByTaskId(cfg.taskId()).stream()
                .map(IncidentService::toAttendanceView).toList();
        return new GuardianView(taskKey, cfg.guardianOne(), cfg.guardianTwo(), cfg.version(),
                attendances);
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
