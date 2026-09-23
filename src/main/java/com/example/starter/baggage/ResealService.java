package com.example.starter.baggage;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import com.example.starter.baggage.ContainerDtos.BagScanState;
import com.example.starter.baggage.ContainerDtos.ConfirmResealRequest;
import com.example.starter.baggage.ContainerDtos.CreateResealOrderRequest;
import com.example.starter.baggage.ContainerDtos.ResealOrderResponse;
import com.example.starter.baggage.ContainerDtos.ResealPreview;
import com.example.starter.baggage.ContainerDtos.ResealSnapshotItem;
import com.example.starter.baggage.ContainerDtos.ResealSourceRequest;
import com.example.starter.baggage.ContainerDtos.ResealSourceView;
import com.example.starter.baggage.ContainerDtos.ResealTargetRequest;
import com.example.starter.baggage.ContainerDtos.ResealTargetView;
import com.example.starter.baggage.ContainerDtos.SourceManifestView;
import com.example.starter.baggage.ContainerService.ContainerRow;

/**
 * 容器重封单：创建时只预览清单差异与各行李当前扫描状态；
 * 激活须操作人与复核人两名不同人员确认，并在同一事务内重新读取源容器、
 * 行李航段、装载/短卸/补到状态及封签，任一变化整单 409/422 回滚，
 * 不先开封也不移动部分行李。成功后源容器统一 CLOSED_REPACKED、目标容器统一 SEALED，
 * 所有行李一次性改绑目标容器并逐项保留原容器链。
 */
@Service
public class ResealService {

    static final String ORDER_PENDING = "PENDING_CONFIRM";
    static final String ORDER_ACTIVATED = "ACTIVATED";

    private static final String BAG_SHORT_UNLOADED = "SHORT_UNLOADED";
    private static final String BAG_DELIVERED = "DELIVERED";

    private static final RowMapper<OrderRow> ORDER_MAPPER = (rs, rowNum) -> new OrderRow(
            rs.getString("repack_key"), rs.getString("operator_id"), rs.getString("reviewer_id"),
            rs.getString("leg_id"), rs.getString("handover_point"), rs.getString("status"),
            rs.getBoolean("operator_confirmed"), rs.getBoolean("reviewer_confirmed"),
            rs.getString("sources_json"), rs.getString("targets_json"),
            rs.getString("before_snapshot"), rs.getString("after_snapshot"),
            getInstant(rs, "activated_at"));

    private final JdbcTemplate jdbcTemplate;
    private final IdempotencyService idempotencyService;
    private final ContainerService containerService;
    private final ObjectMapper objectMapper;
    /** 可控时钟，默认系统 UTC 时钟，测试可替换。 */
    private Supplier<Instant> clock = Instant::now;

    public ResealService(JdbcTemplate jdbcTemplate,
                         IdempotencyService idempotencyService,
                         ContainerService containerService,
                         ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.idempotencyService = idempotencyService;
        this.containerService = containerService;
        this.objectMapper = objectMapper;
    }

    /** 替换时钟（测试使用），激活时刻取该时钟的 UTC 时刻。 */
    void setClock(Supplier<Instant> clock) {
        this.clock = clock;
    }

    /**
     * 创建重封单：仅做结构校验并保存待确认单，返回清单差异与各行李当前扫描状态预览；
     * 不改变任何容器或行李。容器与 bagTag 换序视为同参。
     */
    public ResealOrderResponse createOrder(CreateResealOrderRequest request) {
        ResealCreatePayload payload = new ResealCreatePayload(
                request.repackKey(), request.operatorId(), request.reviewerId(),
                normalizeSources(request.sources()), normalizeTargets(request.targets()));
        return idempotencyService.execute(request.requestId(), "RESEAL_CREATE", 201,
                payload, ResealOrderResponse.class, () -> doCreate(request));
    }

    /**
     * 双人确认：操作人与复核人两名不同人员各确认一次；
     * 第二人确认在同一事务内重新读取全部状态并原子激活，失败整单回滚。
     */
    public ResealOrderResponse confirm(String repackKey, ConfirmResealRequest request) {
        ConfirmPayload payload = new ConfirmPayload(repackKey, request.confirmerId());
        return idempotencyService.execute(request.requestId(), "RESEAL_CONFIRM", 200,
                payload, ResealOrderResponse.class, () -> doConfirm(repackKey, request));
    }

