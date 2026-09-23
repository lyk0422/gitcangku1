package com.example.starter.incident;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.example.starter.incident.dto.Requests.HandoverAcceptRequest;
import com.example.starter.incident.dto.Requests.HandoverFreezeRequest;
import com.example.starter.incident.dto.Responses.HandoverIncidentSummary;
import com.example.starter.incident.dto.Responses.HandoverSnapshotView;
import com.example.starter.incident.dto.Responses.HandoverSummary;
import com.example.starter.incident.dto.Responses.HandoverTaskSummary;
import com.example.starter.incident.dto.Responses.HandoverView;
import com.example.starter.incident.dto.Responses.SnapshotEscalationView;
import com.example.starter.incident.dto.Responses.SnapshotIncidentView;
import com.example.starter.incident.dto.Responses.SnapshotTaskView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 联合指挥交接核心服务。
 *
 * <p>预览冻结：当前指挥人选择 2~20 个未解决事件，系统从这些事件的 OPEN 任务出发，
 * 沿未完成（阻塞事件仍未进入 CONTAINED/RESOLVED/CLOSED）的阻塞关系做闭包，
 * 提交集合必须恰好覆盖闭包，遗漏返回 422 并列缺失事件；多余、混入非本人指挥事件或
 * 重复键返回 400/403。冻结每事件指挥人、状态、全部 OPEN 任务版本状态及排序依赖、
 * 未确认升级版本，生成 handoverVersion（冻结摘要 SHA-256）。
 *
 * <p>接受：接收人回传完整摘要和 expectedHandoverVersion；任一事件、任务、依赖或升级
 * 变化均 409。成功后一个事务内切换全部事件指挥人、交接单 ACCEPTED 并保存不可变闭包
 * 快照；终态事件或非指定接收人拒绝。
 *
 * <p>并发：冻结按事件键序迭代锁定闭包全部事件行直至闭包固定点（边与任务、状态、
 * 升级的变更都需要任务/事件所属事件的行锁，故全部锁定后闭包稳定）；接受先锁交接单
 * 行再按事件键序锁定闭包事件行。任务完成/修订、升级确认、单事件转交均先锁事件行，
 * 故与联合交接按事务提交顺序串行，快照对应切换时一致状态。
 */
@Service
public class JointHandoverService {

    /** 联合交接提交事件数下限。 */
    private static final int MIN_INCIDENTS = 2;

    /** 联合交接提交事件数上限。 */
    private static final int MAX_INCIDENTS = 20;

    private static final Set<IncidentStatus> UNBLOCKING_STATUSES = Set.of(
            IncidentStatus.CONTAINED, IncidentStatus.RESOLVED, IncidentStatus.CLOSED);

    private final IncidentRepository incidents;
    private final IncidentTaskRepository tasks;
    private final EscalationRepository escalations;
    private final JointHandoverRepository handovers;
    private final IdempotentCommands commands;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public JointHandoverService(IncidentRepository incidents, IncidentTaskRepository tasks,
                                EscalationRepository escalations, JointHandoverRepository handovers,
                                IdempotentCommands commands, ObjectMapper objectMapper, Clock clock) {
        this.incidents = incidents;
        this.tasks = tasks;
        this.escalations = escalations;
        this.handovers = handovers;
        this.commands = commands;
        // 冻结摘要需要确定性 JSON：记录组件声明顺序固定，关闭时间戳写为数值。
        this.objectMapper = objectMapper.copy()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.clock = clock;
    }

    private Instant now() {
        return clock.instant();
    }

