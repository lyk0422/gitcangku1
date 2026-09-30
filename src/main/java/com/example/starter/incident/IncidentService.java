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
import com.example.starter.incident.dto.Requests.TransferAcceptRequest;
import com.example.starter.incident.dto.Requests.TransferRequest;
import com.example.starter.incident.dto.Responses.ActionView;
import com.example.starter.incident.dto.Responses.CredentialView;
import com.example.starter.incident.dto.Responses.DispatchView;
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
 * 并发约定：所有事件写接口先 SELECT ... FOR UPDATE 锁定事件行，同事务内完成
 * 幂等键占位、业务校验与写入；涉及凭据的派发/执行启动在事件行锁之后再对凭据行
 * SELECT ... FOR UPDATE，保证撤销与执行启动只依据凭据行上已提交的状态裁决。
 * 幂等约定：commandKey 全局唯一，同键同参重放首次响应，同键改参返回 409；
 * 派发另有 (taskId, requestId) 唯一键，同请求重放首次任务与凭据版本，不产生新派发。
 * 凭据约定：派发时校验凭据类型、覆盖区域与 UTC 有效期并冻结版本；进入执行状态前
 * 针对冻结版本重新校验，过期或撤销使任务 DISPATCHED→BLOCKED，BLOCKED 不能直接完成，
 * 必须用有效凭据重新派发，旧凭据版本不会自动解锁。
 * 遏制期限：首次进入 COMMANDING 时以该次接管 UTC 时刻按等级确定
 * （S1=5分钟、S2=15分钟、S3=60分钟、S4=240分钟），交接不重置。
 */
@Service
public class IncidentService {

