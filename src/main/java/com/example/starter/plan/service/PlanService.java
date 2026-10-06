package com.example.starter.plan.service;

import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.Direction;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.PlanStatus;
import com.example.starter.plan.model.PublishedSlot;
import com.example.starter.plan.model.RescheduleLink;
import com.example.starter.plan.model.SectionConfig;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.repo.SectionConfigRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.OccupancyView;
import com.example.starter.plan.web.dto.PlanResponse;
import com.example.starter.plan.web.dto.PublishedSlotView;
import com.example.starter.plan.web.dto.RescheduleChainItem;
import com.example.starter.plan.web.dto.RescheduleChainResponse;
import com.example.starter.plan.web.dto.RescheduleRequest;
import com.example.starter.plan.web.dto.RescheduleResponse;
import com.example.starter.plan.web.dto.UpdateOccupanciesRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
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
 * 铁路走廊日计划核心业务：草稿创建/整体替换、发布、取消、原子改签与查询。
 *
 * <p>并发与幂等约定：写操作按 (操作类型, requestKey) 幂等，同键同参重放返回首次成功结果，
 * 同键不同参返回 409；发布与改签经全局发布锁串行化，同一计划的更新/发布/取消/改签经行锁按事务提交顺序生效；
 * 仅成功结果写入幂等记录，失败（含 422 时隙冲突）不缓存、可修正后重试。
 */
@Service
public class PlanService {

    /** 运营日解释时区。 */
    public static final ZoneId OPERATION_ZONE = ZoneId.of("Asia/Shanghai");

    private static final String OP_CREATE = "CREATE";
    private static final String OP_UPDATE = "UPDATE";
    private static final String OP_PUBLISH = "PUBLISH";
    private static final String OP_CANCEL = "CANCEL";
    private static final String OP_RESCHEDULE = "RESCHEDULE";

