package com.example.starter.incident;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import com.example.starter.incident.dto.Requests.HandoverAcceptRequest;
import com.example.starter.incident.dto.Requests.HandoverInitiateRequest;
import com.example.starter.incident.dto.Responses.HandoverDetailView;
import com.example.starter.incident.dto.Responses.HandoverEscalationSummary;
import com.example.starter.incident.dto.Responses.HandoverHistoryView;
import com.example.starter.incident.dto.Responses.HandoverIncidentSummary;
import com.example.starter.incident.dto.Responses.HandoverSummary;
import com.example.starter.incident.dto.Responses.HandoverTaskSummary;
import com.example.starter.incident.dto.Responses.HandoverView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 联合指挥交接服务：同一当前指挥人将 2~20 个未解决事件连同其跨事件依赖闭包
 * 整体交接给指定接收人。
 * 闭包：从提交事件的 OPEN 任务出发，沿未完成阻塞关系（阻塞事件未进入
 * CONTAINED/RESOLVED/CLOSED）可达的全部事件；提交集合必须恰好覆盖闭包，
 * 遗漏 422 并列缺失事件，多余/重复键 400，混入非本人指挥事件 403。
 * 版本：handoverVersion 为闭包摘要（每事件当前指挥人、状态、全部 OPEN 任务的
 * 版本/状态/排序后依赖、未确认升级的版本）规范化串的 SHA-256；
 * 接受时按 id 升序锁定闭包全部事件行后重算，任一变化均 409。
 * 并发：接受与任务完成/取消、升级确认、单事件交接共用事件行锁串行化，
 * 快照对应切换时的一致状态；多行加锁统一按 id 升序避免死锁。
 * 幂等：commandKey 全局唯一，按操作、操作者与结构化参数（集合排序归一）去重，
 * 同键同参重放首次响应，异参 409，失败不占键。
 */
@Service
public class HandoverService {

    private static final String SEP = "\\u001F";

    /** 联合交接事件数下限。 */
    private static final int MIN_INCIDENTS = 2;

    /** 联合交接事件数上限。 */
    private static final int MAX_INCIDENTS = 20;

    /** 视为阻塞已解除（阻塞关系已完成）的目标事件状态。 */
    private static final Set<IncidentStatus> UNBLOCKING_STATUSES = EnumSet.of(
            IncidentStatus.CONTAINED, IncidentStatus.RESOLVED, IncidentStatus.CLOSED);

    /** 不可参与联合交接的终态事件状态。 */
    private static final Set<IncidentStatus> TERMINAL_STATUSES = EnumSet.of(
            IncidentStatus.RESOLVED, IncidentStatus.CLOSED);

