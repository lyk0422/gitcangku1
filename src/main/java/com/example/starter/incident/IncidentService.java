package com.example.starter.incident;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import com.example.starter.incident.dto.Requests.ActionRequest;
import com.example.starter.incident.dto.Requests.BriefingRefreshRequest;
import com.example.starter.incident.dto.Requests.EscalationAckRequest;
import com.example.starter.incident.dto.Requests.EscalationCheckRequest;
import com.example.starter.incident.dto.Requests.ReadbackRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TransferAcceptRequest;
import com.example.starter.incident.dto.Requests.TransferRequest;
import com.example.starter.incident.dto.Responses.ActionView;
import com.example.starter.incident.dto.Responses.BriefingContent;
import com.example.starter.incident.dto.Responses.BriefingEscalationItem;
import com.example.starter.incident.dto.Responses.BriefingTaskItem;
import com.example.starter.incident.dto.Responses.BriefingView;
import com.example.starter.incident.dto.Responses.EscalationHistoryView;
import com.example.starter.incident.dto.Responses.EscalationView;
import com.example.starter.incident.dto.Responses.HistoryView;
import com.example.starter.incident.dto.Responses.IncidentTasksView;
import com.example.starter.incident.dto.Responses.IncidentView;
import com.example.starter.incident.dto.Responses.ReadbackView;
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
    private final TransferBriefingRepository briefings;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IncidentService(IncidentRepository incidents, EscalationRepository escalations,
                           IncidentTaskRepository tasks, CommandKeyRepository commandKeys,
                           TransferBriefingRepository briefings,
                           ObjectMapper objectMapper, Clock clock) {
        this.incidents = incidents;
        this.escalations = escalations;
        this.tasks = tasks;
        this.commandKeys = commandKeys;
        this.briefings = briefings;
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
     * 发起同时冻结首版简报（事件状态、未完成任务、未确认升级、补充说明）供接班人回读。
     */
    @Transactional
    public TransferView initiateTransfer(String incidentKey, String actor, TransferRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String toCommander = requireText(req.toCommander(), "toCommander");
        String note = req.note() == null || req.note().isBlank() ? null : req.note().strip();
        Incident incident = lockIncident(incidentKey);
        return runIdempotent(commandKey, "transfer_initiate",
                hashStructured("transfer_initiate", incidentKey, actor, toCommander, note),
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
                    IncidentTransfer transfer = incidents.findPendingTransfer(incident.id())
                            .orElseThrow();
                    // 发起同时冻结首版简报：事件状态、未完成任务、未确认升级与补充说明
                    createBriefing(incident, transfer, note, 1);
                    return toTransferView(transfer);
                });
    }

    /**
     * 接受交接：仅待接受目标人可接受；接受后原子切换当前指挥人。
     * RESOLVED/CLOSED 禁止接受；待接受期间目标人无其他操作权限。
     * 必须引用当前待交接记录内已回读的最新简报；简报失效（任务/升级/状态变化）返回 409；
     * 接受成功同事务冻结简报与回读人。
     */
    @Transactional
    public IncidentView acceptTransfer(String incidentKey, String actor, TransferAcceptRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        if (req.briefingId() == null) {
            throw ApiException.badRequest("briefingId 不能为空");
        }
        Incident incident = lockIncident(incidentKey);
        return runIdempotent(commandKey, "transfer_accept",
                hashStructured("transfer_accept", incidentKey, actor, req.briefingId()),
                IncidentView.class, () -> {
                    if (incident.status() == IncidentStatus.RESOLVED
                            || incident.status() == IncidentStatus.CLOSED) {
                        throw ApiException.illegalTransition(
                                incident.status() + " 状态不允许接受交接");
                    }
                    IncidentTransfer pending = incidents.findPendingTransfer(incident.id())
                            .orElseThrow(() -> ApiException.conflict("当前没有待接受的交接"));
                    if (!pending.toCommander().equals(actor)) {
                        throw ApiException.conflict("只有交接目标人 " + pending.toCommander()
                                + " 能接受交接");
                    }
                    TransferBriefing briefing = briefings.findById(req.briefingId())
                            .filter(b -> b.transferId() == pending.id())
                            .orElseThrow(() -> ApiException.conflict(
                                    "简报不属于当前待接受的交接: " + req.briefingId()));
                    TransferBriefing latest = briefings.findLatestByTransfer(pending.id())
                            .orElseThrow();
                    if (briefing.id() != latest.id()) {
                        throw ApiException.conflict("简报版本已过期，需刷新并重新回读后再接受");
                    }
                    briefings.findReadback(briefing.id(), actor)
                            .orElseThrow(() -> ApiException.conflict(
                                    "接班人尚未回读该简报，不能接受交接"));
                    // 失效检测：以当前数据重算摘要，与冻结摘要不一致即 409
                    String currentDigest = digestOf(buildContent(incident, pending, briefing.note()));
                    if (!currentDigest.equals(briefing.digest())) {
                        throw ApiException.conflict(
                                "简报已失效：事件状态、未完成任务或未确认升级已变化");
                    }
                    Instant now = now();
                    // 同一事务内切换指挥权并冻结简报与回读证据，不先换指挥再补证据
                    incidents.acceptTransfer(pending.id(), now);
                    incidents.updateState(incident.id(), incident.status(), pending.toCommander(), now);
                    briefings.freeze(briefing.id());
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
     * 查询当前待交接记录的最新简报。仅交出人或指定接班人可读，其余 403。
     */
    @Transactional(readOnly = true)
    public BriefingView currentBriefing(String incidentKey, String actor) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        IncidentTransfer pending = incidents.findPendingTransfer(incident.id())
                .orElseThrow(() -> ApiException.notFound("当前没有待接受的交接简报"));
        requireTransferParty(pending, actor);
        return toBriefingView(briefings.findLatestByTransfer(pending.id()).orElseThrow());
    }

    /**
     * 按 id 查询简报（含已保留的历史版本）。仅该简报所属交接单的交出人或接班人可读，
     * 其余 403。
     */
    @Transactional(readOnly = true)
    public BriefingView getBriefing(String incidentKey, long briefingId, String actor) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        TransferBriefing briefing = briefings.findById(briefingId)
                .filter(b -> b.incidentId() == incident.id())
                .orElseThrow(() -> ApiException.notFound("简报不存在: " + briefingId));
        IncidentTransfer transfer = incidents.findTransferById(briefing.transferId())
                .orElseThrow();
        requireTransferParty(transfer, actor);
        return toBriefingView(briefing);
    }

    /**
     * 简报回读：仅指定接班人可回读，权限检查（403）先于幂等重放。
     * 提交摘要须与冻结摘要一致，错误摘要不产生确认且失败不占幂等键；
     * 回读绑定简报版本，刷新后旧回读不能复用。
     */
    @Transactional
    public ReadbackView readbackBriefing(String incidentKey, String actor, ReadbackRequest req) {
        String requestId = requireText(req.requestId(), "requestId");
        if (req.briefingId() == null) {
            throw ApiException.badRequest("briefingId 不能为空");
        }
        String digest = requireText(req.digest(), "digest");
        Incident incident = lockIncident(incidentKey);
        IncidentTransfer pending = incidents.findPendingTransfer(incident.id())
                .orElseThrow(() -> ApiException.conflict("当前没有待接受的交接"));
        if (!pending.toCommander().equals(actor)) {
            throw ApiException.forbidden("只有交接目标人 " + pending.toCommander() + " 能回读简报");
        }
        return runIdempotent(requestId, "transfer_readback",
                hashStructured("transfer_readback", incidentKey, actor, req.briefingId(), digest),
                ReadbackView.class, () -> {
                    TransferBriefing briefing = briefings.findById(req.briefingId())
                            .filter(b -> b.transferId() == pending.id())
                            .orElseThrow(() -> ApiException.conflict(
                                    "简报不属于当前待接受的交接: " + req.briefingId()));
                    TransferBriefing latest = briefings.findLatestByTransfer(pending.id())
                            .orElseThrow();
                    if (briefing.id() != latest.id()) {
                        throw ApiException.conflict("简报已被刷新，请读取最新版本重新回读");
                    }
                    if (!briefing.digest().equals(digest)) {
                        throw ApiException.conflict("提交摘要与简报摘要不一致，未产生回读确认");
                    }
                    var existing = briefings.findReadback(briefing.id(), actor);
                    if (existing.isPresent()) {
                        return toReadbackView(briefing, existing.get());
                    }
                    briefings.insertReadback(new TransferReadback(0L, briefing.id(), actor,
                            digest, now()));
                    return toReadbackView(briefing,
                            briefings.findReadback(briefing.id(), actor).orElseThrow());
                });
    }

    /**
     * 刷新简报：仅交出人（仍为当前指挥人）可在原待交接记录内刷新，版本加一；
     * 旧简报保留，旧回读不能复用。权限检查（403）先于幂等重放。
     */
    @Transactional
    public BriefingView refreshBriefing(String incidentKey, String actor,
                                        BriefingRefreshRequest req) {
        String requestId = requireText(req.requestId(), "requestId");
        Incident incident = lockIncident(incidentKey);
        IncidentTransfer pending = incidents.findPendingTransfer(incident.id())
                .orElseThrow(() -> ApiException.conflict("当前没有待接受的交接"));
        if (!pending.fromCommander().equals(actor) || !actor.equals(incident.commander())) {
            throw ApiException.forbidden("只有交出人 " + pending.fromCommander() + " 能刷新简报");
        }
        return runIdempotent(requestId, "transfer_briefing_refresh",
                hashStructured("transfer_briefing_refresh", incidentKey, actor, req.note()),
                BriefingView.class, () -> {
                    TransferBriefing latest = briefings.findLatestByTransfer(pending.id())
                            .orElseThrow();
                    String note = req.note() == null || req.note().isBlank()
                            ? latest.note() : req.note().strip();
                    long id = createBriefing(incident, pending, note, latest.version() + 1);
                    return toBriefingView(briefings.findById(id).orElseThrow());
                });
    }

    /**
     * 简报读取权限：仅交接单的交出人或接班人。
     */
    private static void requireTransferParty(IncidentTransfer transfer, String actor) {
        if (!transfer.fromCommander().equals(actor) && !transfer.toCommander().equals(actor)) {
            throw ApiException.forbidden("只有交出人或接班人能读取交接简报");
        }
    }

    /**
     * 构建结构化简报内容：冻结事件状态、未完成任务（OPEN，按 taskKey 排序）、
     * 未确认升级记录（OPEN）与交出人补充说明。
     */
    private BriefingContent buildContent(Incident incident, IncidentTransfer transfer,
                                         String note) {
        List<BriefingTaskItem> unfinished = tasks.listOpenByIncident(incident.id()).stream()
                .map(t -> new BriefingTaskItem(t.taskKey(), t.groupCode(), t.status().name()))
                .sorted(Comparator.comparing(BriefingTaskItem::taskKey))
                .toList();
        List<BriefingEscalationItem> unacknowledged = escalations.listByIncident(incident.id())
                .stream()
                .filter(e -> e.status() == EscalationStatus.OPEN)
                .map(e -> new BriefingEscalationItem(e.id(), e.status().name(), e.triggeredAt(),
                        e.deadlineAt()))
                .toList();
        return new BriefingContent(incident.incidentKey(), incident.status().name(),
                transfer.fromCommander(), transfer.toCommander(), note, unfinished,
                unacknowledged);
    }

    /**
     * 创建简报新版本：结构化内容 JSON 与其摘要同事务落库，返回生成主键。
     */
    private long createBriefing(Incident incident, IncidentTransfer transfer, String note,
                                int version) {
        BriefingContent content = buildContent(incident, transfer, note);
        return briefings.insert(new TransferBriefing(0L, transfer.id(), incident.id(), version,
                toJson(content), digestOf(content), note, BriefingStatus.ACTIVE,
                transfer.fromCommander(), now()));
    }

    /**
     * 简报摘要：完整结构化内容的规范化 JSON 取 SHA-256，字段边界由 JSON 结构保证。
     */
    private String digestOf(BriefingContent content) {
        return sha256Hex(toJson(content));
    }

    /**
     * 幂等身份：操作、操作者、资源与全部参数序列化为 JSON 数组后取 SHA-256，
     * 结构化边界避免分隔符碰撞。
     */
    private String hashStructured(Object... parts) {
        return sha256Hex(toJson(Arrays.asList(parts)));
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 组装简报视图：content 由落库 JSON 还原；附带接班人回读确认信息（未回读为空）。
     */
    private BriefingView toBriefingView(TransferBriefing briefing) {
        BriefingContent content;
        try {
            content = objectMapper.readValue(briefing.content(), BriefingContent.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("简报内容反序列化失败", e);
        }
        TransferReadback readback = briefings.findReadbackByBriefing(briefing.id()).orElse(null);
        return new BriefingView(briefing.id(), briefing.transferId(), briefing.version(),
                briefing.status().name(), briefing.digest(), content, briefing.createdBy(),
                briefing.createdAt(), readback == null ? null : readback.reader(),
                readback == null ? null : readback.createdAt());
    }

    private static ReadbackView toReadbackView(TransferBriefing briefing,
                                               TransferReadback readback) {
        return new ReadbackView(briefing.id(), briefing.version(), readback.reader(),
                readback.digest(), readback.createdAt());
    }
}

