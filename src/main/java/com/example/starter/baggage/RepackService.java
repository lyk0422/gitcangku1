package com.example.starter.baggage;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.example.starter.baggage.RepackDtos.ActivateRepackRequest;
import com.example.starter.baggage.RepackDtos.BagScanStatus;
import com.example.starter.baggage.RepackDtos.ContainerPackRequest;
import com.example.starter.baggage.RepackDtos.ContainerResponse;
import com.example.starter.baggage.RepackDtos.CreateRepackRequest;
import com.example.starter.baggage.RepackDtos.RepackActivatedResponse;
import com.example.starter.baggage.RepackDtos.RepackContainerSnapshot;
import com.example.starter.baggage.RepackDtos.RepackDetailResponse;
import com.example.starter.baggage.RepackDtos.RepackEvidenceEntry;
import com.example.starter.baggage.RepackDtos.RepackEvidenceResponse;
import com.example.starter.baggage.RepackDtos.RepackMovement;
import com.example.starter.baggage.RepackDtos.RepackPreviewResponse;
import com.example.starter.baggage.RepackDtos.RepackSourceView;
import com.example.starter.baggage.RepackDtos.RepackTargetView;
import com.example.starter.baggage.RepackDtos.TargetSourcePart;

/**
 * 行李容器重封服务：容器封装、重封单创建预览、双人激活与证据查询。
 * 激活在单事务内重读并锁定源容器与全部行李行，核对航段、装载/短卸/补到状态、版本、封签与完整集合，
 * 任一行李已卸载/已报短卸/已转交下一航段，或版本、封签、完整集合变化，整单 409/422 回滚，
 * 不会先开封或移动部分行李。成功后源容器统一 CLOSED_REPACKED、目标容器统一 SEALED，
 * 行李一次性改绑并在 bag_container_chain 逐项保留原容器链。
 */
@Service
public class RepackService {

    private static final String CONTAINER_SEALED = "SEALED";
    private static final String CONTAINER_CLOSED_REPACKED = "CLOSED_REPACKED";
    private static final String ORDER_PREVIEW = "PREVIEW";
    private static final String ORDER_ACTIVE = "ACTIVE";
    private static final String BAG_IN_TRANSIT = "IN_TRANSIT";
    private static final String BAG_SHORT_UNLOADED = "SHORT_UNLOADED";
    private static final String SCAN_IN_CONTAINER = "IN_CONTAINER";
    private static final String SCAN_UNLOADED = "UNLOADED";
    private static final String SCAN_SHORT_UNLOADED = "SHORT_UNLOADED";
    private static final String SCAN_NEXT_LEG = "HANDED_OVER_NEXT_LEG";

    private final JdbcTemplate jdbcTemplate;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    /** 可控时钟，默认系统 UTC 时钟，测试可替换。 */
    private Supplier<java.time.Instant> clock = java.time.Instant::now;

