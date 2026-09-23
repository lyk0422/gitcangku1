package com.example.starter.plan.service;

import com.example.starter.plan.model.CapacitySwap;
import com.example.starter.plan.model.CapacitySwapItem;
import com.example.starter.plan.model.CapacitySwapPhase;
import com.example.starter.plan.model.CapacitySwapSnapshot;
import com.example.starter.plan.model.CapacitySwapStatus;
import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.PlanStatus;
import com.example.starter.plan.model.PublishedSlot;
import com.example.starter.plan.model.SwapSegment;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.repo.SwapRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreateSwapRequest;
import com.example.starter.plan.web.dto.SwapItemRequest;
import com.example.starter.plan.web.dto.SwapItemView;
import com.example.starter.plan.web.dto.SwapResponse;
import com.example.starter.plan.web.dto.SwapSegmentRequest;
import com.example.starter.plan.web.dto.SwapSegmentView;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 容量交换单业务：创建预览、激活（单事务闭环原子交换）、交换证据只读查询。
 *
 * <p>预览不改任何计划占用，仅固化提交集合（交换项与占用段均按规范排序，顺序无关）与服务端读到的
 * 交换前实际占用，并按交换后的完整后态计算与未参与已发布计划的冲突；允许 A→B→C→A 闭环交换，
 * 冲突一律按交换后完整集合判定，不做逐项临时校验。
 *
 * <p>激活在单个事务内经全局发布锁与普通发布/取消/改签及其他交换单串行化，按数据库提交顺序裁决：
 * 版本变化、提交集合遗漏/多余/重复、当前占用不精确匹配、目标占用重复、与未参与计划的外部冲突
 * 一律 409/422 且整体回滚，不释放任何部分旧占用；成功后一次性替换全部占用、写入不可变前后快照、
 * 计划仍为 PUBLISHED 且各版本递增一次。
 *
 * <p>激活按 (SWAP_ACTIVATE, requestId) 幂等：同参重放首次响应，异参 409，失败不占键；
 * swapKey 跨请求唯一。
 */
@Service
public class SwapService {

    private static final String OP_SWAP_ACTIVATE = "SWAP_ACTIVATE";

