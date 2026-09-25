package com.example.starter.plan.service;

import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.PlanStatus;
import com.example.starter.plan.model.Preemption;
import com.example.starter.plan.model.PreemptionSlot;
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
import com.example.starter.plan.web.dto.PreemptionSlotView;
import com.example.starter.plan.web.dto.PreemptionView;
import com.example.starter.plan.web.dto.PublishedSlotView;
import com.example.starter.plan.web.dto.RegisterSectionRequest;
import com.example.starter.plan.web.dto.SectionOccupancyView;
import com.example.starter.plan.web.dto.SectionResponse;
import com.example.starter.plan.web.dto.UpdateOccupanciesRequest;
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
import java.util.LinkedHashSet;
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
 * 铁路走廊日计划核心业务：草稿创建/整体替换、发布（含等级抢占）、取消、
 * 区段等级登记与查询。
 *
 * <p>并发与幂等约定：写操作按 (操作类型, requestKey) 幂等，同键同参重放返回首次成功结果，
 * 同键不同参返回 409；发布与抢占经全局发布锁串行化，同一计划的更新/发布/取消经行锁按事务提交顺序生效；
 * 仅成功结果写入幂等记录，失败（含 422 时隙冲突、抢占条件不满足）不缓存、可修正后重试。
 *
 * <p>抢占规则：计划等级为其全部占用区段的最高登记等级（未登记区段按 1 级）。发布草稿携带
 * preemptKey 时，若全部冲突已发布计划的所有占用区段等级都低于草稿等级，则在同一事务内把
 * 被抢占计划整体转为 PREEMPTED 终态、写入不可变抢占记录并发布草稿；任一区段等级不低于草稿
 * 返回 422 并携带该区段与等级；同一时隙只允许被抢占一次，再次抢占返回 409。
 */
@Service
public class PlanService {

    /** 运营日解释时区。 */
    public static final ZoneId OPERATION_ZONE = ZoneId.of("Asia/Shanghai");

    /** 未登记区段的默认等级（最低）。 */
    public static final int DEFAULT_SECTION_LEVEL = 1;