    /** 重封单证据查询：只读，所有集合稳定排序。 */
    public ResealOrderResponse getOrder(String repackKey) {
        OrderRow order = findOrder(repackKey);
        if (order == null) {
            throw ApiException.notFound("重封单不存在: " + repackKey);
        }
        return toResponse(order, null);
    }

    private ResealOrderResponse doCreate(CreateResealOrderRequest request) {
        if (request.operatorId().equals(request.reviewerId())) {
            throw ApiException.unprocessable("操作人与复核人必须为两名不同人员");
        }
        List<SourceRecord> sources = normalizeSources(request.sources());
        List<TargetRecord> targets = normalizeTargets(request.targets());
        if (sources.size() != new HashSet<>(sources.stream().map(SourceRecord::containerId).toList()).size()) {
            throw ApiException.unprocessable("源容器编号不得重复");
        }
        if (targets.size() != new HashSet<>(targets.stream().map(TargetRecord::containerId).toList()).size()) {
            throw ApiException.unprocessable("目标容器编号不得重复");
        }
        if (targets.size() != new HashSet<>(targets.stream().map(TargetRecord::newSealNo).toList()).size()) {
            throw ApiException.unprocessable("目标容器新封签不得重复");
        }
        Set<String> sourceIds = new HashSet<>(sources.stream().map(SourceRecord::containerId).toList());
        for (TargetRecord target : targets) {
            if (sourceIds.contains(target.containerId())) {
                throw ApiException.unprocessable("目标容器编号与源容器重叠: " + target.containerId());
            }
        }
        List<String> allTargetBags = targets.stream()
                .flatMap(target -> target.bagTags().stream())
                .toList();
        if (allTargetBags.size() != new HashSet<>(allTargetBags).size()) {
            throw ApiException.unprocessable("目标集合存在重复 bagTag");
        }
        if (allTargetBags.size() < 2 || allTargetBags.size() > 200) {
            throw ApiException.unprocessable("重封行李总数必须为 2~200，当前 " + allTargetBags.size());
        }
        if (findOrder(request.repackKey()) != null) {
            throw ApiException.conflict("repackKey 已存在: " + request.repackKey());
        }
        List<ContainerRow> containers = new ArrayList<>();
        for (SourceRecord source : sources) {
            ContainerRow container = containerService.findContainer(source.containerId());
            if (container == null) {
                throw ApiException.unprocessable("源容器不存在: " + source.containerId());
            }
            containers.add(container);
        }
        String legId = containers.get(0).legId();
        String handoverPoint = containers.get(0).handoverPoint();
        for (ContainerRow container : containers) {
            if (!legId.equals(container.legId())) {
                throw ApiException.unprocessable("源容器必须属于同一航段: " + container.containerId());
            }
            if (!handoverPoint.equals(container.handoverPoint())) {
                throw ApiException.unprocessable("源容器必须属于同一交接点: " + container.containerId());
            }
        }
        jdbcTemplate.update(
                "INSERT INTO reseal_order (repack_key, operator_id, reviewer_id, leg_id,"
                        + " handover_point, status, sources_json, targets_json)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                request.repackKey(), request.operatorId(), request.reviewerId(), legId,
                handoverPoint, ORDER_PENDING, writeJson(sources), writeJson(targets));
        ResealPreview preview = buildPreview(containers, targets);
        return toResponse(findOrder(request.repackKey()), preview);
    }

    /** 创建预览：源容器当前清单、目标相对源清单的缺失/外部袋号及各行李当前扫描状态。 */
    private ResealPreview buildPreview(List<ContainerRow> containers, List<TargetRecord> targets) {
        List<SourceManifestView> manifests = new ArrayList<>();
        Set<String> sourceUnion = new LinkedHashSet<>();
        for (ContainerRow container : containers) {
            List<String> bags = containerService.currentBags(container.containerId());
            manifests.add(new SourceManifestView(container.containerId(), container.status(),
                    container.version(), container.sealNo(), bags));
            sourceUnion.addAll(bags);
        }
        Set<String> targetUnion = new LinkedHashSet<>();
        for (TargetRecord target : targets) {
            targetUnion.addAll(target.bagTags());
        }
        List<String> missing = sourceUnion.stream()
                .filter(bagTag -> !targetUnion.contains(bagTag))
                .sorted()
                .toList();
        List<String> external = targetUnion.stream()
                .filter(bagTag -> !sourceUnion.contains(bagTag))
                .sorted()
                .toList();
        List<BagScanState> bagStates = targetUnion.stream()
                .sorted()
                .map(this::scanState)
                .toList();
        return new ResealPreview(manifests, missing, external, bagStates);
    }

