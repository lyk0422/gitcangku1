package com.example.starter.plan.service;

import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.PlanStatus;
import com.example.starter.plan.model.ReplacementLink;
import com.example.starter.plan.model.SectionSwitch;
import com.example.starter.plan.model.SwitchStatus;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.repo.SwitchRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.OccupancyView;
import com.example.starter.plan.web.dto.SwitchActivateRequest;
import com.example.starter.plan.web.dto.SwitchDetailResponse;
import com.example.starter.plan.web.dto.SwitchMappingItem;
import com.example.starter.plan.web.dto.SwitchMappingView;
import com.example.starter.plan.web.dto.SwitchPlanView;
import com.example.starter.plan.web.dto.SwitchPreviewResponse;
import com.example.starter.plan.web.dto.SwitchRegisterRequest;
import com.example.starter.plan.web.dto.SwitchView;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
 * 区段封锁切换单核心业务：登记、预览、原子激活与查询。
 *
 * <p>激活在单事务（全局发布锁 + 全部相关计划按 id 顺序行锁）内重新计算与封锁窗口相交的
 * PUBLISHED 影响集合，依次校验：提交集合完整（无遗漏/多余）、旧计划为 PUBLISHED 且版本匹配、
 * 替代计划为 DRAFT、版本匹配、与旧计划同运营日、替代不重复、互不为旧集合成员、
 * 不存在改签链/替代链上的祖先后继关系、替代计划内部列车时隙不重叠、替代计划彼此之间
 * 及与未受影响已发布计划的区段时隙不冲突。任一失败整体回滚并返回 409/422。
 *
 * <p>全部通过后：切换单 ACTIVE，旧计划 SUSPENDED（状态翻转即释放已发布时隙，占用历史保留），
 * 替代计划 PUBLISHED，写入不可变一对一替代链与完整切换快照。
 *
 * <p>幂等：登记/激活分别按 (opType, requestKey) 幂等，同键同参（映射换序视为同参）重放首次快照，
 * 同键异参 409，失败不占键；switchKey 换请求登记复用 409。
 */
@Service
public class SwitchService {

    private static final String OP_REGISTER = "SWITCH_REGISTER";
    private static final String OP_ACTIVATE = "SWITCH_ACTIVATE";