    private final PlanRepository planRepo;
    private final SwapRepository swapRepo;
    private final IdempotencyRepository idemRepo;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public SwapService(PlanRepository planRepo, SwapRepository swapRepo,
                       IdempotencyRepository idemRepo, ObjectMapper objectMapper,
                       PlatformTransactionManager txManager) {
        this.planRepo = planRepo;
        this.swapRepo = swapRepo;
        this.idemRepo = idemRepo;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 创建交换单（仅预览）：返回全部计划版本、交换前实际占用、提交目标占用与按完整后态计算的冲突。
     * 提交集合（交换项与占用段）经规范排序后固化，列表顺序不影响语义。
     */
    public SwapResponse preview(CreateSwapRequest request) {
        validateSegmentParams(request.items());
        String hash = hashPreview(request);
        try {
            return tx.execute(status -> {
                if (swapRepo.findSwapByKey(request.swapKey()).isPresent()) {
                    throw conflict("SWAP_KEY_EXISTS", "swapKey 已存在: " + request.swapKey());
                }
                List<SwapItemRequest> items = sortedItems(request.items());
                Map<String, DayPlan> plans = loadPlans(items);
                Map<String, List<Occupancy>> actualOccupancies = new LinkedHashMap<>();
                List<Map<String, Object>> conflicts = new ArrayList<>();
                for (SwapItemRequest item : items) {
                    DayPlan plan = plans.get(item.scheduleKey());
                    if (plan.status() != PlanStatus.PUBLISHED) {
                        throw conflict("PLAN_STATE_CONFLICT",
                                "仅已发布计划可参与交换，计划 " + item.scheduleKey()
                                        + " 当前状态: " + plan.status());
                    }
                    if (!plan.opDate().equals(request.opDate())) {
                        throw conflict("OP_DATE_MISMATCH",
                                "计划 " + item.scheduleKey() + " 运营日 " + plan.opDate()
                                        + " 与交换单运营日 " + request.opDate() + " 不一致");
                    }
                    List<Occupancy> actual = planRepo.findOccupancies(plan.id());
                    actualOccupancies.put(item.scheduleKey(), actual);
                    if (item.expectedVersion() != plan.version()) {
                        Map<String, Object> detail = new LinkedHashMap<>();
                        detail.put("type", "VERSION_MISMATCH");
                        detail.put("scheduleKey", item.scheduleKey());
                        detail.put("expectedVersion", item.expectedVersion());
                        detail.put("actualVersion", plan.version());
                        conflicts.add(detail);
                    }
                    if (!segmentsEqual(item.currentOccupancies(), actual)) {
                        Map<String, Object> detail = new LinkedHashMap<>();
                        detail.put("type", "CURRENT_OCCUPANCY_MISMATCH");
                        detail.put("scheduleKey", item.scheduleKey());
                        conflicts.add(detail);
                    }
                }
                conflicts.addAll(findPostStateConflicts(request.opDate(), items,
                        plans.keySet().stream().map(k -> plans.get(k).id()).toList()));
                long now = System.currentTimeMillis();
                long swapId = swapRepo.insertSwap(request.swapKey(), request.opDate(), hash,
                        toJson(conflicts), now);
                List<SwapSegment> frozen = new ArrayList<>();
                for (int i = 0; i < items.size(); i++) {
                    SwapItemRequest item = items.get(i);
                    swapRepo.insertItem(swapId, i, plans.get(item.scheduleKey()).id(),
                            item.scheduleKey(), item.expectedVersion());
                    frozen.addAll(toFrozenSegments(swapId, i, CapacitySwapPhase.BEFORE,
                            item.currentOccupancies()));
                    frozen.addAll(toFrozenSegments(swapId, i, CapacitySwapPhase.AFTER,
                            item.targetOccupancies()));
                }
                swapRepo.insertSegments(frozen);
                List<SwapItemView> views = new ArrayList<>();
                for (int i = 0; i < items.size(); i++) {
                    SwapItemRequest item = items.get(i);
                    views.add(new SwapItemView(item.scheduleKey(),
                            plans.get(item.scheduleKey()).version(),
                            toSegmentViews(actualOccupancies.get(item.scheduleKey())),
                            toSegmentViews(item.targetOccupancies())));
                }
                return new SwapResponse(request.swapKey(), request.opDate(),
                        CapacitySwapStatus.PREVIEW.name(), Instant.ofEpochMilli(now), null,
                        views, conflicts);
            });
        } catch (DuplicateKeyException e) {
            throw conflict("SWAP_KEY_EXISTS", "swapKey 已存在: " + request.swapKey());
        }
    }

    /**
     * 激活交换单：单事务重读全部计划与占用并整体裁决，成功后一次性替换全部占用、
     * 写入不可变前后快照，计划仍为 PUBLISHED 且各版本递增一次。
     */
    public SwapResponse activate(String swapKey, String requestKey) {
        String hash = sha256(OP_SWAP_ACTIVATE + '\n' + swapKey);
        Optional<SwapResponse> replay = replayIfPresent(requestKey, hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                CapacitySwap swap = swapRepo.findSwapByKeyForUpdate(swapKey)
                        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                                "SWAP_NOT_FOUND", "交换单不存在: " + swapKey));
                if (swap.status() == CapacitySwapStatus.ACTIVATED) {
                    // 交换单已激活：视为同参完成态，返回既有证据（幂等）
                    SwapResponse evidence = loadEvidence(swap);
                    idemRepo.insert(OP_SWAP_ACTIVATE, requestKey, hash, toJson(evidence),
                            System.currentTimeMillis());
                    return evidence;
                }
                List<CapacitySwapItem> items = swapRepo.findItems(swap.id());
                List<SwapSegment> frozen = swapRepo.findSegments(swap.id());
                Map<Integer, List<SwapSegment>> frozenBefore = groupSegments(frozen,
                        CapacitySwapPhase.BEFORE);
                Map<Integer, List<SwapSegment>> frozenAfter = groupSegments(frozen,
                        CapacitySwapPhase.AFTER);

                // 单事务内重读全部计划（按 id 升序加行锁，避免死锁）
                List<CapacitySwapItem> byPlanId = items.stream()
                        .sorted(Comparator.comparingLong(CapacitySwapItem::planId)).toList();
                Map<Long, DayPlan> plans = new LinkedHashMap<>();
                for (CapacitySwapItem item : byPlanId) {
                    DayPlan plan = planRepo.findByIdForUpdate(item.planId())
                            .orElseThrow(() -> conflict("SWAP_PLAN_MISSING",
                                    "参与计划已不存在: " + item.scheduleKey()));
                    plans.put(plan.id(), plan);
                }
                if (plans.size() != items.size()) {
                    throw conflict("SWAP_SET_MISMATCH", "交换项计划集合不完整或重复");
                }
                Map<Long, List<Occupancy>> actualOccupancies = new LinkedHashMap<>();
                for (CapacitySwapItem item : byPlanId) {
                    DayPlan plan = plans.get(item.planId());
                    if (plan.status() != PlanStatus.PUBLISHED) {
                        throw conflict("PLAN_STATE_CONFLICT",
                                "参与计划须为已发布，计划 " + item.scheduleKey()
                                        + " 当前状态: " + plan.status());
                    }
                    if (!plan.opDate().equals(swap.opDate())) {
                        throw conflict("OP_DATE_MISMATCH",
                                "计划 " + item.scheduleKey() + " 运营日 " + plan.opDate()
                                        + " 与交换单运营日 " + swap.opDate() + " 不一致");
                    }
                    if (item.expectedVersion() != plan.version()) {
                        throw conflict("VERSION_CONFLICT",
                                "计划 " + item.scheduleKey() + " expectedVersion="
                                        + item.expectedVersion() + " 与当前版本 "
                                        + plan.version() + " 不一致");
                    }
                    List<Occupancy> actual = planRepo.findOccupancies(plan.id());
                    if (!frozenSegmentsEqual(frozenBefore.get(item.itemSeq()), actual)) {
                        throw conflict("CURRENT_OCCUPANCY_MISMATCH",
                                "计划 " + item.scheduleKey() + " 当前占用与提交集合不精确一致");
                    }
                    actualOccupancies.put(plan.id(), actual);
                }

                // 目标占用互不重复（全单跨计划规范键唯一）
                Map<String, String> targetOwner = new HashMap<>();
                for (CapacitySwapItem item : items) {
                    for (SwapSegment seg : frozenAfter.get(item.itemSeq())) {
                        String key = segmentKey(seg.sectionId(), seg.startUtc(), seg.endUtc());
                        String owner = targetOwner.putIfAbsent(key, item.scheduleKey());
                        if (owner != null) {
                            throw conflict("TARGET_DUPLICATE",
                                    "目标占用段重复: " + seg.sectionId() + " ["
                                            + seg.startUtc() + ", " + seg.endUtc() + ") 计划 "
                                            + owner + " 与 " + item.scheduleKey());
                        }
                    }
                }

                // 交换后完整集合与未参与已发布计划的外部冲突（闭环内部让位不算冲突）
                List<SwapSegment> allTargets = items.stream()
                        .flatMap(item -> frozenAfter.get(item.itemSeq()).stream()).toList();
                List<Map<String, Object>> conflicts =
                        findExternalConflicts(swap.opDate(), allTargets, plans.keySet());
                if (!conflicts.isEmpty()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_CONFLICT",
                            "交换后完整集合与未参与已发布计划冲突，交换未生效", conflicts);
                }

