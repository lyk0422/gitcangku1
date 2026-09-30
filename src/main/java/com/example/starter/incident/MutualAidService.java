package com.example.starter.incident;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

import com.example.starter.incident.dto.Requests.LeaseReturnRequest;
import com.example.starter.incident.dto.Requests.MutualAidRequest;
import com.example.starter.incident.dto.Requests.ResourceRegisterRequest;
import com.example.starter.incident.dto.Responses.IncidentLeasesView;
import com.example.starter.incident.dto.Responses.LeaseDetailView;
import com.example.starter.incident.dto.Responses.LeaseView;
import com.example.starter.incident.dto.Responses.ResourceInventoryView;
import com.example.starter.incident.dto.Responses.ResourceReturnView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 跨事件互助资源征调服务。
 *
 * <p>账务约定：来源事件以 incident_resource_inventories 记录登记总量与可用量，
 * 目标事件以 incident_resource_leases 记录资源、数量与 UTC 到期时刻，
 * incident_resource_returns 记录分次归还流水（归还人与 UTC 时间）。
 *
 * <p>并发约定：征调事务先按 incidents.id 升序锁定来源、目标事件行（消除相向征调死锁），
 * 再锁定来源台账行做排他与余量检查，扣减为 {@code SET available = available - ? WHERE available >= ?}
 * 条件更新，由数据库原子裁决，保证并发征调下来源可用量永不为负；
 * 归还事务锁定目标事件行与租约行，归还累加同为条件更新
 * （OPEN 且 returned + 本次 ≤ quantity），超额或已关闭返回 422 并整体回滚。
 *
 * <p>幂等约定：征调 requestId 全局唯一（复用 command_keys 表，operation=mutual_aid），
 * 同键同参重放首次双侧账务响应，同键改数量或目标（或来源/资源/时长/操作人）返回 409；
 * 失败不占键。
 *
 * <p>到期约定：expiresAt 仅为元数据，到期不自动虚构归还、不自动关闭、不自动释放来源额度；
 * 历史租约的来源/目标事件与征调数量永不改变。
 */
@Service
public class MutualAidService {

    private static final String SEP = "\u001F";