    public RepackService(JdbcTemplate jdbcTemplate,
                         IdempotencyService idempotencyService,
                         ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    /** 替换时钟（测试使用），所有登记时刻均取该时钟的 UTC 时刻。 */
    void setClock(Supplier<java.time.Instant> clock) {
        this.clock = clock;
    }

    /** 封装容器：把同一航段已装载且未在有效容器中的行李封入全局唯一编号与封签的 SEALED 容器。 */
    public ContainerResponse packContainer(ContainerPackRequest request) {
        List<String> sortedBags = sortedCopy(request.bagTags());
        PackPayload payload = new PackPayload(request.containerNo(), request.legId(),
                request.handoverPoint(), request.sealNo(), sortedBags);
        return idempotencyService.execute(request.requestId(), "PACK_CONTAINER", 201,
                payload, ContainerResponse.class, () -> doPackContainer(request, sortedBags));
    }

    /** 创建重封单：只预览清单差异与扫描状态，不移动任何行李；容器与 bagTag 换序视为同参。 */
    public RepackPreviewResponse createRepack(CreateRepackRequest request) {
        CreatePayload payload = normalizeCreate(request);
        return idempotencyService.execute(request.requestId(), "CREATE_REPACK", 201,
                payload, RepackPreviewResponse.class, () -> doCreateRepack(request, payload));
    }

    /** 双人激活：操作人与复核人不同，单事务核对并重封；失败整体回滚不占幂等键。 */
    public RepackActivatedResponse activateRepack(String repackKey, ActivateRepackRequest request) {
        ActivatePayload payload = new ActivatePayload(repackKey, request.operatorId(), request.reviewerId());
        return idempotencyService.execute(request.requestId(), "ACTIVATE_REPACK", 200,
                payload, RepackActivatedResponse.class, () -> doActivateRepack(repackKey, request));
    }

    /** 重封单详情查询。 */
    public RepackDetailResponse getRepack(String repackKey) {
        OrderRow order = findOrder(repackKey);
        if (order == null) {
            throw ApiException.notFound("重封单不存在: " + repackKey);
        }
        List<String> sourceNos = readStringList(order.sourceContainers());
        List<Integer> sourceVersions = readIntList(order.sourceVersions());
        List<String> oldSeals = readStringList(order.oldSeals());
        List<String> targetNos = readStringList(order.partitionTargets());
        List<String> newSeals = readStringList(order.partitionSeals());
        Map<String, List<String>> before = readStringListMap(order.beforeSnapshot());
        Map<String, List<String>> after = order.afterSnapshot() == null
                ? Map.of() : readStringListMap(order.afterSnapshot());

        List<RepackContainerSnapshot> sources = new ArrayList<>();
        for (int i = 0; i < sourceNos.size(); i++) {
            String no = sourceNos.get(i);
            String currentStatus = currentContainerStatus(no);
            sources.add(new RepackContainerSnapshot(no, oldSeals.get(i), currentStatus,
                    sourceVersions.get(i), before.getOrDefault(no, List.of())));
        }
        List<RepackContainerSnapshot> targets = new ArrayList<>();
        for (int i = 0; i < targetNos.size(); i++) {
            String no = targetNos.get(i);
            String status = ORDER_ACTIVE.equals(order.status()) ? currentContainerStatus(no) : ORDER_PREVIEW;
            Integer version = ORDER_ACTIVE.equals(order.status()) ? currentContainerVersion(no) : null;
            List<String> bags = ORDER_ACTIVE.equals(order.status())
                    ? after.getOrDefault(no, List.of()) : List.of();
            targets.add(new RepackContainerSnapshot(no, newSeals.get(i), status, version, bags));
        }
        return new RepackDetailResponse(order.repackKey(), order.status(), order.legId(),
                order.handoverPoint(), order.operatorId(), order.reviewerId(),
                sources, targets, readMovements(order.previewDifferences()),
                order.createdAt() == null ? null : order.createdAt().toString(),
                order.activatedAt() == null ? null : order.activatedAt().toString());
    }

    /** 证据查询：只读，明细按 bagTag 稳定排序。 */
    public RepackEvidenceResponse getEvidence(String repackKey) {
        OrderRow order = findOrder(repackKey);
        if (order == null) {
            throw ApiException.notFound("重封单不存在: " + repackKey);
        }
        List<RepackEvidenceEntry> entries = jdbcTemplate.query(
                "SELECT bag_tag, source_container, target_container, old_seal, new_seal"
                        + " FROM repack_evidence WHERE repack_key = ? ORDER BY bag_tag",
                (rs, rowNum) -> new RepackEvidenceEntry(rs.getString("bag_tag"),
                        rs.getString("source_container"), rs.getString("target_container"),
                        rs.getString("old_seal"), rs.getString("new_seal")),
                repackKey);
        return new RepackEvidenceResponse(order.repackKey(), order.status(), order.legId(),
                order.handoverPoint(), order.operatorId(), order.reviewerId(), entries);
    }

    /** 容器查询：返回容器头信息与当前绑定的完整 bagTag 集合（排序）。 */
    public ContainerResponse getContainer(String containerNo) {
        ContainerRow container = findContainer(containerNo);
        if (container == null) {
            throw ApiException.notFound("容器不存在: " + containerNo);
        }
        return toContainerResponse(container);
    }

    private ContainerResponse doPackContainer(ContainerPackRequest request, List<String> sortedBags) {
        if (findContainer(request.containerNo()) != null) {
            throw ApiException.conflict("容器编号已存在: " + request.containerNo());
        }
        if (sealNoInUse(request.sealNo())) {
            throw ApiException.conflict("封签号已被使用: " + request.sealNo());
        }
        if (findLegId(request.legId()) == null) {
            throw ApiException.unprocessable("航段不存在: " + request.legId());
        }
        // 先锁航段行，与装载/到达/补到路径在同一层次串行化
        lockLeg(request.legId());
        if (new java.util.HashSet<>(sortedBags).size() != sortedBags.size()) {
            throw ApiException.unprocessable("封装行李 bagTag 不得重复");
        }
        for (String bagTag : sortedBags) {
            BagRow bag = lockBag(bagTag);
            if (bag == null) {
                throw ApiException.unprocessable("行李不存在: " + bagTag);
            }
            if (!BAG_IN_TRANSIT.equals(bag.status())) {
                throw ApiException.unprocessable(
                        "行李 " + bagTag + " 状态为 " + bag.status() + "，不能封装");
            }
            if (!request.legId().equals(bag.loadedLegId())) {
                throw ApiException.unprocessable(
                        "行李 " + bagTag + " 未装载到航段 " + request.legId() + "，不能封入该航段容器");
            }
            if (bag.containerNo() != null) {
                throw ApiException.conflict(
                        "行李 " + bagTag + " 已在有效容器 " + bag.containerNo() + " 中");
            }
        }
        insertContainerRow(request.containerNo(), request.legId(), request.handoverPoint(),
                request.sealNo(), CONTAINER_SEALED);
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.get(), ZoneOffset.UTC);
        for (String bagTag : sortedBags) {
            jdbcTemplate.update("UPDATE bag SET container_no = ? WHERE bag_tag = ?",
                    request.containerNo(), bagTag);
            appendChain(bagTag, request.containerNo(), request.legId(), now);
        }
        return toContainerResponse(lockContainer(request.containerNo()));
    }