    private static final String SEP = "\u001F";

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
     * 创建处置任务：仅当前指挥人；taskKey 事件内唯一；resourceKey/region 必填，
     * 分别表示执行所需资源与任务区域；阻塞事件 0~5 个、必须存在且非自身；
     * 创建时在依赖图全局锁内做环检测，拒绝直接或间接环（409 且不留部分任务或边）。
     * taskKey 幂等：同键同内容（含资源、区域、阻塞集合）返回首次任务，同键不同内容返回 409。
     */
    @Transactional
    public TaskView createTask(String incidentKey, String actor, TaskCreateRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String taskKey = requireText(req.taskKey(), "taskKey");
        String groupCode = requireText(req.groupCode(), "groupCode");
        String title = requireText(req.title(), "title");
        String resourceKey = requireText(req.resourceKey(), "resourceKey");
        String region = requireText(req.region(), "region");
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
                hash(incidentKey, actor, taskKey, groupCode, title, resourceKey, region,
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
                        if (!found.sameContent(groupCode, title, resourceKey, region)
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
                            groupCode, title, resourceKey, region, TaskStatus.OPEN, actor,
                            null, null, null, null, null, null, null, null, now, now));
                    for (Incident blocker : blockers) {
                        tasks.insertBlocker(taskId, blocker.id(), now);
                    }
                    return toTaskView(tasks.findByKey(incident.id(), taskKey).orElseThrow());
                });
    }

    /**
     * 派发任务：仅当前指挥人；任务须处于 OPEN（首次派发）或 BLOCKED（更新凭据后重新派发）。
     * 派发时对资源最新已提交凭据版本做校验：类型与请求一致、覆盖任务区域、状态 ACTIVE、
     * 当前 UTC 时刻早于失效时刻；任一不满足返回 409，不产生派发记录。
     * 通过后冻结凭据版本快照写入派发记录，OPEN/BLOCKED→DISPATCHED；重新派发时旧的
     * 待执行派发记录置为 SUPERSEDED，旧凭据版本不得用于自动解锁。
     * requestId 幂等：(任务, requestId) 唯一，同键同参重放首次任务与凭据版本（不再派发），
     * 同键换资源或改凭据类型返回 409。
     */
    @Transactional
    public TaskView dispatchTask(String incidentKey, String taskKey, String actor,
                                 TaskDispatchRequest req) {
        String requestId = requireText(req.requestId(), "requestId");
        String resourceKey = requireText(req.resourceKey(), "resourceKey");
        String credentialType = requireText(req.credentialType(), "credentialType");
        Incident incident = lockIncident(incidentKey);
        requireCommander(incident, actor);
        IncidentTask task = tasks.findByKey(incident.id(), taskKey)
                .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskKey));
        if (!task.resourceKey().equals(resourceKey)) {
            throw ApiException.conflict("派发资源必须与任务资源一致: " + task.resourceKey());
        }
        var existingDispatch = dispatches.findByRequest(task.id(), requestId);
        if (existingDispatch.isPresent()) {
            // 重放：不产生新派发、不发生状态转移；参数须与首次派发一致
            TaskDispatch first = existingDispatch.get();
            if (!first.resourceKey().equals(resourceKey)
                    || !first.credentialType().equals(credentialType)) {
                throw ApiException.conflict(
                        "requestId 已被不同资源或凭据类型的派发使用: " + requestId);
            }
            return toTaskView(tasks.findByKey(incident.id(), taskKey).orElseThrow());
        }
        if (incident.status() == IncidentStatus.CLOSED) {
            throw ApiException.illegalTransition("事件已关闭，不能派发任务");
        }
        if (task.status() == TaskStatus.DONE || task.status() == TaskStatus.CANCELLED) {
            throw ApiException.conflict("任务已处于终态 " + task.status() + "，不能派发");
        }
        if (task.status() == TaskStatus.DISPATCHED || task.status() == TaskStatus.EXECUTING) {
            throw ApiException.conflict("任务已派发（状态 " + task.status()
                    + "），不能重复派发；被阻断后须先重新派发");
        }
        Instant now = now();
        // 单语句锁定当前已提交的最新凭据版本行；与撤销在同一行锁上串行
        ResourceCredential credential = credentials.lockLatest(resourceKey)
                .orElseThrow(() -> ApiException.conflict("资源不存在有效凭据: " + resourceKey));
        if (credential.status() == CredentialStatus.REVOKED) {
            throw ApiException.conflict("资源凭据 v" + credential.version() + " 已撤销，不能派发");
        }
        if (!credential.credentialType().equals(credentialType)) {
            throw ApiException.conflict("凭据类型不匹配：要求 " + credentialType + "，当前为 "
                    + credential.credentialType());
        }
        if (!credential.region().equals(task.region())) {
            throw ApiException.conflict("凭据未覆盖任务区域 " + task.region() + "（凭据区域 "
                    + credential.region() + "）");
        }
        if (!now.isBefore(credential.expiresAt())) {
            throw ApiException.conflict("资源凭据 v" + credential.version()
                    + " 已于 UTC " + credential.expiresAt() + " 失效，不能派发");
        }
        // 旧待执行派发失效（BLOCKED 场景上一条已是 BLOCKED，此处通常为 0 行）
        dispatches.supersedeOpen(task.id(), now);
        int moved = tasks.transitionToDispatched(task.id(), credential.version(), now);
        if (moved == 0) {
            throw ApiException.conflict("任务状态已被并发改变，派发失败: " + taskKey);
        }
        dispatches.insert(new TaskDispatch(0L, incident.id(), task.id(), requestId, resourceKey,
                credential.id(), credential.version(), credential.credentialType(),
                credential.region(), credential.expiresAt(), DispatchStatus.DISPATCHED, null,
                actor, now, null, null, now, now));
        return toTaskView(tasks.findByKey(incident.id(), taskKey).orElseThrow());
    }

    /**
     * 执行前检查并进入执行状态：仅当前指挥人；仅 DISPATCHED 可启动。
     * 针对派发冻结的凭据版本重新校验（SELECT ... FOR UPDATE 只读取已提交状态）：
     * 同类型、覆盖任务区域、未撤销且当前 UTC 时刻仍在有效期内才 DISPATCHED→EXECUTING；
     * 已撤销或有效期已结束则 DISPATCHED→BLOCKED（同事务落库阻断原因与派发记录），
     * 返回 BLOCKED 任务视图，任务不能直接完成，须更新凭据后重新派发。
     * 已 EXECUTING 时同 commandKey 幂等返回首次结果；OPEN/BLOCKED/终态按冲突拒绝。
     */
    @Transactional
    public TaskView startTask(String incidentKey, String taskKey, String actor,
                              TaskActionRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        Incident incident = lockIncident(incidentKey);
        return runIdempotent(commandKey, "task_execute", hash(incidentKey, taskKey, actor),
                TaskView.class, () -> {
                    requireCommander(incident, actor);
                    IncidentTask task = tasks.findByKey(incident.id(), taskKey)
                            .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskKey));
                    if (task.status() == TaskStatus.EXECUTING) {
                        return toTaskView(task);
                    }
                    if (task.status() == TaskStatus.DONE || task.status() == TaskStatus.CANCELLED) {
                        throw ApiException.conflict(
                                "任务已处于终态 " + task.status() + "，不能进入执行");
                    }
                    if (task.status() == TaskStatus.OPEN) {
                        throw ApiException.conflict("任务尚未派发，不能进入执行: " + taskKey);
                    }
                    if (task.status() == TaskStatus.BLOCKED) {
                        throw ApiException.conflict(
                                "任务已被凭据阻断（" + task.blockReason()
                                        + "），请更新有效凭据后重新派发");
                    }
                    TaskDispatch dispatch = dispatches.findLatest(task.id())
                            .filter(d -> d.status() == DispatchStatus.DISPATCHED)
                            .orElseThrow(() -> ApiException.conflict(
                                    "任务没有待执行的派发记录: " + taskKey));
                    Instant now = now();
                    // 锁定派发所依据的凭据版本行：与撤销竞争按已提交状态裁决
                    ResourceCredential credential = credentials
                            .lockVersion(dispatch.resourceKey(), dispatch.credentialVersion())
                            .orElseThrow(() -> ApiException.conflict(
                                    "派发绑定的凭据版本不存在: v" + dispatch.credentialVersion()));
                    String reason = invalidReason(credential, dispatch, task.region(), now);
                    if (reason != null) {
                        int blocked = tasks.transitionToBlocked(task.id(), credential.version(),
                                reason, now);
                        if (blocked == 0) {
                            throw ApiException.conflict("任务状态已被并发改变，阻断失败: " + taskKey);
                        }
                        dispatches.markBlocked(dispatch.id(), reason, now);
                        return toTaskView(tasks.findByKey(incident.id(), taskKey).orElseThrow());
                    }
                    int moved = tasks.transitionToExecuting(task.id(), actor, now);
                    if (moved == 0) {
                        throw ApiException.conflict("任务状态已被并发改变，不能进入执行: " + taskKey);
                    }
                    return toTaskView(tasks.findByKey(incident.id(), taskKey).orElseThrow());
                });
    }

    /**
     * 完成任务：仅当前指挥人；任务必须已通过执行前检查处于 EXECUTING。
     * BLOCKED 任务不能直接完成（409，提示更新凭据后重新派发）；OPEN/DISPATCHED 须先派发并启动。
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
                    if (task.status() == TaskStatus.BLOCKED) {
                        throw ApiException.conflict("任务因凭据 " + task.blockReason()
                                + " 被阻断，不能直接完成；请更新有效凭据后重新派发并启动");
                    }
                    if (task.status() == TaskStatus.DONE || task.status() == TaskStatus.CANCELLED) {
                        throw ApiException.conflict(
                                "任务已处于终态 " + task.status() + "，不能完成");
                    }
                    if (task.status() != TaskStatus.EXECUTING) {
                        throw ApiException.conflict("任务尚未进入执行状态（当前 " + task.status()
                                + "），请先派发并通过执行前检查");
                    }
                    List<String> unresolved = unresolvedBlockers(task.id());
                    if (!unresolved.isEmpty()) {
                        throw ApiException.conflict("存在未解除阻塞的事件: "
                                + String.join(",", unresolved), List.copyOf(unresolved));
                    }
                    int moved = tasks.transitionToDone(task.id(), actor, now());
                    if (moved == 0) {
                        throw ApiException.conflict("任务状态已被并发改变，不能完成: " + taskKey);
                    }
                    return toTaskView(tasks.findByKey(incident.id(), taskKey).orElseThrow());
                });
    }

    /**
     * 取消任务：仅当前指挥人；任何非终态（OPEN/DISPATCHED/EXECUTING/BLOCKED）均可取消，
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
                    if (!task.status().isCancellable()) {
                        throw ApiException.conflict(
                                "任务已处于终态 " + task.status() + "，不能取消");
                    }
                    int moved = tasks.transitionToCancelled(task.id(), actor, now());
                    if (moved == 0) {
                        throw ApiException.conflict("任务状态已被并发改变，不能取消: " + taskKey);
                    }
                    return toTaskView(tasks.findByKey(incident.id(), taskKey).orElseThrow());
                });
    }

    /**
     * 登记资源凭据新版本：同 resourceKey 版本号在全局版本锁内自增，状态 ACTIVE。
     * commandKey 幂等同其它写接口；登记本身不绑定事件，X-Actor-Id 记录为登记人。
     */
    @Transactional
    public CredentialView registerCredential(String actor, CredentialRegisterRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String resourceKey = requireText(req.resourceKey(), "resourceKey");
        String credentialType = requireText(req.credentialType(), "credentialType");
        String region = requireText(req.region(), "region");
        if (req.expiresAt() == null) {
            throw ApiException.badRequest("expiresAt 不能为空");
        }
        Instant expiresAt = req.expiresAt();
        return runIdempotent(commandKey, "credential_register",
                hash(actor, resourceKey, credentialType, region, expiresAt.toString()),
                CredentialView.class, () -> {
                    credentials.lockSequence();
                    int version = credentials.maxVersion(resourceKey) + 1;
                    Instant now = now();
                    credentials.insert(new ResourceCredential(0L, resourceKey, version,
                            credentialType, region, expiresAt, CredentialStatus.ACTIVE, actor,
                            null, now, now));
                    return toCredentialView(credentials.findVersion(resourceKey, version)
                            .orElseThrow());
                });
    }

    /**
     * 撤销指定资源的指定版本凭据：撤销不可逆，条件更新只作用于 ACTIVE；
     * 已撤销/版本不存在分别返回 409/404。撤销与派发、执行启动在凭据行锁上串行裁决。
     */
    @Transactional
    public CredentialView revokeCredential(String actor, CredentialRevokeRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String resourceKey = requireText(req.resourceKey(), "resourceKey");
        if (req.version() == null || req.version() < 1) {
            throw ApiException.badRequest("version 必须为正整数");
        }
        int version = req.version();
        return runIdempotent(commandKey, "credential_revoke",
                hash(actor, resourceKey, String.valueOf(version)), CredentialView.class, () -> {
                    ResourceCredential credential = credentials.lockVersion(resourceKey, version)
                            .orElseThrow(() -> ApiException.notFound(
                                    "凭据版本不存在: " + resourceKey + " v" + version));
                    if (credential.status() == CredentialStatus.REVOKED) {
                        throw ApiException.conflict("资源凭据 v" + version + " 已撤销");
                    }
                    Instant now = now();
                    int updated = credentials.revoke(credential.id(), now);
                    if (updated == 0) {
                        throw ApiException.conflict("资源凭据 v" + version + " 已被并发撤销");
                    }
                    return toCredentialView(credentials.findVersion(resourceKey, version)
                            .orElseThrow());
                });
    }

    /**
     * 查询资源全部凭据版本（按版本号升序）；资源从未登记返回 404。
     */
    @Transactional(readOnly = true)
    public List<CredentialView> listCredentials(String resourceKey) {
        requireText(resourceKey, "resourceKey");
        List<ResourceCredential> list = credentials.listByResource(resourceKey);
        if (list.isEmpty()) {
            throw ApiException.notFound("资源凭据不存在: " + resourceKey);
        }
        return list.stream().map(IncidentService::toCredentialView).toList();
    }

    /**
     * 按事件分组查询任务（含凭据版本、阻断原因与派发记录，按查询时当前状态计算）。只读。
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
     * 查询单任务明细（含凭据版本、阻断原因、派发记录与阻塞状态）。只读。
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
     * 执行前复检判定凭据失效原因；无失效返回 null。
     * 撤销优先于过期判定；类型/区域为派发时冻结快照，仍做一致性防御校验，
     * 不一致视为凭据不可用（阻断，不能凭旧快照自动解锁）。
     */
    private static String invalidReason(ResourceCredential credential, TaskDispatch dispatch,
                                        String taskRegion, Instant now) {
        if (credential.status() == CredentialStatus.REVOKED) {
            return TaskDispatch.REASON_REVOKED;
        }
        if (!credential.credentialType().equals(dispatch.credentialType())
                || !credential.region().equals(taskRegion)) {
            return TaskDispatch.REASON_REVOKED;
        }
        if (!now.isBefore(credential.expiresAt())) {
            return TaskDispatch.REASON_EXPIRED;
        }
        return null;
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
     * 组装任务视图：阻塞状态按目标事件查询时的当前状态计算；
     * credentialVersion/blockReason 取任务当前值；dispatches 含全部历史派发记录，
     * 历史执行记录保持派发时的区域与凭据版本快照。
     */
    private TaskView toTaskView(IncidentTask task) {
        List<TaskBlockerView> blockers = incidents.listBlockingIncidents(task.id()).stream()
                .map(b -> new TaskBlockerView(b.incidentKey(), b.status().name(),
                        UNBLOCKING_STATUSES.contains(b.status())))
                .toList();
        List<DispatchView> dispatchViews = dispatches.listByTask(task.id()).stream()
                .map(IncidentService::toDispatchView).toList();
        return new TaskView(task.taskKey(), task.groupCode(), task.title(), task.status().name(),
                task.resourceKey(), task.region(), task.credentialVersion(), task.blockReason(),
                blockers, task.createdBy(), task.executingBy(), task.executingAt(),
                task.doneBy(), task.doneAt(), task.cancelledBy(), task.cancelledAt(),
                dispatchViews);
    }

    private static DispatchView toDispatchView(TaskDispatch dispatch) {
        return new DispatchView(dispatch.id(), dispatch.requestId(), dispatch.resourceKey(),
                dispatch.credentialVersion(), dispatch.credentialType(), dispatch.region(),
                dispatch.credentialExpiresAt(), dispatch.status().name(), dispatch.blockReason(),
                dispatch.dispatchedBy(), dispatch.dispatchedAt(), dispatch.blockedAt(),
                dispatch.supersededAt());
    }

    private static CredentialView toCredentialView(ResourceCredential credential) {
        return new CredentialView(credential.resourceKey(), credential.version(),
                credential.credentialType(), credential.region(), credential.expiresAt(),
                credential.status().name(), credential.createdBy(), credential.revokedAt(),
                credential.createdAt());
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
