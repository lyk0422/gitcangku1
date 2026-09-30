package com.example.starter.incident;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

import com.example.starter.incident.dto.Requests.AidRequestRequest;
import com.example.starter.incident.dto.Requests.AidReturnRequest;
import com.example.starter.incident.dto.Requests.ResourceRegisterRequest;
import com.example.starter.incident.dto.Responses.AidLeaseDetailView;
import com.example.starter.incident.dto.Responses.AidLeaseView;
import com.example.starter.incident.dto.Responses.AidReturnView;
import com.example.starter.incident.dto.Responses.ResourceInventoryView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 跨事件互助资源征调服务。
 * 账务约定：来源事件以 incident_resource_inventories 记可用量，目标事件以
 * resource_aid_leases 记租约，归还以 resource_aid_returns 记分次流水。
 * 并发约定：征调先按事件 id 升序 SELECT ... FOR UPDATE 锁住来源与目标事件，
 * 再锁库存行并以带 available_quantity &gt;= ? 条件的 UPDATE 原子扣减；
 * 归还先锁租约行，累计更新带 returned_quantity + ? &lt;= quantity 条件。
 * 幂等约定：征调以 requestId、归还以 commandKey 作为幂等键，同键同参重放首次响应，
 * 同键改参返回 409；任何业务失败均回滚，双侧不留下部分变更。
 * 到期约定：expiresAt 只登记 UTC 到期时刻，到期不自动虚构归还、不自动释放来源额度。
 */
@Service
public class MutualAidService {

    // 请求摘要字段分隔符（U+001F）以代码点构造，避免源码中出现裸控制字符；行尾原有字面量已注释
    private static final String SEP2 = String.valueOf(Character.toChars(0x1F)); //"";