    private final IncidentRepository incidents;
    private final IncidentTaskRepository tasks;
    private final EscalationRepository escalations;
    private final IncidentHandoverRepository handovers;
    private final CommandKeyRepository commandKeys;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public HandoverService(IncidentRepository incidents, IncidentTaskRepository tasks,
                           EscalationRepository escalations, IncidentHandoverRepository handovers,
                           CommandKeyRepository commandKeys, ObjectMapper objectMapper,
                           Clock clock) {
        this.incidents = incidents;
        this.tasks = tasks;
        this.escalations = escalations;
        this.handovers = handovers;
        this.commandKeys = commandKeys;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    private Instant now() {
        return clock.instant();
    }

    /**
     * 发起联合交接：校验提交集合恰好覆盖依赖闭包后创建 PENDING 交接单，
     * 返回冻结当前状态的完整摘要与 handoverVersion。
     */
    @Transactional
    public HandoverDetailView initiate(String actor, HandoverInitiateRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String handoverKey = requireText(req.handoverKey(), "handoverKey");
        String toCommander = requireText(req.toCommander(), "toCommander");
        List<String> incidentKeys = validateIncidentKeys(req.incidentKeys());
        if (toCommander.equals(actor)) {
            throw ApiException.badRequest("接收人必须与当前指挥人不同");
        }
        return runIdempotent(commandKey, "handover_initiate",
                hash(actor, handoverKey, toCommander, String.join(",", incidentKeys)),
                HandoverDetailView.class, () -> {
                    if (handovers.findByKey(handoverKey).isPresent()) {
                        throw ApiException.conflict("handoverKey 已存在: " + handoverKey);
                    }
                    List<Incident> submitted = lockSubmitted(incidentKeys);
                    for (Incident incident : submitted) {
                        if (TERMINAL_STATUSES.contains(incident.status())) {
                            throw ApiException.badRequest("事件 " + incident.incidentKey()
                                    + " 已处于终态 " + incident.status() + "，不属于未解决事件（多余）");
                        }
                        if (incident.commander() == null || !incident.commander().equals(actor)) {
                            throw ApiException.forbidden("事件 " + incident.incidentKey()
                                    + " 的当前指挥人不是 " + actor + "，不能混入联合交接");
                        }
                    }
                    List<Incident> closure = computeClosure(submitted);
                    Set<String> submittedKeys = new LinkedHashSet<>(incidentKeys);
                    List<String> missing = closure.stream()
                            .map(Incident::incidentKey)
                            .filter(k -> !submittedKeys.contains(k))
                            .sorted()
                            .toList();
                    if (!missing.isEmpty()) {
                        throw ApiException.closureIncomplete("提交集合未覆盖依赖闭包，缺失事件: "
                                + String.join(",", missing), missing);
                    }
                    Instant now = now();
                    List<String> closureKeys = closure.stream()
                            .map(Incident::incidentKey).sorted().toList();
                    long handoverId = handovers.insert(new IncidentHandover(0L, handoverKey,
                            actor, toCommander, HandoverStatus.PENDING, closure.size(),
                            toJson(closureKeys), null, null, now, null));
                    for (Incident incident : closure) {
                        handovers.insertMember(handoverId, incident.id());
                    }
                    HandoverSummary summary = buildSummary(closure);
                    String version = versionOf(summary);
                    return new HandoverDetailView(
                            toView(handovers.findByKey(handoverKey).orElseThrow(), closureKeys),
                            summary, version);
                });
    }

    /**
     * 接受联合交接：仅指定接收人可接受；提交完整摘要与 expectedHandoverVersion，
     * 在锁定闭包全部事件行后重算版本，任一事件、任务、依赖或升级变化均 409；
     * 成功后在同一事务内切换全部事件指挥人、交接单置 ACCEPTED 并保存不可变闭包快照。
     */
    @Transactional
    public HandoverDetailView accept(String handoverKey, String actor, HandoverAcceptRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String expectedVersion = requireText(req.expectedHandoverVersion(),
                "expectedHandoverVersion");
        HandoverSummary submitted = validateSummary(req.summary());
        IncidentHandover handover = handovers.lockByKey(requireText(handoverKey, "handoverKey"))
                .orElseThrow(() -> ApiException.notFound("联合交接单不存在: " + handoverKey));
        return runIdempotent(commandKey, "handover_accept",
                hash(handoverKey, actor, expectedVersion, canonical(submitted)),
                HandoverDetailView.class, () -> {
                    if (handover.status() != HandoverStatus.PENDING) {
                        throw ApiException.conflict("交接单已处于终态 " + handover.status()
                                + "，不能重复接受");
                    }
                    if (!handover.toCommander().equals(actor)) {
                        throw ApiException.forbidden("只有指定接收人 " + handover.toCommander()
                                + " 能接受该联合交接");
                    }
                    if (!versionOf(submitted).equals(expectedVersion)) {
                        throw ApiException.badRequest(
                                "提交的摘要与 expectedHandoverVersion 不匹配");
                    }
                    List<Long> memberIds = handovers.listMemberIncidentIds(handover.id());
                    List<Incident> closure = incidents.lockByIds(memberIds);
                    HandoverSummary current = buildSummary(closure);
                    String currentVersion = versionOf(current);
                    if (!currentVersion.equals(expectedVersion)) {
                        throw ApiException.conflict(
                                "闭包内事件、任务、依赖或升级已变化，交接版本不一致");
                    }
                    for (Incident incident : closure) {
                        if (TERMINAL_STATUSES.contains(incident.status())) {
                            throw ApiException.conflict("闭包内事件 " + incident.incidentKey()
                                    + " 已处于终态 " + incident.status() + "，拒绝接受");
                        }
                    }
                    Instant now = now();
                    for (Incident incident : closure) {
                        incidents.updateState(incident.id(), incident.status(),
                                handover.toCommander(), now);
                    }
                    String snapshotJson = toJson(current);
                    int updated = handovers.markAccepted(handover.id(), snapshotJson,
                            currentVersion, now);
                    if (updated == 0) {
                        throw ApiException.conflict("交接单已被并发处理，不能重复接受");
                    }
                    List<String> closureKeys = closure.stream()
                            .map(Incident::incidentKey).sorted().toList();
                    return new HandoverDetailView(
                            toView(handovers.findByKey(handoverKey).orElseThrow(), closureKeys),
                            current, currentVersion);
                });
    }

    /**
     * 查询联合交接详情：PENDING 返回按当前状态实时重算的预览与版本；
     * ACCEPTED 返回接受时保存的不可变快照与版本。只读，不写数据。
     */
    @Transactional(readOnly = true)
    public HandoverDetailView getHandover(String handoverKey) {
        IncidentHandover handover = handovers.findByKey(handoverKey)
                .orElseThrow(() -> ApiException.notFound("联合交接单不存在: " + handoverKey));
        List<String> closureKeys = memberKeys(handover.id());
        if (handover.status() == HandoverStatus.ACCEPTED) {
            return new HandoverDetailView(toView(handover, closureKeys),
                    fromJson(handover.snapshotJson()), handover.handoverVersion());
        }
        List<Incident> closure = incidents.listByIds(
                handovers.listMemberIncidentIds(handover.id()));
        HandoverSummary current = buildSummary(closure);
        return new HandoverDetailView(toView(handover, closureKeys), current,
                versionOf(current));
    }

    /**
     * 查询指定事件参与的联合交接历史（按发起顺序）。只读，不写数据。
     */
    @Transactional(readOnly = true)
    public HandoverHistoryView historyForIncident(String incidentKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        List<HandoverView> views = handovers.listByIncident(incident.id()).stream()
                .map(h -> toView(h, memberKeys(h.id())))
                .toList();
        return new HandoverHistoryView(incident.incidentKey(), views);
    }

    /**
     * 校验提交事件集合：非空、无空白键、无重复键、数量 2~20；返回升序列表。
     */
    private static List<String> validateIncidentKeys(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            throw ApiException.badRequest("incidentKeys 不能为空");
        }
        List<String> keys = raw.stream().map(k -> requireText(k, "incidentKey")).toList();
        if (new HashSet<>(keys).size() != keys.size()) {
            throw ApiException.badRequest("incidentKeys 存在重复键");
        }
        if (keys.size() < MIN_INCIDENTS || keys.size() > MAX_INCIDENTS) {
            throw ApiException.badRequest("联合交接事件数必须在 " + MIN_INCIDENTS + "~"
                    + MAX_INCIDENTS + " 之间");
        }
        return keys.stream().sorted().toList();
    }

