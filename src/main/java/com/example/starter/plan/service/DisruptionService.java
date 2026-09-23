package com.example.starter.plan.service;

import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.DisruptionMapping;
import com.example.starter.plan.model.DisruptionStatus;
import com.example.starter.plan.model.DisruptionSwitch;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.PlanStatus;
import com.example.starter.plan.model.PublishedSlot;
import com.example.starter.plan.repo.DisruptionRepository;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.DisruptionActivateRequest;
import com.example.starter.plan.web.dto.DisruptionDetailResponse;
import com.example.starter.plan.web.dto.DisruptionMappingItem;
import com.example.starter.plan.web.dto.DisruptionMappingView;
import com.example.starter.plan.web.dto.DisruptionPlanSnapshotView;
import com.example.starter.plan.web.dto.DisruptionPreviewResponse;
import com.example.starter.plan.web.dto.DisruptionRegisterRequest;
import com.example.starter.plan.web.dto.DisruptionSubmitRequest;
import com.example.starter.plan.web.dto.DisruptionSwitchView;
import com.example.starter.plan.web.dto.OccupancyView;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 区段封锁切换业务：登记 sectionId 与左闭右开 UTC 窗口、预览相交 PUBLISHED 计划、
 * 提交完整旧计划集合到现有 DRAFT 替代计划的一对一映射，并在单事务内原子激活。
 *
 * <p>激活在全局发布锁内重新计算影响集合，依次校验影响集合完整（无遗漏/多余）、
 * 旧计划版本与状态、替代计划状态与运营日、映射与提交一致、改签链环，
 * 以及替代计划彼此之间和与未受影响已发布计划的区段时隙；任一失败整体回滚，
 * 封锁状态、计划状态、时隙与改签链均不变化。成功后切换单 ACTIVE、旧计划 SUSPENDED
 * 释放时隙、替代计划 PUBLISHED 占用时隙，写入不可变一对一替代链与完整切换快照。
 *
 * <p>登记与激活按 (操作类型, requestId) 幂等：同键同参（映射按旧计划键排序，换序同参）
 * 重放首次快照，异参 409，失败不占键；已激活切换单换 requestId 复用 409。
 */
@Service
public class DisruptionService {

    private static final String OP_REGISTER = "DISRUPTION_REGISTER";
    private static final String OP_ACTIVATE = "DISRUPTION_ACTIVATE";