    private RepackPreviewResponse doCreateRepack(CreateRepackRequest request, CreatePayload payload) {
        if (findOrder(request.repackKey()) != null) {
            throw ApiException.conflict("重封单已存在: " + request.repackKey());
        }
        // 源容器：存在、SEALED、同航段、同交接点，版本与旧封签与提交一致
        List<ContainerRow> sources = new ArrayList<>();
        String legId = null;
        String handoverPoint = null;
        for (SourcePayload sp : payload.sources()) {
            ContainerRow container = findContainer(sp.containerNo());
            if (container == null) {
                throw ApiException.notFound("源容器不存在: " + sp.containerNo());
            }
            if (!CONTAINER_SEALED.equals(container.status())) {
                throw ApiException.unprocessable(
                        "源容器 " + sp.containerNo() + " 状态为 " + container.status() + "，必须为 SEALED");
            }
            if (container.version() != sp.expectedVersion()) {
                throw ApiException.conflict(
                        "源容器 " + sp.containerNo() + " 版本冲突: 期望 " + sp.expectedVersion()
                                + "，当前 " + container.version());
            }
            if (!container.sealNo().equals(sp.sealNo())) {
                throw ApiException.unprocessable(
                        "源容器 " + sp.containerNo() + " 封签不匹配: 提交 " + sp.sealNo()
                                + "，当前 " + container.sealNo());
            }
            if (legId == null) {
                legId = container.legId();
                handoverPoint = container.handoverPoint();
            } else if (!legId.equals(container.legId())
                    || !handoverPoint.equals(container.handoverPoint())) {
                throw ApiException.unprocessable("全部源容器必须属于同一航段且同一交接点");
            }
            sources.add(container);
        }

        // 源清单并集：以行李当前 container_no 为准，逐件锁定读取
        Map<String, String> bagToSource = new TreeMap<>();
        Map<String, List<String>> beforeSnapshot = new TreeMap<>();
        for (ContainerRow container : sources) {
            List<String> bags = listBagsInContainer(container.containerNo());
            beforeSnapshot.put(container.containerNo(), bags);
            for (String bagTag : bags) {
                String previous = bagToSource.put(bagTag, container.containerNo());
                if (previous != null) {
                    throw new IllegalStateException("行李 " + bagTag + " 同时归属多个有效容器，数据异常");
                }
            }
        }
        if (bagToSource.size() < 2 || bagToSource.size() > 200) {
            throw ApiException.unprocessable("重封行李总数必须为 2~200，当前 " + bagToSource.size());
        }

        // 目标容器：编号/封签互不重复且全局不存在，分区必须是源清单的精确分区
        Map<String, TargetPayload> targetMap = new LinkedHashMap<>();
        Set<String> allTargetBags = new java.util.TreeSet<>();
        for (TargetPayload tp : payload.targets()) {
            if (targetMap.putIfAbsent(tp.containerNo(), tp) != null) {
                throw ApiException.unprocessable("目标容器编号重复: " + tp.containerNo());
            }
            if (findContainer(tp.containerNo()) != null) {
                throw ApiException.conflict("目标容器编号已存在: " + tp.containerNo());
            }
            for (TargetPayload other : payload.targets()) {
                if (other != tp && other.newSealNo().equals(tp.newSealNo())) {
                    throw ApiException.unprocessable("目标新封签号重复: " + tp.newSealNo());
                }
            }
            if (sealNoInUse(tp.newSealNo())) {
                throw ApiException.conflict("新封签号已被使用: " + tp.newSealNo());
            }
            if (new java.util.HashSet<>(tp.bagTags()).size() != tp.bagTags().size()) {
                throw ApiException.unprocessable(
                        "目标容器 " + tp.containerNo() + " 内 bagTag 不得重复");
            }
            for (String bagTag : tp.bagTags()) {
                if (!allTargetBags.add(bagTag)) {
                    throw ApiException.unprocessable(
                            "行李 " + bagTag + " 出现在多个目标容器，分区必须互斥");
                }
            }
        }
        if (!allTargetBags.equals(bagToSource.keySet())) {
            Set<String> missing = new java.util.TreeSet<>(bagToSource.keySet());
            missing.removeAll(allTargetBags);
            Set<String> extra = new java.util.TreeSet<>(allTargetBags);
            extra.removeAll(bagToSource.keySet());
            throw ApiException.unprocessable(
                    "目标集合必须是源清单的精确分区；遗漏: " + missing + "，外部行李: " + extra);
        }

        // 清单差异：每件行李的源容器 -> 目标容器去向
        Map<String, String> bagToTarget = new TreeMap<>();
        for (TargetPayload tp : payload.targets()) {
            for (String bagTag : tp.bagTags()) {
                bagToTarget.put(bagTag, tp.containerNo());
            }
        }
        List<RepackMovement> movements = bagToSource.keySet().stream()
                .map(bagTag -> new RepackMovement(bagTag, bagToSource.get(bagTag), bagToTarget.get(bagTag)))
                .sorted((a, b) -> {
                    int byTarget = a.targetContainerNo().compareTo(b.targetContainerNo());
                    return byTarget != 0 ? byTarget : a.bagTag().compareTo(b.bagTag());
                })
                .toList();

        // 各行李当前扫描状态快照
        Map<String, ScanSnapshot> scanStatuses = new TreeMap<>();
        List<BagScanStatus> scanViews = new ArrayList<>();
        for (String bagTag : bagToSource.keySet()) {
            BagRow bag = findBag(bagTag);
            String scanStatus = deriveScanStatus(bag, legId);
            scanStatuses.put(bagTag, new ScanSnapshot(scanStatus, bag.loadedLegId(),
                    bag.status(), bag.currentLocation()));
            scanViews.add(new BagScanStatus(bagTag, scanStatus, bag.loadedLegId(),
                    bag.status(), bag.currentLocation()));
        }

        java.sql.Timestamp createdTs = new java.sql.Timestamp(clock.get().toEpochMilli());
        insertOrderRow(
                request.repackKey(), legId, handoverPoint, ORDER_PREVIEW,
                writeJson(payload.sources().stream().map(SourcePayload::containerNo).toList()),
                writeJson(payload.sources().stream().map(SourcePayload::expectedVersion).toList()),
                writeJson(payload.sources().stream().map(SourcePayload::sealNo).toList()),
                writeJson(payload.targets().stream().map(TargetPayload::containerNo).toList()),
                writeJson(payload.targets().stream().map(TargetPayload::newSealNo).toList()),
                writeJson(payload.targets().stream().map(TargetPayload::bagTags).toList()),
                writeJson(beforeSnapshot), writeJson(movements), writeJson(scanStatuses),
                request.requestId(), createdTs);

        List<RepackSourceView> sourceViews = new ArrayList<>();
        for (int i = 0; i < payload.sources().size(); i++) {
            SourcePayload sp = payload.sources().get(i);
            sourceViews.add(new RepackSourceView(sp.containerNo(), sp.expectedVersion(), sp.sealNo(),
                    CONTAINER_SEALED, beforeSnapshot.get(sp.containerNo())));
        }
        List<RepackTargetView> targetViews = new ArrayList<>();
        for (TargetPayload tp : payload.targets()) {
            Map<String, List<String>> grouped = new TreeMap<>();
            for (String bagTag : tp.bagTags()) {
                grouped.computeIfAbsent(bagToSource.get(bagTag), key -> new ArrayList<>()).add(bagTag);
            }
            List<TargetSourcePart> parts = grouped.entrySet().stream()
                    .map(e -> new TargetSourcePart(e.getKey(), e.getValue()))
                    .toList();
            targetViews.add(new RepackTargetView(tp.containerNo(), tp.newSealNo(), tp.bagTags(), parts));
        }
        return new RepackPreviewResponse(request.repackKey(), ORDER_PREVIEW, legId, handoverPoint,
                sourceViews, targetViews, bagToSource.size(), movements, scanViews);
    }