    /**
     * 按提交集合加载并按 id 升序锁定事件行；任一事件不存在返回 404。
     */
    private List<Incident> lockSubmitted(List<String> incidentKeys) {
        List<Long> ids = incidentKeys.stream()
                .map(k -> incidents.findByKey(k)
                        .orElseThrow(() -> ApiException.notFound("事件不存在: " + k)))
                .map(Incident::id)
                .sorted()
                .toList();
        return incidents.lockByIds(ids);
    }

    /**
     * 计算事件依赖闭包：从种子事件的 OPEN 任务出发，沿未完成阻塞关系
     * （阻塞事件未进入 CONTAINED/RESOLVED/CLOSED）广度优先扩展，含种子事件本身。
     */
    private List<Incident> computeClosure(List<Incident> seeds) {
        Map<Long, Incident> closure = new LinkedHashMap<>();
        Deque<Incident> queue = new ArrayDeque<>();
        for (Incident seed : seeds) {
            closure.put(seed.id(), seed);
            queue.add(seed);
        }
        while (!queue.isEmpty()) {
            Incident current = queue.poll();
            for (IncidentTask task : tasks.listOpenByIncident(current.id())) {
                for (Incident blocker : incidents.listBlockingIncidents(task.id())) {
                    if (!UNBLOCKING_STATUSES.contains(blocker.status())
                            && !closure.containsKey(blocker.id())) {
                        closure.put(blocker.id(), blocker);
                        queue.add(blocker);
                    }
                }
            }
        }
        return List.copyOf(closure.values());
    }

    /**
     * 组装闭包完整摘要：每事件冻结当前指挥人、状态、全部 OPEN 任务的
     * 版本/状态/排序后依赖，以及未确认（OPEN）升级的版本；事件按事件键升序。
     */
    private HandoverSummary buildSummary(List<Incident> closure) {
        List<HandoverIncidentSummary> items = closure.stream()
                .sorted(Comparator.comparing(Incident::incidentKey))
                .map(this::buildIncidentSummary)
                .toList();
        return new HandoverSummary(items);
    }