    private static final String OP_CREATE = "CREATE";
    private static final String OP_UPDATE = "UPDATE";
    private static final String OP_PUBLISH = "PUBLISH";
    private static final String OP_CANCEL = "CANCEL";
    private static final String OP_SECTION = "SECTION";

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
        Optional<PlanResponse> replay = replayIfPresent(OP_CREATE, req.requestKey(), hash,
                PlanResponse.class);
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
        Optional<PlanResponse> replay = replayIfPresent(OP_UPDATE, req.requestKey(), hash,
                PlanResponse.class);
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
     * 发布计划（不带抢占授权），等同 {@link #publish(String, String, String)} 且 preemptKey 为空。
     */
    public PlanResponse publish(String scheduleKey, String requestKey) {
        return publish(scheduleKey, requestKey, null);
    }

    /**
     * 发布计划：全局发布锁内原子校验本计划列车重叠与跨计划区段重叠。
     *
     * <p>不带 preemptKey 时任一冲突抛出 422（计划保持草稿）。携带 preemptKey 时，若全部冲突
     * 已发布计划的所有占用区段等级都低于草稿等级，则同事务内把被抢占计划转为 PREEMPTED 终态、
     * 写入不可变抢占记录并发布草稿；任一区段等级不低于草稿抛出 422（携带该区段与等级）；
     * 同一时隙已被抢占过的再次抢占抛出 409。任一冲突整单回滚，已发布计划不变。
     */
    public PlanResponse publish(String scheduleKey, String requestKey, String preemptKey) {
        if (preemptKey != null && preemptKey.isBlank()) {
            throw badRequest("preemptKey 不能为空字符串");
        }
        String hash = hashPublish(scheduleKey, preemptKey);
        Optional<PlanResponse> replay = replayIfPresent(OP_PUBLISH, requestKey, hash,
                PlanResponse.class);
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
                // 同批草稿内部列车重叠不可抢占，始终按原规则 422
                List<Map<String, Object>> trainOverlaps = findTrainOverlaps(scheduleKey, occupancies);
                if (!trainOverlaps.isEmpty()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_CONFLICT",
                            "存在时隙冲突，计划保持草稿", trainOverlaps);
                }
                Map<String, List<PublishedSlot>> conflictingByTarget = new TreeMap<>();
                List<Map<String, Object>> conflicts = new ArrayList<>(
                        findSectionConflicts(plan, occupancies, conflictingByTarget));
                if (!conflicts.isEmpty()) {
                    if (preemptKey == null) {
                        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_CONFLICT",
                                "存在时隙冲突，计划保持草稿", conflicts);
                    }
                    preemptConflictingPlans(plan, occupancies, conflictingByTarget);
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
        String hash = hashAction(OP_CANCEL, scheduleKey);
        Optional<PlanResponse> replay = replayIfPresent(OP_CANCEL, requestKey, hash,
                PlanResponse.class);
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
     * 登记走廊区段等级（1～5，数值越大越高）；区段唯一，重复登记返回 409。
     */
    public SectionResponse registerSection(RegisterSectionRequest req) {
        String hash = sha256(OP_SECTION + '\n' + req.sectionId() + '\n' + req.priority());
        Optional<SectionResponse> replay = replayIfPresent(OP_SECTION, req.requestKey(), hash,
                SectionResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                if (!sectionRepo.findLevels(List.of(req.sectionId())).isEmpty()) {
                    throw conflict("SECTION_EXISTS", "区段已登记: " + req.sectionId());
                }
                long now = System.currentTimeMillis();
                sectionRepo.insert(req.sectionId(), req.priority(), now);
                SectionResponse response = new SectionResponse(req.sectionId(), req.priority());
                idemRepo.insert(OP_SECTION, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_SECTION, req.requestKey(), hash, SectionResponse.class)
                    .orElseThrow(() -> conflict("SECTION_EXISTS", "区段已登记: " + req.sectionId()));
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
     * 查询全部抢占记录（不可变），含双方计划、各自等级与涉及时隙，按发生顺序返回。
     */
    public List<PreemptionView> listPreemptions() {
        List<Preemption> headers = preemptionRepo.findAll();
        if (headers.isEmpty()) {
            return List.of();
        }
        Map<Long, List<PreemptionSlotView>> slotsByPreemption = new LinkedHashMap<>();
        List<Long> ids = headers.stream().map(Preemption::id).toList();
        for (PreemptionSlot slot : preemptionRepo.findSlots(ids)) {
            slotsByPreemption.computeIfAbsent(slot.preemptionId(), k -> new ArrayList<>())
                    .add(new PreemptionSlotView(slot.sectionId(), slot.sectionLevel(),
                            slot.startUtc(), slot.endUtc()));
        }
        return headers.stream()
                .map(h -> new PreemptionView(h.id(), h.preemptingScheduleKey(),
                        h.preemptedScheduleKey(), h.preemptingLevel(), h.preemptedLevel(),
                        slotsByPreemption.getOrDefault(h.id(), List.of()), h.createdAt()))
                .toList();
    }

    /**
     * 按区段查询当前已发布的生效占用，携带区段登记等级与所属计划等级。
     */
    public List<SectionOccupancyView> getSectionOccupancy(LocalDate opDate, String sectionId) {
        List<PublishedSlot> slots = planRepo.findPublishedSlots(opDate, List.of(sectionId), -1L);
        int sectionLevel = sectionRepo.findLevels(List.of(sectionId))
                .getOrDefault(sectionId, DEFAULT_SECTION_LEVEL);
        Map<String, Integer> planLevels = new LinkedHashMap<>();
        List<SectionOccupancyView> views = new ArrayList<>();
        for (PublishedSlot slot : slots) {
            Integer planLevel = planLevels.get(slot.scheduleKey());
            if (planLevel == null) {
                planLevel = planRepo.findByKey(slot.scheduleKey())
                        .map(p -> planLevel(planRepo.findOccupancies(p.id())))
                        .orElse(DEFAULT_SECTION_LEVEL);
                planLevels.put(slot.scheduleKey(), planLevel);
            }
            views.add(new SectionOccupancyView(slot.scheduleKey(), slot.trainNo(), slot.sectionId(),
                    sectionLevel, planLevel, slot.startUtc(), slot.endUtc()));
        }
        return views;
    }

    // ---------- 内部实现 ----------

    /**
     * 抢占执行（发布锁内）：锁定并复核目标计划仍为已发布，校验同一时隙未被抢占过（409）、
     * 目标全部占用区段等级都低于草稿（422），然后同事务降级目标、写抢占记录。
     */
    private void preemptConflictingPlans(DayPlan draft, List<Occupancy> draftOccupancies,
                                         Map<String, List<PublishedSlot>> conflictingByTarget) {
        int draftLevel = planLevel(draftOccupancies);
        // 锁定目标计划行并按提交顺序复核状态；并发取消后不再已发布的目标无需抢占
        Map<String, DayPlan> targets = new TreeMap<>();
        for (String key : conflictingByTarget.keySet()) {
            Optional<DayPlan> target = planRepo.findByKeyForUpdate(key);
            if (target.isPresent() && target.get().status() == PlanStatus.PUBLISHED) {
                targets.put(key, target.get());
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        // 同一时隙只能被抢占一次：任一涉及时隙与历史抢占时隙重叠即 409
        for (Map.Entry<String, DayPlan> entry : targets.entrySet()) {
            for (PublishedSlot slot : conflictingByTarget.get(entry.getKey())) {
                if (preemptionRepo.existsOverlappingSlot(slot.sectionId(),
                        slot.startUtc(), slot.endUtc())) {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("type", "SLOT_ALREADY_PREEMPTED");
                    detail.put("sectionId", slot.sectionId());
                    detail.put("scheduleKey", entry.getKey());
                    detail.put("startUtc", slot.startUtc().toString());
                    detail.put("endUtc", slot.endUtc().toString());
                    throw new ApiException(HttpStatus.CONFLICT, "SLOT_ALREADY_PREEMPTED",
                            "时隙已被抢占过一次，不可再次抢占", List.of(detail));
                }
            }
        }
        // 等级校验：目标全部占用区段等级都必须低于草稿等级
        Map<String, List<Occupancy>> targetOccupancies = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> targetSectionLevels = new LinkedHashMap<>();
        Map<String, Integer> targetLevels = new LinkedHashMap<>();
        List<Map<String, Object>> levelFailures = new ArrayList<>();
        for (Map.Entry<String, DayPlan> entry : targets.entrySet()) {
            DayPlan target = entry.getValue();
            List<Occupancy> occupancies = planRepo.findOccupancies(target.id());
            Map<String, Integer> levels = sectionRepo.findLevels(sectionIdsOf(occupancies));
            targetOccupancies.put(entry.getKey(), occupancies);
            targetSectionLevels.put(entry.getKey(), levels);
            targetLevels.put(entry.getKey(), occupancies.stream()
                    .mapToInt(o -> levels.getOrDefault(o.sectionId(), DEFAULT_SECTION_LEVEL))
                    .max().orElse(DEFAULT_SECTION_LEVEL));
            for (Occupancy o : occupancies) {
                int level = levels.getOrDefault(o.sectionId(), DEFAULT_SECTION_LEVEL);
                if (level >= draftLevel) {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("type", "SECTION_LEVEL_NOT_LOWER");
                    detail.put("sectionId", o.sectionId());
                    detail.put("level", level);
                    detail.put("draftLevel", draftLevel);
                    detail.put("scheduleKey", entry.getKey());
                    levelFailures.add(detail);
                }
            }
        }
        if (!levelFailures.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PREEMPTION_LEVEL_INSUFFICIENT",
                    "存在等级不低于草稿的占用区段，不满足抢占条件", levelFailures);
        }
        // 同事务执行：目标整体降级为 PREEMPTED 终态并写入不可变抢占记录
        long now = System.currentTimeMillis();
        for (Map.Entry<String, DayPlan> entry : targets.entrySet()) {
            DayPlan target = entry.getValue();
            planRepo.updateStatus(target.id(), PlanStatus.PREEMPTED, now);
            long preemptionId = preemptionRepo.insert(draft.id(), draft.scheduleKey(),
                    target.id(), target.scheduleKey(), draftLevel,
                    targetLevels.get(entry.getKey()), now);
            Map<String, Integer> levels = targetSectionLevels.get(entry.getKey());
            List<PreemptionSlot> slots = new LinkedHashSet<>(
                    conflictingByTarget.get(entry.getKey())).stream()
                    .map(s -> new PreemptionSlot(0L, preemptionId, s.sectionId(),
                            levels.getOrDefault(s.sectionId(), DEFAULT_SECTION_LEVEL),
                            s.startUtc(), s.endUtc()))
                    .toList();
            preemptionRepo.insertSlots(preemptionId, slots);
        }
    }

    private PlanResponse loadPlan(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> notFound(scheduleKey));
        List<Occupancy> occupancies = planRepo.findOccupancies(plan.id());
        List<OccupancyView> views = occupancies.stream()
                .map(o -> new OccupancyView(o.trainNo(), o.sectionId(), o.startUtc(), o.endUtc()))
                .toList();
        return new PlanResponse(plan.scheduleKey(), plan.opDate(), plan.version(),
                plan.status().name(), planLevel(occupancies), views);
    }

    /**
     * 计划等级：全部占用区段的最高登记等级；未登记区段按 {@link #DEFAULT_SECTION_LEVEL} 级。
     */
    private int planLevel(List<Occupancy> occupancies) {
        Map<String, Integer> levels = sectionRepo.findLevels(sectionIdsOf(occupancies));
        return occupancies.stream()
                .mapToInt(o -> levels.getOrDefault(o.sectionId(), DEFAULT_SECTION_LEVEL))
                .max().orElse(DEFAULT_SECTION_LEVEL);
    }

    private List<String> sectionIdsOf(List<Occupancy> occupancies) {
        return occupancies.stream()
                .map(Occupancy::sectionId)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new))
                .stream().toList();
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
     * 与其他已发布计划在同日期、同区段上的重叠检测（左闭右开，相邻合法）；
     * 同时把冲突时隙按对方计划归组，供抢占校验与记录使用。
     */
    private List<Map<String, Object>> findSectionConflicts(
            DayPlan plan, List<Occupancy> occupancies,
            Map<String, List<PublishedSlot>> conflictingByTarget) {
        List<PublishedSlot> published = planRepo.findPublishedSlots(plan.opDate(),
                sectionIdsOf(occupancies), plan.id());
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (Occupancy o : occupancies) {
            for (PublishedSlot slot : published) {
                if (!o.sectionId().equals(slot.sectionId())) {
                    continue;
                }
                boolean overlap = o.startUtc().isBefore(slot.endUtc())
                        && slot.startUtc().isBefore(o.endUtc());
                if (overlap) {
                    conflictingByTarget.computeIfAbsent(slot.scheduleKey(), k -> new ArrayList<>())
                            .add(slot);
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("type", "SECTION_CONFLICT");
                    detail.put("sectionId", o.sectionId());
                    detail.put("scheduleKey", plan.scheduleKey());
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

    /**
     * 幂等重放：存在记录且参数一致返回首次结果；参数不一致抛 409。
     */
    private <T> Optional<T> replayIfPresent(String opType, String requestKey, String hash,
                                            Class<T> type) {
        return idemRepo.find(opType, requestKey).map(record -> {
            if (!record.requestHash().equals(hash)) {
                throw conflict("IDEMPOTENT_KEY_REUSED",
                        "requestKey 已用于其他参数: " + requestKey);
            }
            return fromJson(record.responseJson(), type);
        });
    }

    /**
     * 并发下唯一键冲突后的裁决：若为同键重放返回首次结果，否则说明 scheduleKey 冲突。
     */
    private PlanResponse resolveDuplicate(String opType, String requestKey, String hash) {
        return replayIfPresent(opType, requestKey, hash, PlanResponse.class)
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

    private String hashPublish(String scheduleKey, String preemptKey) {
        return sha256(OP_PUBLISH + '\n' + scheduleKey + '\n'
                + (preemptKey == null ? "" : preemptKey));
    }

    private String hashAction(String opType, String scheduleKey) {
        return sha256(opType + '\n' + scheduleKey);
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