    private final PlanRepository planRepo;
    private final SwitchRepository switchRepo;
    private final IdempotencyRepository idemRepo;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public SwitchService(PlanRepository planRepo, SwitchRepository switchRepo,
                         IdempotencyRepository idemRepo, ObjectMapper objectMapper,
                         PlatformTransactionManager txManager) {
        this.planRepo = planRepo;
        this.switchRepo = switchRepo;
        this.idemRepo = idemRepo;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 登记区段封锁切换单（REGISTERED）。窗口必须为合法左闭右开区间。
     */
    public SwitchDetailResponse register(SwitchRegisterRequest req) {
        if (!req.endUtc().isAfter(req.startUtc())) {
            throw badRequest("封锁窗口结束时刻必须晚于开始时刻");
        }
        String hash = hashRegister(req);
        Optional<SwitchDetailResponse> replay = replayIfPresent(OP_REGISTER, req.requestKey(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                long now = System.currentTimeMillis();
                switchRepo.insertSwitch(req.switchKey(), req.sectionId(),
                        req.startUtc(), req.endUtc(), now);
                SwitchDetailResponse response = buildDetail(
                        switchRepo.findByKey(req.switchKey()).orElseThrow(IllegalStateException::new),
                        List.of());
                idemRepo.insert(OP_REGISTER, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            Optional<SwitchDetailResponse> replayAgain = replayIfPresent(OP_REGISTER, req.requestKey(), hash);
            if (replayAgain.isPresent()) {
                return replayAgain.get();
            }
            if (idemRepo.find(OP_REGISTER, req.requestKey()).isPresent()) {
                throw conflict("IDEMPOTENT_KEY_REUSED",
                        "requestKey 已用于其他参数: " + req.requestKey());
            }
            throw conflict("SWITCH_KEY_EXISTS", "switchKey 已存在: " + req.switchKey());
        }
    }

    /**
     * 预览与封锁窗口相交的全部 PUBLISHED 计划、版本与占用，只读不写。
     */
    public SwitchPreviewResponse preview(String switchKey) {
        SectionSwitch sw = switchRepo.findByKey(switchKey)
                .orElseThrow(() -> switchNotFound(switchKey));
        List<SwitchPlanView> affected = loadAffectedViews(sw);
        return new SwitchPreviewResponse(toView(sw), affected);
    }

    /**
     * 提交完整旧→替代映射并在单事务内原子激活。
     */
    public SwitchDetailResponse activate(String switchKey, SwitchActivateRequest req) {
        String hash = hashActivate(switchKey, req);
        Optional<SwitchDetailResponse> replay = replayIfPresent(OP_ACTIVATE, req.requestKey(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> doActivate(switchKey, req, hash));
        } catch (DuplicateKeyException e) {
            Optional<SwitchDetailResponse> replayAgain = replayIfPresent(OP_ACTIVATE, req.requestKey(), hash);
            if (replayAgain.isPresent()) {
                return replayAgain.get();
            }
            if (idemRepo.find(OP_ACTIVATE, req.requestKey()).isPresent()) {
                throw conflict("IDEMPOTENT_KEY_REUSED",
                        "requestKey 已用于其他参数: " + req.requestKey());
            }
            throw conflict("LINK_CONFLICT", "替代关联冲突，激活未生效");
        }
    }

    /**
     * 查询切换单、映射与前后占用，只读不写；ACTIVE 返回不可变快照内容。
     */
    public SwitchDetailResponse getSwitch(String switchKey) {
        SectionSwitch sw = switchRepo.findByKey(switchKey)
                .orElseThrow(() -> switchNotFound(switchKey));
        if (sw.status() == SwitchStatus.ACTIVE) {
            return fromJson(sw.snapshotJson(), SwitchDetailResponse.class);
        }
        return buildDetail(sw, List.of());
    }

    // ---------- 激活事务主体 ----------

    private SwitchDetailResponse doActivate(String switchKey, SwitchActivateRequest req, String hash) {
        // 全局发布锁：与普通发布/取消（行锁）/改签按事务提交顺序裁决
        planRepo.acquirePublishLock();
        SectionSwitch sw = switchRepo.findByKeyForUpdate(switchKey)
                .orElseThrow(() -> switchNotFound(switchKey));
        if (sw.status() == SwitchStatus.ACTIVE) {
            // 并发下另一事务可能已用同 requestKey 激活成功：重放首次快照，否则 409
            Optional<SwitchDetailResponse> replayAgain =
                    replayIfPresent(OP_ACTIVATE, req.requestKey(), hash);
            if (replayAgain.isPresent()) {
                return replayAgain.get();
            }
            throw conflict("SWITCH_ALREADY_ACTIVE", "切换单已激活，不可重复激活: " + switchKey);
        }

        List<SwitchMappingItem> mappings = canonicalMappings(req.mappings());

        // 引用的计划必须全部存在；旧集合与替代集合各自不重复且互不相交
        Map<String, DayPlan> referenced = new LinkedHashMap<>();
        Set<Long> lockIds = new TreeSet<>();
        for (SwitchMappingItem m : mappings) {
            DayPlan oldPlan = planRepo.findByKey(m.oldScheduleKey())
                    .orElseThrow(() -> conflict("MAPPING_CONFLICT",
                            "映射引用的旧计划不存在: " + m.oldScheduleKey()));
            DayPlan replPlan = planRepo.findByKey(m.replacementScheduleKey())
                    .orElseThrow(() -> conflict("MAPPING_CONFLICT",
                            "映射引用的替代计划不存在: " + m.replacementScheduleKey()));
            referenced.put(oldPlan.scheduleKey(), oldPlan);
            referenced.put(replPlan.scheduleKey(), replPlan);
            lockIds.add(oldPlan.id());
            lockIds.add(replPlan.id());
        }
        // 按 id 统一顺序加行锁，避免与改签/取消并发时的死锁
        Map<Long, DayPlan> locked = new TreeMap<>();
        for (DayPlan p : planRepo.findByIdsForUpdate(lockIds)) {
            locked.put(p.id(), p);
        }

        Set<Long> oldIds = new HashSet<>();
        Set<Long> replacementIds = new HashSet<>();
        List<DayPlan> oldPlans = new ArrayList<>();
        List<DayPlan> replacementPlans = new ArrayList<>();
        for (SwitchMappingItem m : mappings) {
            DayPlan oldPlan = referenced.get(m.oldScheduleKey());
            DayPlan replPlan = referenced.get(m.replacementScheduleKey());
            DayPlan lockedOld = locked.get(oldPlan.id());
            DayPlan lockedRepl = locked.get(replPlan.id());
            if (!oldIds.add(lockedOld.id())) {
                throw conflict("MAPPING_CONFLICT", "旧计划重复提交: " + m.oldScheduleKey());
            }
            if (!replacementIds.add(lockedRepl.id())) {
                throw conflict("MAPPING_CONFLICT",
                        "替代计划不得重复: " + m.replacementScheduleKey());
            }
            if (oldIds.contains(lockedRepl.id()) || replacementIds.contains(lockedOld.id())) {
                throw conflict("LINK_CONFLICT",
                        "替代计划不能同时是本次旧计划集合成员: " + m.replacementScheduleKey());
            }
            oldPlans.add(lockedOld);
            replacementPlans.add(lockedRepl);
        }

        // 重新计算影响集合：必须与提交的旧计划集合完全一致
        List<Long> affectedIds = switchRepo.findPublishedPlanIdsIntersecting(
                sw.sectionId(), sw.startUtc(), sw.endUtc());
        Set<Long> affectedSet = new HashSet<>(affectedIds);
        for (Long id : affectedIds) {
            if (!oldIds.contains(id)) {
                DayPlan missing = locked.containsKey(id)
                        ? locked.get(id)
                        : planRepo.findById(id).orElseThrow(IllegalStateException::new);
                throw conflict("AFFECTED_SET_MISMATCH",
                        "提交集合遗漏了与封锁窗口相交的已发布计划: " + missing.scheduleKey());
            }
        }
        for (Long id : oldIds) {
            if (!affectedSet.contains(id)) {
                throw conflict("AFFECTED_SET_MISMATCH",
                        "提交集合包含与封锁窗口不相交或非已发布的多余计划: " + locked.get(id).scheduleKey());
            }
        }

        // 逐条校验状态、版本、同运营日、既有替代链、改签链/替代链环
        for (int i = 0; i < mappings.size(); i++) {
            SwitchMappingItem m = mappings.get(i);
            DayPlan oldPlan = oldPlans.get(i);
            DayPlan replPlan = replacementPlans.get(i);
            if (oldPlan.status() != PlanStatus.PUBLISHED) {
                throw conflict("PLAN_STATE_CONFLICT",
                        "旧计划必须为已发布状态: " + oldPlan.scheduleKey() + " 当前 " + oldPlan.status());
            }
            if (m.expectedOldVersion() != oldPlan.version()) {
                throw conflict("VERSION_CONFLICT",
                        "旧计划 " + oldPlan.scheduleKey() + " expectedOldVersion="
                                + m.expectedOldVersion() + " 与当前版本 " + oldPlan.version() + " 不一致");
            }
            if (replPlan.status() != PlanStatus.DRAFT) {
                throw conflict("PLAN_STATE_CONFLICT",
                        "替代计划必须为草稿: " + replPlan.scheduleKey() + " 当前 " + replPlan.status());
            }
            if (m.expectedReplacementVersion() != replPlan.version()) {
                throw conflict("VERSION_CONFLICT",
                        "替代计划 " + replPlan.scheduleKey() + " expectedReplacementVersion="
                                + m.expectedReplacementVersion()
                                + " 与当前版本 " + replPlan.version() + " 不一致");
            }
            if (!oldPlan.opDate().equals(replPlan.opDate())) {
                throw conflict("OP_DATE_MISMATCH",
                        "替代计划与旧计划运营日必须相同: 旧=" + oldPlan.opDate()
                                + " 替代=" + replPlan.opDate());
            }
            if (switchRepo.findReplacementByOldPlan(oldPlan.id()).isPresent()) {
                throw conflict("LINK_CONFLICT",
                        "旧计划已存在替代关联: " + oldPlan.scheduleKey());
            }
            if (switchRepo.findReplacementByReplacementPlan(replPlan.id()).isPresent()) {
                throw conflict("LINK_CONFLICT",
                        "替代计划已用于其他切换: " + replPlan.scheduleKey());
            }
            if (areOnSameRescheduleChain(oldPlan, replPlan)) {
                throw conflict("LINK_CONFLICT",
                        "替代计划不能是旧计划改签链上的祖先或后继: " + replPlan.scheduleKey());
            }
            if (areOnSameReplacementChain(oldPlan, replPlan)) {
                throw conflict("LINK_CONFLICT",
                        "替代计划不能是旧计划替代链上的祖先或后继: " + replPlan.scheduleKey());
            }
        }

        // 时隙校验：替代计划内部列车重叠、替代计划彼此区段冲突、与未受影响已发布计划区段冲突
        List<List<Occupancy>> replacementOccupancies = new ArrayList<>();
        for (DayPlan replPlan : replacementPlans) {
            replacementOccupancies.add(planRepo.findOccupancies(replPlan.id()));
        }
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (int i = 0; i < replacementPlans.size(); i++) {
            conflicts.addAll(findTrainOverlaps(replacementPlans.get(i), replacementOccupancies.get(i)));
        }
        conflicts.addAll(findReplacementMutualConflicts(replacementPlans, replacementOccupancies));
        conflicts.addAll(findUnaffectedPublishedConflicts(
                oldPlans, replacementPlans, replacementOccupancies, oldIds, replacementIds));
        if (!conflicts.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_CONFLICT",
                    "替代计划存在时隙冲突，切换未生效", conflicts);
        }

        // 全部校验通过：翻转状态、写一对一替代链、快照与幂等记录
        long now = System.currentTimeMillis();
        List<long[]> pairs = new ArrayList<>();
        for (int i = 0; i < mappings.size(); i++) {
            planRepo.updateStatus(oldPlans.get(i).id(), PlanStatus.SUSPENDED, now);
            planRepo.updateStatus(replacementPlans.get(i).id(), PlanStatus.PUBLISHED, now);
            pairs.add(new long[]{oldPlans.get(i).id(), replacementPlans.get(i).id()});
        }
        switchRepo.insertReplacementLinks(sw.id(), pairs, now);

        // 以提交后状态重建视图（占用历史保留，状态翻转即释放/占用时隙）
        List<SwitchMappingView> mappingViews = new ArrayList<>();
        for (int i = 0; i < mappings.size(); i++) {
            DayPlan oldPlan = planRepo.findById(oldPlans.get(i).id())
                    .orElseThrow(IllegalStateException::new);
            DayPlan replPlan = planRepo.findById(replacementPlans.get(i).id())
                    .orElseThrow(IllegalStateException::new);
            mappingViews.add(toMappingView(oldPlan, replPlan,
                    planRepo.findOccupancies(oldPlan.id()),
                    planRepo.findOccupancies(replPlan.id())));
        }
        SwitchDetailResponse response = buildDetail(sw, mappingViews, SwitchStatus.ACTIVE);
        String snapshot = toJson(response);
        switchRepo.markActive(sw.id(), snapshot, now);
        idemRepo.insert(OP_ACTIVATE, req.requestKey(), hash, snapshot, now);
        return response;
    }

    // ---------- 时隙检测 ----------

    /**
     * 单个替代计划内同一列车的重叠占用（左闭右开，相邻合法）。
     */
    private List<Map<String, Object>> findTrainOverlaps(DayPlan plan, List<Occupancy> occupancies) {
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
                    detail.put("scheduleKey", plan.scheduleKey());
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

    /**
     * 不同替代计划之间在同一区段上的时隙重叠检测。
     */
    private List<Map<String, Object>> findReplacementMutualConflicts(
            List<DayPlan> plans, List<List<Occupancy>> occupanciesPerPlan) {
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (int i = 0; i < plans.size(); i++) {
            for (int j = i + 1; j < plans.size(); j++) {
                for (Occupancy a : occupanciesPerPlan.get(i)) {
                    for (Occupancy b : occupanciesPerPlan.get(j)) {
                        if (a.sectionId().equals(b.sectionId())
                                && a.startUtc().isBefore(b.endUtc())
                                && b.startUtc().isBefore(a.endUtc())) {
                            Map<String, Object> detail = new LinkedHashMap<>();
                            detail.put("type", "REPLACEMENT_CONFLICT");
                            detail.put("sectionId", a.sectionId());
                            detail.put("scheduleKey", plans.get(i).scheduleKey());
                            detail.put("conflictingScheduleKey", plans.get(j).scheduleKey());
                            detail.put("trainNo", a.trainNo());
                            detail.put("startUtc", a.startUtc().toString());
                            detail.put("endUtc", a.endUtc().toString());
                            conflicts.add(detail);
                        }
                    }
                }
            }
        }
        return conflicts;
    }

    /**
     * 替代计划与未受影响已发布计划（排除全部旧计划与替代计划自身）的区段时隙冲突检测。
     * 旧计划将在同事务内挂起释放时隙，替代计划可合法复用其时隙。
     */
    private List<Map<String, Object>> findUnaffectedPublishedConflicts(
            List<DayPlan> oldPlans, List<DayPlan> replacementPlans,
            List<List<Occupancy>> occupanciesPerPlan, Set<Long> oldIds, Set<Long> replacementIds) {
        List<Map<String, Object>> conflicts = new ArrayList<>();
        Set<Long> excludeIds = new HashSet<>();
        excludeIds.addAll(oldIds);
        excludeIds.addAll(replacementIds);

        // 按运营日分组（不同映射的旧/替代计划理论上可属于不同运营日）
        Map<java.time.LocalDate, List<DayPlan>> byOpDate = new LinkedHashMap<>();
        Map<Long, Integer> planIndex = new LinkedHashMap<>();
        for (int i = 0; i < replacementPlans.size(); i++) {
            DayPlan plan = replacementPlans.get(i);
            byOpDate.computeIfAbsent(plan.opDate(), k -> new ArrayList<>()).add(plan);
            planIndex.put(plan.id(), i);
        }
        byOpDate.forEach((opDate, plansOnDate) -> {
            Set<String> sectionIds = new TreeSet<>();
            for (DayPlan p : plansOnDate) {
                for (Occupancy o : occupanciesPerPlan.get(planIndex.get(p.id()))) {
                    sectionIds.add(o.sectionId());
                }
            }
            List<com.example.starter.plan.model.PublishedSlot> published =
                    planRepo.findPublishedSlots(opDate, sectionIds, excludeIds);
            for (DayPlan p : plansOnDate) {
                for (Occupancy o : occupanciesPerPlan.get(planIndex.get(p.id()))) {
                    for (com.example.starter.plan.model.PublishedSlot slot : published) {
                        if (!o.sectionId().equals(slot.sectionId())) {
                            continue;
                        }
                        if (o.startUtc().isBefore(slot.endUtc())
                                && slot.startUtc().isBefore(o.endUtc())) {
                            Map<String, Object> detail = new LinkedHashMap<>();
                            detail.put("type", "SECTION_CONFLICT");
                            detail.put("sectionId", o.sectionId());
                            detail.put("scheduleKey", p.scheduleKey());
                            detail.put("conflictingScheduleKey", slot.scheduleKey());
                            detail.put("trainNo", o.trainNo());
                            detail.put("startUtc", o.startUtc().toString());
                            detail.put("endUtc", o.endUtc().toString());
                            conflicts.add(detail);
                        }
                    }
                }
            }
        });
        return conflicts;
    }

    // ---------- 链环检测 ----------

    /**
     * 两个计划是否处于同一条改签链上（沿直接前驱/后继任一方向可达）。
     */
    private boolean areOnSameRescheduleChain(DayPlan a, DayPlan b) {
        return reachableBySuccessor(a.id(), b.id()) || reachableByPredecessor(a.id(), b.id());
    }

    private boolean reachableBySuccessor(long startId, long targetId) {
        long cursor = startId;
        Set<Long> seen = new HashSet<>();
        while (seen.add(cursor)) {
            Optional<com.example.starter.plan.model.RescheduleLink> link =
                    planRepo.findLinkByPredecessor(cursor);
            if (link.isEmpty()) {
                return false;
            }
            cursor = link.get().successorPlanId();
            if (cursor == targetId) {
                return true;
            }
        }
        return false;
    }

    private boolean reachableByPredecessor(long startId, long targetId) {
        long cursor = startId;
        Set<Long> seen = new HashSet<>();
        while (seen.add(cursor)) {
            Optional<com.example.starter.plan.model.RescheduleLink> link =
                    planRepo.findLinkBySuccessor(cursor);
            if (link.isEmpty()) {
                return false;
            }
            cursor = link.get().predecessorPlanId();
            if (cursor == targetId) {
                return true;
            }
        }
        return false;
    }

    /**
     * 两个计划是否处于同一条一对一替代链上（沿旧→替代任一方向可达）。
     */
    private boolean areOnSameReplacementChain(DayPlan a, DayPlan b) {
        return replacementReachable(a.id(), b.id(), true)
                || replacementReachable(a.id(), b.id(), false);
    }

    private boolean replacementReachable(long startId, long targetId, boolean forward) {
        long cursor = startId;
        Set<Long> seen = new HashSet<>();
        while (seen.add(cursor)) {
            Optional<ReplacementLink> link = forward
                    ? switchRepo.findReplacementByOldPlan(cursor)
                    : switchRepo.findReplacementByReplacementPlan(cursor);
            if (link.isEmpty()) {
                return false;
            }
            cursor = forward ? link.get().replacementPlanId() : link.get().oldPlanId();
            if (cursor == targetId) {
                return true;
            }
        }
        return false;
    }

    // ---------- 视图装配 ----------

    private List<SwitchPlanView> loadAffectedViews(SectionSwitch sw) {
        List<SwitchPlanView> views = new ArrayList<>();
        for (Long planId : switchRepo.findPublishedPlanIdsIntersecting(
                sw.sectionId(), sw.startUtc(), sw.endUtc())) {
            DayPlan plan = planRepo.findById(planId).orElseThrow(IllegalStateException::new);
            views.add(new SwitchPlanView(plan.scheduleKey(), plan.opDate(), plan.version(),
                    plan.status().name(), toOccupancyViews(planRepo.findOccupancies(plan.id()))));
        }
        return views;
    }

    private SwitchMappingView toMappingView(DayPlan oldPlan, DayPlan replPlan,
                                            List<Occupancy> beforeOccupancies,
                                            List<Occupancy> afterOccupancies) {
        return new SwitchMappingView(oldPlan.scheduleKey(), oldPlan.version(), oldPlan.status().name(),
                replPlan.scheduleKey(), replPlan.version(), replPlan.status().name(),
                toOccupancyViews(beforeOccupancies), toOccupancyViews(afterOccupancies));
    }

    private List<OccupancyView> toOccupancyViews(List<Occupancy> occupancies) {
        return occupancies.stream()
                .map(o -> new OccupancyView(o.trainNo(), o.sectionId(), o.startUtc(), o.endUtc()))
                .toList();
    }

    private SwitchView toView(SectionSwitch sw) {
        return new SwitchView(sw.switchKey(), sw.sectionId(), sw.startUtc(), sw.endUtc(),
                sw.status().name());
    }

    private SwitchDetailResponse buildDetail(SectionSwitch sw, List<SwitchMappingView> mappings) {
        return buildDetail(sw, mappings, sw.status());
    }

    private SwitchDetailResponse buildDetail(SectionSwitch sw, List<SwitchMappingView> mappings,
                                             SwitchStatus responseStatus) {
        SwitchView view = new SwitchView(sw.switchKey(), sw.sectionId(), sw.startUtc(),
                sw.endUtc(), responseStatus.name());
        return new SwitchDetailResponse(view, mappings);
    }

    // ---------- 幂等与哈希 ----------

    /**
     * 映射按旧计划键规范化排序，使映射换序与同参等价。
     */
    private List<SwitchMappingItem> canonicalMappings(List<SwitchMappingItem> mappings) {
        return mappings.stream()
                .sorted(Comparator.comparing(SwitchMappingItem::oldScheduleKey)
                        .thenComparing(SwitchMappingItem::replacementScheduleKey))
                .toList();
    }

    private Optional<SwitchDetailResponse> replayIfPresent(String opType, String requestKey,
                                                           String hash) {
        return idemRepo.find(opType, requestKey).map(record -> {
            if (!record.requestHash().equals(hash)) {
                throw conflict("IDEMPOTENT_KEY_REUSED",
                        "requestKey 已用于其他参数: " + requestKey);
            }
            return fromJson(record.responseJson(), SwitchDetailResponse.class);
        });
    }

    private String hashRegister(SwitchRegisterRequest req) {
        String text = OP_REGISTER + '\n' + req.switchKey() + '\n' + req.sectionId() + '\n'
                + req.startUtc().toEpochMilli() + '\n' + req.endUtc().toEpochMilli();
        return sha256(text);
    }

    private String hashActivate(String switchKey, SwitchActivateRequest req) {
        StringBuilder sb = new StringBuilder(OP_ACTIVATE).append('\n').append(switchKey);
        for (SwitchMappingItem m : canonicalMappings(req.mappings())) {
            sb.append('\n').append(m.oldScheduleKey()).append('|')
                    .append(m.replacementScheduleKey()).append('|')
                    .append(m.expectedOldVersion()).append('|')
                    .append(m.expectedReplacementVersion());
        }
        return sha256(sb.toString());
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

    private ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", message);
    }

    private ApiException switchNotFound(String switchKey) {
        return new ApiException(HttpStatus.NOT_FOUND, "SWITCH_NOT_FOUND",
                "切换单不存在: " + switchKey);
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