    private final IncidentRepository incidents;
    private final ResourceAidRepository aid;
    private final CommandKeyRepository commandKeys;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MutualAidService(IncidentRepository incidents, ResourceAidRepository aid,
                            CommandKeyRepository commandKeys, ObjectMapper objectMapper, Clock clock) {
        this.incidents = incidents;
        this.aid = aid;
        this.commandKeys = commandKeys;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    private Instant now() {
        return clock.instant();
    }

    /**
     * 登记事件资源可用量（合成数据入口）：同一事件同一资源仅可登记一次，
     * 重复登记相同数量幂等返回，登记不同数量返回 409。
     */
    @Transactional
    public ResourceInventoryView registerInventory(String incidentKey, String actor,
                                                   ResourceRegisterRequest req) {
        String resourceCode = requireText(req.resourceCode(), "resourceCode");
        Integer quantity = req.quantity();
        if (quantity == null || quantity < 0) {
            throw ApiException.badRequest("quantity 必须为非负整数");
        }
        Incident incident = lockIncident(incidentKey);
        var existing = aid.findInventory(incident.id(), resourceCode);
        if (existing.isPresent()) {
            if (existing.get().availableQuantity() != quantity) {
                throw ApiException.conflict("资源 " + resourceCode + " 已登记不同可用量: "
                        + existing.get().availableQuantity());
            }
            return toInventoryView(incident.incidentKey(), existing.get());
        }
        Instant now = now();
        try {
            aid.insertInventory(new ResourceInventory(0L, incident.id(), resourceCode, quantity, now, now));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("资源已登记: " + resourceCode);
        }
        return toInventoryView(incident.incidentKey(),
                aid.findInventory(incident.id(), resourceCode).orElseThrow());
    }

    /**
     * 查询事件登记的全部资源可用量，按资源编码排序。只读。
     */
    @Transactional(readOnly = true)
    public List<ResourceInventoryView> listInventories(String incidentKey) {
        Incident incident = requireIncident(incidentKey);
        return aid.listInventories(incident.id()).stream()
                .map(i -> toInventoryView(incident.incidentKey(), i)).toList();
    }

    /**
     * 征调提交：原子扣减来源可用量并为目标登记 ACTIVE 租约（资源、数量、UTC 到期时刻）。
     * 来源不足、资源已被排他租约占用（或排他征调时仍有未结出租约）返回 422，双侧不留部分变更。
     * requestId 全局幂等：同键同参重放首次双侧账务响应，改数量或目标等任一参数返回 409。
     */
    @Transactional
    public AidLeaseDetailView requestAid(String actor, AidRequestRequest req) {
        String requestId = requireText(req.requestId(), "requestId");
        String sourceKey = requireText(req.sourceIncidentKey(), "sourceIncidentKey");
        String targetKey = requireText(req.targetIncidentKey(), "targetIncidentKey");
        String resourceCode = requireText(req.resourceCode(), "resourceCode");
        Integer quantity = req.quantity();
        if (quantity == null || quantity <= 0) {
            throw ApiException.badRequest("quantity 必须为正整数");
        }
        boolean exclusive = Boolean.TRUE.equals(req.exclusive());
        if (req.expiresAt() == null) {
            throw ApiException.badRequest("expiresAt 不能为空");
        }
        Instant expiresAt = req.expiresAt();
        if (!expiresAt.isAfter(now())) {
            throw ApiException.badRequest("expiresAt 必须晚于当前 UTC 时刻");
        }
        if (sourceKey.equals(targetKey)) {
            throw ApiException.badRequest("来源事件与目标事件不能相同");
        }
        requireText(actor, "actor");
        // 按事件 id 升序加锁，避免双向并发征调时交叉持锁形成死锁
        lockSourceAndTarget(sourceKey, targetKey);
        return runIdempotent(requestId, "aid_request",
                hash(sourceKey, targetKey, resourceCode, String.valueOf(quantity),
                        String.valueOf(exclusive), expiresAt.toString()),
                AidLeaseDetailView.class, () -> {
                    Incident source = incidents.lockByKey(sourceKey).orElseThrow();
                    Incident target = incidents.lockByKey(targetKey).orElseThrow();
                    ResourceInventory inventory = aid.lockInventory(source.id(), resourceCode)
                            .orElseThrow(() -> ApiException.illegalTransition(
                                    "来源事件可用量不足：资源 " + resourceCode + " 未登记"));
                    List<AidLease> activeLeases = aid.findActiveLeases(source.id(), resourceCode);
                    if (activeLeases.stream().anyMatch(AidLease::exclusive)) {
                        throw ApiException.illegalTransition(
                                "资源 " + resourceCode + " 已被排他租约占用: requestId="
                                        + activeLeases.stream().filter(AidLease::exclusive)
                                                .findFirst().orElseThrow().requestId());
                    }
                    if (exclusive && !activeLeases.isEmpty()) {
                        throw ApiException.illegalTransition(
                                "资源 " + resourceCode + " 仍有未结出租约，不能排他征调");
                    }
                    Instant now = now();
                    if (inventory.availableQuantity() < quantity) {
                        throw ApiException.illegalTransition(
                                "来源事件可用量不足：资源 " + resourceCode + " 剩余 "
                                        + inventory.availableQuantity() + "，请求 " + quantity);
                    }
                    int deducted = aid.decreaseAvailable(source.id(), resourceCode, quantity, now);
                    if (deducted == 0) {
                        throw ApiException.illegalTransition(
                                "来源事件可用量不足：资源 " + resourceCode + " 请求 " + quantity);
                    }
                    aid.insertLease(new AidLease(0L, requestId, source.id(), target.id(), resourceCode,
                            quantity, 0, exclusive, AidLeaseStatus.ACTIVE, expiresAt, actor, now, null));
                    return toDetail(aid.findLeaseByRequest(requestId).orElseThrow());
                });
    }

    /**
     * 租约归还：按本次实际数量累计结算剩余未还量，记录归还人与服务端 UTC 时刻。
     * 超过未归还量（含对已关闭租约重复归还）返回 422，不写流水、不释放额度。
     * 恰好还完时租约关闭；可用量在每次归还时按实际数量加回，关闭不再释放，杜绝重复释放。
     * commandKey 幂等：同键同参重放首次响应，改参返回 409。
     */
    @Transactional
    public AidLeaseDetailView returnLease(String requestId, String actor, AidReturnRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        Integer quantity = req.quantity();
        if (quantity == null || quantity <= 0) {
            throw ApiException.badRequest("quantity 必须为正整数");
        }
        requireText(actor, "actor");
        AidLease lease = aid.findLeaseByRequest(requestId)
                .orElseThrow(() -> ApiException.notFound("租约不存在: " + requestId));
        return runIdempotent(commandKey, "aid_return",
                hash(requestId, actor, String.valueOf(quantity)), AidLeaseDetailView.class, () -> {
                    AidLease locked = aid.lockLeaseByRequest(requestId).orElseThrow();
                    if (locked.status() == AidLeaseStatus.CLOSED) {
                        throw ApiException.illegalTransition(
                                "租约已结清关闭，不能重复归还: " + requestId);
                    }
                    if (quantity > locked.outstandingQuantity()) {
                        throw ApiException.illegalTransition("归还数量超过未归还量：待还 "
                                + locked.outstandingQuantity() + "，本次归还 " + quantity);
                    }
                    Instant now = now();
                    int accumulated = aid.accumulateReturned(locked.id(), quantity);
                    if (accumulated == 0) {
                        throw ApiException.illegalTransition(
                                "并发归还导致超出未归还量: " + requestId);
                    }
                    aid.insertReturn(new AidReturn(0L, locked.id(), quantity, actor, now, now));
                    aid.increaseAvailable(locked.sourceIncidentId(), locked.resourceCode(), quantity, now);
                    if (quantity == locked.outstandingQuantity()) {
                        int closed = aid.closeIfSettled(locked.id(), now);
                        if (closed == 0) {
                            throw new IllegalStateException("租约结清状态异常: " + requestId);
                        }
                    }
                    return toDetail(aid.lockLeaseByRequest(requestId).orElseThrow());
                });
    }

    /**
     * 查询单条征调租约的完整关联账务：租约本体（含首次来源/目标事件）、来源当前资源扣减账、
     * 分次归还流水。只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public AidLeaseDetailView getLease(String requestId) {
        AidLease lease = aid.findLeaseByRequest(requestId)
                .orElseThrow(() -> ApiException.notFound("租约不存在: " + requestId));
        return toDetail(lease);
    }

    /**
     * 查询与事件关联的全部租约（作为来源或目标），历史（含已 CLOSED）保留首次征调的
     * 来源与目标事件，按创建顺序返回。只读。
     */
    @Transactional(readOnly = true)
    public List<AidLeaseView> listLeasesByIncident(String incidentKey) {
        Incident incident = requireIncident(incidentKey);
        return aid.listLeasesByIncident(incident.id()).stream()
                .map(lease -> toLeaseView(lease,
                        incidents.findById(lease.sourceIncidentId()).map(Incident::incidentKey).orElse(null),
                        incidents.findById(lease.targetIncidentId()).map(Incident::incidentKey).orElse(null)))
                .toList();
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

    /**
     * 按事件 id 升序锁住来源与目标事件，固定加锁顺序以避免双向并发征调死锁。
     */
    private void lockSourceAndTarget(String sourceKey, String targetKey) {
        Incident source = incidents.findByKey(sourceKey)
                .orElseThrow(() -> ApiException.notFound("来源事件不存在: " + sourceKey));
        Incident target = incidents.findByKey(targetKey)
                .orElseThrow(() -> ApiException.notFound("目标事件不存在: " + targetKey));
        List<Incident> ordered = List.of(source, target).stream()
                .sorted(Comparator.comparingLong(Incident::id))
                .toList();
        for (Incident incident : ordered) {
            incidents.lockByKey(incident.incidentKey());
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
            byte[] out = digest.digest(String.join(SEP2, parts).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private ResourceInventoryView toInventoryView(String incidentKey, ResourceInventory inventory) {
        return new ResourceInventoryView(incidentKey, inventory.resourceCode(),
                inventory.availableQuantity(), inventory.createdAt(), inventory.updatedAt());
    }

    private AidLeaseView toLeaseView(AidLease lease, String sourceKey, String targetKey) {
        return new AidLeaseView(lease.requestId(), sourceKey, targetKey, lease.resourceCode(),
                lease.quantity(), lease.returnedQuantity(), lease.outstandingQuantity(),
                lease.exclusive(), lease.status().name(), lease.expiresAt(), lease.createdBy(),
                lease.createdAt(), lease.closedAt());
    }

    /**
     * 组装租约完整账务视图：补齐来源/目标事件键、来源当前可用量与全部分次归还流水。
     */
    private AidLeaseDetailView toDetail(AidLease lease) {
        Incident source = incidents.findById(lease.sourceIncidentId())
                .orElseThrow(() -> new IllegalStateException("来源事件缺失: " + lease.sourceIncidentId()));
        Incident target = incidents.findById(lease.targetIncidentId())
                .orElseThrow(() -> new IllegalStateException("目标事件缺失: " + lease.targetIncidentId()));
        AidLeaseView view = toLeaseView(lease, source.incidentKey(), target.incidentKey());
        ResourceInventoryView sourceInventory = aid.findInventory(source.id(), lease.resourceCode())
                .map(i -> toInventoryView(source.incidentKey(), i))
                .orElseGet(() -> new ResourceInventoryView(source.incidentKey(), lease.resourceCode(),
                        0, null, null));
        List<AidReturnView> returns = aid.listReturns(lease.id()).stream()
                .map(r -> new AidReturnView(r.id(), r.quantity(), r.returnedBy(), r.returnedAt(),
                        r.createdAt()))
                .toList();
        return new AidLeaseDetailView(view, sourceInventory, returns);
    }
}
