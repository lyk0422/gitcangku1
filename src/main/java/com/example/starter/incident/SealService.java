package com.example.starter.incident;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.example.starter.incident.SealRepository.EdgeRow;
import com.example.starter.incident.dto.Requests.SealAppendixRequest;
import com.example.starter.incident.dto.Requests.SealCreateRequest;
import com.example.starter.incident.dto.Responses.SealAppendixView;
import com.example.starter.incident.dto.Responses.SealDiagnosticsView;
import com.example.starter.incident.dto.Responses.SealEdgeView;
import com.example.starter.incident.dto.Responses.SealEntryView;
import com.example.starter.incident.dto.Responses.SealHistoryView;
import com.example.starter.incident.dto.Responses.SealPackageDetailView;
import com.example.starter.incident.dto.Responses.SealPackageView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 事件指挥日志封存服务。
 * 封存语义：对未关闭事件按截至 UTC 时刻 cutoff 生成递增版本的不可变日志包，
 * 覆盖区间 [上一包截至时刻或事件创建时刻, cutoff) 左闭右开；包内记录按发生时刻、
 * 同刻按记录标识排序，固化每条记录版本与任务依赖边快照，封存后任何业务操作不回写。
 * 同刻指令冲突：存在 occurredAt 恰好等于 cutoff 的指令（处置记录）时返回 409，
 * 调用方需先完成该写入或选择其他截至时刻后再封存。
 * 依赖图校验：封存时校验包内依赖边不构成环且引用任务均属于本事件，违规 422 且
 * 同事务回滚，不生成部分包。
 * 更正附录：已封存记录只能追加附录（保留原值快照、新值、理由、操作人），不修改原包；
 * 已关闭事件允许查询与追加附录，但不得新增封存包。
 * 并发约定：与指令写入、交接、任务修订共用事件行 FOR UPDATE 锁，按事务提交顺序裁决。
 * 幂等约定：commandKey 全局唯一，同键同参重放首次响应，同键改参 409，失败不占键。
 */
@Service
public class SealService {

    private static final String SEP = "\\u001F";

