package com.example.starter.incident;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 事件指挥核心业务：状态机、两步指挥交接、处置记录与命令幂等。
 *
 * <p>并发约定：所有变更命令在事务内先对事件行 SELECT ... FOR UPDATE 加锁，
 * 并发命令按事务提交顺序生效；commandKey 在事件内唯一，同键同参重放返回首次结果，
 * 同键改参返回 409。
 */
@Service
public class IncidentService {

    private static final String OP_TAKE_COMMAND = "TAKE_COMMAND";
    private static final String OP_HANDOVER_INITIATE = "HANDOVER_INITIATE";
    private static final String OP_HANDOVER_ACCEPT = "HANDOVER_ACCEPT";
    private static final String OP_APPEND_ACTION = "APPEND_ACTION";
    private static final String OP_CHANGE_STATUS = "CHANGE_STATUS";

    private static final String EVENT_REPORTED = "REPORTED";
    private static final String EVENT_TOOK_COMMAND = "TOOK_COMMAND";
    private static final String EVENT_HANDOVER_INITIATED = "HANDOVER_INITIATED";
    private static final String EVENT_HANDOVER_ACCEPTED = "HANDOVER_ACCEPTED";
    private static final String EVENT_STATUS_CHANGED = "STATUS_CHANGED";
    private static final String EVENT_ACTION_APPENDED = "ACTION_APPENDED";

    private final IncidentRepository incidents;
    private final IncidentActionRepository actions;
    private final IncidentEventRepository events;
    private final CommandRecordRepository commands;
    private final ObjectMapper objectMapper;

    public IncidentService(
            IncidentRepository incidents,
            IncidentActionRepository actions,
            IncidentEventRepository events,
            CommandRecordRepository commands,
            ObjectMapper objectMapper) {
        this.incidents = incidents;
        this.actions = actions;
        this.events = events;
        this.commands = commands;
        this.objectMapper = objectMapper;
    }

    /**
     * 上报事件，初始状态 REPORTED；incidentKey 重复返回 409。
     */
    @Transactional
    public IncidentView report(ReportIncidentRequest request) {
        Instant now = now();
        Incident incident = new Incident(
                null,
                request.incidentKey(),
                request.severity(),
                request.summary(),
                request.reporter(),
                IncidentStatus.REPORTED,
                null,
                null,
                0,
                now,
                now);
        Incident saved;
        try {
            saved = incidents.insert(incident);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("事件已存在: " + request.incidentKey());
        }
        events.insert(new IncidentEvent(
                null, saved.id(), EVENT_REPORTED, saved.reporter(),
                null, IncidentStatus.REPORTED.name(), null, null, null, now));
        return IncidentView.of(saved);
    }

    /**
     * 首次接管：REPORTED -&gt; COMMANDING 并记录指挥人。
     */
    @Transactional
    public CommandOutcome<IncidentView> takeCommand(String incidentKey, String actorId, TakeCommandRequest request) {
        String actor = requireActor(actorId);
        return executeIdempotent(incidentKey, request.commandKey(), OP_TAKE_COMMAND, actor, IncidentView.class,
                incident -> {
                    if (incident.status() != IncidentStatus.REPORTED) {
                        throw ApiException.conflict("事件已被接管，当前状态: " + incident.status());
                    }
                    Incident updated = save(incident, IncidentStatus.COMMANDING, actor, null);
                    events.insert(new IncidentEvent(
                            null, incident.id(), EVENT_TOOK_COMMAND, actor,
                            IncidentStatus.REPORTED.name(), IncidentStatus.COMMANDING.name(),
                            null, actor, null, now()));
                    return new CommandOutcome<>(200, IncidentView.of(updated));
                });
    }

