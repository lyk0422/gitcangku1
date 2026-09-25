package com.example.starter.incident;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

import com.example.starter.incident.dto.Requests.ActionRequest;
import com.example.starter.incident.dto.Requests.EscalateRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskCompleteRequest;
import com.example.starter.incident.dto.Requests.TaskRequest;
import com.example.starter.incident.dto.Requests.TransferAcceptRequest;
import com.example.starter.incident.dto.Requests.TransferRequest;
import com.example.starter.incident.dto.Responses.ActionView;
import com.example.starter.incident.dto.Responses.EscalationView;
import com.example.starter.incident.dto.Responses.HistoryView;
import com.example.starter.incident.dto.Responses.IncidentView;
import com.example.starter.incident.dto.Responses.StatusChangeView;
import com.example.starter.incident.dto.Responses.TaskView;
import com.example.starter.incident.dto.Responses.TransferView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 事件指挥核心服务，按 REAL/DRILL 两域隔离。
 * 并发约定：写接口先锁定（演练域先锁批次行再锁事件行，真实域直接锁事件行），
 * 同事务内完成幂等键占位、业务校验与写入，保证并发请求按事务提交顺序生效。
 * 幂等约定：commandKey 域内唯一，同键同参重放首次响应，同键改参 409，失败不占键。
 * 跨域约定：任务阻塞事件必须同域，跨域引用返回 422；演练升级不产生真实域通知。
 */
@Service
public class IncidentService {

    private static final String SEP = "\\u001F";

    private final IncidentRepository incidents;
    private final CommandKeyRepository commandKeys;
    private final ObjectMapper objectMapper;

    public IncidentService(IncidentRepository incidents, CommandKeyRepository commandKeys,
                           ObjectMapper objectMapper) {
        this.incidents = incidents;
        this.commandKeys = commandKeys;
        this.objectMapper = objectMapper;
    }

    /**
     * 事件上报：初始状态 REPORTED，无指挥人。
     * 携带 drillKey 时进入演练沙盘域，否则进入真实域；同 incidentKey 两域可各存一条。
     * 演练域 (domain, incidentKey) 重复返回 409；向已清理批次新建演练事件返回 404。
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

        boolean drill = req.drillKey() != null && !req.drillKey().isBlank();
        Instant now = Instant.now();
        long id;
        if (drill) {
            String drillKey = req.drillKey().strip();
            String batchKey = req.drillBatch() == null || req.drillBatch().isBlank()
                    ? drillKey : req.drillBatch().strip();
            // 先锁/建批次行，保证与清理事务按提交顺序裁决。
            ensureBatchActiveForWrite(batchKey, drillKey);
            if (incidents.findByKey(Domain.DRILL, incidentKey).isPresent()) {
                throw ApiException.conflict("演练事件 incidentKey 已存在: " + incidentKey);
            }
            Incident incident = new Incident(0L, Domain.DRILL, incidentKey, drillKey, batchKey,
                    severity, summary, reporter, IncidentStatus.REPORTED, null, now, now);
            try {
                id = incidents.insert(incident);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("演练事件 incidentKey 已存在: " + incidentKey);
            }
        } else {
            if (req.drillBatch() != null && !req.drillBatch().isBlank()) {
                throw ApiException.badRequest("真实事件不能携带 drillBatch");
            }
            if (incidents.findByKey(Domain.REAL, incidentKey).isPresent()) {
                throw ApiException.conflict("incidentKey 已存在: " + incidentKey);
            }
            Incident incident = new Incident(0L, Domain.REAL, incidentKey, null, null,
                    severity, summary, reporter, IncidentStatus.REPORTED, null, now, now);
            try {
                id = incidents.insert(incident);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("incidentKey 已存在: " + incidentKey);
            }
        }
        incidents.insertStatusChange(new StatusChange(0L, id, null, IncidentStatus.REPORTED, reporter, now));
        Incident saved = incidents.findByKey(drill ? Domain.DRILL : Domain.REAL, incidentKey)
                .orElseThrow();
        return toView(saved, null);
    }

    /**
     * 首次接管：REPORTED → COMMANDING，记录当前指挥人。
     */
    @Transactional
    public IncidentView takeover(Domain domain, String incidentKey, String actor, TakeoverRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        Incident incident = lockIncident(domain, incidentKey);
        return runIdempotent(domain, commandKey, "takeover", hash(incidentKey, actor), IncidentView.class,
                () -> {
                    if (incident.status() != IncidentStatus.REPORTED) {
                        throw ApiException.illegalTransition(
                                "仅 REPORTED 状态可接管，当前状态: " + incident.status());
                    }
                    Instant now = Instant.now();
                    incidents.updateState(incident.id(), IncidentStatus.COMMANDING, actor, now);
                    incidents.insertStatusChange(new StatusChange(0L, incident.id(),
                            IncidentStatus.REPORTED, IncidentStatus.COMMANDING, actor, now));
                    return toView(incidents.lockByKey(domain, incidentKey).orElseThrow(), null);
                });
    }

