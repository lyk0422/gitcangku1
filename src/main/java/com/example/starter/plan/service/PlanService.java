package com.example.starter.plan.service;

import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.PlanStatus;
import com.example.starter.plan.model.Preemption;
import com.example.starter.plan.model.PreemptionSection;
import com.example.starter.plan.model.PublishedSlot;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.repo.PreemptionRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.OccupancyView;
import com.example.starter.plan.web.dto.PlanResponse;
import com.example.starter.plan.web.dto.PreemptionSectionView;
import com.example.starter.plan.web.dto.PreemptionView;
import com.example.starter.plan.web.dto.PublishedSlotView;
import com.example.starter.plan.web.dto.SectionOccupancyView;
import com.example.starter.plan.web.dto.SectionRegisterRequest;
import com.example.starter.plan.web.dto.SectionView;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 铁路走廊日计划核心业务：草稿创建/整体替换、发布、抢占、取消与查询。
 *
 * <p>走廊等级：区段登记 1～5 级（未登记按 1 级），发布计划继承其全部占用区段的最高等级。
 * 发布携带 preemptKey 且冲突的已发布计划全部占用区段等级都低于本次草稿时，在同一事务内
 * 把被抢占计划整体转为 PREEMPTED 终态并释放时隙、写入不可变抢占记录，随后发布抢占草稿；
 * 任一冲突计划存在不低于草稿等级的区段时 422（返回该区段与等级）；冲突时隙已被当前持有者
 * 抢占获得且等级不足时 409（同一时隙只能被抢占一次）。
 *
 * <p>并发与幂等约定：写操作按 (操作类型, requestKey) 幂等，同键同参重放返回首次成功结果，
 * 同键不同参返回 409；发布（含抢占）经全局发布锁串行化，按事务提交顺序裁决；
 * 仅成功结果写入幂等记录，失败（含 422 时隙冲突）不缓存、可修正后重试。
 */
@Service
public class PlanService {

    /** 运营日解释时区。 */
    public static final ZoneId OPERATION_ZONE = ZoneId.of("Asia/Shanghai");

    /** 未登记区段的默认走廊等级。 */
    public static final int DEFAULT_SECTION_LEVEL = 1;

    private static final String OP_CREATE = "CREATE";
    private static final String OP_UPDATE = "UPDATE";
    private static final String OP_PUBLISH = "PUBLISH";
    private static final String OP_CANCEL = "CANCEL";

    private final PlanRepository planRepo;
    private final IdempotencyRepository idemRepo;
    private final PreemptionRepository preemptionRepo;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public PlanService(PlanRepository planRepo, IdempotencyRepository idemRepo,
                       PreemptionRepository preemptionRepo,
                       ObjectMapper objectMapper, PlatformTransactionManager txManager) {
        this.planRepo = planRepo;
        this.idemRepo = idemRepo;
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
     * 无冲突直接发布；有冲突且未提交 preemptKey 时 422；提交 preemptKey 时按走廊等级
     * 校验抢占条件，满足则在同一事务内降级被抢占计划（PREEMPTED）、写入抢占记录并发布，
     * 任一失败整单回滚且已发布计划不变。
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
                ConflictScan scan = scanSectionConflicts(plan, occupancies);
                Map<String, Integer> draftLevels = sectionLevels(
                        occupancies.stream().map(Occupancy::sectionId).toList());
                int draftLevel = draftLevels.values().stream()
                        .mapToInt(Integer::intValue).max().orElse(DEFAULT_SECTION_LEVEL);
                if (!scan.details().isEmpty()) {
                    if (normalizedPreemptKey == null) {
                        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_CONFLICT",
                                "存在时隙冲突，计划保持草稿", scan.details());
                    }
                    preemptConflictingPlans(plan, normalizedPreemptKey, scan, draftLevel,
                            System.currentTimeMillis());
                }
                long now = System.currentTimeMillis();
                planRepo.updateStatusAndLevel(plan.id(), PlanStatus.PUBLISHED, draftLevel, now);
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
     * 登记或更新区段走廊等级（1～5）。
     */
    public SectionView registerSection(String sectionId, SectionRegisterRequest req) {
        planRepo.upsertSection(sectionId, req.priority(), System.currentTimeMillis());
        return new SectionView(sectionId, req.priority());
    }