    /**
     * 发起交接（第一步）：仅当前指挥人，目标必须不同；RESOLVED/CLOSED 不可发起。
     */
    @Transactional
    public CommandOutcome<IncidentView> initiateHandover(String incidentKey, String actorId,
            InitiateHandoverRequest request) {
        String actor = requireActor(actorId);
        String target = request.targetCommanderId();
        return executeIdempotent(incidentKey, request.commandKey(), OP_HANDOVER_INITIATE,
                actor + "|" + target, IncidentView.class,
                incident -> {
                    requireCommander(incident, actor);
                    rejectIfResolvedOrClosed(incident, "发起交接");
                    if (target.equals(actor)) {
                        throw ApiException.badRequest("目标指挥人不能与当前指挥人相同");
                    }
                    if (incident.pendingCommanderId() != null) {
                        throw ApiException.conflict("存在待接受的交接，目标: " + incident.pendingCommanderId());
                    }
                    Incident updated = save(incident, incident.status(), incident.commanderId(), target);
                    events.insert(new IncidentEvent(
                            null, incident.id(), EVENT_HANDOVER_INITIATED, actor,
                            null, null, null, null, "target=" + target, now()));
                    return new CommandOutcome<>(200, IncidentView.of(updated));
                });
    }

    /**
     * 接受交接（第二步）：仅待接受的目标指挥人；接受后原子切换当前指挥人。
     */
    @Transactional
    public CommandOutcome<IncidentView> acceptHandover(String incidentKey, String actorId,
            AcceptHandoverRequest request) {
        String actor = requireActor(actorId);
        return executeIdempotent(incidentKey, request.commandKey(), OP_HANDOVER_ACCEPT, actor, IncidentView.class,
                incident -> {
                    rejectIfResolvedOrClosed(incident, "接受交接");
                    String pending = incident.pendingCommanderId();
                    if (pending == null) {
                        throw ApiException.conflict("当前无待接受的交接");
                    }
                    if (!pending.equals(actor)) {
                        throw ApiException.conflict("仅目标指挥人可接受交接");
                    }
                    Incident updated = save(incident, incident.status(), actor, null);
                    events.insert(new IncidentEvent(
                            null, incident.id(), EVENT_HANDOVER_ACCEPTED, actor,
                            null, null, incident.commanderId(), actor, null, now()));
                    return new CommandOutcome<>(200, IncidentView.of(updated));
                });
    }

    /**
     * 追加处置记录：仅当前指挥人；actionKey 同内容幂等、不同内容 409；关闭后禁止追加。
     */
    @Transactional
    public CommandOutcome<ActionView> appendAction(String incidentKey, String actorId, AppendActionRequest request) {
        String actor = requireActor(actorId);
        Instant occurredAt = request.occurredAt().truncatedTo(ChronoUnit.MICROS);
        String fingerprint = actor + "|" + request.actionKey() + "|" + occurredAt + "|"
                + request.type() + "|" + request.description();
        return executeIdempotent(incidentKey, request.commandKey(), OP_APPEND_ACTION, fingerprint, ActionView.class,
                incident -> {
                    if (incident.status() == IncidentStatus.CLOSED) {
                        throw ApiException.conflict("事件已关闭，不能追加处置记录");
                    }
                    requireCommander(incident, actor);
                    Optional<IncidentAction> existing =
                            actions.findByActionKey(incident.id(), request.actionKey());
                    if (existing.isPresent()) {
                        IncidentAction found = existing.get();
                        boolean sameContent = found.occurredAt().equals(occurredAt)
                                && found.actionType().equals(request.type())
                                && found.description().equals(request.description());
                        if (!sameContent) {
                            throw ApiException.conflict("actionKey 已存在且内容不一致: " + request.actionKey());
                        }
                        return new CommandOutcome<>(200, ActionView.of(found));
                    }
                    IncidentAction saved = actions.insert(new IncidentAction(
                            null, incident.id(), request.actionKey(), actor, occurredAt,
                            request.type(), request.description(), now()));
                    events.insert(new IncidentEvent(
                            null, incident.id(), EVENT_ACTION_APPENDED, actor,
                            null, null, null, null, "actionKey=" + request.actionKey(), now()));
                    return new CommandOutcome<>(201, ActionView.of(saved));
                });
    }