    private BagScanState scanState(String bagTag) {
        List<BagScanState> rows = jdbcTemplate.query(
                "SELECT status, loaded_leg_id, current_location FROM bag WHERE bag_tag = ?",
                (rs, rowNum) -> new BagScanState(bagTag, rs.getString("status"),
                        rs.getString("loaded_leg_id"), rs.getString("current_location")),
                bagTag);
        return rows.isEmpty() ? new BagScanState(bagTag, "NOT_FOUND", null, null) : rows.get(0);
    }

    private ResealOrderResponse doConfirm(String repackKey, ConfirmResealRequest request) {
        OrderRow order = lockOrder(repackKey);
        if (ORDER_ACTIVATED.equals(order.status())) {
            throw ApiException.conflict("重封单已激活: " + repackKey);
        }
        String confirmer = request.confirmerId();
        boolean operator = confirmer.equals(order.operatorId());
        boolean reviewer = confirmer.equals(order.reviewerId());
        if (!operator && !reviewer) {
            throw ApiException.unprocessable("确认人须为操作人或复核人: " + confirmer);
        }
        if (operator && order.operatorConfirmed()) {
            throw ApiException.unprocessable("操作人已确认，须由复核人完成双人确认");
        }
        if (reviewer && order.reviewerConfirmed()) {
            throw ApiException.unprocessable("复核人已确认，须由操作人完成双人确认");
        }
        jdbcTemplate.update(
                operator
                        ? "UPDATE reseal_order SET operator_confirmed = TRUE WHERE repack_key = ?"
                        : "UPDATE reseal_order SET reviewer_confirmed = TRUE WHERE repack_key = ?",
                repackKey);
        OrderRow updated = lockOrder(repackKey);
        if (updated.operatorConfirmed() && updated.reviewerConfirmed()) {
            activate(updated);
        }
        return toResponse(lockOrder(repackKey), null);
    }