    /**
     * 预览冻结：校验提交集合、计算依赖闭包、恰好覆盖校验，随后在依赖图全局锁与闭包
     * 事件行锁内冻结摘要，生成 handoverVersion 与 PENDING 交接单。
     */
    @Transactional
    public HandoverView freeze(String actor, HandoverFreezeRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String handoverKey = requireText(req.handoverKey(), "handoverKey");
        String toCommander = requireText(req.toCommander(), "toCommander");
        List<String> rawKeys = req.incidentKeys() == null ? List.of() : req.incidentKeys();
        if (rawKeys.size() < MIN_INCIDENTS || rawKeys.size() > MAX_INCIDENTS) {
            throw ApiException.badRequest(
                    "提交事件数必须在 " + MIN_INCIDENTS + "~" + MAX_INCIDENTS + " 个之间");
        }
        Set<String> duplicateKeys = rawKeys.stream()
                .filter(k -> k != null && !k.isBlank())
                .map(String::strip)
                .collect(Collectors.groupingBy(k -> k, Collectors.counting()))
                .entrySet().stream().filter(e -> e.getValue() > 1).map(Map.Entry::getKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!duplicateKeys.isEmpty()) {
            throw ApiException.badRequest("提交事件键重复: " + String.join(",", duplicateKeys));
        }
        List<String> submittedKeys = rawKeys.stream().map(k -> requireText(k, "incidentKey"))
                .sorted().toList();
        String requestHash = RequestHashes.sha256(handoverKey, actor, toCommander,
                String.join(",", submittedKeys));
        return commands.run(commandKey, "joint_handover_freeze", requestHash, HandoverView.class,
                () -> doFreeze(actor, handoverKey, toCommander, submittedKeys));
    }

    private HandoverView doFreeze(String actor, String handoverKey, String toCommander,
                                  List<String> submittedKeys) {
        if (handovers.findByKey(handoverKey).isPresent()) {
            throw ApiException.conflict("handoverKey 已存在: " + handoverKey);
        }
        if (toCommander.equals(actor)) {
            throw ApiException.badRequest("接收人必须与当前指挥人不同");
        }
        // 不加锁载入提交事件，先做存在性/未解决/指挥人校验。
        // 终态（RESOLVED/CLOSED）事件不属于未完成依赖闭包，混入即为“多余”，返回 400。
        for (String key : submittedKeys) {
            Incident incident = incidents.findByKey(key)
                    .orElseThrow(() -> ApiException.notFound("事件不存在: " + key));
            if (incident.status() == IncidentStatus.RESOLVED
                    || incident.status() == IncidentStatus.CLOSED) {
                throw ApiException.badRequest(
                        "终态事件 " + key + "（" + incident.status() + "）不属于未解决事件，不能发起联合交接");
            }
            if (incident.commander() == null || !incident.commander().equals(actor)) {
                throw ApiException.forbidden("事件 " + key + " 的当前指挥人不是 " + actor);
            }
        }

        // 迭代加锁直到闭包固定点：所有边的新增（创建任务）、任务完成/取消、状态推进、
        // 升级确认都要求先持有任务所属事件的行锁，故闭包内事件全部锁定后闭包不再变化。
        // 全程按事件键序加锁，与其它写路径的单事件行锁及第二个冻结请求保持一致顺序，避免死锁。
        Map<String, Incident> lockedByKey = new LinkedHashMap<>();
        List<String> toLock = submittedKeys.stream().sorted().toList();
        while (!toLock.isEmpty()) {
            for (String key : toLock) {
                lockedByKey.putIfAbsent(key, incidents.lockByKey(key)
                        .orElseThrow(() -> ApiException.notFound("事件不存在: " + key)));
            }
            Set<String> reached = expandReachable(lockedByKey);
            toLock = reached.stream().filter(k -> !lockedByKey.containsKey(k)).sorted().toList();
        }
        Set<String> closure = closureFromLocked(submittedKeys, lockedByKey);
        requireExactlyCovered(submittedKeys, closure);

        List<String> orderedClosure = closure.stream().sorted().toList();
        // 恰好覆盖意味着提交集合就是闭包：闭包内每个事件都必须未解决、由发起人指挥，
        // 且不存在待接受的单事件转交，否则该联合交接不可发起。
        for (String key : orderedClosure) {
            Incident incident = lockedByKey.get(key);
            if (incident.status() == IncidentStatus.RESOLVED
                    || incident.status() == IncidentStatus.CLOSED) {
                throw ApiException.badRequest(
                        "终态事件 " + key + " 不属于未解决事件，不能发起联合交接");
            }
            if (incident.commander() == null || !incident.commander().equals(actor)) {
                throw ApiException.forbidden(
                        "闭包事件 " + key + " 的当前指挥人不是 " + actor);
            }
            if (incidents.findPendingTransfer(incident.id()).isPresent()) {
                throw ApiException.conflict(
                        "事件 " + key + " 存在待接受的单事件转交，不能发起联合交接");
            }
        }

        List<Long> closureIds = orderedClosure.stream()
                .map(k -> lockedByKey.get(k).id()).toList();
        List<String> overlapping = handovers.findPendingKeysOverlapping(closureIds, -1L);
        if (!overlapping.isEmpty()) {
            throw ApiException.conflict(
                    "闭包内事件已存在待接受的联合交接: " + String.join(",", overlapping));
        }

        HandoverSummary summary = buildSummary(actor, toCommander, orderedClosure, lockedByKey);
        String version = RequestHashes.sha256(canonicalJson(summary));
        Instant now = now();
        long handoverId = handovers.insert(new JointHandover(0L, handoverKey, actor, toCommander,
                HandoverStatus.PENDING, version, writeJson(orderedClosure), writeJson(summary),
                now, now, null));
        for (int i = 0; i < orderedClosure.size(); i++) {
            handovers.insertMember(handoverId, lockedByKey.get(orderedClosure.get(i)).id(), i);
        }
        return new HandoverView(handoverKey, actor, toCommander, HandoverStatus.PENDING.name(),
                version, orderedClosure, summary, now, null);
    }