    private HandoverIncidentSummary buildIncidentSummary(Incident incident) {
        List<HandoverTaskSummary> openTasks = tasks.listOpenByIncident(incident.id()).stream()
                .map(t -> new HandoverTaskSummary(t.taskKey(), t.version(), t.status().name(),
                        incidents.listBlockingIncidents(t.id()).stream()
                                .map(Incident::incidentKey).sorted().toList()))
                .sorted(Comparator.comparing(HandoverTaskSummary::taskKey))
                .toList();
        List<HandoverEscalationSummary> unacknowledged = escalations
                .findByIncident(incident.id()).stream()
                .filter(e -> e.status() == EscalationStatus.OPEN)
                .map(e -> new HandoverEscalationSummary(e.id(), e.version()))
                .toList();
        return new HandoverIncidentSummary(incident.incidentKey(), incident.commander(),
                incident.status().name(), openTasks, unacknowledged);
    }

    /**
     * 交接版本：完整摘要规范化串的 SHA-256 十六进制。
     */
    private static String versionOf(HandoverSummary summary) {
        return hash(canonical(summary));
    }

    /**
     * 摘要规范化串：事件按事件键升序、任务按 taskKey 升序、依赖与升级排序后拼接，
     * 与集合顺序无关，供版本计算与同键改参检测使用。
     */
    private static String canonical(HandoverSummary summary) {
        StringBuilder sb = new StringBuilder();
        summary.incidents().stream()
                .sorted(Comparator.comparing(HandoverIncidentSummary::incidentKey))
                .forEach(inc -> {
                    sb.append(inc.incidentKey()).append(SEP)
                            .append(inc.commander()).append(SEP)
                            .append(inc.status()).append(SEP);
                    inc.openTasks().stream()
                            .sorted(Comparator.comparing(HandoverTaskSummary::taskKey))
                            .forEach(t -> {
                                sb.append(t.taskKey()).append(':').append(t.version())
                                        .append(':').append(t.status()).append(':');
                                t.dependencies().stream().sorted()
                                        .forEach(d -> sb.append(d).append(','));
                                sb.append(';');
                            });
                    sb.append(SEP);
                    inc.unacknowledgedEscalations().stream()
                            .sorted(Comparator.comparingLong(
                                    HandoverEscalationSummary::escalationId))
                            .forEach(e -> sb.append(e.escalationId()).append(':')
                                    .append(e.version()).append(';'));
                    sb.append(SEP);
                });
        return sb.toString();
    }

    /**
     * 校验接受的完整摘要结构：各集合与必填字段不得为 null。
     */
    private static HandoverSummary validateSummary(HandoverSummary summary) {
        if (summary == null || summary.incidents() == null) {
            throw ApiException.badRequest("summary 不能为空");
        }
        for (HandoverIncidentSummary inc : summary.incidents()) {
            if (inc == null || inc.incidentKey() == null || inc.status() == null
                    || inc.openTasks() == null || inc.unacknowledgedEscalations() == null) {
                throw ApiException.badRequest("summary 结构不完整");
            }
            for (HandoverTaskSummary task : inc.openTasks()) {
                if (task == null || task.taskKey() == null || task.status() == null
                        || task.dependencies() == null) {
                    throw ApiException.badRequest("summary 任务结构不完整");
                }
            }
        }
        return summary;
    }

    private List<String> memberKeys(long handoverId) {
        return incidents.listByIds(handovers.listMemberIncidentIds(handoverId)).stream()
                .map(Incident::incidentKey).sorted().toList();
    }

    private HandoverView toView(IncidentHandover handover, List<String> incidentKeys) {
        return new HandoverView(handover.id(), handover.handoverKey(), handover.fromCommander(),
                handover.toCommander(), handover.status().name(), handover.incidentCount(),
                incidentKeys, handover.createdAt(), handover.acceptedAt());
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(field + " 不能为空");
        }
        return value.strip();
    }

    /**
     * 幂等执行：同键同参重放首次响应，同键改参 409；并发同键由唯一约束串行化；
     * 业务失败时占位随事务回滚，不占键。
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

    private <T> T replay(CommandKeyRecord record, String operation, String requestHash,
                         Class<T> type) {
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

    private HandoverSummary fromJson(String json) {
        try {
            return objectMapper.readValue(json, HandoverSummary.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("闭包快照反序列化失败", e);
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
}
