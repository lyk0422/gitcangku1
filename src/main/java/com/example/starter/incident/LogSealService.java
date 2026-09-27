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
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import com.example.starter.incident.IncidentTaskRepository.TimedEdge;
import com.example.starter.incident.dto.Requests.CorrectionRequest;
import com.example.starter.incident.dto.Requests.SealRequest;
import com.example.starter.incident.dto.Responses.CorrectionView;
import com.example.starter.incident.dto.Responses.SealDiagnosticsView;
import com.example.starter.incident.dto.Responses.SealItemView;
import com.example.starter.incident.dto.Responses.SealListView;
import com.example.starter.incident.dto.Responses.SealSummaryView;
import com.example.starter.incident.dto.Responses.SealView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 事件指挥日志封存服务。
 *
 * <p>封存：对未关闭事件，按给定截至 UTC 时刻（区间左闭右开）固化该时刻前的状态流转、
 * 处置记录、交接、任务状态与任务依赖边，生成事件内版本递增的不可变日志包。条目按记录
 * 发生时刻升序、同刻按记录标识升序排列，并固化每条记录的修订版本（交接/任务被接受或终态
 * 化为版本 2，其余为 1）与依赖边。封存先锁定事件行，先完成整批校验（截至时刻边界、同刻
 * 写入冲突、包内引用归属与有环）再一次事务写入包与条目，任一失败不留半成品。</p>
 *
 * <p>并发裁决：封存与指令写入均先抢事件行锁，按事务提交顺序裁决——写入先提交则落入后续
 * 封存边界，封存先提交则仅影响本次快照边界。commandKey 沿用全局幂等规则：同键同参重放
 * 首次响应，异参 409，失败不占键。</p>
 *
 * <p>更正：已封存记录的事实错误只能追加更正附录，引用原包版本与原记录标识，保留原值、
 * 新值、理由与操作人；原包任何内容都不更新。已关闭事件允许查询与追加更正附录，
 * 但不允许新增完整封存包。</p>
 */
@Service
public class LogSealService {

    private static final String SEP = "\u001F";

    /** 包内记录类型，同时用于同刻排序后的标识与更正附录定位。 */
    public static final String TYPE_ACTION = "ACTION";
    public static final String TYPE_TRANSFER = "TRANSFER";
    public static final String TYPE_STATUS = "STATUS";
    public static final String TYPE_TASK = "TASK";
    public static final String TYPE_DEPENDENCY = "DEPENDENCY";

    private static final Set<String> RECORD_TYPES = Set.of(
            TYPE_ACTION, TYPE_TRANSFER, TYPE_STATUS, TYPE_TASK, TYPE_DEPENDENCY);