    private final PlanRepository planRepo;
    private final IdempotencyRepository idemRepo;
    private final SectionConfigRepository configRepo;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public PlanService(PlanRepository planRepo, IdempotencyRepository idemRepo,
                       SectionConfigRepository configRepo,
                       ObjectMapper objectMapper, PlatformTransactionManager txManager) {
        this.planRepo = planRepo;
        this.idemRepo = idemRepo;
        this.configRepo = configRepo;
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
                // 行锁内复查：并发同键更新在首次提交后进入时直接重放
                Optional<PlanResponse> replayed = replayIfPresent(OP_UPDATE, req.requestKey(), hash);
                if (replayed.isPresent()) {
                    return replayed.get();
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
     * 发布计划：全局发布锁内原子校验本计划列车重叠、跨计划区段互斥与单线区段换向间隔，
     * 任一冲突则整张计划保持草稿并抛出 422（不写任何占用）；
     * 成功时冻结各配置区段的方向与配置版本。
     */
    public PlanResponse publish(String scheduleKey, String requestKey) {
        String hash = hashAction(OP_PUBLISH, scheduleKey);
        Optional<PlanResponse> replay = replayIfPresent(OP_PUBLISH, requestKey, hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                // 串行边界内复查：并发同键发布在首次提交后进入时直接重放
                Optional<PlanResponse> replayed = replayIfPresent(OP_PUBLISH, requestKey, hash);
                if (replayed.isPresent()) {
                    return replayed.get();
                }
                DayPlan plan = planRepo.findByKeyForUpdate(scheduleKey)
                        .orElseThrow(() -> notFound(scheduleKey));
                if (plan.status() != PlanStatus.DRAFT) {
                    throw conflict("PLAN_STATE_CONFLICT",
                            "仅草稿可发布，当前状态: " + plan.status());
                }
                List<Occupancy> occupancies = planRepo.findOccupancies(plan.id());
                Map<String, SectionConfig> configs = loadConfigs(occupancies);
                List<Map<String, Object>> conflicts = new ArrayList<>();
                conflicts.addAll(findTrainOverlaps(scheduleKey, occupancies));
                conflicts.addAll(findSectionConflicts(plan, occupancies, List.of(plan.id()), configs));
                if (!conflicts.isEmpty()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_CONFLICT",
                            "存在时隙冲突，计划保持草稿", conflicts);
                }
                long now = System.currentTimeMillis();
                freezeClearanceVersions(plan.id(), occupancies, configs);
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
     * 取消已发布计划：时隙立即释放，历史计划与占用（含冻结的方向与配置版本）保留不改写。
     * 与发布、改签、配置更新共用同一全局串行边界。
     */
    public PlanResponse cancel(String scheduleKey, String requestKey) {
        String hash = hashAction(OP_CANCEL, scheduleKey);
        Optional<PlanResponse> replay = replayIfPresent(OP_CANCEL, requestKey, hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                // 串行边界内复查：并发同键取消在首次提交后进入时直接重放
                Optional<PlanResponse> replayed = replayIfPresent(OP_CANCEL, requestKey, hash);
                if (replayed.isPresent()) {
                    return replayed.get();
                }
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
     * 原子改签：全局发布锁内校验新草稿（列车内部重叠与跨计划区段冲突，仅排除旧计划占用），
     * 通过后同一事务取消旧计划、发布新计划并追加不可变前后继关联。
     * 任一步失败整体回滚：旧计划仍发布、新计划仍草稿，版本、占用与关联均不改变。
     */
    public RescheduleResponse reschedule(String oldScheduleKey, RescheduleRequest req) {
        if (oldScheduleKey.equals(req.newScheduleKey())) {
            throw badRequest("新旧计划必须不同: " + oldScheduleKey);
        }
        String hash = hashReschedule(oldScheduleKey, req);
        Optional<RescheduleResponse> replay =
                replayIfPresent(OP_RESCHEDULE, req.requestKey(), hash, RescheduleResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                // 串行边界内复查：并发同键改签在首次提交后进入时直接重放
                Optional<RescheduleResponse> replayed =
                        replayIfPresent(OP_RESCHEDULE, req.requestKey(), hash,
                                RescheduleResponse.class);
                if (replayed.isPresent()) {
                    return replayed.get();
                }
                DayPlan oldPlan = planRepo.findByKeyForUpdate(oldScheduleKey)
                        .orElseThrow(() -> notFound(oldScheduleKey));
                DayPlan newPlan = planRepo.findByKeyForUpdate(req.newScheduleKey())
                        .orElseThrow(() -> notFound(req.newScheduleKey()));
                if (oldPlan.status() != PlanStatus.PUBLISHED) {
                    throw conflict("PLAN_STATE_CONFLICT",
                            "仅已发布计划可改签，旧计划当前状态: " + oldPlan.status());
                }
                if (newPlan.status() != PlanStatus.DRAFT) {
                    throw conflict("PLAN_STATE_CONFLICT",
                            "改签新计划必须为草稿，当前状态: " + newPlan.status());
                }
                if (req.expectedOldVersion() != oldPlan.version()) {
                    throw conflict("VERSION_CONFLICT",
                            "expectedOldVersion=" + req.expectedOldVersion()
                                    + " 与旧计划当前版本 " + oldPlan.version() + " 不一致");
                }
                if (req.expectedNewVersion() != newPlan.version()) {
                    throw conflict("VERSION_CONFLICT",
                            "expectedNewVersion=" + req.expectedNewVersion()
                                    + " 与新计划当前版本 " + newPlan.version() + " 不一致");
                }
                if (!oldPlan.opDate().equals(newPlan.opDate())) {
                    throw conflict("OP_DATE_MISMATCH",
                            "新旧计划运营日必须相同: 旧=" + oldPlan.opDate() + " 新=" + newPlan.opDate());
                }
                if (planRepo.findLinkByPredecessor(oldPlan.id()).isPresent()) {
                    throw conflict("LINK_CONFLICT", "旧计划已存在直接后继: " + oldScheduleKey);
                }
                if (planRepo.findLinkBySuccessor(newPlan.id()).isPresent()) {
                    throw conflict("LINK_CONFLICT", "新计划已存在直接前驱: " + req.newScheduleKey());
                }
                List<Occupancy> occupancies = planRepo.findOccupancies(newPlan.id());
                Map<String, SectionConfig> configs = loadConfigs(occupancies);
                List<Map<String, Object>> conflicts = new ArrayList<>();
                conflicts.addAll(findTrainOverlaps(req.newScheduleKey(), occupancies));
                // 移除旧计划占用后，对改签后全部相邻对重新裁决；仅排除新旧两个计划，
                // 第三方已发布计划照常参与冲突裁决
                conflicts.addAll(findSectionConflicts(newPlan, occupancies,
                        List.of(newPlan.id(), oldPlan.id()), configs));
                if (!conflicts.isEmpty()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_CONFLICT",
                            "新草稿存在时隙冲突，改签未生效", conflicts);
                }
                long now = System.currentTimeMillis();
                planRepo.updateStatus(oldPlan.id(), PlanStatus.CANCELLED, now);
                freezeClearanceVersions(newPlan.id(), occupancies, configs);
                planRepo.updateStatus(newPlan.id(), PlanStatus.PUBLISHED, now);
                planRepo.insertRescheduleLink(oldPlan.id(), newPlan.id(), now);
                RescheduleResponse response = new RescheduleResponse(
                        loadPlan(oldScheduleKey), loadPlan(req.newScheduleKey()));
                idemRepo.insert(OP_RESCHEDULE, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_RESCHEDULE, req.requestKey(), hash, RescheduleResponse.class)
                    .orElseThrow(() -> conflict("LINK_CONFLICT", "改签前后继关联冲突"));
        }
    }

    /**
     * 查询包含指定计划在内的完整有序改签链（从最前驱到最后继）；
     * 无改签历史的计划链中仅含自身。计划不存在返回 404。
     */
    public RescheduleChainResponse getRescheduleChain(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> notFound(scheduleKey));
        List<DayPlan> predecessors = new ArrayList<>();
        DayPlan cursor = plan;
        while (true) {
            Optional<RescheduleLink> link = planRepo.findLinkBySuccessor(cursor.id());
            if (link.isEmpty()) {
                break;
            }
            cursor = planRepo.findById(link.get().predecessorPlanId())
                    .orElseThrow(() -> new IllegalStateException("改签链前驱缺失: " + link.get()));
            predecessors.add(cursor);
        }
        Collections.reverse(predecessors);
        List<DayPlan> chain = new ArrayList<>(predecessors);
        chain.add(plan);
        cursor = plan;
        while (true) {
            Optional<RescheduleLink> link = planRepo.findLinkByPredecessor(cursor.id());
            if (link.isEmpty()) {
                break;
            }
            cursor = planRepo.findById(link.get().successorPlanId())
                    .orElseThrow(() -> new IllegalStateException("改签链后继缺失: " + link.get()));
            chain.add(cursor);
        }
        List<RescheduleChainItem> items = chain.stream()
                .map(p -> new RescheduleChainItem(p.scheduleKey(), p.opDate(), p.version(),
                        p.status().name()))
                .toList();
        return new RescheduleChainResponse(scheduleKey, items);
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
        return planRepo.findPublishedSlots(opDate, List.of(sectionId), List.of(-1L)).stream()
                .map(s -> new PublishedSlotView(s.scheduleKey(), s.trainNo(), s.sectionId(),
                        s.startUtc(), s.endUtc(), s.direction()))
                .toList();
    }

    // ---------- 内部实现 ----------

    private PlanResponse loadPlan(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> notFound(scheduleKey));
        List<OccupancyView> views = planRepo.findOccupancies(plan.id()).stream()
                .map(o -> new OccupancyView(o.trainNo(), o.sectionId(), o.startUtc(), o.endUtc(),
                        o.direction(), o.clearanceVersion()))
                .toList();
        return new PlanResponse(plan.scheduleKey(), plan.opDate(), plan.version(),
                plan.status().name(), views);
    }

    private List<Occupancy> toOccupancies(long planId, List<OccupancyRequest> requests) {
        List<Occupancy> result = new ArrayList<>(requests.size());
        for (int i = 0; i < requests.size(); i++) {
            OccupancyRequest r = requests.get(i);
            result.add(new Occupancy(0L, planId, i, r.trainNo(), r.sectionId(),
                    r.startUtc(), r.endUtc(), r.direction(), null));
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
     * 加载候选占用涉及区段的单线换向配置（未配置的区段不在结果中）。
     */
    private Map<String, SectionConfig> loadConfigs(List<Occupancy> occupancies) {
        List<String> sectionIds = occupancies.stream()
                .map(Occupancy::sectionId)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new))
                .stream().toList();
        return configRepo.findBySectionIds(sectionIds);
    }

    /**
     * 发布/改签成功时，冻结候选计划在各配置区段上的配置版本。
     */
    private void freezeClearanceVersions(long planId, List<Occupancy> occupancies,
                                         Map<String, SectionConfig> configs) {
        occupancies.stream()
                .map(Occupancy::sectionId)
                .distinct()
                .forEach(sectionId -> {
                    SectionConfig config = configs.get(sectionId);
                    if (config != null) {
                        planRepo.freezeClearanceVersion(planId, sectionId, config.version());
                    }
                });
    }

    /**
     * 与其他已发布计划在同日期、同区段上的冲突检测（左闭右开，相邻合法）。
     * 未配置区段仅做候选与已发布的原互斥检测；已配置单线区段把已发布与候选占用
     * 合并排序后逐相邻对裁决：任意方向重叠即互斥冲突，相邻反向对要求
     * 后者开始减前者结束不小于配置的整秒清空间隔，候选内部同样检查。
     *
     * @param excludePlanIds 检测时排除的计划 id（发布为自身；改签为新旧两个计划）
     */
    private List<Map<String, Object>> findSectionConflicts(DayPlan plan, List<Occupancy> occupancies,
                                                           List<Long> excludePlanIds,
                                                           Map<String, SectionConfig> configs) {
        List<String> sectionIds = occupancies.stream()
                .map(Occupancy::sectionId)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new))
                .stream().toList();
        List<PublishedSlot> published = planRepo.findPublishedSlots(plan.opDate(), sectionIds,
                excludePlanIds);
        Map<String, List<PublishedSlot>> publishedBySection = new LinkedHashMap<>();
        for (PublishedSlot slot : published) {
            publishedBySection.computeIfAbsent(slot.sectionId(), k -> new ArrayList<>()).add(slot);
        }
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (String sectionId : sectionIds) {
            List<Occupancy> candidate = occupancies.stream()
                    .filter(o -> o.sectionId().equals(sectionId)).toList();
            List<PublishedSlot> others = publishedBySection.getOrDefault(sectionId, List.of());
            SectionConfig config = configs.get(sectionId);
            if (config == null) {
                conflicts.addAll(findMutualExclusions(plan, candidate, others));
            } else {
                conflicts.addAll(findClearanceConflicts(plan, candidate, others, config));
            }
        }
        return conflicts;
    }

