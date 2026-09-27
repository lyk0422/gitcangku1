package com.example.starter.incident;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import com.example.starter.incident.IncidentSealRepository.EdgeSnapshot;
import com.example.starter.incident.dto.Requests.AppendixRequest;
import com.example.starter.incident.dto.Requests.SealCreateRequest;
import com.example.starter.incident.dto.Responses.AppendixHistoryView;
import com.example.starter.incident.dto.Responses.AppendixView;
import com.example.starter.incident.dto.Responses.SealDetailView;
import com.example.starter.incident.dto.Responses.SealDiagnosticsView;
import com.example.starter.incident.dto.Responses.SealEdgeView;
import com.example.starter.incident.dto.Responses.SealHistoryView;
import com.example.starter.incident.dto.Responses.SealRecordView;
import com.example.starter.incident.dto.Responses.SealView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 事件指挥日志封存与更正附录服务。
 * 封存：对未关闭事件按截至 UTC 时刻（左闭右开）固化该时刻前的指令、交接、
 * 任务状态快照与任务依赖边，生成事件内递增版本的不可变日志包；
 * 包内记录按 (发生时刻, 记录标识) 排序，后续任务修订/撤销/合并不改写包内容。
 * 并发约定：与指令写入、交接、任务依赖修订相同，先 SELECT ... FOR UPDATE 锁定事件行，
 * 并持有任务依赖图全局锁读取依赖边，按事务提交顺序裁决。
 * 幂等约定：commandKey 全局唯一，同键同参重放首次响应，同键改参 409，失败不占键。
 * 更正附录：只追加不修改原包；已关闭事件允许查询与追加附录，但不得新增封存包。
 */
@Service
public class IncidentSealService {

    private static final String SEP = "\\u001F";