    private RepackActivatedResponse doActivateRepack(String repackKey, ActivateRepackRequest request) {
        if (request.operatorId().equals(request.reviewerId())) {
            throw ApiException.unprocessable("操作人与复核人必须为两名不同人员");
        }
        OrderRow order = lockOrder(repackKey);
        if (order == null) {
            throw ApiException.notFound("重封单不存在: " + repackKey);
        }
        if (ORDER_ACTIVE.equals(order.status())) {
            throw ApiException.conflict("重封单 " + repackKey + " 已激活，不能重复激活");
        }
        // 顶层先锁共同航段，与装载/到达/补到路径在航段行上串行化，杜绝跨操作死锁
        LegRow leg = lockLeg(order.legId());

        List<String> sourceNos = readStringList(order.sourceContainers());
        List<Integer> sourceVersions = readIntList(order.sourceVersions());
        List<String> oldSeals = readStringList(order.oldSeals());
        List<String> targetNos = readStringList(order.partitionTargets());
        List<String> newSeals = readStringList(order.partitionSeals());
        Map<String, List<String>> beforeSnapshot = readStringListMap(order.beforeSnapshot());

        // 按编号排序后逐一加行锁，避免与另一重封/卸载/交接并发时死锁
        List<String> lockedSourceNos = new ArrayList<>(sourceNos);
        java.util.Collections.sort(lockedSourceNos);
        Map<String, ContainerRow> sourceRows = new LinkedHashMap<>();
        for (String no : lockedSourceNos) {
            sourceRows.put(no, lockContainer(no));
        }
        for (int i = 0; i < sourceNos.size(); i++) {
            String no = sourceNos.get(i);
            ContainerRow current = sourceRows.get(no);
            if (!CONTAINER_SEALED.equals(current.status())) {
                throw ApiException.conflict(
                        "源容器 " + no + " 当前状态为 " + current.status() + "，已不是 SEALED，整单拒绝");
            }
            if (current.version() != sourceVersions.get(i)) {
                throw ApiException.conflict(
                        "源容器 " + no + " 版本已变化: 创建时 " + sourceVersions.get(i)
                                + "，当前 " + current.version());
            }
            if (!current.sealNo().equals(oldSeals.get(i))) {
                throw ApiException.unprocessable(
                        "源容器 " + no + " 封签已变化: 创建时 " + oldSeals.get(i)
                                + "，当前 " + current.sealNo());
            }
        }

        // 目标编号与封签在创建后仍未被占用
        for (int i = 0; i < targetNos.size(); i++) {
            if (findContainer(targetNos.get(i)) != null) {
                throw ApiException.conflict("目标容器编号已被占用: " + targetNos.get(i));
            }
            Integer sealOwners = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM baggage_container WHERE seal_no = ?",
                    Integer.class, newSeals.get(i));
            if (sealOwners != null && sealOwners > 0) {
                throw ApiException.conflict("新封签号已被占用: " + newSeals.get(i));
            }
        }