    /**
     * 查询区段登记等级，未登记返回 404。
     */
    public SectionView getSection(String sectionId) {
        int priority = planRepo.findSectionPriority(sectionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SECTION_NOT_FOUND",
                        "区段未登记: " + sectionId));
        return new SectionView(sectionId, priority);
    }

    /**
     * 查询抢占记录，可按计划业务键（匹配抢占方或被抢占方）与运营日过滤，按提交顺序返回。
     */
    public List<PreemptionView> getPreemptions(String scheduleKey, LocalDate opDate) {
        return preemptionRepo.findRecords(scheduleKey, opDate).stream()
                .map(p -> new PreemptionView(p.preemptKey(), p.opDate(),
                        p.winnerScheduleKey(), p.winnerLevel(),
                        p.loserScheduleKey(), p.loserLevel(),
                        preemptionRepo.findSections(p.id()).stream()
                                .map(s -> new PreemptionSectionView(s.sectionId(), s.sectionLevel()))
                                .toList(),
                        p.createdAt()))
                .toList();
    }

    /**
     * 按区段查询当前等级占用：该区段上当前已发布生效的时隙及计划/区段等级。
     */
    public List<SectionOccupancyView> getSectionOccupancy(LocalDate opDate, String sectionId) {
        int sectionLevel = planRepo.findSectionPriority(sectionId).orElse(DEFAULT_SECTION_LEVEL);
        return planRepo.findPublishedSlots(opDate, List.of(sectionId), -1L).stream()
                .map(s -> new SectionOccupancyView(s.scheduleKey(), s.trainNo(), s.sectionId(),
                        s.startUtc(), s.endUtc(), s.planLevel(), sectionLevel))
                .toList();
    }

    // ---------- 内部实现 ----------

    /**
     * 跨计划区段冲突扫描结果：422 明细、按冲突计划分组的争夺区段与计划业务键。
     */
    private record ConflictScan(List<Map<String, Object>> details,
                                Map<Long, Set<String>> contestedSectionsByPlan,
                                Map<Long, String> scheduleKeysByPlan) {
    }

    private PlanResponse loadPlan(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> notFound(scheduleKey));
        List<OccupancyView> views = planRepo.findOccupancies(plan.id()).stream()
                .map(o -> new OccupancyView(o.trainNo(), o.sectionId(), o.startUtc(), o.endUtc()))
                .toList();
        return new PlanResponse(plan.scheduleKey(), plan.opDate(), plan.version(),
                plan.status().name(), plan.planLevel(), views);
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
     * 与其他已发布计划在同日期、同区段上的重叠扫描（左闭右开，相邻合法），
     * 同时汇总按冲突计划分组的争夺区段，供抢占校验使用。
     */
    private ConflictScan scanSectionConflicts(DayPlan plan, List<Occupancy> occupancies) {
        List<String> sectionIds = occupancies.stream()
                .map(Occupancy::sectionId)
                .collect(Collectors.toCollection(TreeSet::new))
                .stream().toList();
        List<PublishedSlot> published = planRepo.findPublishedSlots(plan.opDate(), sectionIds, plan.id());
        List<Map<String, Object>> details = new ArrayList<>();
        Map<Long, Set<String>> contested = new LinkedHashMap<>();
        Map<Long, String> scheduleKeys = new LinkedHashMap<>();
        for (Occupancy o : occupancies) {
            for (PublishedSlot slot : published) {
                if (!o.sectionId().equals(slot.sectionId())) {
                    continue;
                }
                boolean overlap = o.startUtc().isBefore(slot.endUtc())
                        && slot.startUtc().isBefore(o.endUtc());
                if (overlap) {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("type", "SECTION_CONFLICT");
                    detail.put("sectionId", o.sectionId());
                    detail.put("scheduleKey", plan.scheduleKey());
                    detail.put("conflictingScheduleKey", slot.scheduleKey());
                    detail.put("trainNo", o.trainNo());
                    detail.put("startUtc", o.startUtc().toString());
                    detail.put("endUtc", o.endUtc().toString());
                    details.add(detail);
                    contested.computeIfAbsent(slot.planId(), k -> new TreeSet<>()).add(slot.sectionId());
                    scheduleKeys.putIfAbsent(slot.planId(), slot.scheduleKey());
                }
            }
        }
        return new ConflictScan(details, contested, scheduleKeys);
    }

    /**
     * 抢占校验与执行（发布锁内、与发布同事务）：
     * 全部冲突计划的全部占用区段等级都低于草稿等级时，原子降级被抢占计划为 PREEMPTED、
     * 写入不可变抢占记录；存在不低于草稿等级的区段时 422（返回区段与等级）；
     * 争夺时隙已被当前持有者抢占获得时 409（同一时隙只能被抢占一次）。
     */
    private void preemptConflictingPlans(DayPlan plan, String preemptKey, ConflictScan scan,
                                         int draftLevel, long now) {
        List<Map<String, Object>> blocking = new ArrayList<>();
        Set<Long> blockedPlanIds = new TreeSet<>();
        Map<Long, Integer> loserLevels = new HashMap<>();
        Map<Long, Map<String, Integer>> loserSectionLevels = new HashMap<>();
        for (Map.Entry<Long, Set<String>> entry : scan.contestedSectionsByPlan().entrySet()) {
            long loserId = entry.getKey();
            Set<String> loserSections = planRepo.findOccupancies(loserId).stream()
                    .map(Occupancy::sectionId)
                    .collect(Collectors.toCollection(TreeSet::new));
            Map<String, Integer> levels = sectionLevels(loserSections);
            loserSectionLevels.put(loserId, levels);
            loserLevels.put(loserId,
                    levels.values().stream().mapToInt(Integer::intValue).max()
                            .orElse(DEFAULT_SECTION_LEVEL));
            for (String sectionId : loserSections) {
                int level = levels.get(sectionId);
                if (level >= draftLevel) {
                    blockedPlanIds.add(loserId);
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("type", "PREEMPT_LEVEL_INSUFFICIENT");
                    detail.put("sectionId", sectionId);
                    detail.put("sectionLevel", level);
                    detail.put("draftLevel", draftLevel);
                    detail.put("conflictingScheduleKey", scan.scheduleKeysByPlan().get(loserId));
                    blocking.add(detail);
                }
            }
        }
        if (!blocking.isEmpty()) {
            for (long blockedId : blockedPlanIds) {
                for (String sectionId : scan.contestedSectionsByPlan().get(blockedId)) {
                    if (preemptionRepo.existsWinnerRecordOnSection(blockedId, sectionId)) {
                        Map<String, Object> detail = new LinkedHashMap<>();
                        detail.put("type", "SLOT_ALREADY_PREEMPTED");
                        detail.put("sectionId", sectionId);
                        detail.put("conflictingScheduleKey",
                                scan.scheduleKeysByPlan().get(blockedId));
                        throw new ApiException(HttpStatus.CONFLICT, "SLOT_ALREADY_PREEMPTED",
                                "同一时隙只能被抢占一次，区段 " + sectionId
                                        + " 的当前占用计划由抢占获得且等级不低于本次草稿",
                                List.of(detail));
                    }
                }
            }
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PREEMPT_LEVEL_INSUFFICIENT",
                    "被抢占计划的全部占用区段等级必须低于本次草稿", blocking);
        }
        if (preemptionRepo.existsByPreemptKey(preemptKey)) {
            throw conflict("PREEMPT_KEY_REUSED", "preemptKey 已用于其他抢占: " + preemptKey);
        }
        for (long loserId : new TreeSet<>(scan.contestedSectionsByPlan().keySet())) {
            if (planRepo.markPreemptedIfPublished(loserId, now) == 0) {
                throw conflict("PREEMPT_TARGET_CHANGED",
                        "被抢占计划状态已变化，抢占失败: " + scan.scheduleKeysByPlan().get(loserId));
            }
            Map<String, Integer> levels = loserSectionLevels.get(loserId);
            long preemptionId = preemptionRepo.insert(new Preemption(0L, preemptKey, plan.opDate(),
                    plan.id(), plan.scheduleKey(), draftLevel,
                    loserId, scan.scheduleKeysByPlan().get(loserId), loserLevels.get(loserId), now));
            List<PreemptionSection> sections = scan.contestedSectionsByPlan().get(loserId).stream()
                    .map(s -> new PreemptionSection(0L, preemptionId, s, levels.get(s)))
                    .toList();
            preemptionRepo.insertSections(preemptionId, sections);
        }
    }

    /**
     * 查询区段等级，未登记区段按 {@link #DEFAULT_SECTION_LEVEL} 级处理。
     */
    private Map<String, Integer> sectionLevels(Collection<String> sectionIds) {
        Set<String> distinct = new TreeSet<>(sectionIds);
        Map<String, Integer> registered = planRepo.findSectionPriorities(distinct);
        Map<String, Integer> levels = new HashMap<>();
        for (String sectionId : distinct) {
            levels.put(sectionId, registered.getOrDefault(sectionId, DEFAULT_SECTION_LEVEL));
        }
        return levels;
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
