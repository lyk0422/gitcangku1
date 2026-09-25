package com.example.starter.plan.service;

import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.PlanStatus;
import com.example.starter.plan.model.PreemptionRecord;
import com.example.starter.plan.model.PreemptionSection;
import com.example.starter.plan.model.PublishedSlot;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.repo.PreemptionRepository;
import com.example.starter.plan.repo.SectionRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.OccupancyView;
import com.example.starter.plan.web.dto.PlanResponse;
import com.example.starter.plan.web.dto.PreemptionRecordView;
import com.example.starter.plan.web.dto.PreemptionSectionView;
import com.example.starter.plan.web.dto.PublishedSlotView;
import com.example.starter.plan.web.dto.SectionOccupancyView;
import com.example.starter.plan.web.dto.SectionPriorityView;
import com.example.starter.plan.web.dto.SectionSlotView;
import com.example.starter.plan.web.dto.UpdateOccupanciesRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 铁路走廊日计划核心业务：草稿创建/整体替换、发布（含走廊等级抢占）、取消与查询。
 *
 * <p>走廊等级：区段登记 1～5 级（数值越大越高），计划继承其全部占用区段的最高等级，
 * 未登记区段按最低等级 1 参与判定。发布携带 preemptKey 时进入抢占模式：冲突的已发布计划
 * 若全部占用区段等级都低于本计划，则同一事务内将其整体转为 PREEMPTED 终态、写入不可变
 * 抢占记录后发布本计划；任一区段等级不低于本计划则 422 并返回该区段与等级。
 * 同一时隙只允许被抢占一次，重复抢占返回 409。
 *
 * <p>并发与幂等约定：写操作按 (操作类型, requestKey) 幂等，同键同参重放返回首次成功结果，
 * 同键不同参返回 409；发布经全局发布锁串行化，同一计划的更新/发布/取消经行锁按事务提交顺序生效；
 * 仅成功结果写入幂等记录，失败（含 422 时隙冲突、422 抢占条件不满足、409 重复抢占）不缓存、可修正后重试。
 */
@Service
public class PlanService {

    /** 运营日解释时区。 */
    public static final ZoneId OPERATION_ZONE = ZoneId.of("Asia/Shanghai");

    private static final String OP_CREATE = "CREATE";
    private static final String OP_UPDATE = "UPDATE";
    private static final String OP_PUBLISH = "PUBLISH";
    private static final String OP_CANCEL = "CANCEL";