        // 锁定全部行李行，重新核对航段、装载/短卸/补到状态与完整集合
        List<String> allBags = new ArrayList<>(beforeSnapshot.values().stream()
                .flatMap(List::stream).sorted().toList());
        Map<String, BagRow> bagRows = new LinkedHashMap<>();
        for (String bagTag : allBags) {
            BagRow bag = lockBag(bagTag);
            if (bag == null) {
                throw ApiException.unprocessable("行李不存在: " + bagTag);
            }
            bagRows.put(bagTag, bag);
        }
        // 完整集合：源容器当前绑定集合必须与创建前快照逐容器一致
        for (String no : sourceNos) {
            List<String> currentBags = listBagsInContainer(no);
            List<String> snapshotBags = beforeSnapshot.getOrDefault(no, List.of());
            if (!currentBags.equals(snapshotBags)) {
                throw ApiException.unprocessable(
                        "源容器 " + no + " 完整集合已变化: 创建时 " + snapshotBags + "，当前 " + currentBags);
            }
        }
        for (Map.Entry<String, BagRow> entry : bagRows.entrySet()) {
            String bagTag = entry.getKey();
            BagRow bag = entry.getValue();
            if (BAG_SHORT_UNLOADED.equals(bag.status())) {
                throw ApiException.unprocessable("行李 " + bagTag + " 已报短卸，整单拒绝");
            }
            if (bag.loadedLegId() == null) {
                throw ApiException.unprocessable("行李 " + bagTag + " 已卸载，整单拒绝");
            }
            if (!order.legId().equals(bag.loadedLegId())) {
                throw ApiException.unprocessable(
                        "行李 " + bagTag + " 已转交航段 " + bag.loadedLegId() + "，整单拒绝");
            }
            if (bag.containerNo() == null || !sourceNos.contains(bag.containerNo())) {
                throw ApiException.unprocessable(
                        "行李 " + bagTag + " 已不在重封源容器中（当前容器 "
                                + bag.containerNo() + "），整单拒绝");
            }
        }