    private final IncidentRepository incidents;
    private final MutualAidRepository mutualAid;
    private final CommandKeyRepository commandKeys;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MutualAidService(IncidentRepository incidents, MutualAidRepository mutualAid,
                            CommandKeyRepository commandKeys, ObjectMapper objectMapper, Clock clock) {
        this.incidents = incidents;
        this.mutualAid = mutualAid;
        this.commandKeys = commandKeys;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    private Instant now() {
        return clock.instant();
    }

    /**
     * 为事件登记互助资源台账：仅当前指挥人可登记；(事件, resourceCode) 唯一，
     * 同键重复登记不同总量返回 409（重复登记相同总量直接幂等返回既有台账，避免对账重试失败）。
     * commandKey 全局幂等，同键改参返回 409。
     */
    @Transactional
    public ResourceInventoryView register(String incidentKey, String actor, ResourceRegisterRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String resourceCode = requireText(req.resourceCode(), "resourceCode");
        long quantity = req.quantity();
        if (quantity <= 0) {
            throw ApiException.badRequest("quantity 必须为正整数");
        }
        Incident incident = lockIncident(incidentKey);
        return runIdempotent(commandKey, "resource_register",
                hash(incidentKey, actor, resourceCode, Long.toString(quantity)),
                ResourceInventoryView.class, () -> {
                    requireCommander(incident, actor);
                    var existing = mutualAid.findInventory(incident.id(), resourceCode);
                    if (existing.isPresent()) {
                        ResourceInventory found = existing.get();
                        if (found.totalQty() != quantity) {
                            throw ApiException.conflict("资源 " + resourceCode
                                    + " 已登记且总量不同: 已有 " + found.totalQty() + "，请求 " + quantity);
                        }
                        return toInventoryView(incident.incidentKey(), found);
                    }
                    Instant now = now();
                    ResourceInventory inventory = new ResourceInventory(0L, incident.id(), resourceCode,
                            quantity, quantity, now, now);
                    try {
                        mutualAid.insertInventory(inventory);
                    } catch (DuplicateKeyException e) {
                        throw ApiException.conflict("资源已登记: " + resourceCode);
                    }
                    ResourceInventory saved = mutualAid.findInventory(incident.id(), resourceCode)
                            .orElseThrow();
                    return toInventoryView(incident.incidentKey(), saved);
                });
    }

    /**
     * 跨事件互助征调：目标事件当前指挥人发起，从来源事件台账原子扣减数量，
     * 并为目标登记 OPEN 租约（资源、数量、UTC 到期时刻）。
     * 来源不足、资源已被排他租约占用（尚有未归还）返回 422，两侧均不留下部分变更；
     * 同 requestId 重放首次双侧账务响应，改数量或目标（及其他参数）返回 409。
     */
    @Transactional
    public LeaseView requestAid(String targetIncidentKey, String actor, MutualAidRequest req) {
        String requestId = requireText(req.requestId(), "requestId");
        String sourceKey = requireText(req.sourceIncidentKey(), "sourceIncidentKey");
        String resourceCode = requireText(req.resourceCode(), "resourceCode");
        long quantity = req.quantity();
        long leaseMinutes = req.leaseMinutes();
        if (quantity <= 0) {
            throw ApiException.badRequest("quantity 必须为正整数");
        }
        if (leaseMinutes <= 0) {
            throw ApiException.badRequest("leaseMinutes 必须为正整数");
        }
        if (sourceKey.equals(targetIncidentKey)) {
            throw ApiException.badRequest("来源事件与目标事件不能相同");
        }
        Incident target = incidents.findByKey(targetIncidentKey)
                .orElseThrow(() -> ApiException.notFound("目标事件不存在: " + targetIncidentKey));
        Incident source = incidents.findByKey(sourceKey)
                .orElseThrow(() -> ApiException.notFound("来源事件不存在: " + sourceKey));

        // 按 id 升序锁定两端事件行：相向征调（A→B 与 B→A）也按统一顺序取锁，避免死锁
        Incident first = source.id() < target.id() ? source : target;
        Incident second = source.id() < target.id() ? target : source;
        Incident lockedFirst = incidents.lockById(first.id()).orElseThrow();
        Incident lockedSecond = incidents.lockById(second.id()).orElseThrow();
        Incident lockedSource = lockedFirst.id() == source.id() ? lockedFirst : lockedSecond;
        Incident lockedTarget = lockedFirst.id() == target.id() ? lockedFirst : lockedSecond;

        return runIdempotent(requestId, "mutual_aid",
                hash(targetIncidentKey, actor, sourceKey, resourceCode,
                        Long.toString(quantity), Long.toString(leaseMinutes)),
                LeaseView.class, () -> {
                    requireCommander(lockedTarget, actor);
                    ResourceInventory inventory = mutualAid
                            .lockInventory(lockedSource.id(), resourceCode)
                            .orElseThrow(() -> ApiException.illegalTransition(
                                    "来源事件没有资源 " + resourceCode + " 的台账，无法征调"));
                    // 排他租约：资源尚有任一 OPEN 租约的未归还量时，不得再次征调
                    long outstanding = mutualAid.sumOpenOutstanding(lockedSource.id(), resourceCode);
                    if (outstanding > 0) {
                        throw ApiException.illegalTransition("资源 " + resourceCode
                                + " 已被排他租约占用，未归还数量: " + outstanding);
                    }
                    Instant now = now();
                    int deducted = mutualAid.deductAvailable(inventory.id(), quantity, now);
                    if (deducted == 0) {
                        throw ApiException.illegalTransition("来源资源 " + resourceCode + " 可用量不足: 需要 "
                                + quantity + "，可用 " + inventory.availableQty());
                    }
                    Instant expiresAt = now.plus(leaseMinutes, ChronoUnit.MINUTES);
                    ResourceLease lease = new ResourceLease(0L, requestId, lockedSource.id(),
                            lockedTarget.id(), resourceCode, quantity, 0L, LeaseStatus.OPEN,
                            expiresAt, null, now, now);
                    try {
                        mutualAid.insertLease(lease);
                    } catch (DuplicateKeyException e) {
                        // 极端情况下 requestId 唯一约束兜底（常规并发由 runIdempotent 占位处理）
                        throw ApiException.conflict("requestId 已存在: " + requestId);
                    }
                    ResourceLease saved = mutualAid.findLeaseByRequest(requestId).orElseThrow();
                    return toLeaseView(saved, lockedSource.incidentKey(), lockedTarget.incidentKey());
                });
    }

    /**
     * 分次归还：目标事件当前指挥人按实际数量归还，累计入租约并记录归还人与 UTC 时间。
     * 累计归还超过未归还量（或租约已关闭）返回 422，归还流水与台账返还均不落库；
     * 恰好还完时租约置为 CLOSED 并记录关闭时刻，同时仅在本次把本次归还量返还来源台账，
     * CLOSED 为终态，重复/继续归还直接 422，不再次释放来源额度。
     */
    @Transactional
    public LeaseView returnResource(String targetIncidentKey, String requestId, String actor,
                                    LeaseReturnRequest req) {
        long quantity = req.quantity();
        if (quantity <= 0) {
            throw ApiException.badRequest("quantity 必须为正整数");
        }
        Incident target = lockIncident(targetIncidentKey);
        ResourceLease lease = mutualAid.findLeaseByRequest(requireText(requestId, "requestId"))
                .orElseThrow(() -> ApiException.notFound("租约不存在: " + requestId));
        if (lease.targetIncidentId() != target.id()) {
            throw ApiException.notFound("租约不属于目标事件: " + requestId);
        }
        requireCommander(target, actor);
        // 锁定租约行，串行化同租约并发归还
        ResourceLease locked = mutualAid.lockLeaseById(lease.id())
                .orElseThrow(() -> ApiException.notFound("租约不存在: " + requestId));
        if (locked.status() != LeaseStatus.OPEN) {
            throw ApiException.illegalTransition("租约已关闭，不能继续归还: " + requestId);
        }
        Instant now = now();
        int updated = mutualAid.addReturnedQty(locked.id(), quantity, now);
        if (updated == 0) {
            throw ApiException.illegalTransition("归还数量超过未归还量: 本次 " + quantity + "，待还 "
                    + locked.outstandingQty());
        }
        ResourceLease after = mutualAid.findLeaseById(locked.id()).orElseThrow();
        ResourceInventory sourceInventory = mutualAid
                .findInventory(after.sourceIncidentId(), after.resourceCode())
                .orElseThrow(() -> ApiException.notFound("来源台账缺失: " + after.resourceCode()));
        // 本次实际归还量（件）返还来源可用量；CHECK 约束保证不超过登记总量
        mutualAid.addAvailable(sourceInventory.id(), quantity, now);
        mutualAid.insertReturn(new ResourceReturn(0L, after.id(), quantity, actor, now, now));
        if (after.returnedQty() == after.quantity()) {
            int closed = mutualAid.closeLease(after.id(), now, now);
            if (closed == 0) {
                throw ApiException.conflict("租约已被并发关闭: " + requestId);
            }
        }
        ResourceLease saved = mutualAid.findLeaseById(locked.id()).orElseThrow();
        Incident source = incidents.findById(saved.sourceIncidentId()).orElseThrow();
        return toLeaseView(saved, source.incidentKey(), target.incidentKey());
    }

    /**
     * 查询租约关联账务：租约本体、来源扣减台账（含当前可用量）与分次归还流水。
     * 路径事件必须是租约的来源或目标之一，否则 404。
     */
    @Transactional(readOnly = true)
    public LeaseDetailView getLease(String incidentKey, String requestId) {
        Incident incident = incidents.findByKey(requireText(incidentKey, "incidentKey"))
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        ResourceLease lease = mutualAid.findLeaseByRequest(requireText(requestId, "requestId"))
                .orElseThrow(() -> ApiException.notFound("租约不存在: " + requestId));
        if (lease.sourceIncidentId() != incident.id() && lease.targetIncidentId() != incident.id()) {
            throw ApiException.notFound("租约与事件无关: " + requestId);
        }
        Incident source = incidents.findById(lease.sourceIncidentId()).orElseThrow();
        Incident target = incidents.findById(lease.targetIncidentId()).orElseThrow();
        ResourceInventory inventory = mutualAid
                .findInventory(lease.sourceIncidentId(), lease.resourceCode())
                .orElseThrow(() -> ApiException.notFound("来源台账缺失: " + lease.resourceCode()));
        List<ResourceReturnView> returns = mutualAid.listReturns(lease.id()).stream()
                .map(r -> new ResourceReturnView(r.id(), r.quantity(), r.returnedBy(),
                        r.returnedAt(), r.createdAt()))
                .toList();
        return new LeaseDetailView(toLeaseView(lease, source.incidentKey(), target.incidentKey()),
                toInventoryView(source.incidentKey(), inventory), returns);
    }

    /**
     * 查询与事件关联的全部租约（作为来源或目标），按征调落库顺序返回。
     * 历史租约保留首次征调的来源与目标事件，不因事件状态或归还进度改变。
     */
    @Transactional(readOnly = true)
    public IncidentLeasesView listLeases(String incidentKey) {
        Incident incident = incidents.findByKey(requireText(incidentKey, "incidentKey"))
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        List<LeaseView> views = mutualAid.listLeasesByIncident(incident.id()).stream()
                .map(l -> {
                    String sourceKey = l.sourceIncidentId() == incident.id()
                            ? incident.incidentKey()
                            : incidents.findById(l.sourceIncidentId()).map(Incident::incidentKey)
                                    .orElseThrow();
                    String targetKey = l.targetIncidentId() == incident.id()
                            ? incident.incidentKey()
                            : incidents.findById(l.targetIncidentId()).map(Incident::incidentKey)
                                    .orElseThrow();
                    return toLeaseView(l, sourceKey, targetKey);
                })
                .toList();
        return new IncidentLeasesView(incident.incidentKey(), views);
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
     * 与 IncidentService 同一套约定，失败不占键（业务异常回滚整个事务，含占位行）。
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
            throw ApiException.conflict("requestId 已被不同参数的请求使用: " + record.commandKey());
        }
        if (record.responseBody() == null) {
            throw ApiException.conflict("requestId 正在处理中: " + record.commandKey());
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

    private static ResourceInventoryView toInventoryView(String incidentKey, ResourceInventory inventory) {
        return new ResourceInventoryView(incidentKey, inventory.resourceCode(), inventory.totalQty(),
                inventory.availableQty(), inventory.createdAt(), inventory.updatedAt());
    }

    private static LeaseView toLeaseView(ResourceLease lease, String sourceKey, String targetKey) {
        return new LeaseView(lease.id(), lease.requestId(), sourceKey, targetKey, lease.resourceCode(),
                lease.quantity(), lease.returnedQty(), lease.outstandingQty(), lease.status().name(),
                lease.expiresAt(), lease.closedAt(), lease.createdAt(), lease.updatedAt());
    }
}