    private final IncidentRepository incidents;
    private final IncidentTaskRepository tasks;
    private final CommandKeyRepository commandKeys;
    private final SealRepository seals;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SealService(IncidentRepository incidents, IncidentTaskRepository tasks,
                       CommandKeyRepository commandKeys, SealRepository seals,
                       ObjectMapper objectMapper, Clock clock) {
        this.incidents = incidents;
        this.tasks = tasks;
        this.commandKeys = commandKeys;
        this.seals = seals;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    private Instant now() {
        return clock.instant();
    }

    /**
     * 创建封存包：仅当前指挥人；事件不得为 CLOSED；cutoff 不得早于事件创建、
     * 不得晚于当前注入时钟、必须晚于上一包截至时刻；同刻指令写入冲突 409；
     * 依赖图违规 422。校验全部通过后在一个事务内写入包、条目与依赖边。
     */
    @Transactional
    public SealPackageView seal(String incidentKey, String actor, SealCreateRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        if (req.cutoffAt() == null) {
            throw ApiException.badRequest("cutoffAt 不能为空");
        }
        Instant cutoff = req.cutoffAt().truncatedTo(ChronoUnit.MICROS);
        Incident incident = lockIncident(incidentKey);
        return runIdempotent(commandKey, "seal_create", hash(incidentKey, actor, cutoff.toString()),
                SealPackageView.class, () -> {
                    requireCommander(incident, actor);
                    if (incident.status() == IncidentStatus.CLOSED) {
                        throw ApiException.illegalTransition("事件已关闭，不能新增封存包");
                    }
                    Instant now = now();
                    validateCutoffBounds(incident, cutoff, now);
                    IncidentSealPackage last = seals.findLatestPackage(incident.id()).orElse(null);
                    Instant lower = last == null ? incident.createdAt() : last.cutoffAt();
                    if (last != null && !cutoff.isAfter(last.cutoffAt())) {
                        throw ApiException.conflict("截至时刻必须晚于上一封存包截至时刻: actual="
                                + cutoff + ", required>" + last.cutoffAt());
                    }
                    List<String> sameInstant = incidents.listActions(incident.id()).stream()
                            .filter(a -> a.occurredAt().equals(cutoff))
                            .map(IncidentAction::actionKey)
                            .sorted()
                            .toList();
                    if (!sameInstant.isEmpty()) {
                        throw ApiException.conflict(
                                "存在与截至时刻同刻的指令写入，请先完成该写入或选择其他截至时刻",
                                List.copyOf(sameInstant));
                    }
                    return doSeal(incident, actor, last, lower, cutoff, now);
                });
    }

    /**
     * 追加更正附录：引用原包版本与原记录标识，原值从封存快照复制；
     * 不修改原包任何内容；已关闭事件仍允许追加。操作人由 X-Actor-Id 标识。
     */
    @Transactional
    public SealAppendixView appendAppendix(String incidentKey, String actor,
                                           SealAppendixRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        requireText(actor, "X-Actor-Id");
        if (req.packageVersion() == null) {
            throw ApiException.badRequest("packageVersion 不能为空");
        }
        int version = req.packageVersion();
        String recordKey = requireText(req.recordKey(), "recordKey");
        String newValue = requireText(req.newValue(), "newValue");
        String reason = requireText(req.reason(), "reason");
        Incident incident = lockIncident(incidentKey);
        return runIdempotent(commandKey, "seal_appendix",
                hash(incidentKey, actor, String.valueOf(version), recordKey, newValue, reason),
                SealAppendixView.class, () -> {
                    IncidentSealPackage pkg = seals.findPackageByVersion(incident.id(), version)
                            .orElseThrow(() -> ApiException.notFound(
                                    "封存包版本不存在: " + version));
                    List<IncidentSealEntry> matches = seals.findEntriesByRecordKey(pkg.id(),
                            recordKey);
                    if (matches.isEmpty()) {
                        throw ApiException.notFound(
                                "封存包 v" + version + " 内不存在记录: " + recordKey);
                    }
                    if (matches.size() > 1) {
                        throw ApiException.conflict("记录标识在包内不唯一，无法定位: " + recordKey);
                    }
                    IncidentSealAppendix appendix = new IncidentSealAppendix(0L, incident.id(),
                            pkg.id(), pkg.version(), recordKey, matches.get(0).payload(),
                            newValue, reason, actor, now());
                    long id = seals.insertAppendix(appendix);
                    return toAppendixView(seals.findAppendix(id).orElseThrow());
                });
    }

    /**
     * 封存历史：版本递增的包摘要列表与全部更正附录。只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public SealHistoryView sealHistory(String incidentKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        List<SealPackageView> packages = seals.listPackages(incident.id()).stream()
                .map(SealService::toPackageView).toList();
        List<SealAppendixView> appendices = seals.listAppendices(incident.id()).stream()
                .map(this::toAppendixView).toList();
        return new SealHistoryView(incident.incidentKey(), packages, appendices);
    }

    /**
     * 封存包明细：摘要 + 全部条目（按包内序号）+ 全部依赖边。只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public SealPackageDetailView sealDetail(String incidentKey, int version) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        IncidentSealPackage pkg = seals.findPackageByVersion(incident.id(), version)
                .orElseThrow(() -> ApiException.notFound("封存包版本不存在: " + version));
        List<SealEntryView> entries = seals.listEntries(pkg.id()).stream()
                .map(this::toEntryView).toList();
        List<SealEdgeView> edges = seals.listEdges(pkg.id()).stream()
                .map(e -> new SealEdgeView(e.taskKey(), e.blockerIncidentKey(), e.edgeCreatedAt()))
                .toList();
        return new SealPackageDetailView(pkg.version(), pkg.lowerBoundAt(), pkg.cutoffAt(),
                pkg.entryCount(), pkg.edgeCount(), pkg.sealedBy(), pkg.createdAt(), entries, edges);
    }

    /**
     * 封存诊断：当前可封存窗口内各类型待封存记录数、与当前时刻同刻的指令标识。
     * 只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public SealDiagnosticsView sealDiagnostics(String incidentKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        Instant now = now();
        IncidentSealPackage last = seals.findLatestPackage(incident.id()).orElse(null);
        Instant lower = last == null ? incident.createdAt() : last.cutoffAt();
        List<IncidentAction> actions = incidents.listActions(incident.id());
        long pendingActions = actions.stream()
                .filter(a -> inWindow(a.occurredAt(), lower, now)).count();
        long pendingTransfers = incidents.listTransfers(incident.id()).stream()
                .filter(t -> inWindow(t.createdAt(), lower, now)).count();
        long pendingStatusChanges = incidents.listStatusHistory(incident.id()).stream()
                .filter(s -> inWindow(s.occurredAt(), lower, now)).count();
        long taskCount = tasks.listByIncident(incident.id()).stream()
                .filter(t -> t.createdAt().isBefore(now)).count();
        List<String> sameInstant = actions.stream()
                .filter(a -> a.occurredAt().equals(now))
                .map(IncidentAction::actionKey)
                .sorted()
                .toList();
        return new SealDiagnosticsView(incident.incidentKey(), incident.status().name(),
                incident.createdAt(), now,
                last == null ? null : last.version(),
                last == null ? null : last.cutoffAt(),
                lower, pendingActions, pendingTransfers, pendingStatusChanges, taskCount,
                sameInstant);
    }

    /**
     * 执行封存：收集窗口内记录与任务快照，校验依赖图，同事务写入包、条目与边。
     */
    private SealPackageView doSeal(Incident incident, String actor, IncidentSealPackage last,
                                   Instant lower, Instant cutoff, Instant now) {
        List<EntryDraft> drafts = new ArrayList<>();
        for (IncidentAction action : incidents.listActions(incident.id())) {
            if (inWindow(action.occurredAt(), lower, cutoff)) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("actionKey", action.actionKey());
                payload.put("actionType", action.actionType());
                payload.put("note", action.note());
                payload.put("occurredAt", action.occurredAt().toString());
                payload.put("actor", action.actor());
                payload.put("createdAt", action.createdAt().toString());
                drafts.add(new EntryDraft("ACTION", action.actionKey(), action.occurredAt(), 1,
                        toJson(payload)));
            }
        }
        for (IncidentTransfer transfer : incidents.listTransfers(incident.id())) {
            if (inWindow(transfer.createdAt(), lower, cutoff)) {
                drafts.add(new EntryDraft("TRANSFER", "TR-" + transfer.id(), transfer.createdAt(),
                        1, toJson(transferPayload(transfer))));
            }
        }
        for (StatusChange change : incidents.listStatusHistory(incident.id())) {
            if (inWindow(change.occurredAt(), lower, cutoff)) {
                drafts.add(new EntryDraft("STATUS_CHANGE", "SC-" + change.id(),
                        change.occurredAt(), 1, toJson(statusChangePayload(change))));
            }
        }
        List<String> sealedTaskKeys = new ArrayList<>();
        for (IncidentTask task : tasks.listByIncident(incident.id())) {
            if (task.createdAt().isBefore(cutoff)) {
                sealedTaskKeys.add(task.taskKey());
                int recordVersion = seals.countSealedEntries(incident.id(), "TASK",
                        task.taskKey()) + 1;
                drafts.add(new EntryDraft("TASK", task.taskKey(), task.createdAt(), recordVersion,
                        toJson(taskPayload(task))));
            }
        }
        // 依赖边快照：仅本事件且任务已在本包收录（created_at < cutoff）的边
        List<EdgeRow> edges = seals.listIncidentEdges(incident.id()).stream()
                .filter(e -> sealedTaskKeys.contains(e.taskKey()))
                .toList();
        validateGraph(incident, edges);
        drafts.sort(Comparator.comparing(EntryDraft::occurredAt)
                .thenComparing(EntryDraft::recordKey)
                .thenComparing(EntryDraft::recordType));
        int version = (last == null ? 0 : last.version()) + 1;
        IncidentSealPackage pkg = new IncidentSealPackage(0L, incident.id(), version, lower,
                cutoff, drafts.size(), edges.size(), actor, now);
        long packageId = seals.insertPackage(pkg);
        int seq = 0;
        for (EntryDraft draft : drafts) {
            seals.insertEntry(new IncidentSealEntry(0L, packageId, ++seq, draft.recordType(),
                    draft.recordKey(), draft.occurredAt(), draft.recordVersion(), draft.payload()));
        }
        for (EdgeRow edge : edges) {
            seals.insertEdge(new IncidentSealEdge(0L, packageId, edge.taskKey(),
                    edge.blockerIncidentKey(), edge.edgeCreatedAt()));
        }
        return toPackageView(seals.findPackageByVersion(incident.id(), version).orElseThrow());
    }