    /**
     * 发起交接：仅当前指挥人可发起，目标人必须不同；终态禁止发起。
     */
    @Transactional
    public TransferView initiateTransfer(Domain domain, String incidentKey, String actor,
                                         TransferRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String toCommander = requireText(req.toCommander(), "toCommander");
        Incident incident = lockIncident(domain, incidentKey);
        return runIdempotent(domain, commandKey, "transfer_initiate",
                hash(incidentKey, actor, toCommander), TransferView.class, () -> {
                    requireCommander(incident, actor);
                    if (incident.status().isTerminal()) {
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
                    Instant now = Instant.now();
                    incidents.insertTransfer(new IncidentTransfer(0L, incident.id(),
                            incident.commander(), toCommander, TransferStatus.PENDING, now, null));
                    return toTransferView(incidents.findPendingTransfer(incident.id()).orElseThrow());
                });
    }

    /**
     * 接受交接：仅待接受目标人可接受；接受后原子切换当前指挥人。终态禁止接受。
     */
    @Transactional
    public IncidentView acceptTransfer(Domain domain, String incidentKey, String actor,
                                       TransferAcceptRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        Incident incident = lockIncident(domain, incidentKey);
        return runIdempotent(domain, commandKey, "transfer_accept", hash(incidentKey, actor),
                IncidentView.class, () -> {
                    if (incident.status().isTerminal()) {
                        throw ApiException.illegalTransition(
                                incident.status() + " 状态不允许接受交接");
                    }
                    IncidentTransfer pending = incidents.findPendingTransfer(incident.id())
                            .orElseThrow(() -> ApiException.conflict("当前没有待接受的交接"));
                    if (!pending.toCommander().equals(actor)) {
                        throw ApiException.conflict("只有交接目标人 " + pending.toCommander() + " 能接受交接");
                    }
                    Instant now = Instant.now();
                    incidents.acceptTransfer(pending.id(), now);
                    incidents.updateState(incident.id(), incident.status(), pending.toCommander(), now);
                    return toView(incidents.lockByKey(domain, incidentKey).orElseThrow(), null);
                });
    }

    /**
     * 追加处置记录：仅当前指挥人可写；CLOSED/CANCELLED 后禁止写入。
     * actionKey 事件内唯一：同键同内容幂等返回首次记录，同键不同内容返回 409。
     */
    @Transactional
    public ActionView addAction(Domain domain, String incidentKey, String actor, ActionRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String actionKey = requireText(req.actionKey(), "actionKey");
        String actionType = requireText(req.actionType(), "actionType");
        String note = requireText(req.note(), "note");
        if (req.occurredAt() == null) {
            throw ApiException.badRequest("occurredAt 不能为空");
        }
        Instant occurredAt = req.occurredAt().truncatedTo(ChronoUnit.MICROS);
        Incident incident = lockIncident(domain, incidentKey);
        return runIdempotent(domain, commandKey, "action",
                hash(incidentKey, actor, actionKey, actionType, note, occurredAt.toString()),
                ActionView.class, () -> {
                    requireCommander(incident, actor);
                    if (incident.status() == IncidentStatus.CLOSED
                            || incident.status() == IncidentStatus.CANCELLED) {
                        throw ApiException.illegalTransition("事件已终结(" + incident.status()
                                + ")，不能再追加处置记录");
                    }
                    var existing = incidents.findAction(incident.id(), actionKey);
                    if (existing.isPresent()) {
                        IncidentAction found = existing.get();
                        if (!found.sameContent(actionType, note, occurredAt)) {
                            throw ApiException.conflict("actionKey 已被不同内容使用: " + actionKey);
                        }
                        return toActionView(found);
                    }
                    Instant now = Instant.now();
                    incidents.insertAction(new IncidentAction(0L, incident.id(), actionKey, actionType,
                            note, occurredAt, actor, now));
                    return toActionView(incidents.findAction(incident.id(), actionKey).orElseThrow());
                });
    }

    /**
     * 状态变更：仅当前指挥人可操作，仅允许 COMMANDING→CONTAINED→RESOLVED→CLOSED 逐级前进。
     */
    @Transactional
    public IncidentView changeStatus(Domain domain, String incidentKey, String actor, StatusRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String target = requireText(req.targetStatus(), "targetStatus");
        IncidentStatus targetStatus;
        try {
            targetStatus = IncidentStatus.valueOf(target);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("未知目标状态: " + target);
        }
        Incident incident = lockIncident(domain, incidentKey);
        return runIdempotent(domain, commandKey, "status", hash(incidentKey, actor, target),
                IncidentView.class, () -> {
                    requireCommander(incident, actor);
                    IncidentStatus next = incident.status().next();
                    if (next == null || next != targetStatus) {
                        throw ApiException.illegalTransition(
                                "不允许从 " + incident.status() + " 流转到 " + targetStatus);
                    }
                    Instant now = Instant.now();
                    incidents.updateState(incident.id(), targetStatus, incident.commander(), now);
                    incidents.insertStatusChange(new StatusChange(0L, incident.id(),
                            incident.status(), targetStatus, actor, now));
                    return toView(incidents.lockByKey(domain, incidentKey).orElseThrow(), null);
                });
    }

    /**
     * 取消事件：仅当前指挥人可操作，任一非终态进入 CANCELLED；终态不可取消。
     */
    @Transactional
    public IncidentView cancel(Domain domain, String incidentKey, String actor,
                               com.example.starter.incident.dto.Requests.CancelRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        Incident incident = lockIncident(domain, incidentKey);
        return runIdempotent(domain, commandKey, "cancel", hash(incidentKey, actor), IncidentView.class,
                () -> {
                    requireCommander(incident, actor);
                    if (incident.status().isTerminal()) {
                        throw ApiException.illegalTransition(
                                incident.status() + " 已是终态，不能取消");
                    }
                    Instant now = Instant.now();
                    incidents.updateState(incident.id(), IncidentStatus.CANCELLED, incident.commander(), now);
                    incidents.insertStatusChange(new StatusChange(0L, incident.id(),
                            incident.status(), IncidentStatus.CANCELLED, actor, now));
                    return toView(incidents.lockByKey(domain, incidentKey).orElseThrow(), null);
                });
    }

    /**
     * 升级事件：仅当前指挥人可操作，终态不可升级。
     * REAL 域升级追加真实通知出站记录；DRILL 域只记录升级历史，绝不触发真实域通知或副作用。
     */
    @Transactional
    public EscalationView escalate(Domain domain, String incidentKey, String actor, EscalateRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String escalateTo = requireText(req.escalateTo(), "escalateTo");
        String reason = requireText(req.reason(), "reason");
        Incident incident = lockIncident(domain, incidentKey);
        return runIdempotent(domain, commandKey, "escalation",
                hash(incidentKey, actor, escalateTo, reason), EscalationView.class, () -> {
                    requireCommander(incident, actor);
                    if (incident.status().isTerminal()) {
                        throw ApiException.illegalTransition(
                                incident.status() + " 状态不允许升级");
                    }
                    Instant now = Instant.now();
                    long id = incidents.insertEscalation(new Escalation(0L, incident.id(),
                            escalateTo, reason, actor, now));
                    if (domain == Domain.REAL) {
                        incidents.insertNotification(incident.id(), "ESCALATION", escalateTo,
                                incident.incidentKey() + " 升级至 " + escalateTo, now);
                    }
                    return toEscalationView(incidents.listEscalations(incident.id()).stream()
                            .filter(e -> e.id() == id).findFirst().orElseThrow());
                });
    }

    /**
     * 创建处置任务：仅当前指挥人可创建，CLOSED/CANCELLED 禁止。
     * blockerIncidentKeys 必须全部解析为同域事件；任一不存在于同域（含仅存在于另一域）返回 422。
     * taskKey 事件内唯一：同键同内容幂等返回，同键不同内容 409。
     */
    @Transactional
    public TaskView createTask(Domain domain, String incidentKey, String actor, TaskRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String taskKey = requireText(req.taskKey(), "taskKey");
        String title = requireText(req.title(), "title");
        List<String> blockerKeys = req.blockerIncidentKeys() == null ? List.of()
                : req.blockerIncidentKeys().stream().filter(k -> k != null && !k.isBlank())
                        .map(String::strip).distinct().sorted().toList();
        Incident incident = lockIncident(domain, incidentKey);
        return runIdempotent(domain, commandKey, "task",
                hash(incidentKey, actor, taskKey, title, String.join(",", blockerKeys)),
                TaskView.class, () -> {
                    requireCommander(incident, actor);
                    if (incident.status() == IncidentStatus.CLOSED
                            || incident.status() == IncidentStatus.CANCELLED) {
                        throw ApiException.illegalTransition("事件已终结(" + incident.status()
                                + ")，不能创建处置任务");
                    }
                    var existing = incidents.findTask(incident.id(), taskKey);
                    if (existing.isPresent()) {
                        TaskView found = toTaskView(domain, existing.get());
                        if (!found.title().equals(title)
                                || !found.blockerIncidentKeys().equals(blockerKeys)) {
                            throw ApiException.conflict("taskKey 已被不同内容使用: " + taskKey);
                        }
                        return found;
                    }
                    // 同域依赖校验：跨域引用（真实依赖演练或反之）一律 422。
                    List<Long> blockerIds = resolveSameDomainBlockers(domain, blockerKeys);
                    Instant now = Instant.now();
                    long taskId = incidents.insertTask(new Task(0L, incident.id(), taskKey, title,
                            TaskStatus.OPEN, actor, now, null));
                    for (Long blockerId : blockerIds) {
                        incidents.insertTaskBlocker(taskId, blockerId);
                    }
                    return toTaskView(domain, incidents.findTask(incident.id(), taskKey).orElseThrow());
                });
    }

    /**
     * 完成处置任务：仅当前指挥人可完成，任务须为 OPEN；同键重放返回首次结果。
     */
    @Transactional
    public TaskView completeTask(Domain domain, String incidentKey, String actor, String taskKey,
                                 TaskCompleteRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String checkedTaskKey = requireText(taskKey, "taskKey");
        Incident incident = lockIncident(domain, incidentKey);
        return runIdempotent(domain, commandKey, "task_complete",
                hash(incidentKey, actor, checkedTaskKey), TaskView.class, () -> {
                    requireCommander(incident, actor);
                    Task task = incidents.findTask(incident.id(), checkedTaskKey)
                            .orElseThrow(() -> ApiException.notFound("任务不存在: " + checkedTaskKey));
                    if (task.status() == TaskStatus.DONE) {
                        return toTaskView(domain, task);
                    }
                    Instant now = Instant.now();
                    incidents.completeTask(task.id(), now);
                    return toTaskView(domain,
                            incidents.findTask(incident.id(), checkedTaskKey).orElseThrow());
                });
    }

    /**
     * 查询事件当前状态（含当前指挥人与待接受交接目标人），结果显式标注域。
     */
    @Transactional(readOnly = true)
    public IncidentView get(Domain domain, String incidentKey) {
        Incident incident = incidents.findByKey(domain, incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        String pendingTo = incidents.findPendingTransfer(incident.id())
                .map(IncidentTransfer::toCommander).orElse(null);
        return toView(incident, pendingTo);
    }

    /**
     * 查询完整历史：事件本体、状态流转、处置记录、交接记录、升级记录与任务。
     */
    @Transactional(readOnly = true)
    public HistoryView history(Domain domain, String incidentKey) {
        Incident incident = incidents.findByKey(domain, incidentKey)
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
        List<EscalationView> escalations = incidents.listEscalations(incident.id()).stream()
                .map(this::toEscalationView).toList();
        List<TaskView> tasks = incidents.listTasks(incident.id()).stream()
                .map(t -> toTaskView(domain, t)).toList();
        return new HistoryView(toView(incident, pendingTo), statusHistory, actions, transfers,
                escalations, tasks);
    }

    /**
     * 列出某域全部事件（按创建顺序），结果均标注域。默认只查真实域。
     */
    @Transactional(readOnly = true)
    public List<IncidentView> list(Domain domain) {
        return incidents.listByDomain(domain).stream()
                .map(i -> toView(i, incidents.findPendingTransfer(i.id())
                        .map(IncidentTransfer::toCommander).orElse(null)))
                .toList();
    }

    /**
     * 按状态统计某域事件数量；两域统计彼此独立。
     */
    @Transactional(readOnly = true)
    public java.util.Map<String, Integer> stats(Domain domain) {
        java.util.Map<String, Integer> out = new java.util.LinkedHashMap<>();
        for (IncidentStatus s : IncidentStatus.values()) {
            out.put(s.name(), incidents.countByDomainAndStatus(domain, s));
        }
        return out;
    }

    private List<Long> resolveSameDomainBlockers(Domain domain, List<String> blockerKeys) {
        List<Long> ids = new ArrayList<>();
        for (String key : blockerKeys) {
            Incident blocker = incidents.findByKey(domain, key)
                    .orElseThrow(() -> ApiException.unprocessableCrossDomain(
                            "阻塞事件必须与任务处于同一域且存在: " + key + "（域 " + domain + "）"));
            ids.add(blocker.id());
        }
        return ids;
    }

    /**
     * 演练写入前确保批次处于 ACTIVE：不存在则在当前事务内创建；CLEANED 墓碑返回 404。
     */
    private void ensureBatchActiveForWrite(String batchKey, String drillKey) {
        var batch = incidents.lockBatch(batchKey);
        if (batch.isEmpty()) {
            try {
                incidents.insertBatch(batchKey, drillKey, Instant.now());
            } catch (DuplicateKeyException e) {
                batch = incidents.lockBatch(batchKey);
            }
        }
        if (batch.isPresent() && batch.get().status() == DrillBatchStatus.CLEANED) {
            throw ApiException.notFound("演练批次已清理，不可再写入: " + batchKey);
        }
    }

    /**
     * 写路径锁定：演练域先锁批次行（清理并发裁决）再锁事件行；真实域直接锁事件行。
     */
    private Incident lockIncident(Domain domain, String incidentKey) {
        requireText(incidentKey, "incidentKey");
        if (domain == Domain.DRILL) {
            Incident probe = incidents.findByKey(Domain.DRILL, incidentKey)
                    .orElseThrow(() -> ApiException.notFound("演练事件不存在: " + incidentKey));
            var batch = incidents.lockBatch(probe.drillBatch());
            if (batch.isPresent() && batch.get().status() == DrillBatchStatus.CLEANED) {
                throw ApiException.notFound("演练批次已清理，事件不可再写入: " + probe.drillBatch());
            }
            return incidents.lockByKey(Domain.DRILL, incidentKey)
                    .orElseThrow(() -> ApiException.notFound("演练事件不存在: " + incidentKey));
        }
        return incidents.lockByKey(Domain.REAL, incidentKey)
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
     * 幂等执行：同键同参重放首次响应，同键改参 409；并发同键由唯一约束串行化；失败不占键
     * （业务异常导致事务回滚，占位插入一并回滚）。
     */
    private <T> T runIdempotent(Domain domain, String commandKey, String operation, String requestHash,
                                Class<T> type, Supplier<T> business) {
        var existing = commandKeys.find(domain, commandKey);
        if (existing.isPresent()) {
            return replay(existing.get(), operation, requestHash, type);
        }
        try {
            commandKeys.insertPlaceholder(domain, commandKey, operation, requestHash, Instant.now());
        } catch (DuplicateKeyException e) {
            var committed = commandKeys.findForUpdate(domain, commandKey)
                    .orElseThrow(() -> ApiException.conflict("commandKey 处理冲突: " + commandKey));
            return replay(committed, operation, requestHash, type);
        }
        T result = business.get();
        commandKeys.fillResponse(domain, commandKey, 200, toJson(result));
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
        return new IncidentView(incident.domain().name(), incident.incidentKey(),
                incident.drillKey(), incident.drillBatch(), incident.severity(), incident.summary(),
                incident.reporter(), incident.status().name(), incident.commander(), pendingTransferTo,
                incident.createdAt(), incident.updatedAt());
    }

    private ActionView toActionView(IncidentAction action) {
        return new ActionView(action.actionKey(), action.actionType(), action.note(),
                action.occurredAt(), action.actor(), action.createdAt());
    }

    private EscalationView toEscalationView(Escalation escalation) {
        return new EscalationView(escalation.id(), escalation.escalateTo(), escalation.reason(),
                escalation.actor(), escalation.createdAt());
    }

    private TaskView toTaskView(Domain domain, Task task) {
        List<String> blockerKeys = incidents.listBlockerIncidentIds(task.id()).stream()
                .map(incidents::findById)
                .map(o -> o.map(Incident::incidentKey).orElse("?"))
                .sorted().toList();
        return new TaskView(domain.name(), task.taskKey(), task.title(), task.status().name(),
                blockerKeys, task.actor(), task.createdAt(), task.completedAt());
    }

    private static TransferView toTransferView(IncidentTransfer transfer) {
        return new TransferView(transfer.id(), transfer.fromCommander(), transfer.toCommander(),
                transfer.status().name(), transfer.createdAt(), transfer.acceptedAt());
    }
}
