package com.example.starter.incident;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.starter.incident.LogSealRepository.EdgeSnapshot;
import com.example.starter.incident.dto.Requests.SealCorrectionRequest;
import com.example.starter.incident.dto.Requests.SealLogRequest;
import com.example.starter.incident.dto.Responses.SealCorrectionView;
import com.example.starter.incident.dto.Responses.SealDetailView;
import com.example.starter.incident.dto.Responses.SealListView;
import com.example.starter.incident.dto.Responses.SealRecordView;
import com.example.starter.incident.dto.Responses.SealVerifyView;
import com.example.starter.incident.dto.Responses.SealView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 事件指挥日志封存服务。
 *
 * <p>封存规则：对未关闭事件，选取一个截至 UTC 时刻 cutoffAt（区间左闭右开，
 * 仅固化 occurredAt 严格小于 cutoffAt 的指令、交接、任务状态与任务依赖边），
 * 生成事件内版本递增的不可变日志包。包内条目按 (发生时刻, 记录标识) 升序，
 * 固化每条记录的版本序号与依赖边，内容 SHA-256 摘要随包存储；后续任务修订、
 * 撤销或事件合并不修改已提交包。
 *
 * <p>并发约定：封存与全部既有写接口一样先 SELECT ... FOR UPDATE 锁定事件行，
 * 同事务完成快照收集、校验、版本分配与写入，按事务提交顺序裁决：
 * 指令写入先提交则可能落在边界外（进入后续封存），封存先提交则仅决定该包快照边界。
 *
 * <p>失败处理：截止时刻早于事件创建或晚于注入时钟当前时刻、已关闭事件再封存、
 * 包内依赖图成环或引用了不属于该事件的任务，均 422 且不生成部分包；
 * 截止时刻恰好存在已提交同刻指令时 409。commandKey 幂等语义与既有写接口一致。
 */
@Service
public class LogSealService {

    private static final String TYPE_COMMAND = "COMMAND";
    private static final String TYPE_TRANSFER = "TRANSFER";
    private static final String TYPE_TASK_STATUS = "TASK_STATUS";
    private static final String TYPE_EDGE = "TASK_BLOCKER_EDGE";