    /**
     * 截至时刻边界校验：不得早于事件创建、不得晚于当前注入时钟；
     * 违规 400 且消息携带实际值与要求值。
     */
    private static void validateCutoffBounds(Incident incident, Instant cutoff, Instant now) {
        if (cutoff.isBefore(incident.createdAt())) {
            throw ApiException.badRequest("截至时刻早于事件创建时刻: actual=" + cutoff
                    + ", required>=" + incident.createdAt());
        }
        if (cutoff.isAfter(now)) {
            throw ApiException.badRequest("截至时刻晚于当前时刻: actual=" + cutoff
                    + ", required<=" + now);
        }
    }

    /**
     * 依赖图校验：包内每条边（本事件任务 → 阻塞事件）不得构成环
     * （阻塞事件沿当前依赖图可达本事件即成环）；违规 422 且不生成部分包。
     * 引用任务均属于本事件由 listIncidentEdges 的关联条件保证。
     */
    private void validateGraph(Incident incident, List<EdgeRow> edges) {
        List<String> cyclic = new ArrayList<>();
        for (EdgeRow edge : edges) {
            if (tasks.isReachable(edge.blockerIncidentId(), incident.id())) {
                cyclic.add(edge.taskKey() + "->" + edge.blockerIncidentKey());
            }
        }
        if (!cyclic.isEmpty()) {
            throw ApiException.illegalTransition("封存包依赖图存在环，不允许封存",
                    List.copyOf(cyclic));
        }
    }