    private final PlanRepository planRepo;
    private final IdempotencyRepository idemRepo;
    private final SectionRepository sectionRepo;
    private final PreemptionRepository preemptionRepo;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public PlanService(PlanRepository planRepo, IdempotencyRepository idemRepo,
                       SectionRepository sectionRepo, PreemptionRepository preemptionRepo,
                       ObjectMapper objectMapper, PlatformTransactionManager txManager) {
        this.planRepo = planRepo;
        this.idemRepo = idemRepo;
        this.sectionRepo = sectionRepo;
        this.preemptionRepo = preemptionRepo;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 创建草稿计划（版本 1，状态 DRAFT）。
     */
    public PlanResponse createDraft(CreatePlanRequest req) {
        validateOccupancyParams(req.occupancies());
        validateWithinOperationDay(req.occupancies(), req.opDate());
        String hash = hashCreate(req);
        Optional<PlanResponse> replay = replayIfPresent(OP_CREATE, req.requestKey(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                // 事务内复核幂等记录：并发同键请求可能已被首次请求提交
                Optional<PlanResponse> replayInTx = replayIfPresent(OP_CREATE, req.requestKey(), hash);
                if (replayInTx.isPresent()) {
                    return replayInTx.get();
                }
                if (planRepo.findByKey(req.scheduleKey()).isPresent()) {
                    throw conflict("SCHEDULE_KEY_EXISTS", "scheduleKey 已存在: " + req.scheduleKey());
                }
                long now = System.currentTimeMillis();
                long planId = planRepo.insertPlan(req.scheduleKey(), req.opDate(), PlanStatus.DRAFT, now);
                planRepo.insertOccupancies(planId, toOccupancies(planId, req.occupancies()));
                PlanResponse response = loadPlan(req.scheduleKey());
                idemRepo.insert(OP_CREATE, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return resolveDuplicate(OP_CREATE, req.requestKey(), hash);
        }
    }

    /**
     * 整体替换草稿占用清单，版本加一；仅 DRAFT 可改，expectedVersion 必须匹配。
     */
    public PlanResponse replaceOccupancies(String scheduleKey, UpdateOccupanciesRequest req) {
        validateOccupancyParams(req.occupancies());
        String hash = hashUpdate(scheduleKey, req);
        Optional<PlanResponse> replay = replayIfPresent(OP_UPDATE, req.requestKey(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                DayPlan plan = planRepo.findByKeyForUpdate(scheduleKey)
                        .orElseThrow(() -> notFound(scheduleKey));
                // 行锁内复核幂等记录：并发同键请求在锁等待期间可能已被首次请求提交
                Optional<PlanResponse> replayInTx = replayIfPresent(OP_UPDATE, req.requestKey(), hash);
                if (replayInTx.isPresent()) {
                    return replayInTx.get();
                }
                if (plan.status() != PlanStatus.DRAFT) {
                    throw conflict("PLAN_STATE_CONFLICT",
                            "仅草稿可修改占用，当前状态: " + plan.status());
                }
                if (req.expectedVersion() != plan.version()) {
                    throw conflict("VERSION_CONFLICT",
                            "expectedVersion=" + req.expectedVersion() + " 与当前版本 " + plan.version() + " 不一致");
                }
                validateWithinOperationDay(req.occupancies(), plan.opDate());
                long now = System.currentTimeMillis();
                planRepo.replaceOccupancies(plan.id(), toOccupancies(plan.id(), req.occupancies()));
                planRepo.updateVersionAndStatus(plan.id(), plan.version() + 1, PlanStatus.DRAFT, now);
                PlanResponse response = loadPlan(scheduleKey);
                idemRepo.insert(OP_UPDATE, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return resolveDuplicate(OP_UPDATE, req.requestKey(), hash);
        }
    }

    /**
     * 发布计划：全局发布锁内原子校验本计划列车重叠与跨计划区段重叠。
     *
     * <p>无冲突直接发布；有冲突且未提交 preemptKey 时按原规则抛出 422（携带冲突区段与计划）。
     * 提交 preemptKey 时进入抢占模式：同一事务内把符合条件的被抢占计划整体转为 PREEMPTED
     * 并写入不可变抢占记录，随后发布本计划；任一冲突整单回滚且已发布计划不变。
     */
    public PlanResponse publish(String scheduleKey, String requestKey, String preemptKey) {
        String normalizedPreemptKey = (preemptKey == null || preemptKey.isBlank()) ? null : preemptKey;
        String hash = hashAction(OP_PUBLISH, scheduleKey, normalizedPreemptKey);
        Optional<PlanResponse> replay = replayIfPresent(OP_PUBLISH, requestKey, hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                // 锁内复核幂等记录：并发同键请求在锁等待期间可能已被首次请求提交
                Optional<PlanResponse> replayInTx = replayIfPresent(OP_PUBLISH, requestKey, hash);
                if (replayInTx.isPresent()) {
                    return replayInTx.get();
                }
                DayPlan plan = planRepo.findByKeyForUpdate(scheduleKey)
                        .orElseThrow(() -> notFound(scheduleKey));
                if (plan.status() != PlanStatus.DRAFT) {
                    throw conflict("PLAN_STATE_CONFLICT",
                            "仅草稿可发布，当前状态: " + plan.status());
                }
                List<Occupancy> occupancies = planRepo.findOccupancies(plan.id());
                List<Map<String, Object>> trainOverlaps = findTrainOverlaps(scheduleKey, occupancies);
                if (!trainOverlaps.isEmpty()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_CONFLICT",
                            "存在时隙冲突，计划保持草稿", trainOverlaps);
                }
                List<SectionConflict> conflicts = findSectionConflicts(plan, occupancies);
                if (!conflicts.isEmpty()) {
                    if (normalizedPreemptKey == null) {
                        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_CONFLICT",
                                "存在时隙冲突，计划保持草稿",
                                toConflictDetails(scheduleKey, conflicts));
                    }
                    executePreemption(plan, occupancies, conflicts, normalizedPreemptKey);
                }
                long now = System.currentTimeMillis();
                planRepo.updateStatus(plan.id(), PlanStatus.PUBLISHED, now);
                PlanResponse response = loadPlan(scheduleKey);
                idemRepo.insert(OP_PUBLISH, requestKey, hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return resolveDuplicate(OP_PUBLISH, requestKey, hash);
        }
    }

    /**
     * 取消已发布计划：时隙立即释放，历史计划与占用保留不改写。
     */
    public PlanResponse cancel(String scheduleKey, String requestKey) {
        String hash = hashAction(OP_CANCEL, scheduleKey, null);
        Optional<PlanResponse> replay = replayIfPresent(OP_CANCEL, requestKey, hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                DayPlan plan = planRepo.findByKeyForUpdate(scheduleKey)
                        .orElseThrow(() -> notFound(scheduleKey));
                // 行锁内复核幂等记录：并发同键请求在锁等待期间可能已被首次请求提交
                Optional<PlanResponse> replayInTx = replayIfPresent(OP_CANCEL, requestKey, hash);
                if (replayInTx.isPresent()) {
                    return replayInTx.get();
                }
                if (plan.status() != PlanStatus.PUBLISHED) {
                    throw conflict("PLAN_STATE_CONFLICT",
                            "仅已发布计划可取消，当前状态: " + plan.status());
                }
                long now = System.currentTimeMillis();
                planRepo.updateStatus(plan.id(), PlanStatus.CANCELLED, now);
                PlanResponse response = loadPlan(scheduleKey);
                idemRepo.insert(OP_CANCEL, requestKey, hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return resolveDuplicate(OP_CANCEL, requestKey, hash);
        }
    }

    /**
     * 按计划业务键查询明细（含历史占用），不存在返回 404。
     */
    public PlanResponse getPlan(String scheduleKey) {
        if (planRepo.findByKey(scheduleKey).isEmpty()) {
            throw notFound(scheduleKey);
        }
        return loadPlan(scheduleKey);
    }

    /**
     * 查询指定运营日与区段上当前已发布的生效时隙。
     */
    public List<PublishedSlotView> getPublishedSlots(LocalDate opDate, String sectionId) {
        return planRepo.findPublishedSlots(opDate, List.of(sectionId), -1L).stream()
                .map(s -> new PublishedSlotView(s.scheduleKey(), s.trainNo(), s.sectionId(),
                        s.startUtc(), s.endUtc()))
                .toList();
    }

    /**
     * 登记或更新区段走廊等级（1～5，幂等 upsert）。
     */
    public SectionPriorityView registerSectionPriority(String sectionId, Integer priority) {
        if (priority == null || priority < 1 || priority > 5) {
            throw badRequest("走廊等级必须为 1～5 的整数: " + priority);
        }
        long now = System.currentTimeMillis();
        sectionRepo.upsert(sectionId, priority, now);
        return new SectionPriorityView(sectionId, priority);
    }

    /**
     * 按区段查询当前等级与生效占用；opDate 为 null 时不限运营日。
     */
    public SectionOccupancyView getSectionOccupancy(String sectionId, LocalDate opDate) {
        Integer priority = sectionRepo.findPriority(sectionId).orElse(null);
        int effectiveLevel = priority == null ? SectionRepository.DEFAULT_PRIORITY : priority;
        List<SectionSlotView> slots = planRepo.findPublishedSlotsBySection(sectionId, opDate).stream()
                .map(s -> new SectionSlotView(s.scheduleKey(), s.trainNo(), s.opDate(),
                        s.startUtc(), s.endUtc()))
                .toList();
        return new SectionOccupancyView(sectionId, priority, effectiveLevel, slots);
    }

    /**
     * 查询不可变抢占记录；opDate、sectionId 均可为 null 表示不过滤。
     */
    public List<PreemptionRecordView> getPreemptionRecords(LocalDate opDate, String sectionId) {
        List<PreemptionRecord> records = preemptionRepo.findRecords(opDate, sectionId);
        Map<Long, List<PreemptionSection>> sectionsByRecord = new LinkedHashMap<>();
        preemptionRepo.findSections(records.stream().map(PreemptionRecord::id).toList())
                .forEach(s -> sectionsByRecord
                        .computeIfAbsent(s.recordId(), k -> new ArrayList<>()).add(s));
        return records.stream()
                .map(r -> new PreemptionRecordView(r.id(), r.opDate(), r.preemptingScheduleKey(),
                        r.preemptedScheduleKey(), r.preemptingLevel(), r.preemptedLevel(),
                        r.preemptKey(),
                        sectionsByRecord.getOrDefault(r.id(), List.of()).stream()
                                .map(s -> new PreemptionSectionView(s.sectionId(), s.sectionLevel(),
                                        s.startUtc(), s.endUtc()))
                                .toList(),
                        r.createdAt()))
                .toList();
    }

    // ---------- 抢占实现 ----------

    /**
     * 本计划一条占用与已发布计划一条时隙的区间冲突（左闭右开判定后的结果）。
     */
    private record SectionConflict(Occupancy mine, PublishedSlot slot) {
    }

    /**
     * 抢占校验与执行（须在持有全局发布锁的事务内调用）：
     * <ol>
     *   <li>同一时隙只能被抢占一次：任一冲突时隙曾与历史抢占记录重叠 → 409；</li>
     *   <li>行锁复核冲突计划状态，已被并发取消的按时隙已释放跳过；</li>
     *   <li>被抢占方任一占用区段等级不低于本计划 → 422 并返回该区段与等级；</li>
     *   <li>同一事务内条件更新为 PREEMPTED 并写入不可变抢占记录。</li>
     * </ol>
     */
    private void executePreemption(DayPlan plan, List<Occupancy> occupancies,
                                   List<SectionConflict> conflicts, String preemptKey) {
        int draftLevel = corridorLevel(occupancies.stream().map(Occupancy::sectionId).toList());
        // 1. 同一时隙只能被抢占一次
        for (SectionConflict c : conflicts) {
            Instant start = c.mine().startUtc().isAfter(c.slot().startUtc())
                    ? c.mine().startUtc() : c.slot().startUtc();
            Instant end = c.mine().endUtc().isBefore(c.slot().endUtc())
                    ? c.mine().endUtc() : c.slot().endUtc();
            if (preemptionRepo.existsOverlapping(plan.opDate(), c.slot().sectionId(), start, end)) {
                throw conflict("SLOT_ALREADY_PREEMPTED",
                        "同一时隙只能被抢占一次，区段 " + c.slot().sectionId() + " 的该时隙已被抢占过");
            }
        }
        // 2. 按计划分组并行锁复核状态
        Map<String, List<SectionConflict>> conflictsByPlan = new LinkedHashMap<>();
        for (SectionConflict c : conflicts) {
            conflictsByPlan.computeIfAbsent(c.slot().scheduleKey(), k -> new ArrayList<>()).add(c);
        }
        Map<String, DayPlan> targets = new LinkedHashMap<>();
        List<Map<String, Object>> blocking = new ArrayList<>();
        for (Map.Entry<String, List<SectionConflict>> entry : conflictsByPlan.entrySet()) {
            Optional<DayPlan> locked = planRepo.findByKeyForUpdate(entry.getKey());
            if (locked.isEmpty() || locked.get().status() != PlanStatus.PUBLISHED) {
                // 并发取消已提交：时隙随之释放，按事务提交顺序裁决，无需抢占
                continue;
            }
            DayPlan target = locked.get();
            targets.put(entry.getKey(), target);
            // 3. 对方全部占用区段等级都必须低于本计划
            List<Occupancy> targetOccupancies = planRepo.findOccupancies(target.id());
            Map<String, Integer> levels = sectionRepo.findPriorities(
                    targetOccupancies.stream().map(Occupancy::sectionId)
                            .collect(java.util.stream.Collectors.toCollection(TreeSet::new)));
            for (Occupancy o : targetOccupancies) {
                int sectionLevel = levels.getOrDefault(o.sectionId(),
                        SectionRepository.DEFAULT_PRIORITY);
                if (sectionLevel >= draftLevel) {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("type", "PREEMPT_LEVEL_INSUFFICIENT");
                    detail.put("scheduleKey", plan.scheduleKey());
                    detail.put("conflictingScheduleKey", target.scheduleKey());
                    detail.put("sectionId", o.sectionId());
                    detail.put("sectionLevel", sectionLevel);
                    detail.put("draftLevel", draftLevel);
                    blocking.add(detail);
                }
            }
        }
        if (!blocking.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PREEMPT_CONDITION_NOT_MET",
                    "存在等级不低于本计划的占用区段，未满足抢占条件", blocking);
        }
        if (targets.isEmpty()) {
            // 冲突计划均已被并发取消，时隙已释放，直接按普通发布继续
            return;
        }
        // 4. 原子降级并写入不可变抢占记录
        Map<String, Integer> slotSectionLevels = sectionRepo.findPriorities(conflicts.stream()
                .map(c -> c.slot().sectionId())
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new)));
        long now = System.currentTimeMillis();
        for (Map.Entry<String, DayPlan> entry : targets.entrySet()) {
            DayPlan target = entry.getValue();
            int updated = planRepo.updateStatusIfCurrent(
                    target.id(), PlanStatus.PUBLISHED, PlanStatus.PREEMPTED, now);
            if (updated == 0) {
                throw conflict("PREEMPT_CONFLICT",
                        "被抢占计划状态已变更，抢占失败: " + target.scheduleKey());
            }
            int targetLevel = corridorLevel(planRepo.findOccupancies(target.id()).stream()
                    .map(Occupancy::sectionId).toList());
            long recordId = preemptionRepo.insertRecord(plan.opDate(), plan.scheduleKey(),
                    target.scheduleKey(), draftLevel, targetLevel, preemptKey, now);
            Map<String, PreemptionSection> sections = new LinkedHashMap<>();
            for (SectionConflict c : conflictsByPlan.get(entry.getKey())) {
                String dedupeKey = c.slot().sectionId() + '|' + c.slot().startUtc().toEpochMilli()
                        + '|' + c.slot().endUtc().toEpochMilli();
                sections.putIfAbsent(dedupeKey, new PreemptionSection(0L, recordId,
                        c.slot().sectionId(),
                        slotSectionLevels.getOrDefault(c.slot().sectionId(),
                                SectionRepository.DEFAULT_PRIORITY),
                        c.slot().startUtc(), c.slot().endUtc()));
            }
            preemptionRepo.insertSections(recordId, List.copyOf(sections.values()));
        }
    }

    /**
     * 计划继承的走廊等级：全部占用区段登记等级的最大值，未登记区段按最低等级 1。
     */
    private int corridorLevel(Collection<String> sectionIds) {
        Map<String, Integer> levels = sectionRepo.findPriorities(new TreeSet<>(sectionIds));
        int max = SectionRepository.DEFAULT_PRIORITY;
        for (int level : levels.values()) {
            max = Math.max(max, level);
        }
        return max;
    }

    // ---------- 内部实现 ----------

    private PlanResponse loadPlan(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> notFound(scheduleKey));
        List<Occupancy> occupancies = planRepo.findOccupancies(plan.id());
        List<OccupancyView> views = occupancies.stream()
                .map(o -> new OccupancyView(o.trainNo(), o.sectionId(), o.startUtc(), o.endUtc()))
                .toList();
        int corridorLevel = corridorLevel(occupancies.stream().map(Occupancy::sectionId).toList());
        return new PlanResponse(plan.scheduleKey(), plan.opDate(), plan.version(),
                plan.status().name(), corridorLevel, views);
    }

    private List<Occupancy> toOccupancies(long planId, List<OccupancyRequest> requests) {
        List<Occupancy> result = new ArrayList<>(requests.size());
        for (int i = 0; i < requests.size(); i++) {
            OccupancyRequest r = requests.get(i);
            result.add(new Occupancy(0L, planId, i, r.trainNo(), r.sectionId(), r.startUtc(), r.endUtc()));
        }
        return result;
    }

    /**
     * 参数级校验：结束必须晚于开始，且起止落在同一 Asia/Shanghai 日历日内。
     */
    private void validateOccupancyParams(List<OccupancyRequest> occupancies) {
        for (int i = 0; i < occupancies.size(); i++) {
            OccupancyRequest o = occupancies.get(i);
            if (!o.endUtc().isAfter(o.startUtc())) {
                throw badRequest("第 " + i + " 条占用结束时刻必须晚于开始时刻");
            }
            LocalDate startDay = o.startUtc().atZone(OPERATION_ZONE).toLocalDate();
            LocalDate endDay = o.endUtc().minusNanos(1).atZone(OPERATION_ZONE).toLocalDate();
            if (!startDay.equals(endDay)) {
                throw badRequest("第 " + i + " 条占用必须落在同一运营日（Asia/Shanghai）内");
            }
        }
    }

    /**
     * 占用必须落在计划运营日内：[start, end) 完全包含于运营日 [dayStart, dayEnd)。
     */
    private void validateWithinOperationDay(List<OccupancyRequest> occupancies, LocalDate opDate) {
        Instant dayStart = opDate.atStartOfDay(OPERATION_ZONE).toInstant();
        Instant dayEnd = opDate.plusDays(1).atStartOfDay(OPERATION_ZONE).toInstant();
        for (int i = 0; i < occupancies.size(); i++) {
            OccupancyRequest o = occupancies.get(i);
            if (o.startUtc().isBefore(dayStart) || o.endUtc().isAfter(dayEnd)) {
                throw badRequest("第 " + i + " 条占用不在运营日 " + opDate + "（Asia/Shanghai）内");
            }
        }
    }

    /**
     * 本计划内同一列车的重叠占用检测（左闭右开，相邻合法）。
     */
    private List<Map<String, Object>> findTrainOverlaps(String scheduleKey, List<Occupancy> occupancies) {
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

    /**
     * 与其他已发布计划在同日期、同区段上的重叠检测（左闭右开，相邻合法）。
     */
    private List<SectionConflict> findSectionConflicts(DayPlan plan, List<Occupancy> occupancies) {
        List<String> sectionIds = occupancies.stream()
                .map(Occupancy::sectionId)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new))
                .stream().toList();
        List<PublishedSlot> published = planRepo.findPublishedSlots(plan.opDate(), sectionIds, plan.id());
        List<SectionConflict> conflicts = new ArrayList<>();
        for (Occupancy o : occupancies) {
            for (PublishedSlot slot : published) {
                if (!o.sectionId().equals(slot.sectionId())) {
                    continue;
                }
                boolean overlap = o.startUtc().isBefore(slot.endUtc())
                        && slot.startUtc().isBefore(o.endUtc());
                if (overlap) {
                    conflicts.add(new SectionConflict(o, slot));
                }
            }
        }
        return conflicts;
    }

