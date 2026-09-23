package com.example.starter.plan.service;

import com.example.starter.plan.model.CapacitySwap;
import com.example.starter.plan.model.CapacitySwapItem;
import com.example.starter.plan.model.CapacitySwapSnapshot;
import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.PlanStatus;
import com.example.starter.plan.model.SwapStatus;
import com.example.starter.plan.repo.CapacitySwapRepository;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.OccupancyView;
import com.example.starter.plan.web.dto.SwapConflictView;
import com.example.starter.plan.web.dto.SwapCreateRequest;
import com.example.starter.plan.web.dto.SwapItemRequest;
import com.example.starter.plan.web.dto.SwapPlanView;
import com.example.starter.plan.web.dto.SwapResponse;
import com.example.starter.plan.web.dto.SwapSnapshotView;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 多计划容量占用闭环原子交换业务。
 *
 * <p>调度员在同一运营日选择 2～20 个 PUBLISHED 计划，为每个计划提交 expectedVersion、
 * 当前占用段与目标占用段；占用段由 sectionId 与左闭右开 UTC 起止确定，列表顺序不影响语义。
 * 允许 A 释放给 B、B 释放给 C、C 再释放给 A 的闭环交换：冲突一律按全部参与计划的完整后态裁决，
 * 绝不因逐项校验时的临时冲突拒绝整组合法交换。
 *
 * <p>创建交换单只做预览，不落任何占用变更；激活在单事务内经全局发布锁串行化，重新读取全部
 * 计划与占用，校验提交集合完整、计划未重复、当前占用精确匹配、目标占用互不重复、交换后完整
 * 集合不与未参与的已发布计划冲突；任一不满足则 409/422 整体回滚，绝不释放部分旧占用。
 *
 * <p>激活按 requestId 幂等：同参重放首次响应（交换项换序视为同参），异参 409，失败不占键；
 * swapKey 跨请求唯一；与普通发布、取消、改签及另一交换单并发时按数据库提交顺序裁决，
 * 只能出现一个完整后态。
 */
@Service
public class CapacitySwapService {

    /** 运营日解释时区。 */
    public static final ZoneId OPERATION_ZONE = ZoneId.of("Asia/Shanghai");

    private static final String OP_SWAP_CREATE = "SWAP_CREATE";
    private static final String OP_SWAP_ACTIVATE = "SWAP_ACTIVATE";