    private static boolean inWindow(Instant occurredAt, Instant lower, Instant cutoff) {
        return !occurredAt.isBefore(lower) && occurredAt.isBefore(cutoff);
    }

    /**
     * 待封存条目草稿：排序后编包内序号。
     */
    private record EntryDraft(String recordType, String recordKey, Instant occurredAt,
                              int recordVersion, String payload) {
    }

    private static Map<String, Object> transferPayload(IncidentTransfer transfer) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", transfer.id());
        payload.put("fromCommander", transfer.fromCommander());
        payload.put("toCommander", transfer.toCommander());
        payload.put("status", transfer.status().name());
        payload.put("createdAt", transfer.createdAt().toString());
        payload.put("acceptedAt",
                transfer.acceptedAt() == null ? null : transfer.acceptedAt().toString());
        return payload;
    }

    private static Map<String, Object> statusChangePayload(StatusChange change) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", change.id());
        payload.put("fromStatus", change.fromStatus() == null ? null : change.fromStatus().name());
        payload.put("toStatus", change.toStatus().name());
        payload.put("actor", change.actor());
        payload.put("occurredAt", change.occurredAt().toString());
        return payload;
    }

    /**
     * 任务快照：固化封存时刻的任务状态与阻塞事件清单（含阻塞事件当时状态）。
     */
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
        List<Map<String, Object>> blockers = incidents.listBlockingIncidents(task.id()).stream()
                .map(b -> {
                    Map<String, Object> blocker = new LinkedHashMap<String, Object>();
                    blocker.put("incidentKey", b.incidentKey());
                    blocker.put("incidentStatus", b.status().name());
                    return blocker;
                })
                .toList();
        payload.put("blockers", blockers);
        return payload;
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
     * 幂等执行：同键同参重放首次响应，同键改参 409；并发同键由唯一约束串行化；
     * 业务失败时事务回滚，占位键随之撤销（失败不占键）。
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

    private static String hash(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(String.join(SEP, parts).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static SealPackageView toPackageView(IncidentSealPackage pkg) {
        return new SealPackageView(pkg.version(), pkg.lowerBoundAt(), pkg.cutoffAt(),
                pkg.entryCount(), pkg.edgeCount(), pkg.sealedBy(), pkg.createdAt());
    }

    private SealEntryView toEntryView(IncidentSealEntry entry) {
        return new SealEntryView(entry.seqNo(), entry.recordType(), entry.recordKey(),
                entry.occurredAt(), entry.recordVersion(), parseJson(entry.payload()));
    }

    private SealAppendixView toAppendixView(IncidentSealAppendix appendix) {
        return new SealAppendixView(appendix.id(), appendix.packageVersion(),
                appendix.recordKey(), parseJson(appendix.oldValue()), appendix.newValue(),
                appendix.reason(), appendix.actor(), appendix.createdAt());
    }

    private Object parseJson(String json) {
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("封存快照反序列化失败", e);
        }
    }
}