    /**
     * 原子激活：在持有重封单行锁的事务内重新读取源容器、行李状态及封签，
     * 任一变化整单抛出 409/422 回滚；全部满足才统一改绑并保存前后快照。
     */
    private void activate(OrderRow order) {
        List<SourceRecord> sources = readJson(order.sourcesJson(), new TypeReference<>() {
        });
        List<TargetRecord> targets = readJson(order.targetsJson(), new TypeReference<>() {
        });
        List<ContainerRow> containers = new ArrayList<>();
        for (SourceRecord source : sources) {
            ContainerRow container = containerService.lockContainer(source.containerId());
            if (!ContainerService.CONTAINER_SEALED.equals(container.status())) {
                throw ApiException.unprocessable(
                        "源容器 " + container.containerId() + " 状态为 " + container.status()
                                + "，禁止重封");
            }
            if (container.version() != source.expectedVersion()) {
                throw ApiException.conflict("源容器 " + container.containerId() + " 版本冲突: 期望 "
                        + source.expectedVersion() + "，当前 " + container.version());
            }
            if (!source.sealNo().equals(container.sealNo())) {
                throw ApiException.conflict("源容器 " + container.containerId() + " 封签不匹配");
            }
            if (!order.legId().equals(container.legId())
                    || !order.handoverPoint().equals(container.handoverPoint())) {
                throw ApiException.unprocessable(
                        "源容器 " + container.containerId() + " 航段或交接点已变化");
            }
            containers.add(container);
        }
        List<SnapshotRecord> before = new ArrayList<>();
        Set<String> sourceUnion = new LinkedHashSet<>();
        for (ContainerRow container : containers) {
            List<String> bags = containerService.currentBags(container.containerId());
            before.add(new SnapshotRecord(container.containerId(), container.sealNo(), bags));
            sourceUnion.addAll(bags);
        }
        Set<String> targetUnion = new LinkedHashSet<>();
        for (TargetRecord target : targets) {
            targetUnion.addAll(target.bagTags());
        }
        if (targetUnion.size() != targets.stream().mapToInt(t -> t.bagTags().size()).sum()) {
            throw ApiException.unprocessable("目标集合存在重复 bagTag，不是源清单的精确分区");
        }
        if (!sourceUnion.equals(targetUnion)) {
            throw ApiException.unprocessable("目标集合不是源清单的精确分区：存在遗漏或外部行李");
        }
        List<String> sortedBags = sourceUnion.stream().sorted().toList();
        for (String bagTag : sortedBags) {
            validateBagForReseal(order, bagTag);
        }
        for (TargetRecord target : targets) {
            if (containerService.findContainer(target.containerId()) != null) {
                throw ApiException.conflict("目标容器编号已存在: " + target.containerId());
            }
            Integer sealCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM container WHERE seal_no = ?", Integer.class,
                    target.newSealNo());
            if (sealCount != null && sealCount > 0) {
                throw ApiException.conflict("封签号已存在: " + target.newSealNo());
            }
        }
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.get(), ZoneOffset.UTC);
        for (ContainerRow container : containers) {
            jdbcTemplate.update(
                    "UPDATE container SET status = ?, version = version + 1 WHERE container_id = ?",
                    ContainerService.CONTAINER_CLOSED_REPACKED, container.containerId());
        }
        List<SnapshotRecord> after = new ArrayList<>();
        for (TargetRecord target : targets) {
            List<String> bags = target.bagTags().stream().sorted().toList();
            jdbcTemplate.update(
                    "INSERT INTO container (container_id, leg_id, handover_point, status, version, seal_no)"
                            + " VALUES (?, ?, ?, ?, 1, ?)",
                    target.containerId(), order.legId(), order.handoverPoint(),
                    ContainerService.CONTAINER_SEALED, target.newSealNo());
            for (String bagTag : bags) {
                jdbcTemplate.update(
                        "UPDATE container_bag SET container_id = ? WHERE bag_tag = ?",
                        target.containerId(), bagTag);
                containerService.appendChain(bagTag, target.containerId(), now);
            }
            after.add(new SnapshotRecord(target.containerId(), target.newSealNo(), bags));
        }
        jdbcTemplate.update(
                "UPDATE reseal_order SET status = ?, before_snapshot = ?, after_snapshot = ?,"
                        + " activated_at = ? WHERE repack_key = ?",
                ORDER_ACTIVATED, writeJson(before), writeJson(after), now, order.repackKey());
    }

    /** 激活前逐件核对：已卸载、已报短卸、已转交下一航段或已交付的行李整单拒绝。 */
    private void validateBagForReseal(OrderRow order, String bagTag) {
        List<BagState> rows = jdbcTemplate.query(
                "SELECT status, loaded_leg_id FROM bag WHERE bag_tag = ? FOR UPDATE",
                (rs, rowNum) -> new BagState(rs.getString("status"), rs.getString("loaded_leg_id")),
                bagTag);
        if (rows.isEmpty()) {
            throw ApiException.unprocessable("行李不存在: " + bagTag);
        }
        BagState bag = rows.get(0);
        if (BAG_SHORT_UNLOADED.equals(bag.status())) {
            throw ApiException.unprocessable("行李 " + bagTag + " 已报短卸，整单拒绝");
        }
        if (BAG_DELIVERED.equals(bag.status())) {
            throw ApiException.unprocessable("行李 " + bagTag + " 已交付，整单拒绝");
        }
        if (!order.legId().equals(bag.loadedLegId())) {
            throw ApiException.unprocessable(
                    "行李 " + bagTag + " 已卸载或已转交下一航段，整单拒绝");
        }
    }

    private OrderRow findOrder(String repackKey) {
        List<OrderRow> rows = jdbcTemplate.query(orderSelect(false), ORDER_MAPPER, repackKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private OrderRow lockOrder(String repackKey) {
        List<OrderRow> rows = jdbcTemplate.query(orderSelect(true), ORDER_MAPPER, repackKey);
        if (rows.isEmpty()) {
            throw ApiException.notFound("重封单不存在: " + repackKey);
        }
        return rows.get(0);
    }

    private static String orderSelect(boolean forUpdate) {
        return "SELECT repack_key, operator_id, reviewer_id, leg_id, handover_point, status,"
                + " operator_confirmed, reviewer_confirmed, sources_json, targets_json,"
                + " before_snapshot, after_snapshot, activated_at FROM reseal_order"
                + " WHERE repack_key = ?" + (forUpdate ? " FOR UPDATE" : "");
    }

    private ResealOrderResponse toResponse(OrderRow order, ResealPreview preview) {
        List<SourceRecord> sources = readJson(order.sourcesJson(), new TypeReference<>() {
        });
        List<TargetRecord> targets = readJson(order.targetsJson(), new TypeReference<>() {
        });
        List<ResealSourceView> sourceViews = sources.stream()
                .map(s -> new ResealSourceView(s.containerId(), s.expectedVersion(), s.sealNo()))
                .toList();
        List<ResealTargetView> targetViews = targets.stream()
                .map(t -> new ResealTargetView(t.containerId(), t.newSealNo(),
                        t.bagTags().stream().sorted().toList()))
                .toList();
        List<ResealSnapshotItem> before = order.beforeSnapshot() == null ? null
                : toSnapshotItems(readJson(order.beforeSnapshot(), new TypeReference<>() {
                }));
        List<ResealSnapshotItem> after = order.afterSnapshot() == null ? null
                : toSnapshotItems(readJson(order.afterSnapshot(), new TypeReference<>() {
                }));
        return new ResealOrderResponse(order.repackKey(), order.status(), order.legId(),
                order.handoverPoint(), order.operatorId(), order.reviewerId(),
                order.operatorConfirmed(), order.reviewerConfirmed(),
                sourceViews, targetViews, preview, before, after,
                order.activatedAt() == null ? null : order.activatedAt().toString());
    }

    private static List<ResealSnapshotItem> toSnapshotItems(List<SnapshotRecord> records) {
        return records.stream()
                .map(r -> new ResealSnapshotItem(r.containerId(), r.sealNo(), r.bagTags()))
                .toList();
    }

    /** 规范化源容器：按容器号排序，换序视为同参。 */
    private static List<SourceRecord> normalizeSources(List<ResealSourceRequest> sources) {
        return sources.stream()
                .map(s -> new SourceRecord(s.containerId(), s.expectedVersion(), s.sealNo()))
                .sorted(Comparator.comparing(SourceRecord::containerId))
                .toList();
    }

    /** 规范化目标容器：按容器号排序、袋号排序，换序视为同参。 */
    private static List<TargetRecord> normalizeTargets(List<ResealTargetRequest> targets) {
        return targets.stream()
                .map(t -> new TargetRecord(t.containerId(), t.newSealNo(),
                        t.bagTags().stream().sorted().toList()))
                .sorted(Comparator.comparing(TargetRecord::containerId))
                .toList();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("重封单数据序列化失败", ex);
        }
    }

    private <T> T readJson(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception ex) {
            throw new IllegalStateException("重封单数据反序列化失败", ex);
        }
    }

    private static Instant getInstant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    /** 重封单行记录。 */
    private record OrderRow(String repackKey, String operatorId, String reviewerId,
                            String legId, String handoverPoint, String status,
                            boolean operatorConfirmed, boolean reviewerConfirmed,
                            String sourcesJson, String targetsJson,
                            String beforeSnapshot, String afterSnapshot, Instant activatedAt) {
    }

    /** 行李激活核对状态。 */
    private record BagState(String status, String loadedLegId) {
    }

    /** 源容器存储记录：容器号 + 期望版本 + 旧封签。 */
    private record SourceRecord(String containerId, int expectedVersion, String sealNo) {
    }

    /** 目标容器存储记录：新容器号 + 新封签 + 袋号集合（排序）。 */
    private record TargetRecord(String containerId, String newSealNo, List<String> bagTags) {
    }

    /** 清单快照存储记录。 */
    private record SnapshotRecord(String containerId, String sealNo, List<String> bagTags) {
    }

    /** 创建重封单幂等摘要参数：容器与袋号均已排序，换序视为同参。 */
    private record ResealCreatePayload(String repackKey, String operatorId, String reviewerId,
                                       List<SourceRecord> sources, List<TargetRecord> targets) {
    }

    /** 确认重封单幂等摘要参数。 */
    private record ConfirmPayload(String repackKey, String confirmerId) {
    }
}