    private final IncidentRepository incidents;
    private final IncidentTaskRepository tasks;
    private final LogSealRepository seals;
    private final IdempotentCommandRunner idempotent;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public LogSealService(IncidentRepository incidents, IncidentTaskRepository tasks,
                          LogSealRepository seals, IdempotentCommandRunner idempotent,
                          ObjectMapper objectMapper, Clock clock) {
        this.incidents = incidents;
        this.tasks = tasks;
        this.seals = seals;
        this.idempotent = idempotent;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** 待固化的包内条目（排序前的中间结构）。 */
    private record Entry(String recordType, String recordKey, Instant occurredAt, Long taskId,
                         Long blockerIncidentId, JsonNode payload) {
    }

    /**
     * 封存日志：锁事件行 → 幂等占位 → 截止时刻校验 → 同刻 409 → 快照收集 →
     * 图校验（无环、引用任务属于事件）→ 排序赋版本与序号 → 一次事务写入包与全部明细。
     */
    @Transactional
    public SealView seal(String incidentKey, String actor, SealLogRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        if (req.cutoffAt() == null) {
            throw ApiException.badRequest("cutoffAt 不能为空");
        }
        Instant cutoff = req.cutoffAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        Incident incident = lockIncident(incidentKey);
        return idempotent.run(commandKey, "log_seal",
                IdempotentCommandRunner.hash(incidentKey, actor, cutoff.toString()),
                SealView.class, () -> {
                    Instant now = clock.instant();
                    if (incident.status() == IncidentStatus.CLOSED) {
                        throw ApiException.unprocessable("SEAL_NOT_ALLOWED",
                                "事件已关闭，不能新增完整封存包，仅可查询或追加更正附录", null);
                    }
                    if (cutoff.isBefore(incident.createdAt())) {
                        throw ApiException.unprocessable("CUTOFF_OUT_OF_RANGE",
                                "截至时刻早于事件创建时刻", new RangeDetail(
                                        cutoff.toString(), incident.createdAt().toString(),
                                        java.time.Duration.between(cutoff, incident.createdAt()).toString(),
                                        null, null));
                    }
                    if (cutoff.isAfter(now)) {
                        throw ApiException.unprocessable("CUTOFF_OUT_OF_RANGE",
                                "截至时刻晚于当前时钟时刻", new RangeDetail(
                                        cutoff.toString(), incident.createdAt().toString(), null,
                                        now.toString(),
                                        java.time.Duration.between(now, cutoff).toString()));
                    }
                    List<String> sameInstantCommands = seals.findActionKeysAt(incident.id(), cutoff);
                    if (!sameInstantCommands.isEmpty()) {
                        throw ApiException.conflict(
                                "截至时刻存在尚未跨过边界的同刻指令，请先完成该写入或选择其他截至时刻",
                                new SameInstantDetail(cutoff.toString(), sameInstantCommands));
                    }

                    List<Entry> entries = collectEntries(incident, cutoff);
                    validateGraph(incident, entries);
                    entries.sort((a, b) -> {
                        int byTime = a.occurredAt.compareTo(b.occurredAt);
                        if (byTime != 0) {
                            return byTime;
                        }
                        return a.recordKey.compareTo(b.recordKey);
                    });
                    Map<String, Integer> versionByType = new HashMap<>();
                    List<LogSealRecord> rows = new ArrayList<>();
                    int edgeCount = 0;
                    for (int i = 0; i < entries.size(); i++) {
                        Entry e = entries.get(i);
                        int version = versionByType.merge(e.recordType, 1, Integer::sum);
                        if (TYPE_EDGE.equals(e.recordType)) {
                            edgeCount++;
                        }
                        rows.add(new LogSealRecord(0L, 0L, i + 1, e.recordType, e.recordKey,
                                e.occurredAt, version, e.taskId, e.blockerIncidentId,
                                e.payload.toString()));
                    }
                    int recordCount = rows.size() - edgeCount;
                    String contentHash = digest(canonical(rows));
                    int nextVersion = seals.countSeals(incident.id()) + 1;
                    Instant sealedAt = now;
                    long sealId = seals.insertSeal(new LogSeal(0L, incident.id(), nextVersion, cutoff,
                            recordCount, edgeCount, contentHash, actor, sealedAt));
                    for (LogSealRecord row : rows) {
                        seals.insertRecord(new LogSealRecord(0L, sealId, row.seq(), row.recordType(),
                                row.recordKey(), row.occurredAt(), row.recordVersion(), row.taskId(),
                                row.blockerIncidentId(), row.payload()));
                    }
                    return toSealView(seals.findSeal(incident.id(), nextVersion).orElseThrow(), rows);
                });
    }

    /**
     * 收集截至时刻前的全部条目，并把交接/任务状态还原为截至时刻的状态
     * （接受或完成/取消时刻不早于 cutoff 时，按 PENDING/OPEN 固化）。
     */
    private List<Entry> collectEntries(Incident incident, Instant cutoff) {
        List<Entry> entries = new ArrayList<>();
        for (IncidentAction action : seals.actionsBefore(incident.id(), cutoff)) {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("actionKey", action.actionKey());
            payload.put("actionType", action.actionType());
            payload.put("note", action.note());
            payload.put("occurredAt", action.occurredAt().toString());
            payload.put("actor", action.actor());
            payload.put("createdAt", action.createdAt().toString());
            entries.add(new Entry(TYPE_COMMAND, "action:" + action.actionKey(), action.occurredAt(),
                    null, null, payload));
        }
        for (IncidentTransfer transfer : seals.transfersBefore(incident.id(), cutoff)) {
            boolean acceptedByCutoff = transfer.acceptedAt() != null
                    && transfer.acceptedAt().isBefore(cutoff);
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("id", transfer.id());
            payload.put("fromCommander", transfer.fromCommander());
            payload.put("toCommander", transfer.toCommander());
            payload.put("status", acceptedByCutoff ? TransferStatus.ACCEPTED.name()
                    : TransferStatus.PENDING.name());
            payload.put("createdAt", transfer.createdAt().toString());
            payload.put("acceptedAt", acceptedByCutoff ? transfer.acceptedAt().toString() : null);
            entries.add(new Entry(TYPE_TRANSFER, "transfer:" + transfer.id(), transfer.createdAt(),
                    null, null, payload));
        }
        Map<Long, IncidentTask> taskById = new LinkedHashMap<>();
        for (IncidentTask task : seals.tasksBefore(incident.id(), cutoff)) {
            taskById.put(task.id(), task);
            boolean doneByCutoff = task.doneAt() != null && task.doneAt().isBefore(cutoff);
            boolean cancelledByCutoff = task.cancelledAt() != null
                    && task.cancelledAt().isBefore(cutoff);
            String statusAtCutoff = doneByCutoff ? TaskStatus.DONE.name()
                    : cancelledByCutoff ? TaskStatus.CANCELLED.name() : TaskStatus.OPEN.name();
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("taskKey", task.taskKey());
            payload.put("groupCode", task.groupCode());
            payload.put("title", task.title());
            payload.put("status", statusAtCutoff);
            payload.put("createdBy", task.createdBy());
            payload.put("createdAt", task.createdAt().toString());
            payload.put("doneBy", doneByCutoff ? task.doneBy() : null);
            payload.put("doneAt", doneByCutoff ? task.doneAt().toString() : null);
            payload.put("cancelledBy", cancelledByCutoff ? task.cancelledBy() : null);
            payload.put("cancelledAt", cancelledByCutoff ? task.cancelledAt().toString() : null);
            entries.add(new Entry(TYPE_TASK_STATUS, "task:" + task.taskKey(), task.createdAt(),
                    task.id(), null, payload));
        }
        for (EdgeSnapshot edge : seals.edgesBefore(incident.id(), cutoff)) {
            String blockerKey = seals.findIncidentKey(edge.blockerIncidentId()).orElse(null);
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("taskKey", edge.taskKey());
            payload.put("taskIncidentId", edge.taskIncidentId());
            payload.put("blockerIncidentId", edge.blockerIncidentId());
            payload.put("blockerIncidentKey", blockerKey);
            payload.put("createdAt", edge.edgeCreatedAt().toString());
            entries.add(new Entry(TYPE_EDGE,
                    "edge:" + edge.taskId() + ":" + edge.blockerIncidentId(), edge.edgeCreatedAt(),
                    edge.taskId(), edge.blockerIncidentId(), payload));
        }
        return entries;
    }

    /**
     * 图校验：包内每条依赖边的起点任务必须属于该事件；包内依赖图（本事件及其阻塞事件
     * 在全局阻塞图中的子图）必须无环。外部事件合并等越权改图引入的环或游离引用在此拒绝，
     * 422 且不留部分包；与本包引用节点无关的全局异常不阻断本次封存。
     */
    private void validateGraph(Incident incident, List<Entry> entries) {
        List<Entry> edgeEntries = entries.stream()
                .filter(e -> TYPE_EDGE.equals(e.recordType)).toList();
        Set<Long> referencedNodes = new HashSet<>();
        referencedNodes.add(incident.id());
        for (Entry edge : edgeEntries) {
            if (edge.taskId() == null
                    || !seals.taskBelongsToIncident(edge.taskId(), incident.id())) {
                throw ApiException.unprocessable("SEAL_FOREIGN_TASK",
                        "封存依赖图引用了不属于该事件的任务",
                        new ForeignTaskDetail(edge.recordKey(),
                                edge.taskId() == null ? null : edge.taskId().toString(),
                                incident.incidentKey()));
            }
            if (edge.blockerIncidentId() != null) {
                referencedNodes.add(edge.blockerIncidentId());
            }
        }
        // 以全局现有边构图：创建任务本已拒绝成环，此处对外部改图/合并做封存前防御性复核。
        List<IncidentTaskRepository.Edge> allEdges = tasks.listAllEdges();
        Map<Long, List<Long>> adjacency = new HashMap<>();
        for (IncidentTaskRepository.Edge edge : allEdges) {
            adjacency.computeIfAbsent(edge.fromIncidentId(), k -> new ArrayList<>())
                    .add(edge.toIncidentId());
        }
        List<Long> cycle = findCycle(adjacency);
        if (cycle != null && cycle.stream().anyMatch(referencedNodes::contains)) {
            throw ApiException.unprocessable("SEAL_GRAPH_CYCLE",
                    "封存依赖图存在环，不能封存", new CycleDetail(cycle));
        }
    }

    /**
     * 有向图环检测（DFS 三色），返回构成环的节点 id 序列，无环返回 null。
     */
    private static List<Long> findCycle(Map<Long, List<Long>> adjacency) {
        Map<Long, Integer> color = new HashMap<>();
        Map<Long, Long> predecessor = new HashMap<>();
        for (Long start : adjacency.keySet()) {
            if (color.getOrDefault(start, 0) != 0) {
                continue;
            }
            Deque<Long> stack = new ArrayDeque<>();
            stack.push(start);
            while (!stack.isEmpty()) {
                long node = stack.peek();
                int state = color.getOrDefault(node, 0);
                if (state == 0) {
                    color.put(node, 1);
                    for (Long next : adjacency.getOrDefault(node, List.of())) {
                        int nextColor = color.getOrDefault(next, 0);
                        if (nextColor == 0) {
                            predecessor.put(next, node);
                            stack.push(next);
                        } else if (nextColor == 1) {
                            List<Long> cycle = new ArrayList<>();
                            long cursor = node;
                            cycle.add(next);
                            while (cursor != next) {
                                cycle.add(cursor);
                                cursor = predecessor.getOrDefault(cursor, next);
                            }
                            cycle.add(next);
                            return cycle;
                        }
                    }
                } else {
                    if (state == 1) {
                        color.put(node, 2);
                    }
                    stack.pop();
                }
            }
        }
        return null;
    }

    /**
     * 追加更正附录：已关闭事件仍允许；原包必须存在且原记录标识必须在包内。
     * 仅追加附录，不修改原包与明细。
     */
    @Transactional
    public SealCorrectionView appendCorrection(String incidentKey, int version, String actor,
                                               SealCorrectionRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String recordKey = requireText(req.recordKey(), "recordKey");
        String fieldName = requireText(req.fieldName(), "fieldName");
        String reason = requireText(req.reason(), "reason");
        Incident incident = lockIncident(incidentKey);
        return idempotent.run(commandKey, "log_correction",
                IdempotentCommandRunner.hash(incidentKey, Integer.toString(version), actor,
                        recordKey, fieldName, String.valueOf(req.oldValue()),
                        String.valueOf(req.newValue()), reason),
                SealCorrectionView.class, () -> {
                    LogSeal seal = seals.findSealForUpdate(incident.id(), version)
                            .orElseThrow(() -> ApiException.notFound(
                                    "封存包不存在: 事件 " + incidentKey + " 版本 " + version));
                    boolean recordExists = seals.listRecords(seal.id()).stream()
                            .anyMatch(r -> r.recordKey().equals(recordKey));
                    if (!recordExists) {
                        throw ApiException.unprocessable("CORRECTION_TARGET_NOT_FOUND",
                                "原封存包中不存在该记录标识，不能追加更正",
                                new CorrectionTargetDetail(version, recordKey));
                    }
                    Instant now = clock.instant();
                    long id = seals.insertCorrection(new LogCorrection(0L, incident.id(), seal.id(),
                            version, recordKey, fieldName, req.oldValue(), req.newValue(), reason,
                            actor, now));
                    return toCorrectionView(new LogCorrection(id, incident.id(), seal.id(), version,
                            recordKey, fieldName, req.oldValue(), req.newValue(), reason, actor,
                            now));
                });
    }

    /**
     * 查询事件全部封存包（按版本升序，不含明细），只读。
     */
    @Transactional(readOnly = true)
    public SealListView listSeals(String incidentKey) {
        Incident incident = requireIncident(incidentKey);
        List<SealView> views = seals.listSeals(incident.id()).stream()
                .map(s -> toSealView(s, List.of())).toList();
        return new SealListView(incident.incidentKey(), views);
    }

    /**
     * 查询单个封存包详情：全部明细（按序号）与该包更正附录（按追加顺序），只读。
     */
    @Transactional(readOnly = true)
    public SealDetailView getSeal(String incidentKey, int version) {
        Incident incident = requireIncident(incidentKey);
        LogSeal seal = seals.findSeal(incident.id(), version)
                .orElseThrow(() -> ApiException.notFound(
                        "封存包不存在: 事件 " + incidentKey + " 版本 " + version));
        List<LogSealRecord> rows = seals.listRecords(seal.id());
        List<SealRecordView> records = rows.stream().map(this::toRecordView).toList();
        List<SealCorrectionView> corrections = seals.listCorrectionsBySeal(seal.id()).stream()
                .map(LogSealService::toCorrectionView).toList();
        return new SealDetailView(toSealView(seal, rows), corrections);
    }

    /**
     * 封存验真诊断：按当前持久化明细重算摘要并与封存时 contentHash 比对，只读。
     */
    @Transactional(readOnly = true)
    public SealVerifyView verifySeal(String incidentKey, int version) {
        Incident incident = requireIncident(incidentKey);
        LogSeal seal = seals.findSeal(incident.id(), version)
                .orElseThrow(() -> ApiException.notFound(
                        "封存包不存在: 事件 " + incidentKey + " 版本 " + version));
        List<LogSealRecord> rows = seals.listRecords(seal.id());
        String recomputed = digest(canonical(rows));
        return new SealVerifyView(version, seal.contentHash(), recomputed,
                seal.contentHash().equals(recomputed) && rows.size()
                        == seal.recordCount() + seal.edgeCount());
    }

    private Incident requireIncident(String incidentKey) {
        requireText(incidentKey, "incidentKey");
        return incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
    }

    private Incident lockIncident(String incidentKey) {
        requireText(incidentKey, "incidentKey");
        return incidents.lockByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(field + " 不能为空");
        }
        return value.strip();
    }

