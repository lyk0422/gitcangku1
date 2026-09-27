package com.example.starter.plan.service;

import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.HazardCar;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.PlanStatus;
import com.example.starter.plan.model.PublishSnapshot;
import com.example.starter.plan.model.PublishedSlot;
import com.example.starter.plan.model.RescheduleLink;
import com.example.starter.plan.repo.HazardRepository;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.BatchRescheduleItem;
import com.example.starter.plan.web.dto.BatchRescheduleRequest;
import com.example.starter.plan.web.dto.BatchRescheduleResponse;
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
    private static final String OP_BATCH_RESCHEDULE = "BATCH_RESCHEDULE";

    private final PlanRepository planRepo;
    private final IdempotencyRepository idemRepo;
    private final HazardRepository hazardRepo;
    private final HazardValidator hazardValidator;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public PlanService(PlanRepository planRepo, IdempotencyRepository idemRepo,
                       HazardRepository hazardRepo, HazardValidator hazardValidator,
                       ObjectMapper objectMapper, PlatformTransactionManager txManager) {
        this.planRepo = planRepo;
        this.idemRepo = idemRepo;
        this.hazardRepo = hazardRepo;
        this.hazardValidator = hazardValidator;
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
     * 发布计划：全局发布锁内原子校验本计划列车重叠、跨计划区段重叠，
     * 并在完整编组与完整区段路径上校验危险品隔离与区段通行；
     * 任一冲突则整张计划保持草稿并抛出 422（携带冲突明细）。
     * 校验通过后在同一事务写入不可变发布快照（编组、类别、区段限制版本与校验结论）。
     */
    public PlanResponse publish(String scheduleKey, String requestKey, String operator) {
        String hash = hashAction(OP_PUBLISH, scheduleKey, operator);
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
                List<Map<String, Object>> conflicts = new ArrayList<>();
                conflicts.addAll(findTrainOverlaps(scheduleKey, occupancies));
                conflicts.addAll(findSectionConflicts(plan, occupancies, List.of(plan.id())));
                if (!conflicts.isEmpty()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_CONFLICT",
                            "存在时隙冲突，计划保持草稿", conflicts);
                }
                // 危险品联合校验：配置锁内读取版本，快照固化同一版本
                List<HazardCar> cars = hazardRepo.findHazardCars(plan.id());
                HazardRepository.ConfigVersions versions = hazardRepo.lockConfigVersions();
                List<Map<String, Object>> hazardConflicts =
                        hazardValidator.validateAll(scheduleKey, occupancies, cars);
                if (!hazardConflicts.isEmpty()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "HAZARD_CONFLICT",
                            "危险品编组或区段通行校验未通过，计划保持草稿", hazardConflicts);
                }
                long now = System.currentTimeMillis();
                planRepo.updateStatus(plan.id(), PlanStatus.PUBLISHED, now);
                hazardRepo.insertSnapshot(buildSnapshot(plan, operator, requestKey, "PUBLISH",
                        versions, occupancies, cars, now));
                PlanResponse response = loadPlan(scheduleKey);
                idemRepo.insert(OP_PUBLISH, requestKey, hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return resolveDuplicate(OP_PUBLISH, requestKey, hash);
        }
    }

    /**
     * 取消已发布计划：时隙立即释放，历史计划、占用与发布快照保留不改写。
     */
    public PlanResponse cancel(String scheduleKey, String requestKey, String operator) {
        String hash = hashAction(OP_CANCEL, scheduleKey, operator);
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
                DayPlan oldPlan = planRepo.findByKeyForUpdate(oldScheduleKey)
                        .orElseThrow(() -> notFound(oldScheduleKey));
                DayPlan newPlan = planRepo.findByKeyForUpdate(req.newScheduleKey())
                        .orElseThrow(() -> notFound(req.newScheduleKey()));
                validateReschedulePair(oldScheduleKey, req.newScheduleKey(),
                        req.expectedOldVersion(), req.expectedNewVersion(), oldPlan, newPlan);
                List<Occupancy> occupancies = planRepo.findOccupancies(newPlan.id());
                List<Map<String, Object>> conflicts = new ArrayList<>();
                conflicts.addAll(findTrainOverlaps(req.newScheduleKey(), occupancies));
                // 仅排除旧计划与自身占用，第三方已发布计划照常参与冲突裁决
                conflicts.addAll(findSectionConflicts(newPlan, occupancies,
                        List.of(newPlan.id(), oldPlan.id())));
                if (!conflicts.isEmpty()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_CONFLICT",
                            "新草稿存在时隙冲突，改签未生效", conflicts);
                }
                // 危险品联合校验：新计划在完整编组与完整区段路径上校验
                List<HazardCar> cars = hazardRepo.findHazardCars(newPlan.id());
                HazardRepository.ConfigVersions versions = hazardRepo.lockConfigVersions();
                List<Map<String, Object>> hazardConflicts =
                        hazardValidator.validateAll(req.newScheduleKey(), occupancies, cars);
                if (!hazardConflicts.isEmpty()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "HAZARD_CONFLICT",
                            "新草稿危险品编组或区段通行校验未通过，改签未生效", hazardConflicts);
                }
                long now = System.currentTimeMillis();
                planRepo.updateStatus(oldPlan.id(), PlanStatus.CANCELLED, now);
                planRepo.updateStatus(newPlan.id(), PlanStatus.PUBLISHED, now);
                planRepo.insertRescheduleLink(oldPlan.id(), newPlan.id(), now);
                hazardRepo.insertSnapshot(buildSnapshot(newPlan, req.operator(), req.requestKey(),
                        "RESCHEDULE", versions, occupancies, cars, now));
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
     * 批量原子改签：全局发布锁内先校验整批完整最终状态（状态、版本、运营日、关联、
     * 时隙冲突、批内新计划两两冲突、危险品编组与区段通行），任一计划非法则整批
     * 不改变时隙或编组；全部通过后在一个事务中提交所有取消、发布、关联与快照。
     */
    public BatchRescheduleResponse batchReschedule(BatchRescheduleRequest req) {
        validateBatchItems(req);
        String hash = hashBatchReschedule(req);
        Optional<BatchRescheduleResponse> replay =
                replayIfPresent(OP_BATCH_RESCHEDULE, req.requestKey(), hash,
                        BatchRescheduleResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                List<DayPlan> oldPlans = new ArrayList<>();
                List<DayPlan> newPlans = new ArrayList<>();
                for (BatchRescheduleItem item : req.items()) {
                    DayPlan oldPlan = planRepo.findByKeyForUpdate(item.oldScheduleKey())
                            .orElseThrow(() -> notFound(item.oldScheduleKey()));
                    DayPlan newPlan = planRepo.findByKeyForUpdate(item.newScheduleKey())
                            .orElseThrow(() -> notFound(item.newScheduleKey()));
                    validateReschedulePair(item.oldScheduleKey(), item.newScheduleKey(),
                            item.expectedOldVersion(), item.expectedNewVersion(),
                            oldPlan, newPlan);
                    oldPlans.add(oldPlan);
                    newPlans.add(newPlan);
                }
                List<Long> excludeIds = new ArrayList<>();
                oldPlans.forEach(p -> excludeIds.add(p.id()));
                newPlans.forEach(p -> excludeIds.add(p.id()));
                HazardRepository.ConfigVersions versions = hazardRepo.lockConfigVersions();
                List<Map<String, Object>> conflicts = new ArrayList<>();
                Map<Long, List<Occupancy>> occupanciesByNew = new LinkedHashMap<>();
                Map<Long, List<HazardCar>> carsByNew = new LinkedHashMap<>();
                for (DayPlan newPlan : newPlans) {
                    List<Occupancy> occupancies = planRepo.findOccupancies(newPlan.id());
                    occupanciesByNew.put(newPlan.id(), occupancies);
                    List<HazardCar> cars = hazardRepo.findHazardCars(newPlan.id());
                    carsByNew.put(newPlan.id(), cars);
                    conflicts.addAll(findTrainOverlaps(newPlan.scheduleKey(), occupancies));
                    // 排除整批新旧计划，第三方已发布计划照常参与冲突裁决
                    conflicts.addAll(findSectionConflicts(newPlan, occupancies, excludeIds));
                    conflicts.addAll(hazardValidator.validateAll(
                            newPlan.scheduleKey(), occupancies, cars));
                }
                conflicts.addAll(findIntraBatchConflicts(newPlans, occupanciesByNew));
                if (!conflicts.isEmpty()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "BATCH_CONFLICT",
                            "批量改签含非法计划，整批未生效", conflicts);
                }
                long now = System.currentTimeMillis();
                List<BatchRescheduleResponse.BatchItemResult> results = new ArrayList<>();
                for (int i = 0; i < req.items().size(); i++) {
                    DayPlan oldPlan = oldPlans.get(i);
                    DayPlan newPlan = newPlans.get(i);
                    planRepo.updateStatus(oldPlan.id(), PlanStatus.CANCELLED, now);
                    planRepo.updateStatus(newPlan.id(), PlanStatus.PUBLISHED, now);
                    planRepo.insertRescheduleLink(oldPlan.id(), newPlan.id(), now);
                    hazardRepo.insertSnapshot(buildSnapshot(newPlan, req.operator(),
                            req.requestKey(), "BATCH_RESCHEDULE", versions,
                            occupanciesByNew.get(newPlan.id()), carsByNew.get(newPlan.id()), now));
                    results.add(new BatchRescheduleResponse.BatchItemResult(
                            oldPlan.scheduleKey(), newPlan.scheduleKey(),
                            loadPlan(oldPlan.scheduleKey()), loadPlan(newPlan.scheduleKey())));
                }
                BatchRescheduleResponse response =
                        new BatchRescheduleResponse(req.requestKey(), results);
                idemRepo.insert(OP_BATCH_RESCHEDULE, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_BATCH_RESCHEDULE, req.requestKey(), hash,
                    BatchRescheduleResponse.class)
                    .orElseThrow(() -> conflict("LINK_CONFLICT", "批量改签前后继关联冲突"));
        }
    }

    /**
     * 批量改签结构校验：项内新旧必须不同；旧计划集合、新计划集合各自不重复且互不相交。
     */
    private void validateBatchItems(BatchRescheduleRequest req) {
        java.util.Set<String> oldKeys = new java.util.HashSet<>();
        java.util.Set<String> newKeys = new java.util.HashSet<>();
        for (BatchRescheduleItem item : req.items()) {
            if (item.oldScheduleKey().equals(item.newScheduleKey())) {
                throw badRequest("新旧计划必须不同: " + item.oldScheduleKey());
            }
            if (!oldKeys.add(item.oldScheduleKey())) {
                throw badRequest("批量改签中旧计划重复: " + item.oldScheduleKey());
            }
            if (!newKeys.add(item.newScheduleKey())) {
                throw badRequest("批量改签中新计划重复: " + item.newScheduleKey());
            }
        }
        for (String oldKey : oldKeys) {
            if (newKeys.contains(oldKey)) {
                throw badRequest("同一计划不能既为旧计划又为新计划: " + oldKey);
            }
        }
    }

    /**
     * 单项改签校验：状态、期望版本、运营日一致性与前后继关联唯一性。
     */
    private void validateReschedulePair(String oldScheduleKey, String newScheduleKey,
                                        int expectedOldVersion, int expectedNewVersion,
                                        DayPlan oldPlan, DayPlan newPlan) {
        if (oldPlan.status() != PlanStatus.PUBLISHED) {
            throw conflict("PLAN_STATE_CONFLICT",
                    "仅已发布计划可改签，旧计划当前状态: " + oldPlan.status());
        }
        if (newPlan.status() != PlanStatus.DRAFT) {
            throw conflict("PLAN_STATE_CONFLICT",
                    "改签新计划必须为草稿，当前状态: " + newPlan.status());
        }
        if (expectedOldVersion != oldPlan.version()) {
            throw conflict("VERSION_CONFLICT",
                    "expectedOldVersion=" + expectedOldVersion
                            + " 与旧计划当前版本 " + oldPlan.version() + " 不一致");
        }
        if (expectedNewVersion != newPlan.version()) {
            throw conflict("VERSION_CONFLICT",
                    "expectedNewVersion=" + expectedNewVersion
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
            throw conflict("LINK_CONFLICT", "新计划已存在直接前驱: " + newScheduleKey);
        }
    }

    /**
     * 批内新计划两两区段重叠检测（左闭右开）：整批提交后这些计划同时发布，
     * 彼此之间同区段重叠同样非法。
     */
    private List<Map<String, Object>> findIntraBatchConflicts(
            List<DayPlan> newPlans, Map<Long, List<Occupancy>> occupanciesByNew) {
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (int i = 0; i < newPlans.size(); i++) {
            for (int j = i + 1; j < newPlans.size(); j++) {
                DayPlan a = newPlans.get(i);
                DayPlan b = newPlans.get(j);
                for (Occupancy oa : occupanciesByNew.get(a.id())) {
                    for (Occupancy ob : occupanciesByNew.get(b.id())) {
                        boolean overlap = oa.sectionId().equals(ob.sectionId())
                                && oa.startUtc().isBefore(ob.endUtc())
                                && ob.startUtc().isBefore(oa.endUtc());
                        if (overlap) {
                            Map<String, Object> detail = new LinkedHashMap<>();
                            detail.put("type", "SECTION_CONFLICT");
                            detail.put("sectionId", oa.sectionId());
                            detail.put("scheduleKey", a.scheduleKey());
                            detail.put("conflictingScheduleKey", b.scheduleKey());
                            detail.put("trainNo", oa.trainNo());
                            detail.put("startUtc", oa.startUtc().toString());
                            detail.put("endUtc", oa.endUtc().toString());
                            conflicts.add(detail);
                        }
                    }
                }
            }
        }
        return conflicts;
    }

    /**
     * 构建发布快照：固化完整编组（按车厢位置升序）、完整区段路径（字典序）、
     * 配置版本与校验结论；仅校验通过才调用，结论固定 PASSED。
     */
    private PublishSnapshot buildSnapshot(DayPlan plan, String operator, String requestKey,
                                          String source,
                                          HazardRepository.ConfigVersions versions,
                                          List<Occupancy> occupancies, List<HazardCar> cars,
                                          long now) {
        var formation = HazardService.toCarViews(cars);
        List<String> sections = HazardService.sectionPath(occupancies);
        return new PublishSnapshot(0L, plan.id(), plan.scheduleKey(), plan.opDate(), operator,
                requestKey, source, versions.ruleVersion(), versions.sectionVersion(),
                toJson(formation), toJson(sections), "PASSED", now);
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
                        s.startUtc(), s.endUtc()))
                .toList();
    }

    // ---------- 内部实现 ----------

    private PlanResponse loadPlan(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> notFound(scheduleKey));
        List<OccupancyView> views = planRepo.findOccupancies(plan.id()).stream()
                .map(o -> new OccupancyView(o.trainNo(), o.sectionId(), o.startUtc(), o.endUtc()))
                .toList();
        return new PlanResponse(plan.scheduleKey(), plan.opDate(), plan.version(),
                plan.status().name(), views);
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
     *
     * @param excludePlanIds 检测时排除的计划 id（发布为自身；改签为新旧两个计划）
     */
    private List<Map<String, Object>> findSectionConflicts(DayPlan plan, List<Occupancy> occupancies,
                                                           List<Long> excludePlanIds) {
        List<String> sectionIds = occupancies.stream()
                .map(Occupancy::sectionId)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new))
                .stream().toList();
        List<PublishedSlot> published = planRepo.findPublishedSlots(plan.opDate(), sectionIds,
                excludePlanIds);
        List<Map<String, Object>> conflicts = new ArrayList<>();
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
                    conflicts.add(detail);
                }
            }
        }
        return conflicts;
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
        Fingerprint fp = Fingerprint.of(OP_CREATE)
                .field(req.operator())
                .field(req.scheduleKey())
                .field(req.opDate().toString());
        appendOccupancies(fp, req.occupancies());
        return fp.sha256();
    }

    private String hashUpdate(String scheduleKey, UpdateOccupanciesRequest req) {
        Fingerprint fp = Fingerprint.of(OP_UPDATE)
                .field(req.operator())
                .field(scheduleKey)
                .field(req.expectedVersion());
        appendOccupancies(fp, req.occupancies());
        return fp.sha256();
    }

    private String hashAction(String opType, String scheduleKey, String operator) {
        return Fingerprint.of(opType).field(operator).field(scheduleKey).sha256();
    }

    private String hashReschedule(String oldScheduleKey, RescheduleRequest req) {
        return Fingerprint.of(OP_RESCHEDULE)
                .field(req.operator())
                .field(oldScheduleKey)
                .field(req.newScheduleKey())
                .field(req.expectedOldVersion())
                .field(req.expectedNewVersion())
                .sha256();
    }

    private String hashBatchReschedule(BatchRescheduleRequest req) {
        Fingerprint fp = Fingerprint.of(OP_BATCH_RESCHEDULE).field(req.operator());
        // 批量项按请求顺序编码（结果顺序与请求一致，顺序影响响应）
        List<String> items = req.items().stream()
                .map(item -> Fingerprint.of("ITEM")
                        .field(item.oldScheduleKey())
                        .field(item.newScheduleKey())
                        .field(item.expectedOldVersion())
                        .field(item.expectedNewVersion())
                        .encode())
                .toList();
        return fp.fields(items).sha256();
    }

    /**
     * 占用清单按提交顺序编码（seq 有顺序语义），每条占用的各字段长度前缀拼接。
     */
    private void appendOccupancies(Fingerprint fp, List<OccupancyRequest> occupancies) {
        List<String> items = occupancies.stream()
                .map(o -> Fingerprint.of("OCC")
                        .field(o.trainNo())
                        .field(o.sectionId())
                        .field(o.startUtc().toEpochMilli())
                        .field(o.endUtc().toEpochMilli())
                        .encode())
                .toList();
        fp.fields(items);
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