    private final IncidentRepository incidents;
    private final IncidentTaskRepository tasks;
    private final LogSealRepository seals;
    private final CommandKeyRepository commandKeys;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public LogSealService(IncidentRepository incidents, IncidentTaskRepository tasks,
                          LogSealRepository seals, CommandKeyRepository commandKeys,
                          ObjectMapper objectMapper, Clock clock) {
        this.incidents = incidents;
        this.tasks = tasks;
        this.seals = seals;
        this.commandKeys = commandKeys;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    private Instant now() {
        return clock.instant();
    }

    /**
     * 封存日志包：见类注释。成功返回版本递增、条目已排序固化的包视图。
     */
    @Transactional
    public SealView seal(String incidentKey, String actor, SealRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        if (req.cutoffAt() == null) {
            throw ApiException.badRequest("cutoffAt 不能为空");
        }
        Instant cutoff = req.cutoffAt().truncatedTo(ChronoUnit.MICROS);
        Incident incident = lockIncident(incidentKey);
        return runIdempotent(commandKey, "log_seal",
                hash(incidentKey, actor, cutoff.toString()), SealView.class, () -> {
                    requireCommander(incident, actor);
                    if (incident.status() == IncidentStatus.CLOSED) {
                        throw ApiException.illegalTransition("事件已关闭，不能新增封存包，"
                                + "仅允许查询与追加更正附录");
                    }
                    validateCutoff(incident, cutoff);
                    assertNoBoundaryWrites(incident.id(), cutoff);

                    List<LogSealItem> items = buildItems(incident, cutoff);
                    validatePackageGraph(incident, items);
                    items.sort(itemOrdering());
                    List<LogSealItem> sequenced = new ArrayList<>();
                    for (int i = 0; i < items.size(); i++) {
                        LogSealItem it = items.get(i);
                        sequenced.add(new LogSealItem(0L, 0L, i + 1, it.recordType(), it.recordKey(),
                                it.occurredAt(), it.recordVersion(), it.fromTaskKey(),
                                it.toIncidentKey(), it.payload()));
                    }

                    int version = seals.maxVersion(incident.id()) + 1;
                    Instant sealedAt = now();
                    String contentHash = hashItems(sequenced);
                    LogSeal seal = new LogSeal(0L, incident.id(), version, cutoff, sealedAt, actor,
                            sequenced.size(), contentHash);
                    long sealId;
                    try {
                        sealId = seals.insertSeal(seal);
                    } catch (DuplicateKeyException e) {
                        throw ApiException.conflict("SEAL_VERSION_CONFLICT",
                                "封存包版本并发冲突，请重试", null);
                    }
                    for (LogSealItem item : sequenced) {
                        seals.insertItem(new LogSealItem(0L, sealId, item.seqNo(), item.recordType(),
                                item.recordKey(), item.occurredAt(), item.recordVersion(),
                                item.fromTaskKey(), item.toIncidentKey(), item.payload()));
                    }
                    return toSealView(new LogSeal(sealId, incident.id(), version, cutoff, sealedAt,
                            actor, sequenced.size(), contentHash), sequenced, List.of());
                });
    }

    /**
     * 追加更正附录：原包与原记录必须存在，新值与理由非空；原值取自原包快照
     * （字段为 null 时保留 null 语义）。原包不做任何更新；已关闭事件也允许追加。
     */
    @Transactional
    public CorrectionView appendCorrection(String incidentKey, String actor, CorrectionRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        int packageVersion = req.packageVersion() == null
                ? 0 : req.packageVersion();
        if (packageVersion <= 0) {
            throw ApiException.badRequest("packageVersion 必须为正整数");
        }
        String recordType = requireText(req.recordType(), "recordType");
        if (!RECORD_TYPES.contains(recordType)) {
            throw ApiException.badRequest("recordType 必须为 ACTION/TRANSFER/STATUS/TASK/DEPENDENCY");
        }
        String recordKey = requireText(req.recordKey(), "recordKey");
        String fieldName = requireText(req.fieldName(), "fieldName");
        String newValue = requireText(req.newValue(), "newValue");
        String reason = requireText(req.reason(), "reason");
        Incident incident = lockIncident(incidentKey);
        return runIdempotent(commandKey, "log_correction",
                hash(incidentKey, actor, String.valueOf(packageVersion), recordType, recordKey,
                        fieldName, newValue, reason),
                CorrectionView.class, () -> {
                    // 未关闭事件仅当前指挥人可追加更正；已关闭事件允许任意操作人追加留痕
                    if (incident.status() != IncidentStatus.CLOSED) {
                        requireCommander(incident, actor);
                    }
                    LogSeal seal = seals.findSeal(incident.id(), packageVersion)
                            .orElseThrow(() -> ApiException.notFound("封存包不存在: 事件 "
                                    + incidentKey + " 版本 " + packageVersion));
                    LogSealItem item = seals.findItem(seal.id(), recordType, recordKey)
                            .orElseThrow(() -> ApiException.unprocessable("CORRECTION_TARGET_NOT_FOUND",
                                    "原包版本 " + packageVersion + " 中不存在记录 " + recordType
                                            + ":" + recordKey, null));
                    JsonNode payload = readJson(item.payload());
                    if (!payload.has(fieldName)) {
                        throw ApiException.unprocessable("CORRECTION_FIELD_NOT_FOUND",
                                "原记录快照中不存在字段: " + fieldName, null);
                    }
                    JsonNode oldNode = payload.get(fieldName);
                    String oldValue = oldNode == null || oldNode.isNull() ? null : oldNodeAsText(oldNode);
                    LogCorrection correction = new LogCorrection(0L, incident.id(), packageVersion,
                            recordType, recordKey, fieldName, oldValue, newValue, reason, actor, now());
                    long id = seals.insertCorrection(correction);
                    return toCorrectionView(new LogCorrection(id, incident.id(), packageVersion,
                            recordType, recordKey, fieldName, oldValue, newValue, reason, actor,
                            correction.createdAt()));
                });
    }

    /**
     * 查询事件封存包清单（仅元数据，按版本升序）。只读。
     */
    @Transactional(readOnly = true)
    public SealListView listSeals(String incidentKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        List<SealSummaryView> views = seals.listSeals(incident.id()).stream()
                .map(s -> new SealSummaryView(s.version(), s.cutoffAt(), s.sealedAt(), s.createdBy(),
                        s.recordCount(), s.contentHash()))
                .toList();
        return new SealListView(incidentKey, views.size(), views);
    }

    /**
     * 查询单个封存包明细（按 seqNo 升序的条目与该包全部更正附录）。只读。
     */
    @Transactional(readOnly = true)
    public SealView getSeal(String incidentKey, int version) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        LogSeal seal = seals.findSeal(incident.id(), version)
                .orElseThrow(() -> ApiException.notFound("封存包不存在: 事件 " + incidentKey
                        + " 版本 " + version));
        List<LogSealItem> items = seals.listItems(seal.id());
        List<CorrectionView> corrections = seals.listCorrectionsByPackage(incident.id(), version)
                .stream().map(LogSealService::toCorrectionView).toList();
        return toSealView(seal, items, corrections);
    }