    /**
     * 按持久化明细构造规范化字符串：序号、类型、标识、时刻、版本、任务、阻塞事件、快照负载。
     * 负载为封存时写入的原文，重算时逐字使用，保证不可变验真稳定。
     */
    private static String canonical(List<LogSealRecord> rows) {
        StringBuilder sb = new StringBuilder();
        for (LogSealRecord r : rows) {
            sb.append(r.seq()).append('|').append(r.recordType()).append('|')
                    .append(r.recordKey()).append('|').append(r.occurredAt()).append('|')
                    .append(r.recordVersion()).append('|')
                    .append(r.taskId() == null ? "" : r.taskId()).append('|')
                    .append(r.blockerIncidentId() == null ? "" : r.blockerIncidentId())
                    .append('|').append(r.payload()).append('\n');
        }
        return sb.toString();
    }

    private static String digest(String canonical) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private SealView toSealView(LogSeal seal, List<LogSealRecord> rows) {
        List<SealRecordView> recordViews = rows.stream().map(this::toRecordView).toList();
        return new SealView(seal.id(), seal.version(), seal.cutoffAt(), seal.recordCount(),
                seal.edgeCount(), seal.contentHash(), seal.sealedBy(), seal.sealedAt(),
                recordViews);
    }

    private SealRecordView toRecordView(LogSealRecord row) {
        JsonNode payload;
        try {
            payload = objectMapper.readTree(row.payload());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("封存快照反序列化失败", e);
        }
        return new SealRecordView(row.seq(), row.recordType(), row.recordKey(), row.occurredAt(),
                row.recordVersion(), row.taskId(), row.blockerIncidentId(), payload);
    }

    private static SealCorrectionView toCorrectionView(LogCorrection c) {
        return new SealCorrectionView(c.id(), c.sealVersion(), c.recordKey(), c.fieldName(),
                c.oldValue(), c.newValue(), c.reason(), c.correctedBy(), c.createdAt());
    }

    /** 422 截止时刻越界明细：实际值、要求值（创建/当前时刻）与差额。 */
    public record RangeDetail(String cutoffAt, String incidentCreatedAt, String beforeCreatedDiff,
                              String currentAt, String afterCurrentDiff) {
    }

    /** 409 同刻边界明细：截止时刻与恰好同刻的指令标识列表。 */
    public record SameInstantDetail(String cutoffAt, List<String> commandKeys) {
    }

    /** 422 引用外部任务明细。 */
    public record ForeignTaskDetail(String recordKey, String taskId, String incidentKey) {
    }

    /** 422 依赖图环明细。 */
    public record CycleDetail(List<Long> incidentIdCycle) {
    }

    /** 422 更正目标不存在明细。 */
    public record CorrectionTargetDetail(int sealVersion, String recordKey) {
    }
}