    private final PlanRepository planRepo;
    private final CapacitySwapRepository swapRepo;
    private final IdempotencyRepository idemRepo;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public CapacitySwapService(PlanRepository planRepo, CapacitySwapRepository swapRepo,
                               IdempotencyRepository idemRepo, ObjectMapper objectMapper,
                               PlatformTransactionManager txManager) {
        this.planRepo = planRepo;
        this.swapRepo = swapRepo;
        this.idemRepo = idemRepo;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 规范化后的一条占用段（含列车编号），按完整四元组排序比较。
     */
    private record NormOcc(String trainNo, String sectionId, Instant startUtc, Instant endUtc)
            implements Comparable<NormOcc> {

        @Override
        public int compareTo(NormOcc o) {
            return Comparator.comparing(NormOcc::sectionId)
                    .thenComparing(NormOcc::startUtc)
                    .thenComparing(NormOcc::endUtc)
                    .thenComparing(NormOcc::trainNo)
                    .compare(this, o);
        }

        OccupancyView toView() {
            return new OccupancyView(trainNo, sectionId, startUtc, endUtc);
        }
    }

    /**
     * 规范化后的一个参与项。
     */
    private record NormItem(String scheduleKey, int expectedVersion,
                            List<NormOcc> current, List<NormOcc> target) {
    }

    /**
     * 创建交换单（仅预览）。
     *
     * <p>校验参与计划均存在且同运营日、占用段参数合法；随后持久化预览单与提交留痕，
     * 返回全部计划版本、交换前实际占用与按完整后态计算的冲突。不改变任何占用，
     * 计划状态/版本/当前占用是否与提交一致留待激活时在锁内裁决。
     */
    public SwapResponse createPreview(SwapCreateRequest request) {
        List<NormItem> items = normalize(request.items());

        // 先校验参与计划存在且同运营日（业务冲突，409），再校验占用段参数本身（400）。
        Map<String, DayPlan> plansByKey = new LinkedHashMap<>();
        for (NormItem item : items) {
            DayPlan plan = planRepo.findByKey(item.scheduleKey())
                    .orElseThrow(() -> notFound(item.scheduleKey()));
            if (!plan.opDate().equals(request.opDate())) {
                throw conflict("OP_DATE_MISMATCH",
                        "参与计划运营日必须与交换单一致: " + item.scheduleKey()
                                + " 计划日=" + plan.opDate() + " 交换单日=" + request.opDate());
            }
            plansByKey.put(item.scheduleKey(), plan);
        }
        validateOccupancyParams(items, request.opDate());

        String requestHash = createRequestHash(request.swapKey(), request.opDate(), items);
        Optional<SwapResponse> replay =
                replayIfPresent(OP_SWAP_CREATE, request.requestKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }

        String normalizedHash = hashItems(items);
        long now = System.currentTimeMillis();
        try {
            return tx.execute(status -> {
                long id = swapRepo.insertSwap(request.swapKey(), request.opDate(),
                        items.size(), normalizedHash, now);
                for (int i = 0; i < items.size(); i++) {
                    NormItem item = items.get(i);
                    swapRepo.insertItem(id, i, plansByKey.get(item.scheduleKey()).id(),
                            item.scheduleKey(), item.expectedVersion(),
                            toOccJson(item.current()), toOccJson(item.target()));
                }
                SwapResponse response = buildPreviewResponse(id, request.swapKey(),
                        request.opDate(), items, plansByKey);
                idemRepo.insert(OP_SWAP_CREATE, request.requestKey(), requestHash,
                        toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            // 唯一键冲突：幂等键已存在则按同参重放/异参 409 裁决；否则为 swapKey 跨请求冲突。
            return idemRepo.find(OP_SWAP_CREATE, request.requestKey())
                    .map(record -> {
                        if (!record.requestHash().equals(requestHash)) {
                            throw conflict("IDEMPOTENT_KEY_REUSED",
                                    "requestKey 已用于其他参数: " + request.requestKey());
                        }
                        return fromJson(record.responseJson(), SwapResponse.class);
                    })
                    .orElseThrow(() -> conflict("SWAP_KEY_EXISTS",
                            "swapKey 已存在: " + request.swapKey()));
        }
    }

    /**
     * 激活交换单。单事务内经全局发布锁串行化，重读全部计划与占用并按完整后态校验，
     * 通过后一次性替换全部占用、写入不可变前后快照、版本各加一、计划保持 PUBLISHED。
     * 任一校验失败整体回滚，不释放部分旧占用。
     */
    public SwapResponse activate(String swapKey, String requestKey) {
        CapacitySwap preview = swapRepo.findByKey(swapKey)
                .orElseThrow(() -> swapNotFound(swapKey));
        if (preview.status() == SwapStatus.ACTIVE) {
            return replayActivate(swapKey, requestKey, preview);
        }

        // 事务前快速裁决 requestId 复用：同参记录只可能对应已激活单（上面的分支已处理），
        // 此处若存在异参记录，直接 409，失败不占键也不做任何占用变更。
        String replayHash = requestHashForReplay(swapKey, preview.requestHash());
        idemRepo.find(OP_SWAP_ACTIVATE, requestKey).ifPresent(record -> {
            if (!record.requestHash().equals(replayHash)) {
                throw conflict("IDEMPOTENT_KEY_REUSED",
                        "requestKey 已用于其他参数: " + requestKey);
            }
        });

        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                CapacitySwap swap = swapRepo.findByKeyForUpdate(swapKey)
                        .orElseThrow(() -> swapNotFound(swapKey));
                if (swap.status() == SwapStatus.ACTIVE) {
                    return replayActivate(swapKey, requestKey, swap);
                }
                List<CapacitySwapItem> storedItems = swapRepo.findItems(swap.id());
                List<NormItem> committed = storedItems.stream()
                        .map(it -> new NormItem(it.scheduleKey(), it.expectedVersion(),
                                fromJsonList(it.currentJson()), fromJsonList(it.targetJson())))
                        .toList();

                validateSetCompleteAndDistinct(committed, swap);

                // 锁内重读全部计划与当前占用。
                Map<String, DayPlan> plansByKey = new LinkedHashMap<>();
                Map<String, List<Occupancy>> actualByKey = new LinkedHashMap<>();
                for (NormItem item : committed) {
                    DayPlan plan = planRepo.findByKeyForUpdate(item.scheduleKey())
                            .orElseThrow(() -> notFound(item.scheduleKey()));
                    if (!plan.opDate().equals(swap.opDate())) {
                        throw conflict("OP_DATE_MISMATCH",
                                "参与计划运营日必须与交换单一致: " + item.scheduleKey());
                    }
                    if (plan.status() != PlanStatus.PUBLISHED) {
                        throw conflict("PLAN_STATE_CONFLICT",
                                "仅已发布计划可参与交换，计划 " + item.scheduleKey()
                                        + " 当前状态: " + plan.status());
                    }
                    if (item.expectedVersion() != plan.version()) {
                        throw conflict("VERSION_CONFLICT",
                                "计划 " + item.scheduleKey() + " expectedVersion="
                                        + item.expectedVersion() + " 与当前版本 "
                                        + plan.version() + " 不一致");
                    }
                    plansByKey.put(item.scheduleKey(), plan);
                    actualByKey.put(item.scheduleKey(), planRepo.findOccupancies(plan.id()));
                }

                // 当前占用必须精确匹配（集合相等，与提交顺序无关）。
                for (NormItem item : committed) {
                    List<NormOcc> actual = actualByKey.get(item.scheduleKey()).stream()
                            .map(o -> new NormOcc(o.trainNo(), o.sectionId(),
                                    o.startUtc(), o.endUtc()))
                            .sorted()
                            .toList();
                    if (!actual.equals(item.current())) {
                        throw conflict("CURRENT_OCCUPANCY_MISMATCH",
                                "计划 " + item.scheduleKey()
                                        + " 当前占用与提交的当前占用段不精确匹配");
                    }
                }

                // 完整后态冲突：参与计划内部/互相 + 与未参与的已发布计划。
                List<SwapConflictView> internalConflicts = detectInternalConflicts(committed);
                if (!internalConflicts.isEmpty()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_CONFLICT",
                            "交换后目标占用存在冲突，交换未生效", toDetailMaps(internalConflicts));
                }
                List<SwapConflictView> externalConflicts =
                        detectExternalConflicts(swap.opDate(), committed, plansByKey);
                if (!externalConflicts.isEmpty()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_CONFLICT",
                            "交换后与未参与的已发布计划存在区段冲突，交换未生效",
                            toDetailMaps(externalConflicts));
                }

                // 全部通过：一次性替换占用、版本各加一、写不可变前后快照。
                long nowMillis = System.currentTimeMillis();
                for (int i = 0; i < committed.size(); i++) {
                    NormItem item = committed.get(i);
                    DayPlan plan = plansByKey.get(item.scheduleKey());
                    planRepo.replaceOccupancies(plan.id(), toOccupancies(plan.id(), item.target()));
                    planRepo.updateVersionAndStatus(plan.id(), plan.version() + 1,
                            PlanStatus.PUBLISHED, nowMillis);
                    swapRepo.insertSnapshot(swap.id(), "BEFORE", i, plan.id(),
                            plan.scheduleKey(), plan.version(),
                            toOccJson(item.current()), nowMillis);
                    swapRepo.insertSnapshot(swap.id(), "AFTER", i, plan.id(),
                            plan.scheduleKey(), plan.version() + 1,
                            toOccJson(item.target()), nowMillis);
                }
                swapRepo.markActive(swap.id(), nowMillis);

                SwapResponse response = buildEvidenceResponse(swap.id());
                idemRepo.insert(OP_SWAP_ACTIVATE, requestKey,
                        requestHashForReplay(swapKey, swap.requestHash()),
                        toJson(response), nowMillis);
                return response;
            });
        } catch (DuplicateKeyException e) {
            // 仅可能来自幂等键唯一约束：并发同 requestKey 或 requestKey 被异参复用。
            CapacitySwap current = swapRepo.findByKey(swapKey)
                    .orElseThrow(() -> swapNotFound(swapKey));
            return replayActivate(swapKey, requestKey, current);
        }
    }

    /**
     * 查询交换证据（只读，稳定排序）；交换单不存在返回 404。
     */
    public SwapResponse getEvidence(String swapKey) {
        CapacitySwap swap = swapRepo.findByKey(swapKey)
                .orElseThrow(() -> swapNotFound(swapKey));
        return buildEvidenceResponse(swap.id());
    }

    // ---------- 预览构建 ----------

    /**
     * 预览响应：plans 为全部参与计划的真实版本与交换前占用；conflicts 为按完整后态计算的
     * 全部冲突（计划内同车重叠、参与计划间目标重叠、与外部已发布计划区段冲突），稳定排序。
     */
    private SwapResponse buildPreviewResponse(long swapId, String swapKey, LocalDate opDate,
                                              List<NormItem> items,
                                              Map<String, DayPlan> plansByKey) {
        List<SwapPlanView> planViews = new ArrayList<>();
        for (NormItem item : items) {
            DayPlan plan = plansByKey.get(item.scheduleKey());
            List<OccupancyView> views = planRepo.findOccupancies(plan.id()).stream()
                    .map(o -> new NormOcc(o.trainNo(), o.sectionId(), o.startUtc(), o.endUtc()))
                    .sorted()
                    .map(NormOcc::toView)
                    .toList();
            planViews.add(new SwapPlanView(item.scheduleKey(), plan.version(), views));
        }

        List<SwapConflictView> conflicts = new ArrayList<>();
        conflicts.addAll(detectInternalConflicts(items));
        conflicts.addAll(detectExternalConflicts(opDate, items, plansByKey));
        conflicts.sort(conflictComparator());

        return new SwapResponse(swapKey, opDate, SwapStatus.PREVIEW.name(),
                List.copyOf(planViews), List.copyOf(conflicts), List.of(), List.of());
    }

    // ---------- 冲突检测（均按完整后态，支持闭环交换） ----------

    /**
     * 参与计划内部冲突：每个计划目标占用中同一列车的时间重叠（与既有发布口径一致，不分区段）；
     * 以及任意两个参与计划目标占用在同区段上的互相重叠。
     */
    private List<SwapConflictView> detectInternalConflicts(List<NormItem> items) {
        List<SwapConflictView> conflicts = new ArrayList<>();
        for (NormItem item : items) {
            Map<String, List<NormOcc>> byTrain = item.target().stream()
                    .collect(Collectors.groupingBy(NormOcc::trainNo,
                            LinkedHashMap::new, Collectors.toList()));
            byTrain.forEach((trainNo, list) -> {
                List<NormOcc> sorted = list.stream()
                        .sorted(Comparator.comparing(NormOcc::startUtc)).toList();
                for (int i = 1; i < sorted.size(); i++) {
                    NormOcc prev = sorted.get(i - 1);
                    NormOcc cur = sorted.get(i);
                    if (cur.startUtc().isBefore(prev.endUtc())) {
                        conflicts.add(new SwapConflictView("TRAIN_OVERLAP", cur.sectionId(),
                                item.scheduleKey(), item.scheduleKey(), trainNo,
                                cur.startUtc(), cur.endUtc()));
                    }
                }
            });
        }
        for (int i = 0; i < items.size(); i++) {
            for (int j = i + 1; j < items.size(); j++) {
                conflicts.addAll(mutualOverlaps(items.get(i), items.get(j)));
            }
        }
        return conflicts;
    }

    /**
     * 两个参与计划目标占用在同区段上的互相重叠，双向各报一条。
     */
    private List<SwapConflictView> mutualOverlaps(NormItem a, NormItem b) {
        List<SwapConflictView> conflicts = new ArrayList<>();
        for (NormOcc oa : a.target()) {
            for (NormOcc ob : b.target()) {
                if (oa.sectionId().equals(ob.sectionId())
                        && oa.startUtc().isBefore(ob.endUtc())
                        && ob.startUtc().isBefore(oa.endUtc())) {
                    conflicts.add(new SwapConflictView("INTERNAL_TARGET_CONFLICT", oa.sectionId(),
                            a.scheduleKey(), b.scheduleKey(), oa.trainNo(),
                            oa.startUtc(), oa.endUtc()));
                    conflicts.add(new SwapConflictView("INTERNAL_TARGET_CONFLICT", ob.sectionId(),
                            b.scheduleKey(), a.scheduleKey(), ob.trainNo(),
                            ob.startUtc(), ob.endUtc()));
                }
            }
        }
        return conflicts;
    }

    /**
     * 交换后完整集合与未参与的已发布计划的区段冲突：查询外部已发布时隙时排除全部参与计划
     * （其旧占用在完整后态中整体消失），再与每个参与计划的目标占用比对。
     */
    private List<SwapConflictView> detectExternalConflicts(
            LocalDate opDate, List<NormItem> items, Map<String, DayPlan> plansByKey) {
        TreeSet<String> sectionIds = items.stream()
                .flatMap(it -> it.target().stream())
                .map(NormOcc::sectionId)
                .collect(Collectors.toCollection(TreeSet::new));
        List<Long> participatingPlanIds = items.stream()
                .map(it -> plansByKey.get(it.scheduleKey()))
                .filter(p -> p != null)
                .map(DayPlan::id)
                .toList();
        var published = planRepo.findPublishedSlots(opDate, new ArrayList<>(sectionIds),
                participatingPlanIds);

        List<SwapConflictView> conflicts = new ArrayList<>();
        for (NormItem item : items) {
            for (NormOcc o : item.target()) {
                for (var slot : published) {
                    if (o.sectionId().equals(slot.sectionId())
                            && o.startUtc().isBefore(slot.endUtc())
                            && slot.startUtc().isBefore(o.endUtc())) {
                        conflicts.add(new SwapConflictView("SECTION_CONFLICT", o.sectionId(),
                                item.scheduleKey(), slot.scheduleKey(), o.trainNo(),
                                o.startUtc(), o.endUtc()));
                    }
                }
            }
        }
        return conflicts;
    }

    // ---------- 证据构建与重放 ----------

    /**
     * 从不可变快照与参与项构建证据响应（已激活时 plans 取 AFTER 快照；预览时取计划实时状态），
     * 全部列表稳定排序。
     */
    private SwapResponse buildEvidenceResponse(long swapId) {
        CapacitySwap swap = swapRepo.findById(swapId)
                .orElseThrow(() -> new IllegalStateException("交换单缺失: " + swapId));
        List<CapacitySwapItem> storedItems = swapRepo.findItems(swap.id());
        List<CapacitySwapSnapshot> snapshots = swapRepo.findSnapshots(swap.id());

        List<SwapSnapshotView> before = new ArrayList<>();
        List<SwapSnapshotView> after = new ArrayList<>();
        for (CapacitySwapSnapshot s : snapshots) {
            SwapSnapshotView view = new SwapSnapshotView(s.phase(), s.itemSeq(),
                    s.scheduleKey(), s.version(), toViews(fromJsonList(s.occupanciesJson())));
            if ("BEFORE".equals(s.phase())) {
                before.add(view);
            } else {
                after.add(view);
            }
        }
        before.sort(Comparator.comparingInt(SwapSnapshotView::itemSeq));
        after.sort(Comparator.comparingInt(SwapSnapshotView::itemSeq));

        List<SwapPlanView> planViews;
        if (swap.status() == SwapStatus.ACTIVE) {
            planViews = after.stream()
                    .map(v -> new SwapPlanView(v.scheduleKey(), v.version(), v.occupancies()))
                    .toList();
        } else {
            List<SwapPlanView> views = new ArrayList<>();
            for (CapacitySwapItem item : storedItems) {
                DayPlan plan = planRepo.findByKey(item.scheduleKey()).orElse(null);
                if (plan != null) {
                    List<OccupancyView> occViews = planRepo.findOccupancies(plan.id()).stream()
                            .map(o -> new NormOcc(o.trainNo(), o.sectionId(),
                                    o.startUtc(), o.endUtc()))
                            .sorted()
                            .map(NormOcc::toView)
                            .toList();
                    views.add(new SwapPlanView(item.scheduleKey(), plan.version(), occViews));
                } else {
                    views.add(new SwapPlanView(item.scheduleKey(),
                            item.expectedVersion(), List.of()));
                }
            }
            planViews = views;
        }

        return new SwapResponse(swap.swapKey(), swap.opDate(), swap.status().name(),
                List.copyOf(planViews), List.of(), List.copyOf(before), List.copyOf(after));
    }

    /**
     * 已激活交换单的激活请求裁决：同参返回首次成功响应，异参 409；未携带首次成功 requestKey 409。
     */
    private SwapResponse replayActivate(String swapKey, String requestKey, CapacitySwap swap) {
        String expectedHash = requestHashForReplay(swapKey, swap.requestHash());
        return idemRepo.find(OP_SWAP_ACTIVATE, requestKey)
                .map(record -> {
                    if (!record.requestHash().equals(expectedHash)) {
                        throw conflict("IDEMPOTENT_KEY_REUSED",
                                "requestKey 已用于其他参数: " + requestKey);
                    }
                    return fromJson(record.responseJson(), SwapResponse.class);
                })
                .orElseThrow(() -> conflict("SWAP_ALREADY_ACTIVE",
                        "交换单已激活且未携带首次成功的 requestKey: " + swapKey));
    }

    // ---------- 规范化、校验与序列化 ----------

    /**
     * 规范化请求：参与计划不允许重复；参与项按 scheduleKey 升序，当前/目标占用按四元组排序，
     * 因此交换项换序与占用段换序均得到相同规范化结果。
     */
    private List<NormItem> normalize(List<SwapItemRequest> rawItems) {
        List<NormItem> items = rawItems.stream()
                .map(it -> new NormItem(it.scheduleKey(), it.expectedVersion(),
                        sortOcc(it.currentOccupancies()), sortOcc(it.targetOccupancies())))
                .sorted(Comparator.comparing(NormItem::scheduleKey))
                .toList();
        long distinct = items.stream().map(NormItem::scheduleKey).distinct().count();
        if (distinct != items.size()) {
            throw conflict("DUPLICATE_PLAN_IN_SWAP", "交换单内计划不允许重复");
        }
        return items;
    }

    private List<NormOcc> sortOcc(List<OccupancyRequest> requests) {
        return requests.stream()
                .map(r -> new NormOcc(r.trainNo(), r.sectionId(), r.startUtc(), r.endUtc()))
                .sorted()
                .toList();
    }

    /**
     * 参数级校验：结束晚于开始；全部当前/目标占用落在交换单运营日 [dayStart, dayEnd) 内。
     */
    private void validateOccupancyParams(List<NormItem> items, LocalDate opDate) {
        Instant dayStart = opDate.atStartOfDay(OPERATION_ZONE).toInstant();
        Instant dayEnd = opDate.plusDays(1).atStartOfDay(OPERATION_ZONE).toInstant();
        for (NormItem item : items) {
            for (NormOcc o : item.current()) {
                checkOneOccupancy(opDate, dayStart, dayEnd, item.scheduleKey(), o);
            }
            for (NormOcc o : item.target()) {
                checkOneOccupancy(opDate, dayStart, dayEnd, item.scheduleKey(), o);
            }
        }
    }

    private void checkOneOccupancy(LocalDate opDate, Instant dayStart, Instant dayEnd,
                                   String scheduleKey, NormOcc o) {
        if (!o.endUtc().isAfter(o.startUtc())) {
            throw badRequest("计划 " + scheduleKey + " 存在结束时刻不晚于开始时刻的占用段");
        }
        LocalDate startDay = o.startUtc().atZone(OPERATION_ZONE).toLocalDate();
        LocalDate endDay = o.endUtc().minusNanos(1).atZone(OPERATION_ZONE).toLocalDate();
        if (!startDay.equals(endDay)) {
            throw badRequest("计划 " + scheduleKey + " 的占用段跨越运营日（Asia/Shanghai）");
        }
        if (o.startUtc().isBefore(dayStart) || o.endUtc().isAfter(dayEnd)) {
            throw badRequest("计划 " + scheduleKey + " 的占用段不在运营日 " + opDate + " 内");
        }
    }

    /**
     * 激活防御性复核：数量与预览单一致、无重复计划、数量在 2～20。
     */
    private void validateSetCompleteAndDistinct(List<NormItem> committed, CapacitySwap swap) {
        long distinct = committed.stream().map(NormItem::scheduleKey).distinct().count();
        if (distinct != committed.size()) {
            throw conflict("DUPLICATE_PLAN_IN_SWAP", "交换单内计划不允许重复");
        }
        if (committed.size() != swap.itemCount() || committed.size() < 2 || committed.size() > 20) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INCOMPLETE_SWAP_SET",
                    "提交集合不完整：参与计划数量必须为 2～20 且与预览单一致");
        }
    }

    private List<Occupancy> toOccupancies(long planId, List<NormOcc> occs) {
        List<Occupancy> result = new ArrayList<>(occs.size());
        for (int i = 0; i < occs.size(); i++) {
            NormOcc o = occs.get(i);
            result.add(new Occupancy(0L, planId, i, o.trainNo(), o.sectionId(),
                    o.startUtc(), o.endUtc()));
        }
        return result;
    }

    private List<OccupancyView> toViews(List<NormOcc> occs) {
        return occs.stream().map(NormOcc::toView).toList();
    }

    /**
     * 占用段列表的规范化 JSON（统一用公开视图形状，避免私有记录的序列化依赖）。
     */
    private String toOccJson(List<NormOcc> occs) {
        return toJson(toViews(occs));
    }

    /**
     * 规范化 hash：参与项按 scheduleKey、占用段按四元组排序后拼接，换序得到同一 hash。
     */
    private String hashItems(List<NormItem> items) {
        StringBuilder sb = new StringBuilder("CAPACITY_SWAP");
        for (NormItem item : items) {
            sb.append('\n').append(item.scheduleKey()).append('@').append(item.expectedVersion());
            appendOcc(sb, "C", item.current());
            appendOcc(sb, "T", item.target());
        }
        return sha256(sb.toString());
    }

    private void appendOcc(StringBuilder sb, String tag, List<NormOcc> occs) {
        for (NormOcc o : occs) {
            sb.append('\n').append(tag).append('|').append(o.trainNo()).append('|')
                    .append(o.sectionId()).append('|')
                    .append(o.startUtc().toEpochMilli()).append('|')
                    .append(o.endUtc().toEpochMilli());
        }
    }

    /**
     * 激活幂等记录的参数 hash：绑定 swapKey 与创建时的规范化请求 hash。
     */
    private String requestHashForReplay(String swapKey, String normalizedRequestHash) {
        return sha256(OP_SWAP_ACTIVATE + '\n' + swapKey + '\n' + normalizedRequestHash);
    }

    /**
     * 创建预览幂等记录的参数 hash：绑定 swapKey、运营日与规范化参与项（换序同 hash）。
     */
    private String createRequestHash(String swapKey, LocalDate opDate, List<NormItem> items) {
        return sha256(OP_SWAP_CREATE + '\n' + swapKey + '\n' + opDate + '\n' + hashItems(items));
    }

    /**
     * 幂等重放：存在记录且参数一致返回首次结果；参数不一致抛 409。
     */
    private Optional<SwapResponse> replayIfPresent(String opType, String requestKey, String hash) {
        return idemRepo.find(opType, requestKey).map(record -> {
            if (!record.requestHash().equals(hash)) {
                throw conflict("IDEMPOTENT_KEY_REUSED",
                        "requestKey 已用于其他参数: " + requestKey);
            }
            return fromJson(record.responseJson(), SwapResponse.class);
        });
    }

    private Comparator<SwapConflictView> conflictComparator() {
        return Comparator.comparing(SwapConflictView::type)
                .thenComparing(SwapConflictView::sectionId)
                .thenComparing(SwapConflictView::scheduleKey)
                .thenComparing(SwapConflictView::conflictingScheduleKey)
                .thenComparing(SwapConflictView::trainNo)
                .thenComparing(SwapConflictView::startUtc)
                .thenComparing(SwapConflictView::endUtc);
    }

    private List<Map<String, Object>> toDetailMaps(List<SwapConflictView> conflicts) {
        return conflicts.stream().sorted(conflictComparator()).map(c -> {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("type", c.type());
            detail.put("sectionId", c.sectionId());
            detail.put("scheduleKey", c.scheduleKey());
            detail.put("conflictingScheduleKey", c.conflictingScheduleKey());
            detail.put("trainNo", c.trainNo());
            detail.put("startUtc", c.startUtc().toString());
            detail.put("endUtc", c.endUtc().toString());
            return detail;
        }).toList();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化失败", e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("反序列化失败", e);
        }
    }

    private List<NormOcc> fromJsonList(String json) {
        try {
            List<OccupancyView> views = objectMapper.readValue(json,
                    objectMapper.getTypeFactory()
                            .constructCollectionType(List.class, OccupancyView.class));
            return views.stream()
                    .map(v -> new NormOcc(v.trainNo(), v.sectionId(), v.startUtc(), v.endUtc()))
                    .sorted()
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("占用段反序列化失败", e);
        }
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", message);
    }

    private ApiException notFound(String scheduleKey) {
        return new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND",
                "参与计划不存在: " + scheduleKey);
    }

    private ApiException swapNotFound(String swapKey) {
        return new ApiException(HttpStatus.NOT_FOUND, "SWAP_NOT_FOUND",
                "交换单不存在: " + swapKey);
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