    /**
     * 查询事件全部更正附录（按追加顺序）。只读。
     */
    @Transactional(readOnly = true)
    public List<CorrectionView> listCorrections(String incidentKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        return seals.listCorrections(incident.id()).stream()
                .map(LogSealService::toCorrectionView).toList();
    }

    /**
     * 包诊断：重算内容摘要、核对条目数量与排序一致性、依赖边解析情况。只读，不修改包。
     */
    @Transactional(readOnly = true)
    public SealDiagnosticsView diagnostics(String incidentKey, int version) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        LogSeal seal = seals.findSeal(incident.id(), version)
                .orElseThrow(() -> ApiException.notFound("封存包不存在: 事件 " + incidentKey
                        + " 版本 " + version));
        List<LogSealItem> items = seals.listItems(seal.id());

        boolean orderingConsistent = true;
        for (int i = 1; i < items.size(); i++) {
            LogSealItem prev = items.get(i - 1);
            LogSealItem cur = items.get(i);
            int cmp = itemOrdering().compare(prev, cur);
            if (cmp >= 0) {
                orderingConsistent = false;
                break;
            }
        }
        boolean seqConsistent = true;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).seqNo() != i + 1) {
                seqConsistent = false;
                break;
            }
        }
        String recomputedHash = hashItems(items);
        boolean hashMatch = recomputedHash.equals(seal.contentHash());
        boolean countMatch = items.size() == seal.recordCount();

        List<LogSealItem> edges = items.stream()
                .filter(i -> TYPE_DEPENDENCY.equals(i.recordType())).toList();
        List<String> dangling = new ArrayList<>();
        for (LogSealItem edge : edges) {
            if (edge.toIncidentKey() == null
                    || incidents.findByKey(edge.toIncidentKey()).isEmpty()) {
                dangling.add(edge.recordKey());
            }
        }
        int correctionsCount = seals.listCorrectionsByPackage(incident.id(), version).size();
        boolean healthy = orderingConsistent && seqConsistent && hashMatch && countMatch
                && dangling.isEmpty();
        String message = healthy ? "封存包内容完整且与固化摘要一致" : "封存包诊断发现不一致，详见各标志位";
        return new SealDiagnosticsView(version, seal.cutoffAt(), items.size(), seal.recordCount(),
                countMatch, orderingConsistent && seqConsistent, seal.contentHash(),
                recomputedHash, hashMatch, edges.size(), List.copyOf(dangling),
                correctionsCount, healthy, message);
    }

    /**
     * 包内排序：先按记录发生 UTC 时刻升序；同刻按记录类型、记录标识升序。
     * 类型参与排序可避免用户自定义 actionKey 与 "transfer:*" 等标识碰撞导致顺序不稳定。
     */
    private static Comparator<LogSealItem> itemOrdering() {
        return Comparator.comparing(LogSealItem::occurredAt)
                .thenComparing(LogSealItem::recordType)
                .thenComparing(LogSealItem::recordKey);
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /**
     * 截至时刻校验：不得早于事件创建（含边界），不得晚于注入时钟当前时刻。
     * 违规返回 422，明细给出实际值、要求边界与差额秒数。
     */
    private void validateCutoff(Incident incident, Instant cutoff) {
        Instant created = incident.createdAt().truncatedTo(ChronoUnit.MICROS);
        if (cutoff.isBefore(created)) {
            long delta = Duration.between(cutoff, created).toSeconds();
            throw ApiException.unprocessable("SEAL_CUTOFF_TOO_EARLY",
                    "截至时刻 " + cutoff + " 早于事件创建时刻 " + created
                            + "，差额 " + delta + " 秒（要求 cutoffAt >= createdAt）",
                    Map.of("actual", cutoff.toString(), "requiredMin", created.toString(),
                            "shortfallSeconds", delta));
        }
        Instant current = now().truncatedTo(ChronoUnit.MICROS);
        if (cutoff.isAfter(current)) {
            long delta = Duration.between(current, cutoff).toSeconds();
            throw ApiException.unprocessable("SEAL_CUTOFF_IN_FUTURE",
                    "截至时刻 " + cutoff + " 晚于当前时刻 " + current
                            + "，差额 " + delta + " 秒（要求 cutoffAt <= now）",
                    Map.of("actual", cutoff.toString(), "requiredMax", current.toString(),
                            "excessSeconds", delta));
        }
    }

    /**
     * 同刻写入冲突：封存请求可能与某条指令写入并发，按提交顺序拿到锁后，若该写入已提交且
     * 其记录时刻恰好等于截至时刻，左闭右开区间无法确定其归属，返回 409 要求先完成该写入
     * （由后续封存包含）或选择其他截至时刻。事件创建行（from_status 为空）不是指令写入，
     * 不参与冲突判定。
     */
    private void assertNoBoundaryWrites(long incidentId, Instant cutoff) {
        Map<String, Map<String, Object>> offenders = new LinkedHashMap<>();
        for (IncidentAction action : incidents.listActions(incidentId)) {
            if (action.occurredAt().equals(cutoff)) {
                offenders.put(TYPE_ACTION + ":" + action.actionKey(),
                        Map.of("recordType", TYPE_ACTION, "recordKey", action.actionKey(),
                                "occurredAt", cutoff.toString()));
            }
        }
        for (IncidentTransfer transfer : incidents.listTransfers(incidentId)) {
            if (transfer.createdAt().equals(cutoff)
                    || (transfer.acceptedAt() != null && transfer.acceptedAt().equals(cutoff))) {
                offenders.put(TYPE_TRANSFER + ":" + transfer.id(),
                        Map.of("recordType", TYPE_TRANSFER,
                                "recordKey", "transfer:" + transfer.id(),
                                "occurredAt", cutoff.toString()));
            }
        }
        for (StatusChange change : incidents.listStatusHistory(incidentId)) {
            if (change.fromStatus() != null && change.occurredAt().equals(cutoff)) {
                offenders.put(TYPE_STATUS + ":" + change.id(),
                        Map.of("recordType", TYPE_STATUS,
                                "recordKey", "status:" + change.id(), "occurredAt", cutoff.toString()));
            }
        }
        for (IncidentTask task : tasks.listByIncident(incidentId)) {
            Instant boundary = task.createdAt().equals(cutoff) ? task.createdAt()
                    : task.doneAt() != null && task.doneAt().equals(cutoff) ? task.doneAt()
                    : task.cancelledAt() != null && task.cancelledAt().equals(cutoff)
                            ? task.cancelledAt() : null;
            if (boundary != null) {
                offenders.put(TYPE_TASK + ":" + task.taskKey(),
                        Map.of("recordType", TYPE_TASK,
                                "recordKey", "task:" + task.taskKey(),
                                "occurredAt", cutoff.toString()));
            }
        }
        for (TimedEdge edge : tasks.listAllEdges()) {
            if (edge.fromIncidentId() == incidentId && edge.createdAt().equals(cutoff)) {
                String blockerKey = edge.blockerIncidentKey() == null
                        ? String.valueOf(edge.blockerIncidentId()) : edge.blockerIncidentKey();
                String edgeKey = "dep:" + edge.taskKey() + ":" + blockerKey;
                offenders.put(TYPE_DEPENDENCY + ":" + edgeKey,
                        Map.of("recordType", TYPE_DEPENDENCY, "recordKey", edgeKey,
                                "occurredAt", cutoff.toString()));
            }
        }
        if (!offenders.isEmpty()) {
            List<Map<String, Object>> details = List.copyOf(offenders.values());
            throw ApiException.conflict("SEAL_BOUNDARY_WRITE",
                    "截至时刻 " + cutoff + " 存在同刻指令写入（实际 " + details.size()
                            + " 条），请先完成该写入后改选后续封存，或选择其他截至时刻",
                    details);
        }
    }

    /**
     * 组装截至时刻前的全部条目（未排序、无 seqNo）：
     * 状态流转与处置记录按追加行逐条固化；交接单/任务固化截至时刻前的最新修订；
     * 依赖边按边创建时刻固化。
     */
    private List<LogSealItem> buildItems(Incident incident, Instant cutoff) {
        long incidentId = incident.id();
        List<LogSealItem> items = new ArrayList<>();

        for (StatusChange change : incidents.listStatusHistory(incidentId)) {
            if (!change.occurredAt().isBefore(cutoff)) {
                continue;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("statusChangeId", change.id());
            payload.put("fromStatus", change.fromStatus() == null ? null : change.fromStatus().name());
            payload.put("toStatus", change.toStatus().name());
            payload.put("actor", change.actor());
            payload.put("occurredAt", change.occurredAt());
            items.add(new LogSealItem(0L, 0L, 0, TYPE_STATUS, "status:" + change.id(),
                    change.occurredAt(), 1, null, null, toJson(payload)));
        }

        for (IncidentAction action : incidents.listActions(incidentId)) {
            if (!action.occurredAt().isBefore(cutoff)) {
                continue;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("actionKey", action.actionKey());
            payload.put("actionType", action.actionType());
            payload.put("note", action.note());
            payload.put("occurredAt", action.occurredAt());
            payload.put("actor", action.actor());
            payload.put("createdAt", action.createdAt());
            items.add(new LogSealItem(0L, 0L, 0, TYPE_ACTION, action.actionKey(),
                    action.occurredAt(), 1, null, null, toJson(payload)));
        }

        for (IncidentTransfer transfer : incidents.listTransfers(incidentId)) {
            boolean accepted = transfer.acceptedAt() != null
                    && transfer.acceptedAt().isBefore(cutoff);
            Instant occurredAt = accepted ? transfer.acceptedAt() : transfer.createdAt();
            if (!occurredAt.isBefore(cutoff)) {
                continue;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("transferId", transfer.id());
            payload.put("fromCommander", transfer.fromCommander());
            payload.put("toCommander", transfer.toCommander());
            payload.put("status", accepted ? TransferStatus.ACCEPTED.name()
                    : TransferStatus.PENDING.name());
            payload.put("createdAt", transfer.createdAt());
            payload.put("acceptedAt", accepted ? transfer.acceptedAt() : null);
            items.add(new LogSealItem(0L, 0L, 0, TYPE_TRANSFER, "transfer:" + transfer.id(),
                    occurredAt, accepted ? 2 : 1, null, null, toJson(payload)));
        }

        List<TimedEdge> incidentEdges = tasks.listAllEdges().stream()
                .filter(e -> e.fromIncidentId() == incidentId).toList();
        Map<String, List<String>> blockersByTask = new HashMap<>();
        for (TimedEdge edge : incidentEdges) {
            if (!edge.createdAt().isBefore(cutoff)) {
                continue;
            }
            blockersByTask.computeIfAbsent(edge.taskKey(), k -> new ArrayList<>())
                    .add(edge.blockerIncidentKey() == null
                            ? String.valueOf(edge.blockerIncidentId()) : edge.blockerIncidentKey());
        }

        for (IncidentTask task : tasks.listByIncident(incidentId)) {
            boolean terminal = false;
            Instant occurredAt = task.createdAt();
            String status = TaskStatus.OPEN.name();
            int version = 1;
            if (task.doneAt() != null && task.doneAt().isBefore(cutoff)) {
                terminal = true;
                occurredAt = task.doneAt();
                status = TaskStatus.DONE.name();
                version = 2;
            } else if (task.cancelledAt() != null && task.cancelledAt().isBefore(cutoff)) {
                terminal = true;
                occurredAt = task.cancelledAt();
                status = TaskStatus.CANCELLED.name();
                version = 2;
            }
            if (!occurredAt.isBefore(cutoff)) {
                continue;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("taskKey", task.taskKey());
            payload.put("groupCode", task.groupCode());
            payload.put("title", task.title());
            payload.put("status", status);
            payload.put("createdBy", task.createdBy());
            payload.put("createdAt", task.createdAt());
            payload.put("doneBy", terminal && task.doneAt() != null ? task.doneBy() : null);
            payload.put("doneAt", terminal && task.doneAt() != null ? task.doneAt() : null);
            payload.put("cancelledBy", terminal && task.cancelledAt() != null ? task.cancelledBy() : null);
            payload.put("cancelledAt", terminal && task.cancelledAt() != null ? task.cancelledAt() : null);
            payload.put("blockerIncidentKeys",
                    blockersByTask.getOrDefault(task.taskKey(), List.of()).stream()
                            .sorted().toList());
            items.add(new LogSealItem(0L, 0L, 0, TYPE_TASK, "task:" + task.taskKey(),
                    occurredAt, version, null, null, toJson(payload)));
        }

        for (TimedEdge edge : incidentEdges) {
            if (!edge.createdAt().isBefore(cutoff)) {
                continue;
            }
            String blockerKey = edge.blockerIncidentKey() == null
                    ? String.valueOf(edge.blockerIncidentId()) : edge.blockerIncidentKey();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("taskKey", edge.taskKey());
            payload.put("blockerIncidentKey", edge.blockerIncidentKey() == null ? null : blockerKey);
            payload.put("blockerIncidentId", edge.blockerIncidentId());
            payload.put("createdAt", edge.createdAt());
            items.add(new LogSealItem(0L, 0L, 0, TYPE_DEPENDENCY,
                    "dep:" + edge.taskKey() + ":" + blockerKey, edge.createdAt(), 1,
                    edge.taskKey(), edge.blockerIncidentKey() == null ? null : blockerKey,
                    toJson(payload)));
        }
        return items;
    }

    /**
     * 包内依赖图校验：每条 DEPENDENCY 边的起点任务必须是包内 TASK 条目（属于本事件且在截至
     * 时刻前已创建）；边引用的阻塞事件必须存在；包内全部有向边不得成环（含自环）。
     * 违规 422，且在校验阶段抛出，不产生部分包。
     */
    private void validatePackageGraph(Incident incident, List<LogSealItem> items) {
        Set<String> packagedTaskKeys = items.stream()
                .filter(i -> TYPE_TASK.equals(i.recordType()))
                .map(LogSealItem::recordKey)
                .collect(java.util.stream.Collectors.toSet());
        List<LogSealItem> edges = items.stream()
                .filter(i -> TYPE_DEPENDENCY.equals(i.recordType())).toList();
        if (edges.isEmpty()) {
            return;
        }
        List<String> foreignTasks = new ArrayList<>();
        List<String> dangling = new ArrayList<>();
        Map<Long, List<Long>> adjacency = new HashMap<>();
        for (LogSealItem edge : edges) {
            if (!packagedTaskKeys.contains("task:" + edge.fromTaskKey())) {
                foreignTasks.add(edge.recordKey());
            }
            JsonNode payload = readJson(edge.payload());
            long blockerId = payload.path("blockerIncidentId").asLong();
            if (edge.toIncidentKey() == null || incidents.findByKey(edge.toIncidentKey()).isEmpty()) {
                dangling.add(edge.recordKey());
            }
            adjacency.computeIfAbsent(incident.id(), k -> new ArrayList<>()).add(blockerId);
        }
        if (!foreignTasks.isEmpty()) {
            throw ApiException.unprocessable("SEAL_FOREIGN_TASK_REFERENCE",
                    "封存包内依赖边引用了不属于本事件的任务（实际 " + foreignTasks.size() + " 条）",
                    List.copyOf(foreignTasks));
        }
        if (!dangling.isEmpty()) {
            throw ApiException.unprocessable("SEAL_DANGLING_REFERENCE",
                    "封存包内依赖图存在无法解析到事件的悬空引用（实际 " + dangling.size() + " 条）",
                    List.copyOf(dangling));
        }
        List<Long> cycle = findCycle(incident.id(), adjacency);
        if (cycle != null) {
            throw ApiException.unprocessable("SEAL_GRAPH_CYCLE",
                    "封存包内任务依赖图存在环，事件链路: " + cycle,
                    cycle.stream().map(String::valueOf).toList());
        }
    }

    /**
     * 从起点出发做 DFS 环检测，发现回边时返回成环的事件 id 链路，无环返回 null。
     */
    private static List<Long> findCycle(long root, Map<Long, List<Long>> adjacency) {
        Set<Long> visited = new java.util.HashSet<>();
        List<Long> path = new ArrayList<>();
        if (dfs(root, adjacency, visited, path)) {
            return List.copyOf(path);
        }
        return null;
    }

    private static boolean dfs(Long node, Map<Long, List<Long>> adjacency,
                               Set<Long> visited, List<Long> path) {
        if (path.contains(node)) {
            path.add(node);
            return true;
        }
        if (!visited.add(node)) {
            return false;
        }
        path.add(node);
        for (Long next : adjacency.getOrDefault(node, List.of())) {
            if (dfs(next, adjacency, visited, path)) {
                return true;
            }
        }
        path.remove(path.size() - 1);
        return false;
    }

    private Incident lockIncident(String incidentKey) {
        requireText(incidentKey, "incidentKey");
        return incidents.lockByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
    }

    private static void requireCommander(Incident incident, String actor) {
        if (incident.commander() == null || !incident.commander().equals(actor)) {
            throw ApiException.conflict("只有当前指挥人 "
                    + (incident.commander() == null ? "(无)" : incident.commander()) + " 能封存日志");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(field + " 不能为空");
        }
        return value.strip();
    }

    /**
     * 幂等执行：与事件指挥写路径同一约定——同键同参重放首次响应，异参 409，失败不占键。
     */
    private <T> T runIdempotent(String commandKey, String operation, String requestHash,
                                Class<T> type, Supplier<T> business) {
        Optional<CommandKeyRecord> existing = commandKeys.find(commandKey);
        if (existing.isPresent()) {
            return replay(existing.get(), operation, requestHash, type);
        }
        try {
            commandKeys.insertPlaceholder(commandKey, operation, requestHash, now());
        } catch (DuplicateKeyException e) {
            CommandKeyRecord committed = commandKeys.findForUpdate(commandKey)
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

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("封存快照解析失败", e);
        }
    }

    private static String oldNodeAsText(JsonNode node) {
        return node.isTextual() ? node.asText() : node.toString();
    }

    /**
     * 计算包内容摘要：按 seqNo 顺序拼接每条记录的序号、类型、标识、时刻、版本、边端点与
     * 快照 JSON，取 SHA-256。任何后续业务改写都会使重算摘要不一致。
     */
    private String hashItems(List<LogSealItem> items) {
        StringBuilder canonical = new StringBuilder();
        for (LogSealItem item : items) {
            canonical.append(item.seqNo()).append(SEP)
                    .append(item.recordType()).append(SEP)
                    .append(item.recordKey()).append(SEP)
                    .append(item.occurredAt()).append(SEP)
                    .append(item.recordVersion()).append(SEP)
                    .append(item.fromTaskKey() == null ? "" : item.fromTaskKey()).append(SEP)
                    .append(item.toIncidentKey() == null ? "" : item.toIncidentKey()).append(SEP)
                    .append(item.payload()).append('\n');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
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

    private SealItemView toItemView(LogSealItem item) {
        return new SealItemView(item.seqNo(), item.recordType(), item.recordKey(), item.occurredAt(),
                item.recordVersion(), item.fromTaskKey(), item.toIncidentKey(), readJson(item.payload()));
    }

    private SealView toSealView(LogSeal seal, List<LogSealItem> items,
                                List<CorrectionView> corrections) {
        List<SealItemView> itemViews = items.stream().map(this::toItemView).toList();
        return new SealView(seal.version(), seal.cutoffAt(), seal.sealedAt(), seal.createdBy(),
                seal.recordCount(), seal.contentHash(), itemViews, corrections);
    }

    private static CorrectionView toCorrectionView(LogCorrection correction) {
        return new CorrectionView(correction.id(), correction.packageVersion(),
                correction.recordType(), correction.recordKey(), correction.fieldName(),
                correction.oldValue(), correction.newValue(), correction.reason(),
                correction.operator(), correction.createdAt());
    }
}
