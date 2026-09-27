package com.example.starter.plan.service;

import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.PlanCar;
import com.example.starter.plan.model.PlanStatus;
import com.example.starter.plan.model.PublishedSlot;
import com.example.starter.plan.model.RescheduleLink;
import com.example.starter.plan.repo.HazardRepository;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.BatchRescheduleItem;
import com.example.starter.plan.web.dto.BatchRescheduleRequest;
import com.example.starter.plan.web.dto.BatchRescheduleResponse;
import com.example.starter.plan.web.dto.CarRequest;
import com.example.starter.plan.web.dto.CarView;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.OccupancyView;
import com.example.starter.plan.web.dto.PlanResponse;
import com.example.starter.plan.web.dto.PublishedSlotView;
import com.example.starter.plan.web.dto.RescheduleChainItem;
import com.example.starter.plan.web.dto.RescheduleChainResponse;
import com.example.starter.plan.web.dto.RescheduleRequest;
import com.example.starter.plan.web.dto.RescheduleResponse;
import com.example.starter.plan.web.dto.UpdateCarsRequest;
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
    private static final String OP_UPDATE_CARS = "UPDATE_CARS";
    private static final String OP_PUBLISH = "PUBLISH";
    private static final String OP_CANCEL = "CANCEL";
    private static final String OP_RESCHEDULE = "RESCHEDULE";
    private static final String OP_BATCH_RESCHEDULE = "BATCH_RESCHEDULE";

    private final PlanRepository planRepo;
    private final IdempotencyRepository idemRepo;
    private final HazardRepository hazardRepo;
    private final HazardService hazardService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public PlanService(PlanRepository planRepo, IdempotencyRepository idemRepo,
                       HazardRepository hazardRepo, HazardService hazardService,
                       ObjectMapper objectMapper, PlatformTransactionManager txManager) {
        this.planRepo = planRepo;
        this.idemRepo = idemRepo;
        this.hazardRepo = hazardRepo;
        this.hazardService = hazardService;
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
                // 行锁内重检幂等：并发下同键请求在锁释放后应命中首次成功记录
                Optional<PlanResponse> replayInTx =
                        replayIfPresent(OP_UPDATE, req.requestKey(), hash);
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
     * 整体替换草稿计划的危险品编组，版本加一；仅 DRAFT 可改，expectedVersion 必须匹配。
     * 车厢位置重复返回 422 并列出冲突对；互斥间隔与区段通行在发布/改签时联合校验。
     */
    public PlanResponse replaceCars(String scheduleKey, UpdateCarsRequest req) {
        String hash = hashUpdateCars(scheduleKey, req);
        Optional<PlanResponse> replay = replayIfPresent(OP_UPDATE_CARS, req.requestKey(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                DayPlan plan = planRepo.findByKeyForUpdate(scheduleKey)
                        .orElseThrow(() -> notFound(scheduleKey));
                // 行锁内重检幂等：并发下同键请求在锁释放后应命中首次成功记录
                Optional<PlanResponse> replayInTx =
                        replayIfPresent(OP_UPDATE_CARS, req.requestKey(), hash);
                if (replayInTx.isPresent()) {
                    return replayInTx.get();
                }
                if (plan.status() != PlanStatus.DRAFT) {
                    throw conflict("PLAN_STATE_CONFLICT",
                            "仅草稿可修改编组，当前状态: " + plan.status());
                }
                if (req.expectedVersion() != plan.version()) {
                    throw conflict("VERSION_CONFLICT",
                            "expectedVersion=" + req.expectedVersion()
                                    + " 与当前版本 " + plan.version() + " 不一致");
                }
                List<Map<String, Object>> conflicts = findDuplicatePositions(scheduleKey, req.cars());
                if (!conflicts.isEmpty()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "HAZARD_CONFLICT",
                            "车厢位置重复，编组未变更", conflicts);
                }
                List<PlanCar> cars = new ArrayList<>(req.cars().size());
                for (CarRequest c : req.cars()) {
                    cars.add(new PlanCar(0L, plan.id(), c.carPosition(), c.category()));
                }
                cars.sort(Comparator.comparingInt(PlanCar::carPosition));
                long now = System.currentTimeMillis();
                hazardRepo.replaceCars(plan.id(), cars);
                planRepo.updateVersionAndStatus(plan.id(), plan.version() + 1, PlanStatus.DRAFT, now);
                PlanResponse response = loadPlan(scheduleKey);
                idemRepo.insert(OP_UPDATE_CARS, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return resolveDuplicate(OP_UPDATE_CARS, req.requestKey(), hash);
        }
    }

    /**
     * 发布计划：全局发布锁内原子校验本计划列车重叠、跨计划区段重叠与危险品联合校验
     * （完整编组 + 完整区段路径），任一冲突则整张计划保持草稿并抛出 422（携带冲突明细）。
     * 校验通过后固化发布快照（编组、类别、区段限制版本与校验结论），后续配置变更或取消不改写。
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
                HazardService.HazardEvaluation evaluation = hazardService.evaluate(plan, occupancies);
                if (!evaluation.passed()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "HAZARD_CONFLICT",
                            "危险品编组隔离或区段通行校验未通过，计划保持草稿", evaluation.conflicts());
                }
                long now = System.currentTimeMillis();
                planRepo.updateStatus(plan.id(), PlanStatus.PUBLISHED, now);
                hazardService.saveSnapshot(plan, OP_PUBLISH, operator, evaluation, now);
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
                HazardService.HazardEvaluation evaluation =
                        hazardService.evaluate(newPlan, occupancies);
                if (!evaluation.passed()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "HAZARD_CONFLICT",
                            "新草稿危险品编组隔离或区段通行校验未通过，改签未生效",
                            evaluation.conflicts());
                }
                long now = System.currentTimeMillis();
                planRepo.updateStatus(oldPlan.id(), PlanStatus.CANCELLED, now);
                planRepo.updateStatus(newPlan.id(), PlanStatus.PUBLISHED, now);
                planRepo.insertRescheduleLink(oldPlan.id(), newPlan.id(), now);
                hazardService.saveSnapshot(newPlan, OP_RESCHEDULE, req.operator(), evaluation, now);
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
     * 批量原子改签：全局发布锁内先校验全部计划的完整最终状态（状态、版本、运营日、
     * 前后继关联、时隙、危险品编组隔离与区段通行，含批次内新计划两两时隙冲突），
     * 再在一个事务中提交全部取消/发布/关联/快照；任一计划非法则整次不改变时隙或编组。
     */
    public BatchRescheduleResponse batchReschedule(BatchRescheduleRequest req) {
        validateBatchItems(req.items());
        String hash = hashBatchReschedule(req);
        Optional<BatchRescheduleResponse> replay =
                replayIfPresent(OP_BATCH_RESCHEDULE, req.requestKey(), hash, BatchRescheduleResponse.class);
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
                            item.expectedOldVersion(), item.expectedNewVersion(), oldPlan, newPlan);
                    oldPlans.add(oldPlan);
                    newPlans.add(newPlan);
                }
                List<Long> excludedIds = new ArrayList<>();
                oldPlans.forEach(p -> excludedIds.add(p.id()));
                newPlans.forEach(p -> excludedIds.add(p.id()));
                List<HazardService.HazardEvaluation> evaluations = new ArrayList<>();
                List<List<Occupancy>> newOccupancies = new ArrayList<>();
                for (int i = 0; i < req.items().size(); i++) {
                    DayPlan newPlan = newPlans.get(i);
                    List<Occupancy> occupancies = planRepo.findOccupancies(newPlan.id());
                    newOccupancies.add(occupancies);
                    List<Map<String, Object>> conflicts = new ArrayList<>();
                    conflicts.addAll(findTrainOverlaps(newPlan.scheduleKey(), occupancies));
                    // 最终状态校验：批次内全部旧计划将取消、全部新计划将发布，均从第三方冲突集中排除
                    conflicts.addAll(findSectionConflicts(newPlan, occupancies, excludedIds));
                    if (!conflicts.isEmpty()) {
                        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_CONFLICT",
                                "计划 " + newPlan.scheduleKey() + " 存在时隙冲突，批量改签未生效",
                                conflicts);
                    }
                    HazardService.HazardEvaluation evaluation =
                            hazardService.evaluate(newPlan, occupancies);
                    if (!evaluation.passed()) {
                        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "HAZARD_CONFLICT",
                                "计划 " + newPlan.scheduleKey()
                                        + " 危险品编组隔离或区段通行校验未通过，批量改签未生效",
                                evaluation.conflicts());
                    }
                    evaluations.add(evaluation);
                }
                List<Map<String, Object>> crossConflicts = findBatchCrossConflicts(newPlans, newOccupancies);
                if (!crossConflicts.isEmpty()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_CONFLICT",
                            "批次内新计划之间存在时隙冲突，批量改签未生效", crossConflicts);
                }
                long now = System.currentTimeMillis();
                List<RescheduleResponse> items = new ArrayList<>();
                for (int i = 0; i < req.items().size(); i++) {
                    DayPlan oldPlan = oldPlans.get(i);
                    DayPlan newPlan = newPlans.get(i);
                    planRepo.updateStatus(oldPlan.id(), PlanStatus.CANCELLED, now);
                    planRepo.updateStatus(newPlan.id(), PlanStatus.PUBLISHED, now);
                    planRepo.insertRescheduleLink(oldPlan.id(), newPlan.id(), now);
                    hazardService.saveSnapshot(newPlan, OP_BATCH_RESCHEDULE, req.operator(),
                            evaluations.get(i), now);
                    items.add(new RescheduleResponse(loadPlan(oldPlan.scheduleKey()),
                            loadPlan(newPlan.scheduleKey())));
                }
                BatchRescheduleResponse response = new BatchRescheduleResponse(items);
                idemRepo.insert(OP_BATCH_RESCHEDULE, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_BATCH_RESCHEDULE, req.requestKey(), hash,
                    BatchRescheduleResponse.class)
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
        List<CarView> cars = hazardRepo.findCars(plan.id()).stream()
                .map(c -> new CarView(c.carPosition(), c.category()))
                .toList();
        return new PlanResponse(plan.scheduleKey(), plan.opDate(), plan.version(),
                plan.status().name(), views, cars);
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
        FingerprintBuilder fp = new FingerprintBuilder(OP_CREATE);
        fp.field(req.scheduleKey()).field(req.opDate().toString());
        appendOccupancies(fp, req.occupancies());
        return fp.sha256();
    }

    private String hashUpdate(String scheduleKey, UpdateOccupanciesRequest req) {
        FingerprintBuilder fp = new FingerprintBuilder(OP_UPDATE);
        fp.field(scheduleKey).field(String.valueOf(req.expectedVersion()));
        appendOccupancies(fp, req.occupancies());
        return fp.sha256();
    }

    private String hashUpdateCars(String scheduleKey, UpdateCarsRequest req) {
        FingerprintBuilder fp = new FingerprintBuilder(OP_UPDATE_CARS);
        fp.field(scheduleKey).field(req.operator()).field(String.valueOf(req.expectedVersion()));
        for (CarRequest c : req.cars()) {
            fp.field(String.valueOf(c.carPosition())).field(c.category());
        }
        return fp.sha256();
    }

    private String hashAction(String opType, String scheduleKey, String operator) {
        return new FingerprintBuilder(opType).field(scheduleKey).field(operator).sha256();
    }

    private String hashReschedule(String oldScheduleKey, RescheduleRequest req) {
        return new FingerprintBuilder(OP_RESCHEDULE)
                .field(oldScheduleKey).field(req.newScheduleKey())
                .field(String.valueOf(req.expectedOldVersion()))
                .field(String.valueOf(req.expectedNewVersion()))
                .field(req.operator())
                .sha256();
    }

    private String hashBatchReschedule(BatchRescheduleRequest req) {
        FingerprintBuilder fp = new FingerprintBuilder(OP_BATCH_RESCHEDULE);
        fp.field(req.operator());
        for (BatchRescheduleItem item : req.items()) {
            fp.field(item.oldScheduleKey()).field(item.newScheduleKey())
                    .field(String.valueOf(item.expectedOldVersion()))
                    .field(String.valueOf(item.expectedNewVersion()));
        }
        return fp.sha256();
    }

    private void appendOccupancies(FingerprintBuilder fp, List<OccupancyRequest> occupancies) {
        for (OccupancyRequest o : occupancies) {
            fp.field(o.trainNo()).field(o.sectionId())
                    .field(String.valueOf(o.startUtc().toEpochMilli()))
                    .field(String.valueOf(o.endUtc().toEpochMilli()));
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

    /**
     * 批量改签参数级校验：新旧键不得相同，批次内旧键、新键均不得重复（含新旧交叉重复）。
     */
    private void validateBatchItems(List<BatchRescheduleItem> items) {
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (BatchRescheduleItem item : items) {
            if (item.oldScheduleKey().equals(item.newScheduleKey())) {
                throw badRequest("新旧计划必须不同: " + item.oldScheduleKey());
            }
            if (seen.putIfAbsent(item.oldScheduleKey(), Boolean.TRUE) != null) {
                throw badRequest("批次内计划重复: " + item.oldScheduleKey());
            }
            if (seen.putIfAbsent(item.newScheduleKey(), Boolean.TRUE) != null) {
                throw badRequest("批次内计划重复: " + item.newScheduleKey());
            }
        }
    }

    /**
     * 单对改签校验：状态、期望版本、运营日一致性与前后继关联唯一性。
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
     * 批次内新计划两两时隙冲突检测（最终状态校验：全部新计划将同时发布）。
     */
    private List<Map<String, Object>> findBatchCrossConflicts(List<DayPlan> newPlans,
                                                              List<List<Occupancy>> newOccupancies) {
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (int i = 0; i < newPlans.size(); i++) {
            for (int j = i + 1; j < newPlans.size(); j++) {
                for (Occupancy a : newOccupancies.get(i)) {
                    for (Occupancy b : newOccupancies.get(j)) {
                        boolean overlap = a.sectionId().equals(b.sectionId())
                                && a.startUtc().isBefore(b.endUtc())
                                && b.startUtc().isBefore(a.endUtc());
                        if (overlap) {
                            Map<String, Object> detail = new LinkedHashMap<>();
                            detail.put("type", "SECTION_CONFLICT");
                            detail.put("sectionId", a.sectionId());
                            detail.put("scheduleKey", newPlans.get(i).scheduleKey());
                            detail.put("conflictingScheduleKey", newPlans.get(j).scheduleKey());
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
     * 编组登记请求的车厢位置重复检测：每个重复位置产生一条冲突对明细。
     */
    private List<Map<String, Object>> findDuplicatePositions(String scheduleKey,
                                                             List<CarRequest> cars) {
        Map<Integer, List<String>> byPosition = new java.util.TreeMap<>();
        for (CarRequest car : cars) {
            byPosition.computeIfAbsent(car.carPosition(), k -> new ArrayList<>()).add(car.category());
        }
        List<Map<String, Object>> conflicts = new ArrayList<>();
        byPosition.forEach((position, categories) -> {
            if (categories.size() > 1) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("type", "DUPLICATE_CAR_POSITION");
                detail.put("scheduleKey", scheduleKey);
                detail.put("carPosition", position);
                detail.put("categories", categories.stream().sorted().toList());
                detail.put("actualCount", categories.size());
                detail.put("requiredCount", 1);
                conflicts.add(detail);
            }
        });
        return conflicts;
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
