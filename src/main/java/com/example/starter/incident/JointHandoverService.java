package com.example.starter.incident;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import com.example.starter.incident.dto.Requests.HandoverAcceptRequest;
import com.example.starter.incident.dto.Requests.HandoverInitiateRequest;
import com.example.starter.incident.dto.Responses.HandoverClosureIncidentView;
import com.example.starter.incident.dto.Responses.HandoverDetailView;
import com.example.starter.incident.dto.Responses.HandoverHistoryItemView;
import com.example.starter.incident.dto.Responses.HandoverHistoryView;
import com.example.starter.incident.dto.Responses.HandoverIncidentSummaryView;
import com.example.starter.incident.dto.Responses.HandoverOpenTaskView;
import com.example.starter.incident.dto.Responses.HandoverSnapshotView;
import com.example.starter.incident.dto.Responses.HandoverSummaryView;
import com.example.starter.incident.dto.Responses.HandoverView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 联合指挥交接服务。
 *
 * <p>发起：当前指挥人选择 2~20 个未解决事件，系统从这些事件的 OPEN 任务出发，
 * 沿未完成阻塞关系（阻塞事件未进入 CONTAINED/RESOLVED/CLOSED 的边）迭代扩展，
 * 直到求出事件依赖闭包；提交集合必须恰好覆盖闭包，遗漏返回 422 并列出缺失事件，
 * 混入终态事件（闭包外多余事件）返回 400，混入非本人指挥事件返回 403，重复键返回 400。
 * 扩展过程中按 id 升序逐个锁定可达事件行后再重算，直到闭包不动点：
 * 并发新建阻塞边必须先持有其任务所属事件行锁，故闭包在锁定集合下与图一致。
 *
 * <p>冻结：闭包内每个事件的当前指挥人、状态与版本，全部 OPEN 任务的版本、状态及
 * 排序后依赖，以及未确认（OPEN）升级的版本，规范化 JSON 后取 SHA-256 作为
 * handoverVersion。
 *
 * <p>接受：先锁交接单行，再按 id 升序锁定全部闭包事件行，重算当前摘要；
 * expectedHandoverVersion 不一致或提交摘要与当前状态有任一字段差异均返回 409
 * （含事件进入终态、任务完成/修订、依赖变化、升级被确认/取消）。
 * 校验通过后在同一事务内切全部事件指挥人、交接单置 ACCEPTED、写不可变闭包快照，
 * 不允许部分接管。
 *
 * <p>幂等：commandKey 按操作、操作者与规范化结构化参数去重，集合换序同参，异参 409；
 * 占位与业务写入同事务，失败回滚不占键。
 */
@Service
public class JointHandoverService {

    private static final String SEP = "\u001F";

    /** 联合交接选择事件数下限。 */
    private static final int MIN_INCIDENTS = 2;

    /** 联合交接选择事件数上限。 */
    private static final int MAX_INCIDENTS = 20;

    /** 阻塞已解除（关系已完成）的目标事件状态。 */
    private static final Set<IncidentStatus> FINISHED_BLOCK_STATUSES = EnumSet.of(
            IncidentStatus.CONTAINED, IncidentStatus.RESOLVED, IncidentStatus.CLOSED);

    /** 不允许选入联合交接的终态事件。 */
    private static final Set<IncidentStatus> TERMINAL_STATUSES = EnumSet.of(
            IncidentStatus.RESOLVED, IncidentStatus.CLOSED);

