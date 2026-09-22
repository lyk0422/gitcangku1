package com.example.starter.incident;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

import com.example.starter.incident.dto.Requests.ActionRequest;
import com.example.starter.incident.dto.Requests.EscalationAckRequest;
import com.example.starter.incident.dto.Requests.EscalationCheckRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TransferAcceptRequest;
import com.example.starter.incident.dto.Requests.TransferRequest;
import com.example.starter.incident.dto.Responses.ActionView;
import com.example.starter.incident.dto.Responses.EscalationCheckView;
import com.example.starter.incident.dto.Responses.EscalationHistoryView;
import com.example.starter.incident.dto.Responses.EscalationView;
import com.example.starter.incident.dto.Responses.HistoryView;
import com.example.starter.incident.dto.Responses.IncidentView;
import com.example.starter.incident.dto.Responses.StatusChangeView;
import com.example.starter.incident.dto.Responses.TransferView;
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
 * 遏制时限：首次进入 COMMANDING 时按该次接管 UTC 时刻确定期限
 * （S1 5 分钟 / S2 15 分钟 / S3 60 分钟 / S4 240 分钟），等级沿用上报值，交接不重置。
 */
@Service
public class IncidentService {

    private static final String SEP = "\u001F";

    private final IncidentRepository incidents;
    private final CommandKeyRepository commandKeys;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IncidentService(IncidentRepository incidents, CommandKeyRepository commandKeys,
                           ObjectMapper objectMapper, Clock clock) {
        this.incidents = incidents;
        this.commandKeys = commandKeys;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
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
                IncidentStatus.REPORTED, null, null, now, now);
        long id;
        try {
            id = incidents.insert(incident);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("incidentKey 已存在: " + incidentKey);
        }
        incidents.insertStatusChange(new StatusChange(0L, id, null, IncidentStatus.REPORTED, reporter, now));
        return toView(incidents.findByKey(incidentKey).orElseThrow());
    }

    /**
     * 首次接管：REPORTED → COMMANDING，记录当前指挥人，
     * 并以该次接管 UTC 时刻按严重等级确定永不重置的遏制期限。
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
                    Instant deadline = now.plus(containmentWindow(incident.severity()));
                    incidents.takeover(incident.id(), actor, deadline, now);
                    incidents.insertStatusChange(new StatusChange(0L, incident.id(),
                            IncidentStatus.REPORTED, IncidentStatus.COMMANDING, actor, now));
                    return toView(incidents.findByKey(incidentKey).orElseThrow());
                });
    }

    /**
     * 发起交接：仅当前指挥人可发起，目标人必须不同；RESOLVED/CLOSED 禁止发起。
     * 交接不重置遏制期限。
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
                    return toView(incidents.findByKey(incidentKey).orElseThrow());
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
     * 进入 CONTAINED 的同一事务内把仍 OPEN 的升级记录原子置为 CANCELLED，已确认记录保留。
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
                    Instant now = now();
                    incidents.updateState(incident.id(), targetStatus, incident.commander(), now);
                    incidents.insertStatusChange(new StatusChange(0L, incident.id(),
                            incident.status(), targetStatus, actor, now));
                    if (targetStatus == IncidentStatus.CONTAINED) {
                        incidents.cancelOpenEscalations(incident.id());
                    }
                    return toView(incidents.findByKey(incidentKey).orElseThrow());
                });
    }

    /**
     * 单事件“逾期未遏制”检查：以注入 Clock 的当前时刻评估，不做定时扫描。
     * 仅当事件仍为 COMMANDING 且当前时刻 ≥ 遏制期限且尚无升级记录时，追加一条 OPEN 记录，
     * 保存期限、触发时刻及当时指挥人；每个事件至多一条。期限前或其他状态不产生记录；
     * 查询不经过本入口，绝不隐式写入。结果由 commandKey 幂等缓存：期限前成功检查的同键重放
     * 不重新评估，重新检查须换键。
     */
    @Transactional
    public EscalationCheckView checkEscalation(String incidentKey, EscalationCheckRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        Incident incident = lockIncident(incidentKey);
        return runIdempotent(commandKey, "escalation_check", hash(incidentKey),
                EscalationCheckView.class, () -> {
                    if (incident.status() != IncidentStatus.COMMANDING) {
                        return new EscalationCheckView(false, incident.containmentDeadline(), null);
                    }
                    var existing = incidents.findEscalation(incident.id());
                    if (existing.isPresent()) {
                        return new EscalationCheckView(false, incident.containmentDeadline(),
                                toEscalationView(existing.get()));
                    }
                    Instant now = now();
                    if (now.isBefore(incident.containmentDeadline())) {
                        return new EscalationCheckView(false, incident.containmentDeadline(), null);
                    }
                    Escalation escalation = new Escalation(0L, incident.id(), EscalationStatus.OPEN,
                            incident.containmentDeadline(), now, incident.commander(),
                            null, null, null, now);
                    long id;
                    try {
                        id = incidents.insertEscalation(escalation);
                    } catch (DuplicateKeyException e) {
                        // 并发检查：唯一约束保证每个事件至多一条，复用已落库记录
                        Escalation saved = incidents.findEscalation(incident.id()).orElseThrow();
                        return new EscalationCheckView(false, incident.containmentDeadline(),
                                toEscalationView(saved));
                    }
                    Escalation saved = incidents.findEscalation(incident.id())
                            .filter(e -> e.id() == id).orElseThrow();
                    return new EscalationCheckView(true, incident.containmentDeadline(),
                            toEscalationView(saved));
                });
    }

    /**
     * 确认 OPEN 升级记录：仅操作当时的当前指挥人可确认（待接受期间目标人无确认权，
     * 交接接受后旧指挥人失去确认权），提交非空处置说明后进入 ACKNOWLEDGED，
     * 记录确认人与 UTC 时刻。记录不存在或不为 OPEN 返回 409，处置说明为空返回 400。
     */
    @Transactional
    public EscalationView acknowledgeEscalation(String incidentKey, String actor, EscalationAckRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String dispositionNote = requireText(req.dispositionNote(), "dispositionNote");
        Incident incident = lockIncident(incidentKey);
        return runIdempotent(commandKey, "escalation_ack",
                hash(incidentKey, actor, dispositionNote), EscalationView.class, () -> {
                    requireCommander(incident, actor);
                    Escalation escalation = incidents.lockEscalation(incident.id())
                            .orElseThrow(() -> ApiException.conflict("该事件没有待确认的升级记录"));
                    if (escalation.status() != EscalationStatus.OPEN) {
                        throw ApiException.conflict("升级记录当前状态为 " + escalation.status()
                                + "，不能确认；不允许关闭后补确认或回退状态");
                    }
                    Instant now = now();
                    int updated = incidents.acknowledgeEscalation(escalation.id(),
                            dispositionNote, actor, now);
                    if (updated == 0) {
                        Escalation current = incidents.lockEscalation(incident.id()).orElseThrow();
                        throw ApiException.conflict("升级记录当前状态为 " + current.status() + "，不能确认");
                    }
                    return toEscalationView(incidents.lockEscalation(incident.id()).orElseThrow());
                });
    }

    /**
     * 查询事件当前状态（含当前指挥人、遏制期限与待接受交接目标人）；只读，不隐式写入。
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
     * 查询遏制期限、当前升级记录及完整升级历史；只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public EscalationHistoryView escalations(String incidentKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        List<EscalationView> history = incidents.listEscalations(incident.id()).stream()
                .map(IncidentService::toEscalationView).toList();
        EscalationView current = history.isEmpty() ? null : history.get(history.size() - 1);
        return new EscalationHistoryView(incident.containmentDeadline(), current, history);
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
        List<EscalationView> escalationViews = incidents.listEscalations(incident.id()).stream()
                .map(IncidentService::toEscalationView).toList();
        return new HistoryView(toView(incident, pendingTo), statusHistory, actions, transfers,
                escalationViews);
    }

    /**
     * 各严重等级的遏制时限：S1 5 分钟、S2 15 分钟、S3 60 分钟、S4 240 分钟。
     */
    private static Duration containmentWindow(String severity) {
        return switch (severity) {
            case "S1" -> Duration.ofMinutes(5);
            case "S2" -> Duration.ofMinutes(15);
            case "S3" -> Duration.ofMinutes(60);
            case "S4" -> Duration.ofMinutes(240);
            default -> throw ApiException.badRequest("severity 必须为 S1~S4");
        };
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
     * 业务失败时整体事务回滚，占位的幂等键一并回滚，失败不占键。
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

    private IncidentView toView(Incident incident) {
        return toView(incident, null);
    }

    private IncidentView toView(Incident incident, String pendingTransferTo) {
        return new IncidentView(incident.incidentKey(), incident.severity(), incident.summary(),
                incident.reporter(), incident.status().name(), incident.commander(), pendingTransferTo,
                incident.containmentDeadline(), incident.createdAt(), incident.updatedAt());
    }

    private ActionView toActionView(IncidentAction action) {
        return new ActionView(action.actionKey(), action.actionType(), action.note(),
                action.occurredAt(), action.actor(), action.createdAt());
    }

    private static TransferView toTransferView(IncidentTransfer transfer) {
        return new TransferView(transfer.id(), transfer.fromCommander(), transfer.toCommander(),
                transfer.status().name(), transfer.createdAt(), transfer.acceptedAt());
    }

    private static EscalationView toEscalationView(Escalation escalation) {
        return new EscalationView(escalation.id(), escalation.status().name(), escalation.deadline(),
                escalation.triggeredAt(), escalation.commander(), escalation.dispositionNote(),
                escalation.acknowledgedBy(), escalation.acknowledgedAt(), escalation.createdAt());
    }
}