    /** 提交集合必须恰好覆盖闭包：遗漏 422（列缺失事件），多余 400。 */
    private static void requireExactlyCovered(List<String> submittedKeys, Set<String> closure) {
        List<String> missing = closure.stream().filter(k -> !submittedKeys.contains(k)).sorted()
                .toList();
        if (!missing.isEmpty()) {
            throw ApiException.unprocessableEntity("CLOSURE_NOT_COVERED",
                    "提交集合未恰好覆盖依赖闭包，缺失事件: " + String.join(",", missing),
                    List.copyOf(missing));
        }
        List<String> extra = submittedKeys.stream().filter(k -> !closure.contains(k)).sorted()
                .toList();
        if (!extra.isEmpty()) {
            throw ApiException.badRequest(
                    "提交集合包含闭包之外的多余事件: " + String.join(",", extra));
        }
    }

    /**
     * 基于已锁定事件向外扩展可达集合：边从库中读取（新加入事件随后补锁），
     * 阻塞解除状态优先取行锁内最新值，未锁定事件取普通读值（可能过期，固定点
     * 循环补锁后会由 {@link #closureFromLocked} 以权威值重算）。
     */
    private Set<String> expandReachable(Map<String, Incident> lockedByKey) {
        Map<Long, Incident> lockedById = lockedByKey.values().stream()
                .collect(Collectors.toMap(Incident::id, i -> i, (a, b) -> a));
        Set<String> reachable = new LinkedHashSet<>(lockedByKey.keySet());
        for (Incident incident : lockedByKey.values()) {
            for (IncidentTask task : tasks.listOpenByIncident(incident.id())) {
                for (Incident blocker : incidents.listBlockingIncidents(task.id())) {
                    Incident authoritative = lockedById.get(blocker.id());
                    IncidentStatus status = authoritative == null ? blocker.status()
                            : authoritative.status();
                    if (!UNBLOCKING_STATUSES.contains(status)) {
                        reachable.add(blocker.incidentKey());
                    }
                }
            }
        }
        return reachable;
    }