    private final IncidentRepository incidents;
    private final IncidentTaskRepository tasks;
    private final IncidentSealRepository seals;
    private final CommandKeyRepository commandKeys;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IncidentSealService(IncidentRepository incidents, IncidentTaskRepository tasks,
                               IncidentSealRepository seals, CommandKeyRepository commandKeys,
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
     * 创建封存包：对未关闭事件封存 sealUntil（左闭右开，不含该时刻）前的
     * 指令、交接、任务状态与依赖图。sealUntil 不得早于事件创建、不得晚于当前时刻；
     * 存在同刻未完成指令写入返回 409；包内依赖图有环或引用越界返回 422 且不生成部分包。
     */
    @Transactional
    public SealView createSeal(String incidentKey, String actor, SealCreateRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        if (req.sealUntil() == null) {
            throw ApiException.badRequest("sealUntil 不能为空");
        }
        Instant sealUntil = req.sealUntil().truncatedTo(ChronoUnit.MICROS);
        Incident incident = lockIncident(incidentKey);
        return runIdempotent(commandKey, "seal_create", hash(incidentKey, actor, sealUntil.toString()),
                SealView.class, () -> {
                    if (incident.status() == IncidentStatus.CLOSED) {
                        throw ApiException.illegalTransition("事件已关闭，不能新增封存包");
                    }
                    Instant now = now();
                    if (sealUntil.isBefore(incident.createdAt())) {
                        throw ApiException.badRequest("sealUntil 早于事件创建时刻: sealUntil="
                                + sealUntil + ", createdAt=" + incident.createdAt());
                    }
                    if (sealUntil.isAfter(now)) {
                        throw ApiException.badRequest("sealUntil 晚于当前时刻: sealUntil="
                                + sealUntil + ", now=" + now);
                    }
                    int inFlight = seals.countInFlightCommandsAt(sealUntil, commandKey);
                    if (inFlight > 0) {
                        throw ApiException.conflict("存在 " + inFlight
                                + " 条同刻尚未完成的指令写入，请先完成该写入或选择其他截至时刻: "
                                + sealUntil);
                    }
                    // 依赖图全局锁：与任务创建/依赖修订按提交顺序裁决，读取一致的依赖边
                    tasks.lockGraph();
                    List<IncidentAction> actions = incidents.listActionsBefore(
                            incident.id(), sealUntil);
                    List<IncidentTransfer> transfers = incidents.listTransfersBefore(
                            incident.id(), sealUntil);
                    List<IncidentTask> taskList = tasks.listByIncidentCreatedBefore(
                            incident.id(), sealUntil);
                    List<EdgeSnapshot> edgeSnapshots = seals.listEdgeSnapshotsBefore(
                            incident.id(), sealUntil);
                    validateGraph(incident, taskList, edgeSnapshots);

                    List<SealRecord> records = buildRecords(actions, transfers, taskList);
                    int version = seals.maxVersion(incident.id()).orElse(0) + 1;
                    Instant sealedAt = now();
                    long sealId = seals.insertSeal(new IncidentLogSeal(0L, incident.id(), version,
                            sealUntil, sealedAt, actor, records.size(), edgeSnapshots.size(),
                            sealedAt));
                    for (SealRecord record : records) {
                        seals.insertRecord(new SealRecord(0L, sealId, record.recordType(),
                                record.recordKey(), record.recordVersion(), record.occurredAt(),
                                record.seq(), record.payload()));
                    }
                    int seq = 0;
                    for (EdgeSnapshot edge : edgeSnapshots) {
                        seals.insertEdge(new SealEdge(0L, sealId, edge.taskKey(),
                                edge.blockerIncidentKey(), seq++));
                    }
                    return toSealView(seals.findSeal(incident.id(), version).orElseThrow());
                });
    }

    /**
     * 追加更正附录：引用原包版本与原记录标识，校验提交原值与固化快照一致后追加；
     * 不修改原包。已关闭事件允许追加。记录或字段不存在于固化快照返回 422，
     * 原值不一致返回 409（响应含实际值与提交值）。
     */
    @Transactional
    public AppendixView appendAppendix(String incidentKey, String actor, AppendixRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        if (req.sealVersion() == null) {
            throw ApiException.badRequest("sealVersion 不能为空");
        }
        int sealVersion = req.sealVersion();
        String recordKey = requireText(req.recordKey(), "recordKey");
        String fieldName = requireText(req.fieldName(), "fieldName");
        String newValue = requireText(req.newValue(), "newValue");
        String reason = requireText(req.reason(), "reason");
        String oldValue = req.oldValue();
        Incident incident = lockIncident(incidentKey);
        return runIdempotent(commandKey, "seal_appendix",
                hash(incidentKey, actor, String.valueOf(sealVersion), recordKey, fieldName,
                        oldValue == null ? "" : oldValue, newValue, reason),
                AppendixView.class, () -> {
                    IncidentLogSeal seal = seals.findSeal(incident.id(), sealVersion)
                            .orElseThrow(() -> ApiException.notFound(
                                    "封存包不存在: version=" + sealVersion));
                    SealRecord record = seals.findRecord(seal.id(), recordKey)
                            .orElseThrow(() -> ApiException.unprocessable(
                                    "原记录不存在于封存包 version=" + sealVersion + ": " + recordKey));
                    JsonNode payload = readTree(record.payload());
                    JsonNode field = payload.get(fieldName);
                    if (field == null) {
                        throw ApiException.unprocessable("字段不存在于固化快照: " + fieldName
                                + " (recordKey=" + recordKey + ")");
                    }
                    String actual = field.isNull() ? null : field.asText();
                    if (!Objects.equals(actual, oldValue)) {
                        throw ApiException.conflict("提交原值与固化快照不一致: field=" + fieldName
                                + ", 实际值=" + actual + ", 提交值=" + oldValue);
                    }
                    Instant now = now();
                    long id = seals.insertAppendix(new SealAppendix(0L, incident.id(), seal.id(),
                            sealVersion, recordKey, fieldName, oldValue, newValue, reason,
                            actor, now));
                    return toAppendixView(seals.listAppendices(incident.id()).stream()
                            .filter(a -> a.id() == id).findFirst().orElseThrow());
                });
    }

    /**
     * 查询事件封存包历史（按版本升序）。只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public SealHistoryView listSeals(String incidentKey) {
        Incident incident = findIncident(incidentKey);
        List<SealView> views = seals.listSeals(incident.id()).stream()
                .map(IncidentSealService::toSealView).toList();
        return new SealHistoryView(incident.incidentKey(), views);
    }

    /**
     * 查询封存包明细：包头、全部封存记录、固化依赖边与该包更正附录。只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public SealDetailView getSeal(String incidentKey, int version) {
        Incident incident = findIncident(incidentKey);
        IncidentLogSeal seal = seals.findSeal(incident.id(), version)
                .orElseThrow(() -> ApiException.notFound("封存包不存在: version=" + version));
        List<SealRecordView> records = seals.listRecords(seal.id()).stream()
                .map(this::toRecordView).toList();
        List<SealEdgeView> edges = seals.listEdges(seal.id()).stream()
                .map(e -> new SealEdgeView(e.taskKey(), e.blockerIncidentKey(), e.seq())).toList();
        List<AppendixView> appendices = seals.listAppendicesBySeal(seal.id()).stream()
                .map(IncidentSealService::toAppendixView).toList();
        return new SealDetailView(toSealView(seal), records, edges, appendices);
    }

    /**
     * 查询事件全部更正附录（按追加顺序）。只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public AppendixHistoryView listAppendices(String incidentKey) {
        Incident incident = findIncident(incidentKey);
        List<AppendixView> views = seals.listAppendices(incident.id()).stream()
                .map(IncidentSealService::toAppendixView).toList();
        return new AppendixHistoryView(incident.incidentKey(), views);
    }

    /**
     * 封存诊断查询：汇总封存包数量、最新版本、截至时刻范围、封存记录与依赖边总数、
     * 更正附录总数。只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public SealDiagnosticsView diagnostics(String incidentKey) {
        Incident incident = findIncident(incidentKey);
        List<IncidentLogSeal> all = seals.listSeals(incident.id());
        long[] sums = seals.sumPackageContents(incident.id());
        return new SealDiagnosticsView(incident.incidentKey(), all.size(),
                all.isEmpty() ? null : all.get(all.size() - 1).version(),
                all.isEmpty() ? null : all.get(0).sealUntil(),
                all.isEmpty() ? null : all.get(all.size() - 1).sealUntil(),
                sums[0], sums[1], seals.countAppendices(incident.id()));
    }

    /**
     * 校验包内依赖图：引用任务均属于本事件，且沿依赖边不存在回到本事件的环。
     * 违规抛 422，事务回滚不生成部分包。
     */
    private void validateGraph(Incident incident, List<IncidentTask> taskList,
                               List<EdgeSnapshot> edgeSnapshots) {
        Set<Long> taskIds = new HashSet<>();
        for (IncidentTask task : taskList) {
            taskIds.add(task.id());
        }
        Set<Long> checkedBlockers = new HashSet<>();
        for (EdgeSnapshot edge : edgeSnapshots) {
            if (!taskIds.contains(edge.taskId())) {
                throw ApiException.unprocessable("依赖边引用的任务不属于本事件: taskId="
                        + edge.taskId() + ", incident=" + incident.incidentKey());
            }
            if (checkedBlockers.add(edge.blockerIncidentId())
                    && tasks.isReachable(edge.blockerIncidentId(), incident.id())) {
                throw ApiException.unprocessable("包内依赖图存在环: "
                        + edge.blockerIncidentKey() + " 可沿依赖边回到 "
                        + incident.incidentKey());
            }
        }
    }

    /**
     * 组装封存记录：指令取 occurredAt、交接与任务取 createdAt 为发生时刻，
     * 按 (发生时刻, 记录标识, 类型) 升序赋包内序号。
     */
    private List<SealRecord> buildRecords(List<IncidentAction> actions,
                                          List<IncidentTransfer> transfers,
                                          List<IncidentTask> taskList) {
        List<SealRecord> records = new ArrayList<>();
        for (IncidentAction action : actions) {
            records.add(new SealRecord(0L, 0L, SealRecordType.ACTION, action.actionKey(), 1,
                    action.occurredAt(), 0, toJson(actionPayload(action))));
        }
        for (IncidentTransfer transfer : transfers) {
            int version = transfer.status() == TransferStatus.ACCEPTED ? 2 : 1;
            records.add(new SealRecord(0L, 0L, SealRecordType.TRANSFER,
                    String.valueOf(transfer.id()), version, transfer.createdAt(), 0,
                    toJson(transferPayload(transfer))));
        }
        for (IncidentTask task : taskList) {
            int version = task.status() == TaskStatus.OPEN ? 1 : 2;
            records.add(new SealRecord(0L, 0L, SealRecordType.TASK, task.taskKey(), version,
                    task.createdAt(), 0, toJson(taskPayload(task))));
        }
        records.sort(Comparator.comparing(SealRecord::occurredAt)
                .thenComparing(SealRecord::recordKey)
                .thenComparing(r -> r.recordType().name()));
        List<SealRecord> sequenced = new ArrayList<>(records.size());
        for (int i = 0; i < records.size(); i++) {
            SealRecord r = records.get(i);
            sequenced.add(new SealRecord(0L, 0L, r.recordType(), r.recordKey(),
                    r.recordVersion(), r.occurredAt(), i, r.payload()));
        }
        return sequenced;
    }

    private Map<String, Object> actionPayload(IncidentAction action) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("actionKey", action.actionKey());
        payload.put("actionType", action.actionType());
        payload.put("note", action.note());
        payload.put("occurredAt", action.occurredAt().toString());
        payload.put("actor", action.actor());
        payload.put("createdAt", action.createdAt().toString());
        return payload;
    }