                // 一次性替换全部占用、写入不可变前后快照、版本各递增一次
                long now = System.currentTimeMillis();
                List<CapacitySwapSnapshot> snapshots = new ArrayList<>();
                for (CapacitySwapItem item : items) {
                    DayPlan plan = plans.get(item.planId());
                    List<Occupancy> before = actualOccupancies.get(plan.id());
                    String trainNo = before.get(0).trainNo();
                    List<Occupancy> after = new ArrayList<>();
                    int seq = 0;
                    for (SwapSegment seg : frozenAfter.get(item.itemSeq())) {
                        after.add(new Occupancy(0L, plan.id(), seq, trainNo, seg.sectionId(),
                                seg.startUtc(), seg.endUtc()));
                        seq++;
                    }
                    planRepo.replaceOccupancies(plan.id(), after);
                    planRepo.updateVersionAndStatus(plan.id(), plan.version() + 1,
                            PlanStatus.PUBLISHED, now);
                    swapRepo.updateItemFinalVersion(item.id(), plan.version() + 1);
                    snapshots.addAll(toSnapshots(swap.id(), item.itemSeq(), plan.id(),
                            CapacitySwapPhase.BEFORE, before));
                    snapshots.addAll(toSnapshots(swap.id(), item.itemSeq(), plan.id(),
                            CapacitySwapPhase.AFTER, after));
                }
                swapRepo.insertSnapshots(snapshots);
                swapRepo.markActivated(swap.id(), now);
                SwapResponse response = loadEvidence(swapRepo.findSwapByKey(swapKey)
                        .orElseThrow(() -> new IllegalStateException("交换单丢失: " + swapKey)));
                idemRepo.insert(OP_SWAP_ACTIVATE, requestKey, hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(requestKey, hash)
                    .orElseThrow(() -> conflict("SWAP_KEY_EXISTS", "swapKey 冲突: " + swapKey));
        }
    }

    /**
     * 查询交换证据（只读，稳定排序）；交换单不存在返回 404。
     * PREVIEW 单返回预览时固化的提交集合与冲突；ACTIVATED 单返回不可变前后快照。
     */
    public SwapResponse getSwap(String swapKey) {
        CapacitySwap swap = swapRepo.findSwapByKey(swapKey)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "SWAP_NOT_FOUND", "交换单不存在: " + swapKey));
        if (swap.status() == CapacitySwapStatus.ACTIVATED) {
            return loadEvidence(swap);
        }
        List<CapacitySwapItem> items = swapRepo.findItems(swap.id());
        List<SwapSegment> frozen = swapRepo.findSegments(swap.id());
        Map<Integer, List<SwapSegment>> before = groupSegments(frozen, CapacitySwapPhase.BEFORE);
        Map<Integer, List<SwapSegment>> after = groupSegments(frozen, CapacitySwapPhase.AFTER);
        List<SwapItemView> views = new ArrayList<>();
        for (CapacitySwapItem item : items) {
            views.add(new SwapItemView(item.scheduleKey(), item.expectedVersion(),
                    toSegmentViews(before.get(item.itemSeq())),
                    toSegmentViews(after.get(item.itemSeq()))));
        }
        return new SwapResponse(swap.swapKey(), swap.opDate(), swap.status().name(),
                Instant.ofEpochMilli(swap.createdAt()), null, views,
                fromJsonList(swap.previewConflicts()));
    }

    // ---------- 内部实现 ----------

    /**
     * 参数级校验：占用段结束必须晚于开始，且起止落在同一 Asia/Shanghai 日历日内；
     * 交换项计划业务键不得重复。
     */
    private void validateSegmentParams(List<SwapItemRequest> items) {
        TreeSet<String> seen = new TreeSet<>();
        for (SwapItemRequest item : items) {
            if (!seen.add(item.scheduleKey())) {
                throw badRequest("交换项计划重复: " + item.scheduleKey());
            }
            validateSegments(item.currentOccupancies(), "当前占用");
            validateSegments(item.targetOccupancies(), "目标占用");
        }
    }

    private void validateSegments(List<SwapSegmentRequest> segments, String label) {
        for (int i = 0; i < segments.size(); i++) {
            SwapSegmentRequest s = segments.get(i);
            if (!s.endUtc().isAfter(s.startUtc())) {
                throw badRequest(label + "第 " + i + " 段结束时刻必须晚于开始时刻");
            }
            LocalDate startDay = s.startUtc().atZone(PlanService.OPERATION_ZONE).toLocalDate();
            LocalDate endDay = s.endUtc().minusNanos(1).atZone(PlanService.OPERATION_ZONE)
                    .toLocalDate();
            if (!startDay.equals(endDay)) {
                throw badRequest(label + "第 " + i + " 段必须落在同一运营日（Asia/Shanghai）内");
            }
        }
    }

    /**
     * 交换项按计划业务键升序规范排序（提交顺序不影响语义）。
     */
    private List<SwapItemRequest> sortedItems(List<SwapItemRequest> items) {
        return items.stream()
                .sorted(Comparator.comparing(SwapItemRequest::scheduleKey)).toList();
    }

    /**
     * 批量加载参与计划；任一不存在抛 404。
     */
    private Map<String, DayPlan> loadPlans(List<SwapItemRequest> items) {
        Map<String, DayPlan> plans = new LinkedHashMap<>();
        for (SwapItemRequest item : items) {
            DayPlan plan = planRepo.findByKey(item.scheduleKey())
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND",
                            "计划不存在: " + item.scheduleKey()));
            plans.put(item.scheduleKey(), plan);
        }
        return plans;
    }

    /**
     * 提交的当前占用段与库内实际占用精确一致判定（多重集合，忽略顺序与列车编号）。
     */
    private boolean segmentsEqual(List<SwapSegmentRequest> submitted, List<Occupancy> actual) {
        TreeMap<String, Integer> expected = new TreeMap<>();
        for (SwapSegmentRequest s : submitted) {
            expected.merge(segmentKey(s.sectionId(), s.startUtc(), s.endUtc()), 1, Integer::sum);
        }
        TreeMap<String, Integer> found = new TreeMap<>();
        for (Occupancy o : actual) {
            found.merge(segmentKey(o.sectionId(), o.startUtc(), o.endUtc()), 1, Integer::sum);
        }
        return expected.equals(found);
    }

    /**
     * 冻结的提交段与库内实际占用精确一致判定（激活重读用）。
     */
    private boolean frozenSegmentsEqual(List<SwapSegment> frozen, List<Occupancy> actual) {
        TreeMap<String, Integer> expected = new TreeMap<>();
        for (SwapSegment s : frozen) {
            expected.merge(segmentKey(s.sectionId(), s.startUtc(), s.endUtc()), 1, Integer::sum);
        }
        TreeMap<String, Integer> found = new TreeMap<>();
        for (Occupancy o : actual) {
            found.merge(segmentKey(o.sectionId(), o.startUtc(), o.endUtc()), 1, Integer::sum);
        }
        return expected.equals(found);
    }

    /**
     * 预览的完整后态冲突：全部参与计划目标占用（含目标重复）与未参与已发布计划的区段重叠。
     */
    private List<Map<String, Object>> findPostStateConflicts(LocalDate opDate,
                                                             List<SwapItemRequest> items,
                                                             List<Long> participantIds) {
        List<Map<String, Object>> conflicts = new ArrayList<>();
        Map<String, String> targetOwner = new HashMap<>();
        List<SwapSegment> allTargets = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            SwapItemRequest item = items.get(i);
            for (SwapSegmentRequest s : item.targetOccupancies()) {
                String key = segmentKey(s.sectionId(), s.startUtc(), s.endUtc());
                String owner = targetOwner.putIfAbsent(key, item.scheduleKey());
                if (owner != null) {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("type", "TARGET_DUPLICATE");
                    detail.put("sectionId", s.sectionId());
                    detail.put("startUtc", s.startUtc().toString());
                    detail.put("endUtc", s.endUtc().toString());
                    detail.put("scheduleKeys", List.of(owner, item.scheduleKey()));
                    conflicts.add(detail);
                }
                allTargets.add(new SwapSegment(0L, i, CapacitySwapPhase.AFTER, 0,
                        s.sectionId(), s.startUtc(), s.endUtc()));
            }
        }
        conflicts.addAll(findExternalConflicts(opDate, allTargets,
                new TreeSet<>(participantIds)));
        return conflicts;
    }

    /**
     * 交换后完整目标集合与未参与已发布计划在同运营日、同区段上的重叠检测（左闭右开，相邻合法）。
     */
    private List<Map<String, Object>> findExternalConflicts(LocalDate opDate,
                                                            List<SwapSegment> targets,
                                                            java.util.Collection<Long> participantIds) {
        List<String> sectionIds = targets.stream().map(SwapSegment::sectionId)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new))
                .stream().toList();
        List<PublishedSlot> published = planRepo.findPublishedSlots(opDate, sectionIds,
                participantIds);
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (SwapSegment t : targets) {
            for (PublishedSlot slot : published) {
                if (!t.sectionId().equals(slot.sectionId())) {
                    continue;
                }
                boolean overlap = t.startUtc().isBefore(slot.endUtc())
                        && slot.startUtc().isBefore(t.endUtc());
                if (overlap) {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("type", "SECTION_CONFLICT");
                    detail.put("sectionId", t.sectionId());
                    detail.put("conflictingScheduleKey", slot.scheduleKey());
                    detail.put("startUtc", t.startUtc().toString());
                    detail.put("endUtc", t.endUtc().toString());
                    conflicts.add(detail);
                }
            }
        }
        return conflicts;
    }

    /**
     * 组装 ACTIVATED 交换单的不可变证据（前后快照，稳定排序）。
     */
    private SwapResponse loadEvidence(CapacitySwap swap) {
        List<CapacitySwapItem> items = swapRepo.findItems(swap.id());
        List<CapacitySwapSnapshot> snapshots = swapRepo.findSnapshots(swap.id());
        Map<Integer, List<CapacitySwapSnapshot>> before = new TreeMap<>();
        Map<Integer, List<CapacitySwapSnapshot>> after = new TreeMap<>();
        for (CapacitySwapSnapshot s : snapshots) {
            (s.phase() == CapacitySwapPhase.BEFORE ? before : after)
                    .computeIfAbsent(s.itemSeq(), k -> new ArrayList<>()).add(s);
        }
        List<SwapItemView> views = new ArrayList<>();
        for (CapacitySwapItem item : items) {
            views.add(new SwapItemView(item.scheduleKey(),
                    item.finalVersion() != null ? item.finalVersion() : item.expectedVersion(),
                    toSnapshotViews(before.getOrDefault(item.itemSeq(), List.of())),
                    toSnapshotViews(after.getOrDefault(item.itemSeq(), List.of()))));
        }
        return new SwapResponse(swap.swapKey(), swap.opDate(), swap.status().name(),
                Instant.ofEpochMilli(swap.createdAt()),
                swap.activatedAt() != null ? Instant.ofEpochMilli(swap.activatedAt()) : null,
                views, List.of());
    }

    private List<SwapSegment> toFrozenSegments(long swapId, int itemSeq, CapacitySwapPhase phase,
                                               List<SwapSegmentRequest> segments) {
        List<SwapSegmentRequest> sorted = segments.stream()
                .sorted(Comparator.comparing(SwapSegmentRequest::sectionId)
                        .thenComparing(SwapSegmentRequest::startUtc)
                        .thenComparing(SwapSegmentRequest::endUtc))
                .toList();
        List<SwapSegment> result = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            SwapSegmentRequest s = sorted.get(i);
            result.add(new SwapSegment(swapId, itemSeq, phase, i, s.sectionId(),
                    s.startUtc(), s.endUtc()));
        }
        return result;
    }

    private List<CapacitySwapSnapshot> toSnapshots(long swapId, int itemSeq, long planId,
                                                   CapacitySwapPhase phase,
                                                   List<Occupancy> occupancies) {
        List<Occupancy> sorted = occupancies.stream()
                .sorted(Comparator.comparing(Occupancy::sectionId)
                        .thenComparing(Occupancy::startUtc)
                        .thenComparing(Occupancy::endUtc))
                .toList();
        List<CapacitySwapSnapshot> result = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            Occupancy o = sorted.get(i);
            result.add(new CapacitySwapSnapshot(0L, swapId, planId, itemSeq, phase, i,
                    o.trainNo(), o.sectionId(), o.startUtc(), o.endUtc()));
        }
        return result;
    }

    private Map<Integer, List<SwapSegment>> groupSegments(List<SwapSegment> segments,
                                                          CapacitySwapPhase phase) {
        Map<Integer, List<SwapSegment>> grouped = new TreeMap<>();
        for (SwapSegment s : segments) {
            if (s.phase() == phase) {
                grouped.computeIfAbsent(s.itemSeq(), k -> new ArrayList<>()).add(s);
            }
        }
        return grouped;
    }

    private List<SwapSegmentView> toSegmentViews(List<?> segments) {
        List<SwapSegmentView> views = new ArrayList<>();
        for (Object s : segments) {
            if (s instanceof Occupancy o) {
                views.add(new SwapSegmentView(o.sectionId(), o.startUtc(), o.endUtc()));
            } else if (s instanceof SwapSegmentRequest r) {
                views.add(new SwapSegmentView(r.sectionId(), r.startUtc(), r.endUtc()));
            } else if (s instanceof SwapSegment f) {
                views.add(new SwapSegmentView(f.sectionId(), f.startUtc(), f.endUtc()));
            } else {
                throw new IllegalStateException("未知占用段类型: " + s.getClass());
            }
        }
        return views.stream()
                .sorted(Comparator.comparing(SwapSegmentView::sectionId)
                        .thenComparing(SwapSegmentView::startUtc)
                        .thenComparing(SwapSegmentView::endUtc))
                .toList();
    }

    private List<SwapSegmentView> toSnapshotViews(List<CapacitySwapSnapshot> snapshots) {
        return snapshots.stream()
                .sorted(Comparator.comparing(CapacitySwapSnapshot::sectionId)
                        .thenComparing(CapacitySwapSnapshot::startUtc)
                        .thenComparing(CapacitySwapSnapshot::endUtc))
                .map(s -> new SwapSegmentView(s.sectionId(), s.startUtc(), s.endUtc()))
                .toList();
    }

    private String segmentKey(String sectionId, Instant startUtc, Instant endUtc) {
        return sectionId + '|' + startUtc.toEpochMilli() + '|' + endUtc.toEpochMilli();
    }

    /**
     * 预览请求规范化哈希：交换项与占用段均排序后参与，列表换序视为同参。
     */
    private String hashPreview(CreateSwapRequest request) {
        StringBuilder sb = new StringBuilder("SWAP_PREVIEW").append('\n')
                .append(request.swapKey()).append('\n').append(request.opDate());
        for (SwapItemRequest item : sortedItems(request.items())) {
            sb.append('\n').append(item.scheduleKey()).append('|').append(item.expectedVersion());
            appendSegments(sb, item.currentOccupancies());
            sb.append('|').append('>');
            appendSegments(sb, item.targetOccupancies());
        }
        return sha256(sb.toString());
    }

    private void appendSegments(StringBuilder sb, List<SwapSegmentRequest> segments) {
        segments.stream()
                .sorted(Comparator.comparing(SwapSegmentRequest::sectionId)
                        .thenComparing(SwapSegmentRequest::startUtc)
                        .thenComparing(SwapSegmentRequest::endUtc))
                .forEach(s -> sb.append('|').append(s.sectionId()).append(',')
                        .append(s.startUtc().toEpochMilli()).append(',')
                        .append(s.endUtc().toEpochMilli()));
    }

    /**
     * 激活幂等重放：存在记录且参数一致返回首次结果；参数不一致抛 409。
     */
    private Optional<SwapResponse> replayIfPresent(String requestKey, String hash) {
        return idemRepo.find(OP_SWAP_ACTIVATE, requestKey).map(record -> {
            if (!record.requestHash().equals(hash)) {
                throw conflict("IDEMPOTENT_KEY_REUSED",
                        "requestKey 已用于其他参数: " + requestKey);
            }
            return fromJson(record.responseJson(), SwapResponse.class);
        });
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
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fromJsonList(String json) {
        try {
            return objectMapper.readValue(json, List.class);
        } catch (Exception e) {
            throw new IllegalStateException("预览冲突反序列化失败", e);
        }
    }

    private ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", message);
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