    /**
     * 全部可达事件均已锁定后，以行锁权威状态从提交种子出发计算最终闭包：边的新增需要
     * 任务所属事件行锁（已被本事务持有），故枚举到的边集合完整；状态机只前进，故结果稳定。
     * 闭包只从提交种子扩展，因此闭包之外的多余提交事件可被恰好覆盖校验识别。
     */
    private Set<String> closureFromLocked(Collection<String> seedKeys,
                                          Map<String, Incident> lockedByKey) {
        Map<Long, Incident> lockedById = lockedByKey.values().stream()
                .collect(Collectors.toMap(Incident::id, i -> i));
        Set<String> closure = new LinkedHashSet<>(seedKeys);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String key : List.copyOf(closure)) {
                Incident incident = lockedByKey.get(key);
                if (incident == null) {
                    continue;
                }
                for (IncidentTask task : tasks.listOpenByIncident(incident.id())) {
                    for (Incident blocker : incidents.listBlockingIncidents(task.id())) {
                        Incident lockedBlocker = lockedById.get(blocker.id());
                        boolean unresolved = lockedBlocker != null
                                && !UNBLOCKING_STATUSES.contains(lockedBlocker.status());
                        if (unresolved && closure.add(blocker.incidentKey())) {
                            changed = true;
                        }
                    }
                }
            }
        }
        return closure;
    }

    private HandoverSummary buildSummary(String actor, String toCommander, List<String> orderedKeys,
                                         Map<String, Incident> lockedByKey) {
        List<HandoverIncidentSummary> incidentSummaries = new ArrayList<>();
        for (String key : orderedKeys) {
            Incident incident = lockedByKey.get(key);
            List<HandoverTaskSummary> openTasks = tasks.listOpenByIncident(incident.id()).stream()
                    .sorted(Comparator.comparing(IncidentTask::taskKey))
                    .map(t -> new HandoverTaskSummary(t.taskKey(), t.status().name(), t.updatedAt(),
                            incidents.listBlockingIncidents(t.id()).stream()
                                    .map(Incident::incidentKey).sorted().toList()))
                    .toList();
            Instant escalationVersion = escalations.findByIncident(incident.id())
                    .filter(e -> e.status() == EscalationStatus.OPEN)
                    .map(Escalation::updatedAt).orElse(null);
            incidentSummaries.add(new HandoverIncidentSummary(incident.incidentKey(),
                    incident.commander(), incident.status().name(), incident.updatedAt(),
                    openTasks, escalationVersion));
        }
        return new HandoverSummary(actor, toCommander, orderedKeys, incidentSummaries);
    }

    /**
     * 接受联合交接：仅指定接收人；回传摘要与版本必须与冻结时一致，且锁定闭包后当前
     * 状态逐项复核；任一事件、任务、依赖或升级变化均 409。成功后同事务切换全部事件
     * 指挥人、交接单 ACCEPTED、写入不可变闭包快照，不允许部分接管。
     */
    @Transactional
    public HandoverView accept(String actor, HandoverAcceptRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String expectedVersion = requireText(req.expectedHandoverVersion(), "expectedHandoverVersion");
        if (req.summary() == null) {
            throw ApiException.badRequest("summary 不能为空");
        }
        HandoverSummary submitted = normalize(req.summary());
        String requestHash = RequestHashes.sha256(actor, expectedVersion, canonicalJson(submitted));
        return commands.run(commandKey, "joint_handover_accept", requestHash, HandoverView.class,
                () -> doAccept(actor, expectedVersion, submitted));
    }

    private HandoverView doAccept(String actor, String expectedVersion, HandoverSummary submitted) {
        // 先锁定交接单自身，保证并发接受串行；闭包事件随后按键序锁定，避免死锁。
        String handoverKey = lookupKeyBySummary(submitted);
        JointHandover handover = handovers.lockByKey(handoverKey)
                .orElseThrow(() -> ApiException.notFound("交接单不存在"));
        if (!handover.toCommander().equals(actor)) {
            throw ApiException.forbidden(
                    "只有指定接收人 " + handover.toCommander() + " 能接受联合交接");
        }
        if (handover.status() != HandoverStatus.PENDING) {
            throw ApiException.conflict("交接单已处于终态 " + handover.status() + "，不能接受");
        }
        List<String> frozenClosure = readStringList(handover.closureKeys());
        if (!frozenClosure.equals(submitted.closureIncidentKeys())) {
            throw ApiException.conflict("提交摘要的闭包事件集合与冻结时不一致");
        }
        if (!handover.handoverVersion().equals(expectedVersion)) {
            throw ApiException.conflict("expectedHandoverVersion 与冻结版本不一致");
        }
        HandoverSummary frozen = normalize(readValue(handover.frozenSummary(), HandoverSummary.class));
        if (!canonicalJson(frozen).equals(canonicalJson(submitted))) {
            throw ApiException.conflict("提交的冻结摘要与服务端不一致");
        }

        Map<String, Incident> lockedByKey = new LinkedHashMap<>();
        for (String key : frozenClosure) {
            lockedByKey.put(key, incidents.lockByKey(key)
                    .orElseThrow(() -> ApiException.notFound("事件不存在: " + key)));
        }
        verifyUnchanged(frozen, lockedByKey);

        Instant now = now();
        // 先保存切换时一致状态的不可变快照，再切换全部事件指挥人，同一事务提交。
        saveSnapshot(handover.id(), frozenClosure, lockedByKey);
        for (Incident incident : lockedByKey.values()) {
            incidents.updateState(incident.id(), incident.status(), handover.toCommander(), now);
        }
        handovers.markAccepted(handover.id(), now);
        return new HandoverView(handover.handoverKey(), handover.fromCommander(),
                handover.toCommander(), HandoverStatus.ACCEPTED.name(),
                handover.handoverVersion(), frozenClosure, frozen, handover.createdAt(), now);
    }

    /** 根据摘要中的发起人/接收人/闭包首个事件反查 PENDING 交接单业务键。 */
    private String lookupKeyBySummary(HandoverSummary submitted) {
        if (submitted.closureIncidentKeys() == null || submitted.closureIncidentKeys().isEmpty()) {
            throw ApiException.badRequest("summary.closureIncidentKeys 不能为空");
        }
        if (submitted.fromCommander() == null || submitted.toCommander() == null) {
            throw ApiException.badRequest("summary 缺少发起人或接收人");
        }
        String firstKey = submitted.closureIncidentKeys().stream().sorted().findFirst().orElseThrow();
        Incident incident = incidents.findByKey(firstKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + firstKey));
        return handovers.listByIncident(incident.id()).stream()
                .filter(h -> h.status() == HandoverStatus.PENDING)
                .filter(h -> h.fromCommander().equals(submitted.fromCommander())
                        && h.toCommander().equals(submitted.toCommander()))
                .map(JointHandover::handoverKey)
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("没有匹配的待接受联合交接"));
    }

    /**
     * 逐项复核冻结后状态未变化：事件指挥人/状态/版本、每事件 OPEN 任务集合及任务
     * 版本/状态/排序依赖、未确认升级版本；终态事件同样拒绝。任一不一致返回 409。
     */
    private void verifyUnchanged(HandoverSummary frozen, Map<String, Incident> lockedByKey) {
        for (HandoverIncidentSummary frozenIncident : frozen.incidents()) {
            Incident current = lockedByKey.get(frozenIncident.incidentKey());
            if (current == null) {
                throw ApiException.conflict("闭包事件丢失: " + frozenIncident.incidentKey());
            }
            if (current.status() == IncidentStatus.RESOLVED
                    || current.status() == IncidentStatus.CLOSED) {
                throw ApiException.conflict(
                        "事件 " + current.incidentKey() + " 已进入终态 " + current.status()
                                + "，不能接受联合交接");
            }
            if (!safe(current.commander()).equals(safe(frozenIncident.commander()))
                    || !current.status().name().equals(frozenIncident.status())
                    || !current.updatedAt().equals(frozenIncident.version())) {
                throw ApiException.conflict(
                        "事件 " + current.incidentKey() + " 在冻结后已变化，版本不一致");
            }
            Map<String, IncidentTask> currentTasks = tasks.listOpenByIncident(current.id()).stream()
                    .collect(Collectors.toMap(IncidentTask::taskKey, t -> t, (a, b) -> a,
                            LinkedHashMap::new));
            Set<String> frozenTaskKeys = frozenIncident.openTasks().stream()
                    .map(HandoverTaskSummary::taskKey).collect(Collectors.toCollection(HashSet::new));
            if (!frozenTaskKeys.equals(currentTasks.keySet())) {
                throw ApiException.conflict(
                        "事件 " + current.incidentKey() + " 的 OPEN 任务集合在冻结后已变化");
            }
            for (HandoverTaskSummary frozenTask : frozenIncident.openTasks()) {
                IncidentTask currentTask = currentTasks.get(frozenTask.taskKey());
                List<String> currentBlockers = incidents.listBlockingIncidents(currentTask.id())
                        .stream().map(Incident::incidentKey).sorted().toList();
                if (!currentTask.status().name().equals(frozenTask.status())
                        || !currentTask.updatedAt().equals(frozenTask.version())
                        || !currentBlockers.equals(frozenTask.blockerKeys())) {
                    throw ApiException.conflict("事件 " + current.incidentKey() + " 的任务 "
                            + frozenTask.taskKey() + " 或其依赖在冻结后已变化");
                }
            }
            Instant currentEscalationVersion = escalations.findByIncident(current.id())
                    .filter(e -> e.status() == EscalationStatus.OPEN)
                    .map(Escalation::updatedAt).orElse(null);
            if (!safe(currentEscalationVersion).equals(
                    safe(frozenIncident.unacknowledgedEscalationVersion()))) {
                throw ApiException.conflict(
                        "事件 " + current.incidentKey() + " 的未确认升级在冻结后已变化");
            }
        }
    }

    private void saveSnapshot(long handoverId, List<String> orderedKeys,
                              Map<String, Incident> lockedByKey) {
        java.util.concurrent.atomic.AtomicInteger incidentOrdinal =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger taskOrdinal =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger escalationOrdinal =
                new java.util.concurrent.atomic.AtomicInteger();
        for (String key : orderedKeys) {
            Incident incident = lockedByKey.get(key);
            handovers.insertSnapshotIncident(new HandoverSnapshotIncident(0L, handoverId,
                    incident.id(), incident.incidentKey(), incident.commander(),
                    incident.status(), incident.updatedAt(), incidentOrdinal.getAndIncrement()));
            List<IncidentTask> openTasks = tasks.listOpenByIncident(incident.id()).stream()
                    .sorted(Comparator.comparing(IncidentTask::taskKey)).toList();
            for (IncidentTask task : openTasks) {
                List<String> blockers = incidents.listBlockingIncidents(task.id()).stream()
                        .map(Incident::incidentKey).sorted().toList();
                handovers.insertSnapshotTask(new HandoverSnapshotTask(0L, handoverId,
                        incident.id(), task.id(), task.taskKey(), task.status(), task.updatedAt(),
                        writeJson(blockers), taskOrdinal.getAndIncrement()));
            }
            escalations.findByIncident(incident.id())
                    .filter(e -> e.status() == EscalationStatus.OPEN)
                    .ifPresent(e -> handovers.insertSnapshotEscalation(new HandoverSnapshotEscalation(
                            0L, handoverId, incident.id(), e.id(), e.updatedAt(),
                            escalationOrdinal.getAndIncrement())));
        }
    }

    /** 查询交接单当前视图（含冻结摘要与闭包），只读不写。 */
    @Transactional(readOnly = true)
    public HandoverView get(String handoverKey) {
        JointHandover handover = handovers.findByKey(handoverKey)
                .orElseThrow(() -> ApiException.notFound("交接单不存在: " + handoverKey));
        return toView(handover);
    }

    /** 查询接受成功后保存的不可变闭包快照；PENDING 交接单尚无快照返回 409。 */
    @Transactional(readOnly = true)
    public HandoverSnapshotView snapshot(String handoverKey) {
        JointHandover handover = handovers.findByKey(handoverKey)
                .orElseThrow(() -> ApiException.notFound("交接单不存在: " + handoverKey));
        if (handover.status() != HandoverStatus.ACCEPTED) {
            throw ApiException.conflict("交接单尚未接受，没有不可变快照: " + handoverKey);
        }
        List<HandoverSnapshotIncident> snapshotIncidents = handovers.listSnapshotIncidents(handover.id());
        Map<Long, String> incidentKeyById = snapshotIncidents.stream()
                .collect(Collectors.toMap(HandoverSnapshotIncident::incidentId,
                        HandoverSnapshotIncident::incidentKey, (a, b) -> a));
        List<SnapshotIncidentView> incidentViews = snapshotIncidents.stream()
                .map(s -> new SnapshotIncidentView(s.incidentKey(), s.commander(),
                        s.status().name(), s.versionAt())).toList();
        List<SnapshotTaskView> taskViews = handovers.listSnapshotTasks(handover.id()).stream()
                .map(s -> new SnapshotTaskView(incidentKeyById.get(s.incidentId()), s.taskKey(),
                        s.status().name(), s.versionAt(), readStringList(s.blockerKeys()))).toList();
        List<SnapshotEscalationView> escalationViews = handovers.listSnapshotEscalations(handover.id())
                .stream().map(s -> new SnapshotEscalationView(
                        incidentKeyById.get(s.incidentId()), s.escalationId(), s.versionAt()))
                .toList();
        return new HandoverSnapshotView(handover.handoverKey(), handover.fromCommander(),
                handover.toCommander(), handover.acceptedAt(), incidentViews, taskViews,
                escalationViews);
    }

    /**
     * 查询涉及某事件的全部联合交接历史（含 PENDING 与 ACCEPTED），按发起顺序。只读不写。
     */
    @Transactional(readOnly = true)
    public List<HandoverView> listByIncident(String incidentKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        return handovers.listByIncident(incident.id()).stream().map(this::toView).toList();
    }

    private HandoverView toView(JointHandover handover) {
        HandoverSummary summary = readValue(handover.frozenSummary(), HandoverSummary.class);
        return new HandoverView(handover.handoverKey(), handover.fromCommander(),
                handover.toCommander(), handover.status().name(), handover.handoverVersion(),
                readStringList(handover.closureKeys()), summary, handover.createdAt(),
                handover.acceptedAt());
    }

    /**
     * 规范化摘要：闭包事件键、事件条目、任务条目、阻塞事件键全部排序，
     * 保证集合换序同参，规范化结果用于版本比对与幂等参数摘要。
     */
    private static HandoverSummary normalize(HandoverSummary summary) {
        List<String> closureKeys = summary.closureIncidentKeys() == null
                ? List.of() : summary.closureIncidentKeys().stream().sorted().toList();
        List<HandoverIncidentSummary> incidents = summary.incidents() == null
                ? List.of()
                : summary.incidents().stream()
                        .sorted(Comparator.comparing(HandoverIncidentSummary::incidentKey))
                        .map(i -> {
                            List<HandoverTaskSummary> tasks = i.openTasks() == null
                                    ? List.of()
                                    : i.openTasks().stream()
                                            .sorted(Comparator.comparing(HandoverTaskSummary::taskKey))
                                            .map(t -> new HandoverTaskSummary(t.taskKey(),
                                                    t.status(), t.version(),
                                                    t.blockerKeys() == null ? List.of()
                                                            : t.blockerKeys().stream().sorted()
                                                                    .toList()))
                                            .toList();
                            return new HandoverIncidentSummary(i.incidentKey(), i.commander(),
                                    i.status(), i.version(), tasks,
                                    i.unacknowledgedEscalationVersion());
                        }).toList();
        return new HandoverSummary(summary.fromCommander(), summary.toCommander(), closureKeys,
                incidents);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String safe(Instant value) {
        return value == null ? "" : value.toString();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(field + " 不能为空");
        }
        return value.strip();
    }

    private String canonicalJson(Object value) {
        try {
            return objectMapper.writeValueAsString(objectMapper.convertValue(value, Object.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("冻结摘要规范化失败", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("冻结摘要序列化失败", e);
        }
    }

    private <T> T readValue(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("冻结摘要反序列化失败", e);
        }
    }

    private List<String> readStringList(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("闭包键反序列化失败", e);
        }
    }
}