        // 全部核对通过：一次性创建目标容器、改绑行李并保留容器链
        for (int i = 0; i < targetNos.size(); i++) {
            insertContainerRow(targetNos.get(i), order.legId(), order.handoverPoint(),
                    newSeals.get(i), CONTAINER_SEALED);
        }
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.get(), ZoneOffset.UTC);
        List<List<String>> partitionBags = readPartitionBags(order.partitionBagTags());
        Map<String, String> bagToTarget = new TreeMap<>();
        for (int i = 0; i < targetNos.size(); i++) {
            for (String bagTag : partitionBags.get(i)) {
                bagToTarget.put(bagTag, targetNos.get(i));
            }
        }
        for (String bagTag : allBags) {
            String targetNo = bagToTarget.get(bagTag);
            jdbcTemplate.update("UPDATE bag SET container_no = ? WHERE bag_tag = ?", targetNo, bagTag);
            appendChain(bagTag, targetNo, order.legId(), now);
        }
        for (String no : sourceNos) {
            jdbcTemplate.update(
                    "UPDATE baggage_container SET status = ?, version = version + 1 WHERE container_no = ?",
                    CONTAINER_CLOSED_REPACKED, no);
        }

        Map<String, List<String>> afterSnapshot = new TreeMap<>();
        for (int i = 0; i < targetNos.size(); i++) {
            afterSnapshot.put(targetNos.get(i), partitionBags.get(i));
        }
        Map<String, String> sourceSealByNo = new LinkedHashMap<>();
        for (int i = 0; i < sourceNos.size(); i++) {
            sourceSealByNo.put(sourceNos.get(i), oldSeals.get(i));
        }
        Map<String, String> targetSealByNo = new LinkedHashMap<>();
        for (int i = 0; i < targetNos.size(); i++) {
            targetSealByNo.put(targetNos.get(i), newSeals.get(i));
        }
        for (String bagTag : allBags) {
            String sourceNo = sourceNos.stream()
                    .filter(no -> beforeSnapshot.get(no).contains(bagTag))
                    .findFirst().orElseThrow();
            jdbcTemplate.update(
                    "INSERT INTO repack_evidence (repack_key, bag_tag, source_container, target_container,"
                            + " old_seal, new_seal) VALUES (?, ?, ?, ?, ?, ?)",
                    repackKey, bagTag, sourceNo, bagToTarget.get(bagTag),
                    sourceSealByNo.get(sourceNo), targetSealByNo.get(bagToTarget.get(bagTag)));
        }

        jdbcTemplate.update(
                "UPDATE repack_order SET status = ?, operator_id = ?, reviewer_id = ?,"
                        + " after_snapshot = ?, activated_request_id = ?, activated_at = ?"
                        + " WHERE repack_key = ?",
                ORDER_ACTIVE, request.operatorId(), request.reviewerId(),
                writeJson(afterSnapshot), request.requestId(), now, repackKey);

        List<RepackContainerSnapshot> sourceViews = new ArrayList<>();
        for (int i = 0; i < sourceNos.size(); i++) {
            String no = sourceNos.get(i);
            sourceViews.add(new RepackContainerSnapshot(no, oldSeals.get(i),
                    CONTAINER_CLOSED_REPACKED, sourceVersions.get(i) + 1,
                    beforeSnapshot.get(no)));
        }
        List<RepackContainerSnapshot> targetViews = new ArrayList<>();
        for (int i = 0; i < targetNos.size(); i++) {
            String no = targetNos.get(i);
            targetViews.add(new RepackContainerSnapshot(no, newSeals.get(i),
                    CONTAINER_SEALED, 1, afterSnapshot.get(no)));
        }
        List<RepackEvidenceEntry> evidence = allBags.stream()
                .map(bagTag -> {
                    String sourceNo = sourceNos.stream()
                            .filter(no -> beforeSnapshot.get(no).contains(bagTag))
                            .findFirst().orElseThrow();
                    return new RepackEvidenceEntry(bagTag, sourceNo, bagToTarget.get(bagTag),
                            sourceSealByNo.get(sourceNo),
                            targetSealByNo.get(bagToTarget.get(bagTag)));
                })
                .toList();
        return new RepackActivatedResponse(repackKey, ORDER_ACTIVE, request.operatorId(),
                request.reviewerId(), sourceViews, targetViews, evidence, now.toString());
    }

    /** 插入容器行，唯一约束兜底（并发创建同编号/同封签）转 409。 */
    private void insertContainerRow(String containerNo, String legId, String handoverPoint,
                                    String sealNo, String status) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO baggage_container (container_no, leg_id, handover_point, seal_no, status, version)"
                            + " VALUES (?, ?, ?, ?, ?, 1)",
                    containerNo, legId, handoverPoint, sealNo, status);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw ApiException.conflict("容器编号或封签号与已有容器冲突: " + containerNo + " / " + sealNo);
        }
    }

    /** 插入重封单行，repackKey 唯一约束兜底转 409。 */
    private void insertOrderRow(Object... args) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO repack_order (repack_key, leg_id, handover_point, status,"
                            + " source_containers, source_versions, old_seals, partition_targets,"
                            + " partition_seals, partition_bag_tags, before_snapshot, preview_differences,"
                            + " scan_statuses, created_request_id, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    args);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw ApiException.conflict("重封单已存在: " + args[0]);
        }
    }

    private String deriveScanStatus(BagRow bag, String legId) {
        if (BAG_SHORT_UNLOADED.equals(bag.status())) {
            return SCAN_SHORT_UNLOADED;
        }
        if (bag.loadedLegId() == null) {
            return SCAN_UNLOADED;
        }
        if (!legId.equals(bag.loadedLegId())) {
            return SCAN_NEXT_LEG;
        }
        return SCAN_IN_CONTAINER;
    }

    private void appendChain(String bagTag, String containerNo, String legId, OffsetDateTime enteredAt) {
        Integer maxSeq = jdbcTemplate.queryForObject(
                "SELECT MAX(seq) FROM bag_container_chain WHERE bag_tag = ?", Integer.class, bagTag);
        int nextSeq = maxSeq == null ? 0 : maxSeq + 1;
        jdbcTemplate.update(
                "INSERT INTO bag_container_chain (bag_tag, seq, container_no, leg_id, entered_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                bagTag, nextSeq, containerNo, legId, enteredAt);
    }

    private List<String> listBagsInContainer(String containerNo) {
        return jdbcTemplate.queryForList(
                "SELECT bag_tag FROM bag WHERE container_no = ? ORDER BY bag_tag",
                String.class, containerNo);
    }

    private boolean sealNoInUse(String sealNo) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM baggage_container WHERE seal_no = ?", Integer.class, sealNo);
        return count != null && count > 0;
    }

    private String findLegId(String legId) {
        List<String> rows = jdbcTemplate.queryForList(
                "SELECT leg_id FROM leg WHERE leg_id = ?", String.class, legId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 锁定航段行并返回其状态，用于与既有装载/到达流程统一加锁层次。 */
    private LegRow lockLeg(String legId) {
        List<LegRow> rows = jdbcTemplate.query(
                "SELECT leg_id, origin, destination, status, version FROM leg WHERE leg_id = ? FOR UPDATE",
                (rs, rowNum) -> new LegRow(rs.getString("leg_id"), rs.getString("origin"),
                        rs.getString("destination"), rs.getString("status"), rs.getInt("version")),
                legId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("航段不存在: " + legId);
        }
        return rows.get(0);
    }

    private ContainerRow findContainer(String containerNo) {
        List<ContainerRow> rows = jdbcTemplate.query(containerSelect(false), CONTAINER_MAPPER, containerNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private ContainerRow lockContainer(String containerNo) {
        List<ContainerRow> rows = jdbcTemplate.query(containerSelect(true), CONTAINER_MAPPER, containerNo);
        if (rows.isEmpty()) {
            throw ApiException.notFound("容器不存在: " + containerNo);
        }
        return rows.get(0);
    }

    private static String containerSelect(boolean forUpdate) {
        return "SELECT container_no, leg_id, handover_point, seal_no, status, version"
                + " FROM baggage_container WHERE container_no = ?" + (forUpdate ? " FOR UPDATE" : "");
    }

    private BagRow findBag(String bagTag) {
        List<BagRow> rows = jdbcTemplate.query(bagSelect(false), BAG_MAPPER, bagTag);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private BagRow lockBag(String bagTag) {
        List<BagRow> rows = jdbcTemplate.query(bagSelect(true), BAG_MAPPER, bagTag);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String bagSelect(boolean forUpdate) {
        return "SELECT bag_tag, current_location, next_leg_index, status, loaded_leg_id,"
                + " short_leg_id, short_destination, short_registered_at, container_no FROM bag"
                + " WHERE bag_tag = ?" + (forUpdate ? " FOR UPDATE" : "");
    }

    private OrderRow findOrder(String repackKey) {
        List<OrderRow> rows = jdbcTemplate.query(orderSelect(false), ORDER_MAPPER, repackKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private OrderRow lockOrder(String repackKey) {
        List<OrderRow> rows = jdbcTemplate.query(orderSelect(true), ORDER_MAPPER, repackKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String orderSelect(boolean forUpdate) {
        return "SELECT repack_key, leg_id, handover_point, status, operator_id, reviewer_id,"
                + " source_containers, source_versions, old_seals, partition_targets,"
                + " partition_seals, partition_bag_tags, before_snapshot, after_snapshot,"
                + " preview_differences, scan_statuses, created_request_id, activated_request_id,"
                + " created_at, activated_at FROM repack_order WHERE repack_key = ?"
                + (forUpdate ? " FOR UPDATE" : "");
    }

    private String currentContainerStatus(String containerNo) {
        ContainerRow row = findContainer(containerNo);
        return row == null ? null : row.status();
    }

    private Integer currentContainerVersion(String containerNo) {
        ContainerRow row = findContainer(containerNo);
        return row == null ? null : row.version();
    }

    private ContainerResponse toContainerResponse(ContainerRow container) {
        return new ContainerResponse(container.containerNo(), container.legId(),
                container.handoverPoint(), container.sealNo(), container.status(),
                container.version(), listBagsInContainer(container.containerNo()));
    }

    private static List<String> sortedCopy(List<String> values) {
        return values.stream().sorted().toList();
    }

    private CreatePayload normalizeCreate(CreateRepackRequest request) {
        List<SourcePayload> sources = request.sources().stream()
                .map(s -> new SourcePayload(s.containerNo(), s.expectedVersion(), s.sealNo()))
                .sorted(java.util.Comparator.comparing(SourcePayload::containerNo))
                .toList();
        List<TargetPayload> targets = request.targets().stream()
                .map(t -> new TargetPayload(t.containerNo(), t.newSealNo(), sortedCopy(t.bagTags())))
                .sorted(java.util.Comparator.comparing(TargetPayload::containerNo))
                .toList();
        return new CreatePayload(request.repackKey(), sources, targets);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("快照序列化失败", ex);
        }
    }

    private List<String> readStringList(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception ex) {
            throw new IllegalStateException("快照反序列化失败", ex);
        }
    }

    private List<Integer> readIntList(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<Integer>>() {
            });
        } catch (Exception ex) {
            throw new IllegalStateException("快照反序列化失败", ex);
        }
    }

    private List<List<String>> readPartitionBags(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<List<String>>>() {
            });
        } catch (Exception ex) {
            throw new IllegalStateException("分区快照反序列化失败", ex);
        }
    }

    private Map<String, List<String>> readStringListMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<TreeMap<String, List<String>>>() {
            });
        } catch (Exception ex) {
            throw new IllegalStateException("快照反序列化失败", ex);
        }
    }

    private List<RepackMovement> readMovements(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<RepackMovement>>() {
            });
        } catch (Exception ex) {
            throw new IllegalStateException("差异快照反序列化失败", ex);
        }
    }

    private record ContainerRow(String containerNo, String legId, String handoverPoint,
                                String sealNo, String status, int version) {
    }

    private record LegRow(String legId, String origin, String destination,
                          String status, int version) {
    }

    private record BagRow(String bagTag, String currentLocation, int nextLegIndex,
                          String status, String loadedLegId, String shortLegId,
                          String shortDestination, java.time.Instant shortRegisteredAt,
                          String containerNo) {
    }

    private record OrderRow(String repackKey, String legId, String handoverPoint, String status,
                            String operatorId, String reviewerId, String sourceContainers,
                            String sourceVersions, String oldSeals, String partitionTargets,
                            String partitionSeals, String partitionBagTags, String beforeSnapshot,
                            String afterSnapshot, String previewDifferences, String scanStatuses,
                            String createdRequestId, String activatedRequestId,
                            OffsetDateTime createdAt, OffsetDateTime activatedAt) {
    }

    private record ScanSnapshot(String scanStatus, String loadedLegId,
                                String bagStatus, String currentLocation) {
    }

    private record PackPayload(String containerNo, String legId, String handoverPoint,
                               String sealNo, List<String> bagTags) {
    }

    private record SourcePayload(String containerNo, int expectedVersion, String sealNo) {
    }

    private record TargetPayload(String containerNo, String newSealNo, List<String> bagTags) {
    }

    /** 创建重封幂等摘要：sources/targets 已排序、bagTags 已排序，换序不视为异参。 */
    private record CreatePayload(String repackKey, List<SourcePayload> sources,
                                 List<TargetPayload> targets) {
    }

    /** 激活幂等摘要。 */
    private record ActivatePayload(String repackKey, String operatorId, String reviewerId) {
    }

    private static final org.springframework.jdbc.core.RowMapper<ContainerRow> CONTAINER_MAPPER =
            (rs, rowNum) -> new ContainerRow(rs.getString("container_no"), rs.getString("leg_id"),
                    rs.getString("handover_point"), rs.getString("seal_no"),
                    rs.getString("status"), rs.getInt("version"));

    private static final org.springframework.jdbc.core.RowMapper<BagRow> BAG_MAPPER = (rs, rowNum) -> {
        java.sql.Timestamp ts = rs.getTimestamp("short_registered_at");
        return new BagRow(rs.getString("bag_tag"), rs.getString("current_location"),
                rs.getInt("next_leg_index"), rs.getString("status"), rs.getString("loaded_leg_id"),
                rs.getString("short_leg_id"), rs.getString("short_destination"),
                ts == null ? null : ts.toInstant(), rs.getString("container_no"));
    };

    private static final org.springframework.jdbc.core.RowMapper<OrderRow> ORDER_MAPPER =
            (rs, rowNum) -> {
                java.sql.Timestamp created = rs.getTimestamp("created_at");
                return new OrderRow(rs.getString("repack_key"), rs.getString("leg_id"),
                        rs.getString("handover_point"), rs.getString("status"),
                        rs.getString("operator_id"), rs.getString("reviewer_id"),
                        rs.getString("source_containers"), rs.getString("source_versions"),
                        rs.getString("old_seals"), rs.getString("partition_targets"),
                        rs.getString("partition_seals"), rs.getString("partition_bag_tags"),
                        rs.getString("before_snapshot"), rs.getString("after_snapshot"),
                        rs.getString("preview_differences"), rs.getString("scan_statuses"),
                        rs.getString("created_request_id"), rs.getString("activated_request_id"),
                        created == null ? null : created.toInstant().atOffset(ZoneOffset.UTC),
                        rs.getObject("activated_at", OffsetDateTime.class));
            };
}