    /**
     * 状态变更：仅当前指挥人，且只能流转到下一合法状态，否则 422。
     */
    @Transactional
    public CommandOutcome<IncidentView> changeStatus(String incidentKey, String actorId,
            ChangeStatusRequest request) {
        String actor = requireActor(actorId);
        IncidentStatus target = request.targetStatus();
        return executeIdempotent(incidentKey, request.commandKey(), OP_CHANGE_STATUS,
                actor + "|" + target, IncidentView.class,
                incident -> {
                    requireCommander(incident, actor);
                    if (incident.status().next() != target) {
                        throw ApiException.invalidTransition(
                                "非法状态流转: " + incident.status() + " -> " + target);
                    }
                    Incident updated = save(incident, target, incident.commanderId(),
                            incident.pendingCommanderId());
                    events.insert(new IncidentEvent(
                            null, incident.id(), EVENT_STATUS_CHANGED, actor,
                            incident.status().name(), target.name(), null, null, null, now()));
                    return new CommandOutcome<>(200, IncidentView.of(updated));
                });
    }

    @Transactional(readOnly = true)
    public IncidentView getIncident(String incidentKey) {
        return IncidentView.of(findIncident(incidentKey));
    }

    @Transactional(readOnly = true)
    public IncidentHistoryView getHistory(String incidentKey) {
        Incident incident = findIncident(incidentKey);
        List<ActionView> actionViews = actions.findByIncidentId(incident.id()).stream()
                .map(ActionView::of)
                .toList();
        List<EventView> eventViews = events.findByIncidentId(incident.id()).stream()
                .map(EventView::of)
                .toList();
        return new IncidentHistoryView(IncidentView.of(incident), actionViews, eventViews);
    }

    /**
     * 幂等执行框架：锁事件行后检查 commandKey，重放返回首次结果，同键改参抛 409。
     */
    private <T> CommandOutcome<T> executeIdempotent(String incidentKey, String commandKey, String operation,
            String fingerprint, Class<T> bodyType, Function<Incident, CommandOutcome<T>> business) {
        Incident incident = incidents.findByKeyForUpdate(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        Optional<CommandRecord> existing = commands.find(incident.id(), commandKey);
        if (existing.isPresent()) {
            CommandRecord record = existing.get();
            if (!record.operation().equals(operation) || !record.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("commandKey 已使用且参数不一致: " + commandKey);
            }
            return new CommandOutcome<>(record.responseStatus(), readBody(record.responseBody(), bodyType));
        }
        CommandOutcome<T> outcome = business.apply(incident);
        commands.insert(new CommandRecord(
                null, incident.id(), commandKey, operation, fingerprint,
                outcome.status(), writeBody(outcome.body()), now()));
        return outcome;
    }

    private Incident findIncident(String incidentKey) {
        return incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
    }

    private Incident save(Incident incident, IncidentStatus status, String commanderId, String pendingCommanderId) {
        Incident updated = new Incident(
                incident.id(), incident.incidentKey(), incident.severity(), incident.summary(),
                incident.reporter(), status, commanderId, pendingCommanderId,
                incident.version() + 1, incident.createdAt(), now());
        incidents.update(updated);
        return updated;
    }

    private void requireCommander(Incident incident, String actorId) {
        if (incident.commanderId() == null) {
            throw ApiException.conflict("事件尚未接管");
        }
        if (!incident.commanderId().equals(actorId)) {
            throw ApiException.conflict("仅当前指挥人可执行该操作");
        }
    }

    private void rejectIfResolvedOrClosed(Incident incident, String operation) {
        if (incident.status() == IncidentStatus.RESOLVED || incident.status() == IncidentStatus.CLOSED) {
            throw ApiException.conflict("事件已 " + incident.status() + "，不能" + operation);
        }
    }

    private String requireActor(String actorId) {
        if (actorId == null || actorId.isBlank()) {
            throw ApiException.badRequest("缺少操作人: X-Actor-Id");
        }
        return actorId;
    }

    private String writeBody(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("响应序列化失败", ex);
        }
    }

    private <T> T readBody(String json, Class<T> bodyType) {
        try {
            return objectMapper.readValue(json, bodyType);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("幂等记录反序列化失败", ex);
        }
    }

    private Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
