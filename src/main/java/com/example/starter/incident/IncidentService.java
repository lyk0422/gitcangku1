package com.example.starter.incident;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.example.starter.incident.dto.Requests.ActionRequest;
import com.example.starter.incident.dto.Requests.CleanupRequest;
import com.example.starter.incident.dto.Requests.DependencyRequest;
import com.example.starter.incident.dto.Requests.EscalateRequest;
import com.example.starter.incident.dto.Requests.CancelRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TransferAcceptRequest;
import com.example.starter.incident.dto.Requests.TransferRequest;
import com.example.starter.incident.dto.Responses.ActionView;
import com.example.starter.incident.dto.Responses.CleanupView;
import com.example.starter.incident.dto.Responses.DependencyView;
import com.example.starter.incident.dto.Responses.DrillBatchView;
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
 * 事件指挥核心服务，支持真实（REAL）与演练沙盘（DRILL）两个互不相通的隔离域。
 *
 * <p>并发约定：所有写接口先 SELECT ... FOR UPDATE 锁定事件行（清理先锁演练批次登记行、
 * 再锁批次内全部事件行），同事务内完成幂等键占位、业务校验与写入，保证并发请求按事务
 * 提交顺序生效。</p>
 *
 * <p>幂等约定：(domain, commandKey) 域内唯一，同键同参重放首次响应，同键改参 409，
 * 业务失败随事务回滚而不占用键；两域键空间独立，同参重放不会跨域命中。</p>
 *
 * <p>跨域约定：处置任务依赖的阻塞事件必须同域，跨域引用返回 422；演练域升级不产生
 * 真实域任何通知或副作用。</p>
 */
@Service
public class IncidentService {

    private static final String SEP = "\u001F";

    private final IncidentRepository incidents;
    private final CommandKeyRepository commandKeys;
    private final DrillBatchRepository drillBatches;
    private final ObjectMapper objectMapper;

    public IncidentService(IncidentRepository incidents, CommandKeyRepository commandKeys,
                           DrillBatchRepository drillBatches, ObjectMapper objectMapper) {
        this.incidents = incidents;
        this.commandKeys = commandKeys;
        this.drillBatches = drillBatches;
        this.objectMapper = objectMapper;
    }