    private final PlanRepository planRepo;
    private final DisruptionRepository disruptionRepo;
    private final IdempotencyRepository idemRepo;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public DisruptionService(PlanRepository planRepo, DisruptionRepository disruptionRepo,
                             IdempotencyRepository idemRepo, ObjectMapper objectMapper,
                             PlatformTransactionManager txManager) {
        this.planRepo = planRepo;
        this.disruptionRepo = disruptionRepo;
        this.idemRepo = idemRepo;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 登记封锁切换单（REGISTERED）。窗口必须为合法左闭右开区间。
     */
    public DisruptionSwitchView register(DisruptionRegisterRequest req) {
        if (!req.windowEndUtc().isAfter(req.windowStartUtc())) {
            throw badRequest("封锁窗口结束时刻必须晚于开始时刻");
        }
        String hash = hashRegister(req);
        Optional<DisruptionSwitchView> replay =
                replayIfPresent(OP_REGISTER, req.requestId(), hash, DisruptionSwitchView.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                long now = System.currentTimeMillis();
                long switchId = disruptionRepo.insertSwitch(req.switchKey(), req.sectionId(),
                        req.windowStartUtc().toEpochMilli(), req.windowEndUtc().toEpochMilli(), now);
                DisruptionSwitchView response = switchView(
                        disruptionRepo.findSwitchByKey(req.switchKey()).orElseThrow());
                idemRepo.insert(OP_REGISTER, req.requestId(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_REGISTER, req.requestId(), hash, DisruptionSwitchView.class)
                    .orElseThrow(() -> conflict("SWITCH_KEY_EXISTS",
                            "switchKey 已存在: " + req.switchKey()));
        }
    }

    /**
     * 预览与封锁窗口相交的全部 PUBLISHED 计划、版本与相交占用；只读不写。
     */
    public DisruptionPreviewResponse preview(String switchKey) {
        DisruptionSwitch sw = loadSwitch(switchKey);
        Map<Long, List<Occupancy>> byPlan = intersectingOccupanciesByPlan(sw);
        List<DisruptionPlanSnapshotView> plans = byPlan.entrySet().stream()
                .map(e -> planSnapshot(planRepo.findById(e.getKey()).orElseThrow(), e.getValue()))
                .sorted(Comparator.comparing(DisruptionPlanSnapshotView::scheduleKey))
                .toList();
        return new DisruptionPreviewResponse(switchView(sw), plans);
    }

    /**
     * 提交完整旧计划集合与一对一替代映射。旧集合必须恰好覆盖当前全部相交 PUBLISHED 计划，
     * 替代计划须为同运营日 DRAFT、彼此不重复且不位于旧计划改签链上；重复提交整体替换。
     */
    public DisruptionDetailResponse submitMappings(String switchKey, DisruptionSubmitRequest req) {
        Map<String, String> pairs = normalizePairs(req.mappings());
        return tx.execute(status -> {
            planRepo.acquirePublishLock();
            DisruptionSwitch sw = disruptionRepo.findSwitchByKeyForUpdate(switchKey)
                    .orElseThrow(() -> switchNotFound(switchKey));
            if (sw.status() != DisruptionStatus.REGISTERED) {
                throw conflict("SWITCH_STATE_CONFLICT",
                        "仅 REGISTERED 切换单可提交映射，当前状态: " + sw.status());
            }
            List<ValidatedPair> validated = validatePairs(sw, pairs);
            long now = System.currentTimeMillis();
            disruptionRepo.deleteMappings(sw.id());
            for (ValidatedPair p : validated) {
                disruptionRepo.insertMapping(sw.id(), p.oldPlan().id(), p.replacement().id(),
                        p.oldPlan().version(), now);
            }
            return buildLiveDetail(sw, validated);
        });
    }

    /**
     * 原子激活：全局发布锁内重新计算影响集合并全量校验，成功则挂起旧计划、发布替代计划、
     * 写入替代链与快照；任何失败整体回滚。
     */
    public DisruptionDetailResponse activate(String switchKey, DisruptionActivateRequest req) {
        String hash = hashActivate(switchKey, req.mappings());
        Optional<DisruptionDetailResponse> replay =
                replayIfPresent(OP_ACTIVATE, req.requestId(), hash, DisruptionDetailResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                DisruptionSwitch sw = disruptionRepo.findSwitchByKeyForUpdate(switchKey)
                        .orElseThrow(() -> switchNotFound(switchKey));
                if (sw.status() == DisruptionStatus.ACTIVE) {
                    // 并发下同 requestId 已由首个事务激活：重放首次快照；换请求复用 409。
                    if (req.requestId().equals(sw.activatedRequestId())) {
                        return fromJson(disruptionRepo.findSnapshot(sw.id()).orElseThrow(),
                                DisruptionDetailResponse.class);
                    }
                    throw conflict("SWITCH_STATE_CONFLICT",
                            "切换单已激活，switchKey 不可换请求复用: " + switchKey);
                }
                Map<String, String> pairs = normalizePairs(req.mappings());
                List<ValidatedPair> validated = validateForActivation(sw, pairs);
                assertNoSlotConflicts(validated);

                long now = System.currentTimeMillis();
                for (ValidatedPair p : validated) {
                    planRepo.updateStatus(p.oldPlan().id(), PlanStatus.SUSPENDED, now);
                    planRepo.updateStatus(p.replacement().id(), PlanStatus.PUBLISHED, now);
                    disruptionRepo.insertReplaceLink(sw.id(), p.oldPlan().id(),
                            p.replacement().id(), now);
                }
                disruptionRepo.markActive(sw.id(), req.requestId(), now);
                DisruptionDetailResponse response = buildActivationSnapshot(sw, validated);
                String json = toJson(response);
                disruptionRepo.insertSnapshot(sw.id(), json, now);
                idemRepo.insert(OP_ACTIVATE, req.requestId(), hash, json, now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_ACTIVATE, req.requestId(), hash, DisruptionDetailResponse.class)
                    .orElseThrow(() -> conflict("LINK_CONFLICT", "封锁替代链或幂等键冲突"));
        }
    }

    /**
     * 查询切换单、映射与前后占用，只读不写：ACTIVE 返回激活时不可变快照，
     * REGISTERED 返回当前实时状态。
     */
    public DisruptionDetailResponse getSwitch(String switchKey) {
        DisruptionSwitch sw = loadSwitch(switchKey);
        if (sw.status() == DisruptionStatus.ACTIVE) {
            return fromJson(disruptionRepo.findSnapshot(sw.id()).orElseThrow(),
                    DisruptionDetailResponse.class);
        }
        List<DisruptionMapping> mappings = disruptionRepo.findMappings(sw.id());
        List<ValidatedPair> pairs = new ArrayList<>();
        for (DisruptionMapping m : mappings) {
            DayPlan oldPlan = planRepo.findById(m.oldPlanId()).orElseThrow();
            DayPlan replacement = planRepo.findById(m.replacementPlanId()).orElseThrow();
            pairs.add(new ValidatedPair(oldPlan, replacement));
        }
        return buildLiveDetail(sw, pairs);
    }

    // ---------- 校验 ----------

    /**
     * 提交时校验：请求结构、影响集合恰好覆盖、旧计划 PUBLISHED、替代计划 DRAFT 同运营日、
     * 替代计划不重复且不在旧计划改签链上。
     */
    private List<ValidatedPair> validatePairs(DisruptionSwitch sw, Map<String, String> pairs) {
        Set<Long> affectedIds = affectedPlanIds(sw);
        List<ValidatedPair> validated = new ArrayList<>();
        Set<Long> oldPlanIds = new LinkedHashSet<>();
        Set<Long> replacementIds = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : pairs.entrySet()) {
            DayPlan oldPlan = planRepo.findByKeyForUpdate(e.getKey())
                    .orElseThrow(() -> planNotFound(e.getKey()));
            DayPlan replacement = planRepo.findByKeyForUpdate(e.getValue())
                    .orElseThrow(() -> planNotFound(e.getValue()));
            if (oldPlan.id() == replacement.id()) {
                throw conflict("MAPPING_CONFLICT", "旧计划与替代计划不能相同: " + e.getKey());
            }
            if (oldPlan.status() != PlanStatus.PUBLISHED) {
                throw conflict("PLAN_STATE_CONFLICT",
                        "旧计划必须为 PUBLISHED，当前状态: " + oldPlan.scheduleKey()
                                + "=" + oldPlan.status());
            }
            if (replacement.status() != PlanStatus.DRAFT) {
                throw conflict("PLAN_STATE_CONFLICT",
                        "替代计划必须为 DRAFT，当前状态: " + replacement.scheduleKey()
                                + "=" + replacement.status());
            }
            if (!oldPlan.opDate().equals(replacement.opDate())) {
                throw conflict("OP_DATE_MISMATCH",
                        "替代计划运营日必须与旧计划相同: " + oldPlan.scheduleKey() + " 旧="
                                + oldPlan.opDate() + " 替代=" + replacement.opDate());
            }
            if (!replacementIds.add(replacement.id())) {
                throw unprocessable("MAPPING_DUPLICATE",
                        "替代计划不得重复: " + replacement.scheduleKey());
            }
            if (isInRescheduleChain(oldPlan.id(), replacement.id())) {
                throw conflict("LINK_CONFLICT",
                        "替代计划不能是旧计划的祖先或后继: 旧=" + oldPlan.scheduleKey()
                                + " 替代=" + replacement.scheduleKey());
            }
            if (disruptionRepo.findReplaceLinkByReplacement(replacement.id()).isPresent()
                    || disruptionRepo.findReplaceLinkBySuspended(oldPlan.id()).isPresent()) {
                throw conflict("LINK_CONFLICT",
                        "计划已存在封锁替代链: " + replacement.scheduleKey());
            }
            oldPlanIds.add(oldPlan.id());
            validated.add(new ValidatedPair(oldPlan, replacement));
        }
        if (!oldPlanIds.equals(affectedIds)) {
            throw conflict("AFFECTED_SET_MISMATCH",
                    "提交旧计划集合与窗口当前相交 PUBLISHED 计划不一致（存在遗漏或多余）");
        }
        validated.sort(Comparator.comparing(p -> p.oldPlan().scheduleKey()));
        return validated;
    }

    /**
     * 激活时在锁内重新计算并校验：请求映射须与提交映射一致、影响集合无遗漏/多余、
     * 旧计划版本与状态未变、替代计划仍为同运营日 DRAFT、无重复且无改签链环。
     */
    private List<ValidatedPair> validateForActivation(DisruptionSwitch sw,
                                                      Map<String, String> requestPairs) {
        List<DisruptionMapping> stored = disruptionRepo.findMappings(sw.id());
        Map<String, String> storedPairs = new TreeMap<>();
        Map<Long, Integer> expectedVersions = new LinkedHashMap<>();
        for (DisruptionMapping m : stored) {
            DayPlan old = planRepo.findById(m.oldPlanId()).orElseThrow();
            storedPairs.put(old.scheduleKey(),
                    planRepo.findById(m.replacementPlanId()).orElseThrow().scheduleKey());
            expectedVersions.put(m.oldPlanId(), m.expectedOldVersion());
        }
        if (!storedPairs.equals(requestPairs)) {
            throw conflict("MAPPING_CONFLICT", "激活映射与提交映射不一致（映射被改动或不完整）");
        }
        Set<Long> affectedIds = affectedPlanIds(sw);
        List<ValidatedPair> validated = new ArrayList<>();
        Set<Long> replacementIds = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : requestPairs.entrySet()) {
            DayPlan oldPlan = planRepo.findByKeyForUpdate(e.getKey())
                    .orElseThrow(() -> planNotFound(e.getKey()));
            DayPlan replacement = planRepo.findByKeyForUpdate(e.getValue())
                    .orElseThrow(() -> planNotFound(e.getValue()));
            if (oldPlan.status() != PlanStatus.PUBLISHED) {
                throw conflict("PLAN_STATE_CONFLICT",
                        "旧计划必须仍为 PUBLISHED，当前状态: " + oldPlan.scheduleKey()
                                + "=" + oldPlan.status());
            }
            Integer expected = expectedVersions.get(oldPlan.id());
            if (expected == null || expected != oldPlan.version()) {
                throw conflict("VERSION_CONFLICT",
                        "旧计划版本已变化: " + oldPlan.scheduleKey() + " 提交时期望="
                                + expected + " 当前=" + oldPlan.version());
            }
            if (replacement.status() != PlanStatus.DRAFT) {
                throw conflict("PLAN_STATE_CONFLICT",
                        "替代计划必须仍为 DRAFT，当前状态: " + replacement.scheduleKey()
                                + "=" + replacement.status());
            }
            if (!oldPlan.opDate().equals(replacement.opDate())) {
                throw conflict("OP_DATE_MISMATCH",
                        "替代计划运营日必须与旧计划相同: " + oldPlan.scheduleKey());
            }
            if (!replacementIds.add(replacement.id())) {
                throw unprocessable("MAPPING_DUPLICATE",
                        "替代计划不得重复: " + replacement.scheduleKey());
            }
            if (isInRescheduleChain(oldPlan.id(), replacement.id())) {
                throw conflict("LINK_CONFLICT",
                        "替代计划不能是旧计划的祖先或后继: 旧=" + oldPlan.scheduleKey()
                                + " 替代=" + replacement.scheduleKey());
            }
            if (disruptionRepo.findReplaceLinkByReplacement(replacement.id()).isPresent()
                    || disruptionRepo.findReplaceLinkBySuspended(oldPlan.id()).isPresent()) {
                throw conflict("LINK_CONFLICT",
                        "计划已存在封锁替代链: " + replacement.scheduleKey());
            }
            validated.add(new ValidatedPair(oldPlan, replacement));
        }
        Set<Long> requestOldIds = validated.stream().map(p -> p.oldPlan().id())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (!requestOldIds.equals(affectedIds)) {
            throw conflict("AFFECTED_SET_MISMATCH",
                    "影响集合已变化，提交集合存在遗漏或多余，激活未生效");
        }
        validated.sort(Comparator.comparing(p -> p.oldPlan().scheduleKey()));
        return validated;
    }