    /**
     * 未配置区段的原互斥：候选与已发布占用重叠（左闭右开）即冲突。
     */
    private List<Map<String, Object>> findMutualExclusions(DayPlan plan, List<Occupancy> candidate,
                                                           List<PublishedSlot> others) {
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (Occupancy o : candidate) {
            for (PublishedSlot slot : others) {
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
                    conflicts.add(detail);
                }
            }
        }
        return conflicts;
    }

    /**
     * 已配置单线区段的合并裁决：已发布与候选占用统一排序后逐相邻对检查。
     * 任一占用缺方向先报 DIRECTION_MISSING；相邻对重叠报 SECTION_CONFLICT；
     * 相邻反向对间隔不足报 CLEARANCE_INSUFFICIENT（携带实际秒数与要求值）。
     */
    private List<Map<String, Object>> findClearanceConflicts(DayPlan plan,
                                                             List<Occupancy> candidate,
                                                             List<PublishedSlot> others,
                                                             SectionConfig config) {
        List<Map<String, Object>> conflicts = new ArrayList<>();
        List<SlotView> merged = new ArrayList<>(candidate.size() + others.size());
        for (Occupancy o : candidate) {
            merged.add(new SlotView(plan.scheduleKey(), o.trainNo(), o.direction(),
                    o.startUtc(), o.endUtc()));
        }
        for (PublishedSlot s : others) {
            merged.add(new SlotView(s.scheduleKey(), s.trainNo(), s.direction(),
                    s.startUtc(), s.endUtc()));
        }
        // 旧无方向占用参与配置区段无法裁决，先报方向缺失，不再猜测方向
        boolean directionMissing = false;
        for (SlotView view : merged) {
            if (view.direction() == null) {
                directionMissing = true;
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("type", "DIRECTION_MISSING");
                detail.put("sectionId", config.sectionId());
                detail.put("scheduleKey", view.scheduleKey());
                detail.put("trainNo", view.trainNo());
                conflicts.add(detail);
            }
        }
        if (directionMissing) {
            return conflicts;
        }
        merged.sort(Comparator.comparing(SlotView::startUtc).thenComparing(SlotView::endUtc));
        SlotView predecessor = null;
        for (SlotView current : merged) {
            if (predecessor != null) {
                if (current.startUtc().isBefore(predecessor.endUtc())) {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("type", "SECTION_CONFLICT");
                    detail.put("sectionId", config.sectionId());
                    detail.put("scheduleKey", current.scheduleKey());
                    detail.put("conflictingScheduleKey", predecessor.scheduleKey());
                    detail.put("trainNo", current.trainNo());
                    detail.put("startUtc", current.startUtc().toString());
                    detail.put("endUtc", current.endUtc().toString());
                    conflicts.add(detail);
                } else if (current.direction() != predecessor.direction()) {
                    long actualSeconds = (current.startUtc().toEpochMilli()
                            - predecessor.endUtc().toEpochMilli()) / 1000;
                    if (actualSeconds < config.clearanceSeconds()) {
                        Map<String, Object> detail = new LinkedHashMap<>();
                        detail.put("type", "CLEARANCE_INSUFFICIENT");
                        detail.put("sectionId", config.sectionId());
                        detail.put("scheduleKey", current.scheduleKey());
                        detail.put("conflictingScheduleKey", predecessor.scheduleKey());
                        detail.put("trainNo", current.trainNo());
                        detail.put("startUtc", current.startUtc().toString());
                        detail.put("endUtc", current.endUtc().toString());
                        detail.put("actualSeconds", actualSeconds);
                        detail.put("requiredSeconds", config.clearanceSeconds());
                        conflicts.add(detail);
                    }
                }
            }
            if (predecessor == null || current.endUtc().isAfter(predecessor.endUtc())) {
                predecessor = current;
            }
        }
        return conflicts;
    }

    /**
     * 合并裁决用的统一占用视图（候选与已发布）。
     */
    private record SlotView(String scheduleKey, String trainNo, Direction direction,
                            Instant startUtc, Instant endUtc) {
    }

    /**
     * 幂等重放：存在记录且参数一致返回首次结果；参数不一致抛 409。
     */
    private Optional<PlanResponse> replayIfPresent(String opType, String requestKey, String hash) {
        return replayIfPresent(opType, requestKey, hash, PlanResponse.class);
    }

    /**
     * 幂等重放（泛型）：存在记录且参数一致返回首次结果；参数不一致抛 409。
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
        return replayIfPresent(opType, requestKey, hash)
                .orElseThrow(() -> conflict("SCHEDULE_KEY_EXISTS", "scheduleKey 已存在"));
    }

    private String hashCreate(CreatePlanRequest req) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("op", OP_CREATE);
        identity.put("scheduleKey", req.scheduleKey());
        identity.put("opDate", req.opDate().toString());
        identity.put("occupancies", occupancyIdentities(req.occupancies()));
        return RequestIdentities.hash(objectMapper, identity);
    }

    private String hashUpdate(String scheduleKey, UpdateOccupanciesRequest req) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("op", OP_UPDATE);
        identity.put("scheduleKey", scheduleKey);
        identity.put("expectedVersion", req.expectedVersion());
        identity.put("occupancies", occupancyIdentities(req.occupancies()));
        return RequestIdentities.hash(objectMapper, identity);
    }

    private String hashAction(String opType, String scheduleKey) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("op", opType);
        identity.put("scheduleKey", scheduleKey);
        return RequestIdentities.hash(objectMapper, identity);
    }

    private String hashReschedule(String oldScheduleKey, RescheduleRequest req) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("op", OP_RESCHEDULE);
        identity.put("oldScheduleKey", oldScheduleKey);
        identity.put("newScheduleKey", req.newScheduleKey());
        identity.put("expectedOldVersion", req.expectedOldVersion());
        identity.put("expectedNewVersion", req.expectedNewVersion());
        return RequestIdentities.hash(objectMapper, identity);
    }

    /**
     * 占用清单的结构化身份：逐条字段独立成键，避免分隔符拼接碰撞。
     */
    private List<Map<String, Object>> occupancyIdentities(List<OccupancyRequest> occupancies) {
        List<Map<String, Object>> result = new ArrayList<>(occupancies.size());
        for (OccupancyRequest o : occupancies) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("trainNo", o.trainNo());
            item.put("sectionId", o.sectionId());
            item.put("startUtc", o.startUtc().toEpochMilli());
            item.put("endUtc", o.endUtc().toEpochMilli());
            item.put("direction", o.direction() == null ? null : o.direction().name());
            result.add(item);
        }
        return result;
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