    private final IncidentRepository incidents;
    private final IncidentTaskRepository tasks;
    private final EscalationRepository escalations;
    private final JointHandoverRepository handovers;
    private final CommandKeyRepository commandKeys;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public JointHandoverService(IncidentRepository incidents, IncidentTaskRepository tasks,
                                EscalationRepository escalations, JointHandoverRepository handovers,
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
     * 发起联合交接：校验提交集合、求依赖闭包、冻结摘要并生成 handoverVersion。
     */
    @Transactional
    public HandoverView initiate(String actor, HandoverInitiateRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String handoverKey = requireText(req.handoverKey(), "handoverKey");
        String toCommander = requireText(req.toCommander(), "toCommander");
        List<String> requested = req.incidentKeys();
        if (requested == null) {
            throw ApiException.badRequest("incidentKeys 不能为空");
        }
        List<String> submitted = requested.stream()
                .map(k -> requireText(k, "incidentKey")).toList();
        if (submitted.size() < MIN_INCIDENTS || submitted.size() > MAX_INCIDENTS) {
            throw ApiException.badRequest("联合交接事件数必须在 " + MIN_INCIDENTS + "~"
                    + MAX_INCIDENTS + " 个之间");
        }
        Set<String> distinct = new LinkedHashSet<>(submitted);
        if (distinct.size() != submitted.size()) {
            throw ApiException.badRequest("incidentKeys 存在重复键");
        }
        if (toCommander.equals(actor)) {
            throw ApiException.badRequest("接收人必须与当前指挥人不同");
        }
        List<String> canonicalKeys = submitted.stream().sorted().toList();
        return runIdempotent(commandKey, "handover_initiate",
                hash(actor, handoverKey, toCommander, String.join(",", canonicalKeys)),
                HandoverView.class, () -> {
                    if (handovers.findByKey(handoverKey).isPresent()) {
                        throw ApiException.conflict("handoverKey 已存在: " + handoverKey);
                    }
                    // 1. 锁定全部提交事件（按 id 升序，避免交叉集合死锁）
                    List<Incident> submittedIncidents = lockByKeys(submitted);
                    // 2. 权限与终态校验：必须全部由发起人当前指挥且均为未解决事件
                    for (Incident incident : submittedIncidents) {
                        if (incident.commander() == null || !incident.commander().equals(actor)) {
                            throw ApiException.forbidden("事件 " + incident.incidentKey()
                                    + " 不由 " + actor + " 当前指挥，不能混入联合交接");
                        }
                        if (TERMINAL_STATUSES.contains(incident.status())) {
                            throw ApiException.badRequest("事件 " + incident.incidentKey()
                                    + " 已处于终态 " + incident.status() + "，不能发起联合交接");
                        }
                    }
                    // 3. 迭代扩展闭包并逐个锁定可达事件，直到不动点
                    List<Long> seedIds = submittedIncidents.stream().map(Incident::id).sorted().toList();
                    List<Incident> closure = expandAndLockClosure(seedIds);
                    Set<String> closureKeys = closure.stream()
                            .map(Incident::incidentKey).collect(LinkedHashSet::new, Set::add, Set::addAll);
                    // 遗漏闭包事件（含他人指挥或尚未接管的传递依赖事件）→ 422，并列缺失事件；
                    // 若把这类事件直接加入提交集合，则在上面的权限校验中返回 403
                    List<String> missing = closure.stream().map(Incident::incidentKey)
                            .filter(k -> !distinct.contains(k)).sorted().toList();
                    if (!missing.isEmpty()) {
                        throw ApiException.closureIncomplete(
                                "提交集合未覆盖完整依赖闭包，缺失事件: " + String.join(",", missing),
                                missing);
                    }
                    // 6. 冻结摘要并生成版本
                    HandoverSummaryView summary = buildSummary(closure);
                    String version = hash(toJson(summary));
                    Instant now = now();
                    JointHandover handover = new JointHandover(0L, handoverKey, actor, toCommander,
                            HandoverStatus.PENDING, version, toJson(submitted), toJson(canonicalKeys),
                            toJson(summary), null, now, now);
                    long handoverId;
                    try {
                        handoverId = handovers.insert(handover);
                    } catch (DuplicateKeyException e) {
                        throw ApiException.conflict("handoverKey 已存在: " + handoverKey);
                    }
                    List<Incident> sortedClosure = closure.stream()
                            .sorted(Comparator.comparing(Incident::incidentKey)).toList();
                    for (int i = 0; i < sortedClosure.size(); i++) {
                        Incident incident = sortedClosure.get(i);
                        handovers.insertIncident(new JointHandoverIncident(0L, handoverId,
                                incident.id(), incident.incidentKey(),
                                distinct.contains(incident.incidentKey()), true, i));
                    }
                    return toView(handover, submitted, canonicalKeys, summary, null);
                });
    }

    /**
     * 接受联合交接：校验接收人与冻结版本/摘要，单事务整体切换指挥权并保存不可变快照。
     */
    @Transactional
    public HandoverView accept(String actor, String handoverKey, HandoverAcceptRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String expectedVersion = requireText(req.expectedHandoverVersion(), "expectedHandoverVersion");
        if (req.summary() == null) {
            throw ApiException.badRequest("summary 不能为空");
        }
        String submittedSummaryJson;
        try {
            submittedSummaryJson = objectMapper.writeValueAsString(req.summary());
        } catch (JsonProcessingException e) {
            throw ApiException.badRequest("summary 序列化失败: " + e.getMessage());
        }
        return runIdempotent(commandKey, "handover_accept",
                hash(actor, handoverKey, expectedVersion, submittedSummaryJson),
                HandoverView.class, () -> {
                    JointHandover handover = handovers.lockByKey(handoverKey)
                            .orElseThrow(() -> ApiException.notFound(
                                    "联合交接单不存在: " + handoverKey));
                    if (handover.status() != HandoverStatus.PENDING) {
                        throw ApiException.conflict(
                                "联合交接单已处于终态 " + handover.status() + "，不能重复接受");
                    }
                    if (!handover.toCommander().equals(actor)) {
                        throw ApiException.conflict("只有指定接收人 " + handover.toCommander()
                                + " 能接受联合交接");
                    }
                    List<String> closureKeys = readStringList(handover.closureIncidentKeys());
                    List<Incident> locked = lockByKeys(closureKeys);
                    // 重算切换时一致状态的摘要，任一变化均 409
                    HandoverSummaryView current = buildSummary(locked);
                    String currentVersion = hash(toJson(current));
                    if (!currentVersion.equals(expectedVersion)
                            || !expectedVersion.equals(handover.handoverVersion())) {
                        throw ApiException.conflict(
                                "交接摘要已过期：事件、任务、依赖或升级发生变化，expectedHandoverVersion 不匹配");
                    }
                    if (!toJson(current).equals(handover.frozenSummary())
                            || !toJson(current).equals(submittedSummaryJson)) {
                        throw ApiException.conflict("交接摘要内容与冻结时不一致，拒绝接受");
                    }
                    Instant now = now();
                    // 同一事务：整体切换指挥人
                    for (Incident incident : locked) {
                        incidents.switchCommander(incident.id(), actor, now);
                    }
                    handovers.markAccepted(handover.id(), now);
                    // 不可变闭包快照：对应切换后一致状态（指挥人=接收人，版本已加 1）
                    List<Incident> switched = incidents.lockByIdsOrdered(
                            locked.stream().map(Incident::id).sorted().toList());
                    Map<String, Incident> switchedByKey = switched.stream()
                            .collect(LinkedHashMap::new, (m, i) -> m.put(i.incidentKey(), i),
                                    Map::putAll);
                    for (HandoverIncidentSummaryView row : current.incidents()) {
                        Incident after = switchedByKey.get(row.incidentKey());
                        handovers.insertSnapshot(new JointHandoverSnapshot(0L, handover.id(),
                                after.id(), after.incidentKey(), after.commander(),
                                after.status().name(), after.version(), toJson(row.openTasks()),
                                row.escalationVersion(), now));
                    }
                    List<String> submittedKeys = readStringList(handover.submittedIncidentKeys());
                    JointHandover refreshed = handovers.lockByKey(handoverKey).orElseThrow();
                    return toView(refreshed, submittedKeys, closureKeys, current, now);
                });
    }

    /**
     * 查询交接闭包详情（交接单 + 闭包事件行 + 不可变快照）。只读，不写数据。
     */
    @Transactional(readOnly = true)
    public HandoverDetailView detail(String handoverKey) {
        JointHandover handover = handovers.findByKey(handoverKey)
                .orElseThrow(() -> ApiException.notFound("联合交接单不存在: " + handoverKey));
        return new HandoverDetailView(toView(handover), listClosureViews(handover.id()),
                listSnapshotViews(handover.id()));
    }

    /**
     * 查询指挥人相关的交接历史（作为发起人或接收人）。只读，不写数据。
     */
    @Transactional(readOnly = true)
    public HandoverHistoryView history(String commander) {
        String actor = requireText(commander, "commander");
        List<HandoverHistoryItemView> items = handovers.listHistoryForCommander(actor).stream()
                .map(h -> new HandoverHistoryItemView(toView(h), listClosureViews(h.id())))
                .toList();
        return new HandoverHistoryView(actor, items);
    }

    /**
     * 按事件键（任意顺序）锁定事件行，内部按 id 升序加锁；有不存在的键抛 404。
     */
    private List<Incident> lockByKeys(List<String> keys) {
        if (keys.isEmpty()) {
            return List.of();
        }
        List<Incident> found = incidents.findAllByKeysOrdered(keys);
        if (found.size() != keys.stream().distinct().count()) {
            Set<String> existing = found.stream().map(Incident::incidentKey)
                    .collect(LinkedHashSet::new, Set::add, Set::addAll);
            String missing = keys.stream().filter(k -> !existing.contains(k))
                    .findFirst().orElse("?");
            throw ApiException.notFound("事件不存在: " + missing);
        }
        return incidents.lockByIdsOrdered(found.stream().map(Incident::id).sorted().toList());
    }

    /**
     * 迭代求未解决依赖闭包并逐步锁定：每轮沿当前已锁定事件集合的 OPEN 任务未完成
     * 阻塞边扩展一步，将新到达事件按 id 升序锁定后再重算，直到集合不再增长。
     * 并发新建阻塞边的事务必须先持有其任务所属事件行锁，因此新边一旦能被读到，
     * 其来源事件必已在本事务锁定集合中（或其事务在锁定后无法再提交），闭包不漏边。
     */
    private List<Incident> expandAndLockClosure(List<Long> seedIds) {
        List<Long> lockedIds = seedIds.stream().sorted().toList();
        while (true) {
            Set<Long> reachable = new LinkedHashSet<>(lockedIds);
            Map<Long, Incident> statusById = new LinkedHashMap<>();
            for (var edge : tasks.listOpenBlockerEdgesByFromIncidents(new ArrayList<>(reachable))) {
                long target = edge.toIncidentId();
                Incident targetIncident = statusById.computeIfAbsent(target,
                        k -> incidents.listByIdsOrdered(List.of(k)).stream().findFirst().orElse(null));
                if (targetIncident != null
                        && !FINISHED_BLOCK_STATUSES.contains(targetIncident.status())) {
                    reachable.add(target);
                }
            }
            List<Long> nextLocked = reachable.stream().sorted().toList();
            if (nextLocked.equals(lockedIds)) {
                return incidents.lockByIdsOrdered(nextLocked);
            }
            // 锁定本轮新到达事件后重算
            incidents.lockByIdsOrdered(nextLocked);
            lockedIds = nextLocked;
        }
    }

    /**
     * 在已锁定闭包事件上构建冻结摘要：事件按键排序，OPEN 任务按任务键排序，依赖按事件键排序。
     */
    private HandoverSummaryView buildSummary(List<Incident> closure) {
        List<Long> incidentIds = closure.stream().map(Incident::id).toList();
        List<IncidentTask> openTasks = tasks.listOpenByIncidents(incidentIds);
        List<Long> taskIds = openTasks.stream().map(IncidentTask::id).toList();
        Map<Long, List<String>> blockersByTask = new LinkedHashMap<>();
        if (!taskIds.isEmpty()) {
            var refs = tasks.listBlockersForTasks(taskIds);
            List<Long> blockerIds = refs.stream()
                    .map(IncidentTaskRepository.TaskBlockerRef::blockerIncidentId)
                    .distinct().sorted().toList();
            Map<Long, String> incidentKeyById = incidents.listByIdsOrdered(blockerIds).stream()
                    .collect(LinkedHashMap::new, (m, i) -> m.put(i.id(), i.incidentKey()),
                            Map::putAll);
            for (var ref : refs) {
                blockersByTask.computeIfAbsent(ref.taskId(), k -> new ArrayList<>())
                        .add(incidentKeyById.get(ref.blockerIncidentId()));
            }
            blockersByTask.replaceAll((k, v) -> v.stream().sorted().distinct().toList());
        }
        Map<Long, Long> openEscalationVersionByIncident = new LinkedHashMap<>();
        for (Escalation escalation : escalations.listByIncidents(incidentIds)) {
            if (escalation.status() == EscalationStatus.OPEN) {
                openEscalationVersionByIncident.put(escalation.incidentId(), escalation.version());
            }
        }
        Map<Long, List<IncidentTask>> tasksByIncident = openTasks.stream()
                .collect(LinkedHashMap::new,
                        (m, t) -> m.computeIfAbsent(t.incidentId(), k -> new ArrayList<>()).add(t),
                        Map::putAll);
        List<HandoverIncidentSummaryView> rows = new ArrayList<>();
        for (Incident incident : closure.stream()
                .sorted(Comparator.comparing(Incident::incidentKey)).toList()) {
            List<HandoverOpenTaskView> taskViews = tasksByIncident
                    .getOrDefault(incident.id(), List.of()).stream()
                    .sorted(Comparator.comparing(IncidentTask::taskKey))
                    .map(t -> new HandoverOpenTaskView(t.taskKey(), t.version(), t.status().name(),
                            blockersByTask.getOrDefault(t.id(), List.of())))
                    .toList();
            rows.add(new HandoverIncidentSummaryView(incident.incidentKey(), incident.commander(),
                    incident.status().name(), incident.version(), taskViews,
                    openEscalationVersionByIncident.get(incident.id())));
        }
        return new HandoverSummaryView(rows);
    }

    private List<HandoverClosureIncidentView> listClosureViews(long handoverId) {
        return handovers.listIncidents(handoverId).stream()
                .map(r -> new HandoverClosureIncidentView(r.incidentKey(), r.inSubmitted(), r.seqNo()))
                .toList();
    }

    private List<HandoverSnapshotView> listSnapshotViews(long handoverId) {
        return handovers.listSnapshots(handoverId).stream()
                .map(s -> new HandoverSnapshotView(s.incidentKey(), s.commander(),
                        s.incidentStatus(), s.incidentVersion(),
                        readOpenTasks(s.openTasksJson()), s.escalationVersion()))
                .toList();
    }

    private HandoverView toView(JointHandover handover) {
        return toView(handover, readStringList(handover.submittedIncidentKeys()),
                readStringList(handover.closureIncidentKeys()),
                readSummary(handover.frozenSummary()), handover.acceptedAt());
    }

    private HandoverView toView(JointHandover handover, List<String> submittedKeys,
                                List<String> closureKeys, HandoverSummaryView summary,
                                Instant acceptedAt) {
        return new HandoverView(handover.handoverKey(), handover.fromCommander(),
                handover.toCommander(), handover.status().name(), handover.handoverVersion(),
                List.copyOf(submittedKeys), List.copyOf(closureKeys), null, summary,
                acceptedAt, handover.createdAt());
    }

    private List<String> readStringList(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("持久化 JSON 反序列化失败", e);
        }
    }

    private List<HandoverOpenTaskView> readOpenTasks(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<HandoverOpenTaskView>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("快照任务 JSON 反序列化失败", e);
        }
    }

    private HandoverSummaryView readSummary(String json) {
        try {
            return objectMapper.readValue(json, HandoverSummaryView.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("冻结摘要 JSON 反序列化失败", e);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(field + " 不能为空");
        }
        return value.strip();
    }

    /**
     * 幂等执行：同键同参重放首次响应，同键改参 409；并发同键由唯一约束串行化；
     * 业务失败随事务回滚，占位一并撤销（失败不占键）。
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
}