    /**
     * 跨计划区段冲突的结构化明细（422 SLOT_CONFLICT 响应体）。
     */
    private List<Map<String, Object>> toConflictDetails(String scheduleKey,
                                                        List<SectionConflict> conflicts) {
        List<Map<String, Object>> details = new ArrayList<>();
        for (SectionConflict c : conflicts) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("type", "SECTION_CONFLICT");
            detail.put("sectionId", c.slot().sectionId());
            detail.put("scheduleKey", scheduleKey);
            detail.put("conflictingScheduleKey", c.slot().scheduleKey());
            detail.put("trainNo", c.mine().trainNo());
            detail.put("startUtc", c.mine().startUtc().toString());
            detail.put("endUtc", c.mine().endUtc().toString());
            details.add(detail);
        }
        return details;
    }

    /**
     * 幂等重放：存在记录且参数一致返回首次结果；参数不一致抛 409。
     */
    private Optional<PlanResponse> replayIfPresent(String opType, String requestKey, String hash) {
        return idemRepo.find(opType, requestKey).map(record -> {
            if (!record.requestHash().equals(hash)) {
                throw conflict("IDEMPOTENT_KEY_REUSED",
                        "requestKey 已用于其他参数: " + requestKey);
            }
            return fromJson(record.responseJson());
        });
    }

    /**
     * 并发下唯一键冲突后的裁决：若为同键重放返回首次结果，否则说明 scheduleKey 冲突。
     */
    private PlanResponse resolveDuplicate(String opType, String requestKey, String hash) {
        return replayIfPresent(opType, requestKey, hash)
                .orElseThrow(() -> conflict("SCHEDULE_KEY_EXISTS", "scheduleKey 已存在"));
    }

    private String hashCreate(CreatePlanRequest req) {
        StringBuilder sb = new StringBuilder(OP_CREATE).append('\n')
                .append(req.scheduleKey()).append('\n').append(req.opDate());
        appendOccupancies(sb, req.occupancies());
        return sha256(sb.toString());
    }

    private String hashUpdate(String scheduleKey, UpdateOccupanciesRequest req) {
        StringBuilder sb = new StringBuilder(OP_UPDATE).append('\n')
                .append(scheduleKey).append('\n').append(req.expectedVersion());
        appendOccupancies(sb, req.occupancies());
        return sha256(sb.toString());
    }

    private String hashAction(String opType, String scheduleKey, String preemptKey) {
        return sha256(opType + '\n' + scheduleKey + '\n'
                + (preemptKey == null ? "" : preemptKey));
    }

    private void appendOccupancies(StringBuilder sb, List<OccupancyRequest> occupancies) {
        for (OccupancyRequest o : occupancies) {
            sb.append('\n').append(o.trainNo()).append('|').append(o.sectionId()).append('|')
                    .append(o.startUtc().toEpochMilli()).append('|').append(o.endUtc().toEpochMilli());
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

    private String toJson(PlanResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private PlanResponse fromJson(String json) {
        try {
            return objectMapper.readValue(json, PlanResponse.class);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    private ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", message);
    }

    private ApiException notFound(String scheduleKey) {
        return new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND",
                "计划不存在: " + scheduleKey);
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