    private Map<String, Object> transferPayload(IncidentTransfer transfer) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("transferId", transfer.id());
        payload.put("fromCommander", transfer.fromCommander());
        payload.put("toCommander", transfer.toCommander());
        payload.put("status", transfer.status().name());
        payload.put("createdAt", transfer.createdAt().toString());
        payload.put("acceptedAt",
                transfer.acceptedAt() == null ? null : transfer.acceptedAt().toString());
        return payload;
    }

    private Map<String, Object> taskPayload(IncidentTask task) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskKey", task.taskKey());
        payload.put("groupCode", task.groupCode());
        payload.put("title", task.title());
        payload.put("status", task.status().name());
        payload.put("createdBy", task.createdBy());
        payload.put("createdAt", task.createdAt().toString());
        payload.put("doneBy", task.doneBy());
        payload.put("doneAt", task.doneAt() == null ? null : task.doneAt().toString());
        payload.put("cancelledBy", task.cancelledBy());
        payload.put("cancelledAt",
                task.cancelledAt() == null ? null : task.cancelledAt().toString());
        return payload;
    }

    private Incident lockIncident(String incidentKey) {
        requireText(incidentKey, "incidentKey");
        return incidents.lockByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
    }

    private Incident findIncident(String incidentKey) {
        requireText(incidentKey, "incidentKey");
        return incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(field + " 不能为空");
        }
        return value.strip();
    }

    /**
     * 幂等执行：同键同参重放首次响应，同键改参 409；并发同键由唯一约束串行化，失败不占键。
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

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("固化快照解析失败", e);
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

    private static SealView toSealView(IncidentLogSeal seal) {
        return new SealView(seal.version(), seal.sealUntil(), seal.sealedAt(), seal.sealedBy(),
                seal.recordCount(), seal.edgeCount());
    }

    private SealRecordView toRecordView(SealRecord record) {
        return new SealRecordView(record.recordType().name(), record.recordKey(),
                record.recordVersion(), record.occurredAt(), record.seq(),
                readTree(record.payload()));
    }

    private static AppendixView toAppendixView(SealAppendix appendix) {
        return new AppendixView(appendix.id(), appendix.sealVersion(), appendix.recordKey(),
                appendix.fieldName(), appendix.oldValue(), appendix.newValue(),
                appendix.reason(), appendix.operator(), appendix.createdAt());
    }
}