    /**
     * 事件上报：初始状态 REPORTED，无指挥人。
     * drillKey 非空标记为演练事件（DRILL 域），此时必须提供 batchKey；
     * 同一 incidentKey 在 REAL 与 DRILL 两域可各自存在而不冲突。
     * 演练批次清理后，同 batchKey 不可再用于新演练（422）。
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

        Domain domain;
        String batchKey = null;
        if (req.drillKey() != null && !req.drillKey().isBlank()) {
            domain = Domain.DRILL;
            batchKey = requireText(req.batchKey(), "batchKey");
            registerDrillBatch(batchKey);
        } else {
            domain = Domain.REAL;
        }

        if (incidents.findByKey(domain, incidentKey).isPresent()) {
            throw ApiException.conflict("incidentKey 已存在: " + incidentKey);
        }
        Instant now = Instant.now();
        Incident incident = new Incident(0L, incidentKey, domain, batchKey, severity, summary, reporter,
                IncidentStatus.REPORTED, null, now, now);
        long id;
        try {
            id = incidents.insert(incident);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("incidentKey 已存在: " + incidentKey);
        }
        incidents.insertStatusChange(new StatusChange(0L, id, null, IncidentStatus.REPORTED, reporter, now));
        return toView(incidents.findByKey(domain, incidentKey).orElseThrow(), null);
    }

    /**
     * 登记演练批次（首次使用），并串行化与清理的并发：
     * 清理先提交则本方法在批次行锁上等待后看到墓碑，拒绝批次标识复用。
     */
    private void registerDrillBatch(String batchKey) {
        var batch = drillBatches.lockByKey(batchKey);
        if (batch.isPresent()) {
            if (batch.get().isCleaned()) {
                throw ApiException.unprocessable("BATCH_ALREADY_CLEANED",
                        "演练批次已清理，批次标识不可复用: " + batchKey);
            }
            return;
        }
        try {
            drillBatches.insert(batchKey, Instant.now());
        } catch (DuplicateKeyException e) {
            // 并发首次登记：另一事务已登记（可能已清理），重查锁定行确认。
            var committed = drillBatches.lockByKey(batchKey)
                    .orElseThrow(() -> ApiException.conflict("演练批次登记冲突: " + batchKey));
            if (committed.isCleaned()) {
                throw ApiException.unprocessable("BATCH_ALREADY_CLEANED",
                        "演练批次已清理，批次标识不可复用: " + batchKey);
            }
        }
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
                    return toView(incidents.findByKey(domain, incidentKey).orElseThrow(), null);
                });
    }

    /**
     * 发起交接：仅当前指挥人可发起，目标人必须不同；RESOLVED/CLOSED/CANCELLED 禁止发起。
     */
    @Transactional
    public TransferView initiateTransfer(Domain domain, String incidentKey, String actor,
                                         TransferRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String toCommander = requireText(req.toCommander(), "toCommander");
        Incident incident = lockIncident(domain, incidentKey);
        return runIdempotent(domain, commandKey, "transfer_initiate", hash(incidentKey, actor, toCommander),
                TransferView.class, () -> {
                    requireCommander(incident, actor);
                    if (incident.status() == IncidentStatus.RESOLVED
                            || incident.status() == IncidentStatus.CLOSED
                            || incident.status() == IncidentStatus.CANCELLED) {
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
     * 接受交接：仅待接受目标人可接受；接受后原子切换当前指挥人。
     * RESOLVED/CLOSED/CANCELLED 禁止接受；待接受期间目标人无其他操作权限。
     */
    @Transactional
    public IncidentView acceptTransfer(Domain domain, String incidentKey, String actor,
                                       TransferAcceptRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        Incident incident = lockIncident(domain, incidentKey);
        return runIdempotent(domain, commandKey, "transfer_accept", hash(incidentKey, actor),
                IncidentView.class, () -> {
                    if (incident.status() == IncidentStatus.RESOLVED
                            || incident.status() == IncidentStatus.CLOSED
                            || incident.status() == IncidentStatus.CANCELLED) {
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
                    return toView(incidents.findByKey(domain, incidentKey).orElseThrow(), null);
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
                        throw ApiException.illegalTransition("事件已终结，不能再追加处置记录");
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
                    return toView(incidents.findByKey(domain, incidentKey).orElseThrow(), null);
                });
    }

    /**
     * 升级事件严重等级：仅当前指挥人可操作，目标等级必须严格更高（S4&lt;S3&lt;S2&lt;S1）。
     * 仅 REAL 域升级写入真实通知副作用；DRILL 域升级只落升级记录，不触发任何真实通知。
     */
    @Transactional
    public IncidentView escalate(Domain domain, String incidentKey, String actor, EscalateRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String toSeverity = requireText(req.toSeverity(), "toSeverity");
        if (!toSeverity.matches("S[1-4]")) {
            throw ApiException.badRequest("toSeverity 必须为 S1~S4");
        }
        String reason = requireText(req.reason(), "reason");
        Incident incident = lockIncident(domain, incidentKey);
        return runIdempotent(domain, commandKey, "escalate",
                hash(incidentKey, actor, toSeverity, reason), IncidentView.class, () -> {
                    requireCommander(incident, actor);
                    if (incident.status() == IncidentStatus.CLOSED
                            || incident.status() == IncidentStatus.CANCELLED) {
                        throw ApiException.illegalTransition("事件已终结，不能升级");
                    }
                    int currentRank = severityRank(incident.severity());
                    int targetRank = severityRank(toSeverity);
                    if (targetRank >= currentRank) {
                        throw ApiException.illegalTransition(
                                "只能升级到更高等级，当前 " + incident.severity() + "，目标 " + toSeverity);
                    }
                    Instant now = Instant.now();
                    incidents.updateSeverity(incident.id(), toSeverity, now);
                    incidents.insertEscalation(new IncidentEscalation(0L, incident.id(),
                            incident.severity(), toSeverity, reason, actor, now));
                    if (domain == Domain.REAL) {
                        incidents.insertNotification(incident.id(), Domain.REAL, "ONSITE",
                                "事件 " + incidentKey + " 升级为 " + toSeverity + "：" + reason, now);
                    }
                    return toView(incidents.findByKey(domain, incidentKey).orElseThrow(), null);
                });
    }

    /**
     * 建立处置任务依赖（阻塞）边：阻塞事件必须与当前事件同域。
     * 阻塞事件仅存在于另一域时返回 422；两域都不存在返回 404；同边重复建立幂等返回。
     */
    @Transactional
    public DependencyView addDependency(Domain domain, String incidentKey, String actor,
                                        DependencyRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String blockedByKey = requireText(req.blockedByIncidentKey(), "blockedByIncidentKey");
        Incident incident = lockIncident(domain, incidentKey);
        return runIdempotent(domain, commandKey, "dependency", hash(incidentKey, blockedByKey),
                DependencyView.class, () -> {
                    requireCommander(incident, actor);
                    if (incident.status() == IncidentStatus.CLOSED
                            || incident.status() == IncidentStatus.CANCELLED) {
                        throw ApiException.illegalTransition("事件已终结，不能建立处置依赖");
                    }
                    if (blockedByKey.equals(incidentKey)) {
                        throw ApiException.badRequest("事件不能依赖自身");
                    }
                    Incident blocked = incidents.findByKey(domain, blockedByKey).orElse(null);
                    if (blocked == null) {
                        Domain other = domain == Domain.REAL ? Domain.DRILL : Domain.REAL;
                        if (incidents.findByKey(other, blockedByKey).isPresent()) {
                            throw ApiException.unprocessable("CROSS_DOMAIN_REFERENCE",
                                    "禁止跨域引用：阻塞事件 " + blockedByKey + " 位于 "
                                            + other + " 域，当前事件位于 " + domain + " 域");
                        }
                        throw ApiException.notFound("阻塞事件不存在: " + blockedByKey);
                    }
                    Instant now = Instant.now();
                    try {
                        incidents.insertDependency(new IncidentDependency(0L, incident.id(),
                                blocked.id(), now));
                    } catch (DuplicateKeyException e) {
                        // 同边重复建立：幂等返回既有边。
                    }
                    return new DependencyView(incidentKey, blockedByKey, now);
                });
    }

    /**
     * 取消演练事件：仅 DRILL 域、仅 REPORTED（尚未接管）可由上报人取消，进入 CANCELLED 终态。
     * 真实事件不允许取消。
     */
    @Transactional
    public IncidentView cancelDrill(String incidentKey, String actor, CancelRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        Incident incident = lockIncident(Domain.DRILL, incidentKey);
        return runIdempotent(Domain.DRILL, commandKey, "cancel", hash(incidentKey, actor),
                IncidentView.class, () -> {
                    if (!incident.reporter().equals(actor)) {
                        throw ApiException.conflict("只有上报人 " + incident.reporter() + " 能取消演练事件");
                    }
                    if (incident.status() != IncidentStatus.REPORTED) {
                        throw ApiException.illegalTransition(
                                "仅 REPORTED 状态的演练事件可取消，当前状态: " + incident.status());
                    }
                    Instant now = Instant.now();
                    incidents.updateState(incident.id(), IncidentStatus.CANCELLED, incident.commander(), now);
                    incidents.insertStatusChange(new StatusChange(0L, incident.id(),
                            IncidentStatus.REPORTED, IncidentStatus.CANCELLED, actor, now));
                    return toView(incidents.findByKey(Domain.DRILL, incidentKey).orElseThrow(), null);
                });
    }

    /**
     * 演练批次批量清理：单事务内校验批次全部事件处于终态（RESOLVED/CLOSED/CANCELLED），
     * 任一未终结则整批 422 并列出未终结事件；全部满足则原子删除事件及其任务、依赖边、
     * 交接、升级记录，并写入批次墓碑（同 batchKey 不可再用）。不影响其他批次与真实事件。
     *
     * <p>与演练域写并发时由行锁按事务提交顺序裁决：清理先提交则后续写 404；
     * 写先提交则清理在拿到批次行/事件行锁后重新校验，未终结即 422。</p>
     */
    @Transactional
    public CleanupView cleanupDrillBatch(CleanupRequest req) {
        String cleanupKey = requireText(req.cleanupKey(), "cleanupKey");
        String batchKey = requireText(req.batchKey(), "batchKey");
        DrillBatch batch = drillBatches.lockByKey(batchKey)
                .orElseThrow(() -> ApiException.notFound("演练批次不存在: " + batchKey));
        return runIdempotent(Domain.DRILL, cleanupKey, "cleanup", hash(batchKey), CleanupView.class,
                () -> {
                    if (batch.isCleaned()) {
                        throw ApiException.conflict("演练批次已清理: " + batchKey);
                    }
                    List<Incident> members = incidents.lockByDrillBatch(batchKey);
                    List<String> unfinished = members.stream()
                            .filter(i -> !i.status().isCleanupTerminal())
                            .map(Incident::incidentKey).toList();
                    if (!unfinished.isEmpty()) {
                        throw ApiException.unprocessable("BATCH_NOT_TERMINAL",
                                "批次存在未终结事件，整批拒绝清理: " + String.join(",", unfinished));
                    }
                    List<Long> ids = members.stream().map(Incident::id).toList();
                    incidents.deleteIncidentsCascade(ids);
                    Instant now = Instant.now();
                    drillBatches.markCleaned(batchKey, cleanupKey, ids.size(), now);
                    return new CleanupView(batchKey, cleanupKey, ids.size(), now);
                });
    }

    /**
     * 查询事件当前状态（含当前指挥人与待接受交接目标人）。默认仅在 REAL 域查找。
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
     * 查询完整历史：事件本体、状态流转、处置记录、交接记录、升级记录与依赖边。
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
                .map(IncidentService::toEscalationView).toList();
        Map<Long, String> keyById = incidents.listByIds(incidents.listDependencies(incident.id()).stream()
                        .map(IncidentDependency::blockedByIncidentId).toList()).stream()
                .collect(java.util.stream.Collectors.toMap(Incident::id, Incident::incidentKey));
        List<DependencyView> dependencies = incidents.listDependencies(incident.id()).stream()
                .map(d -> new DependencyView(incidentKey,
                        keyById.getOrDefault(d.blockedByIncidentId(), "#" + d.blockedByIncidentId()),
                        d.createdAt()))
                .toList();
        return new HistoryView(toView(incident, pendingTo), statusHistory, actions, transfers,
                escalations, dependencies);
    }

    /**
     * 列事件：默认仅真实域；includeDrill=true 时同时返回演练域，每个结果均带 domain 标注。
     */
    @Transactional(readOnly = true)
    public List<IncidentView> list(boolean includeDrill) {
        List<IncidentView> views = toViews(incidents.listByDomain(Domain.REAL));
        if (includeDrill) {
            views = new ArrayList<>(views);
            views.addAll(toViews(incidents.listByDomain(Domain.DRILL)));
        }
        return views;
    }

    /**
     * 两域独立统计：按域给出事件总数与各状态计数；默认仅真实域，includeDrill=true 附带演练域。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> stats(boolean includeDrill) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("REAL", domainStats(Domain.REAL));
        if (includeDrill) {
            result.put("DRILL", domainStats(Domain.DRILL));
        }
        return result;
    }

    private Map<String, Object> domainStats(Domain domain) {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (Incident incident : incidents.listByDomain(domain)) {
            byStatus.merge(incident.status().name(), 1L, Long::sum);
        }
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("domain", domain.name());
        stats.put("total", total);
        stats.put("byStatus", byStatus);
        return stats;
    }

    /**
     * 查询演练批次视图（清理历史/墓碑信息），含批次内在册演练事件清单。
     */
    @Transactional(readOnly = true)
    public DrillBatchView getDrillBatch(String batchKey) {
        DrillBatch batch = drillBatches.findByKey(batchKey)
                .orElseThrow(() -> ApiException.notFound("演练批次不存在: " + batchKey));
        return toBatchView(batch, incidents.listByDrillBatch(batchKey));
    }

    /**
     * 查询全部演练批次（含已清理墓碑），按登记时间倒序，供清理历史查询。
     */
    @Transactional(readOnly = true)
    public List<DrillBatchView> listDrillBatches() {
        List<DrillBatchView> views = new ArrayList<>();
        for (DrillBatch batch : drillBatches.listAll()) {
            views.add(toBatchView(batch, incidents.listByDrillBatch(batch.batchKey())));
        }
        return views;
    }

    private Incident lockIncident(Domain domain, String incidentKey) {
        requireText(incidentKey, "incidentKey");
        return incidents.lockByKey(domain, incidentKey)
                .orElseThrow(() -> ApiException.notFound(
                        (domain == Domain.DRILL ? "演练事件不存在（或已被清理）: " : "事件不存在: ")
                                + incidentKey));
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

    private static int severityRank(String severity) {
        return Integer.parseInt(severity.substring(1));
    }

    /**
     * 幂等执行：同域同键同参重放首次响应，同键改参 409；并发同键由唯一约束串行化。
     * 业务异常时整个事务回滚，占位幂等键一并回滚，即“失败不占键”。
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
        return new IncidentView(incident.incidentKey(), incident.domain().name(),
                incident.drillBatchKey(), incident.severity(), incident.summary(), incident.reporter(),
                incident.status().name(), incident.commander(), pendingTransferTo,
                incident.createdAt(), incident.updatedAt());
    }

    private List<IncidentView> toViews(List<Incident> list) {
        return list.stream().map(i -> toView(i, null)).toList();
    }

    private DrillBatchView toBatchView(DrillBatch batch, List<Incident> members) {
        return new DrillBatchView(batch.batchKey(), batch.isCleaned(), members.size(),
                batch.deletedIncidentCount(), batch.cleanupKey(), batch.createdAt(), batch.cleanedAt(),
                toViews(members));
    }

    private ActionView toActionView(IncidentAction action) {
        return new ActionView(action.actionKey(), action.actionType(), action.note(),
                action.occurredAt(), action.actor(), action.createdAt());
    }

    private static TransferView toTransferView(IncidentTransfer transfer) {
        return new TransferView(transfer.id(), transfer.fromCommander(), transfer.toCommander(),
                transfer.status().name(), transfer.createdAt(), transfer.acceptedAt());
    }

    private static EscalationView toEscalationView(IncidentEscalation escalation) {
        return new EscalationView(escalation.fromSeverity(), escalation.toSeverity(),
                escalation.reason(), escalation.actor(), escalation.createdAt());
    }
}