    /**
     * 时隙冲突校验：替代计划内部同列车重叠、替代计划彼此之间同区段重叠，
     * 以及替代计划与未受影响 PUBLISHED 计划（排除全部旧计划）的同区段重叠。
     */
    private void assertNoSlotConflicts(List<ValidatedPair> validated) {
        List<Map<String, Object>> conflicts = new ArrayList<>();
        List<Occupancy> allReplacementOccupancies = new ArrayList<>();
        Set<Long> oldPlanIds = new LinkedHashSet<>();
        for (ValidatedPair p : validated) {
            oldPlanIds.add(p.oldPlan().id());
            List<Occupancy> occupancies = planRepo.findOccupancies(p.replacement().id());
            conflicts.addAll(findTrainOverlaps(p.replacement().scheduleKey(), occupancies));
            allReplacementOccupancies.addAll(occupancies);
        }
        // 替代计划彼此之间的区段时隙冲突
        for (int i = 0; i < validated.size(); i++) {
            for (int j = i + 1; j < validated.size(); j++) {
                conflicts.addAll(findPairwiseSectionConflicts(validated.get(i), validated.get(j)));
            }
        }
        // 与未受影响已发布计划的冲突：排除全部旧计划（其时隙正在释放）与替代计划自身
        Set<Long> excludeIds = new LinkedHashSet<>(oldPlanIds);
        validated.forEach(p -> excludeIds.add(p.replacement().id()));
        for (ValidatedPair p : validated) {
            conflicts.addAll(findConflictsWithPublished(p.replacement(), excludeIds));
        }
        if (!conflicts.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_CONFLICT",
                    "替代计划存在时隙冲突，切换未生效", conflicts);
        }
    }

    // ---------- 占用与链辅助 ----------

    /**
     * 重新计算窗口当前相交的 PUBLISHED 计划 id 集合。
     */
    private Set<Long> affectedPlanIds(DisruptionSwitch sw) {
        return intersectingOccupanciesByPlan(sw).keySet();
    }

    private Map<Long, List<Occupancy>> intersectingOccupanciesByPlan(DisruptionSwitch sw) {
        List<Occupancy> intersecting = planRepo.findPublishedOccupanciesIntersecting(
                sw.sectionId(), sw.windowStartUtc(), sw.windowEndUtc());
        Map<Long, List<Occupancy>> byPlan = new LinkedHashMap<>();
        for (Occupancy o : intersecting) {
            byPlan.computeIfAbsent(o.planId(), k -> new ArrayList<>()).add(o);
        }
        return byPlan;
    }

    /**
     * 判断 targetPlanId 是否位于 anchorPlanId 的改签链上（任意层祖先或后继）。
     */
    private boolean isInRescheduleChain(long anchorPlanId, long targetPlanId) {
        long cursor = anchorPlanId;
        while (true) {
            var link = planRepo.findLinkBySuccessor(cursor);
            if (link.isEmpty()) {
                break;
            }
            cursor = link.get().predecessorPlanId();
            if (cursor == targetPlanId) {
                return true;
            }
        }
        cursor = anchorPlanId;
        while (true) {
            var link = planRepo.findLinkByPredecessor(cursor);
            if (link.isEmpty()) {
                break;
            }
            cursor = link.get().successorPlanId();
            if (cursor == targetPlanId) {
                return true;
            }
        }
        return false;
    }

    private List<Map<String, Object>> findTrainOverlaps(String scheduleKey,
                                                        List<Occupancy> occupancies) {
        List<Map<String, Object>> conflicts = new ArrayList<>();
        Map<String, List<Occupancy>> byTrain = new LinkedHashMap<>();
        for (Occupancy o : occupancies) {
            byTrain.computeIfAbsent(o.trainNo(), k -> new ArrayList<>()).add(o);
        }
        byTrain.forEach((trainNo, list) -> {
            List<Occupancy> sorted = list.stream()
                    .sorted(Comparator.comparing(Occupancy::startUtc)).toList();
            for (int i = 1; i < sorted.size(); i++) {
                Occupancy prev = sorted.get(i - 1);
                Occupancy cur = sorted.get(i);
                if (cur.startUtc().isBefore(prev.endUtc())) {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("type", "TRAIN_OVERLAP");
                    detail.put("scheduleKey", scheduleKey);
                    detail.put("trainNo", trainNo);
                    detail.put("sectionId", cur.sectionId());
                    detail.put("startUtc", cur.startUtc().toString());
                    detail.put("endUtc", cur.endUtc().toString());
                    conflicts.add(detail);
                }
            }
        });
        return conflicts;
    }

    private List<Map<String, Object>> findPairwiseSectionConflicts(ValidatedPair a,
                                                                    ValidatedPair b) {
        List<Occupancy> occA = planRepo.findOccupancies(a.replacement().id());
        List<Occupancy> occB = planRepo.findOccupancies(b.replacement().id());
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (Occupancy oa : occA) {
            for (Occupancy ob : occB) {
                if (oa.sectionId().equals(ob.sectionId())
                        && oa.startUtc().isBefore(ob.endUtc())
                        && ob.startUtc().isBefore(oa.endUtc())) {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("type", "SECTION_CONFLICT");
                    detail.put("sectionId", oa.sectionId());
                    detail.put("scheduleKey", a.replacement().scheduleKey());
                    detail.put("conflictingScheduleKey", b.replacement().scheduleKey());
                    detail.put("trainNo", oa.trainNo());
                    detail.put("startUtc", oa.startUtc().toString());
                    detail.put("endUtc", oa.endUtc().toString());
                    conflicts.add(detail);
                }
            }
        }
        return conflicts;
    }

    private List<Map<String, Object>> findConflictsWithPublished(DayPlan replacement,
                                                                 Set<Long> excludePlanIds) {
        List<Occupancy> occupancies = planRepo.findOccupancies(replacement.id());
        Collection<String> sectionIds = occupancies.stream()
                .map(Occupancy::sectionId)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        List<PublishedSlot> published = planRepo.findPublishedSlots(replacement.opDate(),
                sectionIds, excludePlanIds);
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (Occupancy o : occupancies) {
            for (PublishedSlot slot : published) {
                if (!o.sectionId().equals(slot.sectionId())) {
                    continue;
                }
                if (o.startUtc().isBefore(slot.endUtc())
                        && slot.startUtc().isBefore(o.endUtc())) {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("type", "SECTION_CONFLICT");
                    detail.put("sectionId", o.sectionId());
                    detail.put("scheduleKey", replacement.scheduleKey());
                    detail.put("conflictingScheduleKey", slot.scheduleKey());
                    detail.put("trainNo", o.trainNo());
                    detail.put("startUtc", o.startUtc().toString());
                    detail.put("endUtc", o.endUtc().toString());
                    conflicts.add(detail);
                }
            }
        }
        return conflicts;
    }

    // ---------- 视图构建 ----------

    private DisruptionDetailResponse buildLiveDetail(DisruptionSwitch sw,
                                                     List<ValidatedPair> pairs) {
        List<DisruptionMappingView> mappingViews = pairs.stream()
                .map(p -> new DisruptionMappingView(p.oldPlan().scheduleKey(),
                        p.replacement().scheduleKey()))
                .toList();
        List<DisruptionPlanSnapshotView> before = pairs.stream()
                .map(p -> fullPlanSnapshot(planRepo.findByKey(p.oldPlan().scheduleKey()).orElseThrow()))
                .sorted(Comparator.comparing(DisruptionPlanSnapshotView::scheduleKey))
                .toList();
        List<DisruptionPlanSnapshotView> after = pairs.stream()
                .map(p -> fullPlanSnapshot(
                        planRepo.findByKey(p.replacement().scheduleKey()).orElseThrow()))
                .sorted(Comparator.comparing(DisruptionPlanSnapshotView::scheduleKey))
                .toList();
        return new DisruptionDetailResponse(switchView(sw), mappingViews, before, after);
    }

    private DisruptionDetailResponse buildActivationSnapshot(DisruptionSwitch storedSwitch,
                                                             List<ValidatedPair> validated) {
        DisruptionSwitch active = disruptionRepo.findSwitchByKey(storedSwitch.switchKey())
                .orElseThrow();
        List<DisruptionMappingView> mappingViews = validated.stream()
                .map(p -> new DisruptionMappingView(p.oldPlan().scheduleKey(),
                        p.replacement().scheduleKey()))
                .toList();
        List<DisruptionPlanSnapshotView> before = validated.stream()
                .map(p -> fullPlanSnapshot(
                        planRepo.findByKey(p.oldPlan().scheduleKey()).orElseThrow()))
                .sorted(Comparator.comparing(DisruptionPlanSnapshotView::scheduleKey))
                .toList();
        List<DisruptionPlanSnapshotView> after = validated.stream()
                .map(p -> fullPlanSnapshot(
                        planRepo.findByKey(p.replacement().scheduleKey()).orElseThrow()))
                .sorted(Comparator.comparing(DisruptionPlanSnapshotView::scheduleKey))
                .toList();
        return new DisruptionDetailResponse(switchView(active), mappingViews, before, after);
    }

    private DisruptionPlanSnapshotView fullPlanSnapshot(DayPlan plan) {
        return planSnapshot(plan, planRepo.findOccupancies(plan.id()));
    }

    private DisruptionPlanSnapshotView planSnapshot(DayPlan plan, List<Occupancy> occupancies) {
        List<OccupancyView> views = occupancies.stream()
                .map(o -> new OccupancyView(o.trainNo(), o.sectionId(), o.startUtc(), o.endUtc()))
                .toList();
        return new DisruptionPlanSnapshotView(plan.scheduleKey(), plan.version(),
                plan.status().name(), views);
    }

    private DisruptionSwitchView switchView(DisruptionSwitch sw) {
        return new DisruptionSwitchView(sw.switchKey(), sw.sectionId(),
                Instant.ofEpochMilli(sw.windowStartUtc()), Instant.ofEpochMilli(sw.windowEndUtc()),
                sw.status().name());
    }

    // ---------- 幂等与哈希 ----------

    private <T> Optional<T> replayIfPresent(String opType, String requestId, String hash,
                                            Class<T> type) {
        return idemRepo.find(opType, requestId).map(record -> {
            if (!record.requestHash().equals(hash)) {
                throw conflict("IDEMPOTENT_KEY_REUSED",
                        "requestId 已用于其他参数: " + requestId);
            }
            return fromJson(record.responseJson(), type);
        });
    }

    private String hashRegister(DisruptionRegisterRequest req) {
        return sha256(OP_REGISTER + '\n' + req.switchKey() + '\n' + req.sectionId() + '\n'
                + req.windowStartUtc().toEpochMilli() + '\n' + req.windowEndUtc().toEpochMilli());
    }

    private String hashActivate(String switchKey, List<DisruptionMappingItem> mappings) {
        StringBuilder sb = new StringBuilder(OP_ACTIVATE).append('\n').append(switchKey);
        mappings.stream()
                .map(m -> m.oldScheduleKey() + "|" + m.replacementScheduleKey())
                .sorted()
                .forEach(line -> sb.append('\n').append(line));
        return sha256(sb.toString());
    }

    /**
     * 规范化映射项：校验旧键、替代键各自不重复，按旧计划业务键排序（换序视为同参）。
     */
    private Map<String, String> normalizePairs(List<DisruptionMappingItem> items) {
        Map<String, String> pairs = new TreeMap<>();
        Set<String> replacements = new HashSet<>();
        for (DisruptionMappingItem item : items) {
            if (pairs.put(item.oldScheduleKey(), item.replacementScheduleKey()) != null) {
                throw unprocessable("MAPPING_DUPLICATE",
                        "旧计划在映射中重复: " + item.oldScheduleKey());
            }
            if (!replacements.add(item.replacementScheduleKey())) {
                throw unprocessable("MAPPING_DUPLICATE",
                        "替代计划不得重复: " + item.replacementScheduleKey());
            }
        }
        return pairs;
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

    private String toJson(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("快照反序列化失败", e);
        }
    }

    private DisruptionSwitch loadSwitch(String switchKey) {
        return disruptionRepo.findSwitchByKey(switchKey)
                .orElseThrow(() -> switchNotFound(switchKey));
    }

    private ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", message);
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    private ApiException unprocessable(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    private ApiException switchNotFound(String switchKey) {
        return new ApiException(HttpStatus.NOT_FOUND, "SWITCH_NOT_FOUND",
                "封锁切换单不存在: " + switchKey);
    }

    private ApiException planNotFound(String scheduleKey) {
        return new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND",
                "计划不存在: " + scheduleKey);
    }

    /**
     * 校验通过的旧计划-替代计划对。
     */
    private record ValidatedPair(DayPlan oldPlan, DayPlan replacement) {
    }
}
